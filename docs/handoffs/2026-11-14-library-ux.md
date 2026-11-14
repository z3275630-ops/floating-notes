# 笔记库入口与视觉层级

本轮调整笔记库。搜索栏旁直接显示筛选，当前范围显示可清除的条件胶囊，已保存的搜索以横向快捷入口呈现。新保存的条件置于首位，长名称保留完整无障碍文本并在窄屏缩略显示。保存、管理、新建与重命名按对象收进菜单。点笔记本或标签直接缩小当前列表范围。

复用 Composables UI 搜索与菜单，并参考 [Apple 备忘录的智能文件夹](https://support.apple.com/guide/iphone/use-smart-folders-iphc43adabc2/ios) 的保存条件复用方式。实现沿用 Room 已有表和查询，无迁移。设计验收写入 [视觉规范](../design/visual-system.md)。

分支 `feat/library-action-hierarchy`，应用提交 `216c26e`。在荣耀 X10（Android 10）覆盖安装并检查新布局，搜索保存/恢复、侧栏和快速记录拖拽三项测试通过。Android debug/test APK、Lint 与文档检查通过。仅核验日间布局与相关流程。后续推进 T13 待办和本地提醒。
