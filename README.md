# Order Assistant

一个订单管理服务：客户用自然语言让 AI 助手查询自己的订单，**并在确认后**才真正创建或变更订单。

核心主张只有一句：**AI 提议，人确认，服务端校验，价格由服务端决定。** 围绕它，这个仓库主要想说明四件事：

1. **AI 的输出是不可信输入。** 模型只能返回白名单里的工具名 + 结构化参数；变更类操作永远不会自动执行，而是落成一张待确认卡片（`PendingAction`），由发起它的客户点击确认后才在事务里执行。见 [ADR-0001](docs/adr/0001-pending-action-confirmation.md)。
2. **AI 调用绝不进数据库事务。** 模型可能跑好几秒，事务里不能握着连接等它。见 [ADR-0002](docs/adr/0002-ai-calls-outside-transaction.md)。
3. **业务代码不依赖 Spring AI。** 业务只依赖 `AiAssistantPort`，Spring AI 藏在适配器后面，测试/离线用桩替换。见 [ADR-0003](docs/adr/0003-ai-behind-own-port.md)。
4. **价格只有一个来源。** 模型只填 sku 和数量，金额由服务端从 `catalog_items` 解析（[ADR-0006](docs/adr/0006-ground-model-with-catalog.md) 记录了一次真实的 sku 幻觉与修法）。

项目文档都收在 [`docs/`](docs/)：原题需求摘录见 [面试作业-需求摘录.md](docs/面试作业-需求摘录.md)，
设计定稿（决策表、表结构、接口契约、里程碑与实测记录）见 [面试作业-设计定稿.md](docs/面试作业-设计定稿.md)，
需求梳理与坑位分析见 [面试作业-需求梳理与坑位分析.md](docs/面试作业-需求梳理与坑位分析.md)，
直播演示的逐步脚本与讲解要点见 [面试作业-演示脚本与讲解要点.md](docs/面试作业-演示脚本与讲解要点.md)，
面向追问型面试的备课手册（要求对照/结构/实现/讲法/复习路径）见 [面试作业-准备手册.md](docs/面试作业-准备手册.md)，
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
cp .env.example .env                                        # 填入 DASHSCOPE_API_KEY：环境变量的唯一入口
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
cp .env.example .env      # 填入 DASHSCOPE_API_KEY；与方式一的本地运行共用同一份 .env
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

所有业务接口都需要请求头 `X-Customer-Id`（模拟登录态），并且**一切查询都按它收窄**——这是防越权的核心手段。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/assistant/chat?conversationId=` | 发消息；不带 `conversationId` 即开新对话。幂等键：请求头 `Idempotency-Key` |
| POST | `/api/assistant/conversations` | 新对话 |
| GET | `/api/assistant/conversations` | 会话列表；每项带 `status`（`ACTIVE`/`ARCHIVED`）与派生 `state`（`IDLE`/`RUNNING`/`WAITING_APPROVAL`，见 [ADR-0011](docs/adr/0011-run-is-the-unit-of-execution.md)） |
| GET | `/api/assistant/conversations/{id}/messages` | 历史（含每张卡片的当前状态） |
| POST | `/api/assistant/conversations/{id}/messages` | 在指定会话里发消息；会话忙时 `409 CONFLICT`（见下） |
| GET | `/api/assistant/conversations/{id}/stream?message=` | SSE：`token` 事件逐个吐字，最后一发 `done` 带完整结果。SPA 用它做增量渲染（`fetch` + `ReadableStream`，因为要带 `X-Customer-Id` 头）。会话忙时**入队**，先补发 `queued`（带可删除的 `itemId`），轮到它时补发 `started`；被删掉的排队项以 `dropped` 收尾 |
| DELETE | `/api/assistant/conversations/{id}/queue/{itemId}` | 删掉一条排队项（`itemId` 是内存态 id）；它**不会执行**，其连接收到 `dropped` 后关闭。不在队列里 → `404` |
| POST | `/api/assistant/actions/{token}/confirm` | 确认并执行（重复确认返回 `replayed:true`，不会执行第二次） |
| POST | `/api/assistant/actions/{token}/reject` | 拒绝 |
| POST | `/api/orders` | 直接下单（幂等键：`Idempotency-Key`） |
| GET | `/api/orders` · `/api/orders/{orderNo}` | 订单列表 / 详情 |
| GET | `/api/catalog/items` | 商品与单价 |
| GET | `/swagger-ui.html` | OpenAPI 文档 |

命令行试一下（假设应用在 8081）：

```bash
# 1. 让助手提议下单 —— 只返回卡片，不执行
curl -s -X POST localhost:8081/api/assistant/chat \
  -H 'Content-Type: application/json' -H 'X-Customer-Id: 1001' \
  -d '{"message":"帮我下单 3 个 WIDGET-01"}'

# 2. 用上一步返回的 token 确认
curl -s -X POST localhost:8081/api/assistant/actions/<token>/confirm -H 'X-Customer-Id: 1001'

# 3. 换成别的客户，看不到也改不了 1001 的数据
curl -s localhost:8081/api/orders -H 'X-Customer-Id: 2002'
```

## 测试

```bash
make db-up                  # 测试跑在真实 MySQL 上，先把常驻容器起起来
./mvnw -pl backend test     # 等价于 make test
```

测试连的是常驻 MySQL 里的独立库 `ordermgt_test`（用 `root` + `createDatabaseIfNotExist` 创建），每次运行前由 Flyway `clean` + `migrate` 重建 schema——测试断言精确条数、并复用固定幂等键，必须从干净结构开始。它不碰开发库 `ordermgt`。

| 层 | 目标 | 用例 |
|---|---|---|
| L1 领域规则（纯单测） | 聚合与值对象的不变量 | `Money`、`Order`/`OrderLine` 状态机、`OrderNumber`、`ToolWhitelist`、`Message`、`Conversation`、`PendingAction`、`AiInvocation`（审计行的列约束） |
| L2 应用用例（Mockito） | 编排与边界，不碰数据库 | `OrderService`、`ConversationService`、`AssistantService`（含跨会话记忆隔离）、`ActionConfirmationService`、`ActionCardFactory`、`IdempotencyGuard`、`OrderToolSpecs`、`CatalogGroundingProvider`（目录 grounding：每个在售 sku 与单价进提示词） |
| L3 API 契约（MockMvc） | 统一响应体、校验、状态码、幂等头 | `ApiContractTest`、`StreamSaturationContractTest`（流式池打满 → 503 `SERVICE_BUSY`，不无限排队；`AsyncConfigTest` 逼出线程/队列边界）、`ConversationControllerTest`（断线只终止投递，回合照常落库）、`SpringAiAdapter`（超时/异常归类成 `TIMEOUT`/`ERROR` + 首字节前重放策略 + grounding facts 注入 system prompt） |
| L4 集成（真 MySQL + 桩模型，不烧 Key） | 端到端业务流与并发正确性 | `AssistantFlowTest`（"提议→不执行→确认→落单→重复确认不重复落单→越权 403"、幂等键重放、每次调用落一行 `ai_invocations`、审计端点只许归属方读）、`OrderAssistantBusinessFlowTest`、`SmokeDemoFlowTest`、`OrderConcurrencyTest`（6 线程真实并发；含"幂等键 FAILED 重试只执行一次"，钉住并验证 `reclaim` 竞态修复） |
| L5 SPA 单元与组件（Vitest + jsdom） | 后端替客户端保证不了的那半份契约 | `api.spec`（SSE 分帧 / 身份头 / 错误体）、`ActionCardPanel.spec`、`OrdersView.spec`、`AssistantView.spec`、`toast.spec`、`errors.spec` |
| L6 浏览器端到端（Playwright + Chromium） | 真浏览器里走完"提议 → 确认 → 落单" | `catalog.spec`、`orders-identity.spec`、`assistant-flow.spec` |

后两层是各自独立的命令：

```bash
make test-frontend   # L5：纯前端，不需要 MySQL
make test-e2e        # L6：自己去构建 SPA 并起应用（:8082），所以要先 make db-up
```

L5 里含金量最高的是一份 `api.spec.ts`：流式回复的分帧（一帧跨两次 `read`、CRLF、多行 `data`）、`done` 事件的整轮语义、每个请求都带 `X-Customer-Id`——这些都是**服务端无法替客户端保证**的契约，也正是这次补齐用例时揪出下面两个真实缺陷的地方。

L6 不信任 Vite dev server：`e2e/support/serve.mjs` 先把 SPA 构建进 `backend/src/main/resources/static`，再以 `mysql,offline` 两个 profile 起后端，于是浏览器访问的就是打包产物本身，与 API 同源、没有代理；助手是 `offline` 的桩适配器，所以每条流都确定、也不产生模型费用。E2E 连的是独立库 `ordermgt_e2e`，每个用例现取一个没人用过的客户号，因此可以反复跑而不必清库。

首次在另一台机器上跑 L6 要先装浏览器：`cd frontend && pnpm exec playwright install chromium`。（`@playwright/test` 钉在 1.49.1 —— 更新的版本已不再发布 macOS 12 可用的 Chromium。）

前端其余检查：`cd frontend && pnpm typecheck`（`vue-tsc`，覆盖 `e2e/` 与三个配置文件）。`pnpm build` 会先跑 Prettier，再交给 Vite。

## 代码风格

- **格式化**：后端用 Spotless（`google-java-format`，AOSP 风格，与既有 4 空格缩进一致）。`apply` 绑定在 `process-sources` 阶段，所以任何构建（如 `./mvnw -pl backend test`）都会**先自动格式化再编译**，不需要手动跑。前端用 Prettier（`frontend/.prettierrc.json`），`pnpm build` 同样会**先 `prettier --write` 再构建**。手动跑用 `make format`；只校验、不改写（CI）用 `make check`（即 `spotless:check` + `prettier --check`）。
- **对象映射**：PO/DTO/领域对象之间的转换统一交给 **MapStruct**（`*ApiMapper` / `*PersistenceMapper`，`componentModel = "spring"`），不再手写逐字段拷贝。注解处理器已在父 POM 配好（`mapstruct-processor` + `lombok-mapstruct-binding`，与 Lombok 共存）。少数 MapStruct 生成不了的地方——聚合根构造器私有、只能经 `rehydrate` 工厂重建（`Order`/`Conversation`/`PendingAction`）——保留为同一 mapper 接口里的 `default` 方法，调用点仍然统一。
- **样板代码**：统一用 Lombok —— 持久化对象（PO，如 `OrderPO`/`IdempotencyRecordPO`）用 `@Data`，构造器注入用 `@RequiredArgsConstructor`，日志用 `@Slf4j`。注入相关注解（`@Qualifier`/`@Value`）由根目录的 `lombok.config` 复制到生成的构造器参数上；配置项因此声明成带 `@Value` 的 final 字段，而构造期需要计算的成员（`ChatClient.Builder.build()`、`Duration` 换算）用私有访问器表达，好让构造器整个交给 Lombok。**API 出入参是 Java record**（`ChatRequest`、`OrderResponse` 等），不是 `@Data` 类——record 自带不可变与 `equals`/`hashCode`，正是 DTO 需要的。

## 目录结构

```
Makefile          常用命令入口（`make help` 看全部）
backend/src/main/java/com/example/ordermgt/
├── shared/       统一响应体、错误码、分页常量、全局异常处理
├── config/       MyBatis-Plus、OpenAPI、流式线程池
├── idempotency/  通用幂等组件（domain / application / infrastructure）
├── catalog/      商品目录（价格的唯一来源）
├── order/        订单领域（Order 聚合根、状态流转、事务边界）
└── assistant/    助手领域（会话记忆、工具白名单、待确认动作、确认闭环）
    ├── AiCallPolicy    模型调用的超时/重试常量（适配器与控制器共用）
    ├── domain/         无框架依赖：Conversation、Message、PendingAction、ToolSpec、ToolCard、ToolResult、AiAssistantPort
    ├── application/    AssistantService（编排）、ConversationService、ActionConfirmationService
    ├── infrastructure/ Spring AI 适配器（ai/prompt 下集中提示词）、MyBatis-Plus 持久化、过期清理任务
    └── api/            控制器、DTO 与 MapStruct 的 ApiMapper
frontend/         Vue 3 SPA（产物直接构建进 backend/src/main/resources/static）
├── src/          api.ts（含 SSE 客户端）、constants.ts（屏级常量）、视图、组件；同名 *.spec.ts 是 L5 用例
├── e2e/          Playwright 用例（L6）+ support/serve.mjs（构建 SPA 并起离线后端）
└── vitest.config.ts / playwright.config.ts
docs/             项目文档
├── adr/          架构决策记录
└── 面试作业-*.md  需求梳理 / 设计定稿 / 演示脚本
```

依赖方向：`api → application → domain`，`infrastructure → domain`（实现端口）。**domain 不依赖任何框架。**
全仓有一处刻意的例外：`assistant/infrastructure/ai/tools/OrderToolDefinitions` 把只读工具接到 `order.application.OrderService` 上——工具定义本质是装配代码（哪个工具名对应哪个业务用例），这条 `infrastructure → application` 的边是单向的，`order` 侧不反向依赖 `assistant`，不构成环。

## 提示词与常量放在哪

"改措辞要翻遍适配器、改个超时要全仓 grep" 是这里要避免的。归置规则见 [ADR-0010](docs/adr/0010-prompts-and-constants-live-in-one-place.md)，一句话概括——**按"离得最近的原作者是谁"归置，而不是堆进一个全局 `Constants`**：

- **提示词**：`assistant/infrastructure/ai/prompt/` 一处——`AiPrompts`（system prompt、catalog grounding 模板）与 `OfflinePrompts`（离线桩台词）。提示词只管措辞，填数据仍由 `CatalogGroundingProvider` 做。
- **随环境调的数字**：`application.yml`（记忆窗口、卡片 TTL、流式并发/队列、幂等保留期、`spring.ai.retry`）。
- **跨模块的固定数字**：`shared/api/Paging`（分页）、`assistant/AiCallPolicy`（流式时长、首 token 重试、阻塞传输的 5s/60s 超时）。前端对应 `src/constants.ts`。
- **领域不变量**：留在拥有它的类型里（`Message.CONTENT_MAX`、`PendingAction.ARGUMENTS_MAX`、`OrderNumber.MAX_LENGTH`、`Money.SCALE` …），因为它和该类型的校验/截断是同一条规则的两半。

## 模型的超时、重试与审计

三件事都集中在适配器与编排层，**不在业务事务里**（ADR-0002）：

- **超时**：1.1.8 没有 `spring.ai.*.timeout` 属性，所以同步调用通过 `RestClientCustomizer` 换上一个带超时的 `JdkClientHttpRequestFactory`（连接 5s / 读取 60s，取自 `AiCallPolicy`，见 `AiHttpClientConfig`）；流式调用在 `SpringAiAdapter` 里用 Reactor `.timeout(2min)` + `.blockLast(2min)` 双上界，与 `ConversationController` 的 SSE 超时同读 `AiCallPolicy.STREAM_TIMEOUT_MS`（类路径上没有反应式连接器，WebClient 走 JDK HttpClient，没有全局响应超时可设）。
- **重试**：Spring AI 自带 `RetryTemplate`，默认 10 次 / 退避 `2s×5` 上限 3 分钟——一个卡死的上游最坏能被拖近 19 分钟。我们只用属性把默认收到 **3 次 / 1s / ×2 / 上限 8s**。流式不被框架重试，所以 `respondStreaming` 自建**同款**退避（`AiCallPolicy`）：**首字节之前**失败整条重放（3 次 / 1s / ×2 / 上限 8s），首字节之后不再重试——重放会把已吐出的字再吐一遍。两处都**没有** jitter、没有熔断；重复执行的风险由幂等键与卡片行锁兜住。
- **审计**：每次模型调用都往 `ai_invocations` 落一行——成功走 `AssistantService.complete`，失败与超时在 `recordFailure`（同步的 `chat` 与流式的 `streamTurn` 都会调用），失败行带上 model、latency 与 `ERROR`/`TIMEOUT`。审计是旁路：写不进去只记一条 warn，绝不影响本轮回复。读取走 `GET /api/assistant/conversations/{id}/invocations`（归属校验同历史，他人会话 403）。

## 会话与运行（Run）

「发送 → 回复」不再是隐式的：每一次执行都对应一行 **Run**（`runs` 表，`RUNNING` 起始，收尾为 `COMPLETED` / `FAILED` / `CANCELLED` / `INTERRUPTED` 之一；同一会话至多一个 `RUNNING`）。同步与流式两个入口走**同一条执行回合**，所以断线的流式回合照样落库；所有新状态都待在 Run 层，**Message 层没有状态列**（[ADR-0009](docs/adr/0009-stream-delivery-is-best-effort.md)）。见 [ADR-0011](docs/adr/0011-run-is-the-unit-of-execution.md)。

侧栏的会话状态是**派生**的、不入库：有活跃 Run → `RUNNING`，否则有未过期卡片 → `WAITING_APPROVAL`，否则 `IDLE`。徽标只显示后两者——`IDLE` 是安静的默认。

进程重启即一次对账：单实例启动那一刻不可能有活 Run，于是启动时（`RunReconciler`，一次性的 `ApplicationRunner`，不是定时器）把库里残留的 `RUNNING` 一律标成 `INTERRUPTED` 并补上 `ended_at`，让状态列说真话。被中断的回合在历史里就是「有问无答」——客户消息早已落库、回复没有——SPA 给出一个**重试**入口：把那句话作为**全新的 Run** 重发，绝不续传半截。

忙碌的会话**只有一个回合在跑**，后来的**流式**发送进这条会话的**内存队列**（FIFO，`ConversationRunQueue`，进程内注册表）：先补一发 `queued`（带位置与可删除的 `itemId`），轮到它时补发 `started`，然后照常吐字；删掉一条排队项（`DELETE .../queue/{itemId}`）**它就不会执行**（连接收 `dropped` 后关闭）。队列深度上限 `assistant.run.queue-depth`（5），排满再发 → `429 QUEUE_FULL`——满了是**容量**问题，所以不是 409。**同步**入口（`POST /{id}/messages`）带幂等键、承诺"要么立刻跑要么拒绝"，所以忙时直接 `409 CONFLICT`，不排队。排队项**不落库**、也不建 Run 行：它们只活在运行态注册表里，随进程重启一起丢。（[ADR-0012](docs/adr/0012-one-running-run-per-conversation.md)）

## 没有 Key 也能跑（离线模式）

```bash
SPRING_PROFILES_ACTIVE=mysql,offline ./mvnw -pl backend spring-boot:run
```

`offline` 激活 `MockAiAdapter`（同时用 `@Profile("!offline")` 关掉真实的 `SpringAiAdapter`），它是一个关键词驱动的桩：说「我有哪些订单」它会提议 `queryMyOrders`，说「帮我下单 3 个 GIZMO-03」它会提议 `createOrder`——于是"提议 → 确认 → 落单"整条闭环在没有任何 API Key 的情况下照样走得通。`mysql` 那一半负责给数据源。集成测试也复用同一个类，所以桩的行为与测试看到的一致。

## 已知缺口（诚实清单）

- **退避是简版的**：阻塞重试靠 Spring AI 属性（3 次 / 1s / ×2 / 8s）；流式只在**首字节前**自建同款重放，**中途失败不重试**（会重复已吐给客户的字）。两处都没有 jitter、没有熔断/降级。
- **真实网络超时→真实退避没有假上游测试**：超时归类（`TIMEOUT`/`ERROR`）与重试策略在单测里逐分支钉住（`SpringAiAdapterTest`，10 例），但要断言"真超时触发真退避"就得引入假上游，当前选择不引入，只靠真上游冒烟观察日志。
- 离线适配器 `MockAiAdapter` 是**关键词驱动的桩**，只用于没有 Key 的演示，不理解真实语义。
- **E2E 只跑 Chromium**（`@playwright/test` 1.49.1，更新的版本不再发布 macOS 12 可用的 Chromium），WebKit / Firefox 未覆盖；且它假定常驻 MySQL 可用、助手走 `offline` 桩——真实模型下的流式与超时行为没有 E2E。
- **`make test-frontend` / `make test-e2e` 没有接进 CI**：本仓库目前没有 CI 配置，`make check` 只是留给 CI 的入口。
- Docker Compose 只起**单实例应用**：确认态的并发去重靠数据库行锁，多实例同样成立，但没有做过压测。
- **「至多一个 RUNNING Run」只在进程内保证**：`ConversationRunQueue` 与队列都是内存注册表，库层不强制唯一 RUNNING，多实例下各算各的；队列中的项也随重启丢弃、且只有发起那条连接能看见并删除它（把队列推给**所有**客户端是 `/api/assistant/events` 的活）。不含每客户并发配额。
