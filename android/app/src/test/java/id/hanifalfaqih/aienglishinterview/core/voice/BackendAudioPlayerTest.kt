package id.hanifalfaqih.aienglishinterview.core.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeAudioDriver : AudioTrackDriver {
    data class Start(val pcm: ByteArray)

    val starts = mutableListOf<Start>()
    var stopCalls = 0
    var releaseCalls = 0
    var listener: PlaybackListener? = null

    override fun start(pcm: ByteArray, listener: PlaybackListener) {
        starts.add(Start(pcm))
        this.listener = listener
    }

    override fun stop() {
        stopCalls++
    }

    override fun release() {
        releaseCalls++
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BackendAudioPlayerTest {

    private suspend fun kotlinx.coroutines.test.TestScope.collect(
        player: BackendAudioPlayer,
        block: suspend () -> Unit,
    ): List<SynthesisEvent> {
        val seen = mutableListOf<SynthesisEvent>()
        val job = launch { player.events.collect { seen.add(it) } }
        advanceUntilIdle()
        block()
        advanceUntilIdle()
        job.cancel()
        return seen
    }

    @Test
    fun validPcm_startsPlaybackAndCompletes() = runTest {
        val driver = FakeAudioDriver()
        val player = BackendAudioPlayer(driver)
        val seen = collect(player) {
            player.speak("hello", byteArrayOf(1, 2, 3, 4))
            driver.listener?.onDone()
        }
        assertEquals(1, driver.starts.size)
        assertTrue(driver.starts.single().pcm.contentEquals(byteArrayOf(1, 2, 3, 4)))
        assertEquals(listOf(SynthesisEvent.Done), seen)
    }

    @Test
    fun emptyAudio_reportsErrorWithoutPlayback() = runTest {
        val driver = FakeAudioDriver()
        val player = BackendAudioPlayer(driver)
        val seen = collect(player) {
            player.speak("hello", byteArrayOf())
            player.speak("hello", null)
        }
        assertTrue(driver.starts.isEmpty())
        assertEquals(2, seen.filterIsInstance<SynthesisEvent.Error>().size)
    }

    @Test
    fun oversizedAudio_reportsError() = runTest {
        val driver = FakeAudioDriver()
        val player = BackendAudioPlayer(driver)
        val seen = collect(player) {
            player.speak("hello", ByteArray(PLAYBACK_MAX_BYTES + 1))
        }
        assertTrue(driver.starts.isEmpty())
        assertTrue(seen.filterIsInstance<SynthesisEvent.Error>().isNotEmpty())
    }

    @Test
    fun driverError_surfacesErrorEvent() = runTest {
        val driver = FakeAudioDriver()
        val player = BackendAudioPlayer(driver)
        val seen = collect(player) {
            player.speak("hello", byteArrayOf(1))
            driver.listener?.onError("audio flung")
        }
        assertTrue(seen.filterIsInstance<SynthesisEvent.Error>().isNotEmpty())
    }

    @Test
    fun stop_delegatesToDriver() = runTest {
        val driver = FakeAudioDriver()
        val player = BackendAudioPlayer(driver)
        collect(player) {
            player.speak("hello", byteArrayOf(1))
            player.stop()
        }
        // speak() stops any previous utterance first, then the explicit stop.
        assertEquals(2, driver.stopCalls)
    }

    @Test
    fun release_shutsDownAndIgnoresLaterSpeech() = runTest {
        val driver = FakeAudioDriver()
        val player = BackendAudioPlayer(driver)
        val seen = collect(player) {
            player.release()
            player.release()
            player.speak("late", byteArrayOf(1))
        }
        assertEquals(1, driver.releaseCalls)
        assertTrue(driver.starts.isEmpty())
        assertTrue(seen.isEmpty())
    }

    @Test
    fun repeatedPlayback_startsFreshEachTime() = runTest {
        val driver = FakeAudioDriver()
        val player = BackendAudioPlayer(driver)
        val seen = collect(player) {
            player.speak("one", byteArrayOf(1))
            driver.listener?.onDone()
            player.speak("two", byteArrayOf(2, 3))
            driver.listener?.onDone()
        }
        assertEquals(2, driver.starts.size)
        assertEquals(2, seen.filterIsInstance<SynthesisEvent.Done>().size)
    }
}
