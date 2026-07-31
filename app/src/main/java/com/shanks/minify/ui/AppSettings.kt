package com.shanks.minify.ui

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.shanks.minify.ui.theme.AppAccent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "minify_settings")

object AppSettings {
    private val KEY_ACCENT      = stringPreferencesKey("accent_color")
    private val KEY_CUSTOM_HEX  = stringPreferencesKey("custom_hex")
    private val KEY_SETUP_DONE  = stringPreferencesKey("setup_done")

    fun accentFlow(context: Context): Flow<AppAccent> =
        context.dataStore.data.map { prefs ->
            val name = prefs[KEY_ACCENT] ?: AppAccent.BLUE.name
            AppAccent.entries.firstOrNull { it.name == name } ?: AppAccent.BLUE
        }

    fun customHexFlow(context: Context): Flow<String?> =
        context.dataStore.data.map { it[KEY_CUSTOM_HEX] }

    fun setupDoneFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[KEY_SETUP_DONE] == "true" }

    suspend fun setAccent(context: Context, accent: AppAccent) {
        context.dataStore.edit { it[KEY_ACCENT] = accent.name }
    }

    suspend fun setCustomHex(context: Context, hex: String) {
        context.dataStore.edit {
            it[KEY_ACCENT]     = AppAccent.CUSTOM.name
            it[KEY_CUSTOM_HEX] = hex
        }
    }

    suspend fun setSetupDone(context: Context, done: Boolean) {
        context.dataStore.edit { it[KEY_SETUP_DONE] = if (done) "true" else "false" }
    }
}
