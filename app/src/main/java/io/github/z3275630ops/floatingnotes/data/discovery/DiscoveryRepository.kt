package io.github.z3275630ops.floatingnotes.data.discovery

import androidx.room.withTransaction
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.core.model.newId
import io.github.z3275630ops.floatingnotes.data.agent.AgentActionEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteLinkEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteScope
import io.github.z3275630ops.floatingnotes.data.local.NoteTagEntity
import io.github.z3275630ops.floatingnotes.data.local.NotesDatabase
import io.github.z3275630ops.floatingnotes.data.local.NotesRepository
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
import io.github.z3275630ops.floatingnotes.data.local.tagNameKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

internal data class DiscoveryCorpusSnapshot(
    val watermark: DiscoveryWatermarkRow,
    val allNotes: List<NoteEntity>,
    val allChunks: List<NoteChunkEntity>,
    val chunksByNote: Map<String, List<NoteChunkEntity>>,
    val tags: List<TagEntity>,
    val noteTagMap: Map<String, Set<String>>,
    val noteNotebookMap: Map<String, Set<String>>,
    val links: List<NoteLinkEntity>,
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
    private val indexMutex = Mutex()
    private val corpusMutex = Mutex()

    @Volatile
    private var cachedCorpusSnapshot: DiscoveryCorpusSnapshot? = null

    private suspend fun invalidateCorpusSnapshot() {
        corpusMutex.withLock {
            cachedCorpusSnapshot = null
        }
    }

    internal suspend fun getOrLoadCorpusSnapshot(): DiscoveryCorpusSnapshot = corpusMutex.withLock {
        val currentWatermark = discoveryDao.discoveryWatermark()
        val existing = cachedCorpusSnapshot
        if (existing != null && existing.watermark == currentWatermark) {
            return@withLock existing
        }
        val allNotes = notesDao.allNotes()
        val allChunks = discoveryDao.allChunks()
        val chunksByNote = allChunks.groupBy { it.noteId }
        val tags = orgDao.tags()
        val noteTagMap = orgDao.noteTags()
            .groupBy({ it.noteId }, { it.tagId })
            .mapValues { it.value.toSet() }
        val noteNotebookMap = orgDao.notebookNotes()
            .groupBy({ it.noteId }, { it.notebookId })
            .mapValues { it.value.toSet() }
        val links = orgDao.noteLinks()
        DiscoveryCorpusSnapshot(
            watermark = currentWatermark,
            allNotes = allNotes,
            allChunks = allChunks,
            chunksByNote = chunksByNote,
            tags = tags,
            noteTagMap = noteTagMap,
            noteNotebookMap = noteNotebookMap,
            links = links,
        ).also { cachedCorpusSnapshot = it }
    }

    private suspend fun rebuildChunksForNoteIds(noteIds: List<String>) {
        for (batchIds in noteIds.chunked(DB_BATCH_SIZE)) {
            val notes = notesDao.notesByIds(batchIds)
            val chunksToInsert = ArrayList<NoteChunkEntity>(notes.size * 2)
            for (note in notes) {
                if (note.deletedAt != null) continue
                chunksToInsert.addAll(LocalRetrievalEngine.buildChunks(note))
            }
            database.withTransaction {
                discoveryDao.deleteChunksForNoteIds(batchIds)
                if (chunksToInsert.isNotEmpty()) {
                    for (chunkBatch in chunksToInsert.chunked(DB_BATCH_SIZE)) {
                        discoveryDao.insertChunks(chunkBatch)
                    }
                }
            }
        }
    }

    suspend fun ensureIndexFresh(forceRebuild: Boolean = false): RetrievalCoverage = withContext(Dispatchers.IO) {
        indexMutex.withLock {
            var mutated = false
            try {
                if (forceRebuild) {
                    mutated = true
                    discoveryDao.clearAllChunks()
                    val activeIds = discoveryDao.staleOrMissingActiveNoteIds()
                    if (activeIds.isNotEmpty()) {
                        rebuildChunksForNoteIds(activeIds)
                    }
                } else {
                    val chunksBeforePurge = discoveryDao.totalChunkCount()
                    val obsoleteIds = discoveryDao.obsoleteChunkNoteIds()
                    if (obsoleteIds.isNotEmpty()) {
                        mutated = true
                        for (batch in obsoleteIds.chunked(DB_BATCH_SIZE)) {
                            discoveryDao.deleteChunksForNoteIds(batch)
                        }
                    }
                    discoveryDao.purgeStaleOrDeletedChunks()
                    if (discoveryDao.totalChunkCount() != chunksBeforePurge) {
                        mutated = true
                    }
                    val dirtyIds = discoveryDao.staleOrMissingActiveNoteIds()
                    if (dirtyIds.isNotEmpty()) {
                        mutated = true
                        rebuildChunksForNoteIds(dirtyIds)
                    }
                }
            } finally {
                if (mutated) {
                    withContext(NonCancellable) {
                        invalidateCorpusSnapshot()
                    }
                }
            }

            val eligibleCount = discoveryDao.indexableActiveNoteCount()
            val indexedCount = discoveryDao.freshIndexedNoteCount()
            val totalChunks = discoveryDao.totalChunkCount()
            val watermark = if (eligibleCount > 0 && indexedCount > 0 && totalChunks > 0) {
                discoveryDao.discoveryWatermark().chunkMaxUpdatedAt
            } else {
                null
            }
            RetrievalCoverage(
                eligibleCount = eligibleCount,
                indexedCount = indexedCount,
                staleCount = (eligibleCount - indexedCount).coerceAtLeast(0),
                indexWatermark = watermark,
            )
        }
    }

    suspend fun syncChunksForNote(note: NoteEntity) = withContext(Dispatchers.IO) {
        indexMutex.withLock {
            var mutated = false
            try {
                val chunks = if (note.deletedAt == null) {
                    LocalRetrievalEngine.buildChunks(note)
                } else {
                    emptyList()
                }
                database.withTransaction {
                    mutated = true
                    discoveryDao.deleteChunksForNote(note.id)
                    if (chunks.isNotEmpty()) {
                        for (chunkBatch in chunks.chunked(DB_BATCH_SIZE)) {
                            discoveryDao.insertChunks(chunkBatch)
                        }
                    }
                }
            } finally {
                if (mutated) {
                    withContext(NonCancellable) {
                        invalidateCorpusSnapshot()
                    }
                }
            }
        }
    }

    suspend fun removeChunksForNoteIds(noteIds: List<String>) = withContext(Dispatchers.IO) {
        val distinctIds = noteIds.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
        if (distinctIds.isEmpty()) return@withContext
        indexMutex.withLock {
            var mutated = false
            try {
                database.withTransaction {
                    mutated = true
                    for (batch in distinctIds.chunked(DB_BATCH_SIZE)) {
                        discoveryDao.deleteChunksForNoteIds(batch)
                    }
                }
            } finally {
                if (mutated) {
                    withContext(NonCancellable) {
                        invalidateCorpusSnapshot()
                    }
                }
            }
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
        val snapshot = getOrLoadCorpusSnapshot()
        val allNotes = snapshot.allNotes
        val notesById = allNotes.associateBy { it.id }
        val tags = snapshot.tags
        val tagsById = tags.associateBy { it.id }
        val noteTagIdsMap = snapshot.noteTagMap
        val events = discoveryDao.allReviewEvents()

        val daySessions = discoveryDao.reviewSessionsForDay(todayStr, profileKey)
        val existingForConfig = discoveryDao.latestReviewSessionForDayAndConfig(todayStr, profileKey, configHash)
        val session = if (
            !startExtraRound &&
            existingForConfig != null
        ) {
            val savedIds = decodeStringArray(existingForConfig.cardIdsJson)
                .filter { id ->
                    val n = notesById[id]
                    n != null && n.deletedAt == null && n.archivedAt == null
                }
            val suppressedNow = DiscoveryEngines.suppressedNoteIdsAt(events, now)
            val validSaved = savedIds.filterNot { it in suppressedNow }
            if (validSaved.isNotEmpty() || savedIds.isNotEmpty()) {
                if (validSaved.size != savedIds.size || validSaved != decodeStringArray(existingForConfig.cardIdsJson)) {
                    val updated = existingForConfig.copy(
                        cardIdsJson = JSONArray(validSaved).toString(),
                        currentIndex = existingForConfig.currentIndex.coerceIn(0, (validSaved.size - 1).coerceAtLeast(0)),
                        updatedAt = isoFormatter.format(now),
                    )
                    discoveryDao.upsertReviewSession(updated)
                    updated
                } else {
                    existingForConfig
                }
            } else {
                createAndSaveReviewSession(
                    notes = allNotes,
                    noteTagIdsMap = noteTagIdsMap,
                    events = events,
                    config = config,
                    localDate = today,
                    profileKey = profileKey,
                    round = existingForConfig.round,
                    now = now,
                    zoneId = zoneId,
                )
            }
        } else {
            val maxRoundToday = daySessions.maxOfOrNull { it.round } ?: 0
            val nextRound = if (maxRoundToday > 0) maxRoundToday + 1 else 1
            val shownTodayIds = if (startExtraRound) {
                daySessions.flatMapTo(HashSet()) { decodeStringArray(it.cardIdsJson) }
            } else {
                emptySet()
            }
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
                alreadyShownNoteIds = shownTodayIds,
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
            val sortedTags = noteTagIdsMap[id].orEmpty()
                .mapNotNull { tid -> tagsById[tid]?.let { tid to it.name } }
                .sortedBy { it.second }
            val tIds = sortedTags.map { it.first }
            val tNames = sortedTags.map { it.second }
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
        alreadyShownNoteIds: Set<String> = emptySet(),
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
            alreadyShownNoteIds = alreadyShownNoteIds,
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
        database.withTransaction {
            val current = discoveryDao.reviewSession(sessionId) ?: return@withTransaction
            val cardIds = decodeStringArray(current.cardIdsJson)
            val clamped = index.coerceIn(0, (cardIds.size - 1).coerceAtLeast(0))
            discoveryDao.updateReviewSessionIndex(sessionId, clamped, isoFormatter.format(now))
        }
    }

    suspend fun markReviewCardSeen(
        sessionId: String,
        noteId: String,
        now: Instant = Instant.now(),
    ) = withContext(Dispatchers.IO) {
        if (sessionId.isBlank() || noteId.isBlank()) return@withContext
        discoveryDao.insertReviewEventIfAbsent(
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
        val snapshot = getOrLoadCorpusSnapshot()

        LocalTagAndGraphEngine.recommendTagsForDraft(
            draftText = draftText,
            currentNoteId = currentNoteId,
            attachedTagIds = attachedTagIds,
            allNotes = snapshot.allNotes,
            allChunks = snapshot.allChunks,
            tags = snapshot.tags,
            noteTagIdsByNote = snapshot.noteTagMap,
            limit = limit,
        )
    }

    suspend fun findDuplicateGroups(
        minSimilarityPercent: Int = 65,
        maxGroups: Int = 10,
    ): List<DuplicateNoteGroup> = withContext(Dispatchers.IO) {
        ensureIndexFresh()
        val snapshot = getOrLoadCorpusSnapshot()
        LocalTextIntelligence.findDuplicateGroups(
            notes = snapshot.allNotes,
            allChunks = snapshot.allChunks,
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
        database.withTransaction {
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
                val session = discoveryDao.reviewSession(sessionId) ?: return@withTransaction
                val ids = decodeStringArray(session.cardIdsJson)
                val removedIdx = ids.indexOf(noteId)
                val remaining = ids.filter { it != noteId }
                val adjustedIdx = when {
                    remaining.isEmpty() -> 0
                    removedIdx in 0 until session.currentIndex -> session.currentIndex - 1
                    else -> session.currentIndex
                }.coerceIn(0, (remaining.size - 1).coerceAtLeast(0))
                discoveryDao.upsertReviewSession(
                    session.copy(
                        cardIdsJson = JSONArray(remaining).toString(),
                        currentIndex = adjustedIdx,
                        updatedAt = isoFormatter.format(now),
                    ),
                )
            }
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
        val snapshot = getOrLoadCorpusSnapshot()
        LocalRetrievalEngine.searchEvidence(
            query = query,
            scope = scope,
            allNotes = snapshot.allNotes,
            allChunks = snapshot.allChunks,
            tags = snapshot.tags,
            noteTagIdsByNote = snapshot.noteTagMap,
            noteNotebookIdsByNote = snapshot.noteNotebookMap,
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
        val snapshot = getOrLoadCorpusSnapshot()
        LocalRetrievalEngine.computeRelatedNotes(
            sourceNote = sourceNote,
            allNotes = snapshot.allNotes,
            chunksByNote = snapshot.chunksByNote,
            tags = snapshot.tags,
            noteTagIdsByNote = snapshot.noteTagMap,
            noteNotebookIdsByNote = snapshot.noteNotebookMap,
            links = snapshot.links,
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
        if (draftText.trim().length < 4) return@withContext null
        val snapshot = getOrLoadCorpusSnapshot()
        val excluded = preferences.excludedFindNoteIds + dismissedNoteIds
        val hit = LocalRetrievalEngine.findBestEdgeEchoCandidate(
            draftText = draftText,
            currentNoteId = currentNoteId,
            allNotes = snapshot.allNotes,
            allChunks = snapshot.allChunks,
            tags = snapshot.tags,
            noteTagIdsByNote = snapshot.noteTagMap,
            noteNotebookIdsByNote = snapshot.noteNotebookMap,
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
        if (fresh.deletedAt != null || fresh.archivedAt != null || fresh.aiPolicy == "exclude") {
            return@withContext null
        }
        val stale = fresh.revision != hit.note.revision || fresh.contentHash != hit.note.contentHash
        if (stale) {
            val freshCorpus = buildString {
                fresh.title?.let(::append)
                append('\n')
                append(fresh.searchText)
            }
            val snippetProbe = hit.mergedExcerpt.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.length >= 4 && it != "…" }
                ?.take(24)
            if (snippetProbe != null && !freshCorpus.contains(snippetProbe, ignoreCase = true)) {
                return@withContext null
            }
            val validBlockIds = runCatching {
                NoteDocument.decode(fresh.documentJson).blocks.mapTo(HashSet()) { it.id }
            }.getOrDefault(emptySet())
            val safeBlockId = hit.matchedBlockId?.takeIf { it in validBlockIds }
            return@withContext hit.copy(
                note = fresh,
                noteTitle = LocalRetrievalEngine.displayNoteTitle(fresh),
                matchedBlockId = safeBlockId,
            )
        }
        hit.copy(note = fresh, noteTitle = LocalRetrievalEngine.displayNoteTitle(fresh))
    }

    suspend fun startWalk(
        seedNoteId: String? = null,
        seedTagId: String? = null,
        now: Instant = Instant.now(),
    ): ActiveWalkSnapshot? = withContext(Dispatchers.IO) {
        ensureIndexFresh()
        val snapshot = getOrLoadCorpusSnapshot()
        val allNotes = snapshot.allNotes.filter { it.deletedAt == null && it.archivedAt == null }
        val noteTagMap = snapshot.noteTagMap
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
        val snapshot = getOrLoadCorpusSnapshot()
        val allNotes = snapshot.allNotes
        val notesById = allNotes.associateBy { it.id }
        val currentNote = notesById[currentStep.noteId] ?: return@withContext loadWalkSnapshot(sessionId)
        val chunksByNote = snapshot.chunksByNote
        val tags = snapshot.tags
        val noteTagMap = snapshot.noteTagMap
        val noteNotebookMap = snapshot.noteNotebookMap
        val links = snapshot.links

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
        val snapshot = getOrLoadCorpusSnapshot()
        val notesById = snapshot.allNotes.associateBy { it.id }
        val tagsById = snapshot.tags.associateBy { it.id }
        val noteTagsByNote = snapshot.noteTagMap
            .mapValues { (_, tagIds) -> tagIds.mapNotNull { tagsById[it]?.name }.sorted() }
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

    sealed class DualBridgeAnalyzeResult {
        data class Ready(val pair: DualCollisionPair, val bridge: DualCollisionBridge) : DualBridgeAnalyzeResult()
        data object Unavailable : DualBridgeAnalyzeResult()
    }

    suspend fun drawDualCollisionPair(excludePairKeys: Set<String> = emptySet()): DualPairDrawResult =
        withContext(Dispatchers.IO) {
            val snapshot = getOrLoadCorpusSnapshot()
            val tagsById = snapshot.tags.associateBy { it.id }
            val (pair, eligibleCount) = DiscoveryEngines.pickDualCollisionPair(
                notes = snapshot.allNotes,
                noteTagIdsByNote = snapshot.noteTagMap,
                tagsById = tagsById,
                excludePairKeys = excludePairKeys,
                allChunks = snapshot.allChunks,
                links = snapshot.links,
                excludedNoteIds = preferences.excludedFindNoteIds,
            )
            if (pair == null) DualPairDrawResult.Insufficient(eligibleCount)
            else DualPairDrawResult.Ready(pair, eligibleCount)
        }

    suspend fun analyzeDualCollision(pair: DualCollisionPair): DualCollisionBridge = withContext(Dispatchers.IO) {
        val snapshot = getOrLoadCorpusSnapshot()
        val tagsById = snapshot.tags.associateBy { it.id }
        DiscoveryEngines.analyzeDualCollision(
            pair = pair,
            links = snapshot.links,
            allNotes = snapshot.allNotes,
            noteTagIdsByNote = snapshot.noteTagMap,
            tagsById = tagsById,
        )
    }

    suspend fun analyzeDualCollisionRefreshed(pair: DualCollisionPair): DualBridgeAnalyzeResult =
        withContext(Dispatchers.IO) {
            val snapshot = getOrLoadCorpusSnapshot()
            val allNotes = snapshot.allNotes
            val byId = allNotes.associateBy { it.id }
            val leftFresh = byId[pair.leftNote.id]
            val rightFresh = byId[pair.rightNote.id]
            val excludedIds = preferences.excludedFindNoteIds
            fun NoteEntity?.isEligible(): Boolean =
                this != null &&
                    deletedAt == null &&
                    archivedAt == null &&
                    aiPolicy != "exclude" &&
                    id !in excludedIds
            if (!leftFresh.isEligible() || !rightFresh.isEligible()) {
                return@withContext DualBridgeAnalyzeResult.Unavailable
            }
            val links = snapshot.links
            val tagsById = snapshot.tags.associateBy { it.id }
            val noteTagMap = snapshot.noteTagMap
            val leftNote = leftFresh!!
            val rightNote = rightFresh!!
            val alignedPair = if (leftNote.revision != pair.leftRevision || rightNote.revision != pair.rightRevision) {
                val leftTitle = LocalRetrievalEngine.displayNoteTitle(leftNote)
                val rightTitle = LocalRetrievalEngine.displayNoteTitle(rightNote)
                val leftTags = noteTagMap[leftNote.id].orEmpty().mapNotNull { tagsById[it]?.name }.sorted()
                val rightTags = noteTagMap[rightNote.id].orEmpty().mapNotNull { tagsById[it]?.name }.sorted()
                pair.copy(
                    leftNote = leftNote,
                    leftTitle = leftTitle,
                    leftBody = LocalRetrievalEngine.bodyExcludingTitle(leftNote, title = leftTitle),
                    leftTags = leftTags,
                    leftRevision = leftNote.revision,
                    rightNote = rightNote,
                    rightTitle = rightTitle,
                    rightBody = LocalRetrievalEngine.bodyExcludingTitle(rightNote, title = rightTitle),
                    rightTags = rightTags,
                    rightRevision = rightNote.revision,
                )
            } else {
                pair
            }
            val bridge = DiscoveryEngines.analyzeDualCollision(
                pair = alignedPair,
                links = links,
                allNotes = allNotes,
                noteTagIdsByNote = noteTagMap,
                tagsById = tagsById,
            )
            DualBridgeAnalyzeResult.Ready(alignedPair, bridge)
        }

    suspend fun saveDualCollisionAsNote(
        pair: DualCollisionPair,
        bridge: DualCollisionBridge?,
        userReflection: String,
        farFetched: Boolean,
        actionId: String = "dual_collision:${pair.pairId}:${pair.leftRevision}_${pair.rightRevision}",
        now: Instant = Instant.now(),
    ): DualCollisionSaveResult = withContext(Dispatchers.IO) {
        val agentDao = database.agent()
        val savedResult = database.withTransaction {
            val existing = agentDao.actionById(actionId)
            if (existing != null && existing.status == "committed") {
                return@withTransaction DualCollisionSaveResult.AlreadySaved(existing.targetNoteId)
            }

            val leftFresh = notesDao.note(pair.leftNote.id)
            val rightFresh = notesDao.note(pair.rightNote.id)
            if (leftFresh == null || rightFresh == null ||
                leftFresh.deletedAt != null || rightFresh.deletedAt != null ||
                leftFresh.revision != pair.leftRevision || rightFresh.revision != pair.rightRevision
            ) {
                return@withTransaction DualCollisionSaveResult.SourceChangedOrDeleted
            }

            val revJson = JSONObject()
                .put(leftFresh.id, leftFresh.revision)
                .put(rightFresh.id, rightFresh.revision)
                .toString()

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
            DualCollisionSaveResult.Saved(created.id, LocalRetrievalEngine.displayNoteTitle(created)) to created
        }

        if (savedResult is DualCollisionSaveResult) {
            return@withContext savedResult
        }
        @Suppress("UNCHECKED_CAST")
        val savedPair = savedResult as Pair<DualCollisionSaveResult.Saved, NoteEntity>
        syncChunksForNote(savedPair.second)
        savedPair.first
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

    private companion object {
        const val DB_BATCH_SIZE = 500
    }
}
