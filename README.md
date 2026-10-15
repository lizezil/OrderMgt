# Order Assistant

一个订单管理服务：客户用自然语言让 AI 助手查询自己的订单，**并在确认后**才真正创建或变更订单。

核心主张只有一句：**AI 提议，人确认，服务端校验，价格由服务端决定。** 围绕它，这个仓库主要想说明四件事：

1. **AI 的输出是不可信输入。** 模型只能返回白名单里的工具名 + 结构化参数；变更类操作永远不会自动执行，而是落成一张待确认卡片（`PendingAction`），由发起它的客户点击确认后才在事务里执行。见 [ADR-0001](docs/adr/0001-pending-action-confirmation.md)。
2. **AI 调用绝不进数据库事务。** 模型可能跑好几秒，事务里不能握着连接等它。见 [ADR-0002](docs/adr/0002-ai-calls-outside-transaction.md)。
3. **业务代码不依赖 Spring AI。** 业务只依赖 `AiAssistantPort`，Spring AI 藏在适配器后面，测试/离线用桩替换。见 [ADR-0003](docs/adr/0003-ai-behind-own-port.md)。
4. **价格只有一个来源。** 模型只填 sku 和数量，金额由服务端从 `catalog_items` 解析（[ADR-0006](docs/adr/0006-ground-model-with-catalog.md) 记录了一次真实的 sku 幻觉与修法）。

项目文档都收在 [`docs/`](docs/)：原题需求摘录见 [面试作业-需求摘录.md](docs/面试作业-需求摘录.md)，
核心设计（决策表、表结构、接口契约、AI 集成与事务边界）见 [面试作业-设计定稿.md](docs/面试作业-设计定稿.md)，
会话与运行（Run）层的设计见 [会话管理-设计定稿.md](docs/会话管理-设计定稿.md)，
面试讲解与演示手册（要求对照、考点、演示脚本、高频追问、诚实清单）见 [面试作业-讲解手册.md](docs/面试作业-讲解手册.md)，
按使用者视角梳理的用户故事见 [用户故事.md](docs/用户故事.md)；
领域词汇表见 [CONTEXT.md](CONTEXT.md)，所有架构决策见 [docs/adr/](docs/adr/)。

## 技术栈

Java 17 · Spring Boot 3.5.16 · Spring AI 1.1.8（DashScope OpenAI 兼容模式，`qwen-max`）· MyBatis-Plus 3.5.17 · MapStruct 1.6 · Flyway · MySQL 8（唯一数据库，本地与测试都直连常驻容器）· Vue 3 + Vite + TypeScript。

## 常用命令（Makefile）

根目录的 `Makefile` 把常用动作收成一组目标，`make`（不带参数）即列出全部：

```bash
make dev            # 起后端（连常驻 MySQL，http://localhost:8081）
make dev-frontend   # 起 Vite 开发服务器（:5173，/api 代理到 :8081）
make test           # 后端测试（需常驻 MySQL）
make build          # 打包「带前端界面」的 jar（-Pfrontend，需要 Node/pnpm）
make build-backend  # 只打后端 jar（不需要 Node）
make format         # 格式化：后端 Spotless、前端 Prettier
make check          # 只校验格式化、不改写（CI 用）
make db-up          # 只起常驻 MySQL 容器（:3306），dev 和 test 都依赖它
make docker-up      # Compose 起 MySQL + 应用
make clean          # 清掉 target/ 与构建出的前端产物
```

`make help` 打印同一份清单（含 db-up、docker-logs 等）。命令都是对 `./mvnw` / `pnpm` 的薄封装，直接敲原始命令同样可行。

## 快速开始

### 方式一：本地跑（需要常驻 MySQL）

只有一种数据库：真实 MySQL 8，就是 `docker-compose.yml` 里那个常驻容器（默认 `localhost:3306`）。先把它起起来，再跑后端：

```bash
make db-up                                                  # 或 docker compose up -d mysql
cp .env.example .env                                        # 填入 AI_API_KEY（或 DASHSCOPE_API_KEY）：环境变量的唯一入口
export JAVA_HOME="$HOME/.jdks/temurin-17/Contents/Home"     # 或任意 JDK 17
cd frontend && pnpm install && pnpm build && cd ..          # 可选：把界面也构建进去
./mvnw -pl backend spring-boot:run
```

打开 <http://localhost:8081>。数据落在容器里的 `ordermgt` 库（compose 的 `mysql-data` 卷），Flyway 会自动建表并写入 5 条商品种子数据。默认 profile 就是 `mysql`，连接参数取自 `MYSQL_*` 环境变量（`.env` 里的缺省值与 compose 一致）；容器没起时应用会直接启动失败，而不是悄悄换一个本地库。没有 Key 也能启动，但助手功能会调不通——想在没有 Key 的情况下把助手整条闭环走一遍，加上 `offline` profile（见「没有 Key 也能跑」）。

> **改了已发布的迁移文件怎么办**：`V1__init.sql` 这类文件的 checksum 记在 `flyway_schema_history` 里,改动之后已有数据库会在下次启动时报 `Migration checksum mismatch` 拒绝启动。本地重建即可:`docker compose down -v && make db-up`——整卷重建,商品种子由 Flyway 重新写入,演示数据可用冒烟用例重生成。测试库每次 `clean` + `migrate`,天然不受影响。

`.env` 是环境变量的唯一入口：compose 启动时插值读取，`make dev` / `make test` 等目标会整份导出给子进程，应用本身也通过 `spring.config.import` 直接加载它——所以 `./mvnw` 裸跑同样生效。已存在的真实环境变量优先于 `.env`。

想确认整条链路是否正常，跑冒烟用例（`make test` 里的 `SmokeDemoFlowTest`，模型用桩、不花 API 配额）：

```bash
./mvnw -pl backend test -Dtest=SmokeDemoFlowTest
# 只读查询 → 提议不执行 → 确认落单 → 重复确认不重复 → 越权 403 → 记忆
```

### 方式二：Docker Compose（MySQL + 应用）

```bash
cp .env.example .env      # 填入 AI_API_KEY（或 DASHSCOPE_API_KEY）；与方式一的本地运行共用同一份 .env
docker compose up --build
```

`mysql` 容器带 `healthcheck`，`app` 等它 `service_healthy` 后才启动——否则 Flyway 会和 MySQL 首次初始化抢跑。

✅ **这套流程已在本机实跑验证过**：镜像构建成功；MySQL 8 首次初始化 → Flyway 建表成功 → `/actuator/health=UP`；商品、下单、幂等重放、幂等冲突与 SPA 全部走**真 MySQL**通过。

> 本机网络下有两处需要绕，都属于**环境问题而非代码问题**，遇到同样报错可照抄：
>
> 1. **`colima start` 卡在下载 VM 镜像，或报 digest mismatch。** colima v0.10.3 内嵌的镜像摘要（`27652801…`）已经过期——`colima-core` v0.10.4 的发布物被重新上传过，GitHub API 当前给出的摘要（`4cd967d2…`）与我们实际下到的文件一致。用 `--disk-image` 指向本地镜像再加 `--force-disk-image` 跳过这个过期校验（文件本身是真品，已按 GitHub API 摘要核对）：
>    ```bash
>    curl -L -o /tmp/ubuntu.raw.gz \
>      https://gh-proxy.com/https://github.com/abiosoft/colima-core/releases/download/v0.10.4/ubuntu-24.04-minimal-cloudimg-amd64-docker.raw.gz
>    mkdir -p ~/.colima/images && mv /tmp/ubuntu.raw.gz ~/.colima/images/
>    colima start --disk-image ~/.colima/images/ubuntu.raw.gz --force-disk-image
>    ```
> 2. **`docker pull` 超时。** Docker Hub 在本机不可达，要给 VM 内的 Docker 配国内仓库镜像：写进 `~/.colima/default/colima.yaml` 的 `docker.registry-mirrors`，或直接改 VM 内 `/etc/docker/daemon.json` 再 `systemctl restart docker`。

## 接口

身份走**会话 cookie**，不走请求头：`POST /api/auth/login`（`customerId` + PIN，PIN 配在 `assistant.auth.pins`）建立会话，此后**一切查询都按该会话的客户收窄**——客户端无法再自称是别人，这是防越权的核心手段。未登录一律 `401`，前端据此回到登录页。写操作还要求 `X-XSRF-TOKEN` 头（值取自 `GET /api/auth/csrf` 写下的 `XSRF-TOKEN` cookie），这是 cookie 会话下必须的 CSRF 防护。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/login` | `customerId` + PIN 建会话；未知客户与错误 PIN 共用一条消息，不能被用来枚举客户 |
| POST | `/api/auth/logout` | 销毁会话 |
| GET | `/api/auth/me` | 当前会话的客户；未登录 `401` |
| GET | `/api/auth/csrf` | 取 CSRF token，并写下可读的 `XSRF-TOKEN` cookie |
| POST | `/api/assistant/chat?conversationId=` | 发消息；不带 `conversationId` 即开新对话。幂等键：请求头 `Idempotency-Key` |
| POST | `/api/assistant/conversations` | 新对话 |
| GET | `/api/assistant/conversations?includeArchived=true` | 会话列表；每项带 `status`（`ACTIVE`/`ARCHIVED`）与派生 `state`（`IDLE`/`RUNNING`/`WAITING_APPROVAL`，见 [ADR-0011](docs/adr/0011-run-is-the-unit-of-execution.md)），以及正在跑的 `runId`（无则 `null`）。默认不含归档，`includeArchived=true` 才带 |
| POST | `/api/assistant/conversations/{id}/archive` | 归档（只改可见性，见 [ADR-0013](docs/adr/0013-archive-hides-does-not-delete.md)）；非静止（有 RUNNING Run 或未过期卡片）→ `409` |
| POST | `/api/assistant/conversations/{id}/restore` | 恢复为 `ACTIVE`（幂等） |
| GET | `/api/assistant/conversations/{id}/messages` | 历史（含每张卡片的当前状态） |
| POST | `/api/assistant/conversations/{id}/messages` | 在指定会话里发消息；会话忙时 `409 CONFLICT`（见下） |
| GET | `/api/assistant/conversations/{id}/stream?message=` | SSE：`token` 事件逐个吐字，最后一发 `done` 带完整结果。SPA 用它做增量渲染（`fetch` + `ReadableStream`，因为要带会话 cookie 与 CSRF 头）。会话忙时**入队**，先补发 `queued`（带可删除的 `itemId`），轮到它时补发 `started`；被删掉的排队项以 `dropped` 收尾；开始跑时会补发 `run`（带 `runId`，停止按钮要用），被停止则以 `cancelled`（带半截回复）收尾 |
| POST | `/api/assistant/conversations/{id}/runs/{runId}/stop` | 停止一个 RUNNING 的 Run：真取消上游流、半截文本落库、Run 收 `CANCELLED`（见 [ADR-0012](docs/adr/0012-one-running-run-per-conversation.md)）；对已终态的 Run 幂等无副作用 |
| GET | `/api/assistant/conversations/{id}/runs/{runId}/events` | 重订阅一个正在跑的 Run：首帧 `snapshot` 带当前累积文本，之后是 `token` 增量；Run 不在了则以 `closed` 收尾（不悬挂）。支持多个订阅者 |
| DELETE | `/api/assistant/conversations/{id}/queue/{itemId}` | 删掉一条排队项（`itemId` 是内存态 id）；它**不会执行**，其连接收到 `dropped` 后关闭。不在队列里 → `404` |
| GET | `/api/assistant/events` | 每客户一条长活 SSE（见 [ADR-0014](docs/adr/0014-conversation-state-is-pushed.md)）：首帧 `snapshot`（该客户全部会话，含归档），之后 `state`/`queue` 增量与 `ping` 心跳；重连重发快照，不做 `Last-Event-ID`。每客户连接数上限 4，超出 `503` |
| POST | `/api/assistant/actions/{token}/confirm` | 确认并执行（重复确认返回 `replayed:true`，不会执行第二次） |
| POST | `/api/assistant/actions/{token}/reject` | 拒绝 |
| POST | `/api/orders` | 直接下单（幂等键：`Idempotency-Key`） |
| GET | `/api/orders` · `/api/orders/{orderNo}` | 订单列表 / 详情 |
| GET | `/api/catalog/items` | 商品与单价 |
| GET | `/swagger-ui.html` | OpenAPI 文档 |

助手的两个入口还各有**准入控制**：每客户每分钟的发送数（`assistant.ai.rate-limit`，默认 6）与全进程同时在飞的模型调用数（`assistant.ai.max-concurrent`，默认 8），超出都回 `429 RATE_LIMITED`（带 `Retry-After`）——前者挡住单个客户刷爆预算，后者挡住所有人一起冲垮上游。流式那一路另有一个有界线程池，池满回 `503 SERVICE_BUSY`（见 [ADR-0009](docs/adr/0009-stream-delivery-is-best-effort.md)）。

命令行试一下（假设应用在 8081）。注意**连登录在内的每个写请求都要带 `X-XSRF-TOKEN`**，所以先取一次 CSRF token；登录会刷新它，之后每次写之前重新取：

```bash
JAR=/tmp/om.jar
csrf() { curl -s -b $JAR -c $JAR localhost:8081/api/auth/csrf \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["token"])'; }

# 1. 登录建会话（PIN 配在 assistant.auth.pins，默认 1001:1111 / 2002:2222）
curl -s -b $JAR -c $JAR -X POST localhost:8081/api/auth/login \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" \
  -d '{"customerId":"1001","pin":"1111"}'

# 2. 让助手提议下单 —— 只返回卡片，不执行
curl -s -b $JAR -c $JAR -X POST localhost:8081/api/assistant/chat \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" \
  -d '{"message":"帮我下单 3 个 WIDGET-01"}'

# 3. 用上一步返回的 token 确认
curl -s -b $JAR -c $JAR -X POST localhost:8081/api/assistant/actions/<token>/confirm \
  -H "X-XSRF-TOKEN: $(csrf)"

# 4. 换一个客户登录，看不到也改不了 1001 的数据
JAR=/tmp/om2.jar
csrf() { curl -s -b $JAR -c $JAR localhost:8081/api/auth/csrf \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["token"])'; }
curl -s -b $JAR -c $JAR -X POST localhost:8081/api/auth/login \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" \
  -d '{"customerId":"2002","pin":"2222"}'
curl -s -b $JAR localhost:8081/api/orders
```

## 测试

```bash
make db-up                  # 测试跑在真实 MySQL 上，先把常驻容器起起来
./mvnw -pl backend test     # 等价于 make test
```

测试连的是常驻 MySQL 里的独立库 `ordermgt_test`（用 `root` + `createDatabaseIfNotExist` 创建），每次运行前由 Flyway `clean` + `migrate` 重建 schema——测试断言精确条数、并复用固定幂等键，必须从干净结构开始。它不碰开发库 `ordermgt`。

| 层 | 目标 | 用例 |
|---|---|---|
| L1 领域规则（纯单测） | 聚合与值对象的不变量 | `Money`、`Order`/`OrderLine` 状态机、`OrderNumber`、`ToolWhitelist`、`Message`、`Conversation`、`Run` 状态机、`ConversationState` 派生、`PendingAction`、`AiInvocation`（审计行的列约束） |
| L2 应用用例（Mockito） | 编排与边界，不碰数据库 | `OrderService`、`ConversationService`、`AssistantService`（含跨会话记忆隔离）、`ActionConfirmationService`、`ActionCardFactory`、`IdempotencyGuard`、`IdempotencySweeper`、`OrderToolSpecs`、`AssistantGroundingProvider`、`ConversationRunQueue`、`RunService`、`RunRegistry`、`RunReconciler`、`ConversationEventBus`、`CustomerRateLimiter`、`AiConcurrencyGuard`、`PinAuthenticationProvider`、`SpringAiAdapter`（超时/异常归类成 `TIMEOUT`/`ERROR`、首字节前重放策略逐分支、grounding facts 注入 system prompt） |
| L3 API 契约（MockMvc） | 统一响应体、校验、状态码、幂等与认证 | `ApiContractTest`、`SecurityContractTest`/`SecurityCsrfContractTest`（未登录 401；写操作缺 CSRF 头被拒）、`RateLimitContractTest`（每客户限流 → 429 `RATE_LIMITED` + `Retry-After`）、`StreamSaturationContractTest`（流式池打满 → 503 `SERVICE_BUSY`，不无限排队；`AsyncConfigTest` 逼出线程/队列边界）、`ConversationControllerTest`（断线只终止投递，回合照常落库）、`QueueContractTest`、`StopContractTest`、`RunReattachContractTest`、`EventsContractTest`、`ArchiveContractTest` |
| L4 集成（真 MySQL + 桩模型，不烧 Key） | 端到端业务流、Run 生命周期与并发正确性 | `AssistantFlowTest`（"提议→不执行→确认→落单→重复确认不重复落单→越权 403"、幂等键重放、每次调用落一行 `ai_invocations`、审计端点只许归属方读）、`RunPersistenceTest`（Run 落库与终态、重启把残留 `RUNNING` 对账成 `INTERRUPTED`）、`ArchiveIntegrationTest`、`OrderAssistantBusinessFlowTest`、`SmokeDemoFlowTest`、`OrderConcurrencyTest`（6 线程真实并发；含"幂等键 FAILED 重试只执行一次"，钉住并验证 `reclaim` 竞态修复） |
| L5 SPA 单元与组件（Vitest + jsdom） | 后端替客户端保证不了的那半份契约 | `api.spec`（SSE 分帧 / CSRF 头 / 错误体）、`store.spec`（快照与增量 patch）、`ConversationRow.spec`、`ActionCardPanel.spec`、`OrdersView.spec`、`AssistantView.spec`、`LoginView.spec`、`markdown.spec`、`toast.spec`、`errors.spec` |
| L6 浏览器端到端（Playwright + Chromium） | 真浏览器里走完"提议 → 确认 → 落单"与会话管理 | `catalog.spec`、`orders-identity.spec`、`assistant-flow.spec`、`sessions.spec`（归档/恢复） |

后两层是各自独立的命令：

```bash
make test-frontend   # L5：纯前端，不需要 MySQL
make test-e2e        # L6：自己去构建 SPA 并起应用（:8082），所以要先 make db-up
```

L5 里含金量最高的是一份 `api.spec.ts`：流式回复的分帧（一帧跨两次 `read`、CRLF、多行 `data`）、`done` 事件的整轮语义、每个写请求都带 `X-XSRF-TOKEN`、`401` 一律回登录页——这些都是**服务端无法替客户端保证**的契约，也正是这次补齐用例时揪出下面两个真实缺陷的地方。

L6 不信任 Vite dev server：`e2e/support/serve.mjs` 先把 SPA 构建进 `backend/src/main/resources/static`，再以 `mysql,offline` 两个 profile 起后端，于是浏览器访问的就是打包产物本身，与 API 同源、没有代理；助手是 `offline` 的桩适配器，所以每条流都确定、也不产生模型费用。身份如今是部署期配置（没有用户表），用例不能自造客户号，于是 `serve.mjs` 启动前先清空独立库 `ordermgt_e2e`、并用 `ASSISTANT_AUTH_PINS` 一次性配出 40 个固定客户号（700001–700040，PIN `4242`）供用例各自登录；身份与隔离都由这一步换来，所以 `reuseExistingServer` 关着——每次跑都拿一个刚清空的进程。

> **现状，说清楚：L6 自 2026-10-14 的会话登录改造之后一度没有跟进。** `e2e/support/viewer.ts` 那时仍往 localStorage 写 `ordermgt.customerId`、请求仍带 `X-Customer-Id`，而身份早已改成会话 cookie + PIN 登录，于是 14 条用例全部停在登录页之前。**2026-10-15 已把这 14 条迁到会话登录、实测 14/14 绿**（改法见上一段）。另一个浏览器层 `frontend/story-walk`（`playwright.walkthrough.config.ts`）按当前身份模型登录，对着**已经在跑**的应用驱动（`APP_PORT`，默认 :8081；容器或本地 `mysql,offline` 都行），一个故事留一张截图，US-1.9「最近一次下单」就在其中。

首次在另一台机器上跑 L6 要先装浏览器：`cd frontend && pnpm exec playwright install chromium`。（`@playwright/test` 钉在 1.49.1 —— 更新的版本已不再发布 macOS 12 可用的 Chromium。）

前端其余检查：`cd frontend && pnpm typecheck`（`vue-tsc`，覆盖 `e2e/`、`story-walk/` 与四个配置文件）。`pnpm build` 会先跑 Prettier，再交给 Vite。

## 代码风格

- **格式化**：后端用 Spotless（`google-java-format`，AOSP 风格，与既有 4 空格缩进一致）。`apply` 绑定在 `process-sources` 阶段，所以任何构建（如 `./mvnw -pl backend test`）都会**先自动格式化再编译**，不需要手动跑。前端用 Prettier（`frontend/.prettierrc.json`），`pnpm build` 同样会**先 `prettier --write` 再构建**。手动跑用 `make format`；只校验、不改写（CI）用 `make check`（即 `spotless:check` + `prettier --check`）。
- **对象映射**：PO/DTO/领域对象之间的转换统一交给 **MapStruct**（`*ApiMapper` / `*PersistenceMapper`，`componentModel = "spring"`），不再手写逐字段拷贝。注解处理器已在父 POM 配好（`mapstruct-processor` + `lombok-mapstruct-binding`，与 Lombok 共存）。少数 MapStruct 生成不了的地方——聚合根构造器私有、只能经 `rehydrate` 工厂重建（`Order`/`Conversation`/`PendingAction`）——保留为同一 mapper 接口里的 `default` 方法，调用点仍然统一。
- **样板代码**：统一用 Lombok —— 持久化对象（PO，如 `OrderPO`/`IdempotencyRecordPO`）用 `@Data`，构造器注入用 `@RequiredArgsConstructor`，日志用 `@Slf4j`。注入相关注解（`@Qualifier`/`@Value`）由根目录的 `lombok.config` 复制到生成的构造器参数上；配置项因此声明成带 `@Value` 的 final 字段，而构造期需要计算的成员（`ChatClient.Builder.build()`、`Duration` 换算）用私有访问器表达，好让构造器整个交给 Lombok。**API 出入参是 Java record**（`ChatRequest`、`OrderResponse` 等），不是 `@Data` 类——record 自带不可变与 `equals`/`hashCode`，正是 DTO 需要的。

## 目录结构

```
Makefile          常用命令入口（`make help` 看全部）
backend/src/main/java/com/example/ordermgt/
├── shared/       统一响应体、错误码、分页常量、全局异常处理
├── config/       MyBatis-Plus、OpenAPI、流式线程池、AI HTTP 超时
├── security/     会话认证（PIN 登录）、CSRF、@CurrentCustomer
├── idempotency/  通用幂等组件（domain / application / infrastructure）
├── catalog/      商品目录（价格的唯一来源）
├── order/        订单领域（Order 聚合根、状态流转、事务边界）
└── assistant/    助手领域（会话记忆、工具白名单、待确认动作、确认闭环、Run）
    ├── AiCallPolicy    模型调用的超时/重试常量（适配器与控制器共用）
    ├── domain/         无框架依赖：Conversation、Message、Run、PendingAction、ToolSpec、ToolCard、ToolResult、AiAssistantPort
    ├── application/    AssistantService（编排）、ConversationService、ActionConfirmationService、ConversationRunQueue、RunService、RunRegistry、RunReconciler
    ├── infrastructure/ Spring AI 适配器（ai/prompt 下集中提示词）、MyBatis-Plus 持久化、过期清理任务
    └── api/            控制器、DTO 与 MapStruct 的 ApiMapper
frontend/         Vue 3 SPA（产物直接构建进 backend/src/main/resources/static）
├── src/          api.ts（含 SSE 客户端与登录）、store.ts（会话状态单例）、constants.ts（屏级常量）、视图、组件；同名 *.spec.ts 是 L5 用例
├── e2e/          Playwright 用例（L6）+ support/serve.mjs（构建 SPA 并起离线后端）
└── vitest.config.ts / playwright.config.ts
docs/             项目文档
├── adr/          架构决策记录（0001–0015）
├── 面试作业-*.md  需求摘录 / 设计定稿 / 讲解手册
└── 会话管理-设计定稿.md  Run 层设计
```

依赖方向：`api → application → domain`，`infrastructure → domain`（实现端口）。**domain 不依赖任何框架。**
全仓有一处刻意的例外：`assistant/infrastructure/ai/tools/OrderToolDefinitions` 把只读工具接到 `order.application.OrderService` 上——工具定义本质是装配代码（哪个工具名对应哪个业务用例），这条 `infrastructure → application` 的边是单向的，`order` 侧不反向依赖 `assistant`，不构成环。

## 提示词与常量放在哪

"改措辞要翻遍适配器、改个超时要全仓 grep" 是这里要避免的。归置规则见 [ADR-0010](docs/adr/0010-prompts-and-constants-live-in-one-place.md)，一句话概括——**按"离得最近的原作者是谁"归置，而不是堆进一个全局 `Constants`**：

- **提示词**：`assistant/infrastructure/ai/prompt/` 一处——`AiPrompts`（system prompt，以及 catalog 与本会话最近一单两类 grounding 模板）与 `OfflinePrompts`（离线桩台词）。提示词只管措辞，填数据仍由 `AssistantGroundingProvider` 做（[ADR-0010](docs/adr/0010-prompts-and-constants-live-in-one-place.md)）。
- **随环境调的数字**：`application.yml`（记忆窗口、卡片 TTL、流式并发/队列、幂等保留期、`spring.ai.retry`）。
- **跨模块的固定数字**：`shared/api/Paging`（分页）、`assistant/AiCallPolicy`（流式时长、首 token 重试、阻塞传输的 5s/60s 超时）。前端对应 `src/constants.ts`。
- **领域不变量**：留在拥有它的类型里（`Message.CONTENT_MAX`、`PendingAction.ARGUMENTS_MAX`、`OrderNumber.MAX_LENGTH`、`Money.SCALE` …），因为它和该类型的校验/截断是同一条规则的两半。

## 模型的超时、重试与审计

三件事都集中在适配器与编排层，**不在业务事务里**（ADR-0002）：

- **超时**：1.1.8 没有 `spring.ai.*.timeout` 属性，所以同步调用通过 `RestClientCustomizer` 换上一个带超时的 `JdkClientHttpRequestFactory`（连接 5s / 读取 60s，取自 `AiCallPolicy`，见 `AiHttpClientConfig`）；流式调用在 `SpringAiAdapter` 里用 Reactor `.timeout(2min)` + `.blockLast(2min)` 双上界，与 `ConversationController` 的 SSE 超时同读 `AiCallPolicy.STREAM_TIMEOUT_MS`（类路径上没有反应式连接器，WebClient 走 JDK HttpClient，没有全局响应超时可设）。
- **重试**：Spring AI 自带 `RetryTemplate`，默认 10 次 / 退避 `2s×5` 上限 3 分钟——一个卡死的上游最坏能被拖近 19 分钟。我们只用属性把默认收到 **3 次 / 1s / ×2 / 上限 8s**。流式不被框架重试，所以 `respondStreaming` 自建**同款**退避（`AiCallPolicy`）：**首字节之前**失败整条重放（3 次 / 1s / ×2 / 上限 8s），首字节之后不再重试——重放会把已吐出的字再吐一遍。两处都**没有** jitter、没有熔断；重复执行的风险由幂等键与卡片行锁兜住。
- **审计**：每次模型调用都往 `ai_invocations` 落一行——成功走 `AssistantService.complete`，失败与超时在 `recordFailure`（同步的 `chat` 与流式的 `streamTurn` 都会调用），失败行带上 model、latency 与 `ERROR`/`TIMEOUT`。**一次调用永远一行**：参数被拒后有界重问（[ADR-0016](docs/adr/0016-refuse-illegal-arguments-and-re-ask-once.md)）时，被丢弃的那次记 `ERROR`、重问那次记它自己的结果，所以一轮顾客发送最多两行。审计是旁路：写不进去只记一条 warn，绝不影响本轮回复。读取走 `GET /api/assistant/conversations/{id}/invocations`（归属校验同历史，他人会话 403）。

## 回复长什么样：只读结果由服务端渲染成人话

顾客的气泡里**没有原始 JSON**。只读工具（`queryMyOrders` / `getOrderByNumber`）在 schema 与 handler 旁边各带一个 `ToolReport`（负载 → markdown），`AssistantService` 把**模型自己的话在前、渲染块在后**拼成一条回复；负载本身不进 transcript，只随响应的 `executed[].resultJson` 出去，供审计与排障。渲染形态是「一行引导 + 每单一块」：`` `订单号` `` · 下单时间 · 中文状态 · `¥`合计，下面每行一条明细——订单号与 sku 包进行内代码保持等宽可复制，标签与金额走正文。空结果说「你名下还没有订单。」而不是 `[]`；引导句**永远点名最近一张**（「你名下有 N 张订单，最近一张是 \`ORD-…\`：」），列不全时改成「你名下共 N 张订单，以下只列出最近 K 张」（缺省的 K 就是共享的 `Paging.DEFAULT_SIZE`，模型也可以用 `limit` 只要最近几张），不把一页当成全部历史。这条不是修辞：模型常常只调工具、一个字都不写（实测发生过），那时回复**完全**由这里组成，而「最近一次下单」的答案必须在服务端就说得出来。见 [ADR-0016](docs/adr/0016-refuse-illegal-arguments-and-re-ask-once.md)。

这条不是装饰：**transcript 里的那份人话就是下一轮的记忆内容**（US-2.1 靠它），所以订单号与金额必须在渲染后仍然在场。变更工具的结果不进回复——它由确认卡呈现，因此 `ToolSpec` 的不变量是「只读 ⇒ 有 `ToolReport`、无 `ToolCard`；变更 ⇒ 有 `ToolCard`、无 `ToolReport`」。另一侧修的是指代：`Grounding` 现在除 catalog 外还注入「本会话最近一次已确认的订单号」（来源是 `pending_actions.result_ref`，按客户 + 会话过滤），于是「查看刚刚这个订单」有了确定答案。见 [ADR-0015](docs/adr/0015-render-readonly-results-server-side.md)。

## 会话与运行（Run）

「发送 → 回复」不再是隐式的：每一次执行都对应一行 **Run**（`runs` 表，`RUNNING` 起始，收尾为 `COMPLETED` / `FAILED` / `CANCELLED` / `INTERRUPTED` 之一；同一会话至多一个 `RUNNING`）。同步与流式两个入口走**同一条执行回合**，所以断线的流式回合照样落库；所有新状态都待在 Run 层，**Message 层没有状态列**（[ADR-0009](docs/adr/0009-stream-delivery-is-best-effort.md)）。见 [ADR-0011](docs/adr/0011-run-is-the-unit-of-execution.md)。

侧栏的会话状态是**派生**的、不入库：有活跃 Run → `RUNNING`，否则有未过期卡片 → `WAITING_APPROVAL`，否则 `IDLE`。徽标只显示后两者——`IDLE` 是安静的默认。

进程重启即一次对账：单实例启动那一刻不可能有活 Run，于是启动时（`RunReconciler`，一次性的 `ApplicationRunner`，不是定时器）把库里残留的 `RUNNING` 一律标成 `INTERRUPTED` 并补上 `ended_at`，让状态列说真话。被中断的回合在历史里就是「有问无答」——客户消息早已落库、回复没有——SPA 给出一个**重试**入口：把那句话作为**全新的 Run** 重发，绝不续传半截。

忙碌的会话**只有一个回合在跑**，后来的**流式**发送进这条会话的**内存队列**（FIFO，`ConversationRunQueue`，进程内注册表）：先补一发 `queued`（带位置与可删除的 `itemId`），轮到它时补发 `started`，然后照常吐字；删掉一条排队项（`DELETE .../queue/{itemId}`）**它就不会执行**（连接收 `dropped` 后关闭）。队列深度上限 `assistant.run.queue-depth`（5），排满再发 → `429 QUEUE_FULL`——满了是**容量**问题，所以不是 409。**同步**入口（`POST /{id}/messages`）带幂等键、承诺"要么立刻跑要么拒绝"，所以忙时直接 `409 CONFLICT`，不排队。排队项**不落库**、也不建 Run 行：它们只活在运行态注册表里，随进程重启一起丢。（[ADR-0012](docs/adr/0012-one-running-run-per-conversation.md)）

**停止是客户的显式意图，不是断线的副作用。** `POST .../runs/{runId}/stop` 只对 `RUNNING` 的 Run 生效、且幂等；它真的掐断上游流（`takeWhile` 到下一个 chunk 就取消订阅，之后的 token 不再被读取或计费），把已经吐出的**半截文本作为一条普通 ASSISTANT 消息**落库（不写 Message 状态），Run 收 `CANCELLED`，`ai_invocations` 记一行 `CANCELLED`（该列 `VARCHAR` 无 CHECK，**零迁移**）。前端在「助手正在拟…」那行给一个**停止**按钮；它与队列条的**删除**是两个动作——停止作用于正在跑的 Run，删除作用于排队项。（[ADR-0012](docs/adr/0012-one-running-run-per-conversation.md)）

**切走再切回一个正在跑的会话**，能接着看它吐字：`GET .../runs/{runId}/events` 首帧给**当前累积文本快照**，之后是 `token` 增量，多个订阅者可以同时看同一条 Run。服务端只持有"当前累积文本"（不缓存逐 delta），所以快照是无状态的读、不是续传；Run 不在了（已终态 / 本进程没跑它）就以 `closed` 收尾，不悬挂。（[ADR-0014](docs/adr/0014-conversation-state-is-pushed.md)）

**归档是可见性，不是删除。** `POST .../archive` 只改 `conversations.status`（`VARCHAR` 无 CHECK，**零迁移**），归档的会话从默认列表隐藏、仍可读、可一键恢复，**没有硬删除**；**非静止**（有 RUNNING Run 或未过期卡片）归档 → `409`——归档只管可见性，终止是上面那个显式的停止。向已归档会话发一条消息即**自动恢复**为 `ACTIVE`。（[ADR-0013](docs/adr/0013-archive-hides-does-not-delete.md)）

**会话状态靠推送，不靠轮询。** `GET /api/assistant/events` 是每客户一条长活 SSE：首帧全量 `snapshot`（含归档），之后推 `state`（某会话派生状态或 `runId` 变了）与 `queue`（队列变了）增量，`ping` 保活；重连重发快照、客户端整体替换，不做 `Last-Event-ID`。这条流**不走回合池**——它由进程内的 `ConversationEventBus` 维护 emitter 注册表，不长期占线程，配每客户连接数上限（4，超出 `503`）。前端用一个**零依赖**的模块级单例 store（`useSessionStore`，仍只有 `vue` 一个运行时依赖）承载列表 / 运行态 / 队列 / 订阅，`AssistantView` 降为它的一个视图。（[ADR-0014](docs/adr/0014-conversation-state-is-pushed.md)）

## 换一个 OpenAI 兼容网关 / 模型

适配器只讲 OpenAI 协议，端点由环境变量决定，所以换网关不必改代码：

```bash
AI_BASE_URL=https://api.commandcode.ai/provider   # 不要带 /v1：Spring AI 自己拼 /v1/chat/completions
AI_MODEL=deepseek/deepseek-v4.1-flash
AI_API_KEY=<该网关的 key>
```

三条都写在 `.env`（唯一入口），compose 与 `./mvnw` 裸跑都吃得到。`AI_API_KEY` 与作业自带的 `DASHSCOPE_API_KEY` 二选一即可，两个都没有时 compose **拒绝启动**，而不是拿占位符去调模型。

注意 `AI_BASE_URL` / `AI_MODEL` **留空不等于"用默认值"**：空串会被当成真值传给占位符，`application.yml` 里的默认就不再生效——写成 `AI_BASE_URL=` 会直接启动失败（`OpenAI base URL must be set`）。要换就填真值，不换就整行注释掉（`.env.example` 里就是这么留的）。

## 没有 Key 也能跑（离线模式）

```bash
SPRING_PROFILES_ACTIVE=mysql,offline ./mvnw -pl backend spring-boot:run
```

`offline` 激活 `MockAiAdapter`（同时用 `@Profile("!offline")` 关掉真实的 `SpringAiAdapter`），它是一个关键词驱动的桩：说「我有哪些订单」它会提议 `queryMyOrders`，说「帮我下单 3 个 GIZMO-03」它会提议 `createOrder`——于是"提议 → 确认 → 落单"整条闭环在没有任何 API Key 的情况下照样走得通。`mysql` 那一半负责给数据源。集成测试也复用同一个类，所以桩的行为与测试看到的一致。

## 已知缺口（诚实清单）

- **退避是简版的**：阻塞重试靠 Spring AI 属性（3 次 / 1s / ×2 / 8s）；流式只在**首字节前**自建同款重放，**中途失败不重试**（会重复已吐给客户的字）。两处都没有 jitter、没有熔断/降级。
- **真实网络超时→真实退避没有假上游测试**：超时归类（`TIMEOUT`/`ERROR`）与重试策略在单测里逐分支钉住（`SpringAiAdapterTest`，10 例），但要断言"真超时触发真退避"就得引入假上游，当前选择不引入，只靠真上游冒烟观察日志。
- 离线适配器 `MockAiAdapter` 是**关键词驱动的桩**，只用于没有 Key 的演示，不理解真实语义。
- **E2E 只跑 Chromium**（`@playwright/test` 1.49.1，更新的版本不再发布 macOS 12 可用的 Chromium），WebKit / Firefox 未覆盖；且它假定常驻 MySQL 可用、助手走 `offline` 桩——真实模型下的流式与超时行为没有 E2E。浏览器层跑法有两套：`frontend/e2e`（L6）起自己的 `:8082`、清空专用库、用 `serve.mjs` 配出的固定客户池登录，2026-10-15 迁到会话登录后实测 14/14 绿；`frontend/story-walk` 对着常驻应用走查。
- **`make test-frontend` / `make test-e2e` 没有接进 CI**：本仓库目前没有 CI 配置，`make check` 只是留给 CI 的入口。
- Docker Compose 只起**单实例应用**：确认态的并发去重靠数据库行锁，多实例同样成立，但没有做过压测。
- **「至多一个 RUNNING Run」只在进程内保证**：`ConversationRunQueue` 与队列都是内存注册表，库层不强制唯一 RUNNING，多实例下各算各的；队列中的项也随重启丢弃。队列会通过 `/api/assistant/events` 的 `queue` 增量推给**所有**标签页，但只推内存态 `itemId` 与位置——排队消息的**文字只存在于发起它的那个标签页里**，另一个标签页能看见、也能删，但显示不出它写了什么。不含每客户并发配额。
- **停止只掐得断流式那一路**：取消信号由流式模型调用逐 chunk 观测（`takeWhile`），所以 `POST .../stop` 对**流式**回合是真的取消上游；**阻塞**回合（`POST /{id}/messages`）是一次同步模型调用，服务端没有中途 abort 的钩子，对它调停止会被接受但取消不了那次调用（SPA 只发流式，所以实际不会遇到）。取消是"到下一个 chunk 为止"，不是瞬时。
- **状态总线与运行态注册表都是进程内的**：`ConversationEventBus`（每客户 emitter 注册表）与 `RunRegistry` 不跨实例，多实例下同一客户的多个标签页连到不同实例就收不到对方的状态推送；`/events` 单条连接有个 1 小时上限，到点客户端重连并整体换快照（换来零服务端事件缓存）。
- **重订阅是尽力而为**：`runStream` 只做"当前累积文本 + 之后增量"，不做断点续传（ADR-0009）；一条 Run 在重订阅的同一瞬间结束，客户端可能先收到 `closed` 再收到 `snapshot`，此时按"没东西可回放"处理。E2E 里离线桩的回合太快，**没有覆盖重订阅**——它由 L3（契约）与 L5（组件）钉住。
- **服务端渲染的措辞是中文硬编码**：提示词已改为"用顾客使用的语言回答"，模型自己的话会跟着切换，`ToolReport` 渲染的那几行（「你名下有 N 张订单，最近一张是 …」、`已创建`）不会。改动前落库的历史消息仍是旧格式 `[工具名] {JSON}`，前端的机器文本通道已删除，它们以普通正文呈现——没有做数据迁移。
