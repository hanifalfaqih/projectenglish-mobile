package id.hanifalfaqih.aienglishinterview.core.network

/**
 * Minimal result wrapper for backend calls. Deliberately small: HTTP status
 * failures, transport failures, and malformed payloads. No global error
 * framework; screens map [Error] to their own UI states.
 */
sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>

    data class Error(
        val kind: ErrorKind,
        val message: String,
        val httpCode: Int? = null,
    ) : ApiResult<Nothing>
}

enum class ErrorKind {
    /** Non-2xx HTTP response. */
    HTTP,

    /** Transport failure (no connectivity, timeout, DNS). */
    NETWORK,

    /** 2xx response that could not be decoded. */
    MALFORMED,
}
