# Composables UI 界面交接

T09 / F03、F12，依赖 T07 和薄荷绿界面。接手人 Codex，分支 feat/T09-composables-ui，基底 [PR #48](https://github.com/z3275630-ops/floating-notes/pull/48)。

首页与笔记库的笔记卡片改用三点入口及窄边框操作菜单。引用选择器使用带放大镜和清除按钮的筛选框。主要改动在 designsystem、NotesShell、NoteEditor 与 LocalRecordingTest。设计选择、源码归属及回退方法见 [ADR 0011](../decisions/0011-composables-ui-source.md)。

验证命令 `python3 scripts/check_docs.py`、`git diff --check`、Android CI 的构建、Lint 和 API 35 模拟器套件。录屏与结果待构建结束后填写。真机动效、TalkBack 和帧耗时待测。下一步按路线推进 T10；T09 正文 #/@ 即时识别与引用选区范围仍待补齐。
