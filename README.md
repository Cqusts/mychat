# EasyChat

一个仿微信的全栈即时通讯应用，基于 Spring Boot + Netty + Vue 3 + Electron 构建，支持单聊、群聊、AI 智能助手等功能。

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.2.5 / Java 17 |
| 实时通信 | Netty 4.1 WebSocket (主从 Reactor 模型) |
| 跨节点广播 | Redisson 3.27 RTopic 发布订阅 |
| 数据库 | MySQL 8.0 + MyBatis |
| 缓存 | Redis (Spring Data Redis + Redisson) |
| AI 对话 | Spring AI (兼容 OpenAI / DeepSeek / 通义千问 / 智谱 GLM) |
| 前端框架 | Vue 3 + Element Plus + Pinia |
| 桌面端 | Electron 25 + electron-vite |

## 项目结构

```
mychat/
├── easychat-java/          # 后端 Spring Boot 服务
│   ├── src/main/java/com/easychat/
│   │   ├── controller/     # REST API 控制器
│   │   ├── service/        # 业务逻辑层
│   │   ├── websocket/      # Netty WebSocket 服务 & 消息广播
│   │   ├── redis/          # Redis 缓存操作
│   │   ├── entity/         # 实体类 & DTO
│   │   └── mappers/        # MyBatis Mapper
│   └── src/main/resources/
│       └── application.properties
├── easychat-front/         # 前端 Electron + Vue 3 桌面应用
│   └── src/
│       ├── main/           # Electron 主进程
│       ├── renderer/       # Vue 3 渲染进程
│       └── preload/        # 预加载脚本
├── benchmark/              # 性能基准测试工具
├── easychat.sql            # 数据库初始化脚本
└── README.md
```

## 核心功能

- **单聊 & 群聊** — 文字、文件消息，消息历史记录
- **好友管理** — 添加好友、好友申请审批、拉黑
- **群组管理** — 建群、解散、邀请/移除成员、群公告
- **AI 智能助手** — 集成大模型 API，支持多轮对话
- **实时推送** — Netty WebSocket 长连接，心跳保活
- **跨节点广播** — Redis RTopic 发布订阅，支持多实例部署
- **桌面客户端** — Electron 打包，支持 Windows / macOS / Linux

## 本地部署

### 环境要求

| 依赖 | 版本 |
|------|------|
| JDK | 17+ |
| Maven | 3.8+ |
| Node.js | 16+ |
| MySQL | 5.7+ / 8.0+ |
| Redis | 6.0+ |

### 1. 初始化数据库

```bash
# 创建数据库并导入表结构
mysql -u root -p -e "CREATE DATABASE easychat CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
mysql -u root -p easychat < easychat.sql
```

### 2. 配置后端

编辑 `easychat-java/src/main/resources/application.properties`：

```properties
# 数据库连接（按实际情况修改）
spring.datasource.url=jdbc:mysql://127.0.0.1:3306/easychat?serverTimezone=GMT%2B8&useUnicode=true&characterEncoding=utf8
spring.datasource.username=root
spring.datasource.password=你的密码

# Redis
spring.data.redis.host=127.0.0.1
spring.data.redis.port=6379

# 文件存储目录
project.folder=D:/easychat/

# AI 大模型（可选，不配置则 AI 助手不可用）
spring.ai.openai.api-key=你的API密钥
spring.ai.openai.base-url=https://api.deepseek.com
spring.ai.openai.chat.options.model=deepseek-chat
```

支持的 AI 服务商：

| 服务商 | base-url | model |
|--------|----------|-------|
| OpenAI | `https://api.openai.com` | gpt-3.5-turbo / gpt-4o |
| DeepSeek | `https://api.deepseek.com` | deepseek-chat |
| 通义千问 | `https://dashscope.aliyuncs.com/compatible-mode` | qwen-turbo |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas` | glm-4-flash |

### 3. 启动后端

```bash
cd easychat-java
mvn clean package -DskipTests
java -jar target/easychat-java-1.0.0.jar
```

启动成功后：
- HTTP API: `http://localhost:5050/api`
- WebSocket: `ws://localhost:5051/ws`

### 4. 启动前端

```bash
cd easychat-front
npm install
npm run dev
```

Electron 窗口会自动弹出，连接本地后端服务。

### 5. 打包桌面客户端（可选）

```bash
# Windows
npm run build:win

# macOS
npm run build:mac

# Linux
npm run build:linux
```

打包产物在 `easychat-front/installPackages/` 目录下。

## 架构说明

```
客户端 (Electron + Vue 3)
  │
  ├── HTTP REST API ──→ Spring Boot (:5050)
  │                        ├── 业务逻辑处理
  │                        ├── MySQL 持久化
  │                        └── RTopic.publish() ──→ Redis
  │
  └── WebSocket ──→ Netty (:5051)                     │
                     ├── 心跳检测 (IdleStateHandler)    │
                     ├── Channel/ChannelGroup 管理      │
                     └── MessageHandler ←── RTopic 订阅 ┘
                          └── 推送消息到客户端
```

**消息流转**：客户端发送消息 → REST API 处理并存库 → RTopic 发布到 Redis → 所有节点的 MessageHandler 收到广播 → 通过 ChannelGroup 推送给目标用户的 WebSocket 连接。
