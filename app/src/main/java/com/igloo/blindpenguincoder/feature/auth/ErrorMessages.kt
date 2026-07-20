package com.igloo.blindpenguincoder.feature.auth

import com.igloo.blindpenguincoder.core.error.AppError

fun AppError.toDisplayMessage(): String = when (this) {
    is AppError.Api -> message
    AppError.Unauthorized -> "Incorrect email or password."
    AppError.Network -> "Couldn't reach the server. Check the address, port, and network connection."
    AppError.Timeout -> "The server didn't respond within 10 seconds. Check the address and try again."
    AppError.TlsVerification ->
        "Couldn't verify this server's HTTPS certificate. Check the certificate or use the correct HTTP address."
    is AppError.UnsafeRedirect -> message
    is AppError.Validation -> message
    is AppError.Unexpected -> "Something went wrong. Please try again."
}
