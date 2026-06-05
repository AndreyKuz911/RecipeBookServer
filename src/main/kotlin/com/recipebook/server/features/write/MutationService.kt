package com.recipebook.server.features.write

import com.recipebook.server.features.comments.CommentDto
import com.recipebook.server.features.comments.CreateCommentRequest
import com.recipebook.server.features.common.badRequest
import com.recipebook.server.features.read.ReadService
import com.recipebook.server.features.recipes.RecipeDetailsDto
import com.recipebook.server.features.recipes.RatingRequest
import com.recipebook.server.features.recipes.RecipeUpsertRequest
import com.recipebook.server.features.users.UpdateProfileRequest
import com.recipebook.server.features.users.UserProfileDto
import com.recipebook.server.features.write.repository.CommentWriteRepository
import com.recipebook.server.features.write.repository.ProfileWriteRepository
import com.recipebook.server.features.write.repository.RecipeWriteRepository
import com.recipebook.server.features.write.repository.SocialWriteRepository
import java.util.UUID

class MutationService internal constructor(
    private val readService: ReadService,
    private val profileWriteRepository: ProfileWriteRepository = ProfileWriteRepository(),
    private val recipeWriteRepository: RecipeWriteRepository = RecipeWriteRepository(),
    private val commentWriteRepository: CommentWriteRepository = CommentWriteRepository(),
    private val socialWriteRepository: SocialWriteRepository = SocialWriteRepository(),
) {
    fun updateProfile(userId: UUID, request: UpdateProfileRequest): UserProfileDto {
        profileWriteRepository.updateProfile(userId, validateProfileUpdate(request))
        return readService.getCurrentProfile(userId)
    }

    fun createRecipe(authorId: UUID, request: RecipeUpsertRequest): RecipeDetailsDto {
        val recipeId = recipeWriteRepository.createRecipe(authorId, validateRecipeUpsert(request))
        return readService.getRecipe(recipeId, authorId)
    }

    fun updateRecipe(recipeId: UUID, authorId: UUID, request: RecipeUpsertRequest): RecipeDetailsDto {
        recipeWriteRepository.updateRecipe(recipeId, authorId, validateRecipeUpsert(request))
        return readService.getRecipe(recipeId, authorId)
    }

    fun deleteRecipe(recipeId: UUID, actorId: UUID) {
        recipeWriteRepository.deleteRecipe(recipeId, actorId)
    }

    fun setRating(recipeId: UUID, userId: UUID, request: RatingRequest): RecipeDetailsDto {
        if (request.value != 1 && request.value != -1) {
            badRequest("Rating value must be 1 or -1")
        }
        recipeWriteRepository.setRating(recipeId, userId, request)
        return readService.getRecipe(recipeId, userId)
    }

    fun removeRating(recipeId: UUID, userId: UUID) {
        recipeWriteRepository.removeRating(recipeId, userId)
    }

    fun addFavorite(recipeId: UUID, userId: UUID): RecipeDetailsDto {
        recipeWriteRepository.addFavorite(recipeId, userId)
        return readService.getRecipe(recipeId, userId)
    }

    fun removeFavorite(recipeId: UUID, userId: UUID) {
        recipeWriteRepository.removeFavorite(recipeId, userId)
    }

    fun createComment(recipeId: UUID, userId: UUID, request: CreateCommentRequest): CommentDto {
        val text = validateCommentText(request.text)
        return commentWriteRepository.createComment(recipeId, userId, text, request.parentCommentId)
    }

    fun deleteComment(commentId: UUID, actorId: UUID) {
        commentWriteRepository.deleteComment(commentId, actorId)
    }

    fun follow(followerId: UUID, followingId: UUID) {
        if (followerId == followingId) {
            badRequest("You cannot follow yourself")
        }
        socialWriteRepository.follow(followerId, followingId)
    }

    fun unfollow(followerId: UUID, followingId: UUID) {
        socialWriteRepository.unfollow(followerId, followingId)
    }
}
