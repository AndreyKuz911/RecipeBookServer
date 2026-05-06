package com.recipebook.server.features.comments

import com.recipebook.server.features.users.UserSummaryDto
import kotlinx.serialization.Serializable

@Serializable
data class CreateCommentRequest(
    val text: String,
    val parentCommentId: String? = null,
)

@Serializable
data class CommentDto(
    val id: String,
    val recipeId: String,
    val parentCommentId: String? = null,
    val text: String,
    val createdAt: String,
    val author: UserSummaryDto,
    val replies: List<CommentDto> = emptyList(),
)
