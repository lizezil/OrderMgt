# 锁定 Spring Boot 3.5 + Spring AI 1.1.8，而非最新的 Boot 4 + Spring AI 2.0

题目明确推荐"Java 17+、Spring Boot 3.x"。Spring AI 2.0.x 强制要求 Spring Boot 4.0.x/4.1.x，会偏离这条推荐栈；且 Boot 4 刚发布，可参考示例少，两天窗口内风险高。

选择 1.1.8 的额外好处：其审批闸口 API `ToolCallingChatOptions.builder().internalToolExecutionEnabled(false)` 正是本项目"变更动作需人工确认"所需的写法，且有官方文档背书。权衡的代价是放弃最新版特性。
