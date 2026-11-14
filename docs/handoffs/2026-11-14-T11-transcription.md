# T11 普通话离线转写交接

分支 `feat/T11-offline-transcription`，实现提交 `3d9ffca`，基底 `97f7ad5`。T11 / F02，依赖 T08、T10。上一轮 [录音与状态栏交接](2026-11-14-T11.md) 保留。

打开笔记内的音频附件，点“转写”。首次主动下载普通话模型，校验后写入应用私有目录。已安装模型以后可断网转写。原始识别和整理版可切换，点“插入正文”会添加独立段落。整理版只处理汉字间的分词空格和段落末尾标点。原始录音与原始识别文字保留，文档与 Room schema 沿用既有格式。转写结果按音频附件 ID 写到同一私有目录，下次打开直接复用。关闭界面或退到后台可取消，失败后可手动重试。

直接使用 [Vosk Android 0.3.75](https://github.com/alphacep/vosk-api) 的识别库和官方 [普通话小模型](https://alphacephei.com/vosk/models)。复用 Android [MediaExtractor](https://developer.android.com/reference/android/media/MediaExtractor) 与 [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec) 做分块解码。模型包约 42 MiB，首次准备需要 160 MiB 可用空间，预览版仅开放 10 分钟以内的音频。音频和文字在本机处理，网络只用于从模型官方地址下载模型，压缩包由固定 SHA-256 校验。

Vosk、JNA 许可与来源在 APK 的 `assets/third-party` 中。模型按需获取。Android 系统识别的既有音频源参数依赖具体识别服务且不兼容荣耀 X10 的 Android 10，因此使用本机文件识别库。无服务端、付费 API 或自动联网重试。后续 T14 备份需覆盖附件旁的转写结果。

## 构建与验证

Windows 英文路径执行 `gradlew.bat --no-daemon --max-workers=2 "-Pkotlin.compiler.execution.strategy=in-process" assembleDebug assembleDebugAndroidTest assembleRelease assembleBenchmark lintDebug testDebugUnitTest` 成功，Lint 0 错误、41 警告，JVM 测试无源码。初次 Lint 拒绝两处 API 29 的带默认值访问，已改用 API 26 支持的参数读取。R8 仍报告原有 Kotlin 元数据警告，优化版只构建。

荣耀 X10（Android 10 / API 29）使用 7.63 秒、29 KB 的合成普通话 M4A。由系统选择器导入，应用完成模型下载、校验、解包和文件转写。确认识别原文与整理版可以切换，覆盖安装最终调试包后重开直接读取已保存结果，插入正文并保存笔记。样例把“记下”识别成“去下”，基础整理保留该错误。模型安装后约 65 MiB，观测到的电池温度读数 29–30℃。测试未关闭整机网络，未做首次离线失败、取消重试、长音频压力、来电和未封口录音恢复验证。未使用模拟器。

同机 AndroidJUnitRunner 回归 22 项全通过。为保留现有笔记，跳过需干净测试数据的 `BootstrapTest` 和固定引用名称的 `LocalRecordingTest#insertReferenceAndOpenItsBacklink`，详见前一轮交接。调试包 v2 签名校验通过，versionCode 13，SHA-256 `3bb869ba82aa562f0776137bc49b45957a593d68ad742cc0f1b19eb13056ec11`。见 [APK 与合成样例截图](https://github.com/z3275630-ops/floating-notes/releases/tag/v0.1.11-transcription.1)。

下一任务 T12 中文搜索与范围。保留 T09 的正文即时识别与引用范围验收。
