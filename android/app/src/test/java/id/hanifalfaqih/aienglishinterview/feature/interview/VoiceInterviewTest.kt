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
import id.hanifalfaqih.aienglishinterview.data.remote.SendTurnRequest
import id.hanifalfaqih.aienglishinterview.data.remote.SendTurnResponse
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
import org.junit.After
import org.junit.Assert.assertEquals
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

private class ScriptedTurnsApi(
    var script: suspend (turnId: String, message: String) -> SendTurnResponse =
        { _, _ -> turnResponse() },
) : InterviewApi {
    data class Turn(val conversationId: String, val turnId: String, val message: String)
    val turns = mutableListOf<Turn>()

    override suspend fun createExperienceProfile(body: CreateExperienceProfileRequest): CreateExperienceProfileResponse =
        throw UnsupportedOperationException()
    override suspend fun createConversation(body: CreateConversationRequest): CreateConversationResponse =
        throw UnsupportedOperationException()
    override suspend fun sendTurn(conversationId: String, body: SendTurnRequest): SendTurnResponse {
        turns.add(Turn(conversationId, body.clientTurnId, body.message))
        return script(body.clientTurnId, body.message)
    }
    override suspend fun getConversation(conversationId: String): GetConversationResponse =
        throw UnsupportedOperationException()
    override suspend fun generateFeedback(conversationId: String): retrofit2.Response<id.hanifalfaqih.aienglishinterview.data.remote.FeedbackDto> =
        throw UnsupportedOperationException()
    override suspend fun getFeedback(conversationId: String): id.hanifalfaqih.aienglishinterview.data.remote.FeedbackDto =
        throw UnsupportedOperationException()
    override suspend fun parseResume(file: okhttp3.MultipartBody.Part): id.hanifalfaqih.aienglishinterview.data.remote.ResumeParseResponse =
        throw UnsupportedOperationException()
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
        // Recognizer stopped exactly once by release (barge-in only stops TTS).
        assertEquals(1, rec.stopCalls)
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
}
