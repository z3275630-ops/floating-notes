# T10 验证记录

源码 `89e791b`，基底 `edeaafb`。2026-11-14 在 Windows（JBR 21，Gradle 8.11.1，SDK/Build Tools 35）构建，在荣耀 X10（Android 10 / API 29）完成验证。

| 验证 | 结果 |
| --- | --- |
| `python scripts/check_docs.py`、`git diff --check` | 通过，73 Markdown、13 JSON、20 功能、37 任务 |
| `gradlew.bat --no-daemon --max-workers=2 "-Pkotlin.compiler.execution.strategy=in-process" assembleDebug assembleDebugAndroidTest` | debug 和测试 APK 构建成功 |
| 同一 Gradle 参数执行 `assembleRelease assembleBenchmark lintDebug testDebugUnitTest` | release 和 benchmark 构建成功。Lint 0 错误、40 警告。JVM 测试为 NO-SOURCE，R8 有 Kotlin 元数据警告 |
| `apksigner verify --verbose app-debug.apk` | v2 签名通过，versionCode 11 |
| `adb install app-debug.apk`、`adb install -t app-debug-androidTest.apk`、`adb shell am instrument -w -r io.github.z3275630ops.floatingnotes.debug.test/androidx.test.runner.AndroidJUnitRunner` | 荣耀 X10 初始 6 项附件测试全通过。最终在清理本任务生成的合成测试数据后，24 项全通过，0 失败，约 25 秒 |
| 真机系统选择器 | 导入 8.56 KB 合成 PNG，显示文件名及缩略图。保存后删除本次创建的源文件并结束浮记进程，重新打开仍可看到笔记与附件预览 |
| 轻量调试边界 | 验证期间电池温度读数约 27–29℃。按用户要求转用真机，已关闭此前启动的本地模拟器，未在模拟器执行测试 |

附件自动化覆盖原件 SHA-256 和字节一致、源文件不可用后读本机副本、2048×1024 图片和 MP4 的限尺寸预览、WAV、坏文件、不可读来源、注入空间不足、模拟中断遗留暂存及无引用文件清理、草稿/历史引用保护，以及分享后重建界面并保存附件笔记。MP4 为用 FFmpeg 生成的 2 秒纯色合成素材。

首次 Windows 构建遇到 Android Gradle 插件中文路径限制，使用独立英文路径完成构建。首次全量回归的材质实验断言假定高版本图形效果，在 API 29 失败。将断言改为检查各 Android 版本实际应显示的效果或降级提示。带此前运行残留合成笔记的重复回归还出现了列表找不到合成示例和重复同名引用。清理本次安装的测试包合成数据后最终全量通过。

Q057 已验证源文件移除后可读，并调用撤销读取授权接口。跨进程提供者主动撤权未单独演练。Q058 已验证缩略图尺寸，快速滚动帧耗时待测。Q059 通过模拟空间不足、损坏文件和模拟中断文件回收用例，未刻意填满真机磁盘，也未在文件复制中途强杀。Android 26、真机高版本玻璃、TalkBack、导入大批量媒体压力和录屏仍待。荣耀 X10 的 ADB shell 缺少 `screenrecord`，提供导入及重开截图。

APK、SHA-256 与真机截图见 [v0.1.9-attachments.1](https://github.com/z3275630-ops/floating-notes/releases/tag/v0.1.9-attachments.1)。调试包 SHA-256 为 `1ae85cbba902a7a35d92b742e02af9b9441044aa2978ac2fa87cd0cec59fec62`。
