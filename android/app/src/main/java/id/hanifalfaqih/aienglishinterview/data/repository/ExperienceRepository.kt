package id.hanifalfaqih.aienglishinterview.data.repository

import id.hanifalfaqih.aienglishinterview.core.network.ApiResult
import id.hanifalfaqih.aienglishinterview.data.model.ExperienceItem
import id.hanifalfaqih.aienglishinterview.data.remote.ApiProvider
import id.hanifalfaqih.aienglishinterview.data.remote.CreateExperienceProfileRequest
import id.hanifalfaqih.aienglishinterview.data.remote.ExperienceItemDto
import id.hanifalfaqih.aienglishinterview.data.remote.InterviewApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Experience profiles. Minimum operation: create one profile from the
 * user's entered items and return its backend id.
 */
class ExperienceRepository(
    private val api: InterviewApi = ApiProvider.api,
) {
    suspend fun createExperienceProfile(items: List<ExperienceItem>): ApiResult<String> {
        val request = CreateExperienceProfileRequest(
            items = items.map { it.toDto() },
        )
        return when (val result = apiCall { api.createExperienceProfile(request) }) {
            is ApiResult.Success -> ApiResult.Success(result.value.id)
            is ApiResult.Error -> result
        }
    }

    /**
     * Resume parse: uploads document bytes as multipart `file` and maps the
     * response items (same contract as experience items) to domain models.
     * MIME defaults to PDF; DOCX is sent with its own type so the backend
     * can route it (unsupported types surface as backend errors).
     */
    suspend fun parseResume(
        pdfBytes: ByteArray,
        filename: String,
        mimeType: String = "application/pdf",
    ): ApiResult<List<ExperienceItem>> {
        val part = MultipartBody.Part.createFormData(
            "file",
            filename.ifBlank { "resume.pdf" },
            pdfBytes.toRequestBody(mimeType.toMediaType()),
        )
        return when (val result = apiCall { api.parseResume(part) }) {
            is ApiResult.Success -> ApiResult.Success(result.value.items.map { it.toDomain() })
            is ApiResult.Error -> result
        }
    }

    private fun ExperienceItemDto.toDomain() = ExperienceItem(
        title = title,
        organization = organization,
        role = role,
        description = description,
        skills = skills,
    )

    private fun ExperienceItem.toDto() = ExperienceItemDto(
        title = title,
        organization = organization,
        role = role,
        description = description,
        skills = skills,
    )
}
