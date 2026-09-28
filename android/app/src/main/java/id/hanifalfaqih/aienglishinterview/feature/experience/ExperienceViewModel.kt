package id.hanifalfaqih.aienglishinterview.feature.experience

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.hanifalfaqih.aienglishinterview.core.network.ApiResult
import id.hanifalfaqih.aienglishinterview.data.model.ExperienceItem
import id.hanifalfaqih.aienglishinterview.data.repository.ConversationRepository
import id.hanifalfaqih.aienglishinterview.data.repository.ExperienceRepository
import kotlinx.coroutines.launch

/** Splits a comma-separated skills field into clean entries. */
fun parseSkills(raw: String): List<String> =
    raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }

/** Form state for one experience entry. */
data class ExperienceForm(
    val title: String = "",
    val organization: String = "",
    val role: String = "",
    val description: String = "",
    val skillsRaw: String = "",
) {
    val titleError: Boolean get() = title.isBlank()
    val descriptionError: Boolean get() = description.isBlank()

    fun toItem() = ExperienceItem(
        title = title.trim(),
        organization = organization.trim().ifEmpty { null },
        role = role.trim().ifEmpty { null },
        description = description.trim(),
        skills = parseSkills(skillsRaw),
    )
}

sealed interface ExperienceUiState {
    data object Editing : ExperienceUiState
    data class Submitting(val step: String) : ExperienceUiState
    data class Parsing(val step: String) : ExperienceUiState
    data class Error(val message: String) : ExperienceUiState
    data class Parsed(val count: Int) : ExperienceUiState
    data class Done(val conversationId: String) : ExperienceUiState
}

/** Backend resume limit, enforced client-side before upload. */
const val MAX_RESUME_BYTES = 5 * 1024 * 1024

/**
 * Session-scoped holder for parsed resume items awaiting human review.
 * Written on parse success, consumed by the review destination. Plain
 * in-memory session state — never persisted.
 */
object ReviewDraft {
    var items: List<ExperienceItem> = emptyList()
}

/**
 * Shared profile → conversation chain used by manual submit and review
 * confirm alike. Returns the real conversation id or the first error.
 */
internal suspend fun createInterviewFor(
    items: List<ExperienceItem>,
    experienceRepository: ExperienceRepository,
    conversationRepository: ConversationRepository,
    onStep: (String) -> Unit,
): ApiResult<String> {
    onStep("Creating experience profile…")
    return when (val profile = experienceRepository.createExperienceProfile(items)) {
        is ApiResult.Error -> profile
        is ApiResult.Success -> {
            onStep("Starting interview…")
            when (val conv = conversationRepository.createConversation(profile.value)) {
                is ApiResult.Error -> conv
                is ApiResult.Success -> ApiResult.Success(conv.value.id)
            }
        }
    }
}

/**
 * My Experience flow: validate one entry, create the experience profile,
 * then create the conversation. Surfaces the real conversation id for
 * navigation; no demo ids are used here.
 */
class ExperienceViewModel(
    private val experienceRepository: ExperienceRepository = ExperienceRepository(),
    private val conversationRepository: ConversationRepository = ConversationRepository(),
) : ViewModel() {

    var form by mutableStateOf(ExperienceForm())
        private set

    var uiState by mutableStateOf<ExperienceUiState>(ExperienceUiState.Editing)
        private set

    fun updateForm(next: ExperienceForm) {
        form = next
        if (uiState is ExperienceUiState.Error) {
            uiState = ExperienceUiState.Editing
        }
    }

    fun submit() {
        val item = form.toItem()
        if (form.titleError || form.descriptionError) return
        viewModelScope.launch {
            uiState = ExperienceUiState.Submitting("Creating experience profile…")
            when (val result = createInterviewFor(
                listOf(item),
                experienceRepository,
                conversationRepository,
                onStep = { uiState = ExperienceUiState.Submitting(it) },
            )) {
                is ApiResult.Error -> uiState = ExperienceUiState.Error(result.message)
                is ApiResult.Success -> uiState = ExperienceUiState.Done(result.value)
            }
        }
    }

    fun onUnreadableFile() {
        uiState = ExperienceUiState.Error(
            "Could not read that file. Please choose a readable PDF or enter manually.",
        )
    }

    fun parseResume(pdfBytes: ByteArray, filename: String = "resume.pdf") {
        if (uiState is ExperienceUiState.Parsing) return
        if (pdfBytes.size > MAX_RESUME_BYTES) {
            uiState = ExperienceUiState.Error(
                "Resume is larger than 5 MB. Please choose a smaller PDF or enter manually.",
            )
            return
        }
        uiState = ExperienceUiState.Parsing("Reading resume…")
        viewModelScope.launch {
            uiState = ExperienceUiState.Parsing("Extracting experiences…")
            when (val result = experienceRepository.parseResume(pdfBytes, filename)) {
                is ApiResult.Error -> {
                    uiState = ExperienceUiState.Error(result.message)
                }
                is ApiResult.Success -> {
                    if (result.value.isEmpty()) {
                        uiState = ExperienceUiState.Error(
                            "No meaningful experience was detected. " +
                                "Please enter your experience manually below.",
                        )
                    } else {
                        ReviewDraft.items = result.value
                        uiState = ExperienceUiState.Parsed(result.value.size)
                    }
                }
            }
        }
    }
}
