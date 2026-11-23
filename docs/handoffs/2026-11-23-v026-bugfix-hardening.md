# 全量 P0 缺陷修复与可靠性加固交接（v0.2.6-bugfix-hardening.1）

日期：2026-11-23  
分支：`main`（本地工作区待提交）  
版本：`versionCode = 25`，`versionName = "0.2.6-bugfix-hardening.1"`（调试包 `0.2.6-bugfix-hardening.1-debug`）

## 1. 目标与约束

- 针对上一轮只读代码评审识别出的 **全部 16 个 `P0` 级高危缺陷** 完成一次性集中修复与端到端回归防护。
- 严格保持 Room 数据库 `SCHEMA_VERSION = 7` 不变，保留三端主结构、既有 `testTag` 与统计摘要行为；按用户确认**不做 APK 架构瘦身**（维持约 `41.23 MiB` 发布包多架构完整兼容）。
- 真机验证全程仅使用 `adb install -r` 覆盖安装，不执行 `adb uninstall` 或 `pm clear`，零破坏用户原有本地笔记库。

## 2. 本轮落地改动清单（P0-01 ～ P0-16）

1. **备份与恢复耐久性 / 数据一致性（`P0-01` ～ `P0-04`）**
   - `BackupCodec.kt`、`BackupManager.kt`、`VersionDiff.kt`：
     - **`P0-01` 数据库提交哨兵与崩溃恢复**：在 `applySnapshotToDatabase` 事务末尾写入 `__restore_commit__:$restoreId` 系统哨兵，事务提交后即使 `APPLYING_DB` 到 `DB_COMMITTED` 日志落盘前进程被杀，重启时 `recoverInterruptedRestoreIfNeeded` 仍可识别哨兵并完成已暂存附件晋升与清理；`NotesViewModel.confirmExecuteRestore` 将提醒重建包装为 `runCatching`，避免单条提醒失败破坏已成功恢复的结果展示。
     - **`P0-02` 导出即校对可恢复上限**：`BackupCodec.writeBackupZip` 统计并强制拦截超出 `MAX_ARCHIVE_ENTRIES` / `MAX_ENTRY_BYTES` / `MAX_TOTAL_UNCOMPRESSED_BYTES` 的归档（含 `manifest.json`），`exportFullBackupToFile` 写完后立即调用 `BackupCodec.readAndValidateArchive` 复核。
     - **`P0-03` 合并恢复保留独立草稿 & 恢复前强制刷盘**：`MERGE_KEEP_CONFLICTS` 保留备份中不依附于已恢复笔记的独立未保存草稿（冲突槽位重映射为 `"capture:restore-${newId()}"` 且 `sourceType = "share"`）；`confirmExecuteRestore` 先执行 `flushOpenEditorLocked(throwOnFailure = true)` 阻止未落盘编辑静默丢失。
     - **`P0-04` 合并恢复同步重写文档内任务 ID**：`VersionDiff.rewriteDocumentIds` 支持 `reminderIdMap` 与 `occurrenceIdMap`，`BackupManager` 预先映射来源笔记勾选块与任务表里的 `reminder_id` / `occurrence_id`，避免“恢复副本”与原笔记共用或串写同一条完成期次。
2. **编辑器、通知联动、待保存附加项与语音回收站安全（`P0-05` ～ `P0-11`）**
   - `NotesRepository.kt`、`NotesViewModel.kt`、`ReminderScheduler.kt`、`ReminderReceiver.kt`：
     - **`P0-05` 清除日程前先提交脏文本**：`clearEditorTaskSchedule` 与 `scheduleEditorTask` 统一为先提交待办编辑再更新提醒。
     - **`P0-06` 防止未保存编辑器覆盖外部通知操作**：`setTaskSchedule`、`clearTaskSchedule`、`toggleTaskCheckedLocked` 轮换笔记 `lastDraftId` 与 `committedSeq = 0L`；`EditorBlock` 记录同步基线（`syncedText` / `syncedChecked` / `syncedDueDate` / `syncedReminderId` / `syncedOccurrenceId`），在自动保存、关闭及日程变更遇到 `StaleDraftException` 时通过 `tryMergeExternalUpdateAndRetryCommitLocked` 合并外部状态后重试。
     - **`P0-07` 草稿待绑定标签/笔记本与待办附加项持久化**：在 `organization.tagNote` 成功后才移除 `pendingTagIds`，并将未落盘草稿附加项持久化至 `draft_extras/<draftId>.json` 随会话生命周期恢复与清理。
     - **`P0-08` & `P0-09` 导入锁顺序、回收站刷盘与录音开关生命周期**：`addImportedBlock` 调整 `persistenceMutex.withLock` 到 `withContext(NonCancellable)` 外层消除锁反转隐患；`moveToTrash` 与 `commitMergeDuplicateGroup` 前先停止录音并刷盘；录音目录按 `recordings/note_${noteId}` 隔离并在关闭编辑器前 `stopActiveRecorderBeforeClose`。
     - **`P0-10` & `P0-11` 已取消/过期提醒隔离 & 版本回退清理**：`observeTaskItems` 对 `CANCELLED` 提醒清空投影日期/状态，禁止对 `CANCELLED` / `COMPLETED` 提醒贪睡（snooze），`completeFromNotification` / `snoozeReminder` / `prepareReminderDelivery` 与 `ReminderReceiver` / `ReminderScheduler` 校验 `EXTRA_REMINDER_REVISION`；`restoreVersion` 在事务内同步取消或重算失效提醒。
3. **重复合并、事务并发保护、回顾与检索稳定性（`P0-12` ～ `P0-16`）**
   - `LocalTextIntelligence.kt`、`AgentEntities.kt`、`AgentRepository.kt`、`AgentWorkspace.kt`：
     - **`P0-12` 合并保留复选框/媒体/引用**：新增 `MergedDuplicateContent`、`buildMergedContent` 与 `extractReferenceLabels`，保留来源中的待办状态/截止日期、媒体附件与笔记引用并在预览弹窗准确提示。
     - **`P0-13` 事务内幂等与修订/隐私拦截**：新增 `AgentDao.deleteAction`，将 `commitSaveInsightAsNote`、`commitExtractTodosAsNote`、`commitMergeDuplicateGroup` 及 `DiscoveryRepository.saveDualCollisionAsNote` 的 `actionById`、修订号、`deletedAt` 与 `aiPolicy` 检查全部移入 `database.withTransaction`；`defaultMergeActionId` 纳入排序后的来源修订号签名与恢复/回收站模式。
   - `DiscoveryRepository.kt`：
     - **`P0-14` 每日回顾标签名与 ID 对齐**：按 `(tagId, tagName)` 配对后再排序，确保点击标签筹码跳转对应正确标签。
   - `LocalRetrievalEngine.kt`、`LocalAgentEngine.kt`、`NotesViewModel.kt`：
     - **`P0-15` 多标签子树独立命中 & 找一找竞态取消**：新增 `expandTagGroups` 支持 `matchAll` 逐组子树独立判定（同步用于证据检索与本地 Agent 过滤）；`runFindSearch` 取消前序 `findSearchJob` 并用递增 `findSearchSeq` 丢弃迟到结果。
   - `LocalFuzzyMatcher.kt`：
     - **`P0-16` 大小写膨胀 Unicode 字符防越界**：`ASCII_WORD_REGEX` 与 `matchCjkSlidingWindow` / `matchAsciiTokens` 在原始文本索引下分窗转小写，避免 `'İ'`（`U+0130`）等字符大小写转换长度变化导致切片 `IndexOutOfBoundsException`。
4. **版本号与新增回归测试**
   - `app/build.gradle.kts`：升级至 `versionCode = 25`、`versionName = "0.2.6-bugfix-hardening.1"`。
   - `app/src/test/.../P0HardeningUnitTest.kt`：新增 4 项 JVM 回归测试（Unicode 大小写膨胀模糊匹配、多标签子树 `matchAll`、`VersionDiff.rewriteDocumentIds` 任务 ID 重写、超出 `MAX_ARCHIVE_ENTRIES` 导出拦截）。
   - `app/src/androidTest/.../P0HardeningEndToEndTest.kt`：新增 5 项 Android 端到端回归测试（每日回顾标签对齐、富内容合并及 `aiPolicy` 冲突拦截、数据库哨兵恢复、合并恢复独立草稿保留与周期期次隔离、通知完成防草稿覆盖与版本回退取消失效提醒）。

## 3. 验证结果

- **Gradle 全量构建、单测与 Lint**（镜像目录 `D:\Codex-FloatingNotes-T10-build-20261114`）：
  - `gradlew.bat testDebugUnitTest --rerun lintDebug assembleDebug assembleDebugAndroidTest assembleRelease assembleBenchmark --offline --console=plain`：退出码 `0`。
  - `testDebugUnitTest`：**9 个测试类，54/54 tests PASS（0 failures, 0 errors, 0 skipped）**。
  - `lintDebug`：**0 errors（69 warnings, 2 informational）**。
- **产物校验**（位于 `D:\Codex-FloatingNotes-T10-build-20261114\app\build\outputs\apk\`）：
  - `debug/app-debug.apk`：`53,930,540` bytes（`51.43 MiB`），SHA-256 `9941cab198701e5c3ff629e23fd203e52fb8c73b0f526edc334fd000efac3fba`
  - `androidTest/debug/app-debug-androidTest.apk`：`1,453,908` bytes（`1.39 MiB`），SHA-256 `653a095565e7d234393b7127327a854d7f816474ebbd62c6022d10ddd5f9f9af`
  - `release/app-release-unsigned.apk`：`43,232,076` bytes（`41.23 MiB`），SHA-256 `b02cf85036369927b22839acc7e00d3a4efe3ef7c0487cd341b2ea77278d3e8d`
  - `benchmark/app-benchmark.apk`：`43,240,276` bytes（`41.24 MiB`），SHA-256 `baab45fd7325cf1497cf90ba1c957e23342721ba83a8fbc7dd7df2f1c2d23479`
- **荣耀 X10 真机全量验收**（`SKSVB20603001748`，`adb install -r` 覆盖安装，未清空或删除任何用户笔记数据）：
  - `am instrument -w io.github.z3275630ops.floatingnotes.debug.test/androidx.test.runner.AndroidJUnitRunner`：**22/22 类，82/82 tests PASS（耗时 79.925s）**。
- **文档契约检查**：
  - `python scripts/check_docs.py`：`PASS: 86 Markdown files, 18 JSON files, 20 features, 37 tasks`。
