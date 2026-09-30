package id.hanifalfaqih.aienglishinterview.feature.interview

import id.hanifalfaqih.aienglishinterview.core.voice.FakeVoiceRecognizer
import id.hanifalfaqih.aienglishinterview.core.voice.FakeVoiceSynthesizer
import id.hanifalfaqih.aienglishinterview.core.voice.RecognitionEvent
import id.hanifalfaqih.aienglishinterview.core.voice.VoicePhase
import id.hanifalfaqih.aienglishinterview.data.remote.ConversationStateDto
import id.hanifalfaqih.aienglishinterview.data.remote.CreateConversationRequest
import id.hanifalfaqih.aienglishinterview.data.remote.CreateConversationResponse
import id.hanifalfaqih.aienglishinterview.data.remote.CreateExperienceProfileRequest
import id.hanifalfaqih.aienglishinterview.data.remote.CreateExperienceProfileResponse
import id.hanifalfaqih.aienglishinterview.data.remote.GetConversationResponse
import id.hanifalfaqih.aienglishinterview.data.remote.InterviewApi
import id.hanifalfaqih.aienglishinterview.data.remote.OpeningResponse
import id.hanifalfaqih.aienglishinterview.data.remote.SubmitRetryRequestDto
import id.hanifalfaqih.aienglishinterview.data.remote.RetryResponseDto
import id.hanifalfaqih.aienglishinterview.data.remote.SendTurnRequest
import id.hanifalfaqih.aienglishinterview.data.remote.SendTurnResponse
import id.hanifalfaqih.aienglishinterview.data.remote.VoiceTurnResponse
import id.hanifalfaqih.aienglishinterview.data.repository.ConversationRepository
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private fun turnResponse(
    message: String = "Tell me more.",
    status: String = "active",
    closing: Boolean = false,
) = SendTurnResponse(
    assistantMessage = message,
    state = ConversationStateDto("experience", emptyList(), null, 1),
    status = status,
    closing = closing,
)

private fun voiceTurnResponse(
    transcript: String = "spoken answer",
    message: String = "Tell me more.",
    status: String = "active",
    closing: Boolean = false,
    audio: ByteArray? = null,
    audioError: String? = null,
) = id.hanifalfaqih.aienglishinterview.data.remote.VoiceTurnResponse(
    transcript = transcript,
    assistantMessage = message,
    state = ConversationStateDto("experience", emptyList(), null, 1),
    status = status,
    closing = closing,
    audio = audio?.let {
        id.hanifalfaqih.aienglishinterview.data.remote.VoiceAudioDto(
            format = "pcm",
            sampleRateHz = 24000,
            channels = 1,
            data = java.util.Base64.getEncoder().encodeToString(it),
        )
    },
    audioError = audioError,
)

private class ScriptedTurnsApi(
    var script: suspend (turnId: String, message: String) -> SendTurnResponse =
        { _, _ -> turnResponse() },
    var voiceScript: suspend (turnId: String, audio: ByteArray) -> VoiceTurnResponse =
        { _, _ -> voiceTurnResponse() },
    var openingScript: suspend () -> OpeningResponse =
        { OpeningResponse("Welcome in.", null, null) },
) : InterviewApi {
    data class Turn(val conversationId: String, val turnId: String, val message: String)
    val turns = mutableListOf<Turn>()
    data class VoiceTurnCall(val conversationId: String, val turnId: String, val audio: ByteArray)
    val voiceTurns = mutableListOf<VoiceTurnCall>()

    override suspend fun createExperienceProfile(body: CreateExperienceProfileRequest): CreateExperienceProfileResponse =
        throw UnsupportedOperationException()
    override suspend fun createConversation(body: CreateConversationRequest): CreateConversationResponse =
        throw UnsupportedOperationException()
    override suspend fun sendTurn(conversationId: String, body: SendTurnRequest): SendTurnResponse {
        turns.add(Turn(conversationId, body.clientTurnId, body.message))
        return script(body.clientTurnId, body.message)
    }
    override suspend fun generateFeedback(conversationId: String): retrofit2.Response<id.hanifalfaqih.aienglishinterview.data.remote.FeedbackDto> =
        throw UnsupportedOperationException()
    override suspend fun getFeedback(conversationId: String): id.hanifalfaqih.aienglishinterview.data.remote.FeedbackDto =
        throw UnsupportedOperationException()
    override suspend fun parseResume(file: okhttp3.MultipartBody.Part): id.hanifalfaqih.aienglishinterview.data.remote.ResumeParseResponse =
        throw UnsupportedOperationException()
    override suspend fun sendVoiceTurn(
        conversationId: String,
        audio: okhttp3.MultipartBody.Part,
        clientTurnId: okhttp3.RequestBody,
    ): id.hanifalfaqih.aienglishinterview.data.remote.VoiceTurnResponse {
        val body = audio.body
        val buffer = okio.Buffer()
        body.writeTo(buffer)
        val bytes = buffer.readByteArray()
        val idBuffer = okio.Buffer()
        clientTurnId.writeTo(idBuffer)
        val turnId = idBuffer.readUtf8()
        voiceTurns.add(VoiceTurnCall(conversationId, turnId, bytes))
        return voiceScript(turnId, bytes)
    }

    override suspend fun transcribeAudio(
        audio: okhttp3.MultipartBody.Part,
    ): id.hanifalfaqih.aienglishinterview.data.remote.TranscriptionResponse =
        throw UnsupportedOperationException()

    var openingCalls = 0

    override suspend fun getOpening(conversationId: String): OpeningResponse {
        openingCalls++
        return openingScript()
    }

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

    var conversationDetail: GetConversationResponse? = null

    override suspend fun getConversation(conversationId: String): GetConversationResponse {
        return conversationDetail ?: throw UnsupportedOperationException()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceInterviewTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        api: ScriptedTurnsApi = ScriptedTurnsApi(),
        recognizer: FakeVoiceRecognizer = FakeVoiceRecognizer(),
        synthesizer: FakeVoiceSynthesizer = FakeVoiceSynthesizer(),
    ) = Triple(
        InterviewViewModel(
            conversationId = "conv-1",
            repository = ConversationRepository(api),
            recognizer = recognizer,
            synthesizer = synthesizer,
        ),
        recognizer,
        synthesizer,
    ) to api

    @Test
    fun finalRecognition_submitsExactlyOnce() = runTest(dispatcher) {
        val (trio, api) = viewModel()
        val (vm, rec, _) = trio
        advanceUntilIdle()

        vm.startVoiceInput()
        rec.emit(RecognitionEvent.Partial("hello wo"))
        advanceUntilIdle()
        assertEquals(0, api.turns.size)
        assertEquals("hello wo", vm.heardText)

        rec.emit(RecognitionEvent.Final("hello world"))
        advanceUntilIdle()
        assertEquals(1, api.turns.size)
        assertEquals("hello world", api.turns.single().message)
        assertEquals("conv-1", api.turns.single().conversationId)
    }

    @Test
    fun blankFinal_doesNotSubmit() = runTest(dispatcher) {
        val (trio, api) = viewModel()
        val (vm, rec, _) = trio
        advanceUntilIdle()

        vm.startVoiceInput()
        rec.emit(RecognitionEvent.Final("   "))
        advanceUntilIdle()
        assertEquals(0, api.turns.size)
        assertTrue(vm.voiceError?.isNotBlank() == true)
        assertEquals(VoicePhase.IDLE, vm.voicePhase)
    }

    @Test
    fun recognitionError_returnsToIdle() = runTest(dispatcher) {
        val (trio, api) = viewModel()
        val (vm, rec, _) = trio
        advanceUntilIdle()

        vm.startVoiceInput()
        rec.emit(RecognitionEvent.Error("no match"))
        advanceUntilIdle()
        assertEquals(0, api.turns.size)
        assertEquals("no match", vm.voiceError)
        assertEquals(VoicePhase.IDLE, vm.voicePhase)
    }

    @Test
    fun duplicateFinal_doesNotDuplicateTurn() = runTest(dispatcher) {
        val (trio, api) = viewModel()
        val (vm, rec, _) = trio
        advanceUntilIdle()

        vm.startVoiceInput()
        rec.emit(RecognitionEvent.Final("same answer"))
        rec.emit(RecognitionEvent.Final("same answer"))
        advanceUntilIdle()
        assertEquals(1, api.turns.size)
    }

    @Test
    fun clientTurnIds_ownedByViewModel() = runTest(dispatcher) {
        val (trio, api) = viewModel()
        val (vm, rec, _) = trio
        advanceUntilIdle()

        vm.startVoiceInput()
        rec.emit(RecognitionEvent.Final("first"))
        advanceUntilIdle()
        vm.startVoiceInput()
        rec.emit(RecognitionEvent.Final("second"))
        advanceUntilIdle()

        assertEquals(2, api.turns.size)
        assertTrue(api.turns[0].turnId.isNotBlank())
        assertNotEquals(api.turns[0].turnId, api.turns[1].turnId)
    }

    @Test
    fun assistantMessage_spokenAndCompletionReturnsToIdle() = runTest(dispatcher) {
        val synth = FakeVoiceSynthesizer(autoComplete = false)
        val (trio, _) = viewModel(synthesizer = synth)
        val (vm, rec, _) = trio
        advanceUntilIdle()

        vm.startVoiceInput()
        rec.emit(RecognitionEvent.Final("my answer"))
        advanceUntilIdle()
        assertEquals(listOf("Tell me more."), synth.spoken)
        assertEquals(VoicePhase.SPEAKING, vm.voicePhase)

        synth.complete()
        advanceUntilIdle()
        assertEquals(VoicePhase.IDLE, vm.voicePhase)
        assertEquals(2, vm.lines.size)
    }

    @Test
    fun ttsError_surfacesWithoutCorruptingConversation() = runTest(dispatcher) {
        val synth = FakeVoiceSynthesizer(autoComplete = false)
        val (trio, _) = viewModel(synthesizer = synth)
        val (vm, rec, _) = trio
        advanceUntilIdle()

        vm.startVoiceInput()
        rec.emit(RecognitionEvent.Final("my answer"))
        advanceUntilIdle()
        synth.fail("audio gone")
        advanceUntilIdle()

        assertEquals("audio gone", vm.voiceError)
        assertEquals(VoicePhase.IDLE, vm.voicePhase)
        assertEquals(2, vm.lines.size)
        assertNull(vm.error)
    }

    @Test
    fun closedConversation_doesNotRestartListeningButSpeaksFinal() = runTest(dispatcher) {
        val api = ScriptedTurnsApi(script = { _, _ -> turnResponse("Goodbye.", "closed", true) })
        val (trio, _) = viewModel(api = api)
        val (vm, rec, synth) = trio
        advanceUntilIdle()

        vm.startVoiceInput()
        rec.emit(RecognitionEvent.Final("last answer"))
        advanceUntilIdle()
        assertTrue(vm.isClosed)
        assertEquals(listOf("Goodbye."), synth.spoken)

        val starts = rec.startCalls
        vm.startVoiceInput()
        advanceUntilIdle()
        assertEquals(starts, rec.startCalls)
        assertEquals(VoicePhase.IDLE, vm.voicePhase)
    }

    @Test
    fun permissionDenied_submitsNothing() = runTest(dispatcher) {
        val (trio, api) = viewModel()
        val (vm, _, _) = trio
        advanceUntilIdle()

        vm.onVoicePermissionDenied()
        advanceUntilIdle()
        assertEquals(0, api.turns.size)
        assertTrue(vm.voiceError?.contains("permission", ignoreCase = true) == true)
    }

    @Test
    fun releaseVoice_stopsEngines() = runTest(dispatcher) {
        val (trio, _) = viewModel()
        val (vm, rec, synth) = trio
        advanceUntilIdle()

        vm.startVoiceInput()
        vm.releaseVoice()
        // Recognizer released exactly once by release (barge-in only stops TTS).
        assertEquals(1, rec.releaseCalls)
        assertTrue(synth.stopCalls >= 1)
        assertEquals(VoicePhase.IDLE, vm.voicePhase)
    }

    @Test
    fun final_exposesTransientPreviewWhileSending() = runTest(dispatcher) {
        val gate = CompletableDeferred<SendTurnResponse>()
        val api = ScriptedTurnsApi(script = { _, _ -> gate.await() })
        val (trio, _) = viewModel(api = api)
        val (vm, rec, _) = trio
        advanceUntilIdle()

        vm.startVoiceInput()
        rec.emit(RecognitionEvent.Final("spoken answer"))
        advanceUntilIdle()

        // Turn submitted exactly once and preview exposed mid-flight.
        assertEquals(1, api.turns.size)
        assertEquals("spoken answer", vm.pendingVoiceAnswer)
        assertTrue(vm.sending)

        gate.complete(turnResponse("Interviewer reply."))
        advanceUntilIdle()

        // Resolved: preview cleared, transcript carries the message once.
        assertNull(vm.pendingVoiceAnswer)
        assertEquals(
            listOf("spoken answer", "Interviewer reply."),
            vm.lines.map { it.text },
        )
    }

    @Test
    fun blankFinal_exposesNoPreview() = runTest(dispatcher) {
        val (trio, api) = viewModel()
        val (vm, rec, _) = trio
        advanceUntilIdle()

        vm.startVoiceInput()
        rec.emit(RecognitionEvent.Final("  "))
        advanceUntilIdle()
        assertNull(vm.pendingVoiceAnswer)
        assertEquals(0, api.turns.size)
    }

    @Test
    fun duplicateFinal_singlePreviewSingleSend() = runTest(dispatcher) {
        val (trio, api) = viewModel()
        val (vm, rec, _) = trio
        advanceUntilIdle()

        vm.startVoiceInput()
        rec.emit(RecognitionEvent.Final("once"))
        rec.emit(RecognitionEvent.Final("once"))
        advanceUntilIdle()
        assertEquals(1, api.turns.size)
        // After immediate success the preview has already transitioned.
        assertNull(vm.pendingVoiceAnswer)
        assertEquals(1, vm.lines.count { it.isUser && it.text == "once" })
    }

    @Test
    fun turnError_clearsPreview() = runTest(dispatcher) {
        val api = ScriptedTurnsApi(script = { _, _ -> throw IOException("down") })
        val (trio, _) = viewModel(api = api)
        val (vm, rec, _) = trio
        advanceUntilIdle()

        vm.startVoiceInput()
        rec.emit(RecognitionEvent.Final("spoken answer"))
        advanceUntilIdle()
        assertNull(vm.pendingVoiceAnswer)
        assertTrue(vm.error?.isNotBlank() == true)
        assertTrue(vm.lines.none { it.isUser })
    }

    @Test
    fun release_clearsTransientPreview() = runTest(dispatcher) {
        val gate = CompletableDeferred<SendTurnResponse>()
        val api = ScriptedTurnsApi(script = { _, _ -> gate.await() })
        val (trio, _) = viewModel(api = api)
        val (vm, rec, _) = trio
        advanceUntilIdle()

        vm.startVoiceInput()
        rec.emit(RecognitionEvent.Final("spoken answer"))
        advanceUntilIdle()
        assertEquals("spoken answer", vm.pendingVoiceAnswer)

        vm.releaseVoice()
        advanceUntilIdle()
        assertNull(vm.pendingVoiceAnswer)
        assertEquals("", vm.heardText)
        gate.complete(turnResponse("Late reply."))
        advanceUntilIdle()
    }

    @Test
    fun finalAudio_uploadsAndRendersTranscript() = runTest(dispatcher) {
        val (trio, api) = viewModel()
        val (vm, rec, synth) = trio
        advanceUntilIdle()

        vm.startVoiceInput()
        rec.emit(RecognitionEvent.FinalAudio(byteArrayOf(1, 2, 3, 4)))
        advanceUntilIdle()

        assertEquals(1, api.voiceTurns.size)
        assertEquals("conv-1", api.voiceTurns.single().conversationId)
        assertTrue(api.voiceTurns.single().turnId.isNotBlank())
        assertEquals(0, api.turns.size)
        assertEquals(
            listOf("spoken answer", "Tell me more."),
            vm.lines.map { it.text },
        )
        assertEquals(listOf("Tell me more."), synth.spoken)
    }

    @Test
    fun manualStop_thenFinalAudio_submitsExactlyOnce() = runTest(dispatcher) {
        // Regression: cancelVoiceInput() moves phase to IDLE before the
        // session emits. FinalAudio must still be consumed exactly once.
        val (trio, api) = viewModel()
        val (vm, rec, _) = trio
        advanceUntilIdle()

        vm.startVoiceInput()
        vm.cancelVoiceInput()
        assertEquals(VoicePhase.IDLE, vm.voicePhase)
        rec.emit(RecognitionEvent.FinalAudio(byteArrayOf(9, 8, 7)))
        advanceUntilIdle()

        assertEquals(1, api.voiceTurns.size)
        val call = api.voiceTurns.single()
        assertEquals("conv-1", call.conversationId)
        assertTrue(call.turnId.isNotBlank())
        assertTrue(call.audio.contentEquals(byteArrayOf(9, 8, 7)))
        assertEquals(
            listOf("spoken answer", "Tell me more."),
            vm.lines.map { it.text },
        )
    }

    @Test
    fun finalAudio_emptyAudioShowsError() = runTest(dispatcher) {
        val (trio, api) = viewModel()
        val (vm, rec, _) = trio
        advanceUntilIdle()

        vm.startVoiceInput()
        rec.emit(RecognitionEvent.FinalAudio(byteArrayOf()))
        advanceUntilIdle()
        assertEquals(0, api.voiceTurns.size)
        assertTrue(vm.voiceError?.isNotBlank() == true)
    }

    @Test
    fun finalAudio_duplicateEmitsSingleUpload() = runTest(dispatcher) {
        val (trio, api) = viewModel()
        val (vm, rec, _) = trio
        advanceUntilIdle()

        vm.startVoiceInput()
        rec.emit(RecognitionEvent.FinalAudio(byteArrayOf(1)))
        rec.emit(RecognitionEvent.FinalAudio(byteArrayOf(1)))
        advanceUntilIdle()
        assertEquals(1, api.voiceTurns.size)
    }

    @Test
    fun finalAudio_closedConversationSpeaksButStaysClosed() = runTest(dispatcher) {
        val api = ScriptedTurnsApi(
            voiceScript = { _, _ -> voiceTurnResponse(status = "closed", closing = true) },
        )
        val (trio, _) = viewModel(api = api)
        val (vm, rec, synth) = trio
        advanceUntilIdle()

        vm.startVoiceInput()
        rec.emit(RecognitionEvent.FinalAudio(byteArrayOf(1, 2)))
        advanceUntilIdle()
        assertTrue(vm.isClosed)
        assertEquals(listOf("Tell me more."), synth.spoken)
        assertEquals(VoicePhase.IDLE, vm.voicePhase)
    }

    @Test
    fun finalAudio_turnIdsAreUniquePerAnswer() = runTest(dispatcher) {
        val (trio, api) = viewModel()
        val (vm, rec, _) = trio
        advanceUntilIdle()

        vm.startVoiceInput()
        rec.emit(RecognitionEvent.FinalAudio(byteArrayOf(1)))
        advanceUntilIdle()
        vm.startVoiceInput()
        rec.emit(RecognitionEvent.FinalAudio(byteArrayOf(2)))
        advanceUntilIdle()
        assertEquals(2, api.voiceTurns.size)
        assertNotEquals(api.voiceTurns[0].turnId, api.voiceTurns[1].turnId)
    }

    private fun voiceVm(
        api: ScriptedTurnsApi,
        player: FakeVoiceSynthesizer = FakeVoiceSynthesizer(),
        synth: FakeVoiceSynthesizer = FakeVoiceSynthesizer(),
    ): Triple<InterviewViewModel, FakeVoiceRecognizer, FakeVoiceSynthesizer> {
        val rec = FakeVoiceRecognizer()
        val vm = InterviewViewModel(
            conversationId = "conv-1",
            repository = ConversationRepository(
                api,
                base64Decode = { java.util.Base64.getDecoder().decode(it) },
            ),
            recognizer = rec,
            synthesizer = synth,
            audioPlayer = player,
        )
        return Triple(vm, rec, player)
    }

    @Test
    fun finalAudio_withBackendAudio_playsBackendAudio() = runTest(dispatcher) {
        val api = ScriptedTurnsApi(
            voiceScript = { _, _ -> voiceTurnResponse(audio = byteArrayOf(7, 7, 7)) },
        )
        val player = FakeVoiceSynthesizer()
        val synth = FakeVoiceSynthesizer()
        val (vm, rec, _) = voiceVm(api, player, synth)
        advanceUntilIdle()

        vm.startVoiceInput()
        rec.emit(RecognitionEvent.FinalAudio(byteArrayOf(1, 2)))
        advanceUntilIdle()

        assertEquals(listOf("spoken answer", "Tell me more."), vm.lines.map { it.text })
        assertTrue(player.spokenAudio.single().contentEquals(byteArrayOf(7, 7, 7)))
        // Fallback stays silent when backend audio plays.
        assertTrue(synth.spoken.isEmpty())
        assertNull(vm.voiceNotice)
    }

    @Test
    fun finalAudio_noAudioWithError_fallsBackWithNotice() = runTest(dispatcher) {
        val api = ScriptedTurnsApi(
            voiceScript = { _, _ ->
                voiceTurnResponse(audio = null, audioError = "Voice output unavailable, showing text.")
            },
        )
        val player = FakeVoiceSynthesizer()
        val synth = FakeVoiceSynthesizer()
        val (vm, rec, _) = voiceVm(api, player, synth)
        advanceUntilIdle()

        vm.startVoiceInput()
        rec.emit(RecognitionEvent.FinalAudio(byteArrayOf(1, 2)))
        advanceUntilIdle()

        // Conversation intact, fallback speaks, notice surfaced.
        assertEquals(listOf("spoken answer", "Tell me more."), vm.lines.map { it.text })
        assertEquals(listOf("Tell me more."), synth.spoken)
        assertTrue(player.spokenAudio.isEmpty())
        assertEquals("Voice output unavailable, showing text.", vm.voiceNotice)
        assertNull(vm.error)
    }

    @Test
    fun finalAudio_whenClosed_doesNotSubmit() = runTest(dispatcher) {
        val api = ScriptedTurnsApi(
            voiceScript = { _, _ -> voiceTurnResponse(status = "closed", closing = true) },
        )
        val (trio, _) = viewModel(api = api)
        val (vm, rec, _) = trio
        advanceUntilIdle()

        // First turn closes the conversation.
        vm.startVoiceInput()
        rec.emit(RecognitionEvent.FinalAudio(byteArrayOf(1)))
        advanceUntilIdle()
        assertTrue(vm.isClosed)

        // A late FinalAudio after close must not submit.
        vm.startVoiceInput()
        rec.emit(RecognitionEvent.FinalAudio(byteArrayOf(2)))
        advanceUntilIdle()
        assertEquals(1, api.voiceTurns.size)
    }

    @Test
    fun finalAudio_whileSending_doesNotDuplicate() = runTest(dispatcher) {
        val gate = CompletableDeferred<id.hanifalfaqih.aienglishinterview.data.remote.VoiceTurnResponse>()
        val api = ScriptedTurnsApi(
            voiceScript = { _, _ -> gate.await() },
        )
        val (trio, _) = viewModel(api = api)
        val (vm, rec, _) = trio
        advanceUntilIdle()

        vm.startVoiceInput()
        rec.emit(RecognitionEvent.FinalAudio(byteArrayOf(1)))
        advanceUntilIdle()
        // Turn in flight; a second FinalAudio must not start another upload.
        rec.emit(RecognitionEvent.FinalAudio(byteArrayOf(2)))
        advanceUntilIdle()
        assertEquals(1, api.voiceTurns.size)
        gate.complete(
            voiceTurnResponse(),
        )
        advanceUntilIdle()
        assertEquals(1, api.voiceTurns.size)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OpeningTurnTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun openingPcm() = java.util.Base64.getEncoder().encodeToString(byteArrayOf(9, 9))

    /**
     * Maps to [id.hanifalfaqih.aienglishinterview.core.network.ErrorKind.HTTP]
     * with the given code via the shared `apiCall` wrapper, the same way a
     * real non-2xx opening response arrives.
     */
    private fun openingHttpError(code: Int): retrofit2.HttpException =
        retrofit2.HttpException(
            retrofit2.Response.error<Any>(code, "".toResponseBody(null)),
        )

    private fun conversationDetail(status: String) = GetConversationResponse(
        id = "conv-1",
        status = status,
        state = ConversationStateDto("experience", emptyList(), null, 1),
        experienceProfileId = "profile-9",
    )

    private fun viewModelWithPlayer(
        api: ScriptedTurnsApi,
        player: FakeVoiceSynthesizer,
    ) = InterviewViewModel(
        conversationId = "conv-1",
        repository = ConversationRepository(
            api,
            base64Decode = { java.util.Base64.getDecoder().decode(it) },
        ),
        recognizer = FakeVoiceRecognizer(),
        synthesizer = FakeVoiceSynthesizer(),
        audioPlayer = player,
    )

    @Test
    fun opening_playsOnceThenMicReady() = runTest(dispatcher) {
        val api = ScriptedTurnsApi()
        val player = FakeVoiceSynthesizer(autoComplete = false)
        val synth = FakeVoiceSynthesizer(autoComplete = false)
        val vm = InterviewViewModel(
            conversationId = "conv-1",
            repository = ConversationRepository(
                api,
                base64Decode = { java.util.Base64.getDecoder().decode(it) },
            ),
            recognizer = FakeVoiceRecognizer(),
            synthesizer = synth,
            audioPlayer = player,
        )
        advanceUntilIdle()

        vm.startOpening()
        assertTrue(vm.opening)
        advanceUntilIdle()

        assertEquals(1, api.openingCalls)
        assertEquals(1, vm.lines.size)
        assertEquals("Welcome in.", vm.lines.single().text)
        assertEquals(VoicePhase.SPEAKING, vm.voicePhase)

        synth.complete()
        advanceUntilIdle()
        assertEquals(VoicePhase.IDLE, vm.voicePhase)
        assertEquals(false, vm.opening)
    }

    @Test
    fun opening_secondTriggerMakesNoSecondRequest() = runTest(dispatcher) {
        val api = ScriptedTurnsApi()
        val vm = viewModelWithPlayer(api, FakeVoiceSynthesizer())
        advanceUntilIdle()

        vm.startOpening()
        vm.startOpening()
        advanceUntilIdle()
        vm.startOpening()
        advanceUntilIdle()

        assertEquals(1, api.openingCalls)
        assertEquals(1, vm.lines.size)
    }

    @Test
    fun opening_blocksMicWhilePreparing() = runTest(dispatcher) {
        val gate = CompletableDeferred<OpeningResponse>()
        val api = ScriptedTurnsApi(openingScript = { gate.await() })
        val rec = FakeVoiceRecognizer()
        val vm = InterviewViewModel(
            conversationId = "conv-1",
            repository = ConversationRepository(api),
            recognizer = rec,
            synthesizer = FakeVoiceSynthesizer(),
            audioPlayer = FakeVoiceSynthesizer(),
        )
        advanceUntilIdle()

        vm.startOpening()
        vm.startVoiceInput()
        advanceUntilIdle()

        assertEquals(0, rec.startCalls)
        assertTrue(vm.lines.isEmpty())

        gate.complete(OpeningResponse("Welcome in.", null, null))
        advanceUntilIdle()
        assertEquals(1, vm.lines.size)

        vm.startVoiceInput()
        advanceUntilIdle()
        assertEquals(1, rec.startCalls)
    }

    @Test
    fun opening_errorOffersRetryAndRetrySucceeds() = runTest(dispatcher) {
        var fail = true
        val api = ScriptedTurnsApi(openingScript = {
            if (fail) throw IOException("backend down")
            OpeningResponse("Welcome in.", null, null)
        })
        val vm = viewModelWithPlayer(api, FakeVoiceSynthesizer())
        advanceUntilIdle()

        vm.startOpening()
        advanceUntilIdle()
        assertTrue(vm.needsOpeningRetry)
        assertTrue(vm.error != null)
        assertTrue(vm.lines.isEmpty())

        fail = false
        vm.retryOpening()
        advanceUntilIdle()
        assertEquals(false, vm.needsOpeningRetry)
        assertEquals(1, vm.lines.size)
        assertEquals(2, api.openingCalls)
    }

    @Test
    fun opening_withBackendAudioPlaysPcm() = runTest(dispatcher) {
        val api = ScriptedTurnsApi(
            openingScript = {
                OpeningResponse(
                    "Welcome in.",
                    id.hanifalfaqih.aienglishinterview.data.remote.VoiceAudioDto(
                        format = "pcm",
                        sampleRateHz = 24000,
                        channels = 1,
                        data = openingPcm(),
                    ),
                    null,
                )
            },
        )
        val player = FakeVoiceSynthesizer()
        val vm = viewModelWithPlayer(api, player)
        advanceUntilIdle()

        vm.startOpening()
        advanceUntilIdle()

        assertEquals(listOf("Welcome in."), player.spoken)
        assertEquals(1, player.spokenAudio.size)
        assertTrue(player.spokenAudio.single()?.contentEquals(byteArrayOf(9, 9)) == true)
    }

    /**
     * The opening is interviewer content only: it must never fabricate a
     * user line locally and must never travel the user-turn path.
     */
    @Test
    fun opening_createsNoUserTurnAndNoTurnRequest() = runTest(dispatcher) {
        val api = ScriptedTurnsApi()
        val vm = viewModelWithPlayer(api, FakeVoiceSynthesizer())
        advanceUntilIdle()

        vm.startOpening()
        advanceUntilIdle()

        assertEquals(1, vm.lines.size)
        val line = vm.lines.single()
        assertFalse(line.isUser)
        assertEquals("Welcome in.", line.text)
        assertTrue(api.turns.isEmpty())
        assertTrue(api.voiceTurns.isEmpty())
    }

    /** Null audio still yields a usable text opening (on-device fallback). */
    @Test
    fun opening_nullAudioKeepsTextOpeningUsable() = runTest(dispatcher) {
        val api = ScriptedTurnsApi(
            openingScript = { OpeningResponse("Welcome in.", null, null) },
        )
        val player = FakeVoiceSynthesizer()
        val synth = FakeVoiceSynthesizer()
        val vm = InterviewViewModel(
            conversationId = "conv-1",
            repository = ConversationRepository(
                api,
                base64Decode = { java.util.Base64.getDecoder().decode(it) },
            ),
            recognizer = FakeVoiceRecognizer(),
            synthesizer = synth,
            audioPlayer = player,
        )
        advanceUntilIdle()

        vm.startOpening()
        advanceUntilIdle()

        assertEquals(1, vm.lines.size)
        assertNull(vm.error)
        assertFalse(vm.needsOpeningRetry)
        // No backend bytes: the existing fallback synthesizer speaks the text.
        assertTrue(player.spoken.isEmpty())
        assertEquals(listOf("Welcome in."), synth.spoken)
        assertNull(vm.voiceNotice)
    }

    /**
     * A TTS failure is presentation-only: the opening stays successful and
     * the message surfaces as a recoverable notice, never as an error.
     */
    @Test
    fun opening_audioErrorDoesNotInvalidateTextOpening() = runTest(dispatcher) {
        val api = ScriptedTurnsApi(
            openingScript = {
                OpeningResponse(
                    "Welcome in.",
                    null,
                    "Voice output unavailable, showing text.",
                )
            },
        )
        val synth = FakeVoiceSynthesizer()
        val vm = InterviewViewModel(
            conversationId = "conv-1",
            repository = ConversationRepository(
                api,
                base64Decode = { java.util.Base64.getDecoder().decode(it) },
            ),
            recognizer = FakeVoiceRecognizer(),
            synthesizer = synth,
            audioPlayer = FakeVoiceSynthesizer(),
        )
        advanceUntilIdle()

        vm.startOpening()
        advanceUntilIdle()

        assertEquals(1, vm.lines.size)
        assertEquals("Welcome in.", vm.lines.single().text)
        assertNull(vm.error)
        assertFalse(vm.needsOpeningRetry)
        assertEquals("Voice output unavailable, showing text.", vm.voiceNotice)
        assertEquals(listOf("Welcome in."), synth.spoken)
    }

    /**
     * 409 on a CLOSED conversation resolves through the existing
     * conversation lookup: the interview is marked closed and no retry is
     * offered, so a finished interview never becomes mic-ready.
     */
    @Test
    fun opening_409ClosedConversationMarksClosedWithoutRetry() = runTest(dispatcher) {
        val api = ScriptedTurnsApi(
            openingScript = { throw openingHttpError(409) },
        )
        api.conversationDetail = conversationDetail("closed")
        val vm = viewModelWithPlayer(api, FakeVoiceSynthesizer())
        advanceUntilIdle()

        vm.startOpening()
        advanceUntilIdle()

        assertTrue(vm.isClosed)
        assertFalse(vm.needsOpeningRetry)
        assertNull(vm.error)
        assertTrue(vm.lines.isEmpty())
        // Exactly one opening attempt: 409 is never blindly re-requested.
        assertEquals(1, api.openingCalls)
        assertTrue(api.turns.isEmpty())
        assertTrue(api.voiceTurns.isEmpty())
    }

    /**
     * 409 on an ACTIVE conversation means the opening was already taken by
     * an existing user turn elsewhere. The conversation stays usable, no
     * retry is offered, and no second opening request is issued.
     */
    @Test
    fun opening_409ActiveConversationDoesNotRetryOrClose() = runTest(dispatcher) {
        val api = ScriptedTurnsApi(
            openingScript = { throw openingHttpError(409) },
        )
        api.conversationDetail = conversationDetail("active")
        val rec = FakeVoiceRecognizer()
        val vm = InterviewViewModel(
            conversationId = "conv-1",
            repository = ConversationRepository(
                api,
                base64Decode = { java.util.Base64.getDecoder().decode(it) },
            ),
            recognizer = rec,
            synthesizer = FakeVoiceSynthesizer(),
            audioPlayer = FakeVoiceSynthesizer(),
        )
        advanceUntilIdle()

        vm.startOpening()
        advanceUntilIdle()

        assertFalse(vm.isClosed)
        assertFalse(vm.needsOpeningRetry)
        assertNull(vm.error)
        assertTrue(vm.lines.isEmpty())
        assertEquals(1, api.openingCalls)

        // A second trigger must not issue another opening request.
        vm.startOpening()
        advanceUntilIdle()
        assertEquals(1, api.openingCalls)
    }

    /**
     * A non-409 HTTP failure uses the existing Android error
     * representation and offers the opening retry path.
     */
    @Test
    fun opening_httpFailureExposesErrorAndRetry() = runTest(dispatcher) {
        val api = ScriptedTurnsApi(
            openingScript = { throw openingHttpError(504) },
        )
        val vm = viewModelWithPlayer(api, FakeVoiceSynthesizer())
        advanceUntilIdle()

        vm.startOpening()
        advanceUntilIdle()

        assertTrue(vm.error != null)
        assertTrue(vm.needsOpeningRetry)
        assertTrue(vm.lines.isEmpty())
        assertFalse(vm.isClosed)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceGatingTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun micStart_blockedWhileTurnProcessing() = runTest(dispatcher) {
        val gate = CompletableDeferred<VoiceTurnResponse>()
        val api = ScriptedTurnsApi(
            voiceScript = { _, _ -> gate.await() },
        )
        val rec = FakeVoiceRecognizer()
        val vm = InterviewViewModel(
            conversationId = "conv-1",
            repository = ConversationRepository(api),
            recognizer = rec,
            synthesizer = FakeVoiceSynthesizer(),
            audioPlayer = FakeVoiceSynthesizer(),
        )
        advanceUntilIdle()

        vm.startVoiceInput()
        rec.emit(RecognitionEvent.FinalAudio(byteArrayOf(1)))
        advanceUntilIdle()
        assertTrue(vm.sending)

        // A second mic start while the turn is in flight must not record.
        vm.startVoiceInput()
        advanceUntilIdle()
        assertEquals(1, rec.startCalls)

        gate.complete(voiceTurnResponse())
        advanceUntilIdle()
        assertEquals(false, vm.sending)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CompletionGatingTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun httpError(code: Int): retrofit2.HttpException =
        retrofit2.HttpException(retrofit2.Response.error<Any>(code, okhttp3.ResponseBody.create(null, "")))

    private fun viewModel(api: ScriptedTurnsApi): InterviewViewModel {
        val rec = FakeVoiceRecognizer()
        return InterviewViewModel(
            conversationId = "conv-1",
            repository = ConversationRepository(api),
            recognizer = rec,
            synthesizer = FakeVoiceSynthesizer(),
            audioPlayer = FakeVoiceSynthesizer(),
        )
    }

    private fun closedTurnsApi(): ScriptedTurnsApi = ScriptedTurnsApi(
        script = { _, _ -> turnResponse("Goodbye.", "closed", true) },
    )

    @Test
    fun completeBlockedWhileFinalAudioSpeaks() = runTest(dispatcher) {
        val synth = FakeVoiceSynthesizer(autoComplete = false)
        val rec = FakeVoiceRecognizer()
        val vm = InterviewViewModel(
            conversationId = "conv-1",
            repository = ConversationRepository(closedTurnsApi()),
            recognizer = rec,
            synthesizer = synth,
            audioPlayer = FakeVoiceSynthesizer(),
        )
        advanceUntilIdle()

        vm.startVoiceInput()
        rec.emit(RecognitionEvent.Final("last answer"))
        advanceUntilIdle()

        assertTrue(vm.isClosed)
        assertEquals(VoicePhase.SPEAKING, vm.voicePhase)
        assertEquals(false, vm.canComplete)

        synth.complete()
        advanceUntilIdle()
        assertEquals(VoicePhase.IDLE, vm.voicePhase)
        assertEquals(true, vm.canComplete)
    }

    @Test
    fun opening409WithClosedConversationDisablesMic() = runTest(dispatcher) {
        val api = ScriptedTurnsApi(
            openingScript = { throw httpError(409) },
        )
        api.conversationDetail = GetConversationResponse(
            id = "conv-1",
            status = "closed",
            state = ConversationStateDto("wrap_up", emptyList(), null, 8),
            experienceProfileId = "profile-9",
        )
        val rec = FakeVoiceRecognizer()
        val vm = InterviewViewModel(
            conversationId = "conv-1",
            repository = ConversationRepository(api),
            recognizer = rec,
            synthesizer = FakeVoiceSynthesizer(),
            audioPlayer = FakeVoiceSynthesizer(),
        )
        advanceUntilIdle()

        vm.startOpening()
        advanceUntilIdle()

        assertTrue(vm.isClosed)
        assertNull(vm.error)
        vm.startVoiceInput()
        advanceUntilIdle()
        assertEquals(0, rec.startCalls)
    }

    @Test
    fun opening409WithActiveConversationStaysMicReady() = runTest(dispatcher) {
        val api = ScriptedTurnsApi(
            openingScript = { throw httpError(409) },
        )
        api.conversationDetail = GetConversationResponse(
            id = "conv-1",
            status = "active",
            state = ConversationStateDto("intro", emptyList(), null, 0),
            experienceProfileId = "profile-9",
        )
        val rec = FakeVoiceRecognizer()
        val vm = InterviewViewModel(
            conversationId = "conv-1",
            repository = ConversationRepository(api),
            recognizer = rec,
            synthesizer = FakeVoiceSynthesizer(),
            audioPlayer = FakeVoiceSynthesizer(),
        )
        advanceUntilIdle()

        vm.startOpening()
        advanceUntilIdle()

        assertEquals(false, vm.isClosed)
        vm.startVoiceInput()
        advanceUntilIdle()
        assertEquals(1, rec.startCalls)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceRetryTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun failedVoiceTurn_retryResubmitsSameTurnId() = runTest(dispatcher) {
        var fail = true
        val api = ScriptedTurnsApi(
            voiceScript = { turnId, audio ->
                if (fail) throw IOException("flaky backend")
                voiceTurnResponse()
            },
        )
        val rec = FakeVoiceRecognizer()
        val vm = InterviewViewModel(
            conversationId = "conv-1",
            repository = ConversationRepository(api),
            recognizer = rec,
            synthesizer = FakeVoiceSynthesizer(),
            audioPlayer = FakeVoiceSynthesizer(),
        )
        advanceUntilIdle()

        vm.startVoiceInput()
        rec.emit(RecognitionEvent.FinalAudio(byteArrayOf(1, 2)))
        advanceUntilIdle()
        assertTrue(vm.error != null)
        assertEquals(1, api.voiceTurns.size)
        val firstId = api.voiceTurns.single().turnId

        fail = false
        vm.retry()
        advanceUntilIdle()

        assertEquals(2, api.voiceTurns.size)
        assertEquals(firstId, api.voiceTurns[1].turnId)
        assertTrue(api.voiceTurns[1].audio.contentEquals(byteArrayOf(1, 2)))
        assertNull(vm.error)
        assertEquals(2, vm.lines.size)
    }

    @Test
    fun voiceRetry_clearedAfterSuccessSoNextFailureStartsFresh() = runTest(dispatcher) {
        var calls = 0
        val api = ScriptedTurnsApi(
            voiceScript = { _, _ ->
                calls++
                if (calls == 1 || calls == 3) throw IOException("flaky $calls")
                voiceTurnResponse()
            },
        )
        val rec = FakeVoiceRecognizer()
        val vm = InterviewViewModel(
            conversationId = "conv-1",
            repository = ConversationRepository(api),
            recognizer = rec,
            synthesizer = FakeVoiceSynthesizer(),
            audioPlayer = FakeVoiceSynthesizer(),
        )
        advanceUntilIdle()

        vm.startVoiceInput()
        rec.emit(RecognitionEvent.FinalAudio(byteArrayOf(1)))
        advanceUntilIdle()
        vm.retry()
        advanceUntilIdle()
        assertEquals(2, api.voiceTurns.size)

        // A later independent failure retries with its own new turn id,
        // not the consumed one.
        vm.startVoiceInput()
        rec.emit(RecognitionEvent.FinalAudio(byteArrayOf(2)))
        advanceUntilIdle()
        assertEquals(3, api.voiceTurns.size)
        val firstId = api.voiceTurns[0].turnId
        assertEquals(firstId, api.voiceTurns[1].turnId)
        vm.retry()
        advanceUntilIdle()
        assertEquals(4, api.voiceTurns.size)
        assertEquals(api.voiceTurns[2].turnId, api.voiceTurns[3].turnId)
        assertTrue(api.voiceTurns[2].turnId != firstId)
        assertEquals(4, vm.lines.size)
    }
}

class MicTapDebounceTest {

    @Test
    fun acceptsFirstTap() {
        assertTrue(shouldAcceptMicTap(0L, 10_000L))
    }

    @Test
    fun ignoresBounceWithinWindow() {
        assertFalse(shouldAcceptMicTap(10_000L, 10_050L))
    }

    @Test
    fun acceptsTapAfterWindow() {
        assertTrue(shouldAcceptMicTap(10_000L, 10_300L))
    }
}
