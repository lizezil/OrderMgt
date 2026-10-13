# Run 是一次执行的单位：运行态落库，会话状态由它派生

今天「一次发送 → 一次回复」是隐式的：它绑在一次 `GET /{id}/stream` 请求上，进程里没有身份、库里没有痕迹。要做停止、重订阅、状态展示、队列，就必须先给这次执行一个名字和一行记录。这个名字是 **Run**（一次被调度的执行实例），这行记录是 `runs` 表。

**Run 是从属于 Conversation 的记录，不是聚合根**，地位与 Message 相同。它的领域不变量只有一条：**同一个 Conversation 至多有一个处于 `RUNNING` 的 Run**（见 [ADR-0012](0012-one-running-run-per-conversation.md)）。Run 的状态取 `RUNNING` 与四个终态 `COMPLETED / FAILED / CANCELLED / INTERRUPTED`；**不设 `QUEUED`**——排队中的消息不落库，没有行可标（ADR-0012）。失败原因存 `error VARCHAR(1000) NULL`，宽度对齐 `ai_invocations.error`。

**读以库为权威，注册表只持有「活的东西」。** 侧栏与历史里的会话状态一律从 `runs` 表 + `pending_actions` 派生，进程内的运行态注册表只拿正在跑的任务句柄、SSE 订阅者和内存队列。这样任何标签页、任何时候读到的状态都一致（[ADR-0014](0014-conversation-state-is-pushed.md) 的状态流也据此推送）。**会话的展示状态只有三值 `IDLE / RUNNING / WAITING_APPROVAL`，且全部派生**：有活跃 Run→`RUNNING`；否则有未过期的 `PENDING` 卡片→`WAITING_APPROVAL`；否则 `IDLE`。`FAILED` 不进状态枚举——失败已有 `ai_invocations` 的审计行，做成常驻状态只会吵。

**进程重启即一次对账。** 单实例启动那一刻不可能有活 Run，所以启动时（`ApplicationRunner`，一次性，不是 `@Scheduled`）把库里残留的 `RUNNING` 一律标成 `INTERRUPTED` 并补 `ended_at`，让状态列说真话——与 `PendingActionSweeper` 同样的脾气。被中断的 Run 在历史里表现为「有问无答」（客户消息在 `prepare` 里已落库），修正手段是**重发一条全新的 Run**，绝不去"续"旧的半截（[ADR-0009](0009-stream-delivery-is-best-effort.md) 已否决断点续传）。

**这不违反 ADR-0009，而是在它之上加一层。** ADR-0009 禁止的是 **Message 级**的状态列，理由是「按消息批量 UPDATE 内容」的写放大与「回复停在半句话」的观感。`runs` 表是 **per-run 一行**、不存内容、一次执行只写两三次，两个问题都不沾。因此规矩不变：**所有新状态待在 Run 层，永不回到 Message 层。**

**代价，说明白：** 多一张表、读列表多一次查询（`(conversation_id, status)` 索引扛住）；内存队列随重启一起丢（与「排队不落库」同一取舍）；不变量只在进程内保证，MySQL 没有 partial index，库层不强制「唯一 RUNNING」——单实例下这条成立，多实例下会破，记入已知缺口。
