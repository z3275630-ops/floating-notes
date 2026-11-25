package io.github.z3275630ops.floatingnotes.feature.home

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

internal enum class FontScaleTier(
    val multiplier: Float,
    val label: String,
) {
    STANDARD(1.0f, "标准"),
    LARGE(1.12f, "大号"),
    EXTRA_LARGE(1.24f, "超大");

    val scale: Float
        get() = multiplier

    companion object {
        fun fromStored(raw: String?): FontScaleTier =
            entries.firstOrNull { it.name == raw } ?: STANDARD
    }
}

internal enum class NoteSortOrder(val label: String) {
    UPDATED_DESC("按修改时间"),
    CREATED_DESC("按创建时间");

    companion object {
        fun fromStored(raw: String?): NoteSortOrder =
            entries.firstOrNull { it.name == raw } ?: UPDATED_DESC
    }
}

@Immutable
internal data class AppPreferencesSnapshot(
    val fontScaleTier: FontScaleTier = FontScaleTier.STANDARD,
    val noteSortOrder: NoteSortOrder = NoteSortOrder.UPDATED_DESC,
    val enableSlashShortcuts: Boolean = true,
    val edgeEchoEnabled: Boolean = true,
    val openTasksFirst: Boolean = false,
    val strongAlarmEnabled: Boolean = true,
    val widgetPrivacyMask: Boolean = true,
    val trashRetentionDays: Int = AppPreferences.DEFAULT_TRASH_RETENTION_DAYS,
)

internal class AppPreferences internal constructor(
    private val prefs: SharedPreferences,
    private val taskPrefs: SharedPreferences = prefs,
    private val explorePrefs: SharedPreferences = prefs,
    private val widgetPrefs: SharedPreferences = prefs,
) {
    constructor(context: Context) : this(
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        taskPrefs = context.applicationContext.getSharedPreferences(TASK_PREFS_NAME, Context.MODE_PRIVATE),
        explorePrefs = context.applicationContext.getSharedPreferences(EXPLORE_PREFS_NAME, Context.MODE_PRIVATE),
        widgetPrefs = context.applicationContext.getSharedPreferences(WIDGET_PREFS_NAME, Context.MODE_PRIVATE),
    )

    var fontScaleTier: FontScaleTier
        get() = FontScaleTier.fromStored(prefs.getString(KEY_FONT_SCALE_TIER, null))
        set(value) {
            prefs.edit().putString(KEY_FONT_SCALE_TIER, value.name).apply()
        }

    var noteSortOrder: NoteSortOrder
        get() = NoteSortOrder.fromStored(prefs.getString(KEY_NOTE_SORT_ORDER, null))
        set(value) {
            prefs.edit().putString(KEY_NOTE_SORT_ORDER, value.name).apply()
        }

    var enableSlashShortcuts: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_SLASH_SHORTCUTS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLE_SLASH_SHORTCUTS, value).apply()
        }

    var trashRetentionDays: Int
        get() = normalizeTrashRetentionDays(
            prefs.getInt(KEY_TRASH_RETENTION_DAYS, DEFAULT_TRASH_RETENTION_DAYS),
        )
        set(value) {
            prefs.edit().putInt(KEY_TRASH_RETENTION_DAYS, normalizeTrashRetentionDays(value)).apply()
        }

    var strongAlarmEnabled: Boolean
        get() = prefs.getBoolean(KEY_STRONG_ALARM_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_STRONG_ALARM_ENABLED, value).apply()
        }

    var openTasksFirst: Boolean
        get() = when {
            taskPrefs.contains(KEY_TODO_STARTUP_ENABLED) ->
                taskPrefs.getBoolean(KEY_TODO_STARTUP_ENABLED, false)
            else -> prefs.getBoolean(KEY_OPEN_TASKS_FIRST, false)
        }
        set(value) {
            taskPrefs.edit().putBoolean(KEY_TODO_STARTUP_ENABLED, value).apply()
            if (prefs !== taskPrefs) {
                prefs.edit().putBoolean(KEY_OPEN_TASKS_FIRST, value).apply()
            } else {
                prefs.edit()
                    .putBoolean(KEY_TODO_STARTUP_ENABLED, value)
                    .putBoolean(KEY_OPEN_TASKS_FIRST, value)
                    .apply()
            }
        }

    var todoStartupEnabled: Boolean
        get() = openTasksFirst
        set(value) {
            openTasksFirst = value
        }

    var edgeEchoEnabled: Boolean
        get() = when {
            explorePrefs.contains(KEY_EDGE_ECHO_ENABLED) ->
                explorePrefs.getBoolean(KEY_EDGE_ECHO_ENABLED, true)
            else -> prefs.getBoolean(KEY_EDGE_ECHO_ENABLED, true)
        }
        set(value) {
            explorePrefs.edit().putBoolean(KEY_EDGE_ECHO_ENABLED, value).apply()
            if (prefs !== explorePrefs) {
                prefs.edit().putBoolean(KEY_EDGE_ECHO_ENABLED, value).apply()
            }
        }

    var widgetPrivacyMask: Boolean
        get() = when {
            widgetPrefs.contains(KEY_WIDGET_PRIVACY_MODE) ->
                widgetPrefs.getBoolean(KEY_WIDGET_PRIVACY_MODE, true)
            else -> prefs.getBoolean(KEY_WIDGET_PRIVACY_MASK, true)
        }
        set(value) {
            widgetPrefs.edit().putBoolean(KEY_WIDGET_PRIVACY_MODE, value).apply()
            if (prefs !== widgetPrefs) {
                prefs.edit().putBoolean(KEY_WIDGET_PRIVACY_MASK, value).apply()
            } else {
                prefs.edit()
                    .putBoolean(KEY_WIDGET_PRIVACY_MODE, value)
                    .putBoolean(KEY_WIDGET_PRIVACY_MASK, value)
                    .apply()
            }
        }

    var widgetPrivacyEnabled: Boolean
        get() = widgetPrivacyMask
        set(value) {
            widgetPrivacyMask = value
        }

    fun snapshot(): AppPreferencesSnapshot = AppPreferencesSnapshot(
        fontScaleTier = fontScaleTier,
        noteSortOrder = noteSortOrder,
        enableSlashShortcuts = enableSlashShortcuts,
        edgeEchoEnabled = edgeEchoEnabled,
        openTasksFirst = openTasksFirst,
        strongAlarmEnabled = strongAlarmEnabled,
        widgetPrivacyMask = widgetPrivacyMask,
        trashRetentionDays = trashRetentionDays,
    )

    fun observeSnapshot(): Flow<AppPreferencesSnapshot> = callbackFlow {
        val watchedKeys = setOf(
            KEY_FONT_SCALE_TIER,
            KEY_NOTE_SORT_ORDER,
            KEY_ENABLE_SLASH_SHORTCUTS,
            KEY_TRASH_RETENTION_DAYS,
            KEY_STRONG_ALARM_ENABLED,
            KEY_OPEN_TASKS_FIRST,
            KEY_TODO_STARTUP_ENABLED,
            KEY_EDGE_ECHO_ENABLED,
            KEY_WIDGET_PRIVACY_MASK,
            KEY_WIDGET_PRIVACY_MODE,
        )
        val stores = linkedSetOf(prefs, taskPrefs, explorePrefs, widgetPrefs)
        trySend(snapshot())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in watchedKeys) {
                trySend(snapshot())
            }
        }
        stores.forEach { it.registerOnSharedPreferenceChangeListener(listener) }
        awaitClose {
            stores.forEach { it.unregisterOnSharedPreferenceChangeListener(listener) }
        }
    }.distinctUntilChanged()

    fun observeFontScaleTier(): Flow<FontScaleTier> =
        observeKey(prefs, setOf(KEY_FONT_SCALE_TIER)) { fontScaleTier }

    fun observeNoteSortOrder(): Flow<NoteSortOrder> =
        observeKey(prefs, setOf(KEY_NOTE_SORT_ORDER)) { noteSortOrder }

    fun observeEnableSlashShortcuts(): Flow<Boolean> =
        observeKey(prefs, setOf(KEY_ENABLE_SLASH_SHORTCUTS)) { enableSlashShortcuts }

    fun observeTrashRetentionDays(): Flow<Int> =
        observeKey(prefs, setOf(KEY_TRASH_RETENTION_DAYS)) { trashRetentionDays }

    fun observeStrongAlarmEnabled(): Flow<Boolean> =
        observeKey(prefs, setOf(KEY_STRONG_ALARM_ENABLED)) { strongAlarmEnabled }

    fun observeOpenTasksFirst(): Flow<Boolean> =
        observeStores(
            stores = linkedSetOf(prefs, taskPrefs),
            keys = setOf(KEY_OPEN_TASKS_FIRST, KEY_TODO_STARTUP_ENABLED),
        ) { openTasksFirst }

    fun observeEdgeEchoEnabled(): Flow<Boolean> =
        observeStores(
            stores = linkedSetOf(prefs, explorePrefs),
            keys = setOf(KEY_EDGE_ECHO_ENABLED),
        ) { edgeEchoEnabled }

    fun observeWidgetPrivacyMask(): Flow<Boolean> =
        observeStores(
            stores = linkedSetOf(prefs, widgetPrefs),
            keys = setOf(KEY_WIDGET_PRIVACY_MASK, KEY_WIDGET_PRIVACY_MODE),
        ) { widgetPrivacyMask }

    private fun <T> observeKey(
        store: SharedPreferences,
        keys: Set<String>,
        reader: () -> T,
    ): Flow<T> = observeStores(linkedSetOf(store), keys, reader)

    private fun <T> observeStores(
        stores: Set<SharedPreferences>,
        keys: Set<String>,
        reader: () -> T,
    ): Flow<T> = callbackFlow {
        trySend(reader())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in keys) {
                trySend(reader())
            }
        }
        stores.forEach { it.registerOnSharedPreferenceChangeListener(listener) }
        awaitClose {
            stores.forEach { it.unregisterOnSharedPreferenceChangeListener(listener) }
        }
    }.distinctUntilChanged()

    companion object {
        const val PREFS_NAME = "floating_notes_prefs"
        const val TASK_PREFS_NAME = "floating_notes_tasks"
        const val EXPLORE_PREFS_NAME = "floating_notes_explore"
        const val WIDGET_PREFS_NAME = "floating_notes_widget_prefs"

        const val KEY_FONT_SCALE_TIER = "font_scale_tier"
        const val KEY_NOTE_SORT_ORDER = "note_sort_order"
        const val KEY_ENABLE_SLASH_SHORTCUTS = "enable_slash_shortcuts"
        const val KEY_TRASH_RETENTION_DAYS = "trash_retention_days"
        const val KEY_STRONG_ALARM_ENABLED = "strong_alarm_enabled"
        const val KEY_OPEN_TASKS_FIRST = "open_tasks_first"
        const val KEY_TODO_STARTUP_ENABLED = "todo_startup_enabled"
        const val KEY_EDGE_ECHO_ENABLED = "edge_echo_enabled"
        const val KEY_WIDGET_PRIVACY_MASK = "widget_privacy_mask"
        const val KEY_WIDGET_PRIVACY_MODE = "widget_privacy_mode"

        const val DEFAULT_TRASH_RETENTION_DAYS = 30
        val VALID_TRASH_RETENTION_DAYS: List<Int> = listOf(7, 30, 90)

        fun normalizeTrashRetentionDays(days: Int): Int =
            if (days in VALID_TRASH_RETENTION_DAYS) days else DEFAULT_TRASH_RETENTION_DAYS

        fun isStrongAlarmEnabled(context: Context): Boolean =
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_STRONG_ALARM_ENABLED, true)

        fun fontScaleMultiplier(context: Context): Float {
            val raw = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_FONT_SCALE_TIER, null)
            return FontScaleTier.fromStored(raw).multiplier
        }
    }
}
