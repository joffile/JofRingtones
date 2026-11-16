package com.jof.ringtones.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore by preferencesDataStore(name = "jof_ringtones")

class Prefs(private val context: Context) {

    private val favKey = stringSetPreferencesKey("favorites")
    private fun copyKey(sourceId: Long, kind: RingtoneKind) =
        stringPreferencesKey("copy_${sourceId}_${kind.name}")

    private val data = context.dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val favorites: Flow<Set<Long>> = data.map { prefs ->
        (prefs[favKey] ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet()
    }

    suspend fun toggleFavorite(id: Long) {
        context.dataStore.edit { prefs ->
            val current = prefs[favKey] ?: emptySet()
            val key = id.toString()
            prefs[favKey] = if (key in current) current - key else current + key
        }
    }

    suspend fun getCopy(sourceId: Long, kind: RingtoneKind): Long? =
        data.first()[copyKey(sourceId, kind)]?.toLongOrNull()

    suspend fun putCopy(sourceId: Long, kind: RingtoneKind, newId: Long) {
        context.dataStore.edit { it[copyKey(sourceId, kind)] = newId.toString() }
    }
}
