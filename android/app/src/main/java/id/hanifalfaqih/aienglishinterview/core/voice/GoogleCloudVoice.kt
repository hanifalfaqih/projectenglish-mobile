package id.hanifalfaqih.aienglishinterview.core.voice

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Production speech-to-text: Android [AudioRecord] capture plus Google Cloud
 * Speech-to-Text V1 synchronous REST. Turn-based only — no streaming, no
 * gRPC, no partial hypotheses (only [RecognitionEvent.Final] is emitted).
 *
 * Key rules: the API key is an Android-restricted client key (see
 * docs/production-voice-setup.md), never a service-account secret. It is
 * sent as an HTTPS query parameter and is never logged: this file builds a
 * dedicated [OkHttpClient] WITHOUT the app's logging interceptor.
 */

const val STT_SAMPLE_RATE_HZ = 16000
internal const val STT_MAX_DURATION_MS = 55_000L
internal const val STT_CHUNK_SHORTS = 1600 // 100 ms at 16 kHz
internal const val STT_SPEECH_RMS_THRESHOLD = 250.0
internal const val STT_MIN_SPEECH_MS = 500L
internal const val STT_END_SILENCE_MS = 1_500L
private const val STT_ENDPOINT = "https://speech.googleapis.com/v1/speech:recognize"

sealed interface SttOutcome {
    data class Transcript(val text: String) : SttOutcome
    data object NoSpeech : SttOutcome
    data class Failure(val message: String) : SttOutcome
}

/** Network boundary for transcription. Replaced by fakes in tests. */
interface SttTransport {
    suspend fun transcribe(pcm16Mono: ByteArray): SttOutcome
}

/** Microphone boundary. Replaced by scripted fakes in tests. */
interface AudioCapture {    /** @return false when the microphone cannot be opened. */
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
            recorder?.read(buffer, 0, buffer.size) ?: -1
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

@Serializable
private data class SttRecognitionResponse(
    val results: List<SttResult> = emptyList(),
)

@Serializable
private data class SttResult(
    val alternatives: List<SttAlternative> = emptyList(),
)

@Serializable
private data class SttAlternative(
    val transcript: String = "",
)

internal val sttJson = Json { ignoreUnknownKeys = true }

/**
 * Android app identity for Google's API-key application restriction.
 * Resolved from the installed package — never hardcoded — so debug and
 * release signing certificates are both supported.
 */
data class AppIdentity(
    val packageName: String,
    /** SHA-1 fingerprint, uppercase hex, no colon separators. */
    val sha1Cert: String,
)

interface AppIdentityProvider {
    /** @return null when the identity cannot be determined. */
    fun current(): AppIdentity?
}

class PackageManagerAppIdentity(
    private val context: Context,
) : AppIdentityProvider {
    override fun current(): AppIdentity? {
        return try {
            val packageName = context.packageName
            val pm = context.packageManager
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                )
                val signing = info.signingInfo ?: return null
                if (signing.hasMultipleSigners()) {
                    signing.apkContentsSigners
                } else {
                    signing.signingCertificateHistory
                }
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                    ?.signatures
            } ?: return null
            val cert = signatures.firstOrNull() ?: return null
            val sha1 = MessageDigest.getInstance("SHA-1").digest(cert.toByteArray())
            AppIdentity(packageName, normalizeCert(sha1Hex(sha1)))
        } catch (e: Exception) {
            // NameNotFound, NoSuchAlgorithm, or platform quirks: the caller
            // treats unknown identity as a configuration error, never as a
            // reason to send an incomplete restricted-key request.
            null
        }
    }

    companion object {
        /** Uppercase hex without colon separators, as Google expects. */
        internal fun sha1Hex(digest: ByteArray): String =
            digest.joinToString("") { "%02X".format(it) }

        internal fun normalizeCert(raw: String): String =
            raw.replace(":", "").uppercase()
    }
}

/**
 * Google Cloud STT V1 synchronous REST transport.
 *
 * [base64] is injectable because `android.util.Base64` is unavailable to JVM
 * unit tests; production passes the platform encoder.
 */
class GoogleSttTransport(
    private val apiKey: String,
    private val client: OkHttpClient = defaultSttClient(),
    private val base64: (ByteArray) -> String = {
        Base64.encodeToString(it, Base64.NO_WRAP)
    },
    private val endpoint: String = STT_ENDPOINT,
    private val identity: AppIdentityProvider? = null,
) : SttTransport {

    override suspend fun transcribe(pcm16Mono: ByteArray): SttOutcome {
        // Android-restricted keys are rejected without both identity headers,
        // so resolve identity first and fail clearly instead of sending an
        // incomplete request.
        val appIdentity = identity?.current()
        if (identity != null && appIdentity == null) {
            return SttOutcome.Failure(
                "Could not determine app identity for speech recognition. " +
                    "Reinstall the app and try again.",
            )
        }
        return suspendCancellableCoroutine { cont ->
            val requestBody = """
                {"config":{"encoding":"LINEAR16","sampleRateHertz":16000,
                "languageCode":"en-US","enableAutomaticPunctuation":true},
                "audio":{"content":"${base64(pcm16Mono)}"}}
            """.trimIndent()
                .toRequestBody("application/json".toMediaType())
            // Key travels as an HTTPS query parameter, per Google's API-key
            // convention. It is never written to logs (see [defaultSttClient]).
            val builder = Request.Builder()
                .url("$endpoint?key=$apiKey")
                .post(requestBody)
            if (appIdentity != null) {
                // Required by Android application restriction: proves the
                // request comes from this package/signature. The fingerprint
                // itself is public key metadata, not a secret — but like the
                // URL, full headers are never logged.
                builder.header("X-Android-Package", appIdentity.packageName)
                builder.header("X-Android-Cert", appIdentity.sha1Cert)
            }
            val call = client.newCall(builder.build())
            cont.invokeOnCancellation {
                try {
                    call.cancel()
                } catch (ignored: Exception) {
                    // Best effort.
                }
            }
            call.enqueue(SttCallback(cont))
        }
    }

    private class SttCallback(
        private val cont: kotlinx.coroutines.CancellableContinuation<SttOutcome>,
    ) : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCompleted) {
                cont.resume(
                    SttOutcome.Failure("Network error. Check your connection and try again."),
                )
            }
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isCompleted) return
            response.use {
                val body = try {
                    it.body?.string().orEmpty()
                } catch (e: IOException) {
                    cont.resume(SttOutcome.Failure("Network error. Check your connection and try again."))
                    return
                }
                cont.resume(parseResponse(it.code, body))
            }
        }
    }

    companion object {
        /**
         * Dedicated client with NO logging interceptor: the request URL
         * carries the API key, so the app's debug-logging client must never
         * be reused here.
         */
        fun defaultSttClient(): OkHttpClient = OkHttpClient.Builder().build()

        internal fun parseResponse(httpCode: Int, body: String): SttOutcome {
            if (httpCode == 401 || httpCode == 403) {
                return SttOutcome.Failure(
                    "Speech service rejected the request (authorization). " +
                        "Check the API key configuration.",
                )
            }
            if (httpCode == 429) {
                return SttOutcome.Failure(
                    "Speech service is busy (rate limited). Wait a moment and try again.",
                )
            }
            if (httpCode !in 200..299) {
                return SttOutcome.Failure("Speech service error. Please try again.")
            }
            val parsed = try {
                sttJson.decodeFromString<SttRecognitionResponse>(body)
            } catch (e: Exception) {
                return SttOutcome.Failure("Unexpected speech service response. Please try again.")
            }
            val transcript = parsed.results
                .flatMap { it.alternatives }
                .firstOrNull { it.transcript.isNotBlank() }
                ?.transcript
                ?.trim()
            if (transcript.isNullOrEmpty()) return SttOutcome.NoSpeech
            return SttOutcome.Transcript(transcript)
        }
    }
}
