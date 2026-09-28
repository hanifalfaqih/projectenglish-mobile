package id.hanifalfaqih.aienglishinterview.core.voice

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Production [VoiceRecognizer]: captures microphone PCM with [AudioCapture]
 * (Android [AudioRecord] in production), applies a lightweight
 * speech-then-silence end detector, and transcribes the utterance with
 * [SttTransport] (Google Cloud STT synchronous REST in production).
 *
 * Emits at most one [RecognitionEvent.Final] per session; a [sessionId]
 * guard drops stale completions from superseded or cancelled sessions.
 * Synchronous REST yields no partials — none are fabricated.
 *
 * AudioRecord itself is the only untestable seam and sits behind
 * [AudioCapture]; orchestration (silence end, cap, cancellation,
 * error mapping) is fully deterministic under fakes.
 */
class GoogleCloudVoiceRecognizer(
    private val apiKey: String,
    private val captureFactory: () -> AudioCapture = { AndroidAudioCapture() },
    private val identity: AppIdentityProvider? = null,
    private val transportFactory: (String) -> SttTransport = { key ->
        GoogleSttTransport(key, identity = identity)
    },
    scope: CoroutineScope? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : VoiceRecognizer {

    private val ownedScope = scope == null
    private val scope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _events = MutableSharedFlow<RecognitionEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<RecognitionEvent> = _events.asSharedFlow()

    private var sessionJob: Job? = null
    private var sessionId = 0L
    private var finishRequested = false
    private var released = false

    @Synchronized
    override fun startListening() {
        if (released || sessionJob?.isActive == true) return
        if (apiKey.isBlank()) {
            _events.tryEmit(
                RecognitionEvent.Error(
                    "Speech recognition is not configured. Add a Google Cloud " +
                        "Speech API key (see docs/production-voice-setup.md).",
                ),
            )
            return
        }
        sessionId += 1
        finishRequested = false
        val id = sessionId
        sessionJob = scope.launch {
            runSession(id)
        }
    }

    /**
     * Graceful stop: the captured audio is still transcribed. Cancellation
     * (release, or a superseding session) discards it instead.
     */
    @Synchronized
    override fun stopListening() {
        finishRequested = true
    }

    /** Idempotent; after release the recognizer accepts no new sessions. */
    @Synchronized
    override fun release() {
        released = true
        sessionJob?.cancel()
        sessionJob = null
        if (ownedScope) {
            scope.cancel()
        }
    }

    private suspend fun runSession(id: Long) {
        val capture = captureFactory()
        try {
            if (!withContext(io) { capture.open() }) {
                emitIfCurrent(id, RecognitionEvent.Error(micUnavailableMessage()))
                return
            }
            val pcm = recordUtterance(id, capture) ?: return // superseded/cancelled
            if (pcm.isEmpty()) {
                emitIfCurrent(id, RecognitionEvent.Error("No speech detected. Try again."))
                return
            }
            val transport = transportFactory(apiKey)
            val outcome = try {
                transport.transcribe(pcm)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                SttOutcome.Failure("Speech recognition failed. Please try again.")
            }
            when (outcome) {
                is SttOutcome.Transcript -> emitIfCurrent(id, RecognitionEvent.Final(outcome.text))
                is SttOutcome.NoSpeech ->
                    emitIfCurrent(id, RecognitionEvent.Error("No speech detected. Try again."))
                is SttOutcome.Failure -> emitIfCurrent(id, RecognitionEvent.Error(outcome.message))
            }
        } finally {
            withContext(io) {
                try {
                    capture.close()
                } catch (ignored: Exception) {
                    // Best effort.
                }
            }
            clearSession(id)
        }
    }

    /**
     * Records until speech-then-silence, the 55 s cap, or cancellation.
     * Durations derive from sample counts, so behavior is deterministic
     * under scripted fakes. Returns null when superseded or cancelled.
     */
    private suspend fun recordUtterance(id: Long, capture: AudioCapture): ByteArray? {
        val out = ByteArrayOutputStream()
        val chunk = ShortArray(STT_CHUNK_SHORTS)
        var speechMs = 0L
        var silenceAfterSpeechMs = 0L
        var totalMs = 0L
        while (true) {
            if (!isCurrent(id)) return null
            if (isFinishRequested()) break
            val read = withContext(io) { capture.read(chunk) }
            if (read < 0) break
            if (read == 0) {
                // No data yet — poll again after a short pause. Zero is
                // never EOS/error: the loop must keep reaching its stop,
                // silence, cap, and cancellation checks. Cancellable, so
                // release works even mid-silence.
                delay(READ_POLL_DELAY_MS)
                continue
            }
            val rms = rms(chunk, read)
            val chunkMs = read * 1000L / STT_SAMPLE_RATE_HZ
            totalMs += chunkMs
            if (rms >= STT_SPEECH_RMS_THRESHOLD) {
                speechMs += chunkMs
                silenceAfterSpeechMs = 0
            } else if (speechMs >= STT_MIN_SPEECH_MS) {
                silenceAfterSpeechMs += chunkMs
            }
            writeLittleEndian(out, chunk, read)
            if (silenceAfterSpeechMs >= STT_END_SILENCE_MS) break
            if (totalMs >= STT_MAX_DURATION_MS) break
        }
        if (!isCurrent(id)) return null
        return out.toByteArray()
    }

    @Synchronized
    private fun isCurrent(id: Long): Boolean = id == sessionId && !released

    @Synchronized
    private fun isFinishRequested(): Boolean = finishRequested

    private fun emitIfCurrent(id: Long, event: RecognitionEvent) {
        var current = false
        synchronized(this) { current = isCurrent(id) }
        if (current) {
            _events.tryEmit(event)
        }
    }

    @Synchronized
    private fun clearSession(id: Long) {
        if (id == sessionId) {
            sessionJob = null
        }
    }

    companion object {
        internal fun rms(samples: ShortArray, count: Int): Double {
            if (count <= 0) return 0.0
            var sum = 0.0
            for (i in 0 until count) {
                val s = samples[i].toDouble()
                sum += s * s
            }
            return sqrt(sum / count)
        }

        internal fun writeLittleEndian(out: ByteArrayOutputStream, samples: ShortArray, count: Int) {
            val bytes = ByteArray(count * 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples, 0, count)
            out.write(bytes, 0, count * 2)
        }

        internal fun micUnavailableMessage(): String =
            "Microphone unavailable. Grant microphone permission and try again."
    }
}
