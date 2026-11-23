package io.github.z3275630ops.floatingnotes.data.discovery

import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.core.model.newId
import io.github.z3275630ops.floatingnotes.core.model.safeUtf16Slice
import io.github.z3275630ops.floatingnotes.data.agent.LocalAgentEngine
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteLinkEntity
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.random.Random

internal data class OnThisDayEntry(
    val note: NoteEntity,
    val yearsAgo: Int,
    val yearLabel: String,
    val excerpt: String,
)

internal data class WalkCandidateChoice(
    val note: NoteEntity,
    val strategyType: String,
    val reasonLabel: String,
)

internal data class WalkPostcardSummary(
    val title: String,
    val stepCount: Int,
    val dateSpanLabel: String,
    val pathLines: List<String>,
    val reflectionLines: List<String>,
    val markdownBody: String,
    val privacyHidden: Boolean,
)

internal data class DualCollisionPair(
    val pairId: String,
    val leftNote: NoteEntity,
    val leftTitle: String,
    val leftBody: String,
    val leftTags: List<String>,
    val leftRevision: Long,
    val rightNote: NoteEntity,
    val rightTitle: String,
    val rightBody: String,
    val rightTags: List<String>,
    val rightRevision: Long,
) {
    val pairKey: String
        get() = listOf(leftNote.id, rightNote.id).sorted().joinToString("|")
}

internal data class DualCollisionBridge(
    val headline: String,
    val points: List<String>,
    val engineNotice: String,
)

internal object DiscoveryEngines {
    const val ECHO_DEBOUNCE_MS = 800L
    const val ECHO_AUTO_INTERVAL_MS = 30_000L

    fun selectDailyReviewNoteIds(
        notes: List<NoteEntity>,
        noteTagIdsByNote: Map<String, Set<String>>,
        events: List<ReviewEventEntity>,
        config: ReviewConfig,
        localDate: LocalDate,
        profileKey: String,
        round: Int,
        now: Instant,
        zoneId: ZoneId,
    ): List<String> {
        val suppressedNoteIds = suppressedNoteIdsAt(events, now)

        val cutoffInstant = if (config.rangeDays > 0) {
            now.minus(config.rangeDays.toLong(), ChronoUnit.DAYS)
        } else null

        val eligible = notes.filter { note ->
            if (note.deletedAt != null || note.archivedAt != null) return@filter false
            if (note.id in suppressedNoteIds) return@filter false
            if (note.searchText.isBlank() && note.title.isNullOrBlank()) return@filter false
            val tagIds = noteTagIdsByNote[note.id].orEmpty()
            if (!config.includeUntagged && tagIds.isEmpty()) return@filter false
            if (config.excludedTagIds.isNotEmpty() && tagIds.any { it in config.excludedTagIds }) return@filter false
            if (config.includeTodosOnly) {
                val hasOpenTodo = runCatching {
                    NoteDocument.decode(note.documentJson).blocks.any { it.type == "checkbox" && !it.checked }
                }.getOrDefault(false)
                if (!hasOpenTodo) return@filter false
            }
            if (cutoffInstant != null) {
                val created = runCatching { Instant.parse(note.createdAt) }.getOrNull()
                if (created == null || created.isBefore(cutoffInstant)) return@filter false
            }
            true
        }

        if (eligible.isEmpty()) return emptyList()

        return FsrsReviewScheduler.rankForDailyReview(
            eligibleNotes = eligible,
            events = events,
            localDate = localDate,
            profileKey = profileKey,
            configHash = config.configHash(),
            round = round,
            now = now,
            zoneId = zoneId,
            dailyCount = config.dailyCount,
        ).map { it.noteId }
    }

    fun suppressedNoteIdsAt(events: List<ReviewEventEntity>, now: Instant): Set<String> {
        val out = HashSet<String>()
        for (ev in events) {
            when (ev.action) {
                "excluded" -> out.add(ev.noteId)
                "suppressed_7d", "deferred" -> {
                    val until = ev.untilIso?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    if (until == null || until.isAfter(now)) {
                        out.add(ev.noteId)
                    }
                }
            }
        }
        return out
    }

    fun findOnThisDayNotes(
        notes: List<NoteEntity>,
        today: LocalDate,
        zoneId: ZoneId,
        excludedNoteIds: Set<String> = emptySet(),
    ): List<OnThisDayEntry> {
        return notes.mapNotNull { note ->
            if (note.deletedAt != null || note.archivedAt != null) return@mapNotNull null
            if (note.id in excludedNoteIds) return@mapNotNull null
            val instant = runCatching { Instant.parse(note.createdAt) }.getOrNull() ?: return@mapNotNull null
            val noteDate = instant.atZone(zoneId).toLocalDate()
            if (noteDate.monthValue != today.monthValue || noteDate.dayOfMonth != today.dayOfMonth) {
                return@mapNotNull null
            }
            val years = today.year - noteDate.year
            if (years <= 0) return@mapNotNull null
            val excerpt = safeUtf16Slice(note.searchText.ifBlank { note.title.orEmpty() }, 0, 140)
            if (excerpt.isBlank()) return@mapNotNull null
            OnThisDayEntry(
                note = note,
                yearsAgo = years,
                yearLabel = "${noteDate.year}年今日（${years}年前）",
                excerpt = excerpt,
            )
        }.sortedByDescending { it.yearsAgo }
    }

    fun pickNextWalkCandidate(
        currentNote: NoteEntity,
        allNotes: List<NoteEntity>,
        chunksByNote: Map<String, List<NoteChunkEntity>>,
        tags: List<TagEntity>,
        noteTagIdsByNote: Map<String, Set<String>>,
        noteNotebookIdsByNote: Map<String, Set<String>>,
        links: List<NoteLinkEntity>,
        visitedNoteIds: Set<String>,
        excludedNoteIds: Set<String>,
        seedTagId: String?,
        forceCrossDirection: Boolean,
        rollPercent: Int = Random.nextInt(100),
    ): WalkCandidateChoice? {
        val activePool = allNotes.filter { note ->
            if (note.deletedAt != null || note.archivedAt != null) return@filter false
            if (note.id == currentNote.id) return@filter false
            if (note.id in excludedNoteIds) return@filter false
            if (seedTagId != null && !forceCrossDirection) {
                val tagIds = noteTagIdsByNote[note.id].orEmpty()
                if (seedTagId !in tagIds) return@filter false
            }
            note.searchText.isNotBlank() || !note.title.isNullOrBlank()
        }
        val unvisited = activePool.filter { it.id !in visitedNoteIds }.ifEmpty { activePool }
        if (unvisited.isEmpty()) return null

        val relatedMatches = LocalRetrievalEngine.computeRelatedNotes(
            sourceNote = currentNote,
            allNotes = unvisited,
            chunksByNote = chunksByNote,
            tags = tags,
            noteTagIdsByNote = noteTagIdsByNote,
            noteNotebookIdsByNote = noteNotebookIdsByNote,
            links = links,
            excludedNoteIds = excludedNoteIds,
            includeArchived = false,
            limit = 12,
        )
        val relatedIds = relatedMatches.mapTo(HashSet()) { it.note.id }
        val nonStrongPool = unvisited.filter { it.id !in relatedIds }

        if (forceCrossDirection) {
            if (nonStrongPool.isNotEmpty()) {
                val pick = nonStrongPool[(abs(rollPercent) % nonStrongPool.size)]
                return WalkCandidateChoice(
                    note = pick,
                    strategyType = "cross_topic",
                    reasonLabel = "换条小路",
                )
            }
            if (relatedMatches.size > 1) {
                val alt = relatedMatches[(abs(rollPercent) % (relatedMatches.size - 1)) + 1]
                return WalkCandidateChoice(
                    note = alt.note,
                    strategyType = "cross_topic",
                    reasonLabel = "换条小路 · ${alt.reasons.firstOrNull() ?: "换个脉络"}",
                )
            }
        }

        val bucket = rollPercent.coerceIn(0, 99)
        return when {
            bucket < 70 && relatedMatches.isNotEmpty() -> {
                val topIndex = abs(bucket) % minOf(relatedMatches.size, 3)
                val chosen = relatedMatches[topIndex]
                WalkCandidateChoice(
                    note = chosen.note,
                    strategyType = "related",
                    reasonLabel = chosen.reasons.firstOrNull() ?: "相关延伸",
                )
            }
            bucket < 90 && nonStrongPool.isNotEmpty() -> {
                val pick = nonStrongPool[abs(bucket) % nonStrongPool.size]
                WalkCandidateChoice(
                    note = pick,
                    strategyType = "cross_topic",
                    reasonLabel = "换条小路",
                )
            }
            nonStrongPool.isNotEmpty() -> {
                val oldestFirst = nonStrongPool.sortedBy { it.createdAt }
                val pick = oldestFirst[abs(bucket) % minOf(oldestFirst.size, 4)]
                WalkCandidateChoice(
                    note = pick,
                    strategyType = "serendipity",
                    reasonLabel = "意外遇见",
                )
            }
            relatedMatches.isNotEmpty() -> {
                val chosen = relatedMatches.first()
                WalkCandidateChoice(
                    note = chosen.note,
                    strategyType = "related",
                    reasonLabel = chosen.reasons.firstOrNull() ?: "相关延伸",
                )
            }
            else -> {
                WalkCandidateChoice(
                    note = unvisited.first(),
                    strategyType = "serendipity",
                    reasonLabel = "意外遇见",
                )
            }
        }
    }

    fun buildWalkPostcard(
        steps: List<WalkStepEntity>,
        notesById: Map<String, NoteEntity>,
        noteTagsByNote: Map<String, List<String>>,
        zoneId: ZoneId,
        hideDetailsForPrivacy: Boolean,
    ): WalkPostcardSummary {
        if (steps.isEmpty()) {
            return WalkPostcardSummary(
                title = "本次漫步还没有停留卡片",
                stepCount = 0,
                dateSpanLabel = "0 条笔记",
                pathLines = emptyList(),
                reflectionLines = emptyList(),
                markdownBody = "本次漫步尚未记录卡片。",
                privacyHidden = hideDetailsForPrivacy,
            )
        }
        val activeSteps = steps.filter { notesById[it.noteId]?.deletedAt == null }
        val stepDates = activeSteps.mapNotNull { step ->
            val note = notesById[step.noteId] ?: return@mapNotNull null
            runCatching { Instant.parse(note.createdAt).atZone(zoneId).toLocalDate() }.getOrNull()
        }
        val spanLabel = if (stepDates.isEmpty()) {
            "共 ${activeSteps.size} 站"
        } else {
            val minD = stepDates.min()
            val maxD = stepDates.max()
            if (minD == maxD) "${minD} · 共 ${activeSteps.size} 站" else "${minD} ~ ${maxD} · 共 ${activeSteps.size} 站"
        }

        val pathLines = activeSteps.mapIndexed { idx, step ->
            val note = notesById[step.noteId]
            val title = if (hideDetailsForPrivacy) {
                "第 ${idx + 1} 站（已隐藏标题）"
            } else {
                note?.let { LocalRetrievalEngine.displayNoteTitle(it) } ?: "已失效笔记"
            }
            val tagsSuffix = if (!hideDetailsForPrivacy && note != null) {
                val t = noteTagsByNote[note.id].orEmpty()
                if (t.isNotEmpty()) " ${t.joinToString(" ") { "#$it" }}" else ""
            } else ""
            "${idx + 1}. $title · ${step.reasonLabel}$tagsSuffix"
        }

        val reflectionLines = activeSteps.mapIndexedNotNull { idx, step ->
            val memo = step.userMemo?.trim()
            if (memo.isNullOrEmpty()) null else "第 ${idx + 1} 站随想：$memo"
        }

        val md = buildString {
            append("漫步明信片（").append(spanLabel).append("）")
            if (hideDetailsForPrivacy) {
                append("\n已开启隐私保护：仅保留路径脉络与随想。")
            }
            append("\n\n【走过的路径】")
            for (line in pathLines) {
                append("\n").append(line)
            }
            if (!hideDetailsForPrivacy) {
                val snippets = activeSteps.mapIndexedNotNull { idx, step ->
                    val note = notesById[step.noteId] ?: return@mapIndexedNotNull null
                    val s = safeUtf16Slice(note.searchText.trim(), 0, 80)
                    if (s.isEmpty()) null else "- 第 ${idx + 1} 站摘录：$s"
                }
                if (snippets.isNotEmpty()) {
                    append("\n\n【沿途摘录】")
                    for (s in snippets) append("\n").append(s)
                }
            }
            if (reflectionLines.isNotEmpty()) {
                append("\n\n【途中随想】")
                for (r in reflectionLines) append("\n").append(r)
            }
        }

        return WalkPostcardSummary(
            title = "笔记漫步明信片",
            stepCount = activeSteps.size,
            dateSpanLabel = spanLabel,
            pathLines = pathLines,
            reflectionLines = reflectionLines,
            markdownBody = md,
            privacyHidden = hideDetailsForPrivacy,
        )
    }

    fun shouldTriggerAutoEcho(
        globalEnabled: Boolean,
        sessionMuted: Boolean,
        isComposing: Boolean,
        draftText: String,
        lastAutoShownAtMs: Long,
        nowMs: Long,
    ): Boolean {
        if (!globalEnabled || sessionMuted) return false
        if (isComposing) return false
        if (draftText.trim().length < 4) return false
        if (lastAutoShownAtMs > 0L && (nowMs - lastAutoShownAtMs) < ECHO_AUTO_INTERVAL_MS) return false
        return true
    }

    fun pickDualCollisionPair(
        notes: List<NoteEntity>,
        noteTagIdsByNote: Map<String, Set<String>>,
        tagsById: Map<String, TagEntity>,
        excludePairKeys: Set<String> = emptySet(),
        random: Random = Random.Default,
        allChunks: List<NoteChunkEntity> = emptyList(),
        links: List<NoteLinkEntity> = emptyList(),
    ): Pair<DualCollisionPair?, Int> {
        val eligible = notes.filter { n ->
            n.deletedAt == null &&
                n.archivedAt == null &&
                (n.searchText.isNotBlank() || !n.title.isNullOrBlank())
        }
        if (eligible.size < 2) return null to eligible.size

        val chunksByNote = allChunks.groupBy { it.noteId }
        val chosen = LocalTagAndGraphEngine.selectBestDualCollisionPair(
            eligibleNotes = eligible,
            chunksByNote = chunksByNote,
            noteTagIdsByNote = noteTagIdsByNote,
            tagsById = tagsById,
            links = links,
            excludePairKeys = excludePairKeys,
            random = random,
        ) ?: return null to eligible.size

        val left = chosen.first
        val right = chosen.second
        val leftTitle = LocalRetrievalEngine.displayNoteTitle(left)
        val rightTitle = LocalRetrievalEngine.displayNoteTitle(right)
        val leftTagNames = noteTagIdsByNote[left.id].orEmpty().mapNotNull { tagsById[it]?.name }.sorted()
        val rightTagNames = noteTagIdsByNote[right.id].orEmpty().mapNotNull { tagsById[it]?.name }.sorted()

        return DualCollisionPair(
            pairId = newId(),
            leftNote = left,
            leftTitle = leftTitle,
            leftBody = LocalRetrievalEngine.bodyExcludingTitle(left, title = leftTitle),
            leftTags = leftTagNames,
            leftRevision = left.revision,
            rightNote = right,
            rightTitle = rightTitle,
            rightBody = LocalRetrievalEngine.bodyExcludingTitle(right, title = rightTitle),
            rightTags = rightTagNames,
            rightRevision = right.revision,
        ) to eligible.size
    }

    fun analyzeDualCollision(
        pair: DualCollisionPair,
        links: List<NoteLinkEntity>,
        allNotes: List<NoteEntity> = emptyList(),
        noteTagIdsByNote: Map<String, Set<String>> = emptyMap(),
        tagsById: Map<String, TagEntity> = emptyMap(),
    ): DualCollisionBridge {
        val (sharedTerms, bridgeTagNames, jaccardSim) = LocalTagAndGraphEngine.computeBridgeInsights(
            left = pair.leftNote,
            right = pair.rightNote,
            leftTags = pair.leftTags,
            rightTags = pair.rightTags,
            allNotes = allNotes,
            noteTagIdsByNote = noteTagIdsByNote,
            tagsById = tagsById,
        )
        val sharedTags = pair.leftTags.intersect(pair.rightTags.toSet()).toList()
        val directLinked = links.any {
            (it.sourceId == pair.leftNote.id && it.targetId == pair.rightNote.id) ||
                (it.sourceId == pair.rightNote.id && it.targetId == pair.leftNote.id)
        }
        val leftTime = runCatching { Instant.parse(pair.leftNote.createdAt) }.getOrNull()
        val rightTime = runCatching { Instant.parse(pair.rightNote.createdAt) }.getOrNull()
        val dayGap = if (leftTime != null && rightTime != null) {
            abs(ChronoUnit.DAYS.between(leftTime, rightTime))
        } else 0L

        val points = buildList {
            if (directLinked) {
                add("两条笔记之间已经存在直接引用（@双链），可以顺着原语境合并重读。")
            }
            if (sharedTags.isNotEmpty()) {
                add("共同归属标签：${sharedTags.joinToString("、") { "#$it" }}，属于同一关注域下的不同片段。")
            } else if (pair.leftTags.isNotEmpty() || pair.rightTags.isNotEmpty()) {
                val lt = pair.leftTags.take(2).joinToString(" ") { "#$it" }.ifEmpty { "无标签" }
                val rt = pair.rightTags.take(2).joinToString(" ") { "#$it" }.ifEmpty { "无标签" }
                add("跨领域对撞：左卡侧重 $lt，右卡侧重 $rt，试着用其中一条的思路检验另一条。")
            }
            if (bridgeTagNames.isNotEmpty()) {
                add("图谱桥梁标签：${bridgeTagNames.joinToString("、") { "#$it" }}（在其它笔记中常同时与两边主题共现）。")
            }
            if (sharedTerms.isNotEmpty()) {
                val overlapPct = (jaccardSim * 100.0).toInt()
                add("高区分度共同概念：${sharedTerms.joinToString("、")}（词集重合度约 ${overlapPct}%）。")
            } else {
                val lTop = LocalAgentEngine.sanitizeQuoteText(pair.leftTitle)
                val rTop = LocalAgentEngine.sanitizeQuoteText(pair.rightTitle)
                add("表面用词几乎不重合，建议围绕“如果把「$lTop」的场景套用到「$rTop」会怎样”写一句联想。")
            }
            if (dayGap >= 7) {
                add("两段记录相隔 $dayGap 天，能看出不同阶段的关注点差异。")
            }
        }

        val headline = when {
            directLinked -> "它们本就有引用关联，适合做一次合并延伸"
            sharedTerms.isNotEmpty() -> "两条相隔记录在「${sharedTerms.first()}」上产生交集"
            bridgeTagNames.isNotEmpty() -> "两条笔记可通过 #${bridgeTagNames.first()} 桥梁主题串联思考"
            sharedTags.isNotEmpty() -> "同属 #${sharedTags.first()} 主题的两种不同切片"
            else -> "这是一组跨主题偶然相遇，适合自己写下新联系"
        }

        return DualCollisionBridge(
            headline = headline,
            points = points,
            engineNotice = "本机规则分析（未调用云端大模型） · 不满意可点“牵强”换一组",
        )
    }
}
