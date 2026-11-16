package com.jof.ringtones.data

import android.media.RingtoneManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

data class Ringtone(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val isRingtone: Boolean,
    val isNotification: Boolean,
    val isAlarm: Boolean,
    val owner: String?,
    val dateAdded: Long
) {
    fun hasFlag(kind: RingtoneKind): Boolean = when (kind) {
        RingtoneKind.RINGTONE -> isRingtone
        RingtoneKind.NOTIFICATION -> isNotification
        RingtoneKind.ALARM -> isAlarm
    }
}

enum class RingtoneKind(val type: Int, val directory: String, val column: String) {
    RINGTONE(RingtoneManager.TYPE_RINGTONE, Environment.DIRECTORY_RINGTONES, MediaStore.Audio.Media.IS_RINGTONE),
    NOTIFICATION(RingtoneManager.TYPE_NOTIFICATION, Environment.DIRECTORY_NOTIFICATIONS, MediaStore.Audio.Media.IS_NOTIFICATION),
    ALARM(RingtoneManager.TYPE_ALARM, Environment.DIRECTORY_ALARMS, MediaStore.Audio.Media.IS_ALARM)
}

data class PlayerState(
    val id: Long? = null,
    val title: String = "",
    val isPlaying: Boolean = false,
    val isPreparing: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0
)

sealed interface DeleteResult {
    data object Deleted : DeleteResult
    data class NeedsConfirmation(val intent: android.content.IntentSender) : DeleteResult
    data class Failed(val message: String) : DeleteResult
}
