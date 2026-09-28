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

sealed interface ReviewUiState {
    data object Reviewing : ReviewUiState
    data class Confirming(val step: String) : ReviewUiState
    data class Error(val message: String) : ReviewUiState
    data class Done(val conversationId: String) : ReviewUiState
}

private fun ExperienceItem.toForm() = ExperienceForm(
    title = title,
    organization = organization.orEmpty(),
    role = role.orEmpty(),
    description = description,
    skillsRaw = skills.joinToString(", "),
)

/**
 * Human verification of AI-extracted experiences before they become
 * interview context. Edits/removals/additions are local until confirm,
 * which reuses the shared profile → conversation chain.
 */
class ReviewViewModel(
    initial: List<ExperienceItem> = ReviewDraft.items,
    private val experienceRepository: ExperienceRepository = ExperienceRepository(),
    private val conversationRepository: ConversationRepository = ConversationRepository(),
) : ViewModel() {

    var forms by mutableStateOf(initial.map { it.toForm() })
        private set

    var uiState by mutableStateOf<ReviewUiState>(ReviewUiState.Reviewing)
        private set

    fun update(index: Int, form: ExperienceForm) {
        forms = forms.toMutableList().also { it[index] = form }
        if (uiState is ReviewUiState.Error) {
            uiState = ReviewUiState.Reviewing
        }
    }

    fun remove(index: Int) {
        forms = forms.toMutableList().also { it.removeAt(index) }
    }

    fun add() {
        forms = forms + ExperienceForm()
    }

    fun confirm() {
        val invalid = forms.indexOfFirst { it.titleError || it.descriptionError }
        if (invalid >= 0 || forms.isEmpty()) {
            uiState = ReviewUiState.Error(
                "Each experience needs a title and description, " +
                    "or remove empty entries before continuing.",
            )
            return
        }
        viewModelScope.launch {
            uiState = ReviewUiState.Confirming("Creating experience profile…")
            when (val result = createInterviewFor(
                forms.map { it.toItem() },
                experienceRepository,
                conversationRepository,
                onStep = { uiState = ReviewUiState.Confirming(it) },
            )) {
                is ApiResult.Error -> uiState = ReviewUiState.Error(result.message)
                is ApiResult.Success -> {
                    ReviewDraft.items = emptyList()
                    uiState = ReviewUiState.Done(result.value)
                }
            }
        }
    }
}
