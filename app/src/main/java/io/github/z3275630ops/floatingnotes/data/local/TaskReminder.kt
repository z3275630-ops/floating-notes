package io.github.z3275630ops.floatingnotes.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

internal enum class ReminderPolicy(val label: String) {
    FOLLOW_LOCAL("随当地时间"),
    KEEP_ORIGIN_ZONE("固定原时区"),
    ;

    companion object {
        fun fromStored(raw: String?): ReminderPolicy =
            entries.firstOrNull { it.name == raw } ?: FOLLOW_LOCAL
    }
}

internal enum class ReminderStatus {
    ACTIVE,
    COMPLETED,
    CANCELLED,
    MISSED,
    ;

    companion object {
        fun fromStored(raw: String?): ReminderStatus =
            entries.firstOrNull { it.name == raw } ?: ACTIVE
    }
}

internal enum class RecurrenceRule(val label: String) {
    NONE("不重复"),
    DAILY("每天"),
    WEEKLY("每周"),
    ;

    companion object {
        fun fromStored(raw: String?): RecurrenceRule =
            entries.firstOrNull { it.name == raw } ?: NONE
    }
}

@Entity(
    tableName = "reminders",
    indices = [
        Index(value = ["note_id", "block_id"], unique = true),
        Index(value = ["status", "trigger_instant_ms"]),
        Index(value = ["occurrence_id"]),
    ],
)
internal data class ReminderEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "note_id") val noteId: String,
    @ColumnInfo(name = "block_id") val blockId: String,
    @ColumnInfo(name = "occurrence_id") val occurrenceId: String?,
    @ColumnInfo(name = "zone_id") val zoneId: String,
    @ColumnInfo(name = "local_date") val localDate: String,
    @ColumnInfo(name = "local_time") val localTime: String,
    @ColumnInfo(name = "trigger_instant_ms") val triggerInstantMs: Long?,
    val policy: String = ReminderPolicy.FOLLOW_LOCAL.name,
    val status: String = ReminderStatus.ACTIVE.name,
    val recurrence: String = RecurrenceRule.NONE.name,
    @ColumnInfo(name = "snoozed_until_ms") val snoozedUntilMs: Long? = null,
    @ColumnInfo(name = "missed_summarized") val missedSummarized: Boolean = false,
    val revision: Long = 1L,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
) {
    val policyEnum: ReminderPolicy get() = ReminderPolicy.fromStored(policy)
    val statusEnum: ReminderStatus get() = ReminderStatus.fromStored(status)
    val recurrenceEnum: RecurrenceRule get() = RecurrenceRule.fromStored(recurrence)
}

@Entity(
    tableName = "task_occurrences",
    indices = [
        Index(value = ["note_id", "block_id"]),
        Index(value = ["completed_at"]),
    ],
)
internal data class TaskOccurrenceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "note_id") val noteId: String,
    @ColumnInfo(name = "block_id") val blockId: String,
    @ColumnInfo(name = "reminder_id") val reminderId: String?,
    @ColumnInfo(name = "scheduled_date") val scheduledDate: String?,
    @ColumnInfo(name = "title_snapshot") val titleSnapshot: String,
    val recurrence: String,
    @ColumnInfo(name = "completed_at") val completedAt: String,
)

internal data class TaskRowTuple(
    val noteId: String,
    val blockId: String,
    val indexChecked: Boolean,
    val indexDueDate: String?,
    val indexOccurrenceId: String?,
    val noteTitle: String?,
    val noteDocumentJson: String,
    val noteSearchText: String,
    val noteArchivedAt: String?,
    val noteUpdatedAt: String,
    val reminderId: String?,
    val reminderZoneId: String?,
    val reminderLocalDate: String?,
    val reminderLocalTime: String?,
    val triggerInstantMs: Long?,
    val reminderPolicy: String?,
    val reminderStatus: String?,
    val reminderRecurrence: String?,
    val snoozedUntilMs: Long?,
    val missedSummarized: Boolean?,
)

internal data class TaskItemProjection(
    val noteId: String,
    val blockId: String,
    val text: String,
    val checked: Boolean,
    val dueDate: String?,
    val occurrenceId: String?,
    val noteTitle: String?,
    val notePreview: String,
    val noteArchived: Boolean,
    val noteUpdatedAt: String,
    val reminderId: String?,
    val reminderZoneId: String?,
    val reminderLocalDate: String?,
    val reminderLocalTime: String?,
    val triggerInstantMs: Long?,
    val policy: ReminderPolicy,
    val reminderStatus: ReminderStatus?,
    val recurrence: RecurrenceRule,
    val snoozedUntilMs: Long?,
    val missedSummarized: Boolean,
)

internal data class MissedReminderSummaryItem(
    val reminderId: String,
    val noteId: String,
    val blockId: String,
    val taskText: String,
    val noteLabel: String,
    val scheduledInstantMs: Long?,
)

internal sealed interface TaskToggleResult {
    data object NotFound : TaskToggleResult
    data object BlockNotFound : TaskToggleResult
    data object StaleReminder : TaskToggleResult
    data class Updated(
        val note: NoteEntity,
        val reminder: ReminderEntity?,
        val advancedToDate: String? = null,
        val cancelledReminderId: String? = null,
        val recordedOccurrence: TaskOccurrenceEntity? = null,
    ) : TaskToggleResult
}

internal data class TaskScheduleResult(
    val note: NoteEntity,
    val reminder: ReminderEntity?,
    val cancelledReminderId: String?,
)

internal data class TimeReconcileResult(
    val activeReminders: List<ReminderEntity>,
    val cancelledReminderIds: List<String>,
    val missedCount: Int,
)

internal data class ReminderDeliveryPayload(
    val reminder: ReminderEntity,
    val taskText: String,
    val noteLabel: String,
)

@Dao
internal interface TaskReminderDao {
    @Query(
        """
        SELECT
            t.note_id AS noteId,
            t.block_id AS blockId,
            t.checked AS indexChecked,
            t.due_date AS indexDueDate,
            t.occurrence_id AS indexOccurrenceId,
            n.title AS noteTitle,
            n.document_json AS noteDocumentJson,
            n.search_text AS noteSearchText,
            n.archived_at AS noteArchivedAt,
            n.updated_at AS noteUpdatedAt,
            r.id AS reminderId,
            r.zone_id AS reminderZoneId,
            r.local_date AS reminderLocalDate,
            r.local_time AS reminderLocalTime,
            r.trigger_instant_ms AS triggerInstantMs,
            r.policy AS reminderPolicy,
            r.status AS reminderStatus,
            r.recurrence AS reminderRecurrence,
            r.snoozed_until_ms AS snoozedUntilMs,
            r.missed_summarized AS missedSummarized
        FROM task_index t
        INNER JOIN notes n ON n.id = t.note_id AND n.deleted_at IS NULL
        LEFT JOIN reminders r ON r.note_id = t.note_id AND r.block_id = t.block_id
        ORDER BY t.checked ASC, COALESCE(t.due_date, '9999-12-31') ASC, n.updated_at DESC, t.block_id ASC
        """,
    )
    fun observeTaskRows(): Flow<List<TaskRowTuple>>

    @Query(
        """
        SELECT o.* FROM task_occurrences o
        INNER JOIN notes n ON n.id = o.note_id AND n.deleted_at IS NULL
        ORDER BY o.completed_at DESC
        LIMIT 200
        """,
    )
    fun observeCompletedOccurrences(): Flow<List<TaskOccurrenceEntity>>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun reminder(id: String): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE note_id = :noteId AND block_id = :blockId")
    suspend fun reminderForBlock(noteId: String, blockId: String): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE note_id = :noteId")
    suspend fun remindersForNote(noteId: String): List<ReminderEntity>

    @Query(
        """
        SELECT r.* FROM reminders r
        INNER JOIN notes n ON n.id = r.note_id AND n.deleted_at IS NULL
        WHERE r.status = 'ACTIVE' AND r.trigger_instant_ms IS NOT NULL
        """,
    )
    suspend fun activeReminders(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE status IN ('ACTIVE', 'MISSED')")
    suspend fun activeOrMissedReminders(): List<ReminderEntity>

    @Query("SELECT * FROM reminders")
    suspend fun allReminders(): List<ReminderEntity>

    @Query("SELECT * FROM task_occurrences")
    suspend fun allOccurrences(): List<TaskOccurrenceEntity>

    @Upsert
    suspend fun upsertReminder(reminder: ReminderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOccurrence(occurrence: TaskOccurrenceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminders(reminders: List<ReminderEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOccurrences(occurrences: List<TaskOccurrenceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTasks(tasks: List<TaskIndexEntity>)

    @Query("SELECT * FROM task_occurrences WHERE note_id = :noteId AND block_id = :blockId ORDER BY completed_at DESC")
    suspend fun occurrencesForTask(noteId: String, blockId: String): List<TaskOccurrenceEntity>

    @Query("UPDATE reminders SET status = 'CANCELLED', snoozed_until_ms = NULL, revision = revision + 1, updated_at = :updatedAt WHERE note_id = :noteId AND block_id = :blockId AND status != 'CANCELLED'")
    suspend fun cancelReminderForBlock(noteId: String, blockId: String, updatedAt: String)

    @Query("DELETE FROM reminders WHERE note_id = :noteId")
    suspend fun deleteRemindersForNote(noteId: String)

    @Query("DELETE FROM task_occurrences WHERE note_id = :noteId")
    suspend fun deleteOccurrencesForNote(noteId: String)

    @Query("SELECT * FROM notes WHERE deleted_at IS NULL")
    suspend fun allLiveNotes(): List<NoteEntity>

    @Query("DELETE FROM task_index")
    suspend fun clearAllTasks()

    @Query("DELETE FROM reminders")
    suspend fun clearAllReminders()

    @Query("DELETE FROM task_occurrences")
    suspend fun clearAllOccurrences()
}

internal val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_index_due_date` ON `task_index` (`due_date`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_index_checked` ON `task_index` (`checked`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_index_occurrence_id` ON `task_index` (`occurrence_id`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `reminders` (
                `id` TEXT NOT NULL,
                `note_id` TEXT NOT NULL,
                `block_id` TEXT NOT NULL,
                `occurrence_id` TEXT,
                `zone_id` TEXT NOT NULL,
                `local_date` TEXT NOT NULL,
                `local_time` TEXT NOT NULL,
                `trigger_instant_ms` INTEGER,
                `policy` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `recurrence` TEXT NOT NULL,
                `snoozed_until_ms` INTEGER,
                `missed_summarized` INTEGER NOT NULL,
                `revision` INTEGER NOT NULL,
                `updated_at` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )""".trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_reminders_note_id_block_id` ON `reminders` (`note_id`, `block_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminders_status_trigger_instant_ms` ON `reminders` (`status`, `trigger_instant_ms`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminders_occurrence_id` ON `reminders` (`occurrence_id`)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `task_occurrences` (
                `id` TEXT NOT NULL,
                `note_id` TEXT NOT NULL,
                `block_id` TEXT NOT NULL,
                `reminder_id` TEXT,
                `scheduled_date` TEXT,
                `title_snapshot` TEXT NOT NULL,
                `recurrence` TEXT NOT NULL,
                `completed_at` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_occurrences_note_id_block_id` ON `task_occurrences` (`note_id`, `block_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_occurrences_completed_at` ON `task_occurrences` (`completed_at`)")
    }
}

internal class TaskPreferences(
    context: Context,
    prefsName: String = PREFS_NAME,
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    var todoStartupEnabled: Boolean
        get() = prefs.getBoolean(KEY_TODO_STARTUP, false)
        set(value) {
            prefs.edit().putBoolean(KEY_TODO_STARTUP, value).apply()
        }

    var exactAlarmGuidanceDismissed: Boolean
        get() = prefs.getBoolean(KEY_EXACT_ALARM_DISMISSED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_EXACT_ALARM_DISMISSED, value).apply()
        }

    var notificationPromptAttempted: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_PROMPTED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_NOTIFICATION_PROMPTED, value).apply()
        }

    fun observeTodoStartupEnabled(): Flow<Boolean> = observeBoolean(KEY_TODO_STARTUP, default = false)

    fun observeExactAlarmGuidanceDismissed(): Flow<Boolean> = observeBoolean(KEY_EXACT_ALARM_DISMISSED, default = false)

    private fun observeBoolean(key: String, default: Boolean): Flow<Boolean> = callbackFlow {
        trySend(prefs.getBoolean(key, default))
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == key || changed == null) trySend(prefs.getBoolean(key, default))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        private const val PREFS_NAME = "floating_notes_tasks"
        private const val KEY_TODO_STARTUP = "todo_startup_enabled"
        private const val KEY_EXACT_ALARM_DISMISSED = "exact_alarm_guidance_dismissed"
        private const val KEY_NOTIFICATION_PROMPTED = "notification_prompt_attempted"
    }
}
