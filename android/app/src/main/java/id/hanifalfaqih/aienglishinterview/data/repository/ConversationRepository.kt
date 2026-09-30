package id.hanifalfaqih.aienglishinterview.data.repository

import id.hanifalfaqih.aienglishinterview.core.network.ApiResult
import id.hanifalfaqih.aienglishinterview.data.model.Conversation
import id.hanifalfaqih.aienglishinterview.data.model.ConversationDetail
import id.hanifalfaqih.aienglishinterview.data.model.ConversationState
import id.hanifalfaqih.aienglishinterview.data.model.Opening
import id.hanifalfaqih.aienglishinterview.data.model.Topic
import id.hanifalfaqih.aienglishinterview.data.model.TurnResult
import id.hanifalfaqih.aienglishinterview.data.model.VoiceTurn
import id.hanifalfaqih.aienglishinterview.data.remote.ApiProvider
import id.hanifalfaqih.aienglishinterview.data.remote.ConversationStateDto
import id.hanifalfaqih.aienglishinterview.data.remote.CreateConversationRequest
import id.hanifalfaqih.aienglishinterview.data.remote.InterviewApi
import id.hanifalfaqih.aienglishinterview.data.remote.SendTurnRequest
import id.hanifalfaqih.aienglishinterview.data.remote.VoiceAudioDto
import id.hanifalfaqih.aienglishinterview.data.remote.VoiceTurnResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Conversations: creation and turn submission. The caller owns
 * `clientTurnId` — generate a fresh UUID per new answer and reuse the same
 * id when retrying an ambiguous failure (the backend replays idempotently).
 */
class ConversationRepository(
    private val api: InterviewApi = ApiProvider.api,
    // Injectable because android.util.Base64 is unavailable to JVM tests;
    // production passes the platform decoder.
    private val base64Decode: (String) -> ByteArray = {
        android.util.Base64.decode(it, android.util.Base64.DEFAULT)
    },
    // Development timing sink. Null (default) logs via Logcat where
    // available and stays silent on JVM unit tests; tests inject a
    // recorder to assert timing content.
    private val timingLog: ((String) -> Unit)? = null,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun createConversation(experienceProfileId: String?): ApiResult<Conversation> {        return when (
            val result = apiCall { api.createConversation(CreateConversationRequest(experienceProfileId)) }
        ) {
            is ApiResult.Success -> ApiResult.Success(
                Conversation(id = result.value.id, status = result.value.status),
            )
            is ApiResult.Error -> result
        }
    }

    /**
     * AI-first opening: the interviewer's first message plus optional PCM
     * audio for a fresh conversation. 409 means turns already exist (the
     * opening was taken) — surfaced with its code for the caller to handle.
     */
    suspend fun getOpening(conversationId: String): ApiResult<Opening> {
        return when (
            val result = apiCall { api.getOpening(conversationId) }
        ) {
            is ApiResult.Success -> ApiResult.Success(
                Opening(
                    assistantMessage = result.value.assistantMessage,
                    audio = result.value.audio?.toPcm(),
                    audioError = result.value.audioError,
                ),
            )
            is ApiResult.Error -> result
        }
    }

    /**
     * Conversation lookup: status (to honor closed conversations) and the
     * experience profile id (to open a new retry session on the same
     * experience without touching the closed conversation).
     */
    suspend fun getConversation(conversationId: String): ApiResult<ConversationDetail> {
        return when (
            val result = apiCall { api.getConversation(conversationId) }
        ) {
            is ApiResult.Success -> ApiResult.Success(
                ConversationDetail(
                    id = result.value.id,
                    status = result.value.status,
                    experienceProfileId = result.value.experienceProfileId,
                ),
            )
            is ApiResult.Error -> result
        }
    }

    suspend fun sendTurn(
        conversationId: String,
        clientTurnId: String,
        message: String,
    ): ApiResult<TurnResult> {
        return when (
            val result = apiCall { api.sendTurn(conversationId, SendTurnRequest(clientTurnId, message)) }
        ) {
            is ApiResult.Success -> ApiResult.Success(
                TurnResult(
                    assistantMessage = result.value.assistantMessage,
                    state = result.value.state.toDomain(),
                    status = result.value.status,
                    closing = result.value.closing,
                ),
            )
            is ApiResult.Error -> result
        }
    }

    /**
     * Voice turn: uploads captured PCM for server-side transcription, then
     * receives the turn result (transcript included). `clientTurnId`
     * ownership is identical to [sendTurn]. A 422 means the backend heard
     * no usable speech — surfaced as a voice error, not a generic failure.
     */
    suspend fun sendVoiceTurn(
        conversationId: String,
        clientTurnId: String,
        pcm16Mono: ByteArray,
    ): ApiResult<VoiceTurn> {
        val audio = MultipartBody.Part.createFormData(
            "audio",
            "answer.pcm",
            pcm16Mono.toRequestBody("application/octet-stream".toMediaType()),
        )
        val turnId = clientTurnId.toRequestBody("text/plain".toMediaType())
        // A: request start. B: HTTP response received. C: audio decoded.
        // D (playback start) is owned by the audio player; see voice logs.
        val requestStartMs = clockMs()
        val result = apiCall { api.sendVoiceTurn(conversationId, audio, turnId) }
        val responseMs = clockMs()
        return when (result) {
            is ApiResult.Success -> {
                val pcm = result.value.audio?.toPcm()
                val decodeMs = clockMs()
                // Sizes, durations, and transcript length only — never audio
                // bytes, transcripts, or credentials.
                emitTiming(
                    "voice-turn upload=${pcm16Mono.size}B " +
                        "request=${responseMs - requestStartMs}ms " +
                        "decode=${decodeMs - responseMs}ms " +
                        "audio=${pcm?.size ?: 0}B " +
                        "(~${"%.2f".format((pcm?.size ?: 0) / 2.0 / 24000)})s " +
                        "transcriptChars=${result.value.transcript.length}",
                )
                ApiResult.Success(
                    VoiceTurn(
                        transcript = result.value.transcript,
                        assistantMessage = result.value.assistantMessage,
                        state = result.value.state.toDomain(),
                        status = result.value.status,
                        closing = result.value.closing,
                        audio = pcm,
                        audioError = result.value.audioError,
                    ),
                )
            }
            is ApiResult.Error ->
                if (result.httpCode == 422) {
                    ApiResult.Error(
                        kind = result.kind,
                        message = "No speech detected. Try again.",
                        httpCode = 422,
                    )
                } else {
                    result
                }
        }
    }

    /** JVM-safe dev logging: Logcat on device, silent under unit tests. */
    private fun emitTiming(message: String) {
        val sink = timingLog
        if (sink != null) {
            sink(message)
            return
        }
        try {
            android.util.Log.d("VoiceTiming", message)
        } catch (_: Throwable) {
            // android.util.Log is unavailable to JVM unit tests; timing is
            // diagnostics only and must never break the turn pipeline.
        }
    }

    private fun ConversationStateDto.toDomain() = ConversationState(
        phase = phase,
        topics = topics.map { Topic(id = it.id, label = it.label, covered = it.covered) },
        currentTopicId = currentTopicId,
        questionCount = questionCount,
    )

    /**
     * Validates backend audio metadata against the fixed playback contract
     * (PCM/16-bit/mono/24 kHz) and decodes the payload. Returns null for any
     * mismatch or undecodable body — the caller falls back to text display.
     * Never throws: malformed payloads must not crash the turn pipeline.
     */
    private fun VoiceAudioDto.toPcm(): ByteArray? {
        if (format != "pcm" || sampleRateHz != 24000 || channels != 1 || data.isBlank()) {
            return null
        }
        return try {
            base64Decode(data).takeIf { it.isNotEmpty() }
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
