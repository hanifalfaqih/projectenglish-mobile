package id.hanifalfaqih.aienglishinterview.core.voice

import android.content.Context

/**
 * Production voice wiring: backend-transcribed capture plus platform TTS.
 * Tests and previews use [FakeVoiceRecognizer] / [FakeVoiceSynthesizer]
 * directly instead.
 */
object VoiceProviderFactory {
    fun recognizer(): VoiceRecognizer = BackendVoiceRecognizer()

    fun synthesizer(context: Context): VoiceSynthesizer =
        PlatformVoiceSynthesizer(context)

    fun audioPlayer(): VoiceSynthesizer = BackendAudioPlayer()
}
