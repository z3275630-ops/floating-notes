# T02 玻璃材质样例

2026-11-09。依赖 T01 的构建与 API35 模拟器安装已通过。

debug 与 benchmark 的首页可进入材质实验。清晰档直接绘制实底和渐变，标准档从滚动场景取样并模糊，增强档额外用 AGSL 在 10dp 宽的圆角边缘带内偏移取样，最大位移 2dp。API 26–30 回退清晰档，API 31–32 的增强档回退标准档。前景文字绘制在效果层上方。效果对象按尺寸、密度、主题和档位缓存。样例页面在 release 中由构建常量关闭。

参考 [Compose 图形层](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers) 与 [RenderEffect API](https://developer.android.com/reference/android/graphics/RenderEffect)。

文档检查、差异空白检查和录屏脚本语法检查通过。源码 `0e0ec40` 的 [Android CI 42541972859](https://github.com/z3275630-ops/floating-notes/actions/runs/42541972859) 通过。build 编译 debug、release、benchmark 和测试 APK，运行 lint 及签名检查。JVM 测试没有源文件。API35 Android 15 x86_64 模拟器完成 2 项 Compose 测试，覆盖启动、三档材质、深色主题和 Activity 重建。

[构建 APK](https://github.com/z3275630-ops/floating-notes/actions/runs/42541972859/artifacts/13642120739) · [录屏与测试报告](https://github.com/z3275630-ops/floating-notes/actions/runs/42541972859/artifacts/13641559029)。检查录屏帧可见三档切换与深色前景，标准档的背景笔记被模糊。自动化截图保存在应用专属目录，测试卸载后未导出，视觉证据以录屏为准。

60/120Hz 真机、GPU、温升、电量和产品审美验收待测。使用 benchmark 包分别测试三档材质，固定刷新率、亮度和起始温度，在浅深色下各滚动 15 秒并重复三次。保存 Perfetto、`dumpsys gfxinfo io.github.z3275630ops.floatingnotes.benchmark framestats` 与 `dumpsys thermalservice`。记录机型、系统、提交和错过帧截止数。
