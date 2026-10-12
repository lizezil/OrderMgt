# 用 catalog 事实给模型做 grounding，而不是加一个 listCatalog 工具

模型会把商品名当成 sku：实测 `"帮我下单 3 个 Widget"` 返回 `{"sku": "Widget"}`，而真实 sku 是 `WIDGET-01`，参数校验拦下后请求直接失败。我们改为在**每次请求时**把当前 catalog（sku / 名称 / 单价）拼进 system prompt：事实由服务端提供，模型只负责选 sku 和数量。

考虑过的替代方案：注册一个只读工具 `listCatalog` 让模型自己先查。放弃的原因是它把"别猜 sku"变成模型的自觉行为——模型可能仍然先编一个 sku，然后才想起来去查；而 system prompt 里的 grounding 是每次调用都在场的。代价是每次会话请求多一次 catalog 查询（5 行数据，可忽略），且 system prompt 随 catalog 变化、不利于 prompt 缓存。

注意这条和"价格由服务端解析"是两件事：grounding 解决的是模型**选错 sku**，服务端解析解决的是模型**报错价**。两者都不能省。
