package io.github.z3275630ops.floatingnotes.feature.widget

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

internal class WidgetPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var privacyModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_PRIVACY_MODE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_PRIVACY_MODE, value).apply()
        }

    fun observePrivacyModeEnabled(): Flow<Boolean> = callbackFlow {
        trySend(privacyModeEnabled)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_PRIVACY_MODE) {
                trySend(privacyModeEnabled)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        private const val PREFS_NAME = "floating_notes_widget_prefs"
        private const val KEY_PRIVACY_MODE = "widget_privacy_mode"
    }
}
