package com.recipebook.server.database

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.datetime
import java.time.LocalDateTime
import java.util.UUID

private val dbJson = Json { ignoreUnknownKeys = true }

object UsersTable : UUIDTable("users") {
    val email = varchar("email", 255).uniqueIndex()
    val username = varchar("username", 50).uniqueIndex()
    val passwordHash = text("password_hash")
    val bio = text("bio").nullable()
    val avatarUrl = text("avatar_url").nullable()
    val createdAt = datetime("created_at")
}

object RecipesTable : UUIDTable("recipes") {
    val authorId = reference("author_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val title = varchar("title", 50)
    val description = varchar("description", 1000)
    val category = varchar("category", 50)
    val cookingTimeMinutes = integer("cooking_time_minutes")
    val ingredients = text("ingredients")
    val steps = text("steps")
    val imageUrls = text("image_urls")
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
}

object RatingsTable : UUIDTable("ratings") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val recipeId = reference("recipe_id", RecipesTable, onDelete = ReferenceOption.CASCADE)
    val value = integer("value")

    init {
        uniqueIndex(userId, recipeId)
    }
}

object CommentsTable : UUIDTable("comments") {
    val recipeId = reference("recipe_id", RecipesTable, onDelete = ReferenceOption.CASCADE)
    val authorId = reference("author_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val parentCommentId = uuid("parent_comment_id").nullable()
    val text = varchar("text", 500)
    val createdAt = datetime("created_at")
}

object FavoritesTable : Table("favorites") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val recipeId = reference("recipe_id", RecipesTable, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(userId, recipeId)
}

object FollowsTable : Table("follows") {
    val followerId = reference("follower_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val followingId = reference("following_id", UsersTable, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(followerId, followingId)
}

fun List<String>.toDbJson(): String = dbJson.encodeToString(this)

fun String.fromDbJson(): List<String> = if (isBlank()) emptyList() else dbJson.decodeFromString(this)

fun now(): LocalDateTime = LocalDateTime.now()

val RecipeCategories = setOf(
    "Супы",
    "Салаты",
    "Горячее",
    "Выпечка и десерты",
    "Напитки",
    "Закуски",
    "Завтраки",
)

enum class RecipeSort {
    NEWEST,
    RATING,
}

enum class TimeRange {
    UP_TO_15,
    FROM_15_TO_30,
    FROM_30_TO_60,
    ABOVE_60,
}

fun parseSort(value: String?): RecipeSort = when (value?.lowercase()) {
    "rating" -> RecipeSort.RATING
    else -> RecipeSort.NEWEST
}

fun parseTimeRange(value: String?): TimeRange? = when (value?.lowercase()) {
    "upto15", "up_to_15", "до15", "15" -> TimeRange.UP_TO_15
    "15_30", "15-30", "15to30" -> TimeRange.FROM_15_TO_30
    "30_60", "30-60", "30to60" -> TimeRange.FROM_30_TO_60
    "above60", "over60", "60+" -> TimeRange.ABOVE_60
    else -> null
}

fun inTimeRange(timeRange: TimeRange?, minutes: Int): Boolean = when (timeRange) {
    null -> true
    TimeRange.UP_TO_15 -> minutes <= 15
    TimeRange.FROM_15_TO_30 -> minutes in 15..30
    TimeRange.FROM_30_TO_60 -> minutes in 30..60
    TimeRange.ABOVE_60 -> minutes > 60
}

fun randomId(): UUID = UUID.randomUUID()
