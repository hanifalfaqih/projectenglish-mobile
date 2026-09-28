package id.hanifalfaqih.aienglishinterview.feature.experience

import id.hanifalfaqih.aienglishinterview.core.network.ApiResult
import id.hanifalfaqih.aienglishinterview.core.network.ErrorKind
import id.hanifalfaqih.aienglishinterview.data.model.ExperienceItem
import id.hanifalfaqih.aienglishinterview.data.remote.ConversationStateDto
import id.hanifalfaqih.aienglishinterview.data.remote.CreateConversationRequest
import id.hanifalfaqih.aienglishinterview.data.remote.CreateConversationResponse
import id.hanifalfaqih.aienglishinterview.data.remote.CreateExperienceProfileRequest
import id.hanifalfaqih.aienglishinterview.data.remote.CreateExperienceProfileResponse
import id.hanifalfaqih.aienglishinterview.data.remote.ExperienceItemDto
import id.hanifalfaqih.aienglishinterview.data.remote.FeedbackDto
import id.hanifalfaqih.aienglishinterview.data.remote.GetConversationResponse
import id.hanifalfaqih.aienglishinterview.data.remote.InterviewApi
import id.hanifalfaqih.aienglishinterview.data.remote.ResumeParseResponse
import id.hanifalfaqih.aienglishinterview.data.remote.SendTurnRequest
import id.hanifalfaqih.aienglishinterview.data.remote.SendTurnResponse
import id.hanifalfaqih.aienglishinterview.data.repository.ConversationRepository
import id.hanifalfaqih.aienglishinterview.data.repository.ExperienceRepository
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

private fun httpError(code: Int): HttpException =
    HttpException(Response.error<Any>(code, "e".toResponseBody("text/plain".toMediaType())))

private fun parsedDto() = ExperienceItemDto(
    title = "Backend Engineer",
    organization = "Acme",
    role = "Intern",
    description = "Built APIs",
    skills = listOf("Kotlin"),
)

private class ResumeFakeApi(
    var parseResult: Result<ResumeParseResponse> =
        Result.success(ResumeParseResponse(listOf(parsedDto()))),
    var failure: Throwable? = null,
) : InterviewApi {
    var lastFilePart: MultipartBody.Part? = null
    var profileCalls = 0
    var lastProfileItems: List<ExperienceItemDto>? = null
    var conversationCalls = 0
    var lastProfileId: String? = null

    override suspend fun parseResume(file: MultipartBody.Part): ResumeParseResponse {
        failure?.let { throw it }
        lastFilePart = file
        return parseResult.getOrThrow()
    }

    override suspend fun createExperienceProfile(
        body: CreateExperienceProfileRequest,
    ): CreateExperienceProfileResponse {
        failure?.let { throw it }
        profileCalls++
        lastProfileItems = body.items
        return CreateExperienceProfileResponse("profile-9")
    }

    override suspend fun createConversation(
        body: CreateConversationRequest,
    ): CreateConversationResponse {
        failure?.let { throw it }
        conversationCalls++
        lastProfileId = body.experienceProfileId
        return CreateConversationResponse(
            "conv-9", "active", ConversationStateDto("intro", emptyList(), null, 0),
        )
    }

    override suspend fun sendTurn(conversationId: String, body: SendTurnRequest): SendTurnResponse =
        throw UnsupportedOperationException()
    override suspend fun getConversation(conversationId: String): GetConversationResponse =
        throw UnsupportedOperationException()
    override suspend fun generateFeedback(conversationId: String): Response<FeedbackDto> =
        throw UnsupportedOperationException()
    override suspend fun getFeedback(conversationId: String): FeedbackDto =
        throw UnsupportedOperationException()
}

@OptIn(ExperimentalCoroutinesApi::class)
class ResumeFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        ReviewDraft.items = emptyList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        ReviewDraft.items = emptyList()
    }

    @Test
    fun parseSuccess_sendsMultipartFileField() = runTest(dispatcher) {
        val api = ResumeFakeApi()
        val result = ExperienceRepository(api).parseResume(byteArrayOf(1, 2, 3), "cv.pdf")
        assertTrue(result is ApiResult.Success)
        val disposition = api.lastFilePart?.headers?.get("Content-Disposition").orEmpty()
        assertTrue(disposition.contains("name=\"file\""))
        assertTrue(disposition.contains("filename=\"cv.pdf\""))
    }

    @Test
    fun parseSuccess_mapsItems() = runTest(dispatcher) {
        val result = ExperienceRepository(ResumeFakeApi()).parseResume(byteArrayOf(1), "cv.pdf")
        val items = (result as ApiResult.Success).value
        assertEquals("Backend Engineer", items.single().title)
        assertEquals(listOf("Kotlin"), items.single().skills)
    }

    @Test
    fun parseEmpty_vmErrorsWithoutProfileCall() = runTest(dispatcher) {
        val api = ResumeFakeApi(parseResult = Result.success(ResumeParseResponse(emptyList())))
        val vm = ExperienceViewModel(ExperienceRepository(api), ConversationRepository(api))
        vm.parseResume(byteArrayOf(1), "cv.pdf")
        advanceUntilIdle()
        assertTrue(vm.uiState is ExperienceUiState.Error)
        assertEquals(0, api.profileCalls)
        assertEquals(0, api.conversationCalls)
    }

    @Test
    fun parseErrors_mapCorrectly() = runTest(dispatcher) {
        val codes = listOf(400, 413, 502, 504)
        for (code in codes) {
            val api = ResumeFakeApi(failure = httpError(code))
            val vm = ExperienceViewModel(ExperienceRepository(api), ConversationRepository(api))
            vm.parseResume(byteArrayOf(1), "cv.pdf")
            advanceUntilIdle()
            val state = vm.uiState
            assertTrue(state is ExperienceUiState.Error)
            assertEquals(0, api.profileCalls)
        }
        val net = ResumeFakeApi(failure = IOException("down"))
        val vm = ExperienceViewModel(ExperienceRepository(net), ConversationRepository(net))
        vm.parseResume(byteArrayOf(1), "cv.pdf")
        advanceUntilIdle()
        assertTrue(vm.uiState is ExperienceUiState.Error)

        val malformed = ResumeFakeApi(failure = SerializationException("bad"))
        val vm2 = ExperienceViewModel(
            ExperienceRepository(malformed), ConversationRepository(malformed),
        )
        vm2.parseResume(byteArrayOf(1), "cv.pdf")
        advanceUntilIdle()
        val state = vm2.uiState as ExperienceUiState.Error
        assertTrue(state.message.isNotBlank())
        val direct = ExperienceRepository(malformed).parseResume(byteArrayOf(1), "cv.pdf")
        assertEquals(ErrorKind.MALFORMED, (direct as ApiResult.Error).kind)
    }

    @Test
    fun parseOversize_rejectedBeforeNetwork() = runTest(dispatcher) {
        val api = ResumeFakeApi()
        val vm = ExperienceViewModel(ExperienceRepository(api), ConversationRepository(api))
        vm.parseResume(ByteArray(MAX_RESUME_BYTES + 1))
        advanceUntilIdle()
        assertTrue(vm.uiState is ExperienceUiState.Error)
        assertEquals(0, api.profileCalls)
    }

    @Test
    fun parseSuccess_stashesDraftAndSignalsParsed() = runTest(dispatcher) {
        val api = ResumeFakeApi()
        val vm = ExperienceViewModel(ExperienceRepository(api), ConversationRepository(api))
        vm.parseResume(byteArrayOf(1), "cv.pdf")
        advanceUntilIdle()
        assertEquals(ExperienceUiState.Parsed(1), vm.uiState)
        assertEquals("Backend Engineer", ReviewDraft.items.single().title)
    }

    @Test
    fun reviewConfirm_editsProfileAndCreatesConversation() = runTest(dispatcher) {
        val api = ResumeFakeApi()
        val vm = ReviewViewModel(
            initial = listOf(
                ExperienceItem("Old", "Acme", null, "Did things", listOf("Kotlin")),
            ),
            experienceRepository = ExperienceRepository(api),
            conversationRepository = ConversationRepository(api),
        )
        vm.update(0, ExperienceForm("New", "Acme", "", "Did more things", "Kotlin, SQL"))
        vm.confirm()
        advanceUntilIdle()

        val done = vm.uiState as ReviewUiState.Done
        // Real ids flow through: profile id into conversation, conversation id out.
        assertEquals("conv-9", done.conversationId)
        assertEquals("profile-9", api.lastProfileId)
        assertEquals("New", api.lastProfileItems!!.single().title)
        assertEquals("Did more things", api.lastProfileItems!!.single().description)
        assertEquals(1, api.profileCalls)
        assertEquals(1, api.conversationCalls)
    }

    @Test
    fun reviewConfirm_invalidBlocksWithoutCalls() = runTest(dispatcher) {
        val api = ResumeFakeApi()
        val vm = ReviewViewModel(
            initial = listOf(ExperienceItem("", "Acme", null, "", emptyList())),
            experienceRepository = ExperienceRepository(api),
            conversationRepository = ConversationRepository(api),
        )
        vm.confirm()
        advanceUntilIdle()
        assertTrue(vm.uiState is ReviewUiState.Error)
        assertEquals(0, api.profileCalls)
    }

    @Test
    fun reviewRemoveAdd_behaves() = runTest(dispatcher) {
        val api = ResumeFakeApi()
        val vm = ReviewViewModel(
            initial = listOf(
                ExperienceItem("A", null, null, "DA", emptyList()),
                ExperienceItem("B", null, null, "DB", emptyList()),
            ),
            experienceRepository = ExperienceRepository(api),
            conversationRepository = ConversationRepository(api),
        )
        vm.remove(0)
        assertEquals(1, vm.forms.size)
        vm.add()
        assertEquals(2, vm.forms.size)
        // Added blank form blocks confirm until fixed or removed.
        vm.confirm()
        advanceUntilIdle()
        assertTrue(vm.uiState is ReviewUiState.Error)
        vm.remove(1)
        vm.confirm()
        advanceUntilIdle()
        assertTrue(vm.uiState is ReviewUiState.Done)
        assertEquals("B", api.lastProfileItems!!.single().title)
    }

    @Test
    fun manualFlow_stillFunctional() = runTest(dispatcher) {
        val api = ResumeFakeApi()
        val vm = ExperienceViewModel(ExperienceRepository(api), ConversationRepository(api))
        vm.updateForm(ExperienceForm(title = "T", description = "D"))
        vm.submit()
        advanceUntilIdle()
        val done = vm.uiState as ExperienceUiState.Done
        assertEquals("conv-9", done.conversationId)
        assertEquals("T", api.lastProfileItems!!.single().title)
    }
}
