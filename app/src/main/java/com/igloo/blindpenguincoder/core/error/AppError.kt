package com.igloo.blindpenguincoder.core.error

sealed interface AppError {
    data class Api(val message: String, val status: Int) : AppError
    data object Unauthorized : AppError
    data object Network : AppError
    data object Timeout : AppError
    data object TlsVerification : AppError
    data class UnsafeRedirect(val message: String) : AppError
    data class Validation(val message: String) : AppError
    data class Unexpected(val debugMessage: String?) : AppError
}

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val error: AppError) : ApiResult<Nothing>
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(value))
    is ApiResult.Failure -> this
}

inline fun <T, R> ApiResult<T>.flatMap(transform: (T) -> ApiResult<R>): ApiResult<R> = when (this) {
    is ApiResult.Success -> transform(value)
    is ApiResult.Failure -> this
}
