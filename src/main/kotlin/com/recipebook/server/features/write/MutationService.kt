package com.recipebook.server.features.write

import com.recipebook.server.database.DatabaseFactory
import com.recipebook.server.database.findRecipeAuthorId
import com.recipebook.server.database.requireAuthenticatedUserExists
import com.recipebook.server.database.requireRecipeExists
import com.recipebook.server.database.requireUserExists
import com.recipebook.server.features.comments.CommentDto
import com.recipebook.server.features.comments.CreateCommentRequest
import com.recipebook.server.features.common.badRequest
import com.recipebook.server.features.common.conflict
import com.recipebook.server.features.common.forbidden
import com.recipebook.server.features.common.notFound
import com.recipebook.server.features.common.unauthorized
import com.recipebook.server.features.read.ReadService
import com.recipebook.server.features.recipes.RecipeDetailsDto
import com.recipebook.server.features.recipes.RatingRequest
import com.recipebook.server.features.recipes.RecipeUpsertRequest
import com.recipebook.server.features.users.UpdateProfileRequest
import com.recipebook.server.features.users.UserProfileDto
import com.recipebook.server.features.users.UserSummaryDto
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.UUID

class MutationService(
    private val readService: ReadService,
) {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun updateProfile(userId: UUID, request: UpdateProfileRequest): UserProfileDto {
        validateProfileUpdate(request)
        return DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                connection.requireAuthenticatedUserExists(userId)

                val duplicate = connection.prepareStatement(
                    "SELECT 1 FROM users WHERE username = ? AND id <> ? LIMIT 1",
                ).use { statement ->
                    statement.setString(1, request.username.trim())
                    statement.setObject(2, userId)
                    statement.executeQuery().use { rs -> rs.next() }
                }
                if (duplicate) {
                    conflict("Username is already taken")
                }

                connection.prepareStatement(
                    """
                    UPDATE users
                    SET username = ?, bio = ?, avatar_url = ?
                    WHERE id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, request.username.trim())
                    statement.setString(2, request.bio?.trim()?.ifBlank { null })
                    statement.setString(3, request.avatarUrl?.trim()?.ifBlank { null })
                    statement.setObject(4, userId)
                    statement.executeUpdate()
                }
            }

            readService.getCurrentProfile(userId)
        }
    }

    fun createRecipe(authorId: UUID, request: RecipeUpsertRequest): RecipeDetailsDto {
        validateRecipeUpsert(request)
        val recipeId = UUID.randomUUID()
        val now = LocalDateTime.now()

        DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                connection.requireAuthenticatedUserExists(authorId)

                connection.prepareStatement(
                    """
                    INSERT INTO recipes (
                      id,
                      author_id,
                      title,
                      description,
                      category,
                      cooking_time_minutes,
                      ingredients,
                      steps,
                      image_urls,
                      created_at,
                      updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, recipeId)
                    statement.setObject(2, authorId)
                    statement.setString(3, request.title.trim())
                    statement.setString(4, request.description.trim())
                    statement.setString(5, request.category.trim())
                    statement.setInt(6, request.cookingTimeMinutes)
                    statement.setString(7, json.encodeToString(request.ingredients.map(String::trim)))
                    statement.setString(8, json.encodeToString(request.steps.map(String::trim)))
                    statement.setString(9, json.encodeToString(request.imageUrls.map(String::trim)))
                    statement.setTimestamp(10, Timestamp.valueOf(now))
                    statement.setTimestamp(11, Timestamp.valueOf(now))
                    statement.executeUpdate()
                }
            }
        }

        return readService.getRecipe(recipeId, authorId)
    }

    fun updateRecipe(recipeId: UUID, authorId: UUID, request: RecipeUpsertRequest): RecipeDetailsDto {
        validateRecipeUpsert(request)
        DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                connection.requireAuthenticatedUserExists(authorId)
                val recipeAuthorId = connection.findRecipeAuthorId(recipeId)
                if (recipeAuthorId != authorId) {
                    forbidden("Only the author can edit this recipe")
                }

                connection.prepareStatement(
                    """
                    UPDATE recipes
                    SET title = ?, description = ?, category = ?, cooking_time_minutes = ?, ingredients = ?, steps = ?, image_urls = ?, updated_at = ?
                    WHERE id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, request.title.trim())
                    statement.setString(2, request.description.trim())
                    statement.setString(3, request.category.trim())
                    statement.setInt(4, request.cookingTimeMinutes)
                    statement.setString(5, json.encodeToString(request.ingredients.map(String::trim)))
                    statement.setString(6, json.encodeToString(request.steps.map(String::trim)))
                    statement.setString(7, json.encodeToString(request.imageUrls.map(String::trim)))
                    statement.setTimestamp(8, Timestamp.valueOf(LocalDateTime.now()))
                    statement.setObject(9, recipeId)
                    statement.executeUpdate()
                }
            }
        }
        return readService.getRecipe(recipeId, authorId)
    }

    fun deleteRecipe(recipeId: UUID, actorId: UUID) {
        DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                connection.requireAuthenticatedUserExists(actorId)
                val recipeAuthorId = connection.findRecipeAuthorId(recipeId)
                if (recipeAuthorId != actorId) {
                    forbidden("Only the author can delete this recipe")
                }
                connection.prepareStatement("DELETE FROM recipes WHERE id = ?").use { statement ->
                    statement.setObject(1, recipeId)
                    statement.executeUpdate()
                }
            }
        }
    }

    fun setRating(recipeId: UUID, userId: UUID, request: RatingRequest): RecipeDetailsDto {
        if (request.value != 1 && request.value != -1) {
            badRequest("Rating value must be 1 or -1")
        }
        DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                connection.requireAuthenticatedUserExists(userId)
                connection.requireRecipeExists(recipeId)
                val existing = connection.prepareStatement(
                    """SELECT "value" FROM ratings WHERE recipe_id = ? AND user_id = ? LIMIT 1""",
                ).use { statement ->
                    statement.setObject(1, recipeId)
                    statement.setObject(2, userId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) rs.getInt("value") else null
                    }
                }

                when {
                    existing == null -> {
                        connection.prepareStatement(
                            """INSERT INTO ratings (id, user_id, recipe_id, "value") VALUES (?, ?, ?, ?)""",
                        ).use { statement ->
                            statement.setObject(1, UUID.randomUUID())
                            statement.setObject(2, userId)
                            statement.setObject(3, recipeId)
                            statement.setInt(4, request.value)
                            statement.executeUpdate()
                        }
                    }
                    existing == request.value -> {
                        connection.prepareStatement(
                            "DELETE FROM ratings WHERE recipe_id = ? AND user_id = ?",
                        ).use { statement ->
                            statement.setObject(1, recipeId)
                            statement.setObject(2, userId)
                            statement.executeUpdate()
                        }
                    }
                    else -> {
                        connection.prepareStatement(
                            """UPDATE ratings SET "value" = ? WHERE recipe_id = ? AND user_id = ?""",
                        ).use { statement ->
                            statement.setInt(1, request.value)
                            statement.setObject(2, recipeId)
                            statement.setObject(3, userId)
                            statement.executeUpdate()
                        }
                    }
                }
            }
        }
        return readService.getRecipe(recipeId, userId)
    }

    fun removeRating(recipeId: UUID, userId: UUID) {
        DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                connection.requireAuthenticatedUserExists(userId)
                connection.prepareStatement("DELETE FROM ratings WHERE recipe_id = ? AND user_id = ?").use { statement ->
                    statement.setObject(1, recipeId)
                    statement.setObject(2, userId)
                    statement.executeUpdate()
                }
            }
        }
    }

    fun addFavorite(recipeId: UUID, userId: UUID): RecipeDetailsDto {
        DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                connection.requireAuthenticatedUserExists(userId)
                connection.requireRecipeExists(recipeId)
                connection.prepareStatement(
                    """
                    INSERT INTO favorites (user_id, recipe_id)
                    VALUES (?, ?)
                    ON CONFLICT DO NOTHING
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setObject(2, recipeId)
                    statement.executeUpdate()
                }
            }
        }
        return readService.getRecipe(recipeId, userId)
    }

    fun removeFavorite(recipeId: UUID, userId: UUID) {
        DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                connection.requireAuthenticatedUserExists(userId)
                connection.prepareStatement("DELETE FROM favorites WHERE user_id = ? AND recipe_id = ?").use { statement ->
                    statement.setObject(1, userId)
                    statement.setObject(2, recipeId)
                    statement.executeUpdate()
                }
            }
        }
    }

    fun createComment(recipeId: UUID, userId: UUID, request: CreateCommentRequest): CommentDto {
        val text = request.text.trim()
        if (text.isBlank() || text.length > 500) {
            badRequest("Comment must contain from 1 to 500 characters")
        }
        return DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                connection.requireAuthenticatedUserExists(userId)
                connection.requireRecipeExists(recipeId)
                val parentId = request.parentCommentId?.let { raw ->
                    runCatching { UUID.fromString(raw) }.getOrElse { badRequest("Invalid parentCommentId") }
                }

                if (parentId != null) {
                    val parentInfo = connection.prepareStatement(
                        """
                        SELECT parent_comment_id
                        FROM comments
                        WHERE id = ? AND recipe_id = ?
                        LIMIT 1
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, parentId)
                        statement.setObject(2, recipeId)
                        statement.executeQuery().use { rs ->
                            if (!rs.next()) {
                                badRequest("Parent comment not found")
                            }
                            rs.getObject("parent_comment_id", UUID::class.java)
                        }
                    }
                    if (parentInfo != null) {
                        badRequest("Only one nesting level is supported")
                    }
                }

                val commentId = UUID.randomUUID()
                val createdAt = LocalDateTime.now()
                connection.prepareStatement(
                    """
                    INSERT INTO comments (id, recipe_id, author_id, parent_comment_id, text, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, commentId)
                    statement.setObject(2, recipeId)
                    statement.setObject(3, userId)
                    if (parentId == null) {
                        statement.setNull(4, java.sql.Types.OTHER)
                    } else {
                        statement.setObject(4, parentId)
                    }
                    statement.setString(5, text)
                    statement.setTimestamp(6, Timestamp.valueOf(createdAt))
                    statement.executeUpdate()
                }

                val author = connection.prepareStatement(
                    "SELECT id, username, avatar_url FROM users WHERE id = ? LIMIT 1",
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        UserSummaryDto(
                            id = rs.getObject("id", UUID::class.java).toString(),
                            username = rs.getString("username"),
                            avatarUrl = rs.getString("avatar_url"),
                        )
                    }
                }

                CommentDto(
                    id = commentId.toString(),
                    recipeId = recipeId.toString(),
                    parentCommentId = parentId?.toString(),
                    text = text,
                    createdAt = createdAt.toString(),
                    author = author,
                    replies = emptyList(),
                )
            }
        }
    }

    fun deleteComment(commentId: UUID, actorId: UUID) {
        DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                connection.requireAuthenticatedUserExists(actorId)
                val comment = connection.prepareStatement(
                    "SELECT recipe_id, author_id FROM comments WHERE id = ? LIMIT 1",
                ).use { statement ->
                    statement.setObject(1, commentId)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) {
                            notFound("Comment not found")
                        }
                        rs.getObject("recipe_id", UUID::class.java) to rs.getObject("author_id", UUID::class.java)
                    }
                }
                val recipeAuthor = connection.prepareStatement(
                    "SELECT author_id FROM recipes WHERE id = ? LIMIT 1",
                ).use { statement ->
                    statement.setObject(1, comment.first)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) {
                            notFound("Recipe not found")
                        }
                        rs.getObject("author_id", UUID::class.java)
                    }
                }
                val canDelete = comment.second == actorId || recipeAuthor == actorId
                if (!canDelete) {
                    forbidden("You cannot delete this comment")
                }
                connection.prepareStatement(
                    "DELETE FROM comments WHERE id = ? OR parent_comment_id = ?",
                ).use { statement ->
                    statement.setObject(1, commentId)
                    statement.setObject(2, commentId)
                    statement.executeUpdate()
                }
            }
        }
    }

    fun follow(followerId: UUID, followingId: UUID) {
        if (followerId == followingId) {
            badRequest("You cannot follow yourself")
        }
        DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                connection.requireAuthenticatedUserExists(followerId)
                connection.requireUserExists(userId = followingId, asUnauthorized = false)
                connection.prepareStatement(
                    """
                    INSERT INTO follows (follower_id, following_id)
                    VALUES (?, ?)
                    ON CONFLICT DO NOTHING
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, followerId)
                    statement.setObject(2, followingId)
                    statement.executeUpdate()
                }
            }
        }
    }

    fun unfollow(followerId: UUID, followingId: UUID) {
        DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                connection.requireAuthenticatedUserExists(followerId)
                connection.prepareStatement(
                    "DELETE FROM follows WHERE follower_id = ? AND following_id = ?",
                ).use { statement ->
                    statement.setObject(1, followerId)
                    statement.setObject(2, followingId)
                    statement.executeUpdate()
                }
            }
        }
    }
}
