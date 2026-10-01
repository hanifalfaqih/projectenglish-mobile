package id.hanifalfaqih.aienglishinterview.feature.experience

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.hanifalfaqih.aienglishinterview.core.network.ApiResult
import id.hanifalfaqih.aienglishinterview.data.repository.ExperienceRepository
import kotlinx.coroutines.launch

sealed interface ImportUiState {
    data object Idle : ImportUiState
    data class Parsing(val step: String) : ImportUiState
    data class Error(val message: String) : ImportUiState
    data class Parsed(val count: Int) : ImportUiState
}

/**
 * Resume import: real backend parse only (`POST resume/parse`), no
 * on-device extraction, no faked experiences. Failures (unreadable,
 * oversize, unsupported, backend) surface as error + retry/manual states.
 */
class ImportResumeViewModel(
    private val experienceRepository: ExperienceRepository = ExperienceRepository(),
) : ViewModel() {

    var uiState by mutableStateOf<ImportUiState>(ImportUiState.Idle)
        private set

    fun onUnreadableFile() {
        uiState = ImportUiState.Error(
            "Could not read that file. Please choose a readable PDF or enter manually.",
        )
    }

    fun parse(bytes: ByteArray, filename: String) {
        if (uiState is ImportUiState.Parsing) return
        if (bytes.size > MAX_IMPORT_BYTES) {
            uiState = ImportUiState.Error(
                "File is larger than 5 MB. Please choose a smaller file or enter manually.",
            )
            return
        }
        uiState = ImportUiState.Parsing("Reading resume…")
        viewModelScope.launch {
            uiState = ImportUiState.Parsing("Extracting experiences…")
            when (val result = experienceRepository.parseResume(bytes, filename, mimeFor(filename))) {
                is ApiResult.Error -> {
                    uiState = ImportUiState.Error(result.message)
                }
                is ApiResult.Success -> {
                    if (result.value.isEmpty()) {
                        uiState = ImportUiState.Error(
                            "No meaningful experience was detected. " +
                                "Please enter your experience manually.",
                        )
                    } else {
                        ReviewDraft.items = result.value
                        uiState = ImportUiState.Parsed(result.value.size)
                    }
                }
            }
        }
    }
}
