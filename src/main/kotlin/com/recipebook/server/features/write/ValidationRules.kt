package com.recipebook.server.features.write

import com.recipebook.server.features.common.badRequest
import com.recipebook.server.features.recipes.RecipeUpsertRequest
import com.recipebook.server.features.users.UpdateProfileRequest

internal fun validateProfileUpdate(request: UpdateProfileRequest) {
    val username = request.username.trim()
    if (username.length !in 3..50) {
        badRequest("Username must contain from 3 to 50 characters")
    }
}

internal fun validateRecipeUpsert(request: RecipeUpsertRequest) {
    if (request.title.trim().length !in 1..50) {
        badRequest("Title must contain from 1 to 50 characters")
    }
    if (request.description.trim().length !in 1..1000) {
        badRequest("Description must contain from 1 to 1000 characters")
    }
    if (request.category.trim().isBlank()) {
        badRequest("Category is required")
    }
    if (request.cookingTimeMinutes <= 0) {
        badRequest("Cooking time must be greater than 0")
    }
    if (request.ingredients.isEmpty() || request.ingredients.any { it.isBlank() }) {
        badRequest("Ingredients must contain at least one non-empty value")
    }
    if (request.steps.isEmpty() || request.steps.any { it.isBlank() }) {
        badRequest("Steps must contain at least one non-empty value")
    }
    if (request.imageUrls.size > 5) {
        badRequest("A recipe can contain at most 5 images")
    }
}
