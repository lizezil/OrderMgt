# `TIMESTAMP(3)` 列的默认值必须写成 `CURRENT_TIMESTAMP(3)`

**背景。** 迁移脚本 `V1__init.sql` 直接对交付目标——MySQL 8——建库。一开始 `created_at` 写的是 `created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP`。

**问题。** 这个写法在 MySQL 8 上直接失败：

```
ERROR 1067 (42000): Invalid default value for 'created_at'
```

原因是列声明了小数秒精度 3，而默认值 `CURRENT_TIMESTAMP` 是精度 0。**MySQL 要求两者一致**；`CURRENT_TIMESTAMP` 只是 `CURRENT_TIMESTAMP(0)` 的简写，不是"跟随列精度"。容器一启动 Flyway 就报错，应用起不来。

**决定。** 迁移脚本里凡 `TIMESTAMP(n)` 列，默认值一律写成 `CURRENT_TIMESTAMP(n)`；这条约束写在 `V1__init.sql` 头部注释里，后续新增迁移同样适用。

**为什么不是别的做法。**

- *改成不带精度的 `TIMESTAMP`*：会丢掉毫秒，而会话排序、`last_message_at` 倒序、并发确认的先后判断都依赖它。
- *放宽容错（`flyway.validateOnMigrate=false` 之类）*：这里不是校验问题，是 MySQL 真的拒绝执行该 DDL，容错没有意义。

**这个 ADR 真正想记的不是那个语法。** 是"迁移脚本唯一的验收标准，是跑在**真实目标库**上"。这条缺陷当年正是被真 MySQL 跑出来的；后来为了让这类缝隙无处可藏，项目**彻底去掉了 H2**——本地开发、测试、交付全部跑同一套真实 MySQL 8，中间不再隔任何兼容模式。
