package io.github.z3275630ops.floatingnotes.data.discovery

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

@Entity(
    tableName = "note_chunks",
    indices = [
        Index(value = ["note_id"]),
        Index(value = ["note_id", "chunk_order"]),
    ],
)
internal data class NoteChunkEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "note_id") val noteId: String,
    @ColumnInfo(name = "chunk_order") val chunkOrder: Int,
    @ColumnInfo(name = "block_ids_json") val blockIdsJson: String,
    @ColumnInfo(name = "note_revision") val noteRevision: Long,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    val excerpt: String,
    @ColumnInfo(name = "token_payload") val tokenPayload: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)

@Entity(
    tableName = "review_sessions",
    indices = [Index(value = ["local_date", "profile_key"])],
)
internal data class ReviewSessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "local_date") val localDate: String,
    @ColumnInfo(name = "profile_key") val profileKey: String,
    @ColumnInfo(name = "config_hash") val configHash: String,
    val round: Int,
    @ColumnInfo(name = "card_ids_json") val cardIdsJson: String,
    @ColumnInfo(name = "current_index") val currentIndex: Int,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)

@Entity(
    tableName = "review_events",
    indices = [
        Index(value = ["session_id"]),
        Index(value = ["note_id"]),
    ],
)
internal data class ReviewEventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "note_id") val noteId: String,
    val action: String,
    @ColumnInfo(name = "until_iso") val untilIso: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String,
)

@Entity(
    tableName = "walk_sessions",
    indices = [Index(value = ["updated_at"])],
)
internal data class WalkSessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "seed_note_id") val seedNoteId: String? = null,
    @ColumnInfo(name = "seed_tag_id") val seedTagId: String? = null,
    @ColumnInfo(name = "current_step_order") val currentStepOrder: Int = 0,
    val status: String = "active",
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)

@Entity(
    tableName = "walk_steps",
    indices = [
        Index(value = ["session_id", "step_order"]),
        Index(value = ["note_id"]),
    ],
)
internal data class WalkStepEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "step_order") val stepOrder: Int,
    @ColumnInfo(name = "parent_step_id") val parentStepId: String? = null,
    @ColumnInfo(name = "note_id") val noteId: String,
    @ColumnInfo(name = "reason_label") val reasonLabel: String,
    @ColumnInfo(name = "strategy_type") val strategyType: String,
    @ColumnInfo(name = "branch_index") val branchIndex: Int = 0,
    @ColumnInfo(name = "user_memo") val userMemo: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String,
)

@Dao
internal interface DiscoveryDao {
    @Query("SELECT * FROM note_chunks ORDER BY note_id, chunk_order")
    suspend fun allChunks(): List<NoteChunkEntity>

    @Query("SELECT DISTINCT note_id FROM note_chunks")
    suspend fun indexedNoteIds(): List<String>

    @Query("SELECT note_id, MAX(note_revision) AS maxRev FROM note_chunks GROUP BY note_id")
    suspend fun indexedNoteRevisions(): List<NoteRevisionMark>

    @Query("SELECT * FROM note_chunks WHERE note_id = :noteId ORDER BY chunk_order")
    suspend fun chunksForNote(noteId: String): List<NoteChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<NoteChunkEntity>)

    @Query("DELETE FROM note_chunks WHERE note_id = :noteId")
    suspend fun deleteChunksForNote(noteId: String)

    @Query("DELETE FROM note_chunks WHERE note_id NOT IN (SELECT id FROM notes WHERE deleted_at IS NULL)")
    suspend fun purgeStaleOrDeletedChunks()

    @Query("DELETE FROM note_chunks")
    suspend fun clearAllChunks()

    @Query(
        "SELECT * FROM review_sessions WHERE local_date = :localDate AND profile_key = :profileKey ORDER BY round DESC, updated_at DESC, id DESC LIMIT 1",
    )
    suspend fun latestReviewSessionForDay(localDate: String, profileKey: String): ReviewSessionEntity?

    @Query(
        "SELECT * FROM review_sessions WHERE local_date = :localDate AND profile_key = :profileKey AND config_hash = :configHash ORDER BY round DESC, updated_at DESC, id DESC LIMIT 1",
    )
    suspend fun latestReviewSessionForDayAndConfig(
        localDate: String,
        profileKey: String,
        configHash: String,
    ): ReviewSessionEntity?

    @Query(
        "SELECT * FROM review_sessions WHERE local_date = :localDate AND profile_key = :profileKey ORDER BY round DESC, updated_at DESC, id DESC",
    )
    suspend fun reviewSessionsForDay(localDate: String, profileKey: String): List<ReviewSessionEntity>

    @Query("SELECT * FROM review_sessions WHERE id = :id")
    suspend fun reviewSession(id: String): ReviewSessionEntity?

    @Query("SELECT * FROM review_sessions ORDER BY created_at DESC LIMIT 1")
    suspend fun latestReviewSession(): ReviewSessionEntity?

    @Upsert
    suspend fun upsertReviewSession(session: ReviewSessionEntity)

    @Query("UPDATE review_sessions SET current_index = :index, updated_at = :updatedAt WHERE id = :sessionId")
    suspend fun updateReviewSessionIndex(sessionId: String, index: Int, updatedAt: String)

    @Query("SELECT * FROM review_events WHERE session_id = :sessionId ORDER BY created_at")
    fun observeReviewEventsForSession(sessionId: String): Flow<List<ReviewEventEntity>>

    @Query("SELECT * FROM review_events ORDER BY created_at DESC")
    fun observeAllReviewEvents(): Flow<List<ReviewEventEntity>>

    @Query("SELECT * FROM review_events ORDER BY created_at DESC")
    suspend fun allReviewEvents(): List<ReviewEventEntity>

    @Upsert
    suspend fun upsertReviewEvent(event: ReviewEventEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReviewEventIfAbsent(event: ReviewEventEntity): Long

    @Query("DELETE FROM review_events WHERE action IN ('excluded', 'suppressed_7d', 'deferred')")
    suspend fun clearSuppressionEvents()

    @Query("DELETE FROM review_events WHERE note_id = :noteId AND action IN ('excluded', 'suppressed_7d', 'deferred')")
    suspend fun clearSuppressionEventsForNote(noteId: String)

    @Query("DELETE FROM review_sessions")
    suspend fun clearAllReviewSessions()

    @Query("DELETE FROM review_events")
    suspend fun clearAllReviewEvents()

    @Query("SELECT * FROM walk_sessions ORDER BY updated_at DESC LIMIT 1")
    fun observeLatestWalkSession(): Flow<WalkSessionEntity?>

    @Query("SELECT * FROM walk_sessions WHERE status = 'active' ORDER BY updated_at DESC LIMIT 1")
    suspend fun latestActiveWalkSession(): WalkSessionEntity?

    @Query("SELECT * FROM walk_sessions ORDER BY updated_at DESC LIMIT 20")
    fun observeRecentWalkSessions(): Flow<List<WalkSessionEntity>>

    @Query("SELECT * FROM walk_sessions WHERE id = :id")
    suspend fun walkSession(id: String): WalkSessionEntity?

    @Upsert
    suspend fun upsertWalkSession(session: WalkSessionEntity)

    @Query("SELECT * FROM walk_steps WHERE session_id = :sessionId ORDER BY step_order ASC, created_at ASC")
    fun observeWalkSteps(sessionId: String): Flow<List<WalkStepEntity>>

    @Query("SELECT * FROM walk_steps WHERE session_id = :sessionId ORDER BY step_order ASC, created_at ASC")
    suspend fun walkSteps(sessionId: String): List<WalkStepEntity>

    @Query("SELECT * FROM walk_steps ORDER BY created_at DESC LIMIT 100")
    suspend fun recentWalkSteps(): List<WalkStepEntity>

    @Upsert
    suspend fun upsertWalkStep(step: WalkStepEntity)

    @Query("DELETE FROM walk_steps")
    suspend fun clearAllWalkSteps()

    @Query("DELETE FROM walk_sessions")
    suspend fun clearAllWalkSessions()
}

internal data class NoteRevisionMark(
    @ColumnInfo(name = "note_id") val noteId: String,
    val maxRev: Long,
)

internal val MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `note_chunks` (
                `id` TEXT NOT NULL,
                `note_id` TEXT NOT NULL,
                `chunk_order` INTEGER NOT NULL,
                `block_ids_json` TEXT NOT NULL,
                `note_revision` INTEGER NOT NULL,
                `content_hash` TEXT NOT NULL,
                `excerpt` TEXT NOT NULL,
                `token_payload` TEXT NOT NULL,
                `updated_at` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_chunks_note_id` ON `note_chunks` (`note_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_chunks_note_id_chunk_order` ON `note_chunks` (`note_id`, `chunk_order`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `review_sessions` (
                `id` TEXT NOT NULL,
                `local_date` TEXT NOT NULL,
                `profile_key` TEXT NOT NULL,
                `config_hash` TEXT NOT NULL,
                `round` INTEGER NOT NULL,
                `card_ids_json` TEXT NOT NULL,
                `current_index` INTEGER NOT NULL,
                `created_at` TEXT NOT NULL,
                `updated_at` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_review_sessions_local_date_profile_key` ON `review_sessions` (`local_date`, `profile_key`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `review_events` (
                `id` TEXT NOT NULL,
                `session_id` TEXT NOT NULL,
                `note_id` TEXT NOT NULL,
                `action` TEXT NOT NULL,
                `until_iso` TEXT,
                `created_at` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_review_events_session_id` ON `review_events` (`session_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_review_events_note_id` ON `review_events` (`note_id`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `walk_sessions` (
                `id` TEXT NOT NULL,
                `seed_note_id` TEXT,
                `seed_tag_id` TEXT,
                `current_step_order` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `created_at` TEXT NOT NULL,
                `updated_at` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_walk_sessions_updated_at` ON `walk_sessions` (`updated_at`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `walk_steps` (
                `id` TEXT NOT NULL,
                `session_id` TEXT NOT NULL,
                `step_order` INTEGER NOT NULL,
                `parent_step_id` TEXT,
                `note_id` TEXT NOT NULL,
                `reason_label` TEXT NOT NULL,
                `strategy_type` TEXT NOT NULL,
                `branch_index` INTEGER NOT NULL,
                `user_memo` TEXT,
                `created_at` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_walk_steps_session_id_step_order` ON `walk_steps` (`session_id`, `step_order`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_walk_steps_note_id` ON `walk_steps` (`note_id`)")
    }
}

internal data class ReviewConfig(
    val dailyCount: Int = 5,
    val rangeDays: Int = 0,
    val includeUntagged: Boolean = true,
    val includeTodosOnly: Boolean = false,
    val excludedTagIds: Set<String> = emptySet(),
) {
    fun configHash(): String {
        val sortedTags = excludedTagIds.sorted().joinToString(",")
        return "$dailyCount|$rangeDays|$includeUntagged|$includeTodosOnly|$sortedTags"
    }
}

internal class ExplorePreferences(
    context: Context,
    prefsName: String = PREFS_NAME,
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    var edgeEchoEnabled: Boolean
        get() = prefs.getBoolean(KEY_EDGE_ECHO_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_EDGE_ECHO_ENABLED, value).apply()
        }

    var reviewConfig: ReviewConfig
        get() = ReviewConfig(
            dailyCount = prefs.getInt(KEY_REVIEW_COUNT, 5).coerceIn(3, 12),
            rangeDays = prefs.getInt(KEY_REVIEW_RANGE_DAYS, 0),
            includeUntagged = prefs.getBoolean(KEY_REVIEW_UNTAGGED, true),
            includeTodosOnly = prefs.getBoolean(KEY_REVIEW_TODOS_ONLY, false),
            excludedTagIds = prefs.getStringSet(KEY_EXCLUDED_TAG_IDS, emptySet())?.toSet().orEmpty(),
        )
        set(value) {
            prefs.edit()
                .putInt(KEY_REVIEW_COUNT, value.dailyCount.coerceIn(3, 12))
                .putInt(KEY_REVIEW_RANGE_DAYS, value.rangeDays)
                .putBoolean(KEY_REVIEW_UNTAGGED, value.includeUntagged)
                .putBoolean(KEY_REVIEW_TODOS_ONLY, value.includeTodosOnly)
                .putStringSet(KEY_EXCLUDED_TAG_IDS, value.excludedTagIds)
                .apply()
        }

    var excludedFindNoteIds: Set<String>
        get() = prefs.getStringSet(KEY_EXCLUDED_FIND_NOTE_IDS, emptySet())?.toSet().orEmpty()
        set(value) {
            prefs.edit().putStringSet(KEY_EXCLUDED_FIND_NOTE_IDS, value).apply()
        }

    fun observeEdgeEchoEnabled(): Flow<Boolean> = callbackFlow {
        trySend(edgeEchoEnabled)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_EDGE_ECHO_ENABLED || key == null) trySend(edgeEchoEnabled)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun observeReviewConfig(): Flow<ReviewConfig> = callbackFlow {
        val watched = setOf(
            KEY_REVIEW_COUNT,
            KEY_REVIEW_RANGE_DAYS,
            KEY_REVIEW_UNTAGGED,
            KEY_REVIEW_TODOS_ONLY,
            KEY_EXCLUDED_TAG_IDS,
        )
        trySend(reviewConfig)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in watched || key == null) trySend(reviewConfig)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun observeExcludedFindNoteIds(): Flow<Set<String>> = callbackFlow {
        trySend(excludedFindNoteIds)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_EXCLUDED_FIND_NOTE_IDS || key == null) trySend(excludedFindNoteIds)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun addExcludedReviewTag(tagId: String) {
        val current = reviewConfig
        reviewConfig = current.copy(excludedTagIds = current.excludedTagIds + tagId)
    }

    fun removeExcludedReviewTag(tagId: String) {
        val current = reviewConfig
        reviewConfig = current.copy(excludedTagIds = current.excludedTagIds - tagId)
    }

    fun clearAllExclusions() {
        val current = reviewConfig
        reviewConfig = current.copy(excludedTagIds = emptySet())
        excludedFindNoteIds = emptySet()
    }

    fun addExcludedFindNote(noteId: String) {
        excludedFindNoteIds = excludedFindNoteIds + noteId
    }

    companion object {
        private const val PREFS_NAME = "floating_notes_explore"
        private const val KEY_EDGE_ECHO_ENABLED = "edge_echo_enabled"
        private const val KEY_REVIEW_COUNT = "review_daily_count"
        private const val KEY_REVIEW_RANGE_DAYS = "review_range_days"
        private const val KEY_REVIEW_UNTAGGED = "review_include_untagged"
        private const val KEY_REVIEW_TODOS_ONLY = "review_todos_only"
        private const val KEY_EXCLUDED_TAG_IDS = "review_excluded_tag_ids"
        private const val KEY_EXCLUDED_FIND_NOTE_IDS = "find_excluded_note_ids"
    }
}
