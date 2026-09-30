package id.hanifalfaqih.aienglishinterview.core.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Fixed capture contract shared by interview voice input: 16 kHz mono
 * 16-bit PCM, recorded with non-blocking reads so stop, silence, cap, and
 * cancellation checks always execute.
 */

const val STT_SAMPLE_RATE_HZ = 16000
internal const val STT_MAX_DURATION_MS = 55_000L
internal const val STT_CHUNK_SHORTS = 1600 // 100 ms at 16 kHz
internal const val STT_SPEECH_RMS_THRESHOLD = 250.0
internal const val STT_MIN_SPEECH_MS = 500L
internal const val STT_END_SILENCE_MS = 4_000L
internal const val READ_POLL_DELAY_MS = 15L

/** Microphone boundary. Replaced by scripted fakes in tests. */
interface AudioCapture {
    /** @return false when the microphone cannot be opened. */
    fun open(): Boolean

    /**
     * Fill [buffer] with PCM samples.
     * @return shorts written, 0 when no data is momentarily available,
     * negative on end/error.
     */
    fun read(buffer: ShortArray): Int
    fun close()
}

class AndroidAudioCapture : AudioCapture {
    private var recorder: AudioRecord? = null

    override fun open(): Boolean {
        return try {
            val minBytes = AudioRecord.getMinBufferSize(
                STT_SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBytes <= 0) return false
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                STT_SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                // Headroom over the minimum so the read loop never starves.
                maxOf(minBytes * 2, STT_SAMPLE_RATE_HZ * 2 * 2),
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return false
            }
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                recorder.release()
                return false
            }
            this.recorder = recorder
            true
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        } catch (e: UnsupportedOperationException) {
            false
        }
    }

    override fun read(buffer: ShortArray): Int {
        return try {
            // Non-blocking: returns immediately with 0 when no data is
            // available yet. A blocking read can park the recording thread
            // indefinitely (observed on the emulator), starving stop,
            // silence, cap, and cancellation checks — so blocking is never
            // used here regardless of device behavior.
            recorder?.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING) ?: -1
        } catch (e: IllegalStateException) {
            -1
        }
    }

    override fun close() {
        try {
            recorder?.stop()
        } catch (ignored: IllegalStateException) {
            // Already stopped; release below.
        } finally {
            try {
                recorder?.release()
            } catch (ignored: Exception) {
                // Best effort on a best-effort path.
            }
            recorder = null
        }
    }
}
