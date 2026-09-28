package id.hanifalfaqih.aienglishinterview.core.voice

import android.content.Context
import id.hanifalfaqih.aienglishinterview.BuildConfig

/**
 * Production voice wiring. Tests and previews use [FakeVoiceRecognizer] /
 * [FakeVoiceSynthesizer] directly instead.
 */
object VoiceProviderFactory {
    fun recognizer(context: Context): VoiceRecognizer =
        GoogleCloudVoiceRecognizer(
            BuildConfig.GOOGLE_CLOUD_SPEECH_API_KEY,
            identity = PackageManagerAppIdentity(context.applicationContext),
        )

    fun synthesizer(context: Context): VoiceSynthesizer =
        PlatformVoiceSynthesizer(context)
}
