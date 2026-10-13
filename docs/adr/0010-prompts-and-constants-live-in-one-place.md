# 提示词与魔法数字：各自的"家"，而不是散落在业务代码里

两类东西都会随时间被改，改的人却常常找不到它们：**发给模型的提示词**（system prompt、grounding 文本、离线桩的固定台词）和**裸落各处的数字**（超时、重试、分页、各种列宽上限）。决定按"离得最近的原作者是谁"归置，而不是塞进一个大而全的 `Constants`：

- **提示词全部集中到 `assistant/infrastructure/ai/prompt/`**：`AiPrompts`（system prompt + catalog grounding 的头与行模板）、`OfflinePrompts`（`MockAiAdapter` 的固定台词）。改措辞只翻这一处；填充数据的类（`CatalogGroundingProvider`）仍负责把真实 catalog 拼进模板，提示词只管 **wording**，不管它被填进什么数据。
- **可随环境调的数字进 `application.yml`**（沿用现状）：记忆窗口、卡片 TTL、流式并发/队列、幂等保留期、`spring.ai.retry` 与模型名/温度。这些是部署形态，不是代码常量。
- **跨模块的固定数字放一个具名之家**：`shared/api/Paging`（页码/页大小/上限，被订单控制器、`OrderService` 与助手的只读工具共用）、`assistant/AiCallPolicy`（流式时长、首字节重试策略、阻塞传输的 5s/60s 超时——`SpringAiAdapter`、`ConversationController`、`AiHttpClientConfig` 三处一起读它）。前端对应 `src/constants.ts`。
- **领域不变量留在拥有它的类型里**：`Message.CONTENT_MAX`、`PendingAction.ARGUMENTS_MAX`、`AiInvocation.ERROR_MAX`、`OrderNumber.MAX_LENGTH`、`Money.SCALE` 等与各自列宽/规则绑定的数字，就写在那个类里并注明列宽来源。

**为什么不合成一个全局常量文件？** 那些领域上限是**该类型的不变量**——它和 `cap()`/构造器是同一条规则的两半，搬走会让"改列宽要改哪"从一处变成两处。集中不等于堆在一起：跨模块的、以及会被人翻找的提示词，集中；只属于一个类型、且已具名的，就近。前端同理：`src/constants.ts` 只收屏级旋钮，UI 文案仍就近写在模板里（那是排版，不是配置）。

**代价，说明白：** 提示词与常量多了一层间接。`frontend/src/constants.ts` 里的页大小与后端 `Paging.DEFAULT_SIZE` 是**故意的两份**——两端独立部署，谁也 import 不了谁；各自改一次，注释里互相指认。
