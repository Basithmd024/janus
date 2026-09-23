package com.janus.app.core

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream

/**
 * Manages active media playback sessions (Spotify, YouTube Music, Apple Music, etc.),
 * mirrors Now Playing metadata and artwork to connected Mac, and handles remote transport controls.
 */
class JanusMediaSessionManager(
    private val context: Context,
    private val listenerComponent: ComponentName? = null
) {
    companion object {
        private const val TAG = "JanusMediaSession"
        var instance: JanusMediaSessionManager? = null
            private set
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var activeController: MediaController? = null
    private var lastSentState: JsonObject? = null
    private var isListening = false

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateActiveController(controllers)
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            broadcastCurrentPlaybackState()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            broadcastCurrentPlaybackState()
        }

        override fun onSessionDestroyed() {
            activeController = null
            refreshActiveSessions()
        }
    }

    init {
        instance = this
    }

    fun startListening() {
        if (isListening) return
        try {
            if (mediaSessionManager != null && listenerComponent != null) {
                mediaSessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, listenerComponent)
                isListening = true
                refreshActiveSessions()
                Log.d(TAG, "MediaSessionManager listener registered successfully")
            } else {
                Log.w(TAG, "Cannot listen to media sessions: listenerComponent is null")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification listener permission needed for MediaSession tracking", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting MediaSession listener", e)
        }
    }

    fun stopListening() {
        if (!isListening) return
        try {
            activeController?.unregisterCallback(controllerCallback)
            activeController = null
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
            isListening = false
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaSession listener", e)
        }
    }

    fun refreshActiveSessions() {
        try {
            if (mediaSessionManager != null && listenerComponent != null) {
                val controllers = mediaSessionManager.getActiveSessions(listenerComponent)
                updateActiveController(controllers)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to get active media sessions: ${e.message}")
        }
    }

    private fun updateActiveController(controllers: List<MediaController>?) {
        val newController = controllers?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers?.firstOrNull()

        if (activeController?.sessionToken != newController?.sessionToken) {
            try {
                activeController?.unregisterCallback(controllerCallback)
            } catch (_: Exception) {}

            activeController = newController
            activeController?.registerCallback(controllerCallback, mainHandler)
            Log.d(TAG, "Active media controller updated: ${activeController?.packageName}")
        }

        broadcastCurrentPlaybackState()
    }

    fun broadcastCurrentPlaybackState() {
        val controller = activeController
        val payload = JsonObject()

        val currentVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
        val volPct = if (maxVol > 0) ((currentVol.toFloat() / maxVol) * 100).toInt() else 50
        payload.addProperty("volume", volPct)

        if (controller != null) {
            val metadata = controller.metadata
            val state = controller.playbackState

            val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: ""
            val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_AUTHOR)
                ?: ""
            val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
            val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            val position = state?.position ?: 0L
            val isPlaying = state?.state == PlaybackState.STATE_PLAYING

            val appName = try {
                val pm = context.packageManager
                val info = pm.getApplicationInfo(controller.packageName, 0)
                pm.getApplicationLabel(info).toString()
            } catch (_: Exception) {
                controller.packageName.substringAfterLast('.')
            }

            payload.addProperty("has_media", title.isNotBlank() || isPlaying)
            payload.addProperty("title", title)
            payload.addProperty("artist", artist)
            payload.addProperty("album", album)
            payload.addProperty("duration_ms", duration)
            payload.addProperty("position_ms", position)
            payload.addProperty("is_playing", isPlaying)
            payload.addProperty("package_name", controller.packageName)
            payload.addProperty("app_name", appName)

            // Artwork
            val artBitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            if (artBitmap != null) {
                val artBase64 = encodeBitmapToBase64(artBitmap)
                if (artBase64 != null) {
                    payload.addProperty("album_art", artBase64)
                }
            }
        } else {
            payload.addProperty("has_media", false)
            payload.addProperty("is_playing", false)
            payload.addProperty("title", "")
            payload.addProperty("artist", "")
        }

        val packet = Packet(
            type = "media.player.state",
            id = java.util.UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis() / 1000,
            payload = payload
        )

        JanusService.instance?.connectionManager?.sendPacket(packet)
    }

    fun handleCommand(action: String, value: Double? = null) {
        val controller = activeController
        val transport = controller?.transportControls
        val isPlaying = controller?.playbackState?.state == PlaybackState.STATE_PLAYING

        Log.d(TAG, "Executing media action: $action with value: $value (controller: ${controller?.packageName})")

        when (action.lowercase()) {
            "play" -> {
                if (transport != null) {
                    transport.play()
                } else {
                    dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                }
            }
            "pause" -> {
                if (transport != null) {
                    transport.pause()
                } else {
                    dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                }
            }
            "play_pause", "toggle" -> {
                if (transport != null) {
                    if (isPlaying) transport.pause() else transport.play()
                } else {
                    dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                }
            }
            "next", "skip_next" -> {
                if (transport != null) {
                    transport.skipToNext()
                } else {
                    dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                }
            }
            "prev", "previous", "skip_prev" -> {
                if (transport != null) {
                    transport.skipToPrevious()
                } else {
                    dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                }
            }
            "seek" -> {
                val posMs = value?.toLong() ?: 0L
                transport?.seekTo(posMs)
            }
            "volume" -> {
                if (value != null && audioManager != null) {
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val targetVol = ((value.coerceIn(0.0, 100.0) / 100.0) * maxVol).toInt()
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                }
            }
            "volume_up" -> {
                audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
            }
            "volume_down" -> {
                audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
            }
            "volume_mute", "mute" -> {
                audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
            }
        }

        // Broadcast updated state shortly after executing
        mainHandler.postDelayed({
            broadcastCurrentPlaybackState()
        }, 250L)
    }

    private fun dispatchMediaKey(keyCode: Int) {
        try {
            audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch media key $keyCode", e)
        }
    }

    private fun encodeBitmapToBase64(bitmap: Bitmap): String? {
        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, 96, 96, true)
            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 75, stream)
            val bytes = stream.toByteArray()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }
}
