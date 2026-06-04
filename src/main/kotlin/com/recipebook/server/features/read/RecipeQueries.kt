package com.recipebook.server.features.read

import com.recipebook.server.database.RecipeSort
import com.recipebook.server.database.TimeRange
import com.recipebook.server.database.bindNullableUuid
import com.recipebook.server.database.bindParams
import com.recipebook.server.features.recipes.RecipeDetailsDto
import com.recipebook.server.features.recipes.RecipeSummaryDto
import com.recipebook.server.features.users.UserSummaryDto
import kotlinx.serialization.json.Json
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.UUID

private val recipeReadJson = Json { ignoreUnknownKeys = true }

internal fun queryRecipeById(
    connection: java.sql.Connection,
    recipeId: UUID,
    currentUserId: UUID?,
): RecipeSnapshot? {
    val sql = """
        SELECT
          r.id,
          r.title,
          r.description,
          r.category,
          r.cooking_time_minutes,
          r.ingredients,
          r.steps,
          r.image_urls,
          r.created_at,
          r.updated_at,
          u.id AS author_id,
          u.username AS author_username,
          u.avatar_url AS author_avatar,
          COALESCE(stats.likes_count, 0) AS likes_count,
          COALESCE(stats.dislikes_count, 0) AS dislikes_count,
          CASE WHEN fav.recipe_id IS NULL THEN FALSE ELSE TRUE END AS is_favorite,
          my."value" AS my_rating
        FROM recipes r
        JOIN users u ON u.id = r.author_id
        LEFT JOIN (
          SELECT
            recipe_id,
            SUM(CASE WHEN "value" = 1 THEN 1 ELSE 0 END)::int AS likes_count,
            SUM(CASE WHEN "value" = -1 THEN 1 ELSE 0 END)::int AS dislikes_count
          FROM ratings
          GROUP BY recipe_id
        ) stats ON stats.recipe_id = r.id
        LEFT JOIN favorites fav ON fav.recipe_id = r.id AND fav.user_id = ?
        LEFT JOIN ratings my ON my.recipe_id = r.id AND my.user_id = ?
        WHERE r.id = ?
        LIMIT 1
    """.trimIndent()

    return connection.prepareStatement(sql).use { statement ->
        var i = 1
        i = statement.bindNullableUuid(i, currentUserId)
        i = statement.bindNullableUuid(i, currentUserId)
        statement.setObject(i, recipeId)
        statement.executeQuery().use { rs ->
            if (rs.next()) rs.toRecipeSnapshot() else null
        }
    }
}

internal fun countRecipes(
    query: String?,
    category: String?,
    timeRange: TimeRange?,
    exec: (sql: String, binder: (PreparedStatement) -> Unit) -> Int,
): Int {
    val where = mutableListOf<String>()
    val params = mutableListOf<Any>()

    if (!query.isNullOrBlank()) {
        where += "LOWER(r.title) LIKE ?"
        params += "%${query.lowercase()}%"
    }
    if (!category.isNullOrBlank()) {
        where += "LOWER(r.category) = LOWER(?)"
        params += category
    }
    timeRangeSql(timeRange)?.let {
        where += it
    }

    val whereClause = if (where.isEmpty()) "" else "WHERE ${where.joinToString(" AND ")}"
    val sql = "SELECT COUNT(*) FROM recipes r $whereClause"

    return exec(sql) { statement ->
        bindParams(statement, params)
    }
}

internal fun queryRecipes(
    currentUserId: UUID?,
    query: String?,
    category: String?,
    timeRange: TimeRange?,
    sort: RecipeSort,
    limit: Int?,
    offset: Int?,
    scope: RecipeScope,
    scopeUserId: UUID?,
    exec: (sql: String, binder: (PreparedStatement) -> Unit) -> List<RecipeSnapshot>,
): List<RecipeSnapshot> {
    val joins = mutableListOf<String>()
    val where = mutableListOf<String>()
    val whereParams = mutableListOf<Any>()

    when (scope) {
        RecipeScope.ALL -> Unit
        RecipeScope.FEED -> {
            joins += "JOIN follows flw ON flw.following_id = r.author_id AND flw.follower_id = ?"
        }
        RecipeScope.FAVORITES -> {
            joins += "JOIN favorites f0 ON f0.recipe_id = r.id AND f0.user_id = ?"
        }
        RecipeScope.AUTHOR -> {
            where += "r.author_id = ?"
            whereParams += scopeUserId ?: error("scopeUserId is required for AUTHOR scope")
        }
    }

    if (!query.isNullOrBlank()) {
        where += "LOWER(r.title) LIKE ?"
        whereParams += "%${query.lowercase()}%"
    }
    if (!category.isNullOrBlank()) {
        where += "LOWER(r.category) = LOWER(?)"
        whereParams += category
    }
    timeRangeSql(timeRange)?.let { where += it }

    val joinsSql = if (joins.isEmpty()) "" else joins.joinToString(separator = "\n", postfix = "\n")
    val whereSql = if (where.isEmpty()) "" else "WHERE ${where.joinToString(" AND ")}"
    val orderSql = when (sort) {
        RecipeSort.NEWEST -> "ORDER BY r.created_at DESC"
        RecipeSort.RATING -> "ORDER BY (COALESCE(stats.likes_count, 0) - COALESCE(stats.dislikes_count, 0)) DESC, r.created_at DESC"
    }
    val pageSql = if (limit != null && offset != null) "LIMIT ? OFFSET ?" else ""

    val sql = """
        SELECT
          r.id,
          r.title,
          r.description,
          r.category,
          r.cooking_time_minutes,
          r.ingredients,
          r.steps,
          r.image_urls,
          r.created_at,
          r.updated_at,
          u.id AS author_id,
          u.username AS author_username,
          u.avatar_url AS author_avatar,
          COALESCE(stats.likes_count, 0) AS likes_count,
          COALESCE(stats.dislikes_count, 0) AS dislikes_count,
          CASE WHEN fav.recipe_id IS NULL THEN FALSE ELSE TRUE END AS is_favorite,
          my."value" AS my_rating
        FROM recipes r
        JOIN users u ON u.id = r.author_id
        LEFT JOIN (
          SELECT
            recipe_id,
            SUM(CASE WHEN "value" = 1 THEN 1 ELSE 0 END)::int AS likes_count,
            SUM(CASE WHEN "value" = -1 THEN 1 ELSE 0 END)::int AS dislikes_count
          FROM ratings
          GROUP BY recipe_id
        ) stats ON stats.recipe_id = r.id
        LEFT JOIN favorites fav ON fav.recipe_id = r.id AND fav.user_id = ?
        LEFT JOIN ratings my ON my.recipe_id = r.id AND my.user_id = ?
        $joinsSql$whereSql
        $orderSql
        $pageSql
    """.trimIndent()

    return exec(sql) { statement ->
        var i = 1
        i = statement.bindNullableUuid(i, currentUserId)
        i = statement.bindNullableUuid(i, currentUserId)
        if (scope == RecipeScope.FEED || scope == RecipeScope.FAVORITES) {
            statement.setObject(i++, scopeUserId)
        }
        i = bindParams(statement, whereParams, i)
        if (limit != null && offset != null) {
            statement.setInt(i++, limit)
            statement.setInt(i, offset)
        }
    }
}

internal fun java.sql.ResultSet.toRecipeSnapshot(): RecipeSnapshot {
    val likes = getInt("likes_count")
    val dislikes = getInt("dislikes_count")
    val myRatingValue = getInt("my_rating")
    val myRating = if (wasNull()) null else myRatingValue
    return RecipeSnapshot(
        id = getObject("id", UUID::class.java),
        title = getString("title"),
        description = getString("description"),
        category = getString("category"),
        cookingTimeMinutes = getInt("cooking_time_minutes"),
        ingredients = parseJsonList(getString("ingredients")),
        steps = parseJsonList(getString("steps")),
        imageUrls = parseJsonList(getString("image_urls")),
        createdAt = getTimestamp("created_at").toLocalDateTime(),
        updatedAt = getTimestamp("updated_at").toLocalDateTime(),
        author = UserSummaryDto(
            id = getObject("author_id", UUID::class.java).toString(),
            username = getString("author_username"),
            avatarUrl = getString("author_avatar"),
        ),
        likesCount = likes,
        dislikesCount = dislikes,
        rating = likes - dislikes,
        isFavorite = getBoolean("is_favorite"),
        myRating = myRating,
    )
}

private fun timeRangeSql(timeRange: TimeRange?): String? = when (timeRange) {
    null -> null
    TimeRange.UP_TO_15 -> "r.cooking_time_minutes <= 15"
    TimeRange.FROM_15_TO_30 -> "r.cooking_time_minutes BETWEEN 15 AND 30"
    TimeRange.FROM_30_TO_60 -> "r.cooking_time_minutes BETWEEN 30 AND 60"
    TimeRange.ABOVE_60 -> "r.cooking_time_minutes > 60"
}

private fun parseJsonList(value: String?): List<String> {
    if (value.isNullOrBlank()) return emptyList()
    return runCatching { recipeReadJson.decodeFromString<List<String>>(value) }.getOrDefault(emptyList())
}

internal enum class RecipeScope {
    ALL,
    FEED,
    FAVORITES,
    AUTHOR,
}

internal data class RecipeSnapshot(
    val id: UUID,
    val title: String,
    val description: String,
    val category: String,
    val cookingTimeMinutes: Int,
    val ingredients: List<String>,
    val steps: List<String>,
    val imageUrls: List<String>,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val author: UserSummaryDto,
    val likesCount: Int,
    val dislikesCount: Int,
    val rating: Int,
    val isFavorite: Boolean,
    val myRating: Int?,
) {
    fun toSummaryDto(): RecipeSummaryDto = RecipeSummaryDto(
        id = id.toString(),
        title = title,
        description = description,
        category = category,
        cookingTimeMinutes = cookingTimeMinutes,
        imageUrl = imageUrls.firstOrNull(),
        author = author,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        likesCount = likesCount,
        dislikesCount = dislikesCount,
        rating = rating,
        isFavorite = isFavorite,
        myRating = myRating,
    )

    fun toDetailsDto(): RecipeDetailsDto = RecipeDetailsDto(
        id = id.toString(),
        title = title,
        description = description,
        category = category,
        cookingTimeMinutes = cookingTimeMinutes,
        ingredients = ingredients,
        steps = steps,
        imageUrls = imageUrls,
        author = author,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        likesCount = likesCount,
        dislikesCount = dislikesCount,
        rating = rating,
        isFavorite = isFavorite,
        myRating = myRating,
    )
}
