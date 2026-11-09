# T35 跨应用文本收集验证

2026-11-09。Android 系统分享接收 ACTION_SEND 的 text/plain 与 text/uri-list，选区接收 ACTION_PROCESS_TEXT 的 text/plain。按 [Android 接收分享](https://developer.android.com/training/sharing/receive) 和 [Intent API](https://developer.android.com/reference/android/content/Intent) 分开注册导出入口。可编辑预览使用 T08 输入面板。来源字段仅保留单独分享的 HTTP/HTTPS 链接。正文原样保留，应用不加载链接，也不请求联网权限。

每次外部启动使用新的 capture_id，旋转复用 ID，独立草稿先落盘并可从记录首页恢复。显式保存复用 Room 事务，取消只清理这一份收集草稿。空文本、错误类型、附件和超过十万字符有明确提示。源应用的选区不被改写。

自动化覆盖 manifest 匹配、链接和选区预览、旋转后保存、同文再次分享、取消隔离和拒绝分支。真实第三方分享面板、OEM 选区菜单、飞行模式、强杀与磁盘满仍需设备测试。结果见下。

初始源码 `c73635d` 的构建通过，模拟器在本地记录用例执行中失联。后续源码 `cbbab63` 的 [CI 42544982334](https://github.com/z3275630-ops/floating-notes/actions/runs/42544982334) 通过三种构建、lint 与签名，API35 运行 13 项测试。4 项收集用例全部通过，基础记录用例通过。联合套件有一项拖拽关闭失败，原因是事务后的焦点操作从后台线程执行。修正主线程调度后继续验收。

[构建包](https://github.com/z3275630-ops/floating-notes/actions/runs/42544982334/artifacts/13642953516) · [实际录屏与报告](https://github.com/z3275630-ops/floating-notes/actions/runs/42544982334/artifacts/13642623454)。
