# Order Assistant

客户通过对话让 AI 助手查询自己的订单，并在**确认后**创建或变更订单的上下文。

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
可下单的商品条目，是**价格的唯一来源**。
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
调用模型前，由服务端把模型不得臆造的事实（当前可用商品的 sku 与单价）注入提示词。
_Avoid_: Context injection, RAG, Prompt stuffing
