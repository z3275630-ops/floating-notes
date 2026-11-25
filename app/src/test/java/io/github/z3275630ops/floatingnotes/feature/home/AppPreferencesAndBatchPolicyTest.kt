package io.github.z3275630ops.floatingnotes.feature.home

import android.content.SharedPreferences
import io.github.z3275630ops.floatingnotes.data.local.NotesRepository
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintTypography
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArraySet

class AppPreferencesAndBatchPolicyTest {

    @Test
    fun defaultsAndNormalizationMatchSpec() {
        val mainStore = FakeSharedPreferences()
        val taskStore = FakeSharedPreferences()
        val exploreStore = FakeSharedPreferences()
        val widgetStore = FakeSharedPreferences()
        val prefs = AppPreferences(
            prefs = mainStore,
            taskPrefs = taskStore,
            explorePrefs = exploreStore,
            widgetPrefs = widgetStore,
        )

        assertEquals(FontScaleTier.STANDARD, prefs.fontScaleTier)
        assertEquals(1.0f, prefs.fontScaleTier.multiplier, 0.0001f)
        assertEquals("标准", prefs.fontScaleTier.label)
        assertEquals(NoteSortOrder.UPDATED_DESC, prefs.noteSortOrder)
        assertEquals("按修改时间", prefs.noteSortOrder.label)
        assertTrue(prefs.enableSlashShortcuts)
        assertTrue(prefs.edgeEchoEnabled)
        assertFalse(prefs.openTasksFirst)
        assertTrue(prefs.strongAlarmEnabled)
        assertTrue(prefs.widgetPrivacyMask)
        assertEquals(30, prefs.trashRetentionDays)

        prefs.fontScaleTier = FontScaleTier.LARGE
        assertEquals(1.12f, prefs.fontScaleTier.multiplier, 0.0001f)
        assertEquals("大号", prefs.fontScaleTier.label)

        prefs.fontScaleTier = FontScaleTier.EXTRA_LARGE
        assertEquals(1.24f, prefs.fontScaleTier.multiplier, 0.0001f)
        assertEquals("超大", prefs.fontScaleTier.label)

        prefs.noteSortOrder = NoteSortOrder.CREATED_DESC
        assertEquals(NoteSortOrder.CREATED_DESC, prefs.noteSortOrder)
        assertEquals("按创建时间", prefs.noteSortOrder.label)

        prefs.trashRetentionDays = 7
        assertEquals(7, prefs.trashRetentionDays)
        prefs.trashRetentionDays = 90
        assertEquals(90, prefs.trashRetentionDays)
        prefs.trashRetentionDays = 14 // unsupported -> fallback to 30
        assertEquals(30, prefs.trashRetentionDays)

        assertEquals(FontScaleTier.STANDARD, FontScaleTier.fromStored("UNKNOWN"))
        assertEquals(NoteSortOrder.UPDATED_DESC, NoteSortOrder.fromStored(null))
    }

    @Test
    fun legacyAndUnifiedPrefsKeysStaySynchronized() = runBlocking {
        val mainStore = FakeSharedPreferences()
        val taskStore = FakeSharedPreferences()
        val exploreStore = FakeSharedPreferences()
        val widgetStore = FakeSharedPreferences()

        // Existing legacy values set before AppPreferences reads them
        taskStore.edit().putBoolean(AppPreferences.KEY_TODO_STARTUP_ENABLED, true).apply()
        exploreStore.edit().putBoolean(AppPreferences.KEY_EDGE_ECHO_ENABLED, false).apply()
        widgetStore.edit().putBoolean(AppPreferences.KEY_WIDGET_PRIVACY_MODE, false).apply()

        val prefs = AppPreferences(
            prefs = mainStore,
            taskPrefs = taskStore,
            explorePrefs = exploreStore,
            widgetPrefs = widgetStore,
        )

        assertTrue(prefs.openTasksFirst)
        assertFalse(prefs.edgeEchoEnabled)
        assertFalse(prefs.widgetPrivacyMask)

        // Writing through AppPreferences updates both legacy stores and floating_notes_prefs keys
        prefs.openTasksFirst = false
        prefs.edgeEchoEnabled = true
        prefs.widgetPrivacyMask = true
        prefs.enableSlashShortcuts = false
        prefs.strongAlarmEnabled = false

        assertFalse(taskStore.getBoolean(AppPreferences.KEY_TODO_STARTUP_ENABLED, true))
        assertFalse(mainStore.getBoolean(AppPreferences.KEY_OPEN_TASKS_FIRST, true))
        assertTrue(exploreStore.getBoolean(AppPreferences.KEY_EDGE_ECHO_ENABLED, false))
        assertTrue(mainStore.getBoolean(AppPreferences.KEY_EDGE_ECHO_ENABLED, false))
        assertTrue(widgetStore.getBoolean(AppPreferences.KEY_WIDGET_PRIVACY_MODE, false))
        assertTrue(mainStore.getBoolean(AppPreferences.KEY_WIDGET_PRIVACY_MASK, false))

        val snap = prefs.observeSnapshot().first()
        assertFalse(snap.openTasksFirst)
        assertTrue(snap.edgeEchoEnabled)
        assertTrue(snap.widgetPrivacyMask)
        assertFalse(snap.enableSlashShortcuts)
        assertFalse(snap.strongAlarmEnabled)
    }

    @Test
    fun mintTypographyFixesDisplayHeadlineInversionAndScalesProportionally() {
        val standard = mintTypography()
        assertEquals(17.5f, standard.bodyLarge.fontSize.value, 0.01f)
        assertEquals(15.5f, standard.bodyMedium.fontSize.value, 0.01f)
        assertEquals(14.0f, standard.bodySmall.fontSize.value, 0.01f)
        assertEquals(16.0f, standard.labelLarge.fontSize.value, 0.01f)
        assertEquals(14.0f, standard.labelMedium.fontSize.value, 0.01f)
        assertEquals(13.0f, standard.labelSmall.fontSize.value, 0.01f)
        assertEquals(20.0f, standard.titleLarge.fontSize.value, 0.01f)
        assertEquals(18.0f, standard.titleMedium.fontSize.value, 0.01f)
        assertEquals(16.5f, standard.titleSmall.fontSize.value, 0.01f)
        assertTrue(standard.displayLarge.fontSize.value > standard.headlineLarge.fontSize.value)
        assertTrue(standard.headlineLarge.fontSize.value > standard.headlineMedium.fontSize.value)
        assertTrue(standard.headlineMedium.fontSize.value > standard.headlineSmall.fontSize.value)

        val large = mintTypography(FontScaleTier.LARGE.multiplier)
        assertEquals(
            standard.bodyMedium.fontSize.value * 1.12f,
            large.bodyMedium.fontSize.value,
            0.01f,
        )
        assertEquals(
            standard.titleSmall.fontSize.value * 1.12f,
            large.titleSmall.fontSize.value,
            0.01f,
        )

        val extraLarge = mintTypography(FontScaleTier.EXTRA_LARGE.multiplier)
        assertEquals(
            standard.displayLarge.fontSize.value * 1.24f,
            extraLarge.displayLarge.fontSize.value,
            0.01f,
        )
    }

    @Test
    fun aiPolicyValidationAndBatchIdNormalization() {
        assertEquals("inherit", NotesRepository.validateAiPolicy("inherit"))
        assertEquals("exclude", NotesRepository.validateAiPolicy("exclude"))
        assertEquals("allow", NotesRepository.validateAiPolicy("allow"))

        assertThrows(IllegalArgumentException::class.java) {
            NotesRepository.validateAiPolicy("deny")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NotesRepository.validateAiPolicy("")
        }

        val normalized = NotesRepository.normalizeBatchNoteIds(
            listOf(" n1 ", "", "   ", "n2", "n1", "n3 ", "n2"),
        )
        assertEquals(listOf("n1", "n2", "n3"), normalized)
    }

    @Test
    fun storageFormattingAndChecklistCountAreAccurate() {
        assertEquals("0.00 MB", formatStorageMb(0L))
        assertEquals("0.00 MB", formatStorageMb(-50L))
        assertEquals("1.50 MB", formatStorageMb(1_572_864L))

        val checklist = AlarmChecklistState(
            notificationsGranted = true,
            exactAlarmGranted = false,
            fullScreenIntentGranted = true,
            batteryOptimizationIgnored = false,
        )
        assertEquals(2, checklist.readyCount)
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val data = LinkedHashMap<String, Any?>()
        private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(data)

        override fun getString(key: String?, defValue: String?): String? =
            (data[key] as? String) ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            return (data[key] as? Set<String>)?.toMutableSet() ?: defValues
        }

        override fun getInt(key: String?, defValue: Int): Int =
            (data[key] as? Int) ?: defValue

        override fun getLong(key: String?, defValue: Long): Long =
            (data[key] as? Long) ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            (data[key] as? Float) ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            (data[key] as? Boolean) ?: defValue

        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {
            if (listener != null) listeners.add(listener)
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {
            if (listener != null) listeners.remove(listener)
        }

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private val removals = LinkedHashSet<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null) {
                    removals.remove(key)
                    pending[key] = value
                }
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                if (key != null) {
                    removals.remove(key)
                    pending[key] = values?.toSet()
                }
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                if (key != null) {
                    removals.remove(key)
                    pending[key] = value
                }
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                if (key != null) {
                    removals.remove(key)
                    pending[key] = value
                }
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                if (key != null) {
                    removals.remove(key)
                    pending[key] = value
                }
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                if (key != null) {
                    removals.remove(key)
                    pending[key] = value
                }
            }

            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) {
                    pending.remove(key)
                    removals.add(key)
                }
            }

            override fun clear(): SharedPreferences.Editor = apply {
                clearAll = true
                pending.clear()
                removals.clear()
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                val changedKeys = LinkedHashSet<String>()
                if (clearAll) {
                    changedKeys.addAll(data.keys)
                    data.clear()
                }
                for (key in removals) {
                    if (data.containsKey(key)) {
                        data.remove(key)
                        changedKeys.add(key)
                    }
                }
                for ((key, value) in pending) {
                    if (value == null) {
                        if (data.remove(key) != null) changedKeys.add(key)
                    } else {
                        data[key] = value
                        changedKeys.add(key)
                    }
                }
                for (key in changedKeys) {
                    listeners.forEach { it.onSharedPreferenceChanged(this@FakeSharedPreferences, key) }
                }
            }
        }
    }
}
