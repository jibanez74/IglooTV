package com.igloo.blindpenguincoder.images

/**
 * The sizes the app requests from the backend's TMDB image proxy
 * (`GET /api/tmdb/images/{size}/{file}`), which also accepts `w92` and `original`.
 */
enum class TmdbImageSize(val segment: String) {
    W185("w185"),
    W500("w500"),
    W1280("w1280"),
}

/**
 * Builds a proxied TMDB image URL. The route takes a bare filename segment, but TMDB
 * paths arrive both as `/abc.jpg` and `abc.jpg`, so any leading slash is stripped.
 *
 * With the server's Download images setting on, the field holds the server's own copy instead,
 * an `/api/static/...` path stored at one size, which resolves against the origin as it is.
 */
fun tmdbImageUrl(apiBaseUrl: String, size: TmdbImageSize, path: String?): String? {
    if (path != null && path.startsWith("/api/")) return apiBaseUrl.removeSuffix("/api") + path
    val file = path?.trimStart('/')
    if (file.isNullOrBlank()) return null
    return "$apiBaseUrl/tmdb/images/${size.segment}/$file"
}

/**
 * Builds a proxied YouTube thumbnail URL (`GET /api/youtube/thumbnails/{key}`, hqdefault).
 * Keys are YouTube video ids (`[A-Za-z0-9_-]`), already URL-safe, so nothing is encoded —
 * the same contract [tmdbImageUrl] leans on for its bare filename segment.
 */
fun youtubeThumbnailUrl(apiBaseUrl: String, key: String?): String? {
    val id = key?.trim()
    if (id.isNullOrEmpty()) return null
    return "$apiBaseUrl/youtube/thumbnails/$id"
}

/**
 * Resolves an image value the server stores against its origin: an uploaded avatar, or an album
 * cover or musician photo, each either an absolute URL or a path on the server. Uploads and the
 * artwork the server downloads (its Download images setting) are relative `/api/static/...`
 * paths; `PUT /users/avatar` accepts any string, and covers and photos are otherwise absolute
 * Spotify or Apple URLs, which pass through untouched. Prepending the origin — rather than
 * returning the path as the same-origin web client does — is what lets `isIglooServerUrl`
 * recognise the result and attach the bearer that `/api/static` requires, both in the image
 * loader and in Media3's artwork loads.
 *
 * Anything else (a bare filename, a `data:` URI) has no defined resolution and yields null,
 * which renders as initials or a glyph.
 */
fun serverImageUrl(origin: String, stored: String?): String? {
    val value = stored?.trim()
    if (value.isNullOrBlank()) return null
    return when {
        value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true) -> value
        value.startsWith("/") -> origin.trimEnd('/') + value
        else -> null
    }
}
