# T35 跨应用文本收集验证

2026-11-09。Android 系统分享接收 ACTION_SEND 的 text/plain 与 text/uri-list，选区接收 ACTION_PROCESS_TEXT 的 text/plain。按 [Android 接收分享](https://developer.android.com/training/sharing/receive) 和 [Intent API](https://developer.android.com/reference/android/content/Intent) 分开注册导出入口。可编辑预览使用 T08 输入面板。来源字段仅保留单独分享的 HTTP/HTTPS 链接。正文原样保留，应用不加载链接，也不请求联网权限。

每次外部启动使用新的 capture_id，旋转复用 ID，独立草稿先落盘并可从记录首页恢复。显式保存复用 Room 事务，取消只清理这一份收集草稿。空文本、错误类型、附件和超过十万字符有明确提示。源应用的选区不被改写。

自动化覆盖 manifest 匹配、链接和选区预览、旋转后保存、同文再次分享、取消隔离和拒绝分支。真实第三方分享面板、OEM 选区菜单、飞行模式、强杀与磁盘满仍需设备测试。结果见下。

初始源码 `c73635d` 的构建通过，模拟器在本地记录用例执行中失联。后续源码 `cbbab63` 的 [CI 42544982334](https://github.com/z3275630-ops/floating-notes/actions/runs/42544982334) 通过三种构建、lint 与签名，API35 运行 13 项测试。4 项收集用例全部通过，基础记录用例通过。联合套件有一项拖拽关闭失败，原因是事务后的焦点操作从后台线程执行。随后修正主线程调度。

[构建包](https://github.com/z3275630-ops/floating-notes/actions/runs/42544982334/artifacts/13642953516) · [实际录屏与报告](https://github.com/z3275630-ops/floating-notes/actions/runs/42544982334/artifacts/13642623454)。

源码 `36335d3f4a522bb777acc56c8870dd18f03a4248` 的 [Android CI 42545683905](https://github.com/z3275630-ops/floating-notes/actions/runs/42545683905) 全部通过。build 运行 `assembleDebug assembleRelease assembleBenchmark lintDebug testDebugUnitTest assembleDebugAndroidTest`，debug 包名 `io.github.z3275630ops.floatingnotes.debug`，versionCode 7，versionName 0.1.6-t35-debug，APK 签名验证成功。JVM 测试为 NO-SOURCE。smoke 在 Android 15 API35 x86_64 重新构建、安装并运行 13 项测试，0 失败、0 跳过。

[安装包归档](https://github.com/z3275630-ops/floating-notes/actions/runs/42545683905/artifacts/13643766110) · [录屏与完整测试报告](https://github.com/z3275630-ops/floating-notes/actions/runs/42545683905/artifacts/13643930489)。debug APK SHA256 为 `9f57f02ad41c28043eb0c2f0fc0395d7df92e105ed14cbbb958a6ae361c4257b`。已将 APK 与构建生成的 SHA256SUMS 核对。录屏只包含合成数据。独立 PNG 因应用卸载未导出。

Q091 的 manifest 与预览、Q092 的 Activity 重建及同文新会话、Q093 的手写草稿隔离取消、Q094 的拒绝路径有自动化证据。系统分享面板的真实第三方交互、OEM 选区、飞行模式、强杀、磁盘满和真机性能仍待验收。
