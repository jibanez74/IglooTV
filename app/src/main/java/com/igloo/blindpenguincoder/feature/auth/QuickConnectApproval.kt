package com.igloo.blindpenguincoder.feature.auth

import com.igloo.blindpenguincoder.core.config.ServerAddressParseResult
import com.igloo.blindpenguincoder.core.config.parseServerAddress

internal fun buildQuickConnectApprovalUrl(serverUrl: String): String {
    val parsed = parseServerAddress(serverUrl)
    require(parsed is ServerAddressParseResult.Valid) {
        "Quick Connect requires a valid server origin"
    }
    return "${parsed.address.origin}/settings/account"
}

internal fun wrapApprovalUrlForDisplay(url: String): String = buildString {
    url.forEach { character ->
        append(character)
        if (character in URL_WRAP_DELIMITERS) append(ZERO_WIDTH_SPACE)
    }
}

private val URL_WRAP_DELIMITERS = setOf(':', '/', '.', '-', '_', '?', '&', '=', '[', ']')
private const val ZERO_WIDTH_SPACE = '\u200B'
