package com.igloo.blindpenguincoder.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Shared response envelope used by most Igloo JSON endpoints:
 * `{ "error": bool, "message": string?, "data": T? }`.
 */
@Serializable
data class ApiEnvelope<T>(
    val error: Boolean,
    val message: String? = null,
    val data: T? = null,
)

/** Envelope for endpoints that return only `error` and `message`. */
@Serializable
data class MessageResponse(
    val error: Boolean,
    val message: String? = null,
)

/**
 * Go `sql.NullString` wire format: `{ "String": "...", "Valid": bool }`.
 */
@Serializable
data class SqlNullString(
    @SerialName("String") val value: String,
    @SerialName("Valid") val valid: Boolean,
) {
    fun orNull(): String? = if (valid) value else null
}

@Serializable
data class SqlNullInt64(
    @SerialName("Int64") val value: Long,
    @SerialName("Valid") val valid: Boolean,
) {
    fun orNull(): Long? = if (valid) value else null
}

@Serializable
data class SqlNullFloat64(
    @SerialName("Float64") val value: Double,
    @SerialName("Valid") val valid: Boolean,
) {
    fun orNull(): Double? = if (valid) value else null
}

@Serializable
enum class SortOrder {
    @SerialName("asc") Ascending,
    @SerialName("desc") Descending,
}
