package com.janus.app.core

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.os.Build
import android.util.Log
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class JanusAudioBridge(private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var recordThread: Thread? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    private val isRunning = AtomicBoolean(false)

    private val sampleRate = 16000
    private val audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION
    private val channelConfigIn = AudioFormat.CHANNEL_IN_MONO
    private val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val bufferSizeIn = AudioRecord.getMinBufferSize(sampleRate, channelConfigIn, audioFormat)
    private val bufferSizeOut = AudioTrack.getMinBufferSize(sampleRate, channelConfigOut, audioFormat)

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start() {
        if (isRunning.get()) {
            Log.w("JanusAudioBridge", "AudioBridge already running")
            return
        }

        Log.d("JanusAudioBridge", "Starting JanusAudioBridge...")

        // Verify Record Audio permission
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.e("JanusAudioBridge", "Cannot start AudioBridge: RECORD_AUDIO permission not granted")
            return
        }

        try {
            // MOB-05 FIX: Force Audio HAL into VoIP Communication Mode for proper mic capture
            try {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = true
            } catch (e: Exception) {
                Log.w("JanusAudioBridge", "Failed to set audioManager mode", e)
            }

            // 1. Initialize AudioRecord (Android -> Mac Call Audio)
            audioRecord = AudioRecord(
                audioSource,
                sampleRate,
                channelConfigIn,
                audioFormat,
                bufferSizeIn.coerceAtLeast(3200)
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("JanusAudioBridge", "AudioRecord failed to initialize")
                release()
                return
            }

            // 2. Initialize AudioTrack (Mac Mic -> Android Call Injection)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val audioFormatSpec = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfigOut)
                .setEncoding(audioFormat)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormatSpec)
                .setBufferSizeInBytes(bufferSizeOut.coerceAtLeast(3200))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e("JanusAudioBridge", "AudioTrack failed to initialize")
                release()
                return
            }

            isRunning.set(true)
            audioRecord?.startRecording()
            audioTrack?.play()

            // Start recording and streaming thread
            recordThread = Thread {
                val buffer = ByteArray(640) // 20ms of audio at 16kHz 16-bit Mono (16000 * 2 bytes * 0.02s = 640 bytes)
                while (isRunning.get()) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        // Binary WebSocket frame: 0x04 (Call Audio) + PCM payload
                        val payload = ByteArray(read + 1)
                        payload[0] = 0x04
                        System.arraycopy(buffer, 0, payload, 1, read)
                        JanusService.instance?.connectionManager?.sendBinary(payload)
                    } else if (read < 0) {
                        Log.e("JanusAudioBridge", "Error reading audio: $read")
                        break
                    }
                }
            }.apply {
                name = "JanusAudioRecordThread"
                priority = Thread.MAX_PRIORITY
                start()
            }

            Log.d("JanusAudioBridge", "AudioBridge started successfully")

        } catch (e: Exception) {
            Log.e("JanusAudioBridge", "Failed to start AudioBridge", e)
            release()
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning.get()) {
            return
        }
        Log.d("JanusAudioBridge", "Stopping JanusAudioBridge...")
        isRunning.set(false)
        
        recordThread?.interrupt()
        recordThread = null

        release()
        Log.d("JanusAudioBridge", "AudioBridge stopped")
    }

    fun writeAudio(data: ByteArray) {
        if (isRunning.get()) {
            audioTrack?.write(data, 0, data.size)
        }
    }

    private fun release() {
        // MOB-05 FIX: Always reset audioManager mode to MODE_NORMAL to avoid breaking system audio
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            Log.w("JanusAudioBridge", "Failed to reset audioManager mode", e)
        }

        try {
            audioRecord?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e("JanusAudioBridge", "Error releasing AudioRecord", e)
        } finally {
            audioRecord = null
        }

        try {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e("JanusAudioBridge", "Error releasing AudioTrack", e)
        } finally {
            audioTrack = null
        }
    }
}
