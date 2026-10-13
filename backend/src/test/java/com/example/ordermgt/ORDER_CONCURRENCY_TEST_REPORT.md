# OrderConcurrencyTest 交付说明与缺陷报告

交付物：`backend/src/test/java/com/example/ordermgt/OrderConcurrencyTest.java`（6 个真实线程并发用例，真实 MySQL 8）。主代码零改动。

## 用例清单与结果

| 用例 | 并发方式 | 结果 |
|------|---------|------|
| 同一张卡片被并发确认只落一单 | 6 线程同时 confirm 同一 token | 绿：恰好 1 个执行（`replayed=false`），5 个重放；订单恰好 1 单 |
| 并发的确认与拒绝只有先到者生效 | confirm 与 reject 同时打同一 token | 绿：恰好一方成功，另一方 409 CONFLICT；终态与赢家一致 |
| 相同幂等键并发下单只落一单 | 4 线程 MockMvc 同 key 同 body | 绿：恰好 1 单；201/409 混合且无 500；所有 201 的 orderNo 相同 |
| 不带幂等键的并发下单互不干扰 | 6 线程不同数量并发 createOrder | 绿：6 单全部成功，订单号无重复，金额=目录价×数量 |
| 并发流转订单状态只允许一个赢家 | 2×COMPLETED + 2×CANCELLED 并发 | 绿：恰好 1 个赢家，其余 409（乐观锁版本闸生效） |
| 幂等键失败后并发重试只执行一次动作 | FAILED 记录 + 6 线程同 key 并发 chat | **红：模型被调 6 次（应为 1）——缺陷①证据** |

运行方式：`make db-up && ./mvnw -pl backend test -Dtest=OrderConcurrencyTest`
（类内 customer id 使用 7500 段，与其他重量级类的 7100/7300/9001 段隔离。）

## 缺陷①（红用例钉住）：幂等键 FAILED 重试的 reclaim 竞态

**现象**：同一 `Idempotency-Key` 的上一次尝试失败（记录为 FAILED）后，6 个并发重试全部拿到执行权，受保护的动作（此处是模型调用+落卡片）执行了 6 次。

**根因**：`IdempotencyGuard.claim()` 的 FAILED 分支（IdempotencyGuard.java:138-145 附近）是 check-then-act：先读记录、见 FAILED 就调用 `repository.reclaim(id)`。而 `reclaim()`（IdempotencyRepositoryImpl.java:67-73）是**无条件** `updateById`——没有 `WHERE status='FAILED'` 条件。两个并发重试同时读到 FAILED，都执行了 reclaim（后者把前者刚置的 IN_PROGRESS 又写回 IN_PROGRESS，无从察觉），双双返回 `Claim.claimed`，于是动作被执行多次。

对比：正常争用路径的 `tryClaim` 是纯 INSERT、靠 `uk_idem` 唯一约束仲裁，是安全的；reclaim 路径恰恰绕开了这道仲裁。

**证据**（`./mvnw -pl backend test -Dtest=OrderConcurrencyTest`）：

```
幂等键失败后并发重试只执行一次动作:335
expected: 1
 but was: 6
```

日志中 6 个线程依次打出 `retrying previously failed idempotent operation SEND_MESSAGE key=retry-race-7502`。

**修复建议**（二选一）：

1. **条件更新 + 归属校验（推荐）**：`reclaim` 改为带条件的 UPDATE——
   `UPDATE idempotency_records SET status='IN_PROGRESS', completed_at=NULL WHERE id=? AND status='FAILED'`，
   返回受影响行数；为 0 说明别人已抢先，此时重新 `find` 该记录按状态分流（IN_PROGRESS→409、COMPLETED→重放）。MyBatis-Plus 下用 `mapper.update(patch, Wrappers.lambdaUpdate().eq(IdempotencyRecordPO::getId, id).eq(IdempotencyRecordPO::getStatus, "FAILED"))` 即可，与既有 `expireStaleInProgress` 的写法一致。
2. **唯一约束兜底**：把 reclaim 拆成"DELETE 旧行 + 重新 INSERT"，让 INSERT 再次由 `uk_idem` 仲裁；缺点是丢失审计历史，不推荐。

修复后该红用例自动转绿，无需改动测试。

**修复状态（2026-10-13）**：缺陷①已按建议①修复——`reclaim` 改为条件更新（`WHERE status='FAILED'`，返回是否抢到），Guard 输掉竞态时重读记录分流（COMPLETED→重放、其余→409），红用例已转绿；另补两个单测覆盖输掉竞态的两条路径（`IdempotencyGuardTest`）。缺陷②如上文勘误，无需修复。

## 计划中另一个"缺陷探测"的勘误

计划里标注的"② orders.version 乐观锁空壳"经实现阶段核实为**误报**：`OrderRepositoryImpl.save()`（OrderRepositoryImpl.java:36-57）手工实现了乐观锁——UPDATE 带 `WHERE id=? AND version=?`，0 行命中即抛 `BusinessException(CONFLICT)`。并发流转用例（2×COMPLETED vs 2×CANCELLED）实测恰好一个赢家，验证了这道版本闸在真实并发下有效。不需要 `@Version` 注解或 OptimisticLocker 拦截器。

## 交付过程中的环境说明

编写与验证期间，**另一个会话在同一工作区和同一个 `ordermgt_test` 库上并发执行 `mvnw clean` + `make test`**（已抓到进程证据），并对助手模块做了中转英重构（提交 6f08152/df08a5a，新增 MySqlMigrationAndLockingTest）。这导致全量套件出现与本类无关的失败：NoClassDefFound（target 被 clean 重建）、Table doesn't exist / Unknown column 'version'（共享测试库被对端 flyway clean 抢先）。本测试类已适配关键词桩的英文消息（"help me order 3 GIZMO-03"），并在安静窗口两次单跑稳定复现 5 绿 1 红。
