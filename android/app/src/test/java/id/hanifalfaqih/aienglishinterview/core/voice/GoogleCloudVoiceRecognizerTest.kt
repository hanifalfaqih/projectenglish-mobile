package id.hanifalfaqih.aienglishinterview.core.voice

import kotlinx.coroutines.CompletableDeferred
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

@OptIn(ExperimentalCoroutinesApi::class)
class GoogleCloudVoiceRecognizerTest {

    private fun TestScope.recognizer(
        key: String = "k",
        capture: AudioCapture = ScriptedCapture(answerScript()),
        transport: SttTransport = object : SttTransport {
            override suspend fun transcribe(pcm16Mono: ByteArray): SttOutcome =
                SttOutcome.Transcript("hello world")
        },
    ): GoogleCloudVoiceRecognizer {
        // Share the test scheduler so advanceUntilIdle drives the session.
        val dispatcher = StandardTestDispatcher(testScheduler)
        return GoogleCloudVoiceRecognizer(
            apiKey = key,
            captureFactory = { capture },
            transportFactory = { transport },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            io = dispatcher,
        )
    }

    private suspend fun TestScope.collect(
        recognizer: GoogleCloudVoiceRecognizer,
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
    fun emptyKey_reportsConfigurationErrorWithoutNetwork() = runTest {
        val capture = ScriptedCapture(answerScript())
        var transcribed = false
        val rec = recognizer(
            key = "  ",
            capture = capture,
            transport = object : SttTransport {
                override suspend fun transcribe(pcm16Mono: ByteArray): SttOutcome {
                    transcribed = true
                    return SttOutcome.Transcript("x")
                }
            },
        )
        val seen = collect(rec) {
            rec.startListening()
        }
        assertEquals(0, capture.openCalls)
        assertTrue(!transcribed)
        val error = seen.filterIsInstance<RecognitionEvent.Error>().single()
        assertTrue(error.message.contains("not configured", ignoreCase = true))
    }

    @Test
    fun speechThenSilence_emitsSingleFinal() = runTest {
        val rec = recognizer()
        val seen = collect(rec) {
            rec.startListening()
        }
        val finals = seen.filterIsInstance<RecognitionEvent.Final>()
        assertEquals(1, finals.size)
        assertEquals("hello world", finals.single().text)
    }

    @Test
    fun explicitStop_transcribesCapturedAudio() = runTest {
        val rec = recognizer()
        val seen = collect(rec) {
            rec.startListening()
            advanceUntilIdle()
            rec.stopListening()
        }
        assertEquals(1, seen.filterIsInstance<RecognitionEvent.Final>().size)
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
        // No crash, no duplicate finals.
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
    fun noSpeech_reportsError() = runTest {
        val rec = recognizer(
            capture = ScriptedCapture(List(600) { silenceChunk() }),
            transport = object : SttTransport {
                override suspend fun transcribe(pcm16Mono: ByteArray): SttOutcome =
                    SttOutcome.NoSpeech
            },
        )
        val seen = collect(rec) {
            rec.startListening()
        }
        // 600 silent chunks hit the duration cap without speech; transport
        // reports nothing usable.
        assertTrue(seen.filterIsInstance<RecognitionEvent.Error>().isNotEmpty())
        assertTrue(seen.filterIsInstance<RecognitionEvent.Final>().isEmpty())
    }

    @Test
    fun transportFailure_reportsError() = runTest {
        val rec = recognizer(
            transport = object : SttTransport {
                override suspend fun transcribe(pcm16Mono: ByteArray): SttOutcome =
                    SttOutcome.Failure("busy")
            },
        )
        val seen = collect(rec) {
            rec.startListening()
        }
        assertEquals("busy", (seen.filterIsInstance<RecognitionEvent.Error>().single()).message)
    }

    @Test
    fun releaseDuringTranscribe_emitsNoStaleFinal() = runTest {
        val gate = CompletableDeferred<SttOutcome>()
        val rec = recognizer(
            transport = object : SttTransport {
                override suspend fun transcribe(pcm16Mono: ByteArray): SttOutcome = gate.await()
            },
        )
        val seen = collect(rec) {
            rec.startListening()
            advanceUntilIdle() // recording finishes (silence end), transcribe pending
            rec.release()
            gate.complete(SttOutcome.Transcript("stale"))
            advanceUntilIdle()
        }
        assertTrue(seen.filterIsInstance<RecognitionEvent.Final>().isEmpty())
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

    private class CapturingTransport : SttTransport {
        var calls = 0
        var lastPcmBytes = 0

        override suspend fun transcribe(pcm16Mono: ByteArray): SttOutcome {
            calls++
            lastPcmBytes = pcm16Mono.size
            return SttOutcome.Transcript("ok")
        }
    }

    @Test
    fun thinkingPause_doesNotAutoEnd() = runTest {
        // 1.5 s mid-answer silence must not end the session: the full
        // script (0.5 + 1.0 + 1.5 + 1.0 s) must reach transcription.
        val transport = CapturingTransport()
        val rec = recognizer(
            capture = ScriptedCapture(
                List(5) { silenceChunk() } +
                    List(10) { speechChunk() } +
                    List(15) { silenceChunk() } +
                    List(10) { speechChunk() },
                eosAfterScript = true,
            ),
            transport = transport,
        )
        val seen = collect(rec) {
            rec.startListening()
        }
        assertEquals(1, transport.calls)
        assertEquals(40 * 1600 * 2, transport.lastPcmBytes)
        assertEquals(1, seen.filterIsInstance<RecognitionEvent.Final>().size)
    }

    @Test
    fun fourSecondSilence_autoEnds() = runTest {
        // 4.0 s of trailing silence ends the session exactly at the
        // threshold: 5 + 10 + 40 chunks, cutting off the infinite tail.
        val transport = CapturingTransport()
        val rec = recognizer(
            capture = ScriptedCapture(
                List(5) { silenceChunk() } +
                    List(10) { speechChunk() } +
                    List(40) { silenceChunk() },
            ),
            transport = transport,
        )
        val seen = collect(rec) {
            rec.startListening()
        }
        assertEquals(1, transport.calls)
        assertEquals(55 * 1600 * 2, transport.lastPcmBytes)
        assertEquals(1, seen.filterIsInstance<RecognitionEvent.Final>().size)
    }

    @Test
    fun fiftyFiveSecondCap_transcribesCapturedAudio() = runTest {
        // 60 s of silence hits the 55 s cap: exactly 550 chunks transcribed.
        val transport = CapturingTransport()
        val rec = recognizer(
            capture = ScriptedCapture(List(600) { silenceChunk() }),
            transport = transport,
        )
        val seen = collect(rec) {
            rec.startListening()
        }
        assertEquals(1, transport.calls)
        assertEquals(550 * 1600 * 2, transport.lastPcmBytes)
        assertEquals(1, seen.filterIsInstance<RecognitionEvent.Final>().size)
    }

    @Test
    fun zeroReads_keepPollingUntilDataArrives() = runTest {
        // Sustained no-data reads must neither end the session nor corrupt
        // the PCM: speech arriving later is captured and transcribed, and
        // zero-reads contribute no bytes.
        val transport = CapturingTransport()
        val rec = recognizer(
            capture = ZeroThenDataCapture(
                zeroReads = 30,
                data = List(10) { speechChunk() } + List(40) { silenceChunk() },
                tailZeros = false,
            ),
            transport = transport,
        )
        val seen = collect(rec) {
            rec.startListening()
        }
        assertEquals(1, transport.calls)
        assertEquals(50 * 1600 * 2, transport.lastPcmBytes)
        assertEquals(1, seen.filterIsInstance<RecognitionEvent.Final>().size)
    }

    @Test
    fun stopDuringZeroPolling_terminatesAndTranscribes() = runTest {
        val transport = CapturingTransport()
        val capture = ZeroThenDataCapture(
            zeroReads = 20,
            // Speech first so stop transcribes real audio; trailing
            // zeros then poll without auto-ending (no 4 s silence yet).
            data = List(10) { speechChunk() },
        )
        val rec = recognizer(
            capture = capture,
            transport = transport,
        )
        var readsAtStop = -1
        val seen = collect(rec) {
            rec.startListening()
            advanceTimeBy(600)
            readsAtStop = capture.reads
            assertEquals(0, transport.calls)
            rec.stopListening()
        }
        assertEquals(1, transport.calls)
        assertEquals(10 * 1600 * 2, transport.lastPcmBytes)
        assertEquals(1, seen.filterIsInstance<RecognitionEvent.Final>().size)
    }

    @Test
    fun releaseDuringZeroPolling_terminatesWithoutTranscription() = runTest {
        val transport = CapturingTransport()
        val rec = recognizer(
            capture = ZeroThenDataCapture(zeroReads = 10_000, data = emptyList()),
            transport = transport,
        )
        val seen = collect(rec) {
            rec.startListening()
            advanceTimeBy(500)
            assertEquals(0, transport.calls)
            rec.release()
        }
        assertEquals(0, transport.calls)
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
        var transcribed = false
        val capture = object : AudioCapture {
            override fun open() = true
            override fun read(buffer: ShortArray): Int {
                gate.await(10, java.util.concurrent.TimeUnit.SECONDS)
                return -1
            }
            override fun close() = Unit
        }
        val transport = object : SttTransport {
            override suspend fun transcribe(pcm16Mono: ByteArray): SttOutcome {
                transcribed = true
                return SttOutcome.Transcript("x")
            }
        }
        val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val rec = GoogleCloudVoiceRecognizer(
            apiKey = "k",
            captureFactory = { capture },
            transportFactory = { transport },
            scope = scope,
            io = kotlinx.coroutines.Dispatchers.IO,
        )
        val seen = java.util.concurrent.ConcurrentLinkedQueue<RecognitionEvent>()
        val collectJob = scope.launch { rec.events.collect { seen.add(it) } }
        try {
            rec.startListening()
            Thread.sleep(800)
            assertTrue(seen.isEmpty())
            assertTrue(!transcribed)
            // The finish flag alone cannot rescue a parked read.
            rec.stopListening()
            Thread.sleep(400)
            assertTrue(seen.isEmpty())
            assertTrue(!transcribed)
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
}
