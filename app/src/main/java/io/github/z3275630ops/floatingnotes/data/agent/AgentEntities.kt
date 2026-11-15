package io.github.z3275630ops.floatingnotes.data.agent

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
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "agent_runs",
    indices = [
        Index(value = ["updated_at"]),
        Index(value = ["skill_id"]),
    ],
)
internal data class AgentRunEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "skill_id") val skillId: String,
    val prompt: String,
    @ColumnInfo(name = "scope_json") val scopeJson: String,
    val status: String,
    @ColumnInfo(name = "result_json") val resultJson: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)

@Entity(
    tableName = "agent_actions",
    indices = [Index(value = ["run_id"])],
)
internal data class AgentActionEntity(
    @PrimaryKey @ColumnInfo(name = "action_id") val actionId: String,
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "action_type") val actionType: String,
    @ColumnInfo(name = "target_note_id") val targetNoteId: String? = null,
    @ColumnInfo(name = "source_revisions_json") val sourceRevisionsJson: String,
    val status: String,
    val summary: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
)

@Dao
internal interface AgentDao {
    @Query("SELECT * FROM agent_runs ORDER BY updated_at DESC LIMIT :limit")
    fun observeRecentRuns(limit: Int = 20): Flow<List<AgentRunEntity>>

    @Query("SELECT * FROM agent_runs ORDER BY updated_at DESC LIMIT :limit")
    suspend fun recentRuns(limit: Int = 20): List<AgentRunEntity>

    @Query("SELECT * FROM agent_runs WHERE id = :id")
    suspend fun runById(id: String): AgentRunEntity?

    @Upsert
    suspend fun upsertRun(run: AgentRunEntity)

    @Query("SELECT * FROM agent_actions WHERE action_id = :actionId")
    suspend fun actionById(actionId: String): AgentActionEntity?

    @Query("SELECT * FROM agent_actions WHERE run_id = :runId ORDER BY created_at DESC")
    suspend fun actionsForRun(runId: String): List<AgentActionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAction(action: AgentActionEntity): Long

    @Query("DELETE FROM agent_actions")
    suspend fun clearAllActions()

    @Query("DELETE FROM agent_runs")
    suspend fun clearAllRuns()
}

internal val MIGRATION_6_7: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `agent_runs` (
                `id` TEXT NOT NULL,
                `skill_id` TEXT NOT NULL,
                `prompt` TEXT NOT NULL,
                `scope_json` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `result_json` TEXT NOT NULL,
                `created_at` TEXT NOT NULL,
                `updated_at` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_runs_updated_at` ON `agent_runs` (`updated_at`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_runs_skill_id` ON `agent_runs` (`skill_id`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `agent_actions` (
                `action_id` TEXT NOT NULL,
                `run_id` TEXT NOT NULL,
                `action_type` TEXT NOT NULL,
                `target_note_id` TEXT,
                `source_revisions_json` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `summary` TEXT NOT NULL,
                `created_at` TEXT NOT NULL,
                PRIMARY KEY(`action_id`)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_actions_run_id` ON `agent_actions` (`run_id`)")
    }
}
