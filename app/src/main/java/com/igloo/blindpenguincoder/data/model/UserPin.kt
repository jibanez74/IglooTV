package com.igloo.blindpenguincoder.data.model

import kotlinx.serialization.Serializable

@Serializable
data class VerifyUserPinRequest(
    val pin: String,
)

/** Payload of the PIN verification envelope. A wrong PIN is a 200 with `valid = false`. */
@Serializable
data class UserPinVerifyData(
    val valid: Boolean,
)
