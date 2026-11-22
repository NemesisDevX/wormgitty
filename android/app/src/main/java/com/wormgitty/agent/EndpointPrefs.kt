package com.wormgitty.agent

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * Persisted backend-endpoint override set from StatusActivity. Empty = the
 * build-time [BuildConfig.WG_BASE_URL] default wins.
 */
private val Context.endpointDataStore by preferencesDataStore(name = EndpointPrefs.STORE_NAME)

class EndpointPrefs(context: Context) {

    private val ds = context.applicationContext.endpointDataStore

    suspend fun override(): String? = ds.data.first()[OVERRIDE]?.takeIf { it.isNotBlank() }

    suspend fun setOverride(value: String?) {
        ds.edit { it[OVERRIDE] = value?.trim().orEmpty() }
    }

    /** Applies the persisted override (or build default) to [BackendClient]. */
    suspend fun apply() {
        BackendClient.baseUrl = override() ?: BuildConfig.WG_BASE_URL
    }

    companion object {
        const val STORE_NAME = "endpoint_prefs"
        val OVERRIDE = stringPreferencesKey("endpoint_override")
    }
}
