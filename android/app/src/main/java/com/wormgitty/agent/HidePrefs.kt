package com.wormgitty.agent

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * Single persisted bit: set when the user taps lock, consumed on the next
 * launch, which is what performs the hide-and-vanish sequence.
 */

// Top-level delegate = exactly one DataStore instance per process/file.
private val Context.hideDataStore by preferencesDataStore(name = HidePrefs.STORE_NAME)

class HidePrefs(context: Context) {

    private val ds = context.applicationContext.hideDataStore

    suspend fun pendingHide(): Boolean = ds.data.first()[PENDING_HIDE] == true

    suspend fun setPendingHide(value: Boolean) {
        ds.edit { it[PENDING_HIDE] = value }
    }

    suspend fun clear() = setPendingHide(false)

    companion object {
        const val STORE_NAME = "hide_prefs"
        val PENDING_HIDE = booleanPreferencesKey("pending_hide")
    }
}
