package io.github.z3275630ops.floatingnotes.data.agent

import io.github.z3275630ops.floatingnotes.core.model.safeUtf16Slice
import io.github.z3275630ops.floatingnotes.data.backup.MiniJson
import io.github.z3275630ops.floatingnotes.data.discovery.LocalRetrievalEngine
import io.github.z3275630ops.floatingnotes.data.discovery.NoteChunkEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteLinkEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteScope
import io.github.z3275630ops.floatingnotes.data.local.NoteTagEntity
import io.github.z3275630ops.floatingnotes.data.local.NotebookNoteEntity
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

internal enum class AgentSkill(
    val id: String,
    val title: String,
    val subtitle: String,
    val defaultPrompt: String,
) {
    DailyReflection(
        id = "daily_reflection",
        title = "今天回看",
        subtitle = "提炼近期新笔记与待办线索",
        defaultPrompt = "帮我回看今天与近三天的记录重点和未竟之事",
    ),
    EvolutionTrack(
        id = "evolution_track",
        title = "观点轨迹",
        subtitle = "对比同一主题早晚想法的变化",
        defaultPrompt = "梳理同一主题从早期到现在关注点的变化",
    ),
    CounterEvidence(
        id = "counter_evidence",
        title = "寻找例外",
        subtitle = "找出存疑、转折与不同角度记录",
        defaultPrompt = "帮我找找笔记里互相对照、存疑或需要推敲的地方",
    ),
    TodoTriage(
        id = "todo_triage",
        title = "未完待办梳理",
        subtitle = "归拢尚未完成的待办与关联背景",
        defaultPrompt = "汇总目前还没勾选的待办和优先级建议",
    ),
    WeeklyDraft(
        id = "weekly_draft",
        title = "周报草稿",
        subtitle = "基于近 7 天笔记生成可确认草稿",
        defaultPrompt = "整理最近 7 天做了什么、完成了哪些与下周可继续的事",
    ),
    FreeQuestion(
        id = "free_question",
        title = "自由追问",
        subtitle = "在授权笔记范围内检索并回答",
        defaultPrompt = "",
    ),
    ;

    companion object {
        val quickSkills = listOf(DailyReflection, EvolutionTrack, CounterEvidence, TodoTriage, WeeklyDraft)

        fun fromId(id: String): AgentSkill = entries.firstOrNull { it.id == id } ?: FreeQuestion
    }
}

internal enum class AgentTimeRange(val label: String, val days: Int?) {
    All("全部笔记", null),
    Last7Days("近 7 天", 7),
    Last30Days("近 30 天", 30),
}

internal data class AgentToolStep(
    val toolName: String,
    val label: String,
    val status: String,
    val itemCount: Int,
)

internal data class AgentEvidenceItem(
    val noteId: String,
    val noteTitle: String,
    val noteRevision: Long,
    val excerpt: String,
    val reason: String,
    val updatedAt: String,
)

internal data class AgentRunResult(
    val engineNotice: String,
    val headline: String,
    val observations: List<String>,
    val evidence: List<AgentEvidenceItem>,
    val alternativeView: String,
    val nextQuestions: List<String>,
    val toolSteps: List<AgentToolStep>,
    val suggestedTodos: List<String>,
    val filteredByPolicyCount: Int,
    val scannedCount: Int,
) {
    fun encode(): String = MiniJson.encode(
        linkedMapOf(
            "engine_notice" to engineNotice,
            "headline" to headline,
            "observations" to observations,
            "evidence" to evidence.map { item ->
                linkedMapOf(
                    "note_id" to item.noteId,
                    "note_title" to item.noteTitle,
                    "note_revision" to item.noteRevision,
                    "excerpt" to item.excerpt,
                    "reason" to item.reason,
                    "updated_at" to item.updatedAt,
                )
            },
            "alternative_view" to alternativeView,
            "next_questions" to nextQuestions,
            "tool_steps" to toolSteps.map { step ->
                linkedMapOf(
                    "tool_name" to step.toolName,
                    "label" to step.label,
                    "status" to step.status,
                    "item_count" to step.itemCount,
                )
            },
            "suggested_todos" to suggestedTodos,
            "filtered_by_policy_count" to filteredByPolicyCount,
            "scanned_count" to scannedCount,
        ),
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun decode(json: String): AgentRunResult? = runCatching {
            val root = MiniJson.parseObject(json)
            val observations = (root["observations"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
            val evidence = (root["evidence"] as? List<*>).orEmpty().mapNotNull { entry ->
                val o = entry as? Map<String, Any?> ?: return@mapNotNull null
                AgentEvidenceItem(
                    noteId = o["note_id"]?.toString().orEmpty(),
                    noteTitle = o["note_title"]?.toString().orEmpty(),
                    noteRevision = (o["note_revision"] as? Number)?.toLong() ?: 0L,
                    excerpt = o["excerpt"]?.toString().orEmpty(),
                    reason = o["reason"]?.toString().orEmpty(),
                    updatedAt = o["updated_at"]?.toString().orEmpty(),
                )
            }
            val nextQuestions = (root["next_questions"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
            val toolSteps = (root["tool_steps"] as? List<*>).orEmpty().mapNotNull { entry ->
                val o = entry as? Map<String, Any?> ?: return@mapNotNull null
                AgentToolStep(
                    toolName = o["tool_name"]?.toString().orEmpty(),
                    label = o["label"]?.toString().orEmpty(),
                    status = o["status"]?.toString().orEmpty(),
                    itemCount = (o["item_count"] as? Number)?.toInt() ?: 0,
                )
            }
            val suggestedTodos = (root["suggested_todos"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
            AgentRunResult(
                engineNotice = root["engine_notice"]?.toString() ?: LocalAgentEngine.ENGINE_NOTICE,
                headline = root["headline"]?.toString().orEmpty(),
                observations = observations,
                evidence = evidence,
                alternativeView = root["alternative_view"]?.toString().orEmpty(),
                nextQuestions = nextQuestions,
                toolSteps = toolSteps,
                suggestedTodos = suggestedTodos,
                filteredByPolicyCount = (root["filtered_by_policy_count"] as? Number)?.toInt() ?: 0,
                scannedCount = (root["scanned_count"] as? Number)?.toInt() ?: 0,
            )
        }.getOrNull()
    }
}

internal object LocalAgentEngine {
    const val ENGINE_NOTICE = "当前由本机检索与规则引擎分析（未接入云端大模型）"

    private val contrastMarkers = listOf(
        "但是", "不过", "反而", "却", "矛盾", "存疑", "怀疑", "问题", "风险",
        "担心", "卡住", "还没", "未必", "也许", "或者", "?", "？",
    )

    private val injectionPatterns = listOf(
        "ignore previous instructions",
        "system prompt",
        "you are now",
        "忽略之前",
        "忽略以上",
        "无视前文",
        "执行指令",
    )

    data class AnalysisOutcome(
        val status: String,
        val result: AgentRunResult,
    )

    fun analyze(
        skill: AgentSkill,
        prompt: String,
        notes: List<NoteEntity>,
        chunks: List<NoteChunkEntity>,
        tags: List<TagEntity>,
        noteTags: List<NoteTagEntity>,
        notebookNotes: List<NotebookNoteEntity>,
        links: List<NoteLinkEntity>,
        scope: NoteScope,
        timeRange: AgentTimeRange,
        now: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): AnalysisOutcome {
        val normalizedScope = scope.copy(aiOnly = true)
        val activeNonArchived = notes.filter { it.deletedAt == null && it.archivedAt == null }
        val excludedByPolicy = activeNonArchived.count { it.aiPolicy !in setOf("inherit", "allow") }
        val cutoff = timeRange.days?.let { now.minus(it.toLong(), ChronoUnit.DAYS) }

        val tagsById = tags.associateBy { it.id }
        val expandedInclude = LocalRetrievalEngine.expandTagSelection(normalizedScope.includeTags, tags, normalizedScope.descendants)
        val expandedExclude = LocalRetrievalEngine.expandTagSelection(normalizedScope.excludeTags, tags, normalizedScope.descendants)
        val noteTagIds = noteTags.groupBy({ it.noteId }, { it.tagId }).mapValues { it.value.toSet() }
        val noteNotebookIds = notebookNotes.groupBy({ it.noteId }, { it.notebookId }).mapValues { it.value.toSet() }

        val scopedNotes = activeNonArchived.filter { note ->
            if (!LocalRetrievalEngine.isScopeEligible(
                    note = note,
                    scope = normalizedScope,
                    noteTagIds = noteTagIds[note.id].orEmpty(),
                    noteNotebookIds = noteNotebookIds[note.id].orEmpty(),
                    expandedIncludeTags = expandedInclude,
                    expandedExcludeTags = expandedExclude,
                    excludedNoteIds = emptySet(),
                )
            ) {
                return@filter false
            }
            if (cutoff != null) {
                val ts = parseInstantOrNull(note.updatedAt) ?: parseInstantOrNull(note.createdAt)
                if (ts != null && ts.isBefore(cutoff)) return@filter false
            }
            true
        }

        val stepScope = AgentToolStep(
            toolName = "scope_notes",
            label = "筛选已授权本地笔记（已遵守 AI 策略）",
            status = "completed",
            itemCount = scopedNotes.size,
        )

        if (scopedNotes.isEmpty()) {
            return insufficientOutcome(
                steps = listOf(stepScope),
                filteredByPolicyCount = excludedByPolicy,
                reason = "当前时间或标签范围内没有可供本机分析的已授权笔记。可以切换为“全部笔记”或先写几条想法。",
            )
        }

        val scopedIds = scopedNotes.mapTo(HashSet()) { it.id }
        val scopedChunks = chunks.filter { it.noteId in scopedIds }

        return when (skill) {
            AgentSkill.DailyReflection -> analyzeDailyReflection(
                scopedNotes = scopedNotes,
                noteTagIds = noteTagIds,
                tagsById = tagsById,
                links = links,
                stepScope = stepScope,
                filteredByPolicyCount = excludedByPolicy,
                now = now,
                zoneId = zoneId,
            )
            AgentSkill.EvolutionTrack -> analyzeEvolutionTrack(
                prompt = prompt,
                scopedNotes = scopedNotes,
                scopedChunks = scopedChunks,
                noteTagIds = noteTagIds,
                tagsById = tagsById,
                stepScope = stepScope,
                filteredByPolicyCount = excludedByPolicy,
            )
            AgentSkill.CounterEvidence -> analyzeCounterEvidence(
                prompt = prompt,
                scopedNotes = scopedNotes,
                scopedChunks = scopedChunks,
                noteTagIds = noteTagIds,
                tagsById = tagsById,
                stepScope = stepScope,
                filteredByPolicyCount = excludedByPolicy,
            )
            AgentSkill.TodoTriage -> analyzeTodoTriage(
                scopedNotes = scopedNotes,
                noteTagIds = noteTagIds,
                tagsById = tagsById,
                stepScope = stepScope,
                filteredByPolicyCount = excludedByPolicy,
            )
            AgentSkill.WeeklyDraft -> analyzeWeeklyDraft(
                scopedNotes = scopedNotes,
                links = links,
                stepScope = stepScope,
                filteredByPolicyCount = excludedByPolicy,
                now = now,
                zoneId = zoneId,
            )
            AgentSkill.FreeQuestion -> analyzeFreeQuestion(
                prompt = prompt,
                scopedNotes = scopedNotes,
                scopedChunks = scopedChunks,
                tags = tags,
                noteTagIds = noteTagIds,
                noteNotebookIds = noteNotebookIds,
                links = links,
                scope = normalizedScope,
                stepScope = stepScope,
                filteredByPolicyCount = excludedByPolicy,
            )
        }
    }

    private fun analyzeDailyReflection(
        scopedNotes: List<NoteEntity>,
        noteTagIds: Map<String, Set<String>>,
        tagsById: Map<String, TagEntity>,
        links: List<NoteLinkEntity>,
        stepScope: AgentToolStep,
        filteredByPolicyCount: Int,
        now: Instant,
        zoneId: ZoneId,
    ): AnalysisOutcome {
        val today = now.atZone(zoneId).toLocalDate()
        val recentWindowStart = now.minus(3, ChronoUnit.DAYS)
        val sortedByRecent = scopedNotes.sortedByDescending { it.updatedAt }
        val recentNotes = sortedByRecent.filter { n ->
            val ts = parseInstantOrNull(n.updatedAt) ?: parseInstantOrNull(n.createdAt)
            ts != null && !ts.isBefore(recentWindowStart)
        }.ifEmpty { sortedByRecent.take(5) }

        val todayCount = recentNotes.count { n ->
            parseInstantOrNull(n.updatedAt)?.atZone(zoneId)?.toLocalDate() == today
        }
        val openTodos = collectOpenTodos(recentNotes)
        val tagFreq = recentNotes.flatMap { noteTagIds[it.id].orEmpty() }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .mapNotNull { (tid, count) -> tagsById[tid]?.let { "#${it.name}（$count 条）" } }

        val evidence = recentNotes.take(4).map { note ->
            val ts = parseInstantOrNull(note.updatedAt)?.atZone(zoneId)?.toLocalDate()
            val label = if (ts == today) "今日更新" else "近几日记录"
            toEvidenceItem(note, label)
        }

        val stepScan = AgentToolStep("scan_recent_window", "梳理近 3 日记录与今日新增", "completed", recentNotes.size)
        val stepTodo = AgentToolStep("inspect_todos", "提取近期笔记中的未完待办与关联", "completed", openTodos.size)

        val observations = buildList {
            add("近 3 天共涉及 ${recentNotes.size} 条记录，其中今天更新了 $todayCount 条。")
            if (tagFreq.isNotEmpty()) {
                add("近期高频主题集中在：${tagFreq.take(3).joinToString("、")}。")
            }
            if (openTodos.isNotEmpty()) {
                add("这批记录里还有 ${openTodos.size} 项未勾待办，最先可处理：「${openTodos.first().text}」。")
            } else {
                add("这几条记录里没有遗留未勾选的待办条目。")
            }
        }

        val headline = if (todayCount > 0) {
            "今天更新了 $todayCount 条笔记，近几日主线聚焦在${tagFreq.firstOrNull()?.substringBefore("（") ?: "日常记录"}。"
        } else {
            "最近 3 天共有 ${recentNotes.size} 条活跃记录，可以趁热把关键一条补全后续想法。"
        }

        return AnalysisOutcome(
            status = "completed",
            result = AgentRunResult(
                engineNotice = ENGINE_NOTICE,
                headline = headline,
                observations = observations,
                evidence = evidence,
                alternativeView = "如果最近只记了零散标题或只记录了工作琐事，建议翻看“回顾”页里的久未重温卡片，避免视角只停留在眼前几小时。",
                nextQuestions = buildList {
                    val topTag = evidence.firstOrNull()?.noteTitle?.take(12)
                    if (topTag != null) add("围绕「$topTag」查看之前的观点变化")
                    add("梳理全部还没做完的待办")
                },
                toolSteps = listOf(stepScope, stepScan, stepTodo),
                suggestedTodos = openTodos.take(3).map { it.text },
                filteredByPolicyCount = filteredByPolicyCount,
                scannedCount = scopedNotes.size,
            ),
        )
    }

    private fun analyzeEvolutionTrack(
        prompt: String,
        scopedNotes: List<NoteEntity>,
        scopedChunks: List<NoteChunkEntity>,
        noteTagIds: Map<String, Set<String>>,
        tagsById: Map<String, TagEntity>,
        stepScope: AgentToolStep,
        filteredByPolicyCount: Int,
    ): AnalysisOutcome {
        val queryTokens = LocalRetrievalEngine.tokenizeForIndex(prompt)
            .filterNot { it in setOf("梳理", "主题", "早期", "现在", "关注", "变化", "同一", "观点", "轨迹") }
        val candidates = if (queryTokens.isNotEmpty()) {
            scopedNotes.filter { n ->
                val textTokens = LocalRetrievalEngine.tokenizeForIndex("${n.title.orEmpty()} ${n.searchText}").toSet()
                queryTokens.any { it in textTokens }
            }.ifEmpty { scopedNotes }
        } else {
            // Group by most frequent tag or chronological span
            val topTagId = scopedNotes.flatMap { noteTagIds[it.id].orEmpty() }
                .groupingBy { it }.eachCount()
                .maxByOrNull { it.value }?.key
            if (topTagId != null) {
                scopedNotes.filter { topTagId in noteTagIds[it.id].orEmpty() }.ifEmpty { scopedNotes }
            } else {
                scopedNotes
            }
        }.sortedBy { it.createdAt }

        if (candidates.size < 2) {
            return insufficientOutcome(
                steps = listOf(stepScope, AgentToolStep("timeline_sort", "按时间排序主题候选笔记", "completed", candidates.size)),
                filteredByPolicyCount = filteredByPolicyCount,
                reason = "当前只找到 ${candidates.size} 条匹配记录，至少需要 2 条不同时间的笔记才能对比观点变化。",
            )
        }

        val earliest = candidates.first()
        val latest = candidates.last()
        val middle = if (candidates.size >= 3) candidates[candidates.size / 2] else null
        val timelineNotes = listOfNotNull(earliest, middle, latest).distinctBy { it.id }

        val earlyTokens = LocalRetrievalEngine.tokenizeForIndex(earliest.searchText).toSet()
        val lateTokens = LocalRetrievalEngine.tokenizeForIndex(latest.searchText).toSet()
        val shared = earlyTokens.intersect(lateTokens).filter { it.length >= 2 }.take(4)
        val newlyEmerged = (lateTokens - earlyTokens).filter { it.length >= 2 }.take(4)

        val stepSort = AgentToolStep("timeline_sort", "按创建时间排列同类记录", "completed", candidates.size)
        val stepDiff = AgentToolStep("compare_tokens", "对比早期与最新笔记用词焦点", "completed", timelineNotes.size)

        val observations = buildList {
            add("该线索共覆盖 ${candidates.size} 条记录，从 ${earliest.createdAt.take(10)} 延续至 ${latest.createdAt.take(10)}。")
            if (shared.isNotEmpty()) {
                add("一直延续的核心关键词：${shared.joinToString("、")}。")
            }
            if (newlyEmerged.isNotEmpty()) {
                add("近期新出现的关注点：${newlyEmerged.joinToString("、")}。")
            }
        }

        val evidence = timelineNotes.mapIndexed { idx, note ->
            val stage = when {
                idx == 0 -> "最早记录 · ${note.createdAt.take(10)}"
                note.id == latest.id -> "最新记录 · ${note.updatedAt.take(10)}"
                else -> "中间节点 · ${note.createdAt.take(10)}"
            }
            toEvidenceItem(note, stage)
        }

        return AnalysisOutcome(
            status = "completed",
            result = AgentRunResult(
                engineNotice = ENGINE_NOTICE,
                headline = "从 ${earliest.createdAt.take(10)} 到 ${latest.createdAt.take(10)}，这条线索从「${LocalRetrievalEngine.displayNoteTitle(earliest)}」延伸到了「${LocalRetrievalEngine.displayNoteTitle(latest)}」。",
                observations = observations,
                evidence = evidence,
                alternativeView = "关键词出现变化有时只是因为换了表达方式，建议点开最早和最新两条原笔记确认背景是否已经改变。",
                nextQuestions = listOf(
                    "在这批记录里找找反例或犹疑",
                    "把这几条脉络存成一篇阶段小结",
                ),
                toolSteps = listOf(stepScope, stepSort, stepDiff),
                suggestedTodos = emptyList(),
                filteredByPolicyCount = filteredByPolicyCount,
                scannedCount = scopedNotes.size,
            ),
        )
    }

    private fun analyzeCounterEvidence(
        prompt: String,
        scopedNotes: List<NoteEntity>,
        scopedChunks: List<NoteChunkEntity>,
        noteTagIds: Map<String, Set<String>>,
        tagsById: Map<String, TagEntity>,
        stepScope: AgentToolStep,
        filteredByPolicyCount: Int,
    ): AnalysisOutcome {
        val ranked = scopedNotes.mapNotNull { note ->
            val text = "${note.title.orEmpty()} ${note.searchText}"
            val matchedMarkers = contrastMarkers.filter { text.contains(it) }
            if (matchedMarkers.isEmpty()) null else note to matchedMarkers
        }.sortedByDescending { it.second.size }

        val stepScan = AgentToolStep("scan_contrast_markers", "检索转折、疑问与风险记录", "completed", ranked.size)
        if (ranked.isEmpty()) {
            return insufficientOutcome(
                steps = listOf(stepScope, stepScan),
                filteredByPolicyCount = filteredByPolicyCount,
                reason = "当前范围内没有检测到明显的“但是 / 疑虑 / 风险 / 问句”等转折或待推敲线索。",
            )
        }

        val topNotes = ranked.take(4)
        val evidence = topNotes.map { (note, markers) ->
            toEvidenceItem(note, "包含「${markers.take(2).joinToString(" / ")}」转折/疑问信号")
        }

        return AnalysisOutcome(
            status = "completed",
            result = AgentRunResult(
                engineNotice = ENGINE_NOTICE,
                headline = "找到 ${ranked.size} 条带有转折、提问或存疑表达的笔记，可对照检验原先判断。",
                observations = listOf(
                    "命中转折/存疑信号最多的是「${evidence.first().noteTitle}」。",
                    "这类片段往往记录了当时的犹豫、反例或尚未解决的阻力，适合配合正反两条笔记一起重读。",
                ),
                evidence = evidence,
                alternativeView = "本机引擎仅基于“但是 / 问题 / 问号”等表面语言线索筛选，不一定涵盖没有用转折词写的隐含矛盾。",
                nextQuestions = listOf(
                    "针对最核心那条梳理待解决动作",
                    "去探索页试试“双卡对撞”",
                ),
                toolSteps = listOf(stepScope, stepScan),
                suggestedTodos = topNotes.take(2).map { (n, _) ->
                    "复核「${LocalRetrievalEngine.displayNoteTitle(n)}」中的疑问点"
                },
                filteredByPolicyCount = filteredByPolicyCount,
                scannedCount = scopedNotes.size,
            ),
        )
    }

    private fun analyzeTodoTriage(
        scopedNotes: List<NoteEntity>,
        noteTagIds: Map<String, Set<String>>,
        tagsById: Map<String, TagEntity>,
        stepScope: AgentToolStep,
        filteredByPolicyCount: Int,
    ): AnalysisOutcome {
        val openTodos = collectOpenTodos(scopedNotes)
        val stepTodo = AgentToolStep("collect_open_todos", "扫描全部未勾选待办方块", "completed", openTodos.size)
        if (openTodos.isEmpty()) {
            return insufficientOutcome(
                steps = listOf(stepScope, stepTodo),
                filteredByPolicyCount = filteredByPolicyCount,
                reason = "当前范围内没有找到未勾选的待办清单，所有任务看起来都已处理完毕。",
            )
        }

        val withDue = openTodos.filter { !it.dueDate.isNullOrBlank() }.sortedBy { it.dueDate }
        val withoutDue = openTodos.filter { it.dueDate.isNullOrBlank() }
        val prioritized = (withDue + withoutDue).take(5)
        val byNote = scopedNotes.associateBy { it.id }

        val evidence = prioritized.distinctBy { it.noteId }.take(4).mapNotNull { todo ->
            byNote[todo.noteId]?.let { n ->
                val reason = if (todo.dueDate != null) "含截止日期 ${todo.dueDate} · ${todo.text}" else "未设日期待办 · ${todo.text}"
                toEvidenceItem(n, reason)
            }
        }

        val observations = buildList {
            add("共扫描到 ${openTodos.size} 项未完成待办，其中 ${withDue.size} 项设定了明确日期，${withoutDue.size} 项尚无截止时间。")
            if (withDue.isNotEmpty()) {
                add("最早到期项为「${withDue.first().text}」（${withDue.first().dueDate}）。")
            }
            if (withoutDue.isNotEmpty()) {
                add("无日期事项中可先清理或归并「${withoutDue.first().text}」。")
            }
        }

        return AnalysisOutcome(
            status = "completed",
            result = AgentRunResult(
                engineNotice = ENGINE_NOTICE,
                headline = "当前共有 ${openTodos.size} 项未完成待办，建议优先推进带日期的 ${withDue.size} 项。",
                observations = observations,
                evidence = evidence,
                alternativeView = "有些待办写成了一时念头却长期没动，与其全盘硬推，不如删掉或降级为灵感备注。",
                nextQuestions = listOf(
                    "整理最近 7 天已完成和待办进展写份周报草稿",
                    "回看今天和近三天的重点",
                ),
                toolSteps = listOf(stepScope, stepTodo),
                suggestedTodos = prioritized.take(3).map { item ->
                    if (item.dueDate != null) "${item.text}（${item.dueDate}）" else item.text
                },
                filteredByPolicyCount = filteredByPolicyCount,
                scannedCount = scopedNotes.size,
            ),
        )
    }

    private fun analyzeWeeklyDraft(
        scopedNotes: List<NoteEntity>,
        links: List<NoteLinkEntity>,
        stepScope: AgentToolStep,
        filteredByPolicyCount: Int,
        now: Instant,
        zoneId: ZoneId,
    ): AnalysisOutcome {
        val weekStart = now.minus(7, ChronoUnit.DAYS)
        val weekNotes = scopedNotes.filter { n ->
            val ts = parseInstantOrNull(n.updatedAt) ?: parseInstantOrNull(n.createdAt)
            ts != null && !ts.isBefore(weekStart)
        }.sortedByDescending { it.updatedAt }

        val stepWeek = AgentToolStep("filter_7_days", "收集最近 7 天新建与修订的笔记", "completed", weekNotes.size)
        if (weekNotes.isEmpty()) {
            return insufficientOutcome(
                steps = listOf(stepScope, stepWeek),
                filteredByPolicyCount = filteredByPolicyCount,
                reason = "最近 7 天没有新增或修改过已授权的笔记，暂时无法生成本周草稿。",
            )
        }

        val doneCount = weekNotes.sumOf { n ->
            parseDocumentBlocks(n.documentJson).count {
                it["type"]?.toString() == "checkbox" && (it["checked"] as? Boolean == true)
            }
        }
        val openTodos = collectOpenTodos(weekNotes)
        val evidence = weekNotes.take(5).map { toEvidenceItem(it, "本周记录 · ${it.updatedAt.take(10)}") }

        val todayStr = LocalDate.now(zoneId).toString()
        val observations = buildList {
            add("截至 $todayStr 的近 7 天内，共形成/更新了 ${weekNotes.size} 条笔记。")
            add("周内已勾选完成 $doneCount 项待办，仍有 ${openTodos.size} 项待跟进。")
            add("代表主题：${evidence.take(3).joinToString("、") { "「${it.noteTitle}」" }}。")
        }

        return AnalysisOutcome(
            status = "completed",
            result = AgentRunResult(
                engineNotice = ENGINE_NOTICE,
                headline = "本周草稿已基于近 7 天 ${weekNotes.size} 条笔记整理就绪，可先预览并一键存为笔记继续编辑。",
                observations = observations,
                evidence = evidence,
                alternativeView = "周报草稿按本周写入和更新的笔记直接汇总，未覆盖线下或未记入浮记的工作内容。",
                nextQuestions = listOf(
                    "帮我挑出这批记录里存疑或有转折的地方",
                    "看看全部待办还有哪些要收尾",
                ),
                toolSteps = listOf(stepScope, stepWeek),
                suggestedTodos = openTodos.take(3).map { it.text },
                filteredByPolicyCount = filteredByPolicyCount,
                scannedCount = scopedNotes.size,
            ),
        )
    }

    private fun analyzeFreeQuestion(
        prompt: String,
        scopedNotes: List<NoteEntity>,
        scopedChunks: List<NoteChunkEntity>,
        tags: List<TagEntity>,
        noteTagIds: Map<String, Set<String>>,
        noteNotebookIds: Map<String, Set<String>>,
        links: List<NoteLinkEntity>,
        scope: NoteScope,
        stepScope: AgentToolStep,
        filteredByPolicyCount: Int,
    ): AnalysisOutcome {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty()) {
            return insufficientOutcome(
                steps = listOf(stepScope),
                filteredByPolicyCount = filteredByPolicyCount,
                reason = "请输入你想查的问题，或直接点选上方快捷场景。",
            )
        }

        val searchResult = LocalRetrievalEngine.searchEvidence(
            query = cleanPrompt,
            scope = scope,
            allNotes = scopedNotes,
            allChunks = scopedChunks,
            tags = tags,
            noteTagIdsByNote = noteTagIds,
            noteNotebookIdsByNote = noteNotebookIds,
            limit = 6,
        )
        val stepSearch = AgentToolStep("search_chunks", "按关键词与片段检索相关笔记", "completed", searchResult.hits.size)

        if (searchResult.hits.isEmpty()) {
            return insufficientOutcome(
                steps = listOf(stepScope, stepSearch),
                filteredByPolicyCount = filteredByPolicyCount,
                reason = "在当前范围内没有找到直接提到「$cleanPrompt」的本地笔记（共检索 ${scopedNotes.size} 条），换个更宽泛的词或切换到“全部笔记”再试。",
            )
        }

        val evidence = searchResult.hits.take(5).map { hit ->
            val sanitizedExcerpt = sanitizeQuoteText(hit.mergedExcerpt)
            AgentEvidenceItem(
                noteId = hit.note.id,
                noteTitle = hit.noteTitle,
                noteRevision = hit.note.revision,
                excerpt = sanitizedExcerpt,
                reason = hit.reasons.ifEmpty { listOf("关键词匹配") }.joinToString(" · "),
                updatedAt = hit.note.updatedAt,
            )
        }

        val relatedTodos = collectOpenTodos(searchResult.hits.map { it.note })
        val stepSynth = AgentToolStep("synthesize_local", "本机整理证据与可追问线索", "completed", evidence.size)

        val observations = buildList {
            add("围绕「$cleanPrompt」，本机检索到 ${searchResult.hits.size} 条相关线索，主要分布在 ${evidence.take(3).joinToString("、") { "「${it.noteTitle}」" }}。")
            if (relatedTodos.isNotEmpty()) {
                add("这些相关笔记里还有 ${relatedTodos.size} 项未勾待办（如「${relatedTodos.first().text}」）。")
            }
            if (searchResult.hits.any { it.reasons.any { r -> r.contains("引用") } }) {
                add("部分笔记之间已有双向引用连接，可从下方来源点开串联阅读。")
            }
        }

        return AnalysisOutcome(
            status = "completed",
            result = AgentRunResult(
                engineNotice = ENGINE_NOTICE,
                headline = "在已授权笔记里找到 ${searchResult.hits.size} 条与「${safeUtf16Slice(cleanPrompt, 0, 18)}」直接相关的记录。",
                observations = observations,
                evidence = evidence,
                alternativeView = "当前由本机词频与引用匹配定位相关段落，尚未进行语义推理；若某些笔记使用了不同表述，可用“找一找”补充同义词检索。",
                nextQuestions = listOf(
                    "查看「${safeUtf16Slice(cleanPrompt, 0, 12)}」从早到晚的观点轨迹",
                    "在这批线索里找找相反意见或疑问",
                ),
                toolSteps = listOf(stepScope, stepSearch, stepSynth),
                suggestedTodos = relatedTodos.take(3).map { it.text },
                filteredByPolicyCount = filteredByPolicyCount,
                scannedCount = scopedNotes.size,
            ),
        )
    }

    private fun insufficientOutcome(
        steps: List<AgentToolStep>,
        filteredByPolicyCount: Int,
        reason: String,
    ): AnalysisOutcome = AnalysisOutcome(
        status = "insufficient_evidence",
        result = AgentRunResult(
            engineNotice = ENGINE_NOTICE,
            headline = "当前范围证据不足，暂不强行下结论",
            observations = listOf(reason),
            evidence = emptyList(),
            alternativeView = if (filteredByPolicyCount > 0) {
                "另有 $filteredByPolicyCount 条笔记受 AI 访问策略限制未参与此次分析。"
            } else {
                "放宽时间范围、换一个关键词，或点上方“今天回看 / 未完待办梳理”都可以立刻重新分析。"
            },
            nextQuestions = listOf("回看今天与近三天的记录", "梳理目前还没做完的待办"),
            toolSteps = steps,
            suggestedTodos = emptyList(),
            filteredByPolicyCount = filteredByPolicyCount,
            scannedCount = 0,
        ),
    )

    private data class OpenTodoRef(
        val noteId: String,
        val text: String,
        val dueDate: String?,
    )

    private fun collectOpenTodos(notes: List<NoteEntity>): List<OpenTodoRef> {
        val out = mutableListOf<OpenTodoRef>()
        for (note in notes) {
            for (block in parseDocumentBlocks(note.documentJson)) {
                val type = block["type"]?.toString()
                val checked = block["checked"] as? Boolean ?: false
                val text = block["text"]?.toString().orEmpty()
                val dueDate = block["due_date"]?.toString()
                if (type == "checkbox" && !checked && text.isNotBlank()) {
                    out.add(OpenTodoRef(note.id, sanitizeQuoteText(text), dueDate))
                }
            }
        }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseDocumentBlocks(docJson: String): List<Map<String, Any?>> =
        runCatching {
            (MiniJson.parseObject(docJson)["blocks"] as? List<*>)
                ?.mapNotNull { it as? Map<String, Any?> }
                .orEmpty()
        }.getOrDefault(emptyList())

    private fun toEvidenceItem(note: NoteEntity, reason: String): AgentEvidenceItem {
        val title = LocalRetrievalEngine.displayNoteTitle(note)
        val rawBody = LocalRetrievalEngine.bodyExcludingTitle(note, note.searchText, title)
            .ifBlank { note.searchText.ifBlank { title } }
        val excerpt = safeUtf16Slice(sanitizeQuoteText(rawBody), 0, 140)
        return AgentEvidenceItem(
            noteId = note.id,
            noteTitle = title,
            noteRevision = note.revision,
            excerpt = excerpt,
            reason = reason,
            updatedAt = note.updatedAt,
        )
    }

    /** Neutralizes any prompt-injection-looking lines inside user notes so quotes remain strictly inert data. */
    fun sanitizeQuoteText(raw: String): String {
        var text = raw.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        for (pattern in injectionPatterns) {
            if (text.contains(pattern, ignoreCase = true)) {
                text = "[笔记原文引用] " + text.replace(pattern, "[...]", ignoreCase = true)
            }
        }
        return safeUtf16Slice(text, 0, 180)
    }

    private fun parseInstantOrNull(iso: String?): Instant? {
        if (iso.isNullOrBlank()) return null
        return runCatching { Instant.parse(iso) }.getOrNull()
    }
}
