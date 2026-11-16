package com.jof.ringtones.ui

import android.app.Application
import android.content.IntentSender
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jof.ringtones.R
import com.jof.ringtones.data.DeleteResult
import com.jof.ringtones.data.Prefs
import com.jof.ringtones.data.Ringtone
import com.jof.ringtones.data.RingtoneKind
import com.jof.ringtones.data.RingtoneRepository
import com.jof.ringtones.player.PlayerController
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data object RequestWriteSettings : UiEvent
    data object RequestContactsPermission : UiEvent
    data object PickContact : UiEvent
    data class ConfirmDelete(val intent: IntentSender) : UiEvent
}

data class UiState(
    val loading: Boolean = true,
    val hasPermission: Boolean = false,
    val items: List<Ringtone> = emptyList(),
    val totalCount: Int = 0,
    val favorites: Set<Long> = emptySet(),
    val query: String = "",
    val favoritesOnly: Boolean = false,
    val defaultRingtoneId: Long? = null,
    val defaultNotificationId: Long? = null,
    val defaultAlarmId: Long? = null
)

class RingtonesViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val repository = RingtoneRepository(app, prefs)
    val player = PlayerController(app)

    private val items = MutableStateFlow<List<Ringtone>>(emptyList())
    private val loading = MutableStateFlow(false)
    private val permission = MutableStateFlow(Permissions.hasReadAudio(app))
    private val query = MutableStateFlow("")
    private val favoritesOnly = MutableStateFlow(false)
    private val defaults = MutableStateFlow<Map<RingtoneKind, Long?>>(emptyMap())

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    private data class Base(val items: List<Ringtone>, val loading: Boolean, val permission: Boolean)
    private data class Filters(val query: String, val favoritesOnly: Boolean)

    private val base = combine(items, loading, permission) { i, l, p -> Base(i, l, p) }
    private val filters = combine(query, favoritesOnly) { q, f -> Filters(q, f) }

    val ui: StateFlow<UiState> = combine(base, filters, prefs.favorites, defaults) { b, f, fav, def ->
        val filtered = b.items.filter { item ->
            (!f.favoritesOnly || item.id in fav) &&
                (f.query.isBlank() ||
                    item.title.contains(f.query, ignoreCase = true) ||
                    item.artist.contains(f.query, ignoreCase = true))
        }
        UiState(
            loading = b.loading,
            hasPermission = b.permission,
            items = filtered,
            totalCount = b.items.size,
            favorites = fav,
            query = f.query,
            favoritesOnly = f.favoritesOnly,
            defaultRingtoneId = def[RingtoneKind.RINGTONE],
            defaultNotificationId = def[RingtoneKind.NOTIFICATION],
            defaultAlarmId = def[RingtoneKind.ALARM]
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    private data class PendingApply(val item: Ringtone, val kind: RingtoneKind)
    private var pendingApply: PendingApply? = null
    private var contactItem: Ringtone? = null

    init {
        viewModelScope.launch { player.messages.collect { send(UiEvent.Message(it)) } }
        if (permission.value) refresh()
    }

    private fun str(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    private suspend fun send(event: UiEvent) = _events.send(event)

    fun setQuery(value: String) { query.value = value }
    fun setFavoritesOnly(value: Boolean) { favoritesOnly.value = value }
    fun toggleFavorite(id: Long) = viewModelScope.launch { prefs.toggleFavorite(id) }
    fun play(item: Ringtone) = player.toggle(item)

    fun onPermissionResult() {
        val granted = Permissions.hasReadAudio(getApplication())
        permission.value = granted
        if (granted) refresh()
    }

    fun refresh() {
        if (!permission.value) return
        viewModelScope.launch {
            loading.value = true
            runCatching { repository.load() }
                .onSuccess { items.value = it }
                .onFailure { send(UiEvent.Message(str(R.string.error_load))) }
            refreshDefaults()
            loading.value = false
        }
    }

    private fun refreshDefaults() {
        defaults.value = RingtoneKind.entries.associateWith { repository.currentDefaultId(it) }
    }

    fun onScreenResume() {
        val granted = Permissions.hasReadAudio(getApplication())
        if (granted != permission.value) {
            permission.value = granted
            if (granted) refresh()
        }
        val pending = pendingApply ?: return
        pendingApply = null
        if (Settings.System.canWrite(getApplication())) {
            applyAs(pending.item, pending.kind)
        } else {
            _events.trySend(UiEvent.Message(str(R.string.write_settings_denied)))
        }
    }

    fun applyAs(item: Ringtone, kind: RingtoneKind) {
        if (!Settings.System.canWrite(getApplication())) {
            pendingApply = PendingApply(item, kind)
            _events.trySend(UiEvent.RequestWriteSettings)
            return
        }
        viewModelScope.launch {
            runCatching {
                val uri = repository.ensureSettable(item, kind)
                RingtoneManager.setActualDefaultRingtoneUri(getApplication(), kind.type, uri)
            }.onSuccess {
                refreshDefaults()
                send(UiEvent.Message(str(R.string.applied, str(kind.label()))))
            }.onFailure {
                send(UiEvent.Message(str(R.string.error_apply)))
            }
        }
    }

    fun assignToContact(item: Ringtone) {
        contactItem = item
        if (Permissions.hasContacts(getApplication())) {
            _events.trySend(UiEvent.PickContact)
        } else {
            _events.trySend(UiEvent.RequestContactsPermission)
        }
    }

    fun onContactsPermission(granted: Boolean) {
        if (granted) {
            _events.trySend(UiEvent.PickContact)
        } else {
            contactItem = null
            _events.trySend(UiEvent.Message(str(R.string.contacts_denied)))
        }
    }

    fun onContactPickCancelled() { contactItem = null }

    fun onContactPicked(contactUri: Uri) {
        val item = contactItem ?: return
        contactItem = null
        viewModelScope.launch {
            runCatching {
                val ringtoneUri = repository.ensureSettable(item, RingtoneKind.RINGTONE)
                repository.setContactRingtone(contactUri, ringtoneUri)
            }.onSuccess { name ->
                send(UiEvent.Message(str(R.string.contact_assigned, name)))
            }.onFailure {
                send(UiEvent.Message(str(R.string.contact_failed)))
            }
        }
    }

    fun delete(item: Ringtone) {
        viewModelScope.launch {
            when (val result = repository.delete(item)) {
                is DeleteResult.Deleted -> {
                    if (player.state.value.id == item.id) player.stop()
                    refresh()
                    send(UiEvent.Message(str(R.string.deleted)))
                }
                is DeleteResult.NeedsConfirmation -> send(UiEvent.ConfirmDelete(result.intent))
                is DeleteResult.Failed -> send(UiEvent.Message(result.message))
            }
        }
    }

    fun onDeleteConfirmed() {
        player.stop()
        refresh()
        _events.trySend(UiEvent.Message(str(R.string.deleted)))
    }

    fun import(uri: Uri) {
        viewModelScope.launch {
            loading.value = true
            repository.import(uri)
                .onSuccess {
                    refresh()
                    send(UiEvent.Message(str(R.string.imported, it.title)))
                }
                .onFailure { send(UiEvent.Message(str(R.string.import_failed))) }
            loading.value = false
        }
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}

private fun RingtoneKind.label(): Int = when (this) {
    RingtoneKind.RINGTONE -> R.string.kind_ringtone
    RingtoneKind.NOTIFICATION -> R.string.kind_notification
    RingtoneKind.ALARM -> R.string.kind_alarm
}
