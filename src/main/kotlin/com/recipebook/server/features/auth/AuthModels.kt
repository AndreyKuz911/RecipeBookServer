package com.recipebook.server.features.auth

import com.recipebook.server.features.recipes.RecipeSummaryDto
import com.recipebook.server.features.users.UserProfileDto
import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val email: String,
    val username: String,
    val password: String,
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
)

@Serializable
data class AuthResponse(
    val token: String,
    val user: UserProfileDto,
)

@Serializable
data class PagedRecipesResponse(
    val items: List<RecipeSummaryDto>,
    val page: Int,
    val limit: Int,
    val total: Int,
)
