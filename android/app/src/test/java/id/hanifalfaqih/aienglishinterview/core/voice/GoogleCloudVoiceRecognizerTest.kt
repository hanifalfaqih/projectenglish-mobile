package id.hanifalfaqih.aienglishinterview.core.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun speechChunk(value: Short = 2000, count: Int = STT_CHUNK_SHORTS): ShortArray =
    ShortArray(count) { value }

private fun silenceChunk(count: Int = STT_CHUNK_SHORTS): ShortArray =
    ShortArray(count) { 0 }

/** Scripted microphone: plays chunks in order, then repeats silence. */
private class ScriptedCapture(
    private val script: List<ShortArray>,
) : AudioCapture {
    var openCalls = 0
    var closeCalls = 0
    private var index = 0

    override fun open(): Boolean {
        openCalls++
        return true
    }

    override fun read(buffer: ShortArray): Int {
        val chunk = if (index < script.size) script[index++] else silenceChunk(buffer.size)
        val n = minOf(chunk.size, buffer.size)
        chunk.copyInto(buffer, 0, 0, n)
        return n
    }

    override fun close() {
        closeCalls++
    }
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
}
