# ADR 0011 · 移植 Composables UI 的界面组件

状态：已采纳。2026-11-09。

浮记继续使用 Kotlin / Jetpack Compose。按用户选定的 Composables UI，先移植笔记菜单和引用选择器筛选框。样式移植自上游 [bbaf1ba](https://github.com/composablehorizons/composables-ui/tree/bbaf1ba5b4c1af32d48dcef14f426f1f562e7ebc) 的 DropdownMenu 与 TextField，两份源码保留原 MIT 许可头。沿用现有色板、字级和 Android 手势。正文编辑和玻璃表面继续用既有组件。

该上游快照基于 Kotlin 2.4、Compose Multiplatform 1.11.1、AGP 9。浮记当前工具链为 Kotlin 2.2.10、Compose BOM 2025.04.01 和 AGP 8.9.2。本轮移植组件的视觉和状态处理，菜单定位及收起复用当前 Compose 菜单机制，筛选框使用当前版本的 BasicTextField。这样可以单独验证样式，无需同时升级整个工具链。组件适配集中在 designsystem，回退只需要还原两个调用入口。
