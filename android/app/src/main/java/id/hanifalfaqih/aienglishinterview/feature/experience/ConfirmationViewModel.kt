package id.hanifalfaqih.aienglishinterview.feature.experience

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.hanifalfaqih.aienglishinterview.core.network.ApiResult
import id.hanifalfaqih.aienglishinterview.data.repository.ConversationRepository
import id.hanifalfaqih.aienglishinterview.data.repository.ExperienceRepository
import kotlinx.coroutines.launch

sealed interface ConfirmationUiState {
    data object Reviewing : ConfirmationUiState
    data class Practicing(val step: String) : ConfirmationUiState
    data class Error(val message: String) : ConfirmationUiState
    data class Done(val conversationId: String) : ConfirmationUiState
}

/**
 * Hands exactly one selected experience to the existing interview entry
 * path: POST /experience-profiles (single item) then POST /conversations,
 * reusing the shared chain. Failure degrades to an error + retry; the
 * interview flow itself is untouched (Slice 3 owns it).
 */
class ConfirmationViewModel(
    private val experienceRepository: ExperienceRepository = ExperienceRepository(),
    private val conversationRepository: ConversationRepository = ConversationRepository(),
) : ViewModel() {

    var uiState by mutableStateOf<ConfirmationUiState>(ConfirmationUiState.Reviewing)
        private set

    fun practice() {
        val item = SelectedExperience.item ?: return
        if (uiState is ConfirmationUiState.Practicing) return
        viewModelScope.launch {
            uiState = ConfirmationUiState.Practicing("Creating experience profile…")
            when (val result = createInterviewFor(
                listOf(item),
                experienceRepository,
                conversationRepository,
                onStep = { uiState = ConfirmationUiState.Practicing(it) },
            )) {
                is ApiResult.Error -> uiState = ConfirmationUiState.Error(result.message)
                is ApiResult.Success -> uiState = ConfirmationUiState.Done(result.value)
            }
        }
    }
}
