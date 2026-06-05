package com.recipebook.server.features.read

import com.recipebook.server.database.parseSort
import com.recipebook.server.database.parseTimeRange
import com.recipebook.server.features.auth.PagedRecipesResponse
import com.recipebook.server.features.comments.CommentDto
import com.recipebook.server.features.common.badRequest
import com.recipebook.server.features.common.notFound
import com.recipebook.server.features.read.repository.CommentReadRepository
import com.recipebook.server.features.read.repository.ProfileReadRepository
import com.recipebook.server.features.read.repository.RecipeReadRepository
import com.recipebook.server.features.recipes.RecipeDetailsDto
import com.recipebook.server.features.recipes.RecipeSummaryDto
import com.recipebook.server.features.users.UserProfileDto
import java.util.UUID

class ReadService internal constructor(
    private val recipeReadRepository: RecipeReadRepository = RecipeReadRepository(),
    private val profileReadRepository: ProfileReadRepository = ProfileReadRepository(),
    private val commentReadRepository: CommentReadRepository = CommentReadRepository(),
) {
    fun listRecipes(
        page: Int,
        limit: Int,
        query: String?,
        category: String?,
        timeRange: String?,
        sort: String?,
        currentUserId: UUID?,
    ): PagedRecipesResponse {
        validatePage(page, limit)
        val parsedSort = parseSort(sort)
        val parsedTimeRange = parseTimeRange(timeRange)
        val normalizedQuery = query?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedCategory = category?.trim()?.takeIf { it.isNotEmpty() }

        val snapshots = recipeReadRepository.listRecipes(
            currentUserId = currentUserId,
            query = normalizedQuery,
            category = normalizedCategory,
            timeRange = parsedTimeRange,
            sort = parsedSort,
            page = page,
            limit = limit,
        )

        return PagedRecipesResponse(
            items = snapshots.items.map { it.toSummaryDto() },
            page = page,
            limit = limit,
            total = snapshots.total,
        )
    }

    fun getRecipe(recipeId: UUID, currentUserId: UUID?): RecipeDetailsDto {
        val recipe = recipeReadRepository.getRecipe(recipeId, currentUserId) ?: notFound("Recipe not found")
        return recipe.toDetailsDto()
    }

    fun listFeed(userId: UUID): List<RecipeSummaryDto> {
        return recipeReadRepository.listFeed(userId).map { it.toSummaryDto() }
    }

    fun listFavorites(userId: UUID): List<RecipeSummaryDto> {
        return recipeReadRepository.listFavorites(userId).map { it.toSummaryDto() }
    }

    fun listRecipesByAuthor(authorId: UUID, currentUserId: UUID?): List<RecipeSummaryDto> {
        return recipeReadRepository.listByAuthor(authorId, currentUserId).map { it.toSummaryDto() }
    }

    fun getProfile(targetUserId: UUID, viewerId: UUID?): UserProfileDto {
        return profileReadRepository.getProfile(targetUserId, viewerId)
    }

    fun getCurrentProfile(userId: UUID): UserProfileDto {
        return profileReadRepository.getCurrentProfile(userId)
    }

    fun listComments(recipeId: UUID): List<CommentDto> {
        return commentReadRepository.listComments(recipeId)
    }

    private fun validatePage(page: Int, limit: Int) {
        if (page < 1 || limit !in 1..100) {
            badRequest("Invalid pagination values")
        }
    }
}
