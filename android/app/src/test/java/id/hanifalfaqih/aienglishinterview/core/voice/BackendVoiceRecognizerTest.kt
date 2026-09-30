package id.hanifalfaqih.aienglishinterview.core.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun speechChunk(value: Short = 2000, count: Int = STT_CHUNK_SHORTS): ShortArray =
    ShortArray(count) { value }

private fun silenceChunk(count: Int = STT_CHUNK_SHORTS): ShortArray =
    ShortArray(count) { 0 }

/** Scripted microphone: plays chunks in order, then repeats silence (or ends). */
private class ScriptedCapture(
    private val script: List<ShortArray>,
    private val eosAfterScript: Boolean = false,
) : AudioCapture {
    var openCalls = 0
    var closeCalls = 0
    private var index = 0

    override fun open(): Boolean {
        openCalls++
        return true
    }

    override fun read(buffer: ShortArray): Int {
        if (index >= script.size) {
            if (eosAfterScript) return -1
            val chunk = silenceChunk(buffer.size)
            val n = minOf(chunk.size, buffer.size)
            chunk.copyInto(buffer, 0, 0, n)
            return n
        }
        val chunk = script[index++]
        val n = minOf(chunk.size, buffer.size)
        chunk.copyInto(buffer, 0, 0, n)
        return n
    }

    override fun close() {
        closeCalls++
    }
}

/**
 * No-data microphone: returns literal 0 exactly [zeroReads] times (no data
 * available, not end-of-stream), then plays [data]. When [tailZeros] is true
 * (default), reads after the script also return literal 0 forever, so the
 * session polls indefinitely: literal zeros advance neither silence nor cap
 * accounting (those only move on real-size reads). When false, the tail is
 * full-size silence, which does advance silence accounting.
 */
private class ZeroThenDataCapture(
    private val zeroReads: Int,
    private val data: List<ShortArray>,
    private val tailZeros: Boolean = true,
) : AudioCapture {
    var reads = 0
    private var dataIndex = 0

    override fun open() = true

    override fun read(buffer: ShortArray): Int {
        reads++
        if (reads <= zeroReads) return 0
        if (dataIndex >= data.size) {
            if (tailZeros) return 0
            val chunk = silenceChunk(buffer.size)
            val n = minOf(chunk.size, buffer.size)
            chunk.copyInto(buffer, 0, 0, n)
            return n
        }
        val chunk = data[dataIndex++]
        val n = minOf(chunk.size, buffer.size)
        chunk.copyInto(buffer, 0, 0, n)
        return n
    }

    override fun close() = Unit
}

private fun answerScript(): List<ShortArray> =
    List(5) { silenceChunk() } +
        List(10) { speechChunk() } +
        List(20) { silenceChunk() }

/**
 * Session tests for [BackendVoiceRecognizer]. Unlike the retired Google
 * path, transcription happens server-side: the recognizer always emits the
 * captured PCM as [RecognitionEvent.FinalAudio] (even silence-only audio —
 * the backend decides no-speech with 422) and never performs network I/O.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackendVoiceRecognizerTest {

    private fun TestScope.recognizer(
        capture: AudioCapture = ScriptedCapture(answerScript()),
    ): BackendVoiceRecognizer {
        // Share the test scheduler so advanceUntilIdle drives the session.
        val dispatcher = StandardTestDispatcher(testScheduler)
        return BackendVoiceRecognizer(
            captureFactory = { capture },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            io = dispatcher,
        )
    }

    private suspend fun TestScope.collect(
        recognizer: BackendVoiceRecognizer,
        block: suspend () -> Unit,
    ): List<RecognitionEvent> {
        val seen = mutableListOf<RecognitionEvent>()
        val job = launch { recognizer.events.collect { seen.add(it) } }
        // Subscribe before triggering: emissions to a SharedFlow with no
        // active collector are not replayed to late subscribers.
        advanceUntilIdle()
        block()
        advanceUntilIdle()
        job.cancel()
        return seen
    }

    @Test
    fun speechThenSilence_emitsSingleFinalAudio() = runTest {
        val rec = recognizer()
        val seen = collect(rec) {
            rec.startListening()
        }
        // 5 + 10 chunks plus trailing silence cut at the 4 s threshold.
        val finals = seen.filterIsInstance<RecognitionEvent.FinalAudio>()
        assertEquals(1, finals.size)
        assertEquals(55 * 1600 * 2, finals.single().audio.size)
    }

    @Test
    fun explicitStop_deliversCapturedAudio() = runTest {
        val rec = recognizer()
        val seen = collect(rec) {
            rec.startListening()
            advanceUntilIdle()
            rec.stopListening()
        }
        assertEquals(1, seen.filterIsInstance<RecognitionEvent.FinalAudio>().size)
    }

    @Test
    fun duplicateStart_keepsSingleSession() = runTest {
        val capture = ScriptedCapture(answerScript())
        val rec = recognizer(capture = capture)
        collect(rec) {
            rec.startListening()
            rec.startListening()
        }
        assertEquals(1, capture.openCalls)
    }

    @Test
    fun stopIsIdempotent() = runTest {
        val rec = recognizer()
        collect(rec) {
            rec.startListening()
            rec.stopListening()
            rec.stopListening()
        }
        // No crash, no duplicate audio events.
    }

    @Test
    fun micOpenFailure_reportsError() = runTest {
        val rec = recognizer(
            capture = object : AudioCapture {
                override fun open() = false
                override fun read(buffer: ShortArray) = -1
                override fun close() = Unit
            },
        )
        val seen = collect(rec) {
            rec.startListening()
        }
        val error = seen.filterIsInstance<RecognitionEvent.Error>().single()
        assertTrue(error.message.contains("icrophone", ignoreCase = true))
    }

    @Test
    fun silenceOnlyAudio_stillDeliveredForBackendDecision() = runTest {
        // 600 silent chunks hit the duration cap without speech. Unlike the
        // retired client-STT path, silence is still delivered: the backend
        // decides no-speech (422) instead of the client dropping it.
        val rec = recognizer(
            capture = ScriptedCapture(List(600) { silenceChunk() }),
        )
        val seen = collect(rec) {
            rec.startListening()
        }
        val finals = seen.filterIsInstance<RecognitionEvent.FinalAudio>()
        assertEquals(1, finals.size)
        assertEquals(550 * 1600 * 2, finals.single().audio.size)
    }

    @Test
    fun emptyCapture_reportsError() = runTest {
        // Immediate end-of-stream with zero bytes captured is a local
        // no-speech error, not an upload.
        val rec = recognizer(
            capture = ScriptedCapture(emptyList(), eosAfterScript = true),
        )
        val seen = collect(rec) {
            rec.startListening()
        }
        assertTrue(seen.filterIsInstance<RecognitionEvent.Error>().isNotEmpty())
        assertTrue(seen.filterIsInstance<RecognitionEvent.FinalAudio>().isEmpty())
    }

    @Test
    fun releaseDuringSession_emitsNoStaleAudio() = runTest {
        val rec = recognizer(
            capture = ZeroThenDataCapture(
                zeroReads = 10,
                data = List(50) { speechChunk() },
            ),
        )
        val seen = collect(rec) {
            rec.startListening()
            advanceTimeBy(300)
            rec.release()
            advanceUntilIdle()
        }
        assertTrue(seen.filterIsInstance<RecognitionEvent.FinalAudio>().isEmpty())
    }

    @Test
    fun releaseIsIdempotentAndBlocksNewSessions() = runTest {
        val capture = ScriptedCapture(answerScript())
        val rec = recognizer(capture = capture)
        collect(rec) {
            rec.release()
            rec.release()
            rec.startListening()
        }
        assertEquals(0, capture.openCalls)
    }

    // Each scripted chunk is 100 ms of audio (1600 shorts at 16 kHz).

    @Test
    fun thinkingPause_doesNotAutoEnd() = runTest {
        // 1.5 s mid-answer silence must not end the session: the full
        // script (0.5 + 1.0 + 1.5 + 1.0 s) is delivered.
        val rec = recognizer(
            capture = ScriptedCapture(
                List(5) { silenceChunk() } +
                    List(10) { speechChunk() } +
                    List(15) { silenceChunk() } +
                    List(10) { speechChunk() },
                eosAfterScript = true,
            ),
        )
        val seen = collect(rec) {
            rec.startListening()
        }
        val finals = seen.filterIsInstance<RecognitionEvent.FinalAudio>()
        assertEquals(1, finals.size)
        assertEquals(40 * 1600 * 2, finals.single().audio.size)
    }

    @Test
    fun fourSecondSilence_autoEnds() = runTest {
        // 4.0 s of trailing silence ends the session exactly at the
        // threshold: 5 + 10 + 40 chunks, cutting off the infinite tail.
        val rec = recognizer(
            capture = ScriptedCapture(
                List(5) { silenceChunk() } +
                    List(10) { speechChunk() } +
                    List(40) { silenceChunk() },
            ),
        )
        val seen = collect(rec) {
            rec.startListening()
        }
        val finals = seen.filterIsInstance<RecognitionEvent.FinalAudio>()
        assertEquals(1, finals.size)
        assertEquals(55 * 1600 * 2, finals.single().audio.size)
    }

    @Test
    fun fiftyFiveSecondCap_deliversCapturedAudio() = runTest {
        // 60 s of silence hits the 55 s cap: exactly 550 chunks delivered.
        val rec = recognizer(
            capture = ScriptedCapture(List(600) { silenceChunk() }),
        )
        val seen = collect(rec) {
            rec.startListening()
        }
        val finals = seen.filterIsInstance<RecognitionEvent.FinalAudio>()
        assertEquals(1, finals.size)
        assertEquals(550 * 1600 * 2, finals.single().audio.size)
    }

    @Test
    fun zeroReads_keepPollingUntilDataArrives() = runTest {
        // Sustained no-data reads must neither end the session nor corrupt
        // the PCM: speech arriving later is captured and delivered, and
        // zero-reads contribute no bytes.
        val rec = recognizer(
            capture = ZeroThenDataCapture(
                zeroReads = 30,
                data = List(10) { speechChunk() } + List(40) { silenceChunk() },
                tailZeros = false,
            ),
        )
        val seen = collect(rec) {
            rec.startListening()
        }
        val finals = seen.filterIsInstance<RecognitionEvent.FinalAudio>()
        assertEquals(1, finals.size)
        assertEquals(50 * 1600 * 2, finals.single().audio.size)
    }

    @Test
    fun stopDuringZeroPolling_terminatesAndDelivers() = runTest {
        val capture = ZeroThenDataCapture(
            zeroReads = 20,
            // Speech first so stop delivers real audio; trailing
            // zeros then poll without auto-ending (no 4 s silence yet).
            data = List(10) { speechChunk() },
        )
        val rec = recognizer(capture = capture)
        var readsAtStop = -1
        val seen = collect(rec) {
            rec.startListening()
            advanceTimeBy(600)
            readsAtStop = capture.reads
            rec.stopListening()
        }
        assertTrue(readsAtStop > 0)
        val finals = seen.filterIsInstance<RecognitionEvent.FinalAudio>()
        assertEquals(1, finals.size)
        assertEquals(10 * 1600 * 2, finals.single().audio.size)
    }

    @Test
    fun releaseDuringZeroPolling_terminatesWithoutDelivery() = runTest {
        val rec = recognizer(
            capture = ZeroThenDataCapture(zeroReads = 10_000, data = emptyList()),
        )
        val seen = collect(rec) {
            rec.startListening()
            advanceTimeBy(500)
            rec.release()
        }
        assertTrue(seen.isEmpty())
    }

    @Test
    fun blockingRead_parksSessionUntilUnblocked() {
        // Models the production failure observed on the emulator: a native
        // blocking read that never returns parks the session so stop flags,
        // silence end, caps, and cancellation cannot execute. A fake cannot
        // reproduce JNI blocking exactly — this parks the calling thread on
        // a latch instead — but it pins the observable symptom (a session
        // that ignores stop) which the non-blocking production strategy
        // eliminates: AndroidAudioCapture now uses READ_NON_BLOCKING, so its
        // read() can never park.
        val gate = java.util.concurrent.CountDownLatch(1)
        val capture = object : AudioCapture {
            override fun open() = true
            override fun read(buffer: ShortArray): Int {
                gate.await(10, java.util.concurrent.TimeUnit.SECONDS)
                return -1
            }
            override fun close() = Unit
        }
        val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val rec = BackendVoiceRecognizer(
            captureFactory = { capture },
            scope = scope,
            io = kotlinx.coroutines.Dispatchers.IO,
        )
        val seen = java.util.concurrent.ConcurrentLinkedQueue<RecognitionEvent>()
        val collectJob = scope.launch { rec.events.collect { seen.add(it) } }
        try {
            rec.startListening()
            Thread.sleep(800)
            assertTrue(seen.isEmpty())
            // The finish flag alone cannot rescue a parked read.
            rec.stopListening()
            Thread.sleep(400)
            assertTrue(seen.isEmpty())
        } finally {
            gate.countDown()
            scope.cancel()
        }
    }

    @Test
    fun unopenedCapture_readReturnsError() {
        // No framework touched: recorder is null, so this is JVM-safe.
        assertEquals(-1, AndroidAudioCapture().read(ShortArray(STT_CHUNK_SHORTS)))
    }

    /**
     * Byte-level proof of the upload format. The backend wraps these exact
     * bytes in a WAV container assuming 16-bit LITTLE-ENDIAN mono, so byte
     * order is part of the contract. A distinctive marker sample makes the
     * order unambiguous (0x1234 must arrive as 0x34 then 0x12).
     */
    @Test
    fun capturedPcm_isLittleEndian16BitMono() = runTest {
        val marker: Short = 0x1234
        // EOS after the script so the session ends deterministically with
        // exactly the samples served — no trailing silence padding.
        val capture = ScriptedCapture(
            listOf(speechChunk(value = marker, count = 4)),
            eosAfterScript = true,
        )
        val rec = recognizer(capture = capture)
        val seen = collect(rec) {
            rec.startListening()
        }

        val audio = seen.filterIsInstance<RecognitionEvent.FinalAudio>().single().audio
        // 4 samples x 2 bytes per 16-bit sample.
        assertEquals(8, audio.size)
        assertEquals(0x34.toByte(), audio[0])
        assertEquals(0x12.toByte(), audio[1])

        // Every sample round-trips through a little-endian decode.
        val decoded = ShortArray(4)
        java.nio.ByteBuffer.wrap(audio)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(decoded)
        assertTrue(decoded.all { it == marker })
        assertEquals(1, capture.closeCalls)
    }

    /**
     * The capture constants must match the format the backend's voice
     * module declares (16 kHz, mono, 16-bit) — see backend/src/voice/audio.ts.
     * Guards against a silent client/server sample-format divergence.
     */
    @Test
    fun captureContract_matchesBackendVoiceFormat() {
        assertEquals(16000, STT_SAMPLE_RATE_HZ)
        // One 100 ms chunk at 16 kHz mono.
        assertEquals(1600, STT_CHUNK_SHORTS)
        assertEquals(100L, STT_CHUNK_SHORTS * 1000L / STT_SAMPLE_RATE_HZ)

        // Two bytes per sample: N samples -> 2N bytes.
        val out = java.io.ByteArrayOutputStream()
        BackendVoiceRecognizer.writeLittleEndian(out, shortArrayOf(1, 2, 3), 3)
        assertEquals(6, out.size())
    }
}
