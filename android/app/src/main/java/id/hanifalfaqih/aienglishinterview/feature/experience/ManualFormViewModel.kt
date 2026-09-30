package id.hanifalfaqih.aienglishinterview.feature.experience

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/**
 * Manual entry form state, seeded from [ManualDraft] so back/edit
 * navigation preserves input. Saved back to the draft on continue.
 * No STAR answers: name + what-you-did are required; the interviewer
 * discovers the rest through follow-ups.
 */
class ManualFormViewModel : ViewModel() {
    var name by mutableStateOf(ManualDraft.name)
    var role by mutableStateOf(ManualDraft.role)
    var did by mutableStateOf(ManualDraft.did)
    var challenge by mutableStateOf(ManualDraft.challenge)
    var result by mutableStateOf(ManualDraft.result)

    val nameError: Boolean get() = name.isBlank()
    val didError: Boolean get() = did.isBlank()
    val canContinue: Boolean get() = !nameError && !didError

    fun saveToDraft() {
        ManualDraft.name = name
        ManualDraft.role = role
        ManualDraft.did = did
        ManualDraft.challenge = challenge
        ManualDraft.result = result
    }
}
