package com.recipebook.server.features.read

import com.recipebook.server.database.DatabaseFactory
import com.recipebook.server.database.RecipeSort
import com.recipebook.server.database.TimeRange
import com.recipebook.server.database.parseSort
import com.recipebook.server.database.parseTimeRange
import com.recipebook.server.features.auth.PagedRecipesResponse
import com.recipebook.server.features.comments.CommentDto
import com.recipebook.server.features.common.badRequest
import com.recipebook.server.features.common.notFound
import com.recipebook.server.features.common.unauthorized
import com.recipebook.server.features.recipes.RecipeDetailsDto
import com.recipebook.server.features.recipes.RecipeSummaryDto
import com.recipebook.server.features.users.UserProfileDto
import com.recipebook.server.features.users.UserSummaryDto
import kotlinx.serialization.json.Json
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.sql.Types
import java.time.LocalDateTime
import java.util.UUID

class ReadService {
    private val json = Json { ignoreUnknownKeys = true }

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

        return DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                val total = countRecipes(
                    query = normalizedQuery,
                    category = normalizedCategory,
                    timeRange = parsedTimeRange,
                ) { sql, binder ->
                    connection.prepareStatement(sql).use { statement ->
                        binder(statement)
                        statement.executeQuery().use { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                    }
                }

                val rows = queryRecipes(
                    currentUserId = currentUserId,
                    query = normalizedQuery,
                    category = normalizedCategory,
                    timeRange = parsedTimeRange,
                    sort = parsedSort,
                    limit = limit,
                    offset = (page - 1) * limit,
                    scope = RecipeScope.ALL,
                    scopeUserId = null,
                ) { sql, binder ->
                    connection.prepareStatement(sql).use { statement ->
                        binder(statement)
                        statement.executeQuery().use { rs ->
                            mutableListOf<RecipeSnapshot>().apply {
                                while (rs.next()) {
                                    add(rs.toRecipeSnapshot())
                                }
                            }
                        }
                    }
                }

                PagedRecipesResponse(
                    items = rows.map { it.toSummaryDto() },
                    page = page,
                    limit = limit,
                    total = total,
                )
            }
        }
    }

    fun getRecipe(recipeId: UUID, currentUserId: UUID?): RecipeDetailsDto {
        return DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                val recipe = queryRecipeById(connection, recipeId, currentUserId) ?: notFound("Recipe not found")
                recipe.toDetailsDto()
            }
        }
    }

    fun listFeed(userId: UUID): List<RecipeSummaryDto> {
        return DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                ensureUserExists(connection, userId, asUnauthorized = true)
                queryRecipes(
                    currentUserId = userId,
                    query = null,
                    category = null,
                    timeRange = null,
                    sort = RecipeSort.NEWEST,
                    limit = null,
                    offset = null,
                    scope = RecipeScope.FEED,
                    scopeUserId = userId,
                ) { sql, binder ->
                    connection.prepareStatement(sql).use { statement ->
                        binder(statement)
                        statement.executeQuery().use { rs ->
                            mutableListOf<RecipeSnapshot>().apply {
                                while (rs.next()) {
                                    add(rs.toRecipeSnapshot())
                                }
                            }
                        }
                    }
                }.map { it.toSummaryDto() }
            }
        }
    }

    fun listFavorites(userId: UUID): List<RecipeSummaryDto> {
        return DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                ensureUserExists(connection, userId, asUnauthorized = true)
                queryRecipes(
                    currentUserId = userId,
                    query = null,
                    category = null,
                    timeRange = null,
                    sort = RecipeSort.NEWEST,
                    limit = null,
                    offset = null,
                    scope = RecipeScope.FAVORITES,
                    scopeUserId = userId,
                ) { sql, binder ->
                    connection.prepareStatement(sql).use { statement ->
                        binder(statement)
                        statement.executeQuery().use { rs ->
                            mutableListOf<RecipeSnapshot>().apply {
                                while (rs.next()) {
                                    add(rs.toRecipeSnapshot())
                                }
                            }
                        }
                    }
                }.map { it.toSummaryDto() }
            }
        }
    }

    fun listRecipesByAuthor(authorId: UUID, currentUserId: UUID?): List<RecipeSummaryDto> {
        return DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                ensureUserExists(connection, authorId, asUnauthorized = false)
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
                        rt.recipe_id,
                        SUM(CASE WHEN rt."value" = 1 THEN 1 ELSE 0 END)::int AS likes_count,
                        SUM(CASE WHEN rt."value" = -1 THEN 1 ELSE 0 END)::int AS dislikes_count
                      FROM ratings rt
                      GROUP BY rt.recipe_id
                    ) stats ON stats.recipe_id = r.id
                    LEFT JOIN favorites fav ON fav.recipe_id = r.id AND fav.user_id = ?
                    LEFT JOIN ratings my ON my.recipe_id = r.id AND my.user_id = ?
                    WHERE r.author_id = ?
                    ORDER BY r.created_at DESC
                """.trimIndent()

                connection.prepareStatement(sql).use { statement ->
                    var i = 1
                    i = statement.bindNullableUuid(i, currentUserId)
                    i = statement.bindNullableUuid(i, currentUserId)
                    statement.setObject(i, authorId)
                    statement.executeQuery().use { rs ->
                        mutableListOf<RecipeSummaryDto>().apply {
                            while (rs.next()) {
                                add(rs.toRecipeSnapshot().toSummaryDto())
                            }
                        }
                    }
                }
            }
        }
    }

    fun getProfile(targetUserId: UUID, viewerId: UUID?): UserProfileDto {
        return DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT
                      u.id,
                      u.email,
                      u.username,
                      u.bio,
                      u.avatar_url,
                      u.created_at,
                      (SELECT COUNT(*) FROM recipes r WHERE r.author_id = u.id) AS recipes_count,
                      (SELECT COUNT(*) FROM follows f WHERE f.following_id = u.id) AS followers_count,
                      (SELECT COUNT(*) FROM follows f WHERE f.follower_id = u.id) AS following_count,
                      CASE
                        WHEN ? IS NULL OR ? = u.id THEN FALSE
                        ELSE EXISTS(
                          SELECT 1
                          FROM follows f2
                          WHERE f2.follower_id = ? AND f2.following_id = u.id
                        )
                      END AS is_following
                    FROM users u
                    WHERE u.id = ?
                    """.trimIndent(),
                ).use { statement ->
                    var i = 1
                    i = statement.bindNullableUuid(i, viewerId)
                    i = statement.bindNullableUuid(i, viewerId)
                    i = statement.bindNullableUuid(i, viewerId)
                    statement.setObject(i, targetUserId)

                    statement.executeQuery().use { rs ->
                        if (!rs.next()) {
                            notFound("User not found")
                        }
                        UserProfileDto(
                            id = rs.getObject("id", UUID::class.java).toString(),
                            email = rs.getString("email"),
                            username = rs.getString("username"),
                            bio = rs.getString("bio"),
                            avatarUrl = rs.getString("avatar_url"),
                            createdAt = rs.getTimestamp("created_at").toLocalDateTime().toString(),
                            recipesCount = rs.getInt("recipes_count"),
                            followersCount = rs.getInt("followers_count"),
                            followingCount = rs.getInt("following_count"),
                            isFollowing = rs.getBoolean("is_following"),
                        )
                    }
                }
            }
        }
    }

    fun getCurrentProfile(userId: UUID): UserProfileDto {
        return DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT
                      u.id,
                      u.email,
                      u.username,
                      u.bio,
                      u.avatar_url,
                      u.created_at,
                      (SELECT COUNT(*) FROM recipes r WHERE r.author_id = u.id) AS recipes_count,
                      (SELECT COUNT(*) FROM follows f WHERE f.following_id = u.id) AS followers_count,
                      (SELECT COUNT(*) FROM follows f WHERE f.follower_id = u.id) AS following_count
                    FROM users u
                    WHERE u.id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) {
                            unauthorized("Session is no longer valid. Please sign in again")
                        }
                        UserProfileDto(
                            id = rs.getObject("id", UUID::class.java).toString(),
                            email = rs.getString("email"),
                            username = rs.getString("username"),
                            bio = rs.getString("bio"),
                            avatarUrl = rs.getString("avatar_url"),
                            createdAt = rs.getTimestamp("created_at").toLocalDateTime().toString(),
                            recipesCount = rs.getInt("recipes_count"),
                            followersCount = rs.getInt("followers_count"),
                            followingCount = rs.getInt("following_count"),
                            isFollowing = false,
                        )
                    }
                }
            }
        }
    }

    fun listComments(recipeId: UUID): List<CommentDto> {
        return DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                ensureRecipeExists(connection, recipeId)
                val flat = connection.prepareStatement(
                    """
                    SELECT
                      c.id,
                      c.recipe_id,
                      c.parent_comment_id,
                      c.text,
                      c.created_at,
                      u.id AS author_id,
                      u.username AS author_username,
                      u.avatar_url AS author_avatar
                    FROM comments c
                    JOIN users u ON u.id = c.author_id
                    WHERE c.recipe_id = ?
                    ORDER BY c.created_at DESC
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, recipeId)
                    statement.executeQuery().use { rs ->
                        mutableListOf<CommentDto>().apply {
                            while (rs.next()) {
                                add(
                                    CommentDto(
                                        id = rs.getObject("id", UUID::class.java).toString(),
                                        recipeId = rs.getObject("recipe_id", UUID::class.java).toString(),
                                        parentCommentId = rs.getObject("parent_comment_id", UUID::class.java)?.toString(),
                                        text = rs.getString("text"),
                                        createdAt = rs.getTimestamp("created_at").toLocalDateTime().toString(),
                                        author = UserSummaryDto(
                                            id = rs.getObject("author_id", UUID::class.java).toString(),
                                            username = rs.getString("author_username"),
                                            avatarUrl = rs.getString("author_avatar"),
                                        ),
                                    ),
                                )
                            }
                        }
                    }
                }

                val repliesByParent = flat.filter { it.parentCommentId != null }.groupBy { it.parentCommentId }
                flat.filter { it.parentCommentId == null }.map { comment ->
                    comment.copy(replies = repliesByParent[comment.id].orEmpty())
                }
            }
        }
    }

    private fun queryRecipeById(
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

    private fun ensureUserExists(connection: java.sql.Connection, userId: UUID, asUnauthorized: Boolean) {
        connection.prepareStatement(
            "SELECT 1 FROM users WHERE id = ? LIMIT 1",
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    if (asUnauthorized) unauthorized("Session is no longer valid. Please sign in again")
                    notFound("User not found")
                }
            }
        }
    }

    private fun ensureRecipeExists(connection: java.sql.Connection, recipeId: UUID) {
        connection.prepareStatement(
            "SELECT 1 FROM recipes WHERE id = ? LIMIT 1",
        ).use { statement ->
            statement.setObject(1, recipeId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    notFound("Recipe not found")
                }
            }
        }
    }

    private fun countRecipes(
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

    private fun queryRecipes(
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

    private fun java.sql.ResultSet.toRecipeSnapshot(): RecipeSnapshot {
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

    private fun bindParams(statement: PreparedStatement, params: List<Any>, startIndex: Int = 1): Int {
        var index = startIndex
        params.forEach { param ->
            when (param) {
                is String -> statement.setString(index, param)
                is Int -> statement.setInt(index, param)
                is UUID -> statement.setObject(index, param)
                is Timestamp -> statement.setTimestamp(index, param)
                else -> statement.setObject(index, param)
            }
            index++
        }
        return index
    }

    private fun PreparedStatement.bindNullableUuid(index: Int, value: UUID?): Int {
        if (value == null) {
            setNull(index, Types.OTHER)
        } else {
            setObject(index, value)
        }
        return index + 1
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
        return runCatching { json.decodeFromString<List<String>>(value) }.getOrDefault(emptyList())
    }

    private fun validatePage(page: Int, limit: Int) {
        if (page < 1 || limit !in 1..100) {
            badRequest("Invalid pagination values")
        }
    }
}

private enum class RecipeScope {
    ALL,
    FEED,
    FAVORITES,
    AUTHOR,
}

private data class RecipeSnapshot(
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
