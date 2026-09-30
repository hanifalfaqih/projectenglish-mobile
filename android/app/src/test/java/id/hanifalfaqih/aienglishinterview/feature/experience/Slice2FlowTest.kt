package id.hanifalfaqih.aienglishinterview.feature.experience

import id.hanifalfaqih.aienglishinterview.data.model.ExperienceItem
import id.hanifalfaqih.aienglishinterview.data.remote.CreateConversationRequest
import id.hanifalfaqih.aienglishinterview.data.remote.CreateConversationResponse
import id.hanifalfaqih.aienglishinterview.data.remote.ConversationStateDto
import id.hanifalfaqih.aienglishinterview.data.remote.CreateExperienceProfileRequest
import id.hanifalfaqih.aienglishinterview.data.remote.CreateExperienceProfileResponse
import id.hanifalfaqih.aienglishinterview.data.remote.ExperienceItemDto
import id.hanifalfaqih.aienglishinterview.data.remote.FeedbackDto
import id.hanifalfaqih.aienglishinterview.data.remote.GetConversationResponse
import id.hanifalfaqih.aienglishinterview.data.remote.OpeningResponse
import id.hanifalfaqih.aienglishinterview.data.remote.SubmitRetryRequestDto
import id.hanifalfaqih.aienglishinterview.data.remote.RetryResponseDto
import id.hanifalfaqih.aienglishinterview.data.remote.InterviewApi
import id.hanifalfaqih.aienglishinterview.data.remote.ResumeParseResponse
import id.hanifalfaqih.aienglishinterview.data.remote.SendTurnRequest
import id.hanifalfaqih.aienglishinterview.data.remote.SendTurnResponse
import id.hanifalfaqih.aienglishinterview.data.remote.VoiceTurnResponse
import id.hanifalfaqih.aienglishinterview.data.repository.ConversationRepository
import id.hanifalfaqih.aienglishinterview.data.repository.ExperienceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MultipartBody
import okhttp3.RequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response as RetrofitResponse

private fun parsedDto() = ExperienceItemDto(
    title = "Backend Engineer",
    organization = "Acme",
    role = "Intern",
    description = "Built billing webhooks.",
    skills = listOf("Kotlin"),
)

/** Minimal InterviewApi double for the Slice 2 view models. */
private class Slice2FakeApi(
    var parseItems: List<ExperienceItemDto> = listOf(parsedDto()),
    var failure: HttpException? = null,
) : InterviewApi {
    var profileCalls = 0
    var lastProfileItems: List<ExperienceItemDto> = emptyList()
    var conversationCalls = 0
    var lastFileName: String? = null
    var lastFileMime: String? = null

    override suspend fun createExperienceProfile(
        body: CreateExperienceProfileRequest,
    ): CreateExperienceProfileResponse {
        failure?.let { throw it }
        profileCalls++
        lastProfileItems = body.items
        return CreateExperienceProfileResponse(id = "profile-1")
    }

    override suspend fun parseResume(file: MultipartBody.Part): ResumeParseResponse {
        failure?.let { throw it }
        lastFileName = file.headers?.get("Content-Disposition")
        lastFileMime = file.body.contentType().toString()
        return ResumeParseResponse(items = parseItems)
    }

    override suspend fun createConversation(
        body: CreateConversationRequest,
    ): CreateConversationResponse {
        failure?.let { throw it }
        conversationCalls++
        return CreateConversationResponse(
            "conv-1", "active", ConversationStateDto("intro", emptyList(), null, 0),
        )
    }

    override suspend fun sendTurn(
        conversationId: String,
        body: SendTurnRequest,
    ): SendTurnResponse = throw UnsupportedOperationException()

    override suspend fun sendVoiceTurn(
        conversationId: String,
        audio: MultipartBody.Part,
        clientTurnId: RequestBody,
    ): VoiceTurnResponse = throw UnsupportedOperationException()

    override suspend fun getOpening(conversationId: String): OpeningResponse =
        throw UnsupportedOperationException()

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


    override suspend fun getConversation(
        conversationId: String,
    ): GetConversationResponse = throw UnsupportedOperationException()

    override suspend fun generateFeedback(
        conversationId: String,
    ): RetrofitResponse<FeedbackDto> = throw UnsupportedOperationException()

    override suspend fun getFeedback(conversationId: String): FeedbackDto =
        throw UnsupportedOperationException()

    override suspend fun transcribeAudio(audio: okhttp3.MultipartBody.Part): id.hanifalfaqih.aienglishinterview.data.remote.TranscriptionResponse =
        throw UnsupportedOperationException()
}

private fun httpError(code: Int): HttpException =
    HttpException(RetrofitResponse.error<Any>(code, okhttp3.ResponseBody.create(null, "")))

@OptIn(ExperimentalCoroutinesApi::class)
class Slice2FlowTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        ManualDraft.clear()
        SelectedExperience.clear()
        ReviewDraft.items = emptyList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        ManualDraft.clear()
        SelectedExperience.clear()
        ReviewDraft.items = emptyList()
    }

    @Test
    fun manualForm_gatesOnRequiredFields() {
        val vm = ManualFormViewModel()
        assertFalse(vm.canContinue)
        vm.name = "Campus Cart"
        assertFalse(vm.canContinue)
        vm.did = "Built a cart feature."
        assertTrue(vm.canContinue)
        vm.saveToDraft()
        assertEquals("Campus Cart", ManualDraft.name)
    }

    @Test
    fun manualForm_seedsFromDraftForEditReturn() {
        ManualDraft.name = "Kept"
        ManualDraft.did = "Kept work"
        val vm = ManualFormViewModel()
        assertEquals("Kept", vm.name)
        assertTrue(vm.canContinue)
    }

    @Test
    fun confirmation_sendsExactlyOneItemThenConversation() = runTest(dispatcher) {
        val api = Slice2FakeApi()
        val vm = ConfirmationViewModel(
            ExperienceRepository(api),
            ConversationRepository(api),
        )
        SelectedExperience.item = ExperienceItem(
            title = "Campus Cart",
            organization = null,
            role = "Android dev",
            description = "Built a cart feature.",
            skills = emptyList(),
        )
        SelectedExperience.typeLabel = "Project"
        vm.practice()
        advanceUntilIdle()
        val done = vm.uiState as ConfirmationUiState.Done
        assertEquals("conv-1", done.conversationId)
        assertEquals(1, api.profileCalls)
        assertEquals(1, api.lastProfileItems.size)
        assertEquals("Campus Cart", api.lastProfileItems.single().title)
        assertEquals(1, api.conversationCalls)
    }

    @Test
    fun confirmation_backendErrorSurfacesWithoutDone() = runTest(dispatcher) {
        val api = Slice2FakeApi(failure = httpError(502))
        val vm = ConfirmationViewModel(
            ExperienceRepository(api),
            ConversationRepository(api),
        )
        SelectedExperience.item = ManualDraft.apply {
            name = "X"
            did = "Y"
        }.toItem()
        vm.practice()
        advanceUntilIdle()
        assertTrue(vm.uiState is ConfirmationUiState.Error)
    }

    @Test
    fun confirmation_noSelectionDoesNothing() = runTest(dispatcher) {
        val api = Slice2FakeApi()
        val vm = ConfirmationViewModel(
            ExperienceRepository(api),
            ConversationRepository(api),
        )
        vm.practice()
        advanceUntilIdle()
        assertTrue(vm.uiState is ConfirmationUiState.Reviewing)
        assertEquals(0, api.profileCalls)
    }

    @Test
    fun import_parsesDocxWithDocxMime() = runTest(dispatcher) {
        val api = Slice2FakeApi()
        val vm = ImportResumeViewModel(ExperienceRepository(api))
        vm.parse(byteArrayOf(1, 2, 3), "cv.docx")
        advanceUntilIdle()
        assertTrue(vm.uiState is ImportUiState.Parsed)
        assertTrue(api.lastFileMime.orEmpty().contains("wordprocessingml"))
        assertEquals(1, ReviewDraft.items.size)
        assertEquals("Backend Engineer", ReviewDraft.items.single().title)
    }

    @Test
    fun import_emptyResultErrorsWithoutDraft() = runTest(dispatcher) {
        val api = Slice2FakeApi(parseItems = emptyList())
        val vm = ImportResumeViewModel(ExperienceRepository(api))
        vm.parse(byteArrayOf(1), "cv.pdf")
        advanceUntilIdle()
        assertTrue(vm.uiState is ImportUiState.Error)
        assertTrue(ReviewDraft.items.isEmpty())
    }

    @Test
    fun import_backendErrorSurfaces() = runTest(dispatcher) {
        val api = Slice2FakeApi(failure = httpError(413))
        val vm = ImportResumeViewModel(ExperienceRepository(api))
        vm.parse(byteArrayOf(1), "cv.pdf")
        advanceUntilIdle()
        assertTrue(vm.uiState is ImportUiState.Error)
        assertTrue(ReviewDraft.items.isEmpty())
    }

    @Test
    fun import_oversizeRejectedBeforeNetwork() = runTest(dispatcher) {
        val api = Slice2FakeApi()
        val vm = ImportResumeViewModel(ExperienceRepository(api))
        vm.parse(ByteArray(MAX_IMPORT_BYTES + 1), "cv.pdf")
        advanceUntilIdle()
        assertTrue(vm.uiState is ImportUiState.Error)
        assertEquals(null, api.lastFileName)
    }
}
