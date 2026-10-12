# 幂等键独立成表，而非散落在业务表列上

把 `client_request_id` 当作 `orders` 的一列，只能覆盖"创建订单"这一件事，无法覆盖"发送消息"等同样会被客户端重试的用例；它还把技术性关注点塞进了业务聚合，让 `orders` 承担了它不该负责的语义。

因此建一张通用 `idempotency_records` 表，键为 `(customer_id, operation, idempotency_key)`，记录处理状态与结果引用，由 `IdempotencyGuard` 在用例边界统一处理：命中 COMPLETED 则重放原响应，命中 IN_PROGRESS 则拒绝并发重复，键相同但 `request_hash` 不同则报"键被复用"。确认接口不走这张表——`pending_actions` 的 token 加状态机本身就是幂等的。

代价是多一张表和一次额外写入；换来的是幂等语义统一、业务表保持干净、并且能覆盖任意可重试用例。
