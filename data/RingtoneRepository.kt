package com.jof.ringtones.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.media.MediaScannerConnection
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.jof.ringtones.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.resume

class RingtoneRepository(
    private val context: Context,
    private val prefs: Prefs
) {
    private val resolver get() = context.contentResolver
    private val audioUri: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    // ---------- Чтение медиатеки ----------

    suspend fun load(): List<Ringtone> = withContext(Dispatchers.IO) {
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.IS_RINGTONE)
            add(MediaStore.Audio.Media.IS_NOTIFICATION)
            add(MediaStore.Audio.Media.IS_ALARM)
            add(MediaStore.Audio.Media.DATE_ADDED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.OWNER_PACKAGE_NAME)
            }
        }.toTypedArray()

        val selection = "${MediaStore.Audio.Media.DURATION} > 0 AND (" +
                "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR " +
                "${MediaStore.Audio.Media.IS_RINGTONE} != 0 OR " +
                "${MediaStore.Audio.Media.IS_NOTIFICATION} != 0 OR " +
                "${MediaStore.Audio.Media.IS_ALARM} != 0)"

        val sort = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        val result = ArrayList<Ringtone>()

        resolver.query(audioUri, projection, selection, null, sort)?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val ringIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_RINGTONE)
            val notifIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_NOTIFICATION)
            val alarmIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_ALARM)
            val dateIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val ownerIdx = c.getColumnIndex(MediaStore.Audio.Media.OWNER_PACKAGE_NAME)

            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                result += Ringtone(
                    id = id,
                    uri = ContentUris.withAppendedId(audioUri, id),
                    title = c.getString(titleIdx) ?: "Без названия",
                    artist = c.getString(artistIdx)?.takeIf { it.isNotBlank() && it != "<unknown>" }
                        ?: context.getString(R.string.unknown_artist),
                    durationMs = c.getLong(durIdx),
                    sizeBytes = c.getLong(sizeIdx),
                    isRingtone = c.getInt(ringIdx) != 0,
                    isNotification = c.getInt(notifIdx) != 0,
                    isAlarm = c.getInt(alarmIdx) != 0,
                    owner = if (ownerIdx >= 0) c.getString(ownerIdx) else null,
                    dateAdded = c.getLong(dateIdx)
                )
            }
        }
        result
    }

    private fun queryOne(uri: Uri): Ringtone? {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.IS_RINGTONE,
            MediaStore.Audio.Media.IS_NOTIFICATION, MediaStore.Audio.Media.IS_ALARM,
            MediaStore.Audio.Media.DATE_ADDED
        )
        resolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                return Ringtone(
                    id = id,
                    uri = ContentUris.withAppendedId(audioUri, id),
                    title = c.getString(1) ?: "",
                    artist = c.getString(2) ?: "",
                    durationMs = c.getLong(3),
                    sizeBytes = c.getLong(4),
                    isRingtone = c.getInt(5) != 0,
                    isNotification = c.getInt(6) != 0,
                    isAlarm = c.getInt(7) != 0,
                    owner = context.packageName,
                    dateAdded = c.getLong(8)
                )
            }
        }
        return null
    }

    private fun exists(uri: Uri): Boolean =
        runCatching {
            resolver.query(uri, arrayOf(MediaStore.Audio.Media._ID), null, null, null)
                ?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)

    // ---------- Импорт внешнего файла ----------

    suspend fun import(source: Uri, kind: RingtoneKind = RingtoneKind.RINGTONE): Result<Ringtone> =
        withContext(Dispatchers.IO) {
            runCatching {
                val rawName = displayName(source) ?: "ringtone_${System.currentTimeMillis()}"
                val mime = resolver.getType(source) ?: "audio/mpeg"
                val name = safeFileName(rawName, mime)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                        put(MediaStore.Audio.Media.TITLE, name.substringBeforeLast('.'))
                        put(MediaStore.Audio.Media.MIME_TYPE, mime)
                        put(MediaStore.Audio.Media.RELATIVE_PATH, kind.directory)
                        put(kind.column, 1)
                        put(MediaStore.Audio.Media.IS_PENDING, 1)
                    }
                    val target = resolver.insert(audioUri, values)
                        ?: throw IOException(context.getString(R.string.import_failed))
                    try {
                        copy(source, target)
                    } catch (t: Throwable) {
                        runCatching { resolver.delete(target, null, null) }
                        throw t
                    }
                    values.clear()
                    values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                    resolver.update(target, values, null, null)
                    queryOne(target) ?: throw IOException(context.getString(R.string.import_failed))
                } else {
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(kind.directory)
                        .apply { if (!exists()) mkdirs() }
                    var file = File(dir, name)
                    var index = 1
                    val base = name.substringBeforeLast('.')
                    val ext = name.substringAfterLast('.', "mp3")
                    while (file.exists()) file = File(dir, "${base}_$index.$ext").also { index++ }

                    resolver.openInputStream(source)?.use { input ->
                        FileOutputStream(file).use { output -> input.copyTo(output) }
                    } ?: throw IOException(context.getString(R.string.import_failed))

                    val scanned = scan(file, mime)
                    queryOne(scanned) ?: throw IOException(context.getString(R.string.import_failed))
                }
            }
        }

    private fun copy(source: Uri, target: Uri) {
        val input = resolver.openInputStream(source)
            ?: throw IOException(context.getString(R.string.error_open_file))
        val output = resolver.openOutputStream(target)
            ?: throw IOException(context.getString(R.string.import_failed))
        input.use { i -> output.use { o -> i.copyTo(o) } }
    }

    private suspend fun scan(file: File, mime: String): Uri = suspendCancellableCoroutine { cont ->
        MediaScannerConnection.scanFile(
            context, arrayOf(file.absolutePath), arrayOf(mime)
        ) { _, uri -> if (cont.isActive) cont.resume(uri ?: Uri.fromFile(file)) }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun safeFileName(raw: String, mime: String): String {
        val cleaned = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-zА-Яа-я0-9 ._\\-()]"), "_")
            .trim()
            .ifBlank { "ringtone_${System.currentTimeMillis()}" }
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "mp3"
        return if (cleaned.substringAfterLast('.', "").length in 2..4) cleaned else "$cleaned.$ext"
    }

    // ---------- Подготовка URI для установки ----------

    /**
     * На Android 10+ файл чужого владельца нельзя пометить флагом IS_RINGTONE,
     * а часть OEM требует, чтобы мелодия лежала в коллекции Ringtones.
     * Поэтому создаём собственную копию (один раз, с кэшированием).
     */
    suspend fun ensureSettable(item: Ringtone, kind: RingtoneKind): Uri = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext item.uri
        if (item.hasFlag(kind) || item.owner == context.packageName) return@withContext item.uri

        prefs.getCopy(item.id, kind)?.let { cachedId ->
            val cached = ContentUris.withAppendedId(audioUri, cachedId)
            if (exists(cached)) return@withContext cached
        }

        val mime = resolver.getType(item.uri) ?: "audio/mpeg"
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "mp3"
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "${item.title.take(60)}.$ext")
            put(MediaStore.Audio.Media.TITLE, item.title)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, kind.directory)
            put(kind.column, 1)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val target = resolver.insert(audioUri, values)
            ?: throw IOException(context.getString(R.string.error_apply))
        try {
            copy(item.uri, target)
        } catch (t: Throwable) {
            runCatching { resolver.delete(target, null, null) }
            throw t
        }
        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        resolver.update(target, values, null, null)
        prefs.putCopy(item.id, kind, ContentUris.parseId(target))
        target
    }

    fun setDefault(kind: RingtoneKind, uri: Uri) {
        RingtoneManager.setActualDefaultRingtoneUri(context, kind.type, uri)
    }

    fun currentDefaultId(kind: RingtoneKind): Long? = runCatching {
        RingtoneManager.getActualDefaultRingtoneUri(context, kind.type)
            ?.let { ContentUris.parseId(it) }
    }.getOrNull()

    // ---------- Удаление ----------

    suspend fun delete(item: Ringtone): DeleteResult = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && item.owner != context.packageName) {
                DeleteResult.NeedsConfirmation(
                    MediaStore.createDeleteRequest(resolver, listOf(item.uri)).intentSender
                )
            } else {
                val rows = resolver.delete(item.uri, null, null)
                if (rows > 0) DeleteResult.Deleted
                else DeleteResult.Failed(context.getString(R.string.delete_failed))
            }
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is android.app.RecoverableSecurityException) {
                DeleteResult.NeedsConfirmation(e.userAction.actionIntent.intentSender)
            } else {
                DeleteResult.Failed(context.getString(R.string.delete_failed))
            }
        } catch (e: Exception) {
            DeleteResult.Failed(context.getString(R.string.delete_failed))
        }
    }

    // ---------- Контакты ----------

    suspend fun setContactRingtone(contactUri: Uri, ringtoneUri: Uri): String =
        withContext(Dispatchers.IO) {
            var id: Long? = null
            var name = ""
            resolver.query(
                contactUri,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) { id = c.getLong(0); name = c.getString(1) ?: "" }
            }
            val contactId = id ?: throw IOException(context.getString(R.string.contact_failed))
            val target = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
            val values = ContentValues().apply {
                put(ContactsContract.Contacts.CUSTOM_RINGTONE, ringtoneUri.toString())
            }
            val rows = resolver.update(target, values, null, null)
            if (rows == 0) {
                val saved = resolver.query(
                    target, arrayOf(ContactsContract.Contacts.CUSTOM_RINGTONE), null, null, null
                )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                if (saved != ringtoneUri.toString()) {
                    throw IOException(context.getString(R.string.contact_failed))
                }
            }
            name
        }
}
