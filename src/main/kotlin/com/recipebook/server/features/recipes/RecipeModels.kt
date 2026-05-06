package com.recipebook.server.features.recipes

import com.recipebook.server.features.users.UserSummaryDto
import kotlinx.serialization.Serializable

@Serializable
data class RecipeUpsertRequest(
    val title: String,
    val description: String,
    val category: String,
    val cookingTimeMinutes: Int,
    val ingredients: List<String>,
    val steps: List<String>,
    val imageUrls: List<String> = emptyList(),
)

@Serializable
data class RatingRequest(
    val value: Int,
)

@Serializable
data class RecipeSummaryDto(
    val id: String,
    val title: String,
    val description: String,
    val category: String,
    val cookingTimeMinutes: Int,
    val imageUrl: String? = null,
    val author: UserSummaryDto,
    val createdAt: String,
    val updatedAt: String,
    val likesCount: Int,
    val dislikesCount: Int,
    val rating: Int,
    val isFavorite: Boolean,
    val myRating: Int? = null,
)

@Serializable
data class RecipeDetailsDto(
    val id: String,
    val title: String,
    val description: String,
    val category: String,
    val cookingTimeMinutes: Int,
    val ingredients: List<String>,
    val steps: List<String>,
    val imageUrls: List<String>,
    val author: UserSummaryDto,
    val createdAt: String,
    val updatedAt: String,
    val likesCount: Int,
    val dislikesCount: Int,
    val rating: Int,
    val isFavorite: Boolean,
    val myRating: Int? = null,
)
