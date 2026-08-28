# EasyChat

一个仿微信的全栈即时通讯应用，在完整的 IM 系统之上构建了 AI Agent 能力：AI 助手可以加为好友私聊、可以被拉进群、能调用业务工具查数据，也能在群里被 @ 触发并互相接力讨论。

基于 Spring Boot + Netty + Spring AI + Vue 3 + Electron 构建。

## 这个项目和"又一个 ChatGPT 套壳"的区别

大多数 AI 应用是「一个输入框 + 一个模型」。这个项目里，**AI 助手被建模成一等公民的用户**：

- 有自己的 `user_id` 和昵称，在 `user_info` 表里有真实记录
- 有个性签名，正常出现在群成员列表里
- 收发消息走的是和真人**完全相同**的链路（`saveMessage` → RTopic 广播 → WebSocket 推送）
- 唯一的区别只有一个：回复内容由大模型生成

助手有两种触发场景，行为不同：

| 场景 | 触发方式 | 人设 | 工具调用 | 上下文 |
|------|----------|------|----------|--------|
| **私聊** | 加为好友后直接对话 | 各自的 `prompt` | ✅ 以提问者身份执行 | ✅ Redis 多轮记忆，按(用户,助手)隔离 |
| **群聊** | 群里被 @ 昵称 | 各自的 `prompt` + 群聊引导 | ❌ 见"已知限制" | 群聊最近记录渲染成 transcript |

系统里内置了一个单聊机器人（`Constants.ROBOT_UID`，注册时自动加为好友），另外可以在配置里定义任意多个助手。两者私聊行为一致，区别只是内置机器人用全局的 `ai.chat.system-prompt` 作人设。

这个抽象带来三件普通 AI 应用做不到的事：

| 能力 | 说明 |
|------|------|
| **多 Agent 群聊协作** | 一个群里拉进多个人设不同的助手，它们能看到彼此的发言并互相接力。协作过程就是一个能直接看的群聊界面，而不是日志里的 trace |
| **异步送达** | AI 回复不占用 HTTP 请求：请求立即返回，回复稍后经长连接推送。这也让"助手跑完长任务再主动发消息"成为架构上的自然延伸（见后续计划） |
| **真实业务工具** | 助手能调用系统自身的能力查好友、查群、搜聊天记录，而不是接一个天气 API 做演示 |

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.2.5 / Java 17 |
| 实时通信 | Netty 4.1 WebSocket（主从 Reactor 模型） |
| 跨节点广播 | Redisson 3.27 RTopic 发布订阅 |
| 数据库 | MySQL 8.0 + MyBatis |
| 缓存 | Redis（Spring Data Redis + Redisson） |
| AI 框架 | Spring AI 1.0.0-M6（兼容 OpenAI / DeepSeek / 通义千问 / 智谱 GLM） |
| 前端框架 | Vue 3 + Element Plus + Pinia |
| 桌面端 | Electron 25 + electron-vite |

## 功能

### IM 基础能力

- **单聊 & 群聊** — 文字、图片、视频、文件消息，消息历史记录
- **好友管理** — 添加好友、好友申请审批、拉黑
- **群组管理** — 建群、解散、邀请/移除成员、群公告
- **实时推送** — Netty WebSocket 长连接，心跳保活，断线重连
- **跨节点广播** — Redis RTopic 发布订阅，支持多实例部署
- **管理后台** — 用户管理、群管理、靓号、系统设置、客户端版本更新
- **桌面客户端** — Electron 打包，支持 Windows / macOS / Linux

### AI Agent 能力

- **流式输出** — 打字机效果，片段按 20 字符 / 80ms 聚合后推送
- **异步执行** — 大模型调用走独立线程池，不占用 HTTP 工作线程
- **多轮记忆** — 对话历史存 Redis，支持多实例部署
- **工具调用** — 助手可调用业务工具查好友、查群、搜聊天记录、查当前时间
- **过程可视化** — 工具执行时前端显示「正在搜索聊天记录…」，Agent 的中间步骤对用户可见
- **多 Agent 群聊** — 助手可入群、被 @ 触发、互相接力，带轮次上限
- **助手中心** — 左侧导航独立入口，卡片展示每个助手能做什么，一键添加并开始聊天

## AI 架构

### 消息流转：普通消息

```
客户端 ──HTTP──→ ChatController.saveMessage()
                      ├── MySQL 落库
                      └── RTopic.publish() ──→ Redis
                                                 │
客户端 ←─WebSocket── Netty(:5051) ←── MessageHandler 订阅
```

### 消息流转：AI 回复

关键设计是**把大模型调用从 HTTP 请求线程里挪出去**。单次响应 2–10 秒，Agent 多轮工具调用可能更久，同步调用会直接打满 Tomcat 线程池。

```
客户端 ──HTTP──→ saveMessage()
                   ├── 用户消息落库
                   ├── 立即返回 ────────────→ 客户端（用户消息上屏）
                   └── 投递任务
                         ↓
                  ai-chat-* 线程池
                         ├── Redis 读取对话记忆
                         ├── Spring AI 流式调用
                         │     └── 工具调用 → 推 AI_TOOL_CALL
                         ├── 每 20 字符 / 80ms ──→ RTopic ──→ WS（打字机）
                         └── 结束后完整内容落库 ──→ RTopic ──→ WS（正式消息）
```

**为什么不用 SSE**：项目本来就有 Netty WebSocket 长连接和 RTopic 广播，流式片段塞进 `MessageSendDto.extendData` 即可，不改表结构、不改路由逻辑。

**为什么要做片段聚合**：逐 token 推送会让 Redis pub/sub 和 WebSocket 写放大上百倍。两个阈值（字符数 / 时间间隔）共同作用，任一满足就推一次。

新增的三种消息类型：

| 类型 | 值 | 说明 |
|------|----|----|
| `AI_STREAM` | 14 | 流式回复片段 |
| `AI_STREAM_END` | 15 | 流式结束，携带完整内容做校准 |
| `AI_TOOL_CALL` | 16 | 工具调用中，前端显示提示 |

这三类消息只走 WebSocket，不落库、不写客户端本地 SQLite。真正的消息记录以流结束后落库的那条普通聊天消息为准。

### 工具调用的安全边界

`ChatAgentTools` **不是 Spring 单例，而是每次对话新建的实例**，`userId` 在构造时由服务端注入：

```java
public ChatAgentTools(String userId, AiStreamCallback callback, ...)
```

`userId` 不作为工具参数暴露给模型，模型没有任何途径伪造身份去读别人的数据。`searchChatHistory` 还会额外回数据库确认模型给出的 `contactId` 确属当前用户的联系人，越权尝试会被拦截并记录警告日志。

> 这里不能用 ThreadLocal 传递身份：Spring AI 在 Flux 管道内部执行工具调用，运行在 reactor 线程上，而不是提交任务的线程池线程。

### 多 Agent 群聊

助手的回复走的是和真人一样的 `saveMessage` 链路，所以它的发言**同样会被解析 @** —— 助手之间的接力就是这么自然跑起来的，没有专门的编排逻辑。

代价是必须防死循环，靠消息链路上的轮次深度封顶：

```
真人发言            depth = 0
  └─ 助手 A 回复     depth = 1
       └─ 助手 B 回复 depth = 2
            └─ 助手 C 回复 depth = 3   ← 到顶，不再触发新助手
```

另外两道保护：助手 @ 到自己不触发（避免自问自答）；绝大多数群消息不含 `@`，先做一次 `indexOf('@')` 短路，不让每条群消息都去查一次群成员表。

**群聊上下文不用 user/assistant 交替**。群里有多个说话人，交替式消息列表表达不了谁是谁，直接渲染成带昵称的聊天记录更可靠：

```
张三：这个功能优先级高吗
产品经理小P：得先看解决谁的问题
李四：@架构师小A 你觉得工作量呢
```

## 项目结构

```
mychat/
├── easychat-java/                    # 后端 Spring Boot 服务
│   ├── src/main/java/com/easychat/
│   │   ├── ai/                       # AI Agent 层
│   │   │   ├── AiAgentDefinition     #   助手定义（id/昵称/人设）
│   │   │   ├── AiAgentRegistry       #   注册表 + @提及解析
│   │   │   ├── AiAgentInitializer    #   启动时写入 user_info
│   │   │   ├── ChatAgentTools        #   暴露给模型的业务工具
│   │   │   └── AiToolFactory         #   按次构造工具实例
│   │   ├── controller/               # REST API 控制器
│   │   ├── service/                  # 业务逻辑层
│   │   │   ├── AiChatService         #   对话服务（阻塞 / 流式 / 无记忆）
│   │   │   ├── AiChatMemory          #   对话记忆接口
│   │   │   ├── AiStreamCallback      #   流式回调
│   │   │   └── impl/RedisAiChatMemory#   Redis 记忆实现
│   │   ├── websocket/                # Netty WebSocket 服务 & 消息广播
│   │   ├── config/                   # AI 客户端 & 线程池配置
│   │   ├── redis/                    # Redis 缓存操作
│   │   ├── entity/                   # 实体类 & DTO
│   │   └── mappers/                  # MyBatis Mapper
│   └── src/main/resources/
│       └── application.properties
├── easychat-front/                   # 前端 Electron + Vue 3 桌面应用
│   └── src/
│       ├── main/                     # Electron 主进程（含 WebSocket 客户端）
│       ├── renderer/                 # Vue 3 渲染进程
│       └── preload/                  # 预加载脚本
├── benchmark/                        # 性能基准测试工具
├── easychat.sql                      # 数据库初始化脚本
└── README.md
```

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
mysql -u root -p -e "CREATE DATABASE easychat CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
mysql -u root -p easychat < easychat.sql
```

### 2. 配置后端

编辑 `easychat-java/src/main/resources/application.properties`：

```properties
# 数据库连接
spring.datasource.url=jdbc:mysql://127.0.0.1:3306/easychat?serverTimezone=GMT%2B8&useUnicode=true&characterEncoding=utf8
spring.datasource.username=root
spring.datasource.password=你的密码

# Redis
spring.data.redis.host=127.0.0.1
spring.data.redis.port=6379

# 文件存储目录
project.folder=D:/easychat/

# AI 大模型（不配置则 AI 功能不可用，IM 部分不受影响）
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

> 工具调用依赖模型本身的 Function Calling 能力。换到不支持的模型时把 `ai.chat.tools.enabled` 设为 `false`，会退回纯对话模式。

### 3. 启动后端

```bash
cd easychat-java
mvn clean package -DskipTests
java -jar target/easychat-1.0.jar
```

启动成功后：

- HTTP API：`http://localhost:5050/api`
- WebSocket：`ws://localhost:5051/ws`

启动日志里应该能看到助手账号被创建：

```
AI助手已创建: 产品经理小P(Uagentpm)
AI助手已创建: 架构师小A(Uagentarch)
AI助手已创建: 测试工程师小T(Uagentqa)
```

### 4. 启动前端

```bash
cd easychat-front
npm install
npm run dev
```

### 5. 打包桌面客户端（可选）

```bash
npm run build:win     # Windows
npm run build:mac     # macOS
npm run build:linux   # Linux
```

产物在 `easychat-front/installPackages/` 目录下。

## 试用 AI 功能

**私聊助手**：点左侧导航的机器人图标进入「AI 助手」页，每张卡片写明了这个助手能做什么，点「添加并聊天」就会自动加为好友并跳进聊天窗口（助手是"直接加入"类型，不需要审批）。

试试这些能触发工具调用的问题：

- 「我有哪些好友？」
- 「帮我搜一下和张三聊天里提到过的方案」
- 「我上周和谁聊过项目的事？」

**群聊多 Agent**：

1. 建一个群
2. 点右上角进群详情 → 点「助手」→ 选择要拉进群的助手
3. 在群里 `@产品经理小P @架构师小A 评估下这个需求：……`
4. 两个助手会同时开始流式回复，并可能把话题交给对方

## AI 配置项

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `ai.chat.system-prompt` | — | 单聊助手的人设 |
| `ai.chat.max-history` | 20 | 保留的最大对话轮数（一问一答为一轮） |
| `ai.chat.history.expire-days` | 7 | 对话历史在 Redis 中的过期天数，每次追加续期 |
| `ai.chat.stream.flush-chars` | 20 | 缓冲区攒够多少字符推送一次 |
| `ai.chat.stream.flush-interval-ms` | 80 | 距上次推送超过多少毫秒推送一次 |
| `ai.chat.stream.timeout-seconds` | 120 | 单次流式回复的整体超时 |
| `ai.chat.executor.core-size` | 16 | AI 线程池核心线程数 |
| `ai.chat.executor.max-size` | 64 | AI 线程池最大线程数 |
| `ai.chat.executor.queue-capacity` | 64 | 队列容量，不宜过深——排太久不如快速失败 |
| `ai.chat.tools.enabled` | true | 是否开放业务工具给模型调用 |
| `ai.chat.group.max-depth` | 3 | 群里助手之间互相接话的最大轮数 |
| `ai.chat.group.context-size` | 15 | 拼给助手看的群聊上下文条数 |

### 增加一个助手

加一段配置即可，不用改代码：

```properties
ai.agents[4].id=Uagentops
ai.agents[4].name=运维小O
ai.agents[4].signature=只关心线上会不会炸
ai.agents[4].description=评估上线风险和回滚方案|排查线上故障的思路|提醒监控和告警该配什么
ai.agents[4].prompt=你是一名运维工程师，习惯从部署复杂度、监控告警和故障恢复的角度思考问题。
```

三个描述字段用途不同，别搞混：

| 字段 | 给谁看 | 作用 |
|------|--------|------|
| `signature` | 用户 | 一句话标签，显示在昵称下方 |
| `description` | 用户 | 能力清单，多条用 `|` 分隔，「AI 助手」页会拆成列表 |
| `prompt` | 模型 | 人设，作为 system prompt 下发 |

> `id` 必须以 `U` 开头且不超过 12 个字符——`user_info.user_id` 字段长度限制。
> 重启后会自动创建助手账号，并生成一张默认头像（`{project.folder}/file/avatar/` 下，已存在的不覆盖）。
> `ai.agents[0]` 是内置助手，`id` 必须等于 `Constants.ROBOT_UID`（即 `Urobot`），注册时会自动加为好友。

## 性能基准

`benchmark/` 目录下是针对两个核心指标的实测工具：

- 端到端消息延迟（RTopic.publish → 客户端收到）
- 单机并发长连接数

使用方式见 [benchmark/README.md](benchmark/README.md)。

## 已知限制

- **群聊助手暂不开放业务工具**。工具是按用户维度鉴权的（工具实例绑定 `userId`），群聊场景下"以谁的身份授权"这个语义尚未定义，因此有意留空。
- **Spring AI 仍是 milestone 版本**（1.0.0-M6），API 在正式版之前可能有变动。
- **助手昵称不要互为前缀**。@提及靠昵称字符串匹配，若同时存在「小P」和「小P助手」，`@小P助手` 会同时命中两个。
- **加好友时的打招呼消息不会触发回复**。加好友走的是 `UserContactService.addContact`，直接插库而不经过 `saveMessage`，所以助手不会回应这条问候。发第二条消息就正常了。
- **对话记忆的 Redis key 在本次改动中变了**（`easychat:ai:history:{userId}` → `easychat:ai:history:{userId}:{agentId}`）。旧 key 不再被读取，会随 7 天 TTL 自然过期，无需手工迁移。

## 后续计划

- RAG 知识库：复用已有的文件上传能力，让助手能基于用户发来的文档回答问题
- 群聊场景的工具授权模型
- 助手主动发起消息（定时任务、长任务完成通知）
