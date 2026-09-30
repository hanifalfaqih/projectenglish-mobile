package id.hanifalfaqih.aienglishinterview.feature.experience

import id.hanifalfaqih.aienglishinterview.data.model.ExperienceItem

/**
 * In-memory Slice 2 entry state. Survives navigation (back/edit) within
 * the process; never persisted. Mirrors the [ReviewDraft] pattern.
 */
object ManualDraft {
    var type: ExperienceType? = null
    var name: String = ""
    var role: String = ""
    var did: String = ""
    var challenge: String = ""
    var result: String = ""

    fun clear() {
        type = null
        name = ""
        role = ""
        did = ""
        challenge = ""
        result = ""
    }

    val nameError: Boolean get() = name.isBlank()
    val didError: Boolean get() = did.isBlank()

    /**
     * Maps the manual entry to the backend item contract. Challenge/result
     * fold into the description as labeled paragraphs; the backend
     * contract (title/organization/role/description/skills) is unchanged.
     */
    fun toItem(): ExperienceItem {
        val parts = listOf(
            did.trim(),
            challenge.trim().ifEmpty { null }?.let { "Challenge: $it" },
            result.trim().ifEmpty { null }?.let { "Result: $it" },
        ).filterNotNull()
        return ExperienceItem(
            title = name.trim(),
            organization = null,
            role = role.trim().ifEmpty { null },
            description = parts.joinToString("\n\n"),
            skills = emptyList(),
        )
    }
}

/** Exactly one experience chosen as the interview context. */
object SelectedExperience {
    var item: ExperienceItem? = null
    var typeLabel: String? = null

    fun clear() {
        item = null
        typeLabel = null
    }
}
