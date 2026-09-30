package id.hanifalfaqih.aienglishinterview.feature.interview

import id.hanifalfaqih.aienglishinterview.core.voice.AudioCapture
import id.hanifalfaqih.aienglishinterview.core.voice.AudioTrackDriver
import id.hanifalfaqih.aienglishinterview.core.voice.BackendAudioPlayer
import id.hanifalfaqih.aienglishinterview.core.voice.BackendVoiceRecognizer
import id.hanifalfaqih.aienglishinterview.core.voice.FakeVoiceSynthesizer
import id.hanifalfaqih.aienglishinterview.core.voice.PlaybackListener
import id.hanifalfaqih.aienglishinterview.core.voice.STT_CHUNK_SHORTS
import id.hanifalfaqih.aienglishinterview.core.voice.VoicePhase
import id.hanifalfaqih.aienglishinterview.data.remote.ConversationStateDto
import id.hanifalfaqih.aienglishinterview.data.remote.CreateConversationRequest
import id.hanifalfaqih.aienglishinterview.data.remote.CreateConversationResponse
import id.hanifalfaqih.aienglishinterview.data.remote.CreateExperienceProfileRequest
import id.hanifalfaqih.aienglishinterview.data.remote.CreateExperienceProfileResponse
import id.hanifalfaqih.aienglishinterview.data.remote.FeedbackDto
import id.hanifalfaqih.aienglishinterview.data.remote.GetConversationResponse
import id.hanifalfaqih.aienglishinterview.data.remote.InterviewApi
import id.hanifalfaqih.aienglishinterview.data.remote.OpeningResponse
import id.hanifalfaqih.aienglishinterview.data.remote.ResumeParseResponse
import id.hanifalfaqih.aienglishinterview.data.remote.RetryResponseDto
import id.hanifalfaqih.aienglishinterview.data.remote.SendTurnRequest
import id.hanifalfaqih.aienglishinterview.data.remote.SendTurnResponse
import id.hanifalfaqih.aienglishinterview.data.remote.SubmitRetryRequestDto
import id.hanifalfaqih.aienglishinterview.data.remote.VoiceAudioDto
import id.hanifalfaqih.aienglishinterview.data.remote.VoiceTurnResponse
import id.hanifalfaqih.aienglishinterview.data.repository.ConversationRepository
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.Dispatchers
import okhttp3.MultipartBody
import okhttp3.RequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * End-to-end proof of ONE user voice turn through the real production
 * components, with only the two true external boundaries faked:
 *
 *   ScriptedCapture (microphone)
 *     -> BackendVoiceRecognizer   [real]
 *     -> InterviewViewModel       [real]
 *     -> ConversationRepository   [real]
 *     -> VoiceTurnApi             [fake HTTP boundary]
 *     -> BackendAudioPlayer       [real, scripted AudioTrack driver]
 *     -> InterviewViewModel.lines [rendered state]
 *
 * No real microphone, no network, no credentials. Complements the
 * layer-isolated suites: [BackendVoiceRecognizerTest] (capture session),
 * `VoiceInterviewTest` (ViewModel with a fake recognizer), and
 * `RepositoryTest` (repository with a fake API). This file is the only
 * place the whole chain is wired together.
 */

/** Microphone double: serves scripted 16 kHz mono samples, then EOS. */
private class ScriptedMic(
    private val chunks: List<ShortArray>,
) : AudioCapture {
    var openCalls = 0
    var closeCalls = 0
    private var index = 0

    override fun open(): Boolean {
        openCalls++
        return true
    }

    override fun read(buffer: ShortArray): Int {
        if (index >= chunks.size) return -1 // end of stream
        val chunk = chunks[index++]
        val n = minOf(chunk.size, buffer.size)
        chunk.copyInto(buffer, 0, 0, n)
        return n
    }

    override fun close() {
        closeCalls++
    }
}

/** AudioTrack double: records what playback received instead of emitting sound. */
private class RecordingDriver : AudioTrackDriver {
    val played = mutableListOf<ByteArray>()
    var stopCalls = 0
    var releaseCalls = 0
    private var listener: PlaybackListener? = null

    override fun start(pcm: ByteArray, listener: PlaybackListener) {
        played.add(pcm)
        this.listener = listener
    }

    override fun stop() {
        stopCalls++
    }

    override fun release() {
        releaseCalls++
        listener = null
    }

    /** Drives the completion event the real AudioTrack would deliver. */
    fun finishPlayback() {
        listener?.onDone()
    }
}

/**
 * HTTP boundary double for the voice-turn endpoint only. Every other
 * route throws, so an accidental call outside this slice fails loudly
 * instead of silently passing.
 */
private class VoiceTurnApi(
    var result: () -> VoiceTurnResponse = { defaultResponse() },
) : InterviewApi {
    data class Call(val conversationId: String, val turnId: String, val audio: ByteArray)

    val calls = mutableListOf<Call>()

    companion object {
        fun defaultResponse(
            transcript: String = "I built Android features in Kotlin.",
            assistantMessage: String = "Tell me about a specific feature.",
            audio: ByteArray? = null,
            audioError: String? = null,
        ) = VoiceTurnResponse(
            transcript = transcript,
            assistantMessage = assistantMessage,
            state = ConversationStateDto("experience", emptyList(), null, 1),
            status = "active",
            closing = false,
            audio = audio?.let {
                VoiceAudioDto(
                    format = "pcm",
                    sampleRateHz = 24000,
                    channels = 1,
                    data = java.util.Base64.getEncoder().encodeToString(it),
                )
            },
            audioError = audioError,
        )
    }

    override suspend fun sendVoiceTurn(
        conversationId: String,
        audio: MultipartBody.Part,
        clientTurnId: RequestBody,
    ): VoiceTurnResponse {
        val audioBuffer = okio.Buffer()
        audio.body.writeTo(audioBuffer)
        val idBuffer = okio.Buffer()
        clientTurnId.writeTo(idBuffer)
        calls.add(Call(conversationId, idBuffer.readUtf8(), audioBuffer.readByteArray()))
        return result()
    }

    // --- Everything below is out of scope for one voice turn. ---

    override suspend fun createExperienceProfile(body: CreateExperienceProfileRequest): CreateExperienceProfileResponse =
        throw UnsupportedOperationException()
    override suspend fun parseResume(file: MultipartBody.Part): ResumeParseResponse =
        throw UnsupportedOperationException()
    override suspend fun createConversation(body: CreateConversationRequest): CreateConversationResponse =
        throw UnsupportedOperationException()
    override suspend fun sendTurn(conversationId: String, body: SendTurnRequest): SendTurnResponse =
        throw UnsupportedOperationException()
    override suspend fun getOpening(conversationId: String): OpeningResponse =
        throw UnsupportedOperationException()
    override suspend fun getConversation(conversationId: String): GetConversationResponse =
        throw UnsupportedOperationException()
    override suspend fun generateFeedback(conversationId: String): Response<FeedbackDto> =
        throw UnsupportedOperationException()
    override suspend fun getFeedback(conversationId: String): FeedbackDto =
        throw UnsupportedOperationException()
    override suspend fun submitRetry(conversationId: String, body: SubmitRetryRequestDto): Response<RetryResponseDto> =
        throw UnsupportedOperationException()
    override suspend fun getCurrentRetry(conversationId: String, answerMessageId: String): RetryResponseDto =
        throw UnsupportedOperationException()
    override suspend fun regenerateRetryFeedback(conversationId: String, answerMessageId: String): RetryResponseDto =
        throw UnsupportedOperationException()
    override suspend fun transcribeAudio(audio: MultipartBody.Part): id.hanifalfaqih.aienglishinterview.data.remote.TranscriptionResponse =
        throw UnsupportedOperationException()
}

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceTurnSliceTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** ~1 s of voiced speech at 16 kHz mono, using a distinctive marker sample. */
    private fun spokenAnswer(marker: Short = 0x1234): List<ShortArray> =
        List(10) { ShortArray(STT_CHUNK_SHORTS) { marker } }

    private class Harness(
        val api: VoiceTurnApi,
        val mic: ScriptedMic,
        val driver: RecordingDriver,
        val fallback: FakeVoiceSynthesizer,
        val vm: InterviewViewModel,
        val recognizer: BackendVoiceRecognizer,
    )

    private fun kotlinx.coroutines.test.TestScope.harness(
        api: VoiceTurnApi = VoiceTurnApi(),
        marker: Short = 0x1234,
    ): Harness {
        val mic = ScriptedMic(spokenAnswer(marker))
        val driver = RecordingDriver()
        val fallback = FakeVoiceSynthesizer()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val recognizer = BackendVoiceRecognizer(
            captureFactory = { mic },
            scope = CoroutineScope(SupervisorJob() + testDispatcher),
            io = testDispatcher,
        )
        val vm = InterviewViewModel(
            conversationId = "conv-slice",
            repository = ConversationRepository(
                api,
                base64Decode = { java.util.Base64.getDecoder().decode(it) },
            ),
            recognizer = recognizer,
            synthesizer = fallback,
            audioPlayer = BackendAudioPlayer(driver),
        )
        return Harness(api, mic, driver, fallback, vm, recognizer)
    }

    @Test
    fun oneRecordingAction_producesOneVoiceTurnWithCapturedPcm() = runTest(dispatcher) {
        val h = harness()
        advanceUntilIdle()

        h.vm.startVoiceInput()
        assertEquals(VoicePhase.LISTENING, h.vm.voicePhase)
        advanceUntilIdle()

        // Exactly one request for one recording action.
        assertEquals(1, h.api.calls.size)
        val call = h.api.calls.single()
        assertEquals("conv-slice", call.conversationId)
        assertTrue(call.turnId.isNotBlank())

        // The uploaded payload is exactly the captured PCM: 10 chunks of
        // 1600 samples at 2 bytes per sample, little-endian.
        assertEquals(10 * STT_CHUNK_SHORTS * 2, call.audio.size)
        val decoded = ShortArray(10 * STT_CHUNK_SHORTS)
        java.nio.ByteBuffer.wrap(call.audio)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(decoded)
        assertTrue(decoded.all { it == 0x1234.toShort() })

        // Capture lifecycle closed cleanly.
        assertEquals(1, h.mic.openCalls)
        assertEquals(1, h.mic.closeCalls)
    }

    @Test
    fun backendTranscriptAndAssistantResponse_reachConversationState() = runTest(dispatcher) {
        val h = harness()
        advanceUntilIdle()

        h.vm.startVoiceInput()
        advanceUntilIdle()

        // Both lines come from the backend response — nothing is fabricated
        // on the client, and the user line carries the ASR transcript.
        assertEquals(
            listOf("I built Android features in Kotlin.", "Tell me about a specific feature."),
            h.vm.lines.map { it.text },
        )
        assertEquals(
            listOf(true, false),
            h.vm.lines.map { it.isUser },
        )
        assertNull(h.vm.error)
        assertFalse(h.vm.sending)
        assertFalse(h.vm.isClosed)
        assertEquals(VoicePhase.IDLE, h.vm.voicePhase)
    }

    @Test
    fun assistantPcmAudio_reachesExistingPlaybackAbstraction() = runTest(dispatcher) {
        val pcm = byteArrayOf(11, 22, 33, 44)
        val h = harness(api = VoiceTurnApi(result = { VoiceTurnApi.defaultResponse(audio = pcm) }))
        advanceUntilIdle()

        h.vm.startVoiceInput()
        advanceUntilIdle()

        // The real BackendAudioPlayer handed the decoded PCM to AudioTrack.
        assertEquals(1, h.driver.played.size)
        assertTrue(h.driver.played.single().contentEquals(pcm))
        // Backend audio is authoritative: the on-device fallback stays silent.
        assertTrue(h.fallback.spoken.isEmpty())
        assertEquals(VoicePhase.SPEAKING, h.vm.voicePhase)

        // Playback completion returns the UI to a usable state.
        h.driver.finishPlayback()
        advanceUntilIdle()
        assertEquals(VoicePhase.IDLE, h.vm.voicePhase)
    }

    @Test
    fun nullAssistantAudio_keepsTextUsableViaFallback() = runTest(dispatcher) {
        val h = harness(api = VoiceTurnApi(result = { VoiceTurnApi.defaultResponse(audio = null) }))
        advanceUntilIdle()

        h.vm.startVoiceInput()
        advanceUntilIdle()

        // No audio played, but the conversation is fully usable.
        assertTrue(h.driver.played.isEmpty())
        assertEquals(
            listOf("I built Android features in Kotlin.", "Tell me about a specific feature."),
            h.vm.lines.map { it.text },
        )
        assertNull(h.vm.error)
        assertFalse(h.vm.sending)
        assertEquals(listOf("Tell me about a specific feature."), h.fallback.spoken)
    }

    @Test
    fun malformedAssistantAudio_degradesToTextWithoutFailingTurn() = runTest(dispatcher) {
        val api = VoiceTurnApi(
            result = {
                VoiceTurnApi.defaultResponse().copy(
                    // Wrong sample rate: the repository's envelope check must
                    // reject it rather than feeding AudioTrack bad bytes.
                    audio = VoiceAudioDto("pcm", 16000, 1, "AQID"),
                )
            },
        )
        val h = harness(api = api)
        advanceUntilIdle()

        h.vm.startVoiceInput()
        advanceUntilIdle()

        assertTrue(h.driver.played.isEmpty())
        assertEquals(2, h.vm.lines.size)
        assertNull(h.vm.error)
        assertFalse(h.vm.sending)
        assertEquals(listOf("Tell me about a specific feature."), h.fallback.spoken)
    }

    @Test
    fun repeatedRecordingAction_doesNotDuplicateSubmission() = runTest(dispatcher) {
        val h = harness()
        advanceUntilIdle()

        // Two taps for one utterance: the second is ignored while listening.
        h.vm.startVoiceInput()
        h.vm.startVoiceInput()
        advanceUntilIdle()

        assertEquals(1, h.api.calls.size)
        assertEquals(1, h.mic.openCalls)
        assertEquals(2, h.vm.lines.size)
    }

    @Test
    fun apiError_fabricatesNothingAndStaysRecoverable() = runTest(dispatcher) {
        val api = VoiceTurnApi(
            result = { throw HttpException(Response.error<Any>(502, "".toResponseBody())) },
        )
        val h = harness(api = api)
        advanceUntilIdle()

        h.vm.startVoiceInput()
        advanceUntilIdle()

        // No fake transcript, no fake assistant line, no playback.
        assertEquals(1, h.api.calls.size)
        assertTrue(h.vm.lines.isEmpty())
        assertTrue(h.driver.played.isEmpty())
        assertTrue(h.fallback.spoken.isEmpty())
        // Actionable error through the existing mechanism, sending cleared.
        assertTrue(h.vm.error != null)
        assertFalse(h.vm.sending)
        assertEquals(VoicePhase.IDLE, h.vm.voicePhase)

        // The existing retry mechanism resubmits the SAME audio with the SAME
        // clientTurnId, so the backend replays idempotently.
        val firstTurnId = h.api.calls.single().turnId
        h.vm.retry()
        advanceUntilIdle()
        assertEquals(2, h.api.calls.size)
        assertEquals(firstTurnId, h.api.calls[1].turnId)
        assertTrue(h.api.calls[0].audio.contentEquals(h.api.calls[1].audio))
    }

    @Test
    fun noSpeech422_surfacesVoiceErrorWithoutCreatingTurn() = runTest(dispatcher) {
        val api = VoiceTurnApi(
            result = { throw HttpException(Response.error<Any>(422, "".toResponseBody())) },
        )
        val h = harness(api = api)
        advanceUntilIdle()

        h.vm.startVoiceInput()
        advanceUntilIdle()

        assertEquals(1, h.api.calls.size)
        assertTrue(h.vm.lines.isEmpty())
        assertTrue(h.vm.error!!.contains("speech", ignoreCase = true))
        assertFalse(h.vm.sending)
        // Mic is re-armed for another attempt.
        assertEquals(VoicePhase.IDLE, h.vm.voicePhase)
    }

    @Test
    fun transportFailure_doesNotPretendTurnSucceeded() = runTest(dispatcher) {
        val api = VoiceTurnApi(result = { throw IOException("network down") })
        val h = harness(api = api)
        advanceUntilIdle()

        h.vm.startVoiceInput()
        advanceUntilIdle()

        assertTrue(h.vm.lines.isEmpty())
        assertTrue(h.vm.error != null)
        assertFalse(h.vm.sending)
    }

    @Test
    fun micUnavailable_surfacesErrorWithoutRequest() = runTest(dispatcher) {
        val api = VoiceTurnApi()
        val driver = RecordingDriver()
        val fallback = FakeVoiceSynthesizer()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        // open() always fails: the microphone cannot be acquired.
        val recognizer = BackendVoiceRecognizer(
            captureFactory = {
                object : AudioCapture {
                    override fun open() = false
                    override fun read(buffer: ShortArray) = -1
                    override fun close() = Unit
                }
            },
            scope = CoroutineScope(SupervisorJob() + testDispatcher),
            io = testDispatcher,
        )
        val vm = InterviewViewModel(
            conversationId = "conv-slice",
            repository = ConversationRepository(api),
            recognizer = recognizer,
            synthesizer = fallback,
            audioPlayer = BackendAudioPlayer(driver),
        )
        advanceUntilIdle()

        vm.startVoiceInput()
        advanceUntilIdle()

        // No upload attempted, no turn fabricated, actionable voice error.
        assertTrue(api.calls.isEmpty())
        assertTrue(vm.lines.isEmpty())
        assertTrue(vm.voiceError != null)
        assertFalse(vm.sending)
        assertEquals(VoicePhase.IDLE, vm.voicePhase)
    }
}
