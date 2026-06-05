package com.recipebook.server.features.write

data class ValidatedProfileUpdate(
    val username: String,
    val bio: String?,
    val avatarUrl: String?,
)

data class ValidatedRecipeUpsert(
    val title: String,
    val description: String,
    val category: String,
    val cookingTimeMinutes: Int,
    val ingredients: List<String>,
    val steps: List<String>,
    val imageUrls: List<String>,
)
