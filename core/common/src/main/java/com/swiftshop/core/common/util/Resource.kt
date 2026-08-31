package com.swiftshop.core.common.util

/**
 * Standard three-state wrapper for anything a ViewModel exposes to Compose
 * screens that isn't already a plain domain [Result]. Use this for
 * `StateFlow<Resource<T>>` in ViewModels instead of ad-hoc per-feature
 * loading/error booleans (which is the pattern several `feature` modules
 * had drifted into before this module existed).
 */
sealed class Resource<out T> {
    data object Loading : Resource<Nothing>()
    data class Success<T>(val data: T) : Resource<T>()
    data class Error(val message: String, val throwable: Throwable? = null) : Resource<Nothing>()

    fun dataOrNull(): T? = (this as? Success)?.data
}

/** Maps a Kotlin [Result] (as returned by every repository in this codebase) to a [Resource]. */
fun <T> Result<T>.toResource(): Resource<T> = fold(
    onSuccess = { Resource.Success(it) },
    onFailure = { Resource.Error(it.message ?: "Something went wrong. Please try again.", it) }
)

inline fun <T> Resource<T>.onSuccess(action: (T) -> Unit): Resource<T> {
    if (this is Resource.Success) action(data)
    return this
}

inline fun <T> Resource<T>.onError(action: (String, Throwable?) -> Unit): Resource<T> {
    if (this is Resource.Error) action(message, throwable)
    return this
}
