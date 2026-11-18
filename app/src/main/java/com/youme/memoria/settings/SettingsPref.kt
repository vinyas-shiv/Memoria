package com.youme.memoria.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "general_settings")

enum class IndexingSpeed(val storageKey: String, val label: String) {
    ECO("eco", "Eco"),
    BALANCED("balanced", "Balanced"),
    FAST("fast", "Fast");

    companion object {
        fun fromKey(raw: String?): IndexingSpeed {
            return values().firstOrNull { it.storageKey == raw } ?: BALANCED
        }
    }
}

object SettingsPrefs {
    val IndicatorStatus = booleanPreferencesKey("indicator_status")
    val IndexingSpeedKey = stringPreferencesKey("indexing_speed")
    val ChargingCooldownKey = booleanPreferencesKey("charging_cooldown")

    suspend fun setIndicatorStatus(context: Context, status: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[IndicatorStatus] = status
        }
    }

    fun getIndicatorStatus(context: Context): Flow<Boolean> {
        return context.dataStore.data.map { prefs ->
            prefs[IndicatorStatus] ?: false
        }
    }

    suspend fun setIndexingSpeed(context: Context, speed: IndexingSpeed) {
        context.dataStore.edit { prefs ->
            prefs[IndexingSpeedKey] = speed.storageKey
        }
    }

    fun getIndexingSpeed(context: Context): Flow<IndexingSpeed> {
        return context.dataStore.data.map { prefs ->
            IndexingSpeed.fromKey(prefs[IndexingSpeedKey])
        }
    }

    suspend fun setChargingCooldown(context: Context, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ChargingCooldownKey] = enabled
        }
    }

    fun getChargingCooldown(context: Context): Flow<Boolean> {
        return context.dataStore.data.map { prefs ->
            prefs[ChargingCooldownKey] ?: true
        }
    }
}
