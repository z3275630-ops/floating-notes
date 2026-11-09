# T07 Room 持久化验证

2026-11-09。Room schema v1，禁用破坏性迁移。笔记、版本、待办索引和 Outbox 在同一事务保存，已提交后清理相应草稿。UUID 固定笔记、块和草稿身份。用 edit_seq 和 base_revision 阻止迟到的回调覆盖新内容。未知块与有格式标记的块保留原 JSON，切片编辑器按只读显示。

Android 集成测试覆盖关闭并重新打开同一数据库、草稿恢复、重复保存、迟到写入、同事务 Outbox 失败回滚并重试。后者通过 SQLite 触发器注入，真实磁盘写满与系统强杀另测。首版没有旧 schema 迁移路径，构建导出的 v1 schema 将归档作为后续迁移基线。

首轮 CI 42542798438 的测试 APK 编译失败。RoomDatabase 不实现 Closeable，测试改为 try/finally 调用 close()，重新执行。

多变体构建 42543240307 在 kaptBenchmarkKotlin 失败。将共享 schemaLocation 迁移至 Room Gradle 插件管理的变体输出，并开启失败堆栈。

源码 `db6ced5` 的 [Android CI 42543663684](https://github.com/z3275630-ops/floating-notes/actions/runs/42543663684) 全绿。debug、release、benchmark、lint、测试 APK 和 debug 签名通过。JVM 测试为 NO-SOURCE。API35 Android 15 模拟器安装并运行 8 项测试，包含 3 项 Room 测试与 T04 导航拖拽。构建实际导出的 v1 schema 已提交。

[构建和 schema](https://github.com/z3275630-ops/floating-notes/actions/runs/42543663684/artifacts/13642328652) · [录像与测试报告](https://github.com/z3275630-ops/floating-notes/actions/runs/42543663684/artifacts/13642441902)。
