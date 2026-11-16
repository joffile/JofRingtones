package com.jof.ringtones.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import androidx.core.content.ContextCompat
import com.jof.ringtones.R
import com.jof.ringtones.data.PlayerState
import com.jof.ringtones.data.Ringtone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlayerController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var mediaPlayer: MediaPlayer? = null
    private var prepared = false
    private var tickerJob: Job? = null
    private var focusRequest: AudioFocusRequest? = null

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> pause()
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    init {
        ContextCompat.registerReceiver(
            context, noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun toggle(item: Ringtone) {
        val current = _state.value
        if (current.id == item.id && mediaPlayer != null) {
            if (current.isPlaying) pause() else resume()
        } else {
            start(item)
        }
    }

    private fun start(item: Ringtone) {
        stopInternal()
        if (!requestFocus()) {
            messages.tryEmit(context.getString(R.string.error_audio_focus))
            return
        }
        _state.value = PlayerState(id = item.id, title = item.title, isPreparing = true)
        mediaPlayer = try {
            MediaPlayer().apply {
                setAudioAttributes(attributes)
                setDataSource(context, item.uri)
                setOnPreparedListener { mp ->
                    prepared = true
                    runCatching { mp.start() }
                    _state.update {
                        it.copy(
                            isPreparing = false,
                            isPlaying = true,
                            durationMs = runCatching { mp.duration }.getOrDefault(0).coerceAtLeast(0)
                        )
                    }
                    startTicker()
                }
                setOnCompletionListener { mp ->
                    stopTicker()
                    runCatching { mp.seekTo(0) }
                    _state.update { it.copy(isPlaying = false, positionMs = 0) }
                    abandonFocus()
                }
                setOnErrorListener { _, what, extra ->
                    stopTicker()
                    messages.tryEmit(context.getString(R.string.error_playback, what, extra))
                    _state.value = PlayerState()
                    releasePlayer()
                    abandonFocus()
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            messages.tryEmit(context.getString(R.string.error_open_file))
            _state.value = PlayerState()
            releasePlayer()
            abandonFocus()
            null
        }
    }

    fun pause() {
        val mp = mediaPlayer ?: return
        if (prepared) runCatching { if (mp.isPlaying) mp.pause() }
        stopTicker()
        _state.update { it.copy(isPlaying = false) }
    }

    fun resume() {
        val mp = mediaPlayer ?: return
        if (!prepared || !requestFocus()) return
        runCatching { mp.start() }
        _state.update { it.copy(isPlaying = true) }
        startTicker()
    }

    fun seekTo(positionMs: Int) {
        if (prepared) runCatching { mediaPlayer?.seekTo(positionMs) }
        _state.update { it.copy(positionMs = positionMs) }
    }

    fun stop() {
        stopInternal()
        _state.value = PlayerState()
    }

    fun release() {
        stopInternal()
        runCatching { context.unregisterReceiver(noisyReceiver) }
        scope.cancel()
    }

    private fun stopInternal() {
        stopTicker()
        releasePlayer()
        abandonFocus()
    }

    private fun releasePlayer() {
        mediaPlayer?.let { mp ->
            runCatching {
                mp.setOnPreparedListener(null)
                mp.setOnCompletionListener(null)
                mp.setOnErrorListener(null)
            }
            runCatching { mp.reset() }
            runCatching { mp.release() }
        }
        mediaPlayer = null
        prepared = false
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                val mp = mediaPlayer
                if (prepared && mp != null) {
                    val position = runCatching { mp.currentPosition }.getOrDefault(0)
                    _state.update { it.copy(positionMs = position) }
                }
                delay(250)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun requestFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }
}
