package io.github.z3275630ops.floatingnotes.data.discovery

import io.github.z3275630ops.floatingnotes.data.backup.BackupCodec
import io.github.z3275630ops.floatingnotes.data.backup.BackupConstants
import io.github.z3275630ops.floatingnotes.data.backup.BackupNoteRecord
import io.github.z3275630ops.floatingnotes.data.backup.BackupSnapshot
import io.github.z3275630ops.floatingnotes.data.backup.BackupValidationException
import io.github.z3275630ops.floatingnotes.data.backup.MiniJson
import io.github.z3275630ops.floatingnotes.data.backup.VersionDiff
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteScope
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
import io.github.z3275630ops.floatingnotes.data.local.tagNameKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class P0HardeningUnitTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun fuzzyMatcherHandlesCaseExpandingUnicodeWithoutOutOfBoundsCrash() {
        // 'İ' (U+0130) expands to two chars ("i\u0307") under lowercase; repeat many times before English/CJK tail.
        val prefix = "\u0130".repeat(32)
        val asciiTarget = "$prefix architecture roadmap review"
        val cjkTarget = "$prefix 项目的读书笔计复盘"

        val asciiRes = LocalFuzzyMatcher.matchFuzzyText("archtecture", asciiTarget)
        assertTrue("Expected fuzzy ASCII match without IndexOutOfBounds", asciiRes.matched)
        assertEquals("architecture", asciiRes.matchedSnippet)

        val cjkRes = LocalFuzzyMatcher.matchFuzzyText("读书笔记", cjkTarget)
        assertTrue("Expected fuzzy CJK match without IndexOutOfBounds", cjkRes.matched)
        assertTrue((cjkRes.matchedSnippet ?: "").isNotEmpty())
    }

    @Test
    fun isScopeEligibleMatchAllExpandsEachTagSubtreeIndependently() {
        val tParentA = TagEntity("pa", "", "工作", tagNameKey("工作"))
        val tChildA = TagEntity("ca", "pa", "项目A", tagNameKey("项目A"))
        val tParentB = TagEntity("pb", "", "领域", tagNameKey("领域"))
        val tChildB = TagEntity("cb", "pb", "隐私", tagNameKey("隐私"))
        val tags = listOf(tParentA, tChildA, tParentB, tChildB)

        val scope = NoteScope(
            includeTags = setOf("pa", "pb"),
            descendants = true,
            matchAll = true,
        )
        val expandedInclude = LocalRetrievalEngine.expandTagSelection(scope.includeTags, tags, scope.descendants)
        val expandedGroups = LocalRetrievalEngine.expandTagGroups(scope.includeTags, tags, scope.descendants)
        val dummyDoc = MiniJson.encode(linkedMapOf("document_schema_version" to 1, "blocks" to emptyList<Any>()))
        val note = NoteEntity(
            id = "n1",
            title = "双树命中",
            documentJson = dummyDoc,
            searchText = "双树命中",
            authorType = "user",
            sourceType = "manual",
            sourceUrl = null,
            createdAt = "2026-11-20T08:00:00Z",
            updatedAt = "2026-11-20T08:00:00Z",
            revision = 1L,
            contentHash = "h1",
            lastDraftId = "d1",
            committedSeq = 1L,
        )

        assertTrue(
            LocalRetrievalEngine.isScopeEligible(
                note = note,
                scope = scope,
                noteTagIds = setOf("ca", "cb"),
                noteNotebookIds = emptySet(),
                expandedIncludeTags = expandedInclude,
                expandedExcludeTags = emptySet(),
                excludedNoteIds = emptySet(),
                expandedIncludeTagGroups = expandedGroups,
            ),
        )
        assertFalse(
            LocalRetrievalEngine.isScopeEligible(
                note = note,
                scope = scope,
                noteTagIds = setOf("ca"),
                noteNotebookIds = emptySet(),
                expandedIncludeTags = expandedInclude,
                expandedExcludeTags = emptySet(),
                excludedNoteIds = emptySet(),
                expandedIncludeTagGroups = expandedGroups,
            ),
        )
    }

    @Test
    fun versionDiffRewriteDocumentIdsRemapsCheckboxReminderAndOccurrenceIds() {
        val rawDoc = MiniJson.encode(
            linkedMapOf(
                "document_schema_version" to 1,
                "blocks" to listOf(
                    linkedMapOf(
                        "id" to "cb-1",
                        "type" to "checkbox",
                        "text" to "每周复盘",
                        "checked" to false,
                        "due_date" to "2026-11-25",
                        "reminder_id" to "old-rem-1",
                        "occurrence_id" to "old-occ-1",
                        "custom_field" to "keep-me",
                    ),
                ),
            ),
        )

        val (rewritten, changed) = VersionDiff.rewriteDocumentIds(
            documentJson = rawDoc,
            noteIdMap = emptyMap(),
            attachmentIdMap = emptyMap(),
            reminderIdMap = mapOf("old-rem-1" to "new-rem-99"),
            occurrenceIdMap = mapOf("old-occ-1" to "new-occ-88"),
        )

        assertTrue(changed)
        val blocks = MiniJson.parseObject(rewritten)["blocks"] as List<*>
        @Suppress("UNCHECKED_CAST")
        val cb = blocks.single() as Map<String, Any?>
        assertEquals("new-rem-99", cb["reminder_id"])
        assertEquals("new-occ-88", cb["occurrence_id"])
        assertEquals("keep-me", cb["custom_field"])
    }

    @Test
    fun writeBackupZipRejectsArchivesExceedingMaxArchiveEntries() {
        val docJson = MiniJson.encode(linkedMapOf("document_schema_version" to 1, "blocks" to emptyList<Any>()))
        val overLimitCount = BackupConstants.MAX_ARCHIVE_ENTRIES + 5
        val notes = (0 until overLimitCount).map { idx ->
            BackupNoteRecord(
                id = "n-$idx",
                title = "标题",
                documentJson = docJson,
                searchText = "正文",
                authorType = "user",
                sourceType = "manual",
                sourceUrl = null,
                aiPolicy = "inherit",
                createdAt = "2026-11-20T08:00:00.000Z",
                updatedAt = "2026-11-20T08:00:00.000Z",
                revision = 1L,
                serverRevision = null,
                contentHash = "h-$idx",
                archivedAt = null,
                deletedAt = null,
                pinned = false,
                lastDraftId = "d-$idx",
                committedSeq = 1L,
                mediaMask = 0,
            )
        }
        val snapshot = BackupSnapshot(
            exportedAt = "2026-11-23T08:00:00.000Z",
            appVersion = "0.2.6-bugfix-hardening.1",
            dbSchemaVersion = 7,
            notes = notes,
            versions = emptyList(),
            drafts = emptyList(),
            tags = emptyList(),
            notebooks = emptyList(),
            noteTags = emptyList(),
            notebookNotes = emptyList(),
            noteLinks = emptyList(),
            savedSearches = emptyList(),
            reminders = emptyList(),
            taskOccurrences = emptyList(),
            attachments = emptyList(),
        )

        val outZip = File(temp.root, "over-limit.zip")
        try {
            BackupCodec.writeBackupZip(
                snapshot,
                outZip,
                attachmentFileProvider = { null },
                shardingEnabled = false,
            )
            fail("Expected BackupValidationException when exceeding MAX_ARCHIVE_ENTRIES")
        } catch (_: BackupValidationException) {
            // Expected fast rejection before producing an un-restorable ZIP.
        }
    }
}
