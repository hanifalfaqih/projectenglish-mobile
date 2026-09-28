package id.hanifalfaqih.aienglishinterview.feature.feedback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.hanifalfaqih.aienglishinterview.core.network.ApiResult
import id.hanifalfaqih.aienglishinterview.data.model.Feedback
import id.hanifalfaqih.aienglishinterview.data.repository.FeedbackRepository
import kotlinx.coroutines.launch

sealed interface FeedbackUiState {
    data object Loading : FeedbackUiState
    data class Content(val feedback: Feedback) : FeedbackUiState
    data class Error(val message: String) : FeedbackUiState
}

/**
 * Feedback for one completed interview. Loads once via the idempotent
 * generate endpoint (201 new / 200 existing are both success); retry
 * re-issues the same call, which the backend answers from the persisted
 * artifact. Duplicate in-flight loads are ignored.
 */
class FeedbackViewModel(
    val conversationId: String,
    private val repository: FeedbackRepository = FeedbackRepository(),
) : ViewModel() {

    var uiState by mutableStateOf<FeedbackUiState>(FeedbackUiState.Loading)
        private set

    private var loading = false

    init {
        load()
    }

    fun load() {
        if (loading) return
        loading = true
        uiState = FeedbackUiState.Loading
        viewModelScope.launch {
            when (val result = repository.generateFeedback(conversationId)) {
                is ApiResult.Success -> {
                    uiState = FeedbackUiState.Content(result.value.feedback)
                }
                is ApiResult.Error -> {
                    uiState = FeedbackUiState.Error(result.message)
                }
            }
            loading = false
        }
    }

    fun retry() = load()
}
