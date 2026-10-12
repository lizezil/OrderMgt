# AI 能力封装为自有端口，Spring AI 只做其中一个实现

业务层只依赖 `assistant.domain` 里的 `AiAssistantPort` 端口；Spring AI 的 `ChatClient` 只出现在 `infrastructure.ai.SpringAiAdapter` 里，测试用 `MockAiAdapter`。对外可替换（换成手写 HTTP、换模型、换供应商）而不动业务层。

选它的原因：题面把"AI 服务适配层与业务层解耦"列为加分项，而把 `ChatClient` 直接注入 service 会让解耦只停留在口号上。代价是多一层接口与实现映射，换来的是可测试性与可辩护性。
