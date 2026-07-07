package com.igloo.blindpenguincoder.core.config

import java.net.URI
import java.net.URISyntaxException

/**
 * Normalizes user-typed server input into a usable API base URL, or returns
 * null when the input cannot be salvaged. Rules: default scheme is http,
 * only http/https are accepted, trailing slashes are stripped, and a bare
 * host gets `/api` appended because all Igloo routes live under it.
 */
fun normalizeServerUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"

    val uri = try {
        URI(withScheme)
    } catch (_: URISyntaxException) {
        return null
    }

    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null

    val host = uri.host?.lowercase() ?: return null
    if (host.isEmpty()) return null

    val port = if (uri.port == -1) "" else ":${uri.port}"
    val path = uri.path.orEmpty().trimEnd('/')
    val effectivePath = path.ifEmpty { "/api" }

    return "$scheme://$host$port$effectivePath"
}
