package id.hanifalfaqih.aienglishinterview.core.voice

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeTtsDriver(
    var initStatus: Int = TextToSpeech.SUCCESS,
    var languageResult: Int = TextToSpeech.LANG_AVAILABLE,
    var speakResult: Int = TextToSpeech.SUCCESS,
) : TtsDriver {
    var utteranceListener: UtteranceProgressListener? = null
    val spoken = mutableListOf<Pair<String, String>>()
    var stopCalls = 0
    var shutdownCalls = 0

    override fun init(onInit: (Int) -> Unit) {
        onInit(initStatus)
    }

    override fun setLanguage(locale: Locale): Int = languageResult

    override fun setListener(listener: UtteranceProgressListener) {
        this.utteranceListener = listener
    }

    override fun speak(text: String, utteranceId: String): Int {
        spoken.add(text to utteranceId)
        return speakResult
    }

    override fun stop(): Int {
        stopCalls++
        return TextToSpeech.SUCCESS
    }

    override fun shutdown() {
        shutdownCalls++
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlatformVoiceSynthesizerTest {

    // NOTE: constructing PlatformVoiceSynthesizer with a_context would touch
    // the framework; tests only need the driver seam, so context is unused.
    // A null Context is never dereferenced because the fake driver ignores it.
    private fun synthesizer(
        driver: FakeTtsDriver = FakeTtsDriver(),
    ) = PlatformVoiceSynthesizer(context = null, driver = driver)

    private suspend fun kotlinx.coroutines.test.TestScope.collect(
        synth: PlatformVoiceSynthesizer,
        block: suspend () -> Unit,
    ): List<SynthesisEvent> {
        val seen = mutableListOf<SynthesisEvent>()
        val job = launch { synth.events.collect { seen.add(it) } }
        // Subscribe before triggering: emissions to a SharedFlow with no
        // active collector are not replayed to late subscribers.
        advanceUntilIdle()
        block()
        advanceUntilIdle()
        job.cancel()
        return seen
    }

    @Test
    fun initSuccess_speakCompletesWithDone() = runTest {
        val driver = FakeTtsDriver()
        val synth = synthesizer(driver)
        advanceUntilIdle()
        val seen = collect(synth) {
            synth.speak("hello")
            driver.utteranceListener?.onDone(driver.spoken.single().second)
        }
        assertEquals(listOf("hello" to driver.spoken.single().second), driver.spoken)
        assertEquals(listOf(SynthesisEvent.Done), seen)
    }

    @Test
    fun initFailure_reportsError() = runTest {
        val synth = synthesizer(FakeTtsDriver(initStatus = TextToSpeech.ERROR))
        val seen = collect(synth) {}
        assertEquals(1, seen.filterIsInstance<SynthesisEvent.Error>().size)
    }

    @Test
    fun unavailableLanguage_reportsError() = runTest {
        val synth = synthesizer(FakeTtsDriver(languageResult = TextToSpeech.LANG_NOT_SUPPORTED))
        val seen = collect(synth) {}
        assertTrue(seen.filterIsInstance<SynthesisEvent.Error>().isNotEmpty())
    }

    @Test
    fun speakStartFailure_reportsError() = runTest {
        val synth = synthesizer(FakeTtsDriver(speakResult = TextToSpeech.ERROR))
        advanceUntilIdle()
        val seen = collect(synth) {
            synth.speak("hi")
        }
        assertTrue(seen.filterIsInstance<SynthesisEvent.Error>().isNotEmpty())
    }

    @Test
    fun engineError_reportsError() = runTest {
        val driver = FakeTtsDriver()
        val synth = synthesizer(driver)
        advanceUntilIdle()
        val seen = collect(synth) {
            synth.speak("hi")
            driver.utteranceListener?.onError(driver.spoken.single().second)
        }
        assertTrue(seen.filterIsInstance<SynthesisEvent.Error>().isNotEmpty())
    }

    @Test
    fun stop_delegatesToDriver() = runTest {
        val driver = FakeTtsDriver()
        val synth = synthesizer(driver)
        advanceUntilIdle()
        synth.stop()
        assertEquals(1, driver.stopCalls)
    }

    @Test
    fun release_shutsDownAndIgnoresLaterSpeech() = runTest {
        val driver = FakeTtsDriver()
        val synth = synthesizer(driver)
        advanceUntilIdle()
        synth.release()
        synth.release()
        synth.speak("late")
        assertEquals(1, driver.shutdownCalls)
        assertTrue(driver.spoken.isEmpty())
    }

    @Test
    fun staleUtteranceDone_isIgnored() = runTest {
        val driver = FakeTtsDriver()
        val synth = synthesizer(driver)
        advanceUntilIdle()
        val seen = collect(synth) {
            driver.utteranceListener?.onDone("unknown-id")
        }
        assertTrue(seen.isEmpty())
    }
}
