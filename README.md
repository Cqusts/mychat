# MyChat

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
├── mychat-java/                    # 后端 Spring Boot 服务
│   ├── src/main/java/com/mychat/
│   │   ├── ai/                       # AI Agent 层
│   │   │   ├── AiAgentDefinition     #   助手定义（id/昵称/人设/能力描述）
│   │   │   ├── AiAgentRegistry       #   注册表 + @提及解析
│   │   │   ├── AiAgentInitializer    #   启动时写入 user_info 并生成头像
│   │   │   ├── ChatAgentTools        #   暴露给模型的业务工具
│   │   │   ├── AiToolFactory         #   按次构造工具实例
│   │   │   ├── AiStreamPusher        #   流式片段推送（单聊/群聊/流水线共用）
│   │   │   ├── AiWorkflowEngine      #   多 Agent 流水线状态机
│   │   │   ├── AiTaskControl         #   任务登记与停止（标记位 + 线程中断）
│   │   │   ├── ToolBudget            #   工具调用熔断（次数/时限/重复检测）
│   │   │   ├── CoderWorkspace        #   代码沙箱，所有安全边界收在这里
│   │   │   └── CoderTools            #   编码 Agent 的读写编译工具集
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
│   │       ├── application.properties  # 数据库/Redis/端口等基础设施配置
│   │       └── application.yml         # AI 配置（中文必须放这里，见下文）
│   └── src/test/java/com/mychat/ai/   # 单元测试
├── mychat-front/                   # 前端 Electron + Vue 3 桌面应用
│   ├── assets/                       # ⚠️ ffmpeg.exe / ffprobe.exe 需自行下载
│   └── src/
│       ├── main/                     # Electron 主进程（含 WebSocket 客户端）
│       ├── renderer/                 # Vue 3 渲染进程
│       └── preload/                  # 预加载脚本
├── benchmark/                        # 性能基准测试工具
├── mychat.sql                      # 数据库初始化脚本
└── README.md
```

## 本地部署

从零跑起来大概 15 分钟。**IM 功能不需要任何 AI 配置就能用**，AI 部分可以之后再配。

### 0. 环境要求

| 依赖 | 版本 | 检查命令 |
|------|------|----------|
| JDK | 17+ | `java -version` |
| Maven | 3.8+ | `mvn -v` |
| Node.js | 16+ | `node -v` |
| MySQL | 5.7+ / 8.0+ | `mysql --version` |
| Redis | 6.0+ | `redis-cli ping` → 返回 `PONG` |

不需要邮件服务器，注册不发验证码。

### 1. 克隆并补齐 ffmpeg

```bash
git clone https://github.com/Cqusts/mychat.git
cd mychat
```

⚠️ **`mychat-front/assets/` 下缺两个二进制文件，需要自己下载**：`ffmpeg.exe`、`ffprobe.exe`。
它们几十 MB，不适合进版本库。缺了会导致**上传头像和发送视频报错**，文字、图片、AI 功能不受影响。

下载地址和放置方法见 [mychat-front/assets/README.md](mychat-front/assets/README.md)。
想先跑起来的话这一步可以跳过。

### 2. 建库导表

```bash
mysql -u root -p -e "CREATE DATABASE mychat CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
mysql -u root -p mychat < mychat.sql
```

导完应该有 9 张表。确认一下：

```bash
mysql -u root -p -e "USE mychat; SHOW TABLES;"
```

### 3. 配置连接信息

**所有敏感配置都走环境变量**，代码里只留占位默认值，不用改任何文件就能跑：

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| `MYSQL_HOST` | `127.0.0.1` | |
| `MYSQL_PORT` | `3306` | |
| `MYSQL_DATABASE` | `mychat` | |
| `MYSQL_USER` | `root` | |
| `MYSQL_PASSWORD` | `root` | **和你本地不一样就必须设** |
| `REDIS_HOST` | `127.0.0.1` | |
| `REDIS_PORT` | `6379` | |
| `MYCHAT_HOME` | `D:/mychat/` | 服务端文件目录（头像、图片、视频、日志），**必须可写**；Linux/macOS 必须改 |
| `ADMIN_EMAILS` | `admin@example.com` | 用这个邮箱注册的账号能进管理后台 |
| `MYCHAT_AI_API_KEY` | — | 大模型 API Key，不配则 AI 功能不可用 |

```bash
# Windows（setx 是永久生效，设完必须重开终端；IDE 也要重启才能读到）
setx MYSQL_PASSWORD "你的密码"
setx MYCHAT_AI_API_KEY "sk-xxxx"

# Linux / macOS
export MYSQL_PASSWORD="你的密码"
export MYCHAT_HOME="$HOME/mychat/"
export MYCHAT_AI_API_KEY="sk-xxxx"
```

不想用环境变量的话，直接改 `mychat-java/src/main/resources/application.properties`
里的默认值也行——**但别把真密码提交回 git**。

### 4. 配置大模型（可选，跳过则只有 IM 功能）

AI 配置在 `mychat-java/src/main/resources/application.yml`：

```yaml
spring:
  ai:
    openai:
      # 优先读环境变量，读不到才用字面值
      api-key: "${MYCHAT_AI_API_KEY:YOUR_API_KEY_HERE}"
      base-url: "https://api.deepseek.com"
      chat:
        options:
          model: "deepseek-chat"
```

支持任何 OpenAI 兼容协议的服务商，换厂商只改这两行：

| 服务商 | base-url | model | 备注 |
|--------|----------|-------|------|
| DeepSeek | `https://api.deepseek.com` | `deepseek-chat` | 便宜，本项目默认 |
| OpenAI | `https://api.openai.com` | `gpt-4o-mini` | |
| 通义千问 | `https://dashscope.aliyuncs.com/compatible-mode` | `qwen-turbo` | |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas` | `glm-4-flash` | 有免费额度 |

没配 key 时启动日志里会有一整块醒目的报错提示，所有 AI 对话都回复"AI助手暂时无法回复"，
IM 功能照常。

> 工具调用依赖模型自身的 Function Calling 能力。换到不支持的模型时把
> `ai.chat.tools.enabled` 设为 `false`，会退回纯对话模式。

> **为什么 AI 配置单独放 yml**：Spring Boot 的 `OriginTrackedPropertiesLoader` 写死用
> ISO-8859-1 读 `.properties`（遵循 `java.util.Properties` 规范），中文写在 `.properties`
> 里一定变成乱码，且没有任何配置项能改这个行为。助手昵称、人设、能力说明都是中文，
> 所以整块 AI 配置放在 yml —— SnakeYAML 默认按 UTF-8 读。编辑时确保保存为 UTF-8。

### 5. 启动后端

```bash
cd mychat-java
mvn clean package -DskipTests
java -jar target/mychat-1.0.jar
```

Windows 控制台如果中文是乱码，先切代码页：

```bat
chcp 65001
java -jar target/mychat-1.0.jar
```

> 不想改代码页就用 `java -DLOG_CONSOLE_CHARSET=GBK -jar target/mychat-1.0.jar`。
> 日志文件 `{MYCHAT_HOME}/logs/mychat.log` 始终是 UTF-8，任何情况下都能直接看。

启动成功的标志：

```
HTTP  →  http://localhost:5050/api
WS    →  ws://localhost:5051/ws
```

配了 AI 的话，日志里还能看到助手账号被创建：

```
AI助手已创建: 智能助手小E(Urobot)
AI助手已创建: 产品经理小P(Uagentpm)
AI助手已创建: 架构师小A(Uagentarch)
...
```

### 6. 启动前端

```bash
cd mychat-front
npm install
npm run dev
```

> `npm install` 在国内容易卡在下载 Electron 二进制上，可以先设镜像：
> ```bash
> npm config set electron_mirror https://npmmirror.com/mirrors/electron/
> ```
> 安装过程中关于 `msvs_version` 的告警可以忽略，不影响运行。

### 7. 验证

1. 客户端起来后点「注册」，随便填个邮箱和密码（不发验证码，填了就能用）
2. 登录进去，左侧导航能看到「AI 助手」入口
3. 配了 AI Key 的话，通讯录里会有「智能助手小E」，直接跟它说句话试试

### 8. 打包桌面客户端（可选）

```bash
npm run build:win     # Windows
npm run build:mac     # macOS
npm run build:linux   # Linux
```

产物在 `mychat-front/installPackages/`。

## 常见问题

| 现象 | 原因 | 解决 |
|------|------|------|
| 启动报 `Access denied for user 'root'` | 数据库密码不对 | 设 `MYSQL_PASSWORD` 环境变量，Windows 上 `setx` 之后要重开终端/IDE |
| 启动报 `Unable to connect to Redis` | Redis 没起 | `redis-server` 启动，`redis-cli ping` 确认返回 `PONG` |
| 启动报 `Table 'mychat.xxx' doesn't exist` | SQL 没导 | 回到第 2 步，确认 9 张表都在 |
| 上传头像一直转圈 / 报「缺少 ffmpeg 组件」 | 没放 ffmpeg | 见第 1 步 |
| 控制台中文乱码 | Windows 控制台默认 GBK | `chcp 65001`，或看日志文件 |
| 通讯录里没有机器人 | 没配 AI Key，助手账号没创建 | 配好 Key 重启后端，账号会自动建 |
| AI 回复「暂时无法回复」 | Key 无效 / 余额不足 / base-url 不对 | 看后端日志里的具体报错 |
| 群里 @ 助手没反应 | 助手不在群里 | 群详情 → 助手 → 把它拉进群 |
| 发消息报 `Data too long for column 'message_content'` | 老库没升级 | 见下面「升级已有数据库」 |
| 前端连不上后端 | 端口被占 / 防火墙 | 确认 5050 和 5051 都通 |

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

以下配置都在 `mychat-java/src/main/resources/application.yml`，表格里用点号写法表示层级。

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

在 `application.yml` 的 `ai.agents` 下追加一项：

```yaml
    - id: "Uagentops"
      name: "运维小O"
      signature: "只关心线上会不会炸"
      description: "评估上线风险和回滚方案|排查线上故障的思路|提醒监控和告警该配什么"
      prompt: "你是一名运维工程师，习惯从部署复杂度、监控告警和故障恢复的角度思考问题。"
```

三个描述字段用途不同，别搞混：

| 字段 | 给谁看 | 作用 |
|------|--------|------|
| `signature` | 用户 | 一句话标签，显示在昵称下方 |
| `description` | 用户 | 能力清单，多条用 `|` 分隔，「AI 助手」页会拆成列表 |
| `prompt` | 模型 | 人设，作为 system prompt 下发 |

> `id` 必须以 `U` 开头且不超过 12 个字符——`user_info.user_id` 字段长度限制。
> 重启后会自动创建助手账号，并生成一张默认头像（`{project.folder}/file/avatar/` 下，已存在的不覆盖）。
> 列表里的第一项是内置助手，`id` 必须等于 `Constants.ROBOT_UID`（即 `Urobot`），注册时会自动加为好友。

## 性能基准

`benchmark/` 目录下是针对两个核心指标的实测工具：

- 端到端消息延迟（RTopic.publish → 客户端收到）
- 单机并发长连接数

使用方式见 [benchmark/README.md](benchmark/README.md)。

## 启用需求流水线的编码与测试环节

群里 @ 产品经理提需求，会自动跑一条流水线：

```
需求分析 → 方案设计 → 方案评审 ──┬─【通过】→ 编码实现 → 测试验证 → 完成
              ↑                  │
              └──【打回】≤2次─────┘
```

开了 TDD（`ai.workflow.tdd.enabled`）之后会在评审和编码之间多插一步「测试先行」，
见 [TDD 红绿门禁](#tdd-红绿门禁怎么证明需求真的实现了)。

前三步开箱即用。**编码和测试会真的在你机器上写文件、跑 Maven、推 git，所以默认关闭**，
确认理解下面的边界后再打开：

```yaml
ai:
  coder:
    enabled: true                                   # 默认 false
    # ⚠️ 独立目录，绝对不要指向你自己的仓库——AI 改一半和你手上的改动搅在一起会很难收拾
    workspace: "${MYCHAT_AI_WORKSPACE:D:/mychat-ai-workspace}"
    git-url: "https://github.com/你的账号/你fork的仓库.git"
    base-branch: "${MYCHAT_BASE_BRANCH:main}"     # 从哪个分支拉出来改
```

`git-url` 留空时会退而从 `source-repo`（一个本地仓库目录）读 origin 地址，两者配一个即可。

前置条件：

- `git` 和 `mvn` 在**服务端进程**的 PATH 里。Windows 上会自动按 PATH 解析出
  `git.exe` / `mvn.cmd` 的真实路径（`CreateProcess` 只补 `.exe` 不补 `.cmd`，
  不做这一步 `mvn` 一定起不来）。注意 IDE 启动的进程不一定继承你终端的 PATH，
  不确定就直接填绝对路径：
  ```yaml
  maven-command: "D:/apache-maven-3.9.6/bin/mvn.cmd"
  git-command: "git"
  ```
  编码阶段开始前会先探测这两个命令，不通会直接停在这一步并说明原因。
- git 能免密推送（SSH key 或 credential helper 配好），否则推送会卡住。

安全边界都在 `CoderWorkspace` 一个类里：

| 防护 | 做法 |
|------|------|
| 独立工作区 | 另外克隆一份，不碰你正在用的工作树 |
| 路径校验 | 文件操作解析后必须仍在工作区内，挡掉 `../` 和绝对路径 |
| 命令白名单 | 只跑写死的 git/mvn 子命令，参数以数组传给 `ProcessBuilder`，不拼 shell |
| 分支白名单 | 正则只放行 `ai/` 前缀，碰不到 `main` 和你的开发分支 |
| 推送时机 | 由引擎在编译通过后决定，模型没有提交推送的工具 |

引擎不信模型的自述：会自己检查工作区是否真有改动、自己再编译一次，
编译不通过就带着报错让它修，仍然不过就放弃推送。

### 跑飞了怎么停

群聊标题栏在流水线运行时会出现「AI 运行中，点击停止」，点一下即可终止，
已改的代码留在工作区分支但不会提交推送。

停止是两层的：先打标记（工具方法每次进来先看标记，一次工具调用之内就停），
再中断任务线程（卡在 HTTP 读或 `mvn` 上时标记轮询不到）。

除了手动停止，编码/测试阶段还有三道自动闸门，可在 `ai.coder` 下调：

| 闸门 | 默认 | 拦什么 |
|------|------|--------|
| `max-tool-calls` | 60 | 单轮工具调用总数 |
| `stage-deadline-minutes` | 20 | 单轮墙钟时限 |
| 同参数重复调用 | 3 次 | 原地打转 |

最后一条是必需的：`Flux.timeout` 管的是「两个分片之间的间隔」，
只要工具一直有产出就永远不触发，挡不住空转。
另外编码阶段开始前会先探测 `git` / `mvn` 能否执行，环境不通就直接停在这一步并说明原因——
不把环境问题丢给模型去猜（曾经因为 `mvn` 不在 PATH，模型反复搜"怎么编译"刷了一千七百多次工具调用）。

整个过程在群里是可见的：切到哪个分支、正在读改哪个文件、正在编译，都会实时推给你。
改完先发一条 `git diff --stat` 的真实统计再推分支——这条统计来自 git 而不是模型的自述，
可以直接拿它对账。编译最终没过就把报错节选发到群里，不用去翻服务端日志。

## 代码检索增强

第一轮评测跑出来的结论很干净：**真实失败 100% 集中在编码阶段的工具预算熔断** ——
Agent 不是改错了，是在 60 次工具调用里压根没找到该改哪个文件。
需求里点名了类/表的 4 条通过 3.5 条，只描述行为、要自己定位的 6 条全灭。

原来的 `searchCode` 是无排序子串匹配、凑够 40 条就停，搜「会话」返回的是文件系统
顺序里最先撞上的 40 行。据此做了三件事：

**① 三路召回 + RRF 融合**（`ai/index/CodeIndex.java`）

| 通道 | 信号 |
|------|------|
| BM25 全文 | 正文和中文注释都进索引，中文需求能直接命中中文注释 |
| 符号名 | 类名/方法名/Mapper 语句 id/表名，比「正文出现过 10 次」强得多 |
| 文件名 | 最强的定位信号，单独加权 |

三路的分数量纲不可比，但排名可比，所以用 RRF（`1/(60+rank)` 求和）融合，不用调权重。
文档和测试统一降权 —— 实测第一版把 `README.md` 和 `*Test.java` 排到了前面，
它们把领域词写了个遍，BM25 上分很高，但答案永远不是它们。

**② 中文需求 → 代码标识符的桥**（`ai/index/CodeGlossary.java`）

需求写「会话列表」，代码里叫 `ChatSessionUser`。分词器把驼峰拆开
（`ChatSessionUser` → chat/session/user）、中文切二元组，再用一本领域词典
把「会话→session」「撤回→revoke」翻过去，同时滤掉「增加/支持/提供」这类套话。

**③ 两个新工具 + 项目地图**

- `findFiles(需求原话)` —— 直接丢中文需求，按相关度返回候选文件及其类/方法清单
- `outline(路径)` —— 只看文件骨架，不返回方法体，比 `readFile` 省一大截上下文
- 编码阶段的 system prompt 里注入分层结构地图和中英文命名对照，
  省得模型用十几次工具调用去试出「Controller → Service → Mapper → XML」这套约定

改造前后对同一批需求的检索结果：

| 需求 | 改造前 Top1 | 改造后 Top1 |
|------|------------|------------|
| 会话列表支持按昵称模糊搜索 | `CodeToken.java` ❌ | `ChatSessionServiceImpl.java` ✅ |
| 实现消息撤回 | `AiEvalReportTest.java` ❌ | `ChatMessage.java` ✅ |
| 给聊天消息增加引用回复 | `AiChatService.java` ❌ | `ChatMessageServiceImpl.java` ✅ |
| 群聊增加仅群主可发言 | `ChatGroupDetail.vue` ❌ | `GroupInfo.java` ✅ |

## 批量修改：为什么轮次比预算重要

第二轮评测把工具预算从 60 提到 120，结果是**单任务成本约 4 倍、跨文件需求通过率没变**。
原因是 Agent 每调一次工具都要重发全部对话历史，token 开销随轮次**平方增长**：

```
60 次调用   1+2+…+60  ≈ 1830 单位
120 次调用  1+2+…+120 ≈ 7260 单位     约 4 倍
```

而且烧满预算的恰恰是注定失败的任务 —— 成功的二三十轮就收工了。**加预算是错误的杠杆**，
正确方向是压缩轮次：

| 工具 | 作用 |
|------|------|
| `readFiles(路径列表)` | 一次读完要改的几个文件，总量上限 4 万字符 |
| `applyEdits(改动列表)` | 一次提交跨多文件的多处改动 |
| `outline(路径)` | 只看骨架，确认文件用它而不是 `readFile` |

`applyEdits` 的关键性质是**原子性**：先全部试算，任何一处对不上就一个文件都不改，
并把所有问题一次性返回。这样模型在一轮里就能拿到全部反馈，
而不是改一处失败一次、来回好几轮 —— 每一轮都要重发全部历史。

## TDD 红绿门禁：怎么证明需求真的实现了

「编译通过并推送」证明不了需求实现了 —— 它只证明代码编得过。评测里
`任务完成率` 和 `一次编译通过率` 都是这个层面的指标，一个只写了半截逻辑
但语法正确的实现照样能拿满分。

所以加了一道 TDD 流程：**编码之前先写测试**，插在方案评审和编码之间。

```
需求分析 → 方案设计 → 方案评审 → 测试先行 → 编码实现 → 测试验证
                                    ↑红灯门禁            ↑绿灯门禁
```

| 门 | 判定 | 意义 |
|----|------|------|
| 红灯 | 新写的测试**必须先失败** | 一次就能跑通说明它没测到新行为，是假测试 |
| 绿灯 | 实现之后测试**全部通过** | 需求真的达成了 |

红灯门禁是这里唯一不容易被糊弄的地方。模型很容易写出恒真的断言来「完成任务」，
但「新测试必须先失败」是**机器可验证**的，不需要人去读测试写得好不好。

配套的强制约束是：**编码阶段测试目录只读**。这个在工具层封死，不靠提示词自觉 ——
`replaceInFile` / `createFile` / `applyEdits` 命中 `src/test/java/` 一律拒绝，
批量修改里夹带一个测试文件则整批都不落盘。否则「让测试变绿」最省事的办法
就是把断言删掉，门禁就成了摆设。

### 开启

```yaml
ai:
  workflow:
    tdd:
      enabled: false        # 默认关闭
      max-red-retry: 1      # 测试一次就跑通(假测试)时，最多让它重写几次
```

默认关掉有两个原因：

1. **要能做 A/B 对照。** 开与不开各跑一批，差值才说明问题。
2. **它会拉低任务完成率。** 多了一道关，原来编译过就算完的现在过不了。

换来的是评测报告里多出来的一行硬指标：

```
任务完成率      45.0%  (9/20 走到DONE)          ← 编译通过并推送
红灯门禁通过    14 个  (测试确实先失败了，不是假测试)
需求达成率      35.0%  (7/20 红绿两道门都过)     ← 验收测试真的从红转绿
```

两个数是**分开算**的，别混着用：完成率的分母是全部任务，达成率的分母也是全部任务
（红灯就没过的不从分母里摘出去 —— 测试都没写对，需求当然谈不上达成）。

> **前提：基线测试套件本身得是绿的。** 红灯是拿整套测试的成败判定的，
> 基线里有失败用例的话，红灯会被误判成「测试写对了」。跑之前先
> `mvn test -DskipTests=false` 确认一遍。

代价是每个任务多跑几次 `mvn test`，耗时会明显上升。

## 跑一次评测，拿到真实指标

多 Agent 系统最难回答的问题是「它到底好不好用」。这套评测工具就是用来回答它的：
一批需求串行跑完，直接输出完成率、返工轮次、编译一次通过率、耗时分位和失败原因分布。

### 开启

```yaml
ai:
  eval:
    enabled: true          # 默认 false
    task-timeout-minutes: 45
  coder:
    enabled: true          # 评测编码环节必须打开
```

> ⚠️ 这几个接口能凭一次请求驱动模型改代码、跑 Maven，**只在本地开，别在公网环境打开**。

重启后端，并确认那个群里 5 个角色助手都在（群详情 → 助手）。

### 跑批（一键脚本）

Windows 上直接用 `scripts/run-eval.ps1`，token、groupId、sessionId 都会自动找：

```powershell
# 先试水一条，确认环境通了（约 4~8 分钟）
.\scripts\run-eval.ps1 -SmokeTest

# 正式跑：10 条需求各 2 次
.\scripts\run-eval.ps1

# 跑完补上 token 成本重新出报告
.\scripts\run-eval.ps1 -ReportOnly -CostYuan 16.6
```

脚本做了这几件手工跑很容易踩坑的事：

| 坑 | 脚本怎么处理 |
|---|---|
| token 不知道是哪个 | 扫 electron-store 的 `config.json`，逐个调接口验活 |
| groupId / sessionId 要手抄 | 从后端日志的群消息 JSON 里正则抓最后一条 |
| PowerShell 中文乱码 | 拿响应原始字节按 UTF-8 解，不走 `Invoke-RestMethod` 的默认解码 |
| 新旧数据混在一起 | 跑批前自动 clear 并校验 `recorded == 0` |
| 忘了跑完要看报告 | 自动轮询到结束，出报告并存成带时间戳的文件 |

> 脚本本身存成了 UTF-8 **带 BOM** —— PowerShell 5.1 读不带 BOM 的 `.ps1` 会按 ANSI 解码，里面的中文会全变乱码。

### 手工跑批

需求集在 `mychat-java/eval-tasks.txt`，按难度分了三层，可以自己改。
拿到 `groupId`（形如 `G7964...`）和 `sessionId`——F12 网络面板里任意一条群聊请求都能看到。

```bash
TOKEN="你的登录token"      # F12 请求头里的 token
GROUP="G79649786975"
SESSION="ce542257668375a28599446adb03eade"

curl -X POST "http://localhost:5050/api/eval/batch" \
  -H "token: $TOKEN" \
  --data-urlencode "groupId=$GROUP" \
  --data-urlencode "sessionId=$SESSION" \
  --data-urlencode "requirements@mychat-java/eval-tasks.txt" \
  --data-urlencode "repeat=2"
```

立刻返回，跑批在后台串行执行。**必须串行**：并发会抢同一个代码工作区，
耗时数据也会被线程池排队时间污染。8 条需求 × 2 次大约要跑 1~3 小时。

看进度：

```bash
curl "http://localhost:5050/api/eval/status" -H "token: $TOKEN"
```

### 看指标

```bash
curl "http://localhost:5050/api/eval/reportText" -H "token: $TOKEN"
```

```
========== 需求流水线评测报告 ==========
样本总数        16 个任务
任务完成率      37.5%  (6/16 走到DONE)
平均返工轮次    0.8 轮
进入编码阶段    11 个
编译一次通过率  54.5%  (分母是进入编码的11个)
成功推送分支    6 个
单需求耗时      中位 6分12秒 / P90 18分40秒

---------- 失败原因分布 ----------
编码零改动                   4 条
编译不通过                   3 条
方案未通过评审                2 条
...
```

报告末尾会把这些数拼成一句可以直接抄的话：

```
---------- 简历口径（核对后可直接抄）----------
构建 10 条难度分层的需求评测集（每条运行 2 次，共 20 个任务），实测端到端任务完成率
37.5%、平均评审返工 0.8 轮、编译一次通过率 54.5%、单需求中位耗时 6.2 分钟 /
token 成本约 0.83 元。
失败集中在编码实现阶段（81.8%），据此定位瓶颈为 __。

最后那个空自己填，别照抄——它是结论不是数据。参考对照：
  最多的失败原因是：编码零改动
  → 模型定位不到该改的文件 → 瓶颈是缺乏代码检索增强（RAG）
```

**token 成本**跑批前后各看一眼大模型控制台的消费额，把差值传进去自动换算：

```bash
curl "http://localhost:5050/api/eval/reportText?totalCostYuan=16.6" -H "token: $TOKEN"
```

`/eval/report` 返回同样内容的 JSON，`/eval/clear` 清空历史记录
（换任务集或改配置后要先清，否则新旧数据混在一起算出来的指标没有意义）。

### 几个必须注意的口径

- **`temperature` 默认 0.7，同一条需求跑两次结果可能不同**，所以 `repeat` 至少给 2。
  报告里的「逐条需求通过情况」（`1/2`、`0/2`）就是用来看稳定性的——
  这比总完成率更能说明问题。
- **编译一次通过率的分母只算走到编码阶段的任务**。把连方案都没过的算进来是在稀释这个数。
- **「一次通过」指第 0 轮 compile 就过**，后面几轮是引擎把报错怼回去逼模型改出来的，不算。
- **token 成本不用测**：跑批前后各看一眼大模型厂商控制台的用量，差值除以任务数最准。

### 怎么读这份报告

完成率 30% 不丢人，答不出完成率才丢人。真正要看的是失败分布：

| 失败集中在 | 说明 | 下一步 |
|---|---|---|
| 编码零改动 | 模型定位不到该改的文件 | 上代码检索增强（RAG） |
| 编译不通过 | 修复轮次不够 | 调大 `ai.coder.max-fix-rounds` 再测一轮 |
| 方案未通过评审 | 评审太严或需求太模糊 | 调 review 的 prompt |
| 被熔断 | 卡在解不开的问题上 | 看日志里的 `[EVAL]` 行定位 |

改完再跑一轮，两组数据一对比，就是一次完整的优化闭环。

## 日志级别

`application.properties` 里有两个开关：

| 配置 | 管什么 | 默认 |
|------|--------|------|
| `log.root.level` | 第三方框架 | `info` |
| `log.app.level` | 项目自己（`com.mychat`，MyBatis 的 SQL 也走这里） | `debug` |

root 之所以不是 `debug`：Spring AI 走 WebClient，DEBUG 级别会把每一个 SSE 分片、每一轮工具调用的
入参出参都打出来，一次流式回复就是几百行，自己的日志全被冲走。真要排查大模型链路时，
把 `logback-spring.xml` 里 `org.springframework.ai` 那一行删掉即可。

## 从旧版本（EasyChat）升级

项目已从 `easychat` 更名为 `mychat`，包名、目录、配置项全部跟着变了。
如果你之前跑过旧版本，有四处需要手工处理：

| 变了什么 | 旧 | 新 | 怎么处理 |
|---|---|---|---|
| 数据库名 | `easychat` | `mychat` | 建新库导 `mychat.sql`，或设 `MYSQL_DATABASE=easychat` 继续用旧库 |
| 文件目录 | `D:/easychat/` | `D:/mychat/` | 把旧目录整个改名，否则已上传的头像和图片会失效 |
| 环境变量 | `EASYCHAT_*` | `MYCHAT_*` | `MYCHAT_HOME`、`MYCHAT_AI_API_KEY`、`MYCHAT_AI_WORKSPACE` 等 |
| jar 名 | `easychat-1.0.jar` | `mychat-1.0.jar` | 重新打包即可 |

Redis 里的旧 key（`easychat:*`）不用管，会随 TTL 自然过期。
数据库**表结构没有任何变化**，只是库名换了。

## 升级已有数据库

如果你的 `mychat` 库是在引入 AI 功能之前建的，需要跑一次：

```sql
ALTER TABLE chat_message MODIFY COLUMN message_content TEXT COMMENT '消息内容';
```

原因：`message_content` 原本是 `varchar(500)`，这是给真人聊天设计的长度。AI 助手的
发言经常几百字，加上换行会被转成 `<br>`（一个换行占 4 个字符），很容易超过 500，
MySQL 严格模式下直接抛 `Data too long`，表现就是"群里凭空少了一条发言"。

`chat_session.last_message` 不用改——它只是会话列表里的一行预览，代码里已经改成超长截断。

## 已知限制

- **群聊助手暂不开放业务工具**。工具是按用户维度鉴权的（工具实例绑定 `userId`），群聊场景下"以谁的身份授权"这个语义尚未定义，因此有意留空。
- **Spring AI 仍是 milestone 版本**（1.0.0-M6），API 在正式版之前可能有变动。
- **助手昵称不要互为前缀**。@提及靠昵称字符串匹配，若同时存在「小P」和「小P助手」，`@小P助手` 会同时命中两个。
- **加好友时的打招呼消息不会触发回复**。加好友走的是 `UserContactService.addContact`，直接插库而不经过 `saveMessage`，所以助手不会回应这条问候。发第二条消息就正常了。
- **对话记忆的 Redis key 在本次改动中变了**（`mychat:ai:history:{userId}` → `mychat:ai:history:{userId}:{agentId}`）。旧 key 不再被读取，会随 7 天 TTL 自然过期，无需手工迁移。

## 后续计划

- RAG 知识库：复用已有的文件上传能力，让助手能基于用户发来的文档回答问题
- 群聊场景的工具授权模型
- 助手主动发起消息（定时任务、长任务完成通知）
- 流水线的评测集：跑一批需求，统计任务完成率、平均返工轮次、编译一次通过率

## 参与贡献

欢迎提 Issue 和 PR。改动后端记得跑一遍测试：

```bash
cd mychat-java
mvn -DskipTests=false test
```

> `pom.xml` 里 `skipTests` 默认是 `true`，不显式覆盖的话测试会被直接跳过、
> 结果永远是"成功"。

## 项目构成

想快速找到某块代码的话：

| 位置 | 内容 |
|------|------|
| `mychat-java/src/main/java/com/mychat/ai/` | AI Agent 层、多 Agent 编排引擎、代码沙箱、评测套件 |
| `mychat-java/src/main/java/com/mychat/websocket/` | Netty 长连接与跨节点消息广播 |
| `mychat-front/src/renderer/src/views/chat/` | 流式渲染、@提及选择器 |
| `mychat-front/src/main/` | Electron 主进程、本地消息库 |
| `benchmark/` | 并发连接数与端到端延迟的压测工具 |

> **许可**：仓库暂未附带 LICENSE 文件，默认保留所有权利。
