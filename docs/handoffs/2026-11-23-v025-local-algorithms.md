# 本机离线算法套件交付交接（v0.2.5-local-algorithms.1）

日期：2026-11-23  
分支：`main`  
版本：`versionCode = 24`，`versionName = "0.2.5-local-algorithms.1"`（调试包 `0.2.5-local-algorithms.1-debug`）

## 1. 目标与隐私约束

在不改变 Room `SCHEMA_VERSION = 7`、零云端依赖与零网络上传的前提下，为浮记接入一套纯端侧可解释算法套件，覆盖**拼音/容错检索、FSRS 每日回顾排程、TextRank 句子抽取、SimHash 相似碎片聚类与安全合并、TF-IDF + 共现标签推荐、跨话题双卡对撞与 2-hop 图谱桥接、待办紧急度排序**。

## 2. 本轮落地改动清单

1. **Wave 1 · 纯端侧算法核心**
   - `ChinesePinyinTable.kt` & `LocalFuzzyMatcher.kt`：覆盖 6763 个 GB2312 及常见汉字（400 音节分 5 块加载），支持全拼对齐、首字母缩写（如 `dsbj` → `读书笔记`）、Damerau-Levenshtein 有界编辑距离容错（如 `项木交付`、`笔计`、`archtecture`）与反误报边界保护，以及标签/引用候选匹配 `matchesTagOrNoteQuery`。
   - `FsrsReviewScheduler.kt`：直接基于既有 `review_events` 历史回放稳定性（`stabilityDays`）、难度（`difficulty`）、遗忘曲线留存率（`retrievability`）与 10 分段紧迫度分桶，生成可读排程理由（`scheduleReason`，如“批注巩固到期 · 距上次 N 天”“首次重温 · 写于 N 天前”“提前巩固 · 记忆留存 N%”）。
   - `LocalTextIntelligence.kt`：提供 TextRank 原句抽取（过滤纯 URL 行、UTF-16 代理对安全截断）、64-bit FNV-1a SimHash + IDF 加权 Jaccard 相似碎片聚类（排除 `agent_local`/`walk_collision`/`merge_cleanup` 等派生笔记）及 `buildMergedParagraphs` 段落去重合并。
   - `LocalTagAndGraphEngine.kt`：结合正文提及、TF-IDF k-NN 近邻与 `#tag` 共现 Jaccard 图谱做草稿标签推荐（`recommendTagsForDraft`），并升级双卡对撞甜蜜点配对（`selectBestDualCollisionPair`）与 2-hop 图谱桥接洞察（`computeBridgeInsights`）。
   - `TaskUrgencyEngine.kt`：综合逾期天数、当日截止、紧急关键词、提醒状态与长期搁置计算待办紧迫度分值与分级徽标（`CRITICAL` / `HIGH` / `MEDIUM` / `STALE` / `NORMAL`）。
2. **Wave 2 · 数据层与仓库集成**
   - `LocalRetrievalEngine.kt` & `NoteSearch.kt`：在 `searchEvidence` 接入拼音与编辑距离回退并升级相关笔记为 IDF 余弦相似度；在 `SearchRepository` 保留既有 `observe` 并新增 `observeSmart`（精确命中 + `NoteScope` 内模糊命中按分值/置顶/更新时间排序）。
   - `DiscoveryEngines.kt` & `DiscoveryRepository.kt`：每日回顾接入 `FsrsReviewScheduler.rankForDailyReview`，填充 `ReviewCardModel.scheduleReason` 与 `keySentences`，新增 `markReviewCardSeen`（按 `sessionId + noteId` 去重）、`recommendTagsForDraft`、`findDuplicateGroups`，并在双卡漫步传递 chunks/links/tags。
   - `LocalAgentEngine.kt` & `AgentRepository.kt`：新增 `AgentSkill.DuplicateCleanup`（相似碎片查重，副标题 `"找出高度重复碎片并安全合并"`）、抽取 `filterScopedNotes`、在 `toEvidenceItem` 对长文使用 TextRank 摘要、`TodoTriage` 接入 `TaskUrgencyEngine.evaluateRaw`；新增 `findScopedDuplicateGroups` 与 `commitMergeDuplicateGroup`（事务提交、`action_id` 幂等、revision 冲突拒绝、`sourceType = "merge_cleanup"`、Tag 并集 + `#合并整理`、默认保留原文并附 `@reference`，可选移入 30 天可恢复回收站）。
3. **Wave 3 · 界面与 ViewModel 接线**
   - `AgentWorkspace.kt`：隐私条改为 `"本机离线运行 · 不上传 · ${notesCount} 篇可检索"`（`agent-privacy-pill`），接入 `DuplicateCleanup` 卡片与 `MintBottomSheet` 预览安全合并弹窗（`agent-dup-merge-sheet`，含回收站开关与确认按钮）。
   - `ExploreScreen.kt` & `NoteEditorDiscovery.kt`：在每日回顾卡片日期行下方独立成行展示 `review-fsrs-reason` 圆角胶囊（完整展示如 `"首次重温 · 写于 8 天前"`，避免与“跳回原笔记 ›”挤占截断）与折叠 `review-key-sentences`（“原话重点”），翻页触发 `onMarkReviewCardSeen`；相关笔记面板支持“本篇原话重点”。
   - `TasksScreen.kt`：待办列表接入 `TaskUrgencyEngine.sortByUrgency` 与语义配色 `task_urgency_badge_${noteId}_${blockId}`。
   - `NoteEditor.kt`、`NotesViewModel.kt`、`NotesShell.kt`、`ExploreModels.kt`：接入 `canFocus = false` 的编辑器上方推荐标签条（`editor-tag-suggestions`）、防抖标签推荐刷新、`TagPickerDialog` 智能推荐分区、行内 `#`/`@` 拼音筛选，以及笔记库 `observeSmart` 智能检索。
   - `app/build.gradle.kts`：升级至 `versionCode = 24`、`versionName = "0.2.5-local-algorithms.1"`。
4. **Wave 4 · 端到端真机测试与文档**
   - 新增 `LocalAlgorithmsEndToEndTest.kt`（7 项测试覆盖 FSRS 排程/去重、找一找 & `observeSmart` 拼音/容错与 `NoteScope`、标签推荐、TextRank/SimHash 聚类与安全合并保留/回收站/幂等、`AgentWorkspace` 合并弹窗、`ExploreScreen` FSRS 理由与关键句、`TasksScreen` 紧迫度徽标）。

## 3. 验证结果（最终编辑后全量重跑）

- **Gradle 全量构建与单测**（镜像目录 `D:\Codex-FloatingNotes-T10-build-20261114`，构建耗时 `2m 30s`）：
  - `gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease assembleBenchmark --offline --console=plain`：退出码 `0`。
  - `testDebugUnitTest`：**50 tests, 0 failures, 0 errors, 0 skipped**（8 个测试类全部通过）。
  - `lintDebug`：**0 errors, 69 warnings, 2 informational**（71 total issues）。
- **产物校验**（位于 `D:\Codex-FloatingNotes-T10-build-20261114\app\build\outputs\apk\`）：
  - `debug/app-debug.apk`：`54,126,213` bytes，SHA-256 `bdee6c2f775dd43313294d35ee4291f6be553aa68970c9449a11590a37e3f85c`
  - `androidTest/debug/app-debug-androidTest.apk`：`1,431,946` bytes，SHA-256 `39bfd270978df34ce65493f2d1823495a992a1952d72ec3b5cadc4ccf5020e96`
  - `release/app-release-unsigned.apk`：`43,215,688` bytes，SHA-256 `b0e20a3e611b52f2483e5fcae6954ead75295b040e9e9629c0f9c69631f03dd`
  - `benchmark/app-benchmark.apk`：`43,223,892` bytes，SHA-256 `9379cf503766beb3e5473c230433e330d5b61f474339552bb2b422dfbacf3f04`
- **荣耀 X10 真机验证**（`SKSVB20603001748`，`adb install -r` 覆盖安装，真实用户笔记库保持完好，未清空或删除任何用户笔记；全量跑测后实机共 `358` 篇可检索笔记）：
  - `am instrument -w io.github.z3275630ops.floatingnotes.debug.test/androidx.test.runner.AndroidJUnitRunner`：**21/21 类，77/77 tests PASS（78.625s）**。
  - 实机非破坏性复拍截图目视核验：
    - `02-explore-review-v2.png`（探索页每日回顾）：`review-fsrs-reason`（`"首次重温 · 写于 8 天前"`）独立成行完整展示，无 `...` 截断、无重叠。
    - `04-agent-dup-row-v2.png`（Agent 页）：隐私条 `"本机离线运行 · 不上传 · 358 篇可检索"`（UI XML 核实）与 `"相似碎片查重"` 副标题 `"找出高度重复碎片并安全合并"` 单行清晰、无单字折行。
    - 兼顾首轮截图核验首页、找一找、漫步（单卡/双卡）及待办清单全部正常渲染。
- **文档契约检查**：
  - `python scripts/check_docs.py`：`PASS: 85 Markdown files, 18 JSON files, 20 features, 37 tasks`。
