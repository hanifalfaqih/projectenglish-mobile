package id.hanifalfaqih.aienglishinterview.core.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Production [VoiceSynthesizer] on Android framework [TextToSpeech].
 * No network, no SDK, no credentials: synthesis is fully on-device (or via
 * the device's installed TTS engine). English [Locale.US] is preferred.
 *
 * The engine itself sits behind [TtsDriver] because framework TTS cannot be
 * instantiated in JVM unit tests; orchestration (ready gating, utterance
 * tracking, Done/Error mapping, release) is tested against fakes.
 */
interface TtsDriver {
    fun init(onInit: (Int) -> Unit)
    fun setLanguage(locale: Locale): Int
    fun setListener(listener: UtteranceProgressListener)
    fun speak(text: String, utteranceId: String): Int
    fun stop(): Int
    fun shutdown()
}

class AndroidTtsDriver(private val context: Context) : TtsDriver {
    private var tts: TextToSpeech? = null

    override fun init(onInit: (Int) -> Unit) {
        tts = TextToSpeech(context.applicationContext) { status -> onInit(status) }
    }

    override fun setLanguage(locale: Locale): Int =
        tts?.setLanguage(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED

    override fun setListener(listener: UtteranceProgressListener) {
        tts?.setOnUtteranceProgressListener(listener)
    }

    override fun speak(text: String, utteranceId: String): Int =
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
            ?: TextToSpeech.ERROR

    override fun stop(): Int = tts?.stop() ?: TextToSpeech.SUCCESS

    override fun shutdown() {
        tts?.shutdown()
        tts = null
    }
}

class PlatformVoiceSynthesizer(
    context: Context?,
    driver: TtsDriver? = null,
) : VoiceSynthesizer {

    // replay=1 so an init failure (emitted during construction, before the
    // ViewModel subscribes) is still visible to the first collector. Later
    // speech events behave as a hot stream; the single continuous collector
    // never sees duplicates.
    private val _events = MutableSharedFlow<SynthesisEvent>(
        replay = 1,
        extraBufferCapacity = 16,
    )
    override val events: SharedFlow<SynthesisEvent> = _events.asSharedFlow()

    // Null only in tests, where a fake driver is always supplied. Production
    // (via VoiceProviderFactory) always passes a real Context.
    private val engine: TtsDriver = driver ?: AndroidTtsDriver(
        requireNotNull(context) { "PlatformVoiceSynthesizer requires a Context" },
    )

    @Volatile
    private var ready = false

    @Volatile
    private var released = false

    private var currentUtterance: String? = null

    init {
        engine.setListener(ProgressForwarder())
        engine.init { status ->
            if (released) return@init
            if (status != TextToSpeech.SUCCESS) {
                _events.tryEmit(SynthesisEvent.Error("Speech output engine failed to start."))
                return@init
            }
            val language = engine.setLanguage(Locale.US)
            if (language == TextToSpeech.LANG_MISSING_DATA ||
                language == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                _events.tryEmit(SynthesisEvent.Error("English voice output is not available on this device."))
                return@init
            }
            ready = true
        }
    }

    override fun speak(text: String, audio: ByteArray?) {
        if (released) return
        // Platform synthesis always renders text; backend audio (if any) is
        // ignored here — BackendAudioPlayer owns byte playback.
        if (!ready) {
            _events.tryEmit(SynthesisEvent.Error("Speech output is not ready yet."))
            return
        }
        currentUtterance = UUID.randomUUID().toString()
        val result = engine.speak(text, currentUtterance!!)
        if (result != TextToSpeech.SUCCESS) {
            currentUtterance = null
            _events.tryEmit(SynthesisEvent.Error("Speech output failed to start."))
        }
    }

    override fun stop() {
        currentUtterance = null
        try {
            engine.stop()
        } catch (ignored: Exception) {
            // Best effort.
        }
    }

    /** Idempotent; after release the synthesizer accepts no new speech. */
    override fun release() {
        if (released) return
        released = true
        currentUtterance = null
        try {
            engine.shutdown()
        } catch (ignored: Exception) {
            // Best effort.
        }
    }

    private inner class ProgressForwarder : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            if (utteranceId != null && utteranceId == currentUtterance && !released) {
                currentUtterance = null
                _events.tryEmit(SynthesisEvent.Done)
            }
        }

        override fun onError(utteranceId: String?) {
            if (utteranceId != null && utteranceId != currentUtterance) return
            if (released) return
            currentUtterance = null
            _events.tryEmit(SynthesisEvent.Error("Speech output failed."))
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            onError(utteranceId)
        }
    }
}
