package id.hanifalfaqih.aienglishinterview.data.repository

import id.hanifalfaqih.aienglishinterview.core.network.ApiResult
import id.hanifalfaqih.aienglishinterview.core.network.ErrorKind
import id.hanifalfaqih.aienglishinterview.data.model.ExperienceItem
import id.hanifalfaqih.aienglishinterview.data.remote.CreateConversationRequest
import id.hanifalfaqih.aienglishinterview.data.remote.CreateConversationResponse
import id.hanifalfaqih.aienglishinterview.data.remote.CreateExperienceProfileRequest
import id.hanifalfaqih.aienglishinterview.data.remote.CreateExperienceProfileResponse
import id.hanifalfaqih.aienglishinterview.data.remote.FeedbackDto
import id.hanifalfaqih.aienglishinterview.data.remote.ConversationStateDto
import id.hanifalfaqih.aienglishinterview.data.remote.GetConversationResponse
import id.hanifalfaqih.aienglishinterview.data.remote.OpeningResponse
import id.hanifalfaqih.aienglishinterview.data.remote.SubmitRetryRequestDto
import id.hanifalfaqih.aienglishinterview.data.remote.RetryResponseDto
import id.hanifalfaqih.aienglishinterview.data.remote.InterviewApi
import id.hanifalfaqih.aienglishinterview.data.remote.ResumeParseResponse
import id.hanifalfaqih.aienglishinterview.data.remote.VoiceTurnResponse
import id.hanifalfaqih.aienglishinterview.data.remote.VoiceAudioDto
import id.hanifalfaqih.aienglishinterview.data.remote.SendTurnRequest
import id.hanifalfaqih.aienglishinterview.data.remote.SendTurnResponse
import id.hanifalfaqih.aienglishinterview.data.remote.TranscriptionResponse
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

private fun httpError(code: Int): HttpException =
    HttpException(Response.error<Any>(code, "e".toResponseBody("text/plain".toMediaType())))

private class FakeApi(
    var profileResult: Result<String> = Result.success("profile-1"),
    var conversationResult: Result<CreateConversationResponse> =
        Result.success(
            CreateConversationResponse(
                id = "conv-1",
                status = "active",
                state = ConversationStateDto("intro", emptyList(), null, 0),
            ),
        ),
    var turnResult: Result<SendTurnResponse> =
        Result.success(
            SendTurnResponse("Hello?", ConversationStateDto("intro", emptyList(), null, 1), "active", false),
        ),
    var failure: Throwable? = null,
) : InterviewApi {
    var lastProfileRequest: CreateExperienceProfileRequest? = null
    var lastConversationRequest: CreateConversationRequest? = null
    var lastTurn: Triple<String, String, String>? = null

    override suspend fun createExperienceProfile(body: CreateExperienceProfileRequest): CreateExperienceProfileResponse {
        failure?.let { throw it }
        lastProfileRequest = body
        return CreateExperienceProfileResponse(profileResult.getOrThrow())
    }

    override suspend fun createConversation(body: CreateConversationRequest): CreateConversationResponse {
        failure?.let { throw it }
        lastConversationRequest = body
        return conversationResult.getOrThrow()
    }

    override suspend fun sendTurn(conversationId: String, body: SendTurnRequest): SendTurnResponse {
        failure?.let { throw it }
        lastTurn = Triple(conversationId, body.clientTurnId, body.message)
        return turnResult.getOrThrow()
    }

    override suspend fun getOpening(conversationId: String): OpeningResponse =
        openingResult?.getOrThrow() ?: throw UnsupportedOperationException()

    /** Null keeps the historical unsupported behavior; tests opt in. */
    var openingResult: Result<OpeningResponse>? = null

    override suspend fun submitRetry(
        conversationId: String,
        body: SubmitRetryRequestDto,
    ): retrofit2.Response<RetryResponseDto> =
        throw UnsupportedOperationException()

    override suspend fun getCurrentRetry(
        conversationId: String,
        answerMessageId: String,
    ): RetryResponseDto = throw UnsupportedOperationException()

    override suspend fun regenerateRetryFeedback(
        conversationId: String,
        answerMessageId: String,
    ): RetryResponseDto = throw UnsupportedOperationException()


    var conversationDetail: Result<GetConversationResponse> =
        Result.success(
            GetConversationResponse(
                id = "conv-1",
                status = "closed",
                state = ConversationStateDto("wrap_up", emptyList(), null, 8),
                experienceProfileId = "profile-9",
            ),
        )

    override suspend fun getConversation(conversationId: String): GetConversationResponse =
        conversationDetail.getOrThrow()

    override suspend fun generateFeedback(conversationId: String): retrofit2.Response<FeedbackDto> =
        throw UnsupportedOperationException()

    override suspend fun getFeedback(conversationId: String): FeedbackDto =
        throw UnsupportedOperationException()

    override suspend fun parseResume(file: okhttp3.MultipartBody.Part): ResumeParseResponse =
        throw UnsupportedOperationException()

    var lastVoiceCall: Triple<String, String, ByteArray>? = null
    /** Raw multipart parts as constructed by the repository. */
    var lastVoiceParts: List<okhttp3.MultipartBody.Part>? = null
    var lastVoiceTurnIdPart: okhttp3.RequestBody? = null
    var voiceResult: Result<VoiceTurnResponse> =
        Result.success(
            VoiceTurnResponse(
                transcript = "spoken hi",
                assistantMessage = "Hello?",
                state = ConversationStateDto("intro", emptyList(), null, 1),
                status = "active",
                closing = false,
            ),
        )

    override suspend fun sendVoiceTurn(
        conversationId: String,
        audio: okhttp3.MultipartBody.Part,
        clientTurnId: okhttp3.RequestBody,
    ): VoiceTurnResponse {
        failure?.let { throw it }
        val audioBuffer = okio.Buffer()
        audio.body.writeTo(audioBuffer)
        val idBuffer = okio.Buffer()
        clientTurnId.writeTo(idBuffer)
        lastVoiceCall = Triple(conversationId, idBuffer.readUtf8(), audioBuffer.readByteArray())
        lastVoiceParts = listOf(audio)
        lastVoiceTurnIdPart = clientTurnId
        return voiceResult.getOrThrow()
    }

    var lastTranscriptionAudio: ByteArray? = null
    var transcriptionResult: Result<TranscriptionResponse> =
        Result.success(TranscriptionResponse("spoken answer"))

    override suspend fun transcribeAudio(
        audio: okhttp3.MultipartBody.Part,
    ): TranscriptionResponse {
        failure?.let { throw it }
        val buffer = okio.Buffer()
        audio.body.writeTo(buffer)
        lastTranscriptionAudio = buffer.readByteArray()
        return transcriptionResult.getOrThrow()
    }
}

class RepositoryTest {

    private val item = ExperienceItem("T", "O", "R", "D", listOf("Kotlin"))

    @Test
    fun createProfile_mapsItemsAndReturnsId() = runBlocking {
        val api = FakeApi()
        val result = ExperienceRepository(api).createExperienceProfile(listOf(item))
        assertEquals(ApiResult.Success("profile-1"), result)
        val sent = api.lastProfileRequest!!.items.single()
        assertEquals("T", sent.title)
        assertEquals(listOf("Kotlin"), sent.skills)
    }

    @Test
    fun createProfile_httpError() = runBlocking {
        val result = ExperienceRepository(FakeApi(failure = httpError(400)))
            .createExperienceProfile(listOf(item))
        assertTrue(result is ApiResult.Error && result.kind == ErrorKind.HTTP && result.httpCode == 400)
    }

    @Test
    fun createProfile_networkError() = runBlocking {
        val result = ExperienceRepository(FakeApi(failure = IOException("down")))
            .createExperienceProfile(listOf(item))
        assertTrue(result is ApiResult.Error && result.kind == ErrorKind.NETWORK)
    }

    @Test
    fun createProfile_malformedError() = runBlocking {
        val result = ExperienceRepository(FakeApi(failure = SerializationException("bad")))
            .createExperienceProfile(listOf(item))
        assertTrue(result is ApiResult.Error && result.kind == ErrorKind.MALFORMED)
    }

    @Test
    fun createConversation_sendsProfileId() = runBlocking {
        val api = FakeApi()
        val result = ConversationRepository(api).createConversation("profile-1")
        assertEquals("conv-1", (result as ApiResult.Success).value.id)
        assertEquals("profile-1", api.lastConversationRequest!!.experienceProfileId)
    }

    @Test
    fun sendTurn_mapsStateAndClosing() = runBlocking {
        val api = FakeApi()
        val result = ConversationRepository(api).sendTurn("conv-1", "turn-1", "Hi") as ApiResult.Success
        assertEquals("Hello?", result.value.assistantMessage)
        assertEquals(1, result.value.state.questionCount)
        assertEquals("turn-1", api.lastTurn!!.second)
        assertEquals("Hi", api.lastTurn!!.third)
    }

    @Test
    fun sendTurn_closedDetected() = runBlocking {
        val api = FakeApi(
            turnResult = Result.success(
                SendTurnResponse("Bye", ConversationStateDto("wrap_up", emptyList(), null, 8), "closed", true),
            ),
        )
        val result = ConversationRepository(api).sendTurn("c", "t", "m") as ApiResult.Success
        assertTrue(result.value.isClosed)
    }

    @Test
    fun sendTurn_http409Surfaced() = runBlocking {
        val result = ConversationRepository(FakeApi(failure = httpError(409)))
            .sendTurn("c", "t", "m")
        assertTrue(result is ApiResult.Error && result.httpCode == 409)
    }

    @Test
    fun sendVoiceTurn_uploadsAudioAndMapsResult() = runBlocking {
        val api = FakeApi()
        val result = ConversationRepository(api)
            .sendVoiceTurn("conv-1", "turn-7", byteArrayOf(9, 8, 7)) as ApiResult.Success
        assertEquals("spoken hi", result.value.transcript)
        assertEquals("Hello?", result.value.assistantMessage)
        assertEquals("conv-1", api.lastVoiceCall!!.first)
        assertEquals("turn-7", api.lastVoiceCall!!.second)
        assertTrue(api.lastVoiceCall!!.third.contentEquals(byteArrayOf(9, 8, 7)))
    }

    @Test
    fun sendVoiceTurn_422MapsToNoSpeechMessage() = runBlocking {
        val result = ConversationRepository(FakeApi(failure = httpError(422)))
            .sendVoiceTurn("c", "t", byteArrayOf(1))
        assertTrue(result is ApiResult.Error && result.httpCode == 422)
        assertTrue((result as ApiResult.Error).message.contains("speech", ignoreCase = true))
    }

    @Test
    fun sendVoiceTurn_networkError() = runBlocking {
        val result = ConversationRepository(FakeApi(failure = java.io.IOException("down")))
            .sendVoiceTurn("c", "t", byteArrayOf(1))
        assertTrue(result is ApiResult.Error && result.kind == ErrorKind.NETWORK)
    }

    private fun jvmRepo(api: FakeApi) = ConversationRepository(
        api,
        base64Decode = { java.util.Base64.getDecoder().decode(it) },
    )

    private fun audioDto(data: String) = VoiceAudioDto(
        format = "pcm",
        sampleRateHz = 24000,
        channels = 1,
        data = data,
    )

    @Test
    fun sendVoiceTurn_logsTimingsWithoutAudioContent() = runBlocking {
        val api = FakeApi()
        api.voiceResult = Result.success(
            VoiceTurnResponse(
                transcript = "spoken hi",
                assistantMessage = "Hello?",
                state = ConversationStateDto("intro", emptyList(), null, 0),
                status = "active",
                closing = false,
                audio = audioDto(java.util.Base64.getEncoder().encodeToString(byteArrayOf(5, 6))),
                audioError = null,
            ),
        )
        val lines = mutableListOf<String>()
        val repo = ConversationRepository(
            api,
            base64Decode = { java.util.Base64.getDecoder().decode(it) },
            timingLog = { lines.add(it) },
            clockMs = { 1000L },
        )
        val result = repo.sendVoiceTurn("c", "t", byteArrayOf(9, 8, 7)) as ApiResult.Success
        assertEquals(1, lines.size)
        val line = lines.single()
        assertTrue(line.contains("upload=3B"))
        assertTrue(line.contains("request=0ms"))
        assertTrue(line.contains("decode=0ms"))
        assertTrue(line.contains("audio=2B"))
        assertTrue(line.contains("transcriptChars=9"))
        assertFalse(line.contains("spoken hi"))
        assertEquals("spoken hi", result.value.transcript)
    }

    @Test
    fun sendVoiceTurn_mapsAudioBytes() = runBlocking {
        val api = FakeApi()
        api.voiceResult = Result.success(
            VoiceTurnResponse(
                transcript = "hi",
                assistantMessage = "yo",
                state = ConversationStateDto("intro", emptyList(), null, 0),
                status = "active",
                closing = false,
                audio = audioDto(java.util.Base64.getEncoder().encodeToString(byteArrayOf(5, 6))),
                audioError = null,
            ),
        )
        val result = jvmRepo(api).sendVoiceTurn("c", "t", byteArrayOf(1)) as ApiResult.Success
        assertTrue(result.value.audio!!.contentEquals(byteArrayOf(5, 6)))
        assertNull(result.value.audioError)
    }

    @Test
    fun sendVoiceTurn_rejectsMismatchedMetadata() = runBlocking {
        for (dto in listOf(
            audioDto("AA==").copy(format = "mp3"),
            audioDto("AA==").copy(sampleRateHz = 16000),
            audioDto("AA==").copy(channels = 2),
            audioDto(""),
        )) {
            val api = FakeApi()
            api.voiceResult = Result.success(
                VoiceTurnResponse("hi", "yo", ConversationStateDto("intro", emptyList(), null, 0), "active", false, dto, null),
            )
            val result = jvmRepo(api).sendVoiceTurn("c", "t", byteArrayOf(1)) as ApiResult.Success
            assertNull(result.value.audio)
        }
    }

    @Test
    fun sendVoiceTurn_malformedBase64YieldsNullAudio() = runBlocking {
        val api = FakeApi()
        api.voiceResult = Result.success(
            VoiceTurnResponse("hi", "yo", ConversationStateDto("intro", emptyList(), null, 0), "active", false, audioDto("!!!not-base64!!!"), null),
        )
        val result = jvmRepo(api).sendVoiceTurn("c", "t", byteArrayOf(1)) as ApiResult.Success
        assertNull(result.value.audio)
        assertEquals("hi", result.value.transcript)
    }

    @Test
    fun sendVoiceTurn_passesThroughAudioError() = runBlocking {
        val api = FakeApi()
        api.voiceResult = Result.success(
            VoiceTurnResponse("hi", "yo", ConversationStateDto("intro", emptyList(), null, 0), "active", false, null, "Voice output unavailable, showing text."),
        )
        val result = jvmRepo(api).sendVoiceTurn("c", "t", byteArrayOf(1)) as ApiResult.Success
        assertNull(result.value.audio)
        assertEquals("Voice output unavailable, showing text.", result.value.audioError)
    }

    /**
     * Wire-format contract for POST /conversations/{id}/voice-turn. The
     * backend routes the file part on `part.fieldname === "audio"` and
     * validates the text field as `clientTurnId` (backend/src/voice/routes.ts),
     * so both names — and the multipart envelope — are part of the contract.
     */
    @Test
    fun sendVoiceTurn_buildsMultipartWithContractFieldNames() = runBlocking {
        val api = FakeApi()
        jvmRepo(api).sendVoiceTurn("conv-9", "turn-1", byteArrayOf(1, 2, 3, 4))

        val part = api.lastVoiceParts!!.single()
        val disposition = part.headers!!["Content-Disposition"]!!
        assertTrue(disposition.contains("form-data"))
        assertTrue(disposition.contains("name=\"audio\""))
        // Filename present so the backend receives a file part, not a field.
        assertTrue(disposition.contains("filename="))
        // createFormData keeps the media type on the body, not the part
        // headers; OkHttp writes it as the part's Content-Type on the wire.
        assertEquals(
            "application/octet-stream",
            part.body.contentType().toString().substringBefore(";").trim(),
        )
        val body = okio.Buffer()
        part.body.writeTo(body)
        assertTrue(body.readByteArray().contentEquals(byteArrayOf(1, 2, 3, 4)))
        // The turn id travels as text/plain, matching the repository.
        val idBody = okio.Buffer()
        api.lastVoiceTurnIdPart!!.writeTo(idBody)
        assertEquals("turn-1", idBody.readUtf8())
        assertEquals(
            "text/plain",
            api.lastVoiceTurnIdPart!!.contentType().toString().substringBefore(";").trim(),
        )

        // The turn-id part name is declared on the Retrofit annotation. The
        // audio parameter's @Part is nameless: its `name="audio"` comes from
        // MultipartBody.Part.createFormData in the repository.
        val method = InterviewApi::class.java.methods.first { it.name == "sendVoiceTurn" }
        val partNames = method.parameterAnnotations
            .mapNotNull { anns -> anns.filterIsInstance<retrofit2.http.Part>().firstOrNull() }
            .map { it.value }
        assertEquals(listOf("", "clientTurnId"), partNames)

        val post = method.getAnnotation(retrofit2.http.POST::class.java)
        assertEquals("conversations/{id}/voice-turn", post!!.value)
        assertTrue(method.isAnnotationPresent(retrofit2.http.Multipart::class.java))
        val pathVars = method.parameterAnnotations
            .mapNotNull { anns -> anns.filterIsInstance<retrofit2.http.Path>().firstOrNull() }
            .map { it.value }
        assertEquals(listOf("id"), pathVars)
    }
}

class ConversationDetailTest {

    @Test
    fun getConversation_mapsStatusAndProfile() = runBlocking {
        val api = FakeApi()
        val result = ConversationRepository(api).getConversation("conv-1") as ApiResult.Success
        assertEquals("conv-1", result.value.id)
        assertEquals("closed", result.value.status)
        assertEquals("profile-9", result.value.experienceProfileId)
        assertTrue(result.value.isClosed)
    }

    @Test
    fun getConversation_httpError() = runBlocking {
        val api = FakeApi()
        api.conversationDetail = Result.failure(
            retrofit2.HttpException(retrofit2.Response.error<Any>(404, "x".toResponseBody())),
        )
        val result = ConversationRepository(api).getConversation("missing")
        assertTrue(result is ApiResult.Error && result.httpCode == 404)
    }
}

/**
 * Repository-level opening contract: DTO→domain mapping, the fixed PCM
 * playback envelope (pcm/24 kHz/mono), and HTTP error passthrough with
 * status codes preserved (409 disambiguation lives in the ViewModel).
 */
class OpeningRepositoryTest {

    private fun jvmRepo(api: FakeApi) = ConversationRepository(
        api,
        base64Decode = { java.util.Base64.getDecoder().decode(it) },
    )

    private fun audioDto(data: String) = VoiceAudioDto(
        format = "pcm",
        sampleRateHz = 24000,
        channels = 1,
        data = data,
    )

    @Test
    fun getOpening_mapsMessageAndDecodesPcm() = runBlocking {
        val api = FakeApi()
        api.openingResult = Result.success(
            OpeningResponse(
                "Welcome in.",
                audioDto(java.util.Base64.getEncoder().encodeToString(byteArrayOf(9, 9))),
                null,
            ),
        )
        val result = jvmRepo(api).getOpening("conv-1") as ApiResult.Success
        assertEquals("Welcome in.", result.value.assistantMessage)
        assertTrue(result.value.audio!!.contentEquals(byteArrayOf(9, 9)))
        assertNull(result.value.audioError)
    }

    @Test
    fun getOpening_nullAudioAndErrorPassThrough() = runBlocking {
        val api = FakeApi()
        api.openingResult = Result.success(
            OpeningResponse("Welcome in.", null, "Voice output unavailable, showing text."),
        )
        val result = jvmRepo(api).getOpening("conv-1") as ApiResult.Success
        assertEquals("Welcome in.", result.value.assistantMessage)
        assertNull(result.value.audio)
        assertEquals("Voice output unavailable, showing text.", result.value.audioError)
    }

    @Test
    fun getOpening_rejectsMismatchedAudioMetadata() = runBlocking {
        for (dto in listOf(
            audioDto("AA==").copy(format = "mp3"),
            audioDto("AA==").copy(sampleRateHz = 16000),
            audioDto("AA==").copy(channels = 2),
            audioDto(""),
            audioDto("!!!not-base64!!!"),
        )) {
            val api = FakeApi()
            api.openingResult = Result.success(OpeningResponse("Welcome in.", dto, null))
            val result = jvmRepo(api).getOpening("conv-1") as ApiResult.Success
            // Text stays valid; only the undecodable audio degrades to null.
            assertEquals("Welcome in.", result.value.assistantMessage)
            assertNull(result.value.audio)
        }
    }

    @Test
    fun getOpening_http409PreservedAsErrorWithCode() = runBlocking {
        val api = FakeApi()
        api.openingResult = Result.failure(httpError(409))
        val result = jvmRepo(api).getOpening("conv-1")
        assertTrue(result is ApiResult.Error)
        result as ApiResult.Error
        assertEquals(ErrorKind.HTTP, result.kind)
        assertEquals(409, result.httpCode)
    }

    @Test
    fun getOpening_networkFailureMapsToNetworkError() = runBlocking {
        val api = FakeApi()
        api.openingResult = Result.failure(IOException("down"))
        val result = jvmRepo(api).getOpening("conv-1")
        assertTrue(result is ApiResult.Error && result.kind == ErrorKind.NETWORK)
    }
}
