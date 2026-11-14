package io.github.z3275630ops.floatingnotes

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.data.local.NoteScope
import io.github.z3275630ops.floatingnotes.data.local.NotesDatabase
import io.github.z3275630ops.floatingnotes.data.local.NotesRepository
import io.github.z3275630ops.floatingnotes.data.local.RecurrenceRule
import io.github.z3275630ops.floatingnotes.data.local.ReminderPolicy
import io.github.z3275630ops.floatingnotes.data.local.ReminderStatus
import io.github.z3275630ops.floatingnotes.data.local.SearchRepository
import io.github.z3275630ops.floatingnotes.data.local.TaskPreferences
import io.github.z3275630ops.floatingnotes.data.local.TaskToggleResult
import io.github.z3275630ops.floatingnotes.feature.tasks.BootTimezoneReceiver
import io.github.z3275630ops.floatingnotes.feature.tasks.ReminderCapability
import io.github.z3275630ops.floatingnotes.feature.tasks.ReminderReceiver
import io.github.z3275630ops.floatingnotes.feature.tasks.ReminderScheduler
import io.github.z3275630ops.floatingnotes.feature.tasks.TaskRecurrence
import io.github.z3275630ops.floatingnotes.feature.tasks.TasksScreen
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassTier
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintColorScheme
import io.github.z3275630ops.floatingnotes.ui.designsystem.rememberGlassBackdrop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class TaskReminderTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val appContext get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun noteBlockCheckboxJsonHonorsStrictSchemaWithoutUnknownKeys() {
        val block = NoteBlock.checkbox(
            text = "整理周报",
            checked = false,
            dueDate = "2026-11-20",
            reminderId = "rem-1",
            occurrenceId = "occ-1",
        )
        val encodedDoc = NoteDocument(listOf(block)).encode()
        val blockJson = JSONObject(encodedDoc).getJSONArray("blocks").getJSONObject(0)
        val keys = blockJson.keys().asSequence().toSet()
        assertEquals(
            setOf("id", "type", "text", "checked", "due_date", "reminder_id", "occurrence_id"),
            keys,
        )
        val decoded = NoteDocument.decode(encodedDoc).findBlock(block.id)!!
        assertEquals("2026-11-20", decoded.dueDate)
        assertEquals("rem-1", decoded.reminderId)
        assertEquals("occ-1", decoded.occurrenceId)

        val plainBlock = NoteBlock.checkbox("无排期待办")
        val plainDoc = NoteDocument.decode(NoteDocument(listOf(plainBlock)).encode()).findBlock(plainBlock.id)!!
        assertNull(plainDoc.dueDate)
        assertNull(plainDoc.reminderId)
        assertNull(plainDoc.occurrenceId)
    }

    @Test
    fun migrationsFromVersions1And2And3To4PreserveTasksAndCreateReminderSchema() {
        for (startVersion in listOf(1, 2, 3)) {
            val dbName = "t13-mig-v$startVersion-${UUID.randomUUID()}.db"
            val dbFile = appContext.getDatabasePath(dbName)
            try {
                seedDatabaseAtVersion(startVersion, dbFile) { rawDb ->
                    if (startVersion == 3) {
                        val docJson = NoteDocument(
                            listOf(
                                NoteBlock.checkbox(
                                    text = "历史待办",
                                    checked = false,
                                    dueDate = "2026-11-18",
                                    occurrenceId = "occ-legacy",
                                ),
                            ),
                        ).encode()
                        rawDb.execSQL(
                            """INSERT INTO notes (
                                id, title, document_json, search_text, author_type, source_type,
                                source_url, ai_policy, created_at, updated_at, local_revision,
                                server_revision, content_hash, archived_at, deleted_at, pinned,
                                last_draft_id, committed_seq, media_mask
                            ) VALUES (
                                'n-legacy', '历史笔记', ?, '历史待办', 'user', 'manual',
                                NULL, 'inherit', '2026-11-10T00:00:00.000Z', '2026-11-10T00:00:00.000Z', 1,
                                NULL, 'hash-legacy', NULL, NULL, 0,
                                'd-legacy', 1, 0
                            )""",
                            arrayOf(docJson),
                        )
                        rawDb.execSQL(
                            "INSERT INTO task_index (note_id, block_id, checked, due_date, occurrence_id) VALUES (?, ?, 0, '2026-11-18', 'occ-legacy')",
                            arrayOf("n-legacy", NoteDocument.decode(docJson).blocks.first().id),
                        )
                    }
                }

                val roomDb = Room.databaseBuilder(appContext, NotesDatabase::class.java, dbName)
                    .addMigrations(*NotesDatabase.migrations)
                    .build()
                try {
                    val sqlite = roomDb.openHelper.writableDatabase
                    assertTableExists(sqlite, "reminders")
                    assertTableExists(sqlite, "task_occurrences")
                    assertIndexExists(sqlite, "index_task_index_due_date")
                    assertIndexExists(sqlite, "index_task_index_checked")
                    assertIndexExists(sqlite, "index_task_index_occurrence_id")

                    if (startVersion == 3) {
                        runBlocking {
                            val items = NotesRepository(roomDb).observeTaskItems().first()
                            assertEquals(1, items.size)
                            assertEquals("历史待办", items.first().text)
                            assertEquals("2026-11-18", items.first().dueDate)
                        }
                    }
                } finally {
                    roomDb.close()
                }
            } finally {
                dbFile.delete()
                File("${dbFile.path}-wal").delete()
                File("${dbFile.path}-shm").delete()
            }
        }
    }

    @Test
    fun toggleTaskCheckedUpdatesDocumentVersionsOutboxAndTaskIndexAtomically() = runBlocking {
        withInMemoryRepository { db, repo ->
            val cb1 = NoteBlock.checkbox("买牛奶")
            val cb2 = NoteBlock.checkbox("回邮件")
            val draft = repo.newDraft("tasks-atomic").copy(
                documentJson = NoteDocument(listOf(NoteBlock.paragraph("清单"), cb1, cb2)).encode(),
                editSeq = 1,
            )
            val note = repo.commit(draft)

            val toggleResult = repo.toggleTaskChecked(note.id, cb1.id, checked = true)
            assertTrue(toggleResult is TaskToggleResult.Updated)

            val updatedNote = repo.loadNote(note.id)!!
            val updatedDoc = NoteDocument.decode(updatedNote.documentJson)
            assertTrue(updatedDoc.findBlock(cb1.id)!!.checked)
            assertFalse(updatedDoc.findBlock(cb2.id)!!.checked)

            val indexed = db.notes().tasks(note.id).associateBy { it.blockId }
            assertEquals(true, indexed[cb1.id]?.checked)
            assertEquals(false, indexed[cb2.id]?.checked)
            assertTrue(db.notes().eventCount(note.id) >= 2)
            assertVersionCountAtLeast(db.openHelper.readableDatabase, note.id, 2)

            // Editor -> Tasks sync check (Q061 bi-directional consistency)
            val editDoc = updatedDoc
                .replaceBlock(cb1.id) { it.withChecked(false) }
                .replaceBlock(cb2.id) { it.withChecked(true) }
            val nextDraft = repo.newDraft("tasks-editor", updatedNote).copy(
                documentJson = editDoc.encode(),
                editSeq = 1,
            )
            repo.commit(nextDraft)

            val items = repo.observeTaskItems().first().associateBy { it.blockId }
            assertEquals(false, items[cb1.id]?.checked)
            assertEquals(true, items[cb2.id]?.checked)

            repo.rebuildTaskIndex()
            val rebuilt = db.notes().tasks(note.id).associateBy { it.blockId }
            assertEquals(false, rebuilt[cb1.id]?.checked)
            assertEquals(true, rebuilt[cb2.id]?.checked)
        }
    }

    @Test
    fun incompleteTasksInArchivedNotesRemainVisibleWithArchivedFlag() = runBlocking {
        withInMemoryRepository { _, repo ->
            val cb = NoteBlock.checkbox("归档里的未完成待办")
            val note = repo.commit(
                repo.newDraft("archived-task").copy(
                    documentJson = NoteDocument(listOf(cb)).encode(),
                    editSeq = 1,
                ),
            )
            repo.setArchived(note.id, archived = true)

            val items = repo.observeTaskItems().first()
            assertEquals(1, items.size)
            assertEquals(cb.text, items.first().text)
            assertTrue(items.first().noteArchived)
            assertFalse(items.first().checked)
        }
    }

    @Test
    fun recurringTaskCompletionRecordsOccurrenceAndAdvancesWithoutCheckingCheckbox() = runBlocking {
        withInMemoryRepository { db, repo ->
            val shanghai = ZoneId.of("Asia/Shanghai")
            val cb = NoteBlock.checkbox("每日复盘")
            val note = repo.commit(
                repo.newDraft("recurring-task").copy(
                    documentJson = NoteDocument(listOf(cb)).encode(),
                    editSeq = 1,
                ),
            )

            val schedule = repo.setTaskSchedule(
                noteId = note.id,
                blockId = cb.id,
                dueDate = "2026-11-14",
                reminderTime = "09:00",
                zoneId = shanghai,
                recurrence = RecurrenceRule.DAILY,
            )
            val initialOccId = NoteDocument.decode(schedule.note.documentJson).findBlock(cb.id)!!.occurrenceId!!

            val toggled = repo.toggleTaskChecked(note.id, cb.id, checked = true, zoneId = shanghai)
            assertTrue(toggled is TaskToggleResult.Updated)
            val updated = toggled as TaskToggleResult.Updated
            assertEquals("2026-11-15", updated.advancedToDate)
            assertNotNull(updated.recordedOccurrence)

            val liveBlock = NoteDocument.decode(repo.loadNote(note.id)!!.documentJson).findBlock(cb.id)!!
            assertFalse(liveBlock.checked)
            assertEquals("2026-11-15", liveBlock.dueDate)
            assertNotEquals(initialOccId, liveBlock.occurrenceId)

            val reminder = db.taskReminders().reminderForBlock(note.id, cb.id)!!
            assertEquals("2026-11-15", reminder.localDate)
            assertEquals(ReminderStatus.ACTIVE.name, reminder.status)

            val occurrences = repo.observeCompletedOccurrences().first()
            assertEquals(1, occurrences.size)
            assertEquals("2026-11-14", occurrences.first().scheduledDate)
            assertEquals("DAILY", occurrences.first().recurrence)

            // Stale notification from previous occurrence must not complete the newly scheduled instance.
            repo.completeFromNotification(
                noteId = note.id,
                blockId = cb.id,
                expectedOccurrenceId = initialOccId,
                zoneId = shanghai,
            )
            assertEquals(1, repo.observeCompletedOccurrences().first().size)
            assertEquals("2026-11-15", NoteDocument.decode(repo.loadNote(note.id)!!.documentJson).findBlock(cb.id)!!.dueDate)
        }
    }

    @Test
    fun reminderReceiverSnoozeAndCompleteActionsUpdateDatabaseState() = runBlocking {
        withInMemoryRepository { db, repo ->
            val shanghai = ZoneId.of("Asia/Shanghai")
            val cb = NoteBlock.checkbox("交电费")
            val note = repo.commit(
                repo.newDraft("receiver-task").copy(
                    documentJson = NoteDocument(listOf(cb)).encode(),
                    editSeq = 1,
                ),
            )
            val schedule = repo.setTaskSchedule(
                noteId = note.id,
                blockId = cb.id,
                dueDate = "2026-11-20",
                reminderTime = "10:00",
                zoneId = shanghai,
            )
            val reminder = schedule.reminder!!

            val snoozeAt = Instant.parse("2026-11-20T02:00:00Z")
            ReminderReceiver.handleIntent(
                appContext = appContext,
                intent = Intent(ReminderScheduler.ACTION_SNOOZE_TASK)
                    .putExtra(ReminderScheduler.EXTRA_REMINDER_ID, reminder.id)
                    .putExtra(ReminderScheduler.EXTRA_OCCURRENCE_ID, reminder.occurrenceId),
                repository = repo,
                zoneId = shanghai,
                now = snoozeAt,
            )
            val snoozed = db.taskReminders().reminder(reminder.id)!!
            assertEquals(snoozeAt.plusSeconds(15 * 60L).toEpochMilli(), snoozed.snoozedUntilMs)
            assertEquals(ReminderStatus.ACTIVE.name, snoozed.status)

            ReminderReceiver.handleIntent(
                appContext = appContext,
                intent = Intent(ReminderScheduler.ACTION_COMPLETE_TASK)
                    .putExtra(ReminderScheduler.EXTRA_NOTE_ID, note.id)
                    .putExtra(ReminderScheduler.EXTRA_BLOCK_ID, cb.id)
                    .putExtra(ReminderScheduler.EXTRA_REMINDER_ID, reminder.id)
                    .putExtra(ReminderScheduler.EXTRA_OCCURRENCE_ID, reminder.occurrenceId),
                repository = repo,
                zoneId = shanghai,
                now = snoozeAt.plusSeconds(60L),
            )
            val completed = db.taskReminders().reminder(reminder.id)!!
            assertEquals(ReminderStatus.COMPLETED.name, completed.status)
            assertNull(completed.snoozedUntilMs)
            assertTrue(NoteDocument.decode(repo.loadNote(note.id)!!.documentJson).findBlock(cb.id)!!.checked)
        }
    }

    @Test
    fun timeReconcileAppliesPoliciesAndDeduplicatesMissedSummary() = runBlocking {
        withInMemoryRepository { db, repo ->
            val tokyo = ZoneId.of("Asia/Tokyo")
            val losAngeles = ZoneId.of("America/Los_Angeles")

            val overdue1 = NoteBlock.checkbox("逾期事项一")
            val overdue2 = NoteBlock.checkbox("逾期事项二")
            val followBlock = NoteBlock.checkbox("跟随当地时区")
            val originBlock = NoteBlock.checkbox("固定东京时区")
            val note = repo.commit(
                repo.newDraft("reconcile-task").copy(
                    documentJson = NoteDocument(listOf(overdue1, overdue2, followBlock, originBlock)).encode(),
                    editSeq = 1,
                ),
            )

            repo.setTaskSchedule(note.id, overdue1.id, "2026-11-10", "08:00", tokyo)
            repo.setTaskSchedule(note.id, overdue2.id, "2026-11-11", "09:00", tokyo)
            val followRes = repo.setTaskSchedule(
                note.id, followBlock.id, "2026-11-20", "09:00", tokyo, ReminderPolicy.FOLLOW_LOCAL,
            )
            val originRes = repo.setTaskSchedule(
                note.id, originBlock.id, "2026-11-20", "09:00", tokyo, ReminderPolicy.KEEP_ORIGIN_ZONE,
            )

            val reconcileNow = Instant.parse("2026-11-14T12:00:00Z")
            val result = repo.reconcileTimeChange(currentZone = losAngeles, now = reconcileNow)
            assertEquals(2, result.missedCount)
            assertEquals(2, result.activeReminders.size)

            val updatedFollow = db.taskReminders().reminder(followRes.reminder!!.id)!!
            val updatedOrigin = db.taskReminders().reminder(originRes.reminder!!.id)!!
            assertEquals(losAngeles.id, updatedFollow.zoneId)
            assertEquals(tokyo.id, updatedOrigin.zoneId)
            assertNotEquals(updatedFollow.triggerInstantMs, updatedOrigin.triggerInstantMs)

            val firstMissed = repo.collectMissedAndMarkSummarized(currentZone = losAngeles, now = reconcileNow)
            assertEquals(2, firstMissed.size)
            val batch = TaskRecurrence.batchMissedReminders(firstMissed, losAngeles)!!
            assertEquals("错过了 2 条待办提醒", batch.headline)

            val secondMissed = repo.collectMissedAndMarkSummarized(currentZone = losAngeles, now = reconcileNow)
            assertTrue(secondMissed.isEmpty())
        }
    }

    @Test
    fun permissionDeniedShowsGuidanceWithoutBlockingScheduleSave() {
        val dbName = "t13-ui-${UUID.randomUUID()}.db"
        val prefsName = "t13-prefs-${UUID.randomUUID()}"
        val db = Room.databaseBuilder(appContext, NotesDatabase::class.java, dbName)
            .addMigrations(*NotesDatabase.migrations)
            .build()
        val repo = NotesRepository(db)
        val prefs = TaskPreferences(appContext, prefsName)
        val checkbox = NoteBlock.checkbox("提交季度材料")
        val note = runBlocking {
            repo.commit(
                repo.newDraft("ui-task").copy(
                    documentJson = NoteDocument(listOf(checkbox)).encode(),
                    editSeq = 1,
                ),
            )
        }
        val futureDate = LocalDate.now().plusDays(6).toString()

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        MaterialTheme(colorScheme = mintColorScheme(dark = false)) {
                            val backdrop = rememberGlassBackdrop()
                            TasksScreen(
                                repository = repo,
                                preferences = prefs,
                                backdrop = backdrop,
                                tier = GlassTier.Reduced,
                                dark = false,
                                onOpenNote = { _, _ -> },
                                capabilityProvider = {
                                    ReminderCapability(
                                        notificationsEnabled = false,
                                        exactAlarmAllowed = false,
                                    )
                                },
                            )
                        }
                    }
                }

                compose.waitUntil(5_000) {
                    compose.onAllNodesWithTag("tasks_notification_banner").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("tasks_notification_banner").assertIsDisplayed()

                compose.onNodeWithTag("task_schedule_${note.id}_${checkbox.id}").performClick()
                compose.onNodeWithTag("task_schedule_modal").assertIsDisplayed()
                compose.onNodeWithTag("schedule_due_input").performScrollTo().performTextReplacement(futureDate)
                compose.onNodeWithTag("schedule_save").performClick()

                compose.waitUntil(5_000) {
                    runBlocking {
                        NoteDocument.decode(repo.loadNote(note.id)!!.documentJson)
                            .findBlock(checkbox.id)?.dueDate == futureDate
                    }
                }

                compose.onNodeWithTag("tasks_filter_upcoming").performClick()
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithTag("task_row_${note.id}_${checkbox.id}")
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("task_row_${note.id}_${checkbox.id}").assertIsDisplayed()

                compose.onNodeWithTag("tasks_startup_toggle").performClick()
                compose.waitUntil(5_000) { prefs.todoStartupEnabled }
                assertTrue(prefs.todoStartupEnabled)
            }
        } finally {
            db.close()
            appContext.deleteDatabase(dbName)
            appContext.deleteSharedPreferences(prefsName)
        }
    }

    @Test
    fun recurringTaskCompletionViaCommitAdvancesAtomicallyWithoutCorruptingReminder() = runBlocking {
        withInMemoryRepository { db, repo ->
            val shanghai = ZoneId.of("Asia/Shanghai")
            val cb = NoteBlock.checkbox("每日晨读")
            val note = repo.commit(
                repo.newDraft("commit-recurring").copy(
                    documentJson = NoteDocument(listOf(cb)).encode(),
                    editSeq = 1,
                ),
            )
            val scheduled = repo.setTaskSchedule(
                noteId = note.id,
                blockId = cb.id,
                dueDate = "2026-11-14",
                reminderTime = "08:30",
                zoneId = shanghai,
                recurrence = RecurrenceRule.DAILY,
            )
            val firstOccId = NoteDocument.decode(scheduled.note.documentJson).findBlock(cb.id)!!.occurrenceId!!

            // Direct document commit checking the recurring box must log 1 occurrence and advance cleanly.
            val editedDoc = NoteDocument.decode(scheduled.note.documentJson)
                .replaceBlock(cb.id) { it.withChecked(true) }
            val committed = repo.commit(
                repo.newDraft("commit-recurring-edit", scheduled.note).copy(
                    documentJson = editedDoc.encode(),
                    editSeq = 1,
                ),
            )
            val liveBlock = NoteDocument.decode(committed.documentJson).findBlock(cb.id)!!
            assertFalse(liveBlock.checked)
            assertEquals("2026-11-15", liveBlock.dueDate)
            assertNotEquals(firstOccId, liveBlock.occurrenceId)

            val reminder = db.taskReminders().reminderForBlock(note.id, cb.id)!!
            assertEquals(ReminderStatus.ACTIVE.name, reminder.status)
            assertEquals("2026-11-15", reminder.localDate)

            val occurrences = repo.observeCompletedOccurrences().first()
            assertEquals(1, occurrences.size)
            assertEquals("2026-11-14", occurrences.first().scheduledDate)

            // Also setting a recurring schedule on an already-checked new block must advance instead of dead-ending COMPLETED.
            val preChecked = NoteBlock.checkbox("新笔记已勾选周期任务", checked = true)
            val secondNote = repo.commit(
                repo.newDraft("commit-prechecked-recurring").copy(
                    documentJson = NoteDocument(listOf(preChecked)).encode(),
                    editSeq = 1,
                ),
            )
            val preScheduled = repo.setTaskSchedule(
                noteId = secondNote.id,
                blockId = preChecked.id,
                dueDate = "2026-11-14",
                reminderTime = "09:00",
                zoneId = shanghai,
                recurrence = RecurrenceRule.WEEKLY,
            )
            val preLiveBlock = NoteDocument.decode(preScheduled.note.documentJson).findBlock(preChecked.id)!!
            assertFalse(preLiveBlock.checked)
            assertEquals("2026-11-21", preLiveBlock.dueDate)
            assertEquals(ReminderStatus.ACTIVE.name, preScheduled.reminder!!.status)
            assertEquals("2026-11-21", preScheduled.reminder!!.localDate)
        }
    }

    @Test
    fun reconcileTimeChangePreservesAlreadyNotifiedMissedSummarized() = runBlocking {
        withInMemoryRepository { db, repo ->
            val shanghai = ZoneId.of("Asia/Shanghai")
            val cb = NoteBlock.checkbox("已按时弹窗待办")
            val note = repo.commit(
                repo.newDraft("notified-ontime").copy(
                    documentJson = NoteDocument(listOf(cb)).encode(),
                    editSeq = 1,
                ),
            )
            val scheduled = repo.setTaskSchedule(
                noteId = note.id,
                blockId = cb.id,
                dueDate = "2026-11-14",
                reminderTime = "09:00",
                zoneId = shanghai,
            )
            val remId = scheduled.reminder!!.id
            val notifyTime = Instant.parse("2026-11-14T01:00:00Z")
            repo.markReminderNotifiedOnTime(remId, now = notifyTime)
            assertTrue(db.taskReminders().reminder(remId)!!.missedSummarized)

            val later = Instant.parse("2026-11-14T03:00:00Z")
            val reconciled = repo.reconcileTimeChange(currentZone = shanghai, now = later)
            assertEquals(0, reconciled.missedCount)
            val updated = db.taskReminders().reminder(remId)!!
            assertEquals(ReminderStatus.MISSED.name, updated.status)
            assertTrue(updated.missedSummarized)

            assertTrue(repo.collectMissedAndMarkSummarized(currentZone = shanghai, now = later).isEmpty())
        }
    }

    @Test
    fun bootTimezoneReceiverRetainsMissedRemindersWhenNotificationsDeniedUntilGranted() = runBlocking {
        withInMemoryRepository { db, repo ->
            val shanghai = ZoneId.of("Asia/Shanghai")
            val cb = NoteBlock.checkbox("权限关闭期间错过的提醒")
            val note = repo.commit(
                repo.newDraft("missed-denied").copy(
                    documentJson = NoteDocument(listOf(cb)).encode(),
                    editSeq = 1,
                ),
            )
            val scheduled = repo.setTaskSchedule(
                noteId = note.id,
                blockId = cb.id,
                dueDate = "2026-11-12",
                reminderTime = "09:00",
                zoneId = shanghai,
            )
            val remId = scheduled.reminder!!.id
            val now = Instant.parse("2026-11-14T03:00:00Z")

            var postedWhenDenied = 0
            BootTimezoneReceiver.reconcileAndNotify(
                appContext = appContext,
                repository = repo,
                currentZone = shanghai,
                now = now,
                canPostNotifications = { false },
                postSummary = { _, _ ->
                    postedWhenDenied++
                    true
                },
            )
            assertEquals(0, postedWhenDenied)
            assertFalse(db.taskReminders().reminder(remId)!!.missedSummarized)

            var postedBatchCount = 0
            BootTimezoneReceiver.reconcileAndNotify(
                appContext = appContext,
                repository = repo,
                currentZone = shanghai,
                now = now,
                canPostNotifications = { true },
                postSummary = { _, batch ->
                    postedBatchCount = batch.count
                    true
                },
            )
            assertEquals(1, postedBatchCount)
            assertTrue(db.taskReminders().reminder(remId)!!.missedSummarized)

            var postedSecondTime = 0
            BootTimezoneReceiver.reconcileAndNotify(
                appContext = appContext,
                repository = repo,
                currentZone = shanghai,
                now = now,
                canPostNotifications = { true },
                postSummary = { _, _ ->
                    postedSecondTime++
                    true
                },
            )
            assertEquals(0, postedSecondTime)
        }
    }

    @Test
    fun uncheckingTaskClearsNonRecurringOccurrenceAndReactivatesFutureReminder() = runBlocking {
        withInMemoryRepository { db, repo ->
            val shanghai = ZoneId.of("Asia/Shanghai")
            val cb = NoteBlock.checkbox("可取消完成的未来提醒")
            val note = repo.commit(
                repo.newDraft("uncheck-task").copy(
                    documentJson = NoteDocument(listOf(cb)).encode(),
                    editSeq = 1,
                ),
            )
            val schedule = repo.setTaskSchedule(
                noteId = note.id,
                blockId = cb.id,
                dueDate = "2099-11-20",
                reminderTime = "09:00",
                zoneId = shanghai,
            )
            val remId = schedule.reminder!!.id
            val now = Instant.parse("2026-11-14T03:00:00Z")

            repo.toggleTaskChecked(note.id, cb.id, checked = true, zoneId = shanghai, now = now)
            assertEquals(ReminderStatus.COMPLETED.name, db.taskReminders().reminder(remId)!!.status)
            assertEquals(1, repo.observeCompletedOccurrences().first().size)

            val unchecked = repo.toggleTaskChecked(note.id, cb.id, checked = false, zoneId = shanghai, now = now)
            assertTrue(unchecked is TaskToggleResult.Updated)
            val updated = unchecked as TaskToggleResult.Updated
            assertEquals(ReminderStatus.ACTIVE.name, updated.reminder!!.status)
            assertTrue(repo.observeCompletedOccurrences().first().isEmpty())

            // Re-check then uncheck via direct document commit() must also clear stale non-recurring occurrence.
            repo.toggleTaskChecked(note.id, cb.id, checked = true, zoneId = shanghai, now = now)
            assertEquals(1, repo.observeCompletedOccurrences().first().size)
            val checkedNote = repo.loadNote(note.id)!!
            val uncheckedDoc = NoteDocument.decode(checkedNote.documentJson)
                .replaceBlock(cb.id) { it.withChecked(false) }
            repo.commit(
                repo.newDraft("uncheck-via-commit", checkedNote).copy(
                    documentJson = uncheckedDoc.encode(),
                    editSeq = 1,
                ),
            )
            assertTrue(repo.observeCompletedOccurrences().first().isEmpty())
            assertEquals(ReminderStatus.ACTIVE.name, db.taskReminders().reminder(remId)!!.status)
        }
    }

    @Test
    fun savedSearchRenameUpdatesInPlacePreservingId() = runBlocking {
        withInMemoryRepository { db, _ ->
            val searchRepo = SearchRepository(db)
            searchRepo.save("旧智能文件夹", "周报", NoteScope())
            val original = searchRepo.saved.first().single()

            searchRepo.rename(original.id, "新智能文件夹")
            val renamedList = searchRepo.saved.first()
            assertEquals(1, renamedList.size)
            assertEquals(original.id, renamedList.single().id)
            assertEquals("新智能文件夹", renamedList.single().name)
        }
    }

    private suspend fun withInMemoryRepository(
        block: suspend (NotesDatabase, NotesRepository) -> Unit,
    ) {
        val db = Room.inMemoryDatabaseBuilder(appContext, NotesDatabase::class.java).build()
        try {
            block(db, NotesRepository(db))
        } finally {
            db.close()
        }
    }

    private fun seedDatabaseAtVersion(
        version: Int,
        dbFile: File,
        afterCreate: (SQLiteDatabase) -> Unit,
    ) {
        dbFile.parentFile?.mkdirs()
        dbFile.delete()
        val schemaText = InstrumentationRegistry.getInstrumentation().context.assets
            .open("io.github.z3275630ops.floatingnotes.data.local.NotesDatabase/$version.json")
            .bufferedReader()
            .use { it.readText() }
        val databaseJson = JSONObject(schemaText).getJSONObject("database")
        val entities = databaseJson.getJSONArray("entities")
        val oldDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                oldDb.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    oldDb.execSQL(
                        indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table),
                    )
                }
            }
            afterCreate(oldDb)
            oldDb.version = version
        } finally {
            oldDb.close()
        }
    }

    private fun assertTableExists(db: SupportSQLiteDatabase, tableName: String) {
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(tableName)).use {
            assertTrue("Missing table $tableName", it.moveToFirst())
        }
    }

    private fun assertIndexExists(db: SupportSQLiteDatabase, indexName: String) {
        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = ?", arrayOf(indexName)).use {
            assertTrue("Missing index $indexName", it.moveToFirst())
        }
    }

    private fun assertVersionCountAtLeast(db: SupportSQLiteDatabase, noteId: String, expectedMin: Int) {
        db.query("SELECT COUNT(*) FROM note_versions WHERE note_id = ?", arrayOf(noteId)).use {
            assertTrue(it.moveToFirst())
            assertTrue(it.getInt(0) >= expectedMin)
        }
    }
}
