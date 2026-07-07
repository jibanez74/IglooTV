package com.igloo.blindpenguincoder.feature.auth

import com.igloo.blindpenguincoder.core.error.AppError

fun AppError.toDisplayMessage(): String = when (this) {
    is AppError.Api -> message
    AppError.Unauthorized -> "Incorrect email or password."
    AppError.Network -> "Couldn't reach the server. Check the address and your connection."
    is AppError.Validation -> message
    is AppError.Unexpected -> "Something went wrong. Please try again."
}
