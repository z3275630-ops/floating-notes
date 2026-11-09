# Composables UI 界面交接

T09 / F03、F12，依赖 T07 和薄荷绿界面。接手人 Codex，分支 feat/T09-composables-ui，源码 `d100102`，[PR #49](https://github.com/z3275630-ops/floating-notes/pull/49)，基底 [PR #48](https://github.com/z3275630-ops/floating-notes/pull/48)。

首页与笔记库的笔记卡片改用三点入口及窄边框操作菜单。引用选择器使用带放大镜和清除按钮的筛选框。主要改动在 designsystem、NotesShell、NoteEditor 与 LocalRecordingTest。设计选择、源码归属及回退方法见 [ADR 0011](../decisions/0011-composables-ui-source.md)。

`python3 scripts/check_docs.py` 与 `git diff --check` 通过。[Android CI](https://github.com/z3275630-ops/floating-notes/actions/runs/42645622221) 的 debug、release、benchmark 和测试 APK 构建成功，调试包签名通过，Lint 0 错误、37 警告。JVM 测试任务无测试源码。Android 15 API 35 x86_64 模拟器 18 项通过、0 失败、0 跳过，新增断言覆盖置顶、取消置顶、无匹配、清除筛选和选中引用。[测试报告与录屏](https://github.com/z3275630-ops/floating-notes/actions/runs/42645622221/artifacts/13688141493)

已在 [GitHub Releases](https://github.com/z3275630-ops/floating-notes/releases/tag/v0.1.8-composables.1) 发布普通调试 APK，versionCode 10，SHA-256 `2e50642150e3af3289e3fd9633a4c77141a4fc1eef8f0cda0aba7e8d2bfe99cd`。项目后续每次交付新 APK 同步更新 Releases，正式版前的测试包按可重装处理。真机动效、TalkBack 和帧耗时待测。下一步按路线推进 T10，T09 正文 #/@ 即时识别与引用选区范围仍待补齐。
