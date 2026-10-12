# Order Assistant

一个订单管理服务：客户用自然语言让 AI 助手查询自己的订单，**并在确认后**才真正创建或变更订单。

核心主张只有一句：**AI 提议，人确认，服务端校验，价格由服务端决定。** 围绕它，这个仓库主要想说明四件事：

1. **AI 的输出是不可信输入。** 模型只能返回白名单里的工具名 + 结构化参数；变更类操作永远不会自动执行，而是落成一张待确认卡片（`PendingAction`），由发起它的客户点击确认后才在事务里执行。见 [ADR-0001](docs/adr/0001-pending-action-confirmation.md)。
2. **AI 调用绝不进数据库事务。** 模型可能跑好几秒，事务里不能握着连接等它。见 [ADR-0002](docs/adr/0002-ai-calls-outside-transaction.md)。
3. **业务代码不依赖 Spring AI。** 业务只依赖 `AiAssistantPort`，Spring AI 藏在适配器后面，测试/离线用桩替换。见 [ADR-0003](docs/adr/0003-ai-behind-own-port.md)。
4. **价格只有一个来源。** 模型只填 sku 和数量，金额由服务端从 `catalog_items` 解析（[ADR-0006](docs/adr/0006-ground-model-with-catalog.md) 记录了一次真实的 sku 幻觉与修法）。

项目文档都收在 [`docs/`](docs/)：设计定稿（决策表、表结构、接口契约、里程碑与实测记录）见 [面试作业-设计定稿.md](docs/面试作业-设计定稿.md)，
需求梳理与坑位分析见 [面试作业-需求梳理与坑位分析.md](docs/面试作业-需求梳理与坑位分析.md)，
直播演示的逐步脚本与讲解要点见 [面试作业-演示脚本与讲解要点.md](docs/面试作业-演示脚本与讲解要点.md)；
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
make smoke          # 对 :8081 跑一遍端到端冒烟脚本
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
export JAVA_HOME="$HOME/.jdks/temurin-17/Contents/Home"     # 或任意 JDK 17
export DASHSCOPE_API_KEY=sk-...                             # 缺省时应用仍能启动，只是调用模型会失败
cd frontend && pnpm install && pnpm build && cd ..          # 可选：把界面也构建进去
./mvnw -pl backend spring-boot:run
```

打开 <http://localhost:8081>。数据落在容器里的 `ordermgt` 库（compose 的 `mysql-data` 卷），Flyway 会自动建表并写入 5 条商品种子数据。默认 profile 就是 `mysql`，连接参数取自 `MYSQL_*` 环境变量（默认值与 compose 一致）；容器没起时应用会直接启动失败，而不是悄悄换一个本地库。没有 Key 也能启动，只是助手功能不可用。

想确认整条链路是否正常，用自带脚本打一遍（真调模型，约 10 秒）：

```bash
./scripts/smoke-demo.sh      # 只读查询 → 提议不执行 → 确认落单 → 重复确认不重复 → 越权 403 → 记忆
```

### 方式二：Docker Compose（MySQL + 应用）

```bash
cp .env.example .env      # 填入 DASHSCOPE_API_KEY
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
| GET | `/api/assistant/conversations` | 会话列表 |
| GET | `/api/assistant/conversations/{id}/messages` | 历史（含每张卡片的当前状态） |
| POST | `/api/assistant/conversations/{id}/messages` | 在指定会话里发消息 |
| GET | `/api/assistant/conversations/{id}/stream?message=` | SSE：`token` 事件逐个吐字，最后一发 `done` 带完整结果 |
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

| 层次 | 覆盖 |
|---|---|
| 领域单测 | `PendingAction`（归属/一次性流转/过期/超长参数）、`Conversation`（标题派生） |
| 应用单测 | `IdempotencyGuard`（重放、键挪用、并发重复、失败重试）、`ActionConfirmationService`（执行一次、重放、越权、过期） |
| 集成（真 MySQL，不烧 Key） | `AssistantFlowTest`：真库 + 桩模型，跑通"提议→不执行→确认→落单→重复确认不重复落单→越权 403"，以及幂等键重放 |

前端：`cd frontend && pnpm typecheck && pnpm build`（`build` 会先跑 Prettier，再交给 Vite）。

## 代码风格

- **格式化**：后端用 Spotless（`google-java-format`，AOSP 风格，与既有 4 空格缩进一致）。`apply` 绑定在 `process-sources` 阶段，所以任何构建（如 `./mvnw -pl backend test`）都会**先自动格式化再编译**，不需要手动跑。前端用 Prettier（`frontend/.prettierrc.json`），`pnpm build` 同样会**先 `prettier --write` 再构建**。手动跑用 `make format`；只校验、不改写（CI）用 `make check`（即 `spotless:check` + `prettier --check`）。
- **对象映射**：PO/DTO/领域对象之间的转换统一交给 **MapStruct**（`*ApiMapper` / `*PersistenceMapper`，`componentModel = "spring"`），不再手写逐字段拷贝。注解处理器已在父 POM 配好（`mapstruct-processor` + `lombok-mapstruct-binding`，与 Lombok 共存）。少数 MapStruct 生成不了的地方——聚合根构造器私有、只能经 `rehydrate` 工厂重建（`Order`/`Conversation`/`PendingAction`）——保留为同一 mapper 接口里的 `default` 方法，调用点仍然统一。
- **样板代码**：统一用 Lombok —— PO/DTO 用 `@Data`，构造器注入用 `@RequiredArgsConstructor`，日志用 `@Slf4j`。注入相关注解（如 `@Qualifier`）由根目录的 `lombok.config` 保留到生成的构造器参数上。

## 目录结构

```
Makefile          常用命令入口（`make help` 看全部）
backend/src/main/java/com/example/ordermgt/
├── shared/       统一响应体、错误码、全局异常处理
├── config/       MyBatis-Plus、OpenAPI、流式线程池
├── idempotency/  通用幂等组件（domain / application / infrastructure）
├── catalog/      商品目录（价格的唯一来源）
├── order/        订单领域（Order 聚合根、状态流转、事务边界）
└── assistant/    助手领域（会话记忆、工具白名单、待确认动作、确认闭环）
    ├── domain/         无框架依赖：Conversation、Message、PendingAction、ToolSpec、ToolCard、ToolResult、AiAssistantPort
    ├── application/    AssistantService（编排）、ConversationService、ActionConfirmationService
    ├── infrastructure/ Spring AI 适配器、MyBatis-Plus 持久化、过期清理任务
    └── api/            控制器、DTO 与 MapStruct 的 ApiMapper
frontend/         Vue 3 SPA（产物直接构建进 backend/src/main/resources/static）
scripts/          smoke-demo.sh 等运维脚本
docs/             项目文档
├── adr/          架构决策记录
└── 面试作业-*.md  需求梳理 / 设计定稿 / 演示脚本
```

依赖方向：`api → application → domain`，`infrastructure → domain`（实现端口）。**domain 不依赖任何框架。**

## 已知缺口（诚实清单）

- `ai_invocations` 表已建但**尚未写入**：token 消耗目前只回在响应体里，没有落审计表。
- SSE 只在服务端实现并已验证；SPA 走的是 JSON 接口，**没有接流式**（工具轮本来就是原子的，流式对它没有意义）。
- 没有 `Idempotency-Key` 超时清理任务：进程在"动作已提交"与"标记 COMPLETED"之间挂掉，该键会停在 `IN_PROGRESS` 直到过期（见 [ADR-0007](docs/adr/0007-two-idempotency-paths.md)）。
- 模型调用没有退避重试；流式调用有 2 分钟上界（`SpringAiAdapter.STREAM_TIMEOUT`），同步调用依赖客户端默认超时。
- Docker Compose 只起**单实例应用**：确认态的并发去重靠数据库行锁，多实例同样成立，但没有做过压测。
