# T02 玻璃材质样例

2026-11-09。依赖 T01 的构建与 API35 模拟器安装已通过。

debug 与 benchmark 的首页可进入材质实验。清晰档直接绘制实底和渐变，标准档从滚动场景取样并模糊，增强档额外用 AGSL 在 10dp 宽的圆角边缘带内偏移取样，最大位移 2dp。API 26–30 回退清晰档，API 31–32 的增强档回退标准档。前景文字绘制在效果层上方。效果对象按尺寸、密度、主题和档位缓存。样例页面在 release 中由构建常量关闭。

参考 [Compose 图形层](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers) 与 [RenderEffect API](https://developer.android.com/reference/android/graphics/RenderEffect)。

已执行文档检查、差异空白检查和录屏脚本语法检查。Android CI 将编译三种构建，安装 debug 包，在 API35 模拟器切换三档材质和深色主题并重建 Activity，保留截图及录屏。实际结果待本次 CI 完成后记录。

60/120Hz 真机、GPU、温升、电量和产品审美验收待测。使用 benchmark 包分别测试三档材质，固定刷新率、亮度和起始温度，在浅深色下各滚动 15 秒并重复三次。保存 Perfetto、`dumpsys gfxinfo io.github.z3275630ops.floatingnotes.benchmark framestats` 与 `dumpsys thermalservice`。记录机型、系统、提交和错过帧截止数。
