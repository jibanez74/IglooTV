package com.igloo.blindpenguincoder.images

/** Sizes the backend's TMDB image proxy accepts (`GET /api/tmdb/images/{size}/{file}`). */
enum class TmdbImageSize(val segment: String) {
    W92("w92"),
    W185("w185"),
    W500("w500"),
    W1280("w1280"),
    Original("original"),
}

/**
 * Builds a proxied TMDB image URL. The route takes a bare filename segment, but TMDB
 * paths arrive both as `/abc.jpg` and `abc.jpg`, so any leading slash is stripped.
 */
fun tmdbImageUrl(apiBaseUrl: String, size: TmdbImageSize, path: String?): String? {
    val file = path?.trimStart('/')
    if (file.isNullOrBlank()) return null
    return "$apiBaseUrl/tmdb/images/${size.segment}/$file"
}

/**
 * Resolves a stored avatar value against the server origin. Uploads are saved as the relative
 * `/api/static/avatars/...` path, but `PUT /users/avatar` accepts any string, so an absolute
 * URL is equally valid and passes through untouched. Prepending the origin — rather than
 * returning the path as the same-origin web client does — is what lets `isIglooImageUrl`
 * recognise the result and attach the bearer that `/api/static` requires.
 *
 * Anything else (a bare filename, a `data:` URI) has no defined resolution and yields null,
 * which renders as initials.
 */
fun avatarImageUrl(origin: String, stored: String?): String? {
    val value = stored?.trim()
    if (value.isNullOrBlank()) return null
    return when {
        value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true) -> value
        value.startsWith("/") -> origin.trimEnd('/') + value
        else -> null
    }
}
