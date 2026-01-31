# EasyChat 性能基准测试

针对简历中两个核心指标的实测工具：
- **端到端消息延迟 < 100ms** (RTopic → WebSocket推送)
- **单机 5000+ 并发长连接**

## 快速开始

### 1. 编译

```bash
cd benchmark
mvn clean package -q
```

### 2. 前提条件

- EasyChat 服务端已启动 (默认 HTTP :5050, WS :5051)
- Redis 已启动 (默认 :6379)
- 系统 `ulimit -n` 已调高 (建议 >= 65535)

```bash
# 调高文件描述符限制 (当前会话)
ulimit -n 65535

# 永久生效: 编辑 /etc/security/limits.conf
# * soft nofile 65535
# * hard nofile 65535
```

### 3. 生成测试 Token

```bash
java -jar target/easychat-benchmark-1.0.0.jar gen-tokens \
  --redis-url redis://localhost:6379 \
  --count 5000 \
  --output tokens.txt
```

> **序列化兼容性说明**: gen-tokens 使用 Redisson 直接写入 Redis。
> 如果服务端的 `RedisComponet.getTokenUserInfoDto()` 使用 Spring RedisTemplate + Jackson 序列化，
> 可能存在格式不兼容。此时需要手动通过服务端接口批量注册用户并登录获取 token。
> 或者修改 `TokenGenerator` 使用与服务端相同的 RedisTemplate 序列化方式。

### 4. 测试并发连接 (5000+)

```bash
java -jar target/easychat-benchmark-1.0.0.jar concurrent \
  --ws-url ws://localhost:5051/ws \
  --tokens-file tokens.txt \
  --connections 5000 \
  --ramp-up 200 \
  --hold-seconds 60 \
  --heartbeat-interval 30
```

**输出示例:**
```
============ 最终测试结果 ============
  目标连接数:   5000
  成功建立:     5000
  连接失败:     0
  保持期断开:   12
  最终存活:     4988
  存活率:       99.8%
  连接建立耗时: 25.3 秒
======================================
>>> 结论: 单机支撑 5000+ 并发长连接 -- 通过
```

**参数调优:**
- `--ramp-up 200`: 每秒建立200个连接，避免瞬间压垮服务端。可根据机器配置调整。
- `--hold-seconds 60`: 全部连接建立后保持60秒，观察连接存活率。
- `--heartbeat-interval 30`: 心跳间隔，服务端 IdleStateHandler 超时为60秒，所以30秒发一次。

### 5. 测试端到端消息延迟

```bash
java -jar target/easychat-benchmark-1.0.0.jar latency \
  --ws-url ws://localhost:5051/ws \
  --redis-url redis://localhost:6379 \
  --tokens-file tokens.txt \
  --messages 1000 \
  --interval-ms 100
```

**输出示例:**
```
============ 延迟测试结果 ============
  发送消息数:    1000
  接收消息数:    998
  丢失消息:      2
  测试总耗时:    103.2 秒
  吞吐量:        10 msg/s

  端到端延迟统计 (从RTopic.publish到客户端收到):
    最小值:  1.23 ms
    P50:     3.45 ms
    P90:     8.72 ms
    P95:     15.31 ms
    P99:     42.18 ms
    最大值:  87.65 ms
    平均值:  5.12 ms

  >>> 结论: P99 < 100ms -- 端到端延迟达标
======================================
```

**延迟测试原理:**
```
测试客户端 RTopic.publish(带nanoTime时间戳)
    ↓
Redis 广播到所有订阅节点
    ↓
服务端 MessageHandler.lisMessage() 收到
    ↓
channelContextUtils.sendMessage() → send2User()
    ↓
Netty Channel.writeAndFlush()
    ↓
测试客户端 WebSocket 收到, 计算 System.nanoTime() - 发送时的nanoTime
```

## 注意事项

### Token 兼容性问题

服务端 `RedisComponet` 使用 Spring `RedisTemplate` 存取 token，而本工具使用 Redisson。
两者的 Redis 序列化方式可能不同。如果测试时 WebSocket 连接被拒(token 无效)，有两种解决方案：

**方案A**: 修改服务端 RedisConfig，让 Redisson 的编解码与 RedisTemplate 一致
**方案B**: 写一个 Spring Boot Test，用服务端同款 RedisTemplate 批量写入 token

### 系统调优 (Linux)

```bash
# 检查当前限制
ulimit -n

# 调大 TCP 相关内核参数
sudo sysctl -w net.ipv4.ip_local_port_range="1024 65535"
sudo sysctl -w net.core.somaxconn=65535
sudo sysctl -w net.ipv4.tcp_max_syn_backlog=65535
```

### 服务端 JVM 调优

```bash
java -Xms1g -Xmx2g \
  -XX:+UseG1GC \
  -Dio.netty.leakDetection.level=disabled \
  -jar easychat.jar
```
