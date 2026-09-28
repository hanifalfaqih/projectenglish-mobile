package id.hanifalfaqih.aienglishinterview.data.repository

import id.hanifalfaqih.aienglishinterview.core.network.ApiResult
import id.hanifalfaqih.aienglishinterview.core.network.ErrorKind
import id.hanifalfaqih.aienglishinterview.data.remote.InterviewApi
import java.io.IOException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/**
 * Maps Retrofit throwables to [ApiResult.Error]. Shared by repositories so
 * every endpoint gets identical success/HTTP/network/malformed semantics.
 */
internal suspend fun <T> apiCall(block: suspend () -> T): ApiResult<T> {
    return try {
        ApiResult.Success(block())
    } catch (e: HttpException) {
        ApiResult.Error(
            kind = ErrorKind.HTTP,
            message = "Server error ${e.code()}",
            httpCode = e.code(),
        )
    } catch (e: IOException) {
        ApiResult.Error(
            kind = ErrorKind.NETWORK,
            message = "Network error. Check the backend is reachable.",
        )
    } catch (e: SerializationException) {
        ApiResult.Error(
            kind = ErrorKind.MALFORMED,
            message = "Unexpected server response.",
        )
    } catch (e: IllegalArgumentException) {
        // Thrown by the converter on malformed bodies as well.
        ApiResult.Error(
            kind = ErrorKind.MALFORMED,
            message = "Unexpected server response.",
        )
    }
}
