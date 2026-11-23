package io.github.z3275630ops.floatingnotes.data.agent

import androidx.room.withTransaction
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.core.model.newId
import io.github.z3275630ops.floatingnotes.data.discovery.DiscoveryRepository
import io.github.z3275630ops.floatingnotes.data.discovery.DuplicateNoteGroup
import io.github.z3275630ops.floatingnotes.data.discovery.LocalRetrievalEngine
import io.github.z3275630ops.floatingnotes.data.discovery.LocalTextIntelligence
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteScope
import io.github.z3275630ops.floatingnotes.data.local.NoteTagEntity
import io.github.z3275630ops.floatingnotes.data.local.NotesDatabase
import io.github.z3275630ops.floatingnotes.data.local.NotesRepository
import io.github.z3275630ops.floatingnotes.data.local.OrganizationRepository
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
import io.github.z3275630ops.floatingnotes.data.local.tagNameKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatterBuilder
import kotlin.coroutines.coroutineContext

internal sealed class AgentActionCommitResult {
    data class Committed(val noteId: String, val summary: String) : AgentActionCommitResult()
    data class AlreadyApplied(val existingNoteId: String?, val summary: String) : AgentActionCommitResult()
    data class ConflictDetected(val changedNoteTitles: List<String>) : AgentActionCommitResult()
}

internal class AgentRepository(
    private val database: NotesDatabase,
    private val notesRepository: NotesRepository,
    private val organizationRepository: OrganizationRepository,
    private val discoveryRepository: DiscoveryRepository,
) {
    private val notesDao = database.notes()
    private val orgDao = database.organization()
    private val agentDao = database.agent()
    private val discoveryDao = database.discovery()
    private val isoFormatter = DateTimeFormatterBuilder().appendInstant(3).toFormatter()

    fun observeRecentRuns(limit: Int = 15): Flow<List<AgentRunEntity>> = agentDao.observeRecentRuns(limit)

    suspend fun actionsForRun(runId: String): List<AgentActionEntity> = withContext(Dispatchers.IO) {
        agentDao.actionsForRun(runId)
    }

    suspend fun runAnalysis(
        skill: AgentSkill,
        prompt: String,
        scope: NoteScope,
        timeRange: AgentTimeRange,
        runId: String = newId(),
        now: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Pair<AgentRunEntity, AgentRunResult> = withContext(Dispatchers.IO) {
        val createdAt = isoFormatter.format(now)
        val effectivePrompt = prompt.trim().ifEmpty { skill.defaultPrompt }
        val runningEntity = AgentRunEntity(
            id = runId,
            skillId = skill.id,
            prompt = effectivePrompt,
            scopeJson = scope.encode(),
            status = "running",
            resultJson = "",
            createdAt = createdAt,
            updatedAt = createdAt,
        )
        agentDao.upsertRun(runningEntity)

        try {
            discoveryRepository.ensureIndexFresh()
            coroutineContext.ensureActive()
            delay(40)
            coroutineContext.ensureActive()

            val allNotes = notesDao.allNotes()
            val allChunks = discoveryDao.allChunks()
            val tags = orgDao.tags()
            val noteTags = orgDao.noteTags()
            val notebookNotes = orgDao.notebookNotes()
            val links = orgDao.noteLinks()

            coroutineContext.ensureActive()

            val outcome = LocalAgentEngine.analyze(
                skill = skill,
                prompt = effectivePrompt,
                notes = allNotes,
                chunks = allChunks,
                tags = tags,
                noteTags = noteTags,
                notebookNotes = notebookNotes,
                links = links,
                scope = scope,
                timeRange = timeRange,
                now = now,
                zoneId = zoneId,
            )

            val doneAt = isoFormatter.format(Instant.now())
            val completedEntity = runningEntity.copy(
                status = outcome.status,
                resultJson = outcome.result.encode(),
                updatedAt = doneAt,
            )
            agentDao.upsertRun(completedEntity)
            completedEntity to outcome.result
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val cancelledResult = AgentRunResult(
                    engineNotice = LocalAgentEngine.ENGINE_NOTICE,
                    headline = "已停止本次本机梳理",
                    observations = listOf("任务已按你的操作中断，可点击“重新整理”或换一个问题再次出发。"),
                    evidence = emptyList(),
                    alternativeView = "本次中断未写入或修改任何笔记。",
                    nextQuestions = listOf("重新梳理", "看看今天和近三天的重点"),
                    toolSteps = listOf(
                        AgentToolStep("cancelled", "用户主动停止", "cancelled", 0),
                    ),
                    suggestedTodos = emptyList(),
                    filteredByPolicyCount = 0,
                    scannedCount = 0,
                )
                agentDao.upsertRun(
                    runningEntity.copy(
                        status = "cancelled",
                        resultJson = cancelledResult.encode(),
                        updatedAt = isoFormatter.format(Instant.now()),
                    ),
                )
            }
            throw cancelled
        } catch (t: Throwable) {
            val failedResult = AgentRunResult(
                engineNotice = LocalAgentEngine.ENGINE_NOTICE,
                headline = "本机整理时遇到异常，未修改你的笔记",
                observations = listOf(t.message ?: "读取本机索引时发生未知错误"),
                evidence = emptyList(),
                alternativeView = "请重试，或在“探索 → 找一找”点击更新索引后再试。",
                nextQuestions = emptyList(),
                toolSteps = listOf(AgentToolStep("error", "本机整理失败", "failed", 0)),
                suggestedTodos = emptyList(),
                filteredByPolicyCount = 0,
                scannedCount = 0,
            )
            val failedEntity = runningEntity.copy(
                status = "failed",
                resultJson = failedResult.encode(),
                updatedAt = isoFormatter.format(Instant.now()),
            )
            agentDao.upsertRun(failedEntity)
            failedEntity to failedResult
        }
    }

    /** Idempotent commit of an Agent insight summary into a new user-owned note with source references. */
    suspend fun commitSaveInsightAsNote(
        run: AgentRunEntity,
        result: AgentRunResult,
        actionId: String = "agent_save_note:${run.id}",
        now: Instant = Instant.now(),
    ): AgentActionCommitResult = withContext(Dispatchers.IO) {
        val existing = agentDao.actionById(actionId)
        if (existing != null && existing.status == "committed") {
            return@withContext AgentActionCommitResult.AlreadyApplied(
                existingNoteId = existing.targetNoteId,
                summary = existing.summary,
            )
        }

        val currentNotes = notesDao.allNotes().associateBy { it.id }
        val changedTitles = detectSourceConflicts(result.evidence, currentNotes)
        val revJson = encodeRevisions(result.evidence)
        val ts = isoFormatter.format(now)
        if (changedTitles.isNotEmpty()) {
            agentDao.insertAction(
                AgentActionEntity(
                    actionId = "${actionId}:conflict:${now.toEpochMilli()}",
                    runId = run.id,
                    actionType = "save_note",
                    targetNoteId = null,
                    sourceRevisionsJson = revJson,
                    status = "conflict_refused",
                    summary = "来源笔记已修订或移除：${changedTitles.joinToString("、")}",
                    createdAt = ts,
                ),
            )
            return@withContext AgentActionCommitResult.ConflictDetected(changedTitles)
        }

        val created = database.withTransaction {
            val skillTitle = AgentSkill.fromId(run.skillId).title
            val blocks = mutableListOf<NoteBlock>()
            blocks.add(NoteBlock.paragraph("【$skillTitle · 本机整理笔记】${result.headline}"))
            for (obs in result.observations) {
                blocks.add(NoteBlock.paragraph("• $obs"))
            }
            if (result.alternativeView.isNotBlank()) {
                blocks.add(NoteBlock.paragraph("边界与补充视角：${result.alternativeView}"))
            }
            for (ev in result.evidence) {
                val fresh = currentNotes[ev.noteId] ?: continue
                blocks.add(
                    NoteBlock.reference(
                        noteId = fresh.id,
                        revision = fresh.revision,
                        label = ev.noteTitle,
                    ),
                )
            }
            blocks.add(NoteBlock.paragraph("来源说明：${result.engineNotice}"))

            val doc = NoteDocument(blocks)
            val draft = notesRepository.newDraft("agent-insight-${newId()}").copy(
                documentJson = doc.encode(),
                editSeq = 1L,
                sourceType = "agent_local",
            )
            val saved = notesRepository.commit(draft)
            attachProvenanceTag(saved.id, "本机整理")
            agentDao.insertAction(
                AgentActionEntity(
                    actionId = actionId,
                    runId = run.id,
                    actionType = "save_note",
                    targetNoteId = saved.id,
                    sourceRevisionsJson = revJson,
                    status = "committed",
                    summary = "已存入笔记《${LocalRetrievalEngine.displayNoteTitle(saved)}》",
                    createdAt = ts,
                ),
            )
            saved
        }
        discoveryRepository.syncChunksForNote(created)
        AgentActionCommitResult.Committed(created.id, "已存入笔记《${LocalRetrievalEngine.displayNoteTitle(created)}》")
    }

    /** Idempotent commit of extracted suggested todos into a checklist note. */
    suspend fun commitExtractTodosAsNote(
        run: AgentRunEntity,
        result: AgentRunResult,
        selectedTodos: List<String>,
        actionId: String = "agent_extract_todos:${run.id}",
        now: Instant = Instant.now(),
    ): AgentActionCommitResult = withContext(Dispatchers.IO) {
        val cleanTodos = selectedTodos.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleanTodos.isEmpty()) {
            return@withContext AgentActionCommitResult.ConflictDetected(listOf("没有可提取的待办条目"))
        }
        val existing = agentDao.actionById(actionId)
        if (existing != null && existing.status == "committed") {
            return@withContext AgentActionCommitResult.AlreadyApplied(
                existingNoteId = existing.targetNoteId,
                summary = existing.summary,
            )
        }

        val currentNotes = notesDao.allNotes().associateBy { it.id }
        val changedTitles = detectSourceConflicts(result.evidence, currentNotes)
        val revJson = encodeRevisions(result.evidence)
        val ts = isoFormatter.format(now)
        if (changedTitles.isNotEmpty()) {
            return@withContext AgentActionCommitResult.ConflictDetected(changedTitles)
        }

        val created = database.withTransaction {
            val skillTitle = AgentSkill.fromId(run.skillId).title
            val blocks = mutableListOf<NoteBlock>()
            blocks.add(NoteBlock.paragraph("【$skillTitle · 提取待办】${run.prompt}"))
            for (item in cleanTodos) {
                blocks.add(NoteBlock.checkbox(text = item, checked = false))
            }
            for (ev in result.evidence.take(3)) {
                val fresh = currentNotes[ev.noteId] ?: continue
                blocks.add(
                    NoteBlock.reference(
                        noteId = fresh.id,
                        revision = fresh.revision,
                        label = ev.noteTitle,
                    ),
                )
            }
            val doc = NoteDocument(blocks)
            val draft = notesRepository.newDraft("agent-todos-${newId()}").copy(
                documentJson = doc.encode(),
                editSeq = 1L,
                sourceType = "agent_local",
            )
            val saved = notesRepository.commit(draft)
            attachProvenanceTag(saved.id, "本机整理")
            agentDao.insertAction(
                AgentActionEntity(
                    actionId = actionId,
                    runId = run.id,
                    actionType = "extract_todos",
                    targetNoteId = saved.id,
                    sourceRevisionsJson = revJson,
                    status = "committed",
                    summary = "已生成含 ${cleanTodos.size} 项待办的新笔记",
                    createdAt = ts,
                ),
            )
            saved
        }
        discoveryRepository.syncChunksForNote(created)
        AgentActionCommitResult.Committed(created.id, "已生成含 ${cleanTodos.size} 项待办的新笔记")
    }

    suspend fun findScopedDuplicateGroups(
        scope: NoteScope,
        timeRange: AgentTimeRange,
        now: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<DuplicateNoteGroup> = withContext(Dispatchers.IO) {
        discoveryRepository.ensureIndexFresh()
        val allNotes = notesDao.allNotes()
        val allChunks = discoveryDao.allChunks()
        val tags = orgDao.tags()
        val noteTags = orgDao.noteTags()
        val notebookNotes = orgDao.notebookNotes()

        val scopedNotes = LocalAgentEngine.filterScopedNotes(
            notes = allNotes,
            tags = tags,
            noteTags = noteTags,
            notebookNotes = notebookNotes,
            scope = scope,
            timeRange = timeRange,
            now = now,
        )
        if (scopedNotes.size < 2) return@withContext emptyList()
        val scopedIds = scopedNotes.mapTo(HashSet()) { it.id }
        val scopedChunks = allChunks.filter { it.noteId in scopedIds }
        LocalTextIntelligence.findDuplicateGroups(
            notes = scopedNotes,
            allChunks = scopedChunks,
        )
    }

    suspend fun commitMergeDuplicateGroup(
        group: DuplicateNoteGroup,
        moveDuplicatesToTrash: Boolean,
        actionId: String = "merge_dup:${group.groupId}",
        now: Instant = Instant.now(),
    ): AgentActionCommitResult = withContext(Dispatchers.IO) {
        val existing = agentDao.actionById(actionId)
        if (existing != null && existing.status == "committed") {
            return@withContext AgentActionCommitResult.AlreadyApplied(
                existingNoteId = existing.targetNoteId,
                summary = existing.summary,
            )
        }

        val sourceNotes = listOf(group.primaryNote) + group.duplicateNotes
        val currentNotes = notesDao.allNotes().associateBy { it.id }
        val conflictTitles = mutableListOf<String>()
        val freshSources = ArrayList<NoteEntity>(sourceNotes.size)
        for (src in sourceNotes) {
            val fresh = currentNotes[src.id]
            if (fresh == null || fresh.deletedAt != null || fresh.revision != src.revision) {
                conflictTitles.add(LocalRetrievalEngine.displayNoteTitle(src))
            } else {
                freshSources.add(fresh)
            }
        }

        val revJson = JSONObject().apply {
            for (src in sourceNotes) {
                put(src.id, src.revision)
            }
        }.toString()
        val ts = isoFormatter.format(now)
        if (conflictTitles.isNotEmpty()) {
            val distinctConflicts = conflictTitles.distinct()
            agentDao.insertAction(
                AgentActionEntity(
                    actionId = "${actionId}:conflict:${now.toEpochMilli()}",
                    runId = group.groupId,
                    actionType = "merge_duplicates",
                    targetNoteId = null,
                    sourceRevisionsJson = revJson,
                    status = "conflict_refused",
                    summary = "来源笔记已修订或移除：${distinctConflicts.joinToString("、")}",
                    createdAt = ts,
                ),
            )
            return@withContext AgentActionCommitResult.ConflictDetected(distinctConflicts)
        }

        val (saved, summary) = database.withTransaction {
            val freshPrimary = freshSources.first()
            val freshDuplicates = freshSources.drop(1)
            val mergedParagraphs = LocalTextIntelligence.buildMergedParagraphs(freshPrimary, freshDuplicates)
            val primaryTitle = LocalRetrievalEngine.displayNoteTitle(freshPrimary)

            val blocks = mutableListOf<NoteBlock>()
            if (mergedParagraphs.isNotEmpty()) {
                for (para in mergedParagraphs) {
                    blocks.add(NoteBlock.paragraph(para))
                }
            } else {
                blocks.add(NoteBlock.paragraph("【合并整理】$primaryTitle"))
            }

            if (!moveDuplicatesToTrash) {
                for (src in freshSources) {
                    blocks.add(
                        NoteBlock.reference(
                            noteId = src.id,
                            revision = src.revision,
                            label = LocalRetrievalEngine.displayNoteTitle(src),
                        ),
                    )
                }
            }

            val doc = NoteDocument(blocks)
            val draft = notesRepository.newDraft("merge-cleanup-${newId()}").copy(
                documentJson = doc.encode(),
                editSeq = 1L,
                sourceType = "merge_cleanup",
            )
            val createdNote = notesRepository.commit(draft)

            val sourceIds = freshSources.mapTo(HashSet()) { it.id }
            val validTagIds = orgDao.tags().mapTo(HashSet()) { it.id }
            val unionTagIds = orgDao.noteTags()
                .filter { it.noteId in sourceIds && it.tagId in validTagIds }
                .mapTo(LinkedHashSet()) { it.tagId }
            for (tagId in unionTagIds) {
                orgDao.attachTag(NoteTagEntity(createdNote.id, tagId))
            }
            attachProvenanceTag(createdNote.id, "合并整理")

            val mergedTitle = LocalRetrievalEngine.displayNoteTitle(createdNote)
            val commitSummary = if (moveDuplicatesToTrash) {
                "已合并 ${freshSources.size} 条相似笔记为《$mergedTitle》，原笔记已移入回收站"
            } else {
                "已合并 ${freshSources.size} 条相似笔记为《$mergedTitle》"
            }

            agentDao.insertAction(
                AgentActionEntity(
                    actionId = actionId,
                    runId = group.groupId,
                    actionType = "merge_duplicates",
                    targetNoteId = createdNote.id,
                    sourceRevisionsJson = revJson,
                    status = "committed",
                    summary = commitSummary,
                    createdAt = ts,
                ),
            )

            if (moveDuplicatesToTrash) {
                for (src in freshSources) {
                    notesRepository.moveToTrash(src.id)
                }
            }

            createdNote to commitSummary
        }

        discoveryRepository.syncChunksForNote(saved)
        if (moveDuplicatesToTrash) {
            discoveryRepository.removeChunksForNoteIds(freshSources.map { it.id })
        }
        AgentActionCommitResult.Committed(saved.id, summary)
    }

    private suspend fun attachProvenanceTag(noteId: String, tagName: String) {
        val key = tagNameKey(tagName)
        val existingTag = orgDao.tags().firstOrNull { it.parentId.isEmpty() && it.nameKey == key }
        val tagId = existingTag?.id ?: TagEntity(newId(), "", tagName, key).also { orgDao.insertTag(it) }.id
        orgDao.attachTag(NoteTagEntity(noteId, tagId))
    }

    private fun detectSourceConflicts(
        evidence: List<AgentEvidenceItem>,
        currentNotes: Map<String, NoteEntity>,
    ): List<String> {
        val conflicts = mutableListOf<String>()
        for (item in evidence) {
            val fresh = currentNotes[item.noteId]
            if (fresh == null || fresh.deletedAt != null || fresh.revision != item.noteRevision) {
                conflicts.add(item.noteTitle)
            }
        }
        return conflicts.distinct()
    }

    private fun encodeRevisions(evidence: List<AgentEvidenceItem>): String {
        val obj = JSONObject()
        for (item in evidence) {
            obj.put(item.noteId, item.noteRevision)
        }
        return obj.toString()
    }
}
