package com.recipebook.server.features.write

import com.recipebook.server.features.common.badRequest
import com.recipebook.server.features.recipes.RecipeUpsertRequest
import com.recipebook.server.features.users.UpdateProfileRequest

internal fun validateProfileUpdate(request: UpdateProfileRequest): ValidatedProfileUpdate {
    val username = request.username.trim()
    if (username.length !in 3..50) {
        badRequest("Username must contain from 3 to 50 characters")
    }
    return ValidatedProfileUpdate(
        username = username,
        bio = request.bio?.trim()?.ifBlank { null },
        avatarUrl = request.avatarUrl?.trim()?.ifBlank { null },
    )
}

internal fun validateRecipeUpsert(request: RecipeUpsertRequest): ValidatedRecipeUpsert {
    val title = request.title.trim()
    val description = request.description.trim()
    val category = request.category.trim()
    val ingredients = request.ingredients.map(String::trim)
    val steps = request.steps.map(String::trim)
    val imageUrls = request.imageUrls.map(String::trim)

    if (title.length !in 1..50) {
        badRequest("Title must contain from 1 to 50 characters")
    }
    if (description.length !in 1..1000) {
        badRequest("Description must contain from 1 to 1000 characters")
    }
    if (category.isBlank()) {
        badRequest("Category is required")
    }
    if (request.cookingTimeMinutes <= 0) {
        badRequest("Cooking time must be greater than 0")
    }
    if (ingredients.isEmpty() || ingredients.any { it.isBlank() }) {
        badRequest("Ingredients must contain at least one non-empty value")
    }
    if (steps.isEmpty() || steps.any { it.isBlank() }) {
        badRequest("Steps must contain at least one non-empty value")
    }
    if (imageUrls.size > 5) {
        badRequest("A recipe can contain at most 5 images")
    }
    return ValidatedRecipeUpsert(
        title = title,
        description = description,
        category = category,
        cookingTimeMinutes = request.cookingTimeMinutes,
        ingredients = ingredients,
        steps = steps,
        imageUrls = imageUrls,
    )
}

internal fun validateCommentText(rawText: String): String {
    val text = rawText.trim()
    if (text.isBlank() || text.length > 500) {
        badRequest("Comment must contain from 1 to 500 characters")
    }
    return text
}
