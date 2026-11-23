package io.github.z3275630ops.floatingnotes.data.discovery

import androidx.room.withTransaction
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.core.model.newId
import io.github.z3275630ops.floatingnotes.data.agent.AgentActionEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteScope
import io.github.z3275630ops.floatingnotes.data.local.NoteTagEntity
import io.github.z3275630ops.floatingnotes.data.local.NotesDatabase
import io.github.z3275630ops.floatingnotes.data.local.NotesRepository
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
import io.github.z3275630ops.floatingnotes.data.local.tagNameKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoUnit

internal data class DailyReviewSnapshot(
    val session: ReviewSessionEntity,
    val cards: List<ReviewCardModel>,
    val currentIndex: Int,
    val onThisDay: List<OnThisDayEntry>,
    val config: ReviewConfig,
    val suppressedCount: Int,
)

internal data class ReviewCardModel(
    val note: NoteEntity,
    val title: String,
    val previewExcerpt: String,
    val fullText: String,
    val tagNames: List<String>,
    val tagIds: List<String>,
    val eventActions: Set<String>,
    val scheduleReason: String = "",
    val keySentences: List<String> = emptyList(),
)

internal data class ActiveWalkSnapshot(
    val session: WalkSessionEntity,
    val allSteps: List<WalkStepEntity>,
    val currentStep: WalkStepEntity?,
    val currentNote: NoteEntity?,
    val currentNoteTags: List<String>,
    val canStepBack: Boolean,
    val stepPositionLabel: String,
)

internal class DiscoveryRepository(
    private val database: NotesDatabase,
    private val notesRepository: NotesRepository,
    val preferences: ExplorePreferences,
) {
    private val notesDao = database.notes()
    private val orgDao = database.organization()
    private val discoveryDao = database.discovery()
    private val isoFormatter = DateTimeFormatterBuilder().appendInstant(3).toFormatter()

    suspend fun ensureIndexFresh(forceRebuild: Boolean = false): RetrievalCoverage = withContext(Dispatchers.IO) {
        if (forceRebuild) {
            discoveryDao.clearAllChunks()
        } else {
            discoveryDao.purgeStaleOrDeletedChunks()
        }
        val activeNotes = notesDao.allNotes().filter { it.deletedAt == null }
        val marks = discoveryDao.indexedNoteRevisions().associate { it.noteId to it.maxRev }
        for (note in activeNotes) {
            val hasContent = !note.title.isNullOrBlank() || note.searchText.isNotBlank()
            val currentMark = marks[note.id]
            val needsSync = if (!hasContent) {
                currentMark != null
            } else {
                currentMark == null || currentMark != note.revision
            }
            if (needsSync) {
                discoveryDao.deleteChunksForNote(note.id)
                val chunks = LocalRetrievalEngine.buildChunks(note)
                if (chunks.isNotEmpty()) {
                    discoveryDao.insertChunks(chunks)
                }
            }
        }
        val allChunks = discoveryDao.allChunks().groupBy { it.noteId }
        LocalRetrievalEngine.computeCoverage(activeNotes, allChunks)
    }

    suspend fun syncChunksForNote(note: NoteEntity) = withContext(Dispatchers.IO) {
        discoveryDao.deleteChunksForNote(note.id)
        if (note.deletedAt == null) {
            val chunks = LocalRetrievalEngine.buildChunks(note)
            if (chunks.isNotEmpty()) {
                discoveryDao.insertChunks(chunks)
            }
        }
    }

    suspend fun removeChunksForNoteIds(noteIds: List<String>) = withContext(Dispatchers.IO) {
        for (id in noteIds) {
            discoveryDao.deleteChunksForNote(id)
        }
    }

    suspend fun loadOrCreateDailyReview(
        startExtraRound: Boolean = false,
        now: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        profileKey: String = "default",
    ): DailyReviewSnapshot = withContext(Dispatchers.IO) {
        ensureIndexFresh()
        val today = now.atZone(zoneId).toLocalDate()
        val todayStr = today.toString()
        val config = preferences.reviewConfig
        val configHash = config.configHash()
        val allNotes = notesDao.allNotes()
        val notesById = allNotes.associateBy { it.id }
        val tags = orgDao.tags()
        val tagsById = tags.associateBy { it.id }
        val noteTags = orgDao.noteTags()
        val noteTagIdsMap = noteTags.groupBy({ it.noteId }, { it.tagId }).mapValues { it.value.toSet() }
        val events = discoveryDao.allReviewEvents()

        val existing = discoveryDao.latestReviewSessionForDay(todayStr, profileKey)
        val session = if (
            !startExtraRound &&
            existing != null &&
            existing.configHash == configHash
        ) {
            val savedIds = decodeStringArray(existing.cardIdsJson)
                .filter { id ->
                    val n = notesById[id]
                    n != null && n.deletedAt == null && n.archivedAt == null
                }
            val suppressedNow = DiscoveryEngines.suppressedNoteIdsAt(events, now)
            val validSaved = savedIds.filterNot { it in suppressedNow }
            if (validSaved.isNotEmpty() || savedIds.isNotEmpty()) {
                if (validSaved.size != savedIds.size || validSaved != decodeStringArray(existing.cardIdsJson)) {
                    val updated = existing.copy(
                        cardIdsJson = JSONArray(validSaved).toString(),
                        currentIndex = existing.currentIndex.coerceIn(0, (validSaved.size - 1).coerceAtLeast(0)),
                        updatedAt = isoFormatter.format(now),
                    )
                    discoveryDao.upsertReviewSession(updated)
                    updated
                } else {
                    existing
                }
            } else {
                createAndSaveReviewSession(
                    notes = allNotes,
                    noteTagIdsMap = noteTagIdsMap,
                    events = events,
                    config = config,
                    localDate = today,
                    profileKey = profileKey,
                    round = existing.round,
                    now = now,
                    zoneId = zoneId,
                )
            }
        } else {
            val nextRound = if (startExtraRound && existing != null) existing.round + 1 else (existing?.round ?: 1)
            createAndSaveReviewSession(
                notes = allNotes,
                noteTagIdsMap = noteTagIdsMap,
                events = events,
                config = config,
                localDate = today,
                profileKey = profileKey,
                round = nextRound,
                now = now,
                zoneId = zoneId,
            )
        }

        val sessionEvents = events.filter { it.sessionId == session.id }
        val eventsByNote = sessionEvents.groupBy { it.noteId }
        val cardIds = decodeStringArray(session.cardIdsJson)
        val cardNotes = cardIds.mapNotNull { id -> notesById[id]?.takeIf { it.deletedAt == null } }
        val fsrsStates = FsrsReviewScheduler.computeNoteStates(
            notes = cardNotes,
            events = events,
            now = now,
            zoneId = zoneId,
        )
        val cards = cardIds.mapNotNull { id ->
            val note = notesById[id]?.takeIf { it.deletedAt == null } ?: return@mapNotNull null
            val tIds = noteTagIdsMap[id].orEmpty().toList()
            val tNames = tIds.mapNotNull { tagsById[it]?.name }.sorted()
            val fullText = note.searchText.ifBlank { note.title.orEmpty() }
            val scheduleReason = fsrsStates[id]?.scheduleReason.orEmpty()
            val keySentences = if (fullText.length > 160) {
                LocalTextIntelligence.extractKeySentences(
                    text = fullText,
                    maxSentences = 2,
                    maxChars = 180,
                ).sentences
            } else {
                emptyList()
            }
            ReviewCardModel(
                note = note,
                title = LocalRetrievalEngine.displayNoteTitle(note),
                previewExcerpt = fullText.take(180),
                fullText = fullText,
                tagNames = tNames,
                tagIds = tIds,
                eventActions = eventsByNote[id].orEmpty().mapTo(HashSet()) { it.action },
                scheduleReason = scheduleReason,
                keySentences = keySentences,
            )
        }
        val onThisDay = DiscoveryEngines.findOnThisDayNotes(
            notes = allNotes,
            today = today,
            zoneId = zoneId,
            excludedNoteIds = preferences.excludedFindNoteIds,
        )
        val suppressedCount = DiscoveryEngines.suppressedNoteIdsAt(events, now).size +
            config.excludedTagIds.size +
            preferences.excludedFindNoteIds.size

        DailyReviewSnapshot(
            session = session,
            cards = cards,
            currentIndex = session.currentIndex.coerceIn(0, (cards.size - 1).coerceAtLeast(0)),
            onThisDay = onThisDay,
            config = config,
            suppressedCount = suppressedCount,
        )
    }

    private suspend fun createAndSaveReviewSession(
        notes: List<NoteEntity>,
        noteTagIdsMap: Map<String, Set<String>>,
        events: List<ReviewEventEntity>,
        config: ReviewConfig,
        localDate: LocalDate,
        profileKey: String,
        round: Int,
        now: Instant,
        zoneId: ZoneId,
    ): ReviewSessionEntity {
        val selectedIds = DiscoveryEngines.selectDailyReviewNoteIds(
            notes = notes,
            noteTagIdsByNote = noteTagIdsMap,
            events = events,
            config = config,
            localDate = localDate,
            profileKey = profileKey,
            round = round,
            now = now,
            zoneId = zoneId,
        )
        val ts = isoFormatter.format(now)
        val session = ReviewSessionEntity(
            id = "day:${localDate}:profile:$profileKey:round:$round:${config.configHash().hashCode()}",
            localDate = localDate.toString(),
            profileKey = profileKey,
            configHash = config.configHash(),
            round = round,
            cardIdsJson = JSONArray(selectedIds).toString(),
            currentIndex = 0,
            createdAt = ts,
            updatedAt = ts,
        )
        discoveryDao.upsertReviewSession(session)
        return session
    }

    suspend fun setReviewIndex(sessionId: String, index: Int, now: Instant = Instant.now()) = withContext(Dispatchers.IO) {
        val current = discoveryDao.reviewSession(sessionId) ?: return@withContext
        val cardIds = decodeStringArray(current.cardIdsJson)
        val clamped = index.coerceIn(0, (cardIds.size - 1).coerceAtLeast(0))
        discoveryDao.upsertReviewSession(current.copy(currentIndex = clamped, updatedAt = isoFormatter.format(now)))
    }

    suspend fun markReviewCardSeen(
        sessionId: String,
        noteId: String,
        now: Instant = Instant.now(),
    ) = withContext(Dispatchers.IO) {
        if (sessionId.isBlank() || noteId.isBlank()) return@withContext
        val alreadySeen = discoveryDao.allReviewEvents().any {
            it.sessionId == sessionId && it.noteId == noteId && it.action == "seen"
        }
        if (alreadySeen) return@withContext
        discoveryDao.upsertReviewEvent(
            ReviewEventEntity(
                id = "seen:$sessionId:$noteId",
                sessionId = sessionId,
                noteId = noteId,
                action = "seen",
                untilIso = null,
                createdAt = isoFormatter.format(now),
            ),
        )
    }

    suspend fun recommendTagsForDraft(
        draftText: String,
        currentNoteId: String?,
        attachedTagIds: Set<String>,
        limit: Int = 4,
    ): List<TagRecommendation> = withContext(Dispatchers.IO) {
        val allNotes = notesDao.allNotes()
        val allChunks = discoveryDao.allChunks()
        val tags = orgDao.tags()
        val noteTagMap = orgDao.noteTags().groupBy({ it.noteId }, { it.tagId }).mapValues { it.value.toSet() }
        LocalTagAndGraphEngine.recommendTagsForDraft(
            draftText = draftText,
            currentNoteId = currentNoteId,
            attachedTagIds = attachedTagIds,
            allNotes = allNotes,
            allChunks = allChunks,
            tags = tags,
            noteTagIdsByNote = noteTagMap,
            limit = limit,
        )
    }

    suspend fun findDuplicateGroups(
        minSimilarityPercent: Int = 65,
        maxGroups: Int = 10,
    ): List<DuplicateNoteGroup> = withContext(Dispatchers.IO) {
        ensureIndexFresh()
        val allNotes = notesDao.allNotes()
        val allChunks = discoveryDao.allChunks()
        LocalTextIntelligence.findDuplicateGroups(
            notes = allNotes,
            allChunks = allChunks,
            minSimilarityPercent = minSimilarityPercent,
            maxGroups = maxGroups,
        )
    }

    suspend fun recordReviewAction(
        sessionId: String,
        noteId: String,
        action: String,
        now: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ) = withContext(Dispatchers.IO) {
        val untilIso = when (action) {
            "deferred" -> {
                val tomorrowStart = now.atZone(zoneId).toLocalDate().plusDays(1).atStartOfDay(zoneId).toInstant()
                isoFormatter.format(tomorrowStart)
            }
            "suppressed_7d" -> isoFormatter.format(now.plus(7, ChronoUnit.DAYS))
            else -> null
        }
        discoveryDao.upsertReviewEvent(
            ReviewEventEntity(
                id = newId(),
                sessionId = sessionId,
                noteId = noteId,
                action = action,
                untilIso = untilIso,
                createdAt = isoFormatter.format(now),
            ),
        )
        if (action == "deferred" || action == "suppressed_7d" || action == "excluded") {
            val session = discoveryDao.reviewSession(sessionId) ?: return@withContext
            val ids = decodeStringArray(session.cardIdsJson)
            val remaining = if (action == "deferred") {
                ids.filter { it != noteId }
            } else {
                ids.filter { it != noteId }
            }
            discoveryDao.upsertReviewSession(
                session.copy(
                    cardIdsJson = JSONArray(remaining).toString(),
                    currentIndex = session.currentIndex.coerceIn(0, (remaining.size - 1).coerceAtLeast(0)),
                    updatedAt = isoFormatter.format(now),
                ),
            )
        }
    }

    suspend fun annotateReviewCardAsNote(
        sessionId: String,
        targetNote: NoteEntity,
        annotationText: String,
        now: Instant = Instant.now(),
    ): NoteEntity = withContext(Dispatchers.IO) {
        val clean = annotationText.trim()
        require(clean.isNotEmpty()) { "批注内容不能为空" }
        val refLabel = LocalRetrievalEngine.displayNoteTitle(targetNote)
        val doc = NoteDocument(
            blocks = listOf(
                NoteBlock.paragraph("回顾批注：$clean"),
                NoteBlock.reference(
                    noteId = targetNote.id,
                    revision = targetNote.revision,
                    label = refLabel,
                ),
            ),
        )
        val draft = notesRepository.newDraft("review-annotation-${newId()}").copy(
            documentJson = doc.encode(),
            editSeq = 1L,
        )
        val created = notesRepository.commit(draft)
        syncChunksForNote(created)
        recordReviewAction(sessionId, targetNote.id, "annotated", now = now)
        created
    }

    suspend fun restoreAllExclusions(): Int = withContext(Dispatchers.IO) {
        val beforeSuppressed = discoveryDao.allReviewEvents()
            .count { it.action in setOf("excluded", "suppressed_7d", "deferred") }
        val tagEx = preferences.reviewConfig.excludedTagIds.size
        val findEx = preferences.excludedFindNoteIds.size
        discoveryDao.clearSuppressionEvents()
        preferences.clearAllExclusions()
        beforeSuppressed + tagEx + findEx
    }

    suspend fun searchFind(
        query: String,
        scope: NoteScope,
        limit: Int = 24,
    ): FindSearchResult = withContext(Dispatchers.IO) {
        ensureIndexFresh()
        val allNotes = notesDao.allNotes()
        val allChunks = discoveryDao.allChunks()
        val tags = orgDao.tags()
        val noteTagMap = orgDao.noteTags().groupBy({ it.noteId }, { it.tagId }).mapValues { it.value.toSet() }
        val noteNotebookMap = orgDao.notebookNotes().groupBy({ it.noteId }, { it.notebookId }).mapValues { it.value.toSet() }
        LocalRetrievalEngine.searchEvidence(
            query = query,
            scope = scope,
            allNotes = allNotes,
            allChunks = allChunks,
            tags = tags,
            noteTagIdsByNote = noteTagMap,
            noteNotebookIdsByNote = noteNotebookMap,
            excludedNoteIds = preferences.excludedFindNoteIds,
            limit = limit,
        )
    }

    suspend fun saveEvidenceBasketAsNote(
        query: String,
        items: List<EvidenceBasketItem>,
    ): NoteEntity = withContext(Dispatchers.IO) {
        require(items.isNotEmpty()) { "请至少选择一条证据卡片" }
        val blocks = ArrayList<NoteBlock>()
        blocks.add(NoteBlock.paragraph("关于「${query.trim().ifEmpty { "找一找" }}」的线索整理"))
        for (item in items) {
            val reasonsText = if (item.reasons.isNotEmpty()) "（${item.reasons.joinToString(" · ")}）" else ""
            blocks.add(NoteBlock.paragraph("${item.excerpt}$reasonsText"))
            blocks.add(
                NoteBlock.reference(
                    noteId = item.noteId,
                    revision = item.noteRevision,
                    label = item.noteTitle,
                ),
            )
        }
        val doc = NoteDocument(blocks)
        val draft = notesRepository.newDraft("evidence-basket-${newId()}").copy(
            documentJson = doc.encode(),
            editSeq = 1L,
        )
        val committed = notesRepository.commit(draft)
        syncChunksForNote(committed)
        committed
    }

    suspend fun relatedNotesFor(
        noteId: String,
        includeArchived: Boolean = false,
        limit: Int = 6,
    ): List<RelatedNoteMatch> = withContext(Dispatchers.IO) {
        val sourceNote = notesDao.note(noteId)?.takeIf { it.deletedAt == null } ?: return@withContext emptyList()
        ensureIndexFresh()
        val allNotes = notesDao.allNotes()
        val chunksByNote = discoveryDao.allChunks().groupBy { it.noteId }
        val tags = orgDao.tags()
        val noteTagMap = orgDao.noteTags().groupBy({ it.noteId }, { it.tagId }).mapValues { it.value.toSet() }
        val noteNotebookMap = orgDao.notebookNotes().groupBy({ it.noteId }, { it.notebookId }).mapValues { it.value.toSet() }
        val links = orgDao.noteLinks()
        LocalRetrievalEngine.computeRelatedNotes(
            sourceNote = sourceNote,
            allNotes = allNotes,
            chunksByNote = chunksByNote,
            tags = tags,
            noteTagIdsByNote = noteTagMap,
            noteNotebookIdsByNote = noteNotebookMap,
            links = links,
            excludedNoteIds = preferences.excludedFindNoteIds,
            includeArchived = includeArchived,
            limit = limit,
        )
    }

    suspend fun findLiveEdgeEcho(
        draftText: String,
        currentNoteId: String?,
        dismissedNoteIds: Set<String> = emptySet(),
    ): EvidenceHit? = withContext(Dispatchers.IO) {
        val allNotes = notesDao.allNotes()
        val allChunks = discoveryDao.allChunks()
        val tags = orgDao.tags()
        val noteTagMap = orgDao.noteTags().groupBy({ it.noteId }, { it.tagId }).mapValues { it.value.toSet() }
        val noteNotebookMap = orgDao.notebookNotes().groupBy({ it.noteId }, { it.notebookId }).mapValues { it.value.toSet() }
        val excluded = preferences.excludedFindNoteIds + dismissedNoteIds
        val hit = LocalRetrievalEngine.findBestEdgeEchoCandidate(
            draftText = draftText,
            currentNoteId = currentNoteId,
            allNotes = allNotes,
            allChunks = allChunks,
            tags = tags,
            noteTagIdsByNote = noteTagMap,
            noteNotebookIdsByNote = noteNotebookMap,
            excludedNoteIds = excluded,
        ) ?: return@withContext null

        verifyEdgeEchoHit(hit, currentNoteId, excluded)
    }

    suspend fun verifyEdgeEchoHit(
        hit: EvidenceHit,
        currentNoteId: String?,
        excludedNoteIds: Set<String> = preferences.excludedFindNoteIds,
    ): EvidenceHit? = withContext(Dispatchers.IO) {
        if (hit.note.id == currentNoteId) return@withContext null
        if (hit.note.id in excludedNoteIds) return@withContext null
        val fresh = notesDao.note(hit.note.id) ?: return@withContext null
        if (fresh.deletedAt != null || fresh.archivedAt != null) return@withContext null
        hit.copy(note = fresh, noteTitle = LocalRetrievalEngine.displayNoteTitle(fresh))
    }

    suspend fun startWalk(
        seedNoteId: String? = null,
        seedTagId: String? = null,
        now: Instant = Instant.now(),
    ): ActiveWalkSnapshot? = withContext(Dispatchers.IO) {
        ensureIndexFresh()
        val allNotes = notesDao.allNotes().filter { it.deletedAt == null && it.archivedAt == null }
        val noteTagMap = orgDao.noteTags().groupBy({ it.noteId }, { it.tagId }).mapValues { it.value.toSet() }
        val excluded = preferences.excludedFindNoteIds
        val pool = allNotes.filter {
            it.id !in excluded &&
                (it.searchText.isNotBlank() || !it.title.isNullOrBlank()) &&
                (seedTagId == null || seedTagId in noteTagMap[it.id].orEmpty())
        }
        val seedNote = seedNoteId?.let { id -> pool.firstOrNull { it.id == id } ?: allNotes.firstOrNull { it.id == id } }
            ?: pool.randomOrNull()
            ?: return@withContext null

        val ts = isoFormatter.format(now)
        val sessionId = newId()
        val session = WalkSessionEntity(
            id = sessionId,
            seedNoteId = seedNote.id,
            seedTagId = seedTagId,
            currentStepOrder = 0,
            status = "active",
            createdAt = ts,
            updatedAt = ts,
        )
        val firstStep = WalkStepEntity(
            id = newId(),
            sessionId = sessionId,
            stepOrder = 0,
            parentStepId = null,
            noteId = seedNote.id,
            reasonLabel = if (seedTagId != null) "标签起点" else "起点",
            strategyType = "seed",
            branchIndex = 0,
            userMemo = null,
            createdAt = ts,
        )
        discoveryDao.upsertWalkSession(session)
        discoveryDao.upsertWalkStep(firstStep)
        loadWalkSnapshot(sessionId)
    }

    suspend fun loadWalkSnapshot(sessionId: String): ActiveWalkSnapshot? = withContext(Dispatchers.IO) {
        val session = discoveryDao.walkSession(sessionId) ?: return@withContext null
        val steps = discoveryDao.walkSteps(sessionId)
        if (steps.isEmpty()) return@withContext null
        val currentStep = steps.firstOrNull { it.stepOrder == session.currentStepOrder } ?: steps.last()
        val note = notesDao.note(currentStep.noteId)?.takeIf { it.deletedAt == null }
        val tagsById = orgDao.tags().associateBy { it.id }
        val currentNoteTags = orgDao.noteTags()
            .filter { it.noteId == currentStep.noteId }
            .mapNotNull { tagsById[it.tagId]?.name }
            .sorted()
        ActiveWalkSnapshot(
            session = session,
            allSteps = steps,
            currentStep = currentStep,
            currentNote = note,
            currentNoteTags = currentNoteTags,
            canStepBack = currentStep.parentStepId != null,
            stepPositionLabel = "第 ${currentStep.stepOrder + 1} 站 · 共 ${steps.size} 站",
        )
    }

    suspend fun loadLatestActiveWalk(): ActiveWalkSnapshot? = withContext(Dispatchers.IO) {
        val session = discoveryDao.latestActiveWalkSession() ?: return@withContext null
        loadWalkSnapshot(session.id)
    }

    suspend fun stepWalkForward(
        sessionId: String,
        forceCrossDirection: Boolean,
        now: Instant = Instant.now(),
    ): ActiveWalkSnapshot? = withContext(Dispatchers.IO) {
        val session = discoveryDao.walkSession(sessionId) ?: return@withContext null
        val steps = discoveryDao.walkSteps(sessionId)
        val currentStep = steps.firstOrNull { it.stepOrder == session.currentStepOrder } ?: steps.lastOrNull()
            ?: return@withContext null
        val allNotes = notesDao.allNotes()
        val notesById = allNotes.associateBy { it.id }
        val currentNote = notesById[currentStep.noteId] ?: return@withContext loadWalkSnapshot(sessionId)
        val chunksByNote = discoveryDao.allChunks().groupBy { it.noteId }
        val tags = orgDao.tags()
        val noteTagMap = orgDao.noteTags().groupBy({ it.noteId }, { it.tagId }).mapValues { it.value.toSet() }
        val noteNotebookMap = orgDao.notebookNotes().groupBy({ it.noteId }, { it.notebookId }).mapValues { it.value.toSet() }
        val links = orgDao.noteLinks()

        val visitedIds = steps.mapTo(HashSet()) { it.noteId }
        val choice = DiscoveryEngines.pickNextWalkCandidate(
            currentNote = currentNote,
            allNotes = allNotes,
            chunksByNote = chunksByNote,
            tags = tags,
            noteTagIdsByNote = noteTagMap,
            noteNotebookIdsByNote = noteNotebookMap,
            links = links,
            visitedNoteIds = visitedIds,
            excludedNoteIds = preferences.excludedFindNoteIds,
            seedTagId = session.seedTagId,
            forceCrossDirection = forceCrossDirection,
        ) ?: return@withContext loadWalkSnapshot(sessionId)

        val nextOrder = (steps.maxOfOrNull { it.stepOrder } ?: 0) + 1
        val nextBranch = if (forceCrossDirection) currentStep.branchIndex + 1 else currentStep.branchIndex
        val ts = isoFormatter.format(now)
        val newStep = WalkStepEntity(
            id = newId(),
            sessionId = sessionId,
            stepOrder = nextOrder,
            parentStepId = currentStep.id,
            noteId = choice.note.id,
            reasonLabel = choice.reasonLabel,
            strategyType = choice.strategyType,
            branchIndex = nextBranch,
            userMemo = null,
            createdAt = ts,
        )
        discoveryDao.upsertWalkStep(newStep)
        discoveryDao.upsertWalkSession(
            session.copy(currentStepOrder = nextOrder, status = "active", updatedAt = ts),
        )
        loadWalkSnapshot(sessionId)
    }

    suspend fun stepWalkBack(sessionId: String, now: Instant = Instant.now()): ActiveWalkSnapshot? = withContext(Dispatchers.IO) {
        val session = discoveryDao.walkSession(sessionId) ?: return@withContext null
        val steps = discoveryDao.walkSteps(sessionId)
        val current = steps.firstOrNull { it.stepOrder == session.currentStepOrder } ?: return@withContext loadWalkSnapshot(sessionId)
        val parent = current.parentStepId?.let { pid -> steps.firstOrNull { it.id == pid } } ?: return@withContext loadWalkSnapshot(sessionId)
        discoveryDao.upsertWalkSession(
            session.copy(currentStepOrder = parent.stepOrder, updatedAt = isoFormatter.format(now)),
        )
        loadWalkSnapshot(sessionId)
    }

    suspend fun saveWalkStepMemo(stepId: String, sessionId: String, memo: String) = withContext(Dispatchers.IO) {
        val steps = discoveryDao.walkSteps(sessionId)
        val target = steps.firstOrNull { it.id == stepId } ?: return@withContext
        discoveryDao.upsertWalkStep(target.copy(userMemo = memo.trim().ifEmpty { null }))
    }

    suspend fun buildWalkPostcard(
        sessionId: String,
        hideDetailsForPrivacy: Boolean,
        zoneId: ZoneId = ZoneId.systemDefault(),
        markCompleted: Boolean = true,
    ): WalkPostcardSummary = withContext(Dispatchers.IO) {
        val session = discoveryDao.walkSession(sessionId)
        if (markCompleted && session != null && session.status != "completed") {
            discoveryDao.upsertWalkSession(
                session.copy(status = "completed", updatedAt = isoFormatter.format(Instant.now())),
            )
        }
        val steps = discoveryDao.walkSteps(sessionId)
        val notesById = notesDao.allNotes().associateBy { it.id }
        val tagsById = orgDao.tags().associateBy { it.id }
        val noteTagsByNote = orgDao.noteTags()
            .groupBy { it.noteId }
            .mapValues { (_, list) -> list.mapNotNull { tagsById[it.tagId]?.name }.sorted() }
        DiscoveryEngines.buildWalkPostcard(
            steps = steps,
            notesById = notesById,
            noteTagsByNote = noteTagsByNote,
            zoneId = zoneId,
            hideDetailsForPrivacy = hideDetailsForPrivacy,
        )
    }

    suspend fun savePostcardAsNote(postcard: WalkPostcardSummary): NoteEntity = withContext(Dispatchers.IO) {
        val lines = postcard.markdownBody.split("\n\n").filter { it.isNotBlank() }
        val blocks = lines.map { block ->
            NoteBlock.paragraph(block)
        }.ifEmpty { listOf(NoteBlock.paragraph(postcard.markdownBody)) }
        val doc = NoteDocument(blocks)
        val draft = notesRepository.newDraft("walk-postcard-${newId()}").copy(
            documentJson = doc.encode(),
            editSeq = 1L,
        )
        val created = notesRepository.commit(draft)
        syncChunksForNote(created)
        created
    }

    sealed class DualPairDrawResult {
        data class Ready(val pair: DualCollisionPair, val eligibleCount: Int) : DualPairDrawResult()
        data class Insufficient(val eligibleCount: Int) : DualPairDrawResult()
    }

    sealed class DualCollisionSaveResult {
        data class Saved(val noteId: String, val title: String) : DualCollisionSaveResult()
        data class AlreadySaved(val noteId: String?) : DualCollisionSaveResult()
        data object SourceChangedOrDeleted : DualCollisionSaveResult()
    }

    suspend fun drawDualCollisionPair(excludePairKeys: Set<String> = emptySet()): DualPairDrawResult =
        withContext(Dispatchers.IO) {
            val allNotes = notesDao.allNotes()
            val allChunks = discoveryDao.allChunks()
            val tagsById = orgDao.tags().associateBy { it.id }
            val noteTagIdsMap = orgDao.noteTags().groupBy({ it.noteId }, { it.tagId }).mapValues { it.value.toSet() }
            val links = orgDao.noteLinks()
            val (pair, eligibleCount) = DiscoveryEngines.pickDualCollisionPair(
                notes = allNotes,
                noteTagIdsByNote = noteTagIdsMap,
                tagsById = tagsById,
                excludePairKeys = excludePairKeys,
                allChunks = allChunks,
                links = links,
            )
            if (pair == null) DualPairDrawResult.Insufficient(eligibleCount)
            else DualPairDrawResult.Ready(pair, eligibleCount)
        }

    suspend fun analyzeDualCollision(pair: DualCollisionPair): DualCollisionBridge = withContext(Dispatchers.IO) {
        val links = orgDao.noteLinks()
        val allNotes = notesDao.allNotes()
        val tagsById = orgDao.tags().associateBy { it.id }
        val noteTagMap = orgDao.noteTags().groupBy({ it.noteId }, { it.tagId }).mapValues { it.value.toSet() }
        DiscoveryEngines.analyzeDualCollision(
            pair = pair,
            links = links,
            allNotes = allNotes,
            noteTagIdsByNote = noteTagMap,
            tagsById = tagsById,
        )
    }

    suspend fun saveDualCollisionAsNote(
        pair: DualCollisionPair,
        bridge: DualCollisionBridge?,
        userReflection: String,
        farFetched: Boolean,
        actionId: String = "dual_collision:${pair.pairId}",
        now: Instant = Instant.now(),
    ): DualCollisionSaveResult = withContext(Dispatchers.IO) {
        val agentDao = database.agent()
        val existing = agentDao.actionById(actionId)
        if (existing != null && existing.status == "committed") {
            return@withContext DualCollisionSaveResult.AlreadySaved(existing.targetNoteId)
        }

        val leftFresh = notesDao.note(pair.leftNote.id)
        val rightFresh = notesDao.note(pair.rightNote.id)
        if (leftFresh == null || rightFresh == null ||
            leftFresh.deletedAt != null || rightFresh.deletedAt != null ||
            leftFresh.revision != pair.leftRevision || rightFresh.revision != pair.rightRevision
        ) {
            return@withContext DualCollisionSaveResult.SourceChangedOrDeleted
        }

        val revJson = JSONObject()
            .put(leftFresh.id, leftFresh.revision)
            .put(rightFresh.id, rightFresh.revision)
            .toString()

        val saved = database.withTransaction {
            val blocks = mutableListOf<NoteBlock>()
            blocks.add(NoteBlock.paragraph("【双卡对撞】${pair.leftTitle} × ${pair.rightTitle}"))
            val reflection = userReflection.trim()
            if (reflection.isNotEmpty()) {
                blocks.add(NoteBlock.paragraph("我的联想：$reflection"))
            }
            if (bridge != null && !farFetched) {
                blocks.add(NoteBlock.paragraph("本机线索：${bridge.headline}"))
                for (pt in bridge.points) {
                    blocks.add(NoteBlock.paragraph("• $pt"))
                }
            }
            blocks.add(NoteBlock.reference(leftFresh.id, leftFresh.revision, pair.leftTitle))
            blocks.add(NoteBlock.reference(rightFresh.id, rightFresh.revision, pair.rightTitle))

            val doc = NoteDocument(blocks)
            val draft = notesRepository.newDraft("dual-collision-${newId()}").copy(
                documentJson = doc.encode(),
                editSeq = 1L,
                sourceType = if (bridge != null && !farFetched) "walk_collision_ai" else "walk_collision",
            )
            val created = notesRepository.commit(draft)
            attachTagByName(created.id, "双卡对撞")
            if (bridge != null && !farFetched) {
                attachTagByName(created.id, "本机分析")
            }
            agentDao.insertAction(
                AgentActionEntity(
                    actionId = actionId,
                    runId = pair.pairId,
                    actionType = "dual_collision_save",
                    targetNoteId = created.id,
                    sourceRevisionsJson = revJson,
                    status = "committed",
                    summary = "双卡对撞已存为笔记",
                    createdAt = isoFormatter.format(now),
                ),
            )
            created
        }
        syncChunksForNote(saved)
        DualCollisionSaveResult.Saved(saved.id, LocalRetrievalEngine.displayNoteTitle(saved))
    }

    private suspend fun attachTagByName(noteId: String, tagName: String) {
        val key = tagNameKey(tagName)
        val existing = orgDao.tags().firstOrNull { it.parentId.isEmpty() && it.nameKey == key }
        val tagId = existing?.id ?: TagEntity(newId(), "", tagName, key).also { orgDao.insertTag(it) }.id
        orgDao.attachTag(NoteTagEntity(noteId, tagId))
    }

    private fun decodeStringArray(json: String): List<String> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map(arr::getString)
    }.getOrDefault(emptyList())
}
