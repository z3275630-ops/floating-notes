package io.github.z3275630ops.floatingnotes.feature.widget

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

internal class WidgetPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val unifiedPrefs: SharedPreferences =
        appContext.getSharedPreferences(UNIFIED_PREFS_NAME, Context.MODE_PRIVATE)

    var privacyModeEnabled: Boolean
        get() = if (unifiedPrefs.contains(KEY_UNIFIED_PRIVACY_MASK)) {
            unifiedPrefs.getBoolean(KEY_UNIFIED_PRIVACY_MASK, true)
        } else {
            prefs.getBoolean(KEY_PRIVACY_MODE, true)
        }
        set(value) {
            prefs.edit().putBoolean(KEY_PRIVACY_MODE, value).apply()
            unifiedPrefs.edit().putBoolean(KEY_UNIFIED_PRIVACY_MASK, value).apply()
        }

    fun observePrivacyModeEnabled(): Flow<Boolean> = callbackFlow {
        trySend(privacyModeEnabled)
        val widgetListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_PRIVACY_MODE || key == null) {
                trySend(privacyModeEnabled)
            }
        }
        val unifiedListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_UNIFIED_PRIVACY_MASK || key == null) {
                trySend(privacyModeEnabled)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(widgetListener)
        unifiedPrefs.registerOnSharedPreferenceChangeListener(unifiedListener)
        awaitClose {
            prefs.unregisterOnSharedPreferenceChangeListener(widgetListener)
            unifiedPrefs.unregisterOnSharedPreferenceChangeListener(unifiedListener)
        }
    }

    companion object {
        private const val PREFS_NAME = "floating_notes_widget_prefs"
        private const val KEY_PRIVACY_MODE = "widget_privacy_mode"
        private const val UNIFIED_PREFS_NAME = "floating_notes_prefs"
        private const val KEY_UNIFIED_PRIVACY_MASK = "widget_privacy_mask"
    }
}
