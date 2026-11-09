# 薄荷绿主题、侧栏和玻璃验证

2026-11-09。依据用户提供的录屏调整配色与组件。雾白正文面配薄荷绿到浅芽绿的背景。底栏保留记录、探索、Agent、笔记库。侧栏切换全部、归档、笔记本、标签，并打开笔记库管理页。选择范围仍使用 Room 中的稳定 ID。

侧栏使用 Compose 的手势抽屉。导航、侧栏和快速记录面板使用同一玻璃组件。Android 13 及以上采样背景并折射边缘，Android 12 使用模糊，Android 11 及以下使用清晰实底。输入法打开时，记录面板使用实底。侧栏打开时隐藏浮动底栏，避免多个玻璃层叠加。

本地 `python3 scripts/check_docs.py` 与 `git diff --check` 通过。[PR #48](https://github.com/z3275630-ops/floating-notes/pull/48) 源码 `fd97fb7` 的 [Android CI](https://github.com/z3275630-ops/floating-notes/actions/runs/42587316495) 构建 debug、release、benchmark 与测试 APK 成功。Lint 0 errors、37 warnings，与 T09 同数。JVM 单元测试为 NO-SOURCE。

[APK 与构建产物](https://github.com/z3275630-ops/floating-notes/actions/runs/42587316495/artifacts/13660465236)。并装预览为 benchmark 包，ID `io.github.z3275630ops.floatingnotes.benchmark`，versionCode 9，versionName `0.1.7-mint`。APK v2 签名和内容校验通过，SHA-256 为 `47697a89baf9362c1ec69f889976cc86173a23ac01218a2941d4bf62f73e96e6`。预览包与旧 debug 包使用独立数据目录。

Android 15 API 35 x86_64 模拟器执行 `connectedDebugAndroidTest`，debug 包实际安装运行。18 项通过，0 失败，0 跳过。新增界面测试覆盖侧栏打开、笔记本筛选、切换归档、滑动关闭、打开快速记录、插入引用及从反链打开笔记。已看过录屏中的记录页、侧栏和深色材质实验画面。截图文件未被 runner 单独导出，画面证据取自录屏。[模拟器报告与录屏](https://github.com/z3275630-ops/floating-notes/actions/runs/42587316495/artifacts/13659189891)

并装预览包已校验构建与签名，未在真机安装。真机色彩、60/120 Hz 帧耗时、耗电和 TalkBack 待测。
