# T03 原生输入验证

2026-11-09。T03 / F01 F20。使用 Compose TextFieldState 保留原生编辑状态、选区、IME composing 和撤销栈。组件接收并保留状态对象，撤销和重做在 composing 期间禁用。旋转由 TextFieldState 的 saver 恢复正文与选区。布局使用 WindowInsets 处理键盘与导航栏。实验内容在内存中，持久草稿在 T07 接入。

API35 Compose 自动化覆盖中文文本替换、撤销/重做、收起键盘、选区与 3 万字的 Activity 重建。真实拼音候选、OPPO 键盘联动、返回手势和长文帧预算待真机测试。CI 结果待补。
