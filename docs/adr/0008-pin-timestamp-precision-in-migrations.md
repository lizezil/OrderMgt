# 小数列的默认值必须写明同样的精度：`TIMESTAMP(3)` 配 `CURRENT_TIMESTAMP(3)`

**背景。** 我们希望**一份** Flyway 脚本同时给 H2（`MODE=MySQL`，本地零依赖）和 MySQL 8（交付目标）建库，而不是维护两份方言不同的迁移。`V1__init.sql` 一开始写的是 `created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP`。

**问题。** 这个写法在 H2 的 MySQL 兼容模式下**通过**，在真 MySQL 8 上直接失败：

```
ERROR 1067 (42000): Invalid default value for 'created_at'
```

原因是列声明了小数秒精度 3，而默认值 `CURRENT_TIMESTAMP` 是精度 0。**MySQL 要求两者一致**；`CURRENT_TIMESTAMP` 只是 `CURRENT_TIMESTAMP(0)` 的简写，不是"跟随列精度"。容器一启动 Flyway 就报错，应用起不来——而在此之前，所有本地测试都是跑在 H2 上、全绿。

**决定。** 迁移脚本里凡 `TIMESTAMP(n)` 列，默认值一律写成 `CURRENT_TIMESTAMP(n)`；这条约束写在 `V1__init.sql` 头部注释里，后续新增迁移同样适用。

**为什么不是别的做法。**

- *改成不带精度的 `TIMESTAMP`*：会丢掉毫秒，而会话排序、`last_message_at` 倒序、并发确认的先后判断都依赖它。
- *给 MySQL 和 H2 各写一份迁移*：直接放弃了"一份脚本两边通跑"的性质，而正是这个性质让本地开发不需要装任何东西；代价是两条方言开始各自漂移，再没有人知道哪边才是真的。
- *放宽容错（`flyway.validateOnMigrate=false` 之类）*：这里不是校验问题，是 MySQL 真的拒绝执行该 DDL，容错没有意义。

**这个 ADR 真正想记的不是那个语法。** 是"只在兼容模式下验证的迁移，等于没验证"：H2 的 `MODE=MySQL` 会放过一批 MySQL 会拒绝的写法，因此**跑一次真 MySQL 是这类脚本唯一的验收标准**。这条缺陷就是本机 Docker 跑起来、3 个 Testcontainers 测试真正执行后才暴露的（设计定稿 §12.1）。
