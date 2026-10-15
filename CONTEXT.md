# Order Assistant

客户通过对话让 AI 助手查询自己的订单，**并在确认后**才创建订单或流转它的状态的上下文。

## Language

### 参与者

**Customer**:
拥有订单与对话的人；每张订单、每段对话都归属于一位 Customer。
_Avoid_: User, Account, Principal, Buyer, Client

### 订单

**Order**:
Customer 的一次购买，带生命周期状态。
_Avoid_: Purchase, Transaction, Bill

**OrderLine**:
Order 中的一行"商品 + 数量 + 单价"。
_Avoid_: Item, Product, SKU

**OrderNumber**:
Order 对外的人类可读编号，全局唯一。
_Avoid_: OrderId（那是内部标识）

**OrderStatus**:
Order 的生命周期状态：CREATED / COMPLETED / CANCELLED。
_Avoid_: State

**CatalogItem**:
可下单的商品条目，是**价格的唯一来源**——价格只在**创建订单那一刻**由它解析，随后作为快照留在 OrderLine 上；目录之后改价不影响已存在的订单。
_Avoid_: Product, SKU, Goods

### 助手

**Conversation**:
一位 Customer 与助手之间的一段连续对话，各自持有独立的上下文记忆。"新对话"即新建一个 Conversation。
_Avoid_: Session, Thread, Chat, Dialog

**Message**:
Conversation 中的一条发言，角色为 CUSTOMER / ASSISTANT / TOOL。
_Avoid_: Turn, Utterance, Entry

**Run**:
一次被调度的执行实例——一条客户消息触发的一次「准备 → 模型调用 → 落库」，也是停止、重订阅与队列的粒度。同一 Conversation 至多一个处于 RUNNING 的 Run。
_Avoid_: Session, Turn, Job, Task

**ToolCall**:
助手为完成请求而要求执行的一个业务动作，分只读与变更两类。
_Avoid_: Intent, Function, Command, Skill

**ReadonlyToolCall**:
不改变任何数据的 ToolCall，可直接执行。
_Avoid_: QueryAction, SafeCall

**MutatingToolCall**:
会改变数据的 ToolCall，必须先转化为 PendingAction 并由 Customer 确认。
_Avoid_: WriteCall, DangerousCall

**ToolReport**:
只读 ToolCall 的结果讲给 Customer 听的样子（服务端渲染的人话）；它同时是 Message 里那份记忆的内容。与 ToolCard 互为镜像：只读有 Report、变更有 Card。
_Avoid_: MachineText, RawResult

### 确认

**PendingAction**:
由一个 MutatingToolCall 产生、尚未生效的动作；只有发起它的 Customer 能确认或拒绝。
_Avoid_: Card, Draft, Proposal, Confirmation

**ActionCard**:
PendingAction 呈现给前端的形态（标题、字段、确认入口）；是表现，不是领域概念。
_Avoid_: —

**AIInvocation**:
一次模型调用的审计记录（模型、token、耗时、结果）。
_Avoid_: AiLog, Call

**Grounding**:
调用模型前，由服务端把**模型不得臆造的事实**注入提示词。当前包括：可用商品的 sku 与名称与单价，以及本会话最近一次已确认动作产出的订单号（后者让「刚刚那个订单」有确定答案，见 ADR-0015）。
_Avoid_: Context injection, RAG, Prompt stuffing
