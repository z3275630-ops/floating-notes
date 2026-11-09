# T09 标签、笔记本与引用验证

2026-11-09。T09 在笔记库增加标签和笔记本的管理与范围筛选，编辑器支持插入引用和查看反链。标签移动与合并按稳定 ID 更新，校验同级重名和循环。笔记本删除只解绑笔记。Room v2 迁移从既有正文回填引用索引，引用在正文块中保存目标 ID 与版本。

[PR #47](https://github.com/z3275630-ops/floating-notes/pull/47) 源码 `9eb87d5` 的 [Android CI](https://github.com/z3275630-ops/floating-notes/actions/runs/42586355141) 通过。构建命令包含 `assembleDebug assembleRelease assembleBenchmark lintDebug testDebugUnitTest assembleDebugAndroidTest`。JVM 任务为 NO-SOURCE。Android 15 API 35 x86_64 模拟器重新构建、安装并执行 `connectedDebugAndroidTest`，16 项通过，0 失败，0 跳过。新增 3 项覆盖 Room v1 升级后保留笔记与草稿并回填引用、标签移动/合并/解绑、归档目标与正文更新后的引用索引。Android 签名验证通过。

[APK 与 schema](https://github.com/z3275630-ops/floating-notes/actions/runs/42586355141/artifacts/13659248795) · [模拟器测试报告与录屏](https://github.com/z3275630-ops/floating-notes/actions/runs/42586355141/artifacts/13660090137)

正文内即时识别 #/@、引用选区范围、真实设备的交互与性能未验收。旧 APK 和 CI 新包的调试证书不同。迁移测试以合成 v1 数据库为输入，无法证明两包能直接覆盖安装。
