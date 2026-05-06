package com.recipebook.server.features.users

import com.recipebook.server.features.recipes.RecipeSummaryDto
import kotlinx.serialization.Serializable

@Serializable
data class UpdateProfileRequest(
    val username: String,
    val bio: String? = null,
    val avatarUrl: String? = null,
)

@Serializable
data class UserSummaryDto(
    val id: String,
    val username: String,
    val avatarUrl: String? = null,
)

@Serializable
data class UserProfileDto(
    val id: String,
    val email: String,
    val username: String,
    val bio: String?,
    val avatarUrl: String?,
    val createdAt: String,
    val recipesCount: Int,
    val followersCount: Int,
    val followingCount: Int,
    val isFollowing: Boolean = false,
)

@Serializable
data class UserWithRecipesDto(
    val profile: UserProfileDto,
    val recipes: List<RecipeSummaryDto>,
)
