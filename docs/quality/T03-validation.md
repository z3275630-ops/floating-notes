# T03 原生输入验证

2026-11-09。T03 / F01 F20。使用 Compose TextFieldState 保留原生编辑状态、选区、IME composing 和撤销栈。组件接收并保留状态对象，撤销和重做在 composing 期间禁用。旋转由 TextFieldState 的 saver 恢复正文与选区。布局使用 WindowInsets 处理键盘与导航栏。实验内容在内存中，持久草稿在 T07 接入。

API35 Compose 自动化覆盖中文文本替换、撤销/重做、收起键盘、选区与 3 万字的 Activity 重建。真实拼音候选、OPPO 键盘联动、返回手势和长文帧预算待真机测试。

第一次 Android CI（42542122062）编译测试 APK 时，选区测试 API 缺少 ExperimentalTestApi 声明。已加到测试类。

源码 `788eb21` 的 [Android CI 42542664962](https://github.com/z3275630-ops/floating-notes/actions/runs/42542664962) 已通过。debug、release、benchmark、lint 与测试 APK 通过，JVM 测试为 NO-SOURCE。API35 Android 15 模拟器实际安装并完成 4 项 Compose 测试，其中两项验证原生编辑和 3 万字恢复。选区测试由 Compose 注入文本，真实候选条和系统返回手势留待真机。

[构建 APK](https://github.com/z3275630-ops/floating-notes/actions/runs/42542664962/artifacts/13641963398) · [录屏与测试报告](https://github.com/z3275630-ops/floating-notes/actions/runs/42542664962/artifacts/13641963519)。
