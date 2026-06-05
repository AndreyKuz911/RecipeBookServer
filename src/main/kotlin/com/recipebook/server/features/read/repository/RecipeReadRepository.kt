package com.recipebook.server.features.read.repository

import com.recipebook.server.database.DatabaseFactory
import com.recipebook.server.database.RecipeSort
import com.recipebook.server.database.TimeRange
import com.recipebook.server.database.requireUserExists
import com.recipebook.server.features.read.RecipeScope
import com.recipebook.server.features.read.RecipeSnapshot
import com.recipebook.server.features.read.countRecipes
import com.recipebook.server.features.read.queryRecipeById
import com.recipebook.server.features.read.queryRecipes
import com.recipebook.server.features.read.toRecipeSnapshot
import java.util.UUID

internal data class PagedRecipeSnapshots(
    val total: Int,
    val items: List<RecipeSnapshot>,
)

internal class RecipeReadRepository {
    fun listRecipes(
        currentUserId: UUID?,
        query: String?,
        category: String?,
        timeRange: TimeRange?,
        sort: RecipeSort,
        page: Int,
        limit: Int,
    ): PagedRecipeSnapshots {
        return DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                val total = countRecipes(
                    query = query,
                    category = category,
                    timeRange = timeRange,
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
                    query = query,
                    category = category,
                    timeRange = timeRange,
                    sort = sort,
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

                PagedRecipeSnapshots(total = total, items = rows)
            }
        }
    }

    fun getRecipe(recipeId: UUID, currentUserId: UUID?): RecipeSnapshot? {
        return DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                queryRecipeById(connection, recipeId, currentUserId)
            }
        }
    }

    fun listFeed(userId: UUID): List<RecipeSnapshot> {
        return DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                connection.requireUserExists(userId = userId, asUnauthorized = true)
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
                }
            }
        }
    }

    fun listFavorites(userId: UUID): List<RecipeSnapshot> {
        return DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                connection.requireUserExists(userId = userId, asUnauthorized = true)
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
                }
            }
        }
    }

    fun listByAuthor(authorId: UUID, currentUserId: UUID?): List<RecipeSnapshot> {
        return DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                connection.requireUserExists(userId = authorId, asUnauthorized = false)
                queryRecipes(
                    currentUserId = currentUserId,
                    query = null,
                    category = null,
                    timeRange = null,
                    sort = RecipeSort.NEWEST,
                    limit = null,
                    offset = null,
                    scope = RecipeScope.AUTHOR,
                    scopeUserId = authorId,
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
            }
        }
    }
}
