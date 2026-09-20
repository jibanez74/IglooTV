package com.igloo.blindpenguincoder.data.model

/**
 * A paired user as the UI sees one. Deliberately carries no token, so a credential
 * cannot reach a composable or be captured in saved instance state.
 */
data class ProfileSummary(
    val userId: Long,
    val name: String,
    val avatarUrl: String?,
    val hasPin: Boolean,
)
