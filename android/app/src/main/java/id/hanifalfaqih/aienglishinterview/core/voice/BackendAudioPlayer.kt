package id.hanifalfaqih.aienglishinterview.core.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Fixed playback contract matching the backend voice-turn audio payload. */
const val PLAYBACK_SAMPLE_RATE_HZ = 24000
const val PLAYBACK_MAX_BYTES = 8 * 1024 * 1024

/** Minimal AudioTrack seam; faked in JVM tests, framework on device. */
interface AudioTrackDriver {
    fun start(pcm: ByteArray, listener: PlaybackListener)
    fun stop()
    fun release()
}

interface PlaybackListener {
    fun onDone()
    fun onError(message: String)
}

class AndroidAudioTrackDriver : AudioTrackDriver {
    private var track: AudioTrack? = null
    private var listener: PlaybackListener? = null
    private var active = false

    override fun start(pcm: ByteArray, listener: PlaybackListener) {
        stop()
        this.listener = listener
        active = true
        try {
            val minBytes = AudioTrack.getMinBufferSize(
                PLAYBACK_SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBytes <= 0) {
                fail("Audio output is not available on this device.")
                return
            }
            val frames = pcm.size / 2
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(PLAYBACK_SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBytes, pcm.size))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(track: AudioTrack?) {
                        if (!active) return
                        active = false
                        listener.onDone()
                    }

                    override fun onPeriodicNotification(track: AudioTrack?) = Unit
                },
            )
            val written = track.write(pcm, 0, pcm.size)
            if (written < 0 || written < pcm.size) {
                fail("Audio output failed to start.")
                try {
                    track.release()
                } catch (ignored: Exception) {
                    // Best effort.
                }
                return
            }
            track.setNotificationMarkerPosition(frames)
            this.track = track
            track.play()
        } catch (e: Exception) {
            fail("Audio output failed to start.")
        }
    }

    override fun stop() {
        active = false
        try {
            track?.stop()
            track?.flush()
        } catch (ignored: IllegalStateException) {
            // Already stopped; release below only on release().
        }
    }

    override fun release() {
        active = false
        listener = null
        try {
            track?.release()
        } catch (ignored: Exception) {
            // Best effort.
        }
        track = null
    }

    private fun fail(message: String) {
        active = false
        listener?.onError(message)
    }
}

/**
 * Production [VoiceSynthesizer] for backend-provided interviewer audio:
 * plays complete PCM payloads via [AudioTrack]. No streaming, no network,
 * no credentials — bytes arrive inside the voice-turn HTTP response.
 * Text-only [speak] calls (no audio) are an error here; the ViewModel uses
 * the platform synthesizer fallback for those.
 */
class BackendAudioPlayer(
    private val driver: AudioTrackDriver = AndroidAudioTrackDriver(),
) : VoiceSynthesizer {

    private val _events = MutableSharedFlow<SynthesisEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<SynthesisEvent> = _events.asSharedFlow()

    @Volatile
    private var released = false

    @Volatile
    private var speaking = false

    override fun speak(text: String, audio: ByteArray?) {
        if (released) return
        if (audio == null || audio.isEmpty()) {
            _events.tryEmit(SynthesisEvent.Error("No voice audio received."))
            return
        }
        if (audio.size > PLAYBACK_MAX_BYTES) {
            _events.tryEmit(SynthesisEvent.Error("Voice audio is too large to play."))
            return
        }
        stop()
        speaking = true
        driver.start(audio, object : PlaybackListener {
            override fun onDone() {
                speaking = false
                if (!released) {
                    _events.tryEmit(SynthesisEvent.Done)
                }
            }

            override fun onError(message: String) {
                speaking = false
                if (!released) {
                    _events.tryEmit(SynthesisEvent.Error(message))
                }
            }
        })
    }

    override fun stop() {
        speaking = false
        try {
            driver.stop()
        } catch (ignored: Exception) {
            // Best effort.
        }
    }

    /** Idempotent; after release no events are emitted. */
    override fun release() {
        if (released) return
        released = true
        speaking = false
        try {
            driver.release()
        } catch (ignored: Exception) {
            // Best effort.
        }
    }
}
