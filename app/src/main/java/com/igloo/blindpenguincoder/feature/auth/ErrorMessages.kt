package com.igloo.blindpenguincoder.feature.auth

import com.igloo.blindpenguincoder.core.error.AppError

fun AppError.toDisplayMessage(): String = when (this) {
    is AppError.Api -> message
    AppError.Unauthorized -> "Incorrect email or password."
    AppError.Network -> "Couldn't reach the server. Check the address, port, and network connection."
    AppError.Timeout -> "The server took too long to respond. Check the address and try again."
    AppError.TlsVerification ->
        "Couldn't verify this server's HTTPS certificate. Check the certificate or use the correct HTTP address."
    is AppError.UnsafeRedirect -> message
    is AppError.Validation -> message
    is AppError.Unexpected -> "Something went wrong. Please try again."
}

/** Pairing has no email/password, so [AppError.Unauthorized] needs different wording. */
fun AppError.toQuickConnectDisplayMessage(): String = when (this) {
    AppError.Unauthorized -> "Couldn't pair with the server. Try again."
    else -> toDisplayMessage()
}
