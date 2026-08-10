package com.s2s.demo.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

class AudioPlaybackManager(
    private val sampleRate: Int = 16000
) {
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var playbackThread: Thread? = null
    private val audioQueue = ConcurrentLinkedQueue<FloatArray>()

    var onPlaybackStarted: (() -> Unit)? = null
    var onPlaybackFinished: (() -> Unit)? = null

    init {
        initAudioTrack()
    }

    private fun initAudioTrack() {
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    fun start() {
        if (isPlaying) return
        if (audioTrack == null) {
            initAudioTrack()
        }
        isPlaying = true
        audioTrack?.play()

        playbackThread = thread(start = true, name = "S2SAudioPlaybackThread") {
            var wasActive = false
            while (isPlaying) {
                val chunk = audioQueue.poll()
                if (chunk != null) {
                    if (!wasActive) {
                        wasActive = true
                        onPlaybackStarted?.invoke()
                    }
                    audioTrack?.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                } else {
                    if (wasActive && audioQueue.isEmpty()) {
                        wasActive = false
                        onPlaybackFinished?.invoke()
                    }
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
    }

    fun queueAudio(samples: FloatArray) {
        Log.d("S2S_AUDIO", "queueAudio: ${samples.size} samples, queueSize=${audioQueue.size}, isPlaying=$isPlaying, trackState=${audioTrack?.playState}")
        audioQueue.offer(samples)
    }

    fun flushAndInterrupt() {
        audioQueue.clear()
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        onPlaybackFinished?.invoke()
    }

    fun isSpeaking(): Boolean {
        return !audioQueue.isEmpty()
    }

    fun release() {
        isPlaying = false
        playbackThread?.interrupt()
        playbackThread?.join(300)
        audioQueue.clear()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }
}
