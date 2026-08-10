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
