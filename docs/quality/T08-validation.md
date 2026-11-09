# T08 本地记录验收

2026-11-09。首个记录切片包含无标题新建、段落和勾选块、草稿恢复、本地保存、重开自动保存、置顶和归档。保存失败保持编辑器与正文。输入面板复用玻璃和跟手拖拽，事务结束后才关闭已保存会话。

CI 运行原生构建、lint、Room 集成测试和 UI 流程。端到端用例依次新建中文段落、添加并勾选待办、保存、重建 Activity、重开编辑并验证自动保存。构建、安装、测试和录屏结果见下。

当前切片的 Room 失败路径用注入事务错误覆盖。真实磁盘满、系统强杀、飞行模式与 60/120Hz 真机动效待测。富文本格式、标签、媒体和搜索沿路线图继续。

源码 8e9e7ad 的 [CI 42543668016](https://github.com/z3275630-ops/floating-notes/actions/runs/42543668016) 三种构建、lint 与 APK 签名验证通过。API35 安装后跑到记录流程时模拟器失联，前 3 项通过，记录流程未取得有效结果。降低模拟器负载，输入面板仅在顶部 112dp 采样模糊，正文区使用稳定实底，保持玻璃边缘与原生跟手转场。修正后重跑。

第二轮 [CI 42544355801](https://github.com/z3275630-ops/floating-notes/actions/runs/42544355801) 的 build 通过，模拟器仍在记录用例中失联。后续将背景采样源移出 IME 动画布局，键盘可见时面板使用稳定的实色渐变档。持久化 UI 流程使用 Compose 语义直接写文本，T03 保留独立的编辑与键盘测试。

集成源码 `cbbab63` 的 [CI 42544982334](https://github.com/z3275630-ops/floating-notes/actions/runs/42544982334) 通过构建、lint 和签名。API35 安装后，LocalRecordingTest 的新建、混排、保存、重开与自动保存通过。联合测试中拖拽关闭回调在测试调度器下从 Room 线程恢复，焦点清理触发 CalledFromWrongThreadException。已显式把打开/关闭动作调度到主线程，

源码 `f318fbc` 的 [Android CI 42545680400](https://github.com/z3275630-ops/floating-notes/actions/runs/42545680400) 通过。debug、release、benchmark、lint、测试 APK 和签名验证通过。API35 Android 15 模拟器实际安装，9 项测试通过，包含记录闭环、导航拖拽、中文选区与长文、Room 回滚与重开。JVM 测试为 NO-SOURCE。录屏包含合成笔记操作，独立 PNG 因测试卸载未能导出。

[安装包归档](https://github.com/z3275630-ops/floating-notes/actions/runs/42545680400/artifacts/13643486211) · [录屏及测试报告](https://github.com/z3275630-ops/floating-notes/actions/runs/42545680400/artifacts/13642903448)。
