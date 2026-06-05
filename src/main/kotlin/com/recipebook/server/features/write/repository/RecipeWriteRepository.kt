package com.recipebook.server.features.write.repository

import com.recipebook.server.database.DatabaseFactory
import com.recipebook.server.database.requireAuthenticatedUserExists
import com.recipebook.server.database.requireRecipeExists
import com.recipebook.server.features.recipes.RatingRequest
import com.recipebook.server.features.write.ValidatedRecipeUpsert
import com.recipebook.server.features.write.requireRecipeAuthor
import kotlinx.serialization.json.Json
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.UUID

class RecipeWriteRepository {
    private val json = Json { ignoreUnknownKeys = true }

    fun createRecipe(authorId: UUID, request: ValidatedRecipeUpsert): UUID {
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
                    statement.setString(3, request.title)
                    statement.setString(4, request.description)
                    statement.setString(5, request.category)
                    statement.setInt(6, request.cookingTimeMinutes)
                    statement.setString(7, json.encodeToString(request.ingredients))
                    statement.setString(8, json.encodeToString(request.steps))
                    statement.setString(9, json.encodeToString(request.imageUrls))
                    statement.setTimestamp(10, Timestamp.valueOf(now))
                    statement.setTimestamp(11, Timestamp.valueOf(now))
                    statement.executeUpdate()
                }
            }
        }
        return recipeId
    }

    fun updateRecipe(recipeId: UUID, authorId: UUID, request: ValidatedRecipeUpsert) {
        DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                connection.requireAuthenticatedUserExists(authorId)
                connection.requireRecipeAuthor(recipeId, authorId, "edit")
                connection.prepareStatement(
                    """
                    UPDATE recipes
                    SET title = ?, description = ?, category = ?, cooking_time_minutes = ?, ingredients = ?, steps = ?, image_urls = ?, updated_at = ?
                    WHERE id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, request.title)
                    statement.setString(2, request.description)
                    statement.setString(3, request.category)
                    statement.setInt(4, request.cookingTimeMinutes)
                    statement.setString(5, json.encodeToString(request.ingredients))
                    statement.setString(6, json.encodeToString(request.steps))
                    statement.setString(7, json.encodeToString(request.imageUrls))
                    statement.setTimestamp(8, Timestamp.valueOf(LocalDateTime.now()))
                    statement.setObject(9, recipeId)
                    statement.executeUpdate()
                }
            }
        }
    }

    fun deleteRecipe(recipeId: UUID, actorId: UUID) {
        DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                connection.requireAuthenticatedUserExists(actorId)
                connection.requireRecipeAuthor(recipeId, actorId, "delete")
                connection.prepareStatement("DELETE FROM recipes WHERE id = ?").use { statement ->
                    statement.setObject(1, recipeId)
                    statement.executeUpdate()
                }
            }
        }
    }

    fun setRating(recipeId: UUID, userId: UUID, request: RatingRequest) {
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

    fun addFavorite(recipeId: UUID, userId: UUID) {
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
}
