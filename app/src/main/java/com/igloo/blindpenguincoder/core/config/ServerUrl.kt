package com.igloo.blindpenguincoder.core.config

import java.net.URI
import java.net.URISyntaxException

@ConsistentCopyVisibility
data class ServerAddress private constructor(
    val origin: String,
    val apiBaseUrl: String,
    val scheme: String,
    val hostname: String,
    val effectivePort: Int,
) {
    fun hasSameOrigin(other: ServerAddress): Boolean =
        scheme == other.scheme &&
            hostname == other.hostname &&
            effectivePort == other.effectivePort

    companion object {
        fun fromApiBaseUrl(apiBaseUrl: String): ServerAddress? {
            val uri = parseUri(apiBaseUrl.trim()) ?: return null
            if (!isApiBasePath(uri.rawPath.orEmpty())) return null
            if (uri.rawQuery != null || uri.rawFragment != null) return null
            return fromUriOrigin(uri)
        }

        internal fun fromUriOrigin(uri: URI): ServerAddress? {
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            if (uri.rawUserInfo != null) return null
            if (hasInvalidPortSyntax(uri)) return null

            val rawHost = uri.host ?: return null
            val hostname = rawHost
                .removePrefix("[")
                .removeSuffix("]")
                .lowercase()
            if (hostname.isBlank()) return null

            val explicitPort = uri.port
            if (explicitPort == 0 || explicitPort > 65_535) return null
            val defaultPort = if (scheme == "http") 80 else 443
            val effectivePort = if (explicitPort == -1) defaultPort else explicitPort
            val renderedHost = if (':' in hostname) "[$hostname]" else hostname
            val renderedPort = if (effectivePort == defaultPort) "" else ":$effectivePort"
            val origin = "$scheme://$renderedHost$renderedPort"

            return ServerAddress(
                origin = origin,
                apiBaseUrl = "$origin/api",
                scheme = scheme,
                hostname = hostname,
                effectivePort = effectivePort,
            )
        }
    }
}

sealed interface ServerAddressParseResult {
    data class Valid(val address: ServerAddress) : ServerAddressParseResult
    data class Invalid(val message: String) : ServerAddressParseResult
}

fun parseServerAddress(raw: String): ServerAddressParseResult {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) {
        return ServerAddressParseResult.Invalid(
            "Enter your server address, like http://192.168.1.5:8080.",
        )
    }

    val withScheme = if (SCHEME_SEPARATOR in trimmed) trimmed else "http://$trimmed"
    val uri = parseUri(withScheme)
        ?: return invalidServerAddress()

    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") {
        return ServerAddressParseResult.Invalid("Use an http:// or https:// server address.")
    }
    if (uri.rawUserInfo != null) {
        return ServerAddressParseResult.Invalid("Enter the server address without a username or password.")
    }
    if (uri.rawQuery != null || uri.rawFragment != null) {
        return ServerAddressParseResult.Invalid("Enter only the server address, without a query or fragment.")
    }

    val path = uri.rawPath.orEmpty()
    if (!isOriginPath(path) && !isApiBasePath(path)) {
        return ServerAddressParseResult.Invalid(
            "Enter only the server address, optionally ending in /api.",
        )
    }

    val address = ServerAddress.fromUriOrigin(uri) ?: return invalidServerAddress()
    if (isInvalidIpv4Address(address.hostname)) return invalidServerAddress()
    return ServerAddressParseResult.Valid(address)
}

private fun invalidServerAddress() = ServerAddressParseResult.Invalid(
    "Enter a valid server address, like http://192.168.1.5:8080/api.",
)

private fun isOriginPath(path: String): Boolean = path.isEmpty() || path == "/"

private fun isApiBasePath(path: String): Boolean = path == "/api" || path == "/api/"

private fun parseUri(value: String): URI? = try {
    URI(value)
} catch (_: URISyntaxException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

private fun isInvalidIpv4Address(hostname: String): Boolean {
    if (!hostname.matches(Regex("[0-9.]+"))) return false
    val parts = hostname.split('.')
    return parts.size != 4 || parts.any { part ->
        part.isEmpty() || part.length > 3 || part.toIntOrNull()?.let { it !in 0..255 } != false
    }
}

private fun hasInvalidPortSyntax(uri: URI): Boolean {
    val authority = uri.rawAuthority ?: return true
    val portText = if (authority.startsWith('[')) {
        val closingBracket = authority.indexOf(']')
        if (closingBracket == -1) return true
        val suffix = authority.substring(closingBracket + 1)
        if (suffix.isEmpty()) return false
        if (!suffix.startsWith(':')) return true
        suffix.drop(1)
    } else {
        val colon = authority.lastIndexOf(':')
        if (colon == -1) return false
        authority.substring(colon + 1)
    }
    return portText.isEmpty() || portText.any { !it.isDigit() }
}

private const val SCHEME_SEPARATOR = "://"
