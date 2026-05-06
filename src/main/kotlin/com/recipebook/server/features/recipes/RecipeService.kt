package com.recipebook.server.features.recipes

import com.recipebook.server.database.CommentsTable
import com.recipebook.server.database.FavoritesTable
import com.recipebook.server.database.FollowsTable
import com.recipebook.server.database.RatingsTable
import com.recipebook.server.database.RecipeCategories
import com.recipebook.server.database.RecipeSort
import com.recipebook.server.database.RecipesTable
import com.recipebook.server.database.TimeRange
import com.recipebook.server.database.UsersTable
import com.recipebook.server.database.fromDbJson
import com.recipebook.server.database.inTimeRange
import com.recipebook.server.database.now
import com.recipebook.server.database.parseSort
import com.recipebook.server.database.parseTimeRange
import com.recipebook.server.database.randomId
import com.recipebook.server.database.toDbJson
import com.recipebook.server.features.auth.PagedRecipesResponse
import com.recipebook.server.features.common.badRequest
import com.recipebook.server.features.common.forbidden
import com.recipebook.server.features.common.notFound
import com.recipebook.server.features.common.unauthorized
import com.recipebook.server.features.users.UserSummaryDto
import com.recipebook.server.features.users.toSummary
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.UUID

class RecipeService {
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
        val normalizedQuery = query?.trim().orEmpty()
        val normalizedCategory = category?.trim().orEmpty()
        val parsedTimeRange = parseTimeRange(timeRange)
        val parsedSort = parseSort(sort)

        val items = transaction {
            fetchRecipeSnapshots(currentUserId)
        }.filter {
            (normalizedQuery.isBlank() || it.title.contains(normalizedQuery, ignoreCase = true)) &&
                (normalizedCategory.isBlank() || it.category.equals(normalizedCategory, ignoreCase = true)) &&
                inTimeRange(parsedTimeRange, it.cookingTimeMinutes)
        }.sortedWith(sortComparator(parsedSort))

        val offset = (page - 1) * limit
        return PagedRecipesResponse(
            items = items.drop(offset).take(limit).map { it.toSummaryDto() },
            page = page,
            limit = limit,
            total = items.size,
        )
    }

    fun getRecipe(recipeId: UUID, currentUserId: UUID?): RecipeDetailsDto = transaction {
        val recipe = fetchRecipeSnapshots(currentUserId).firstOrNull { it.id == recipeId }
            ?: notFound("Recipe not found")
        recipe.toDetailsDto()
    }

    fun createRecipe(authorId: UUID, request: RecipeUpsertRequest): RecipeDetailsDto = transaction {
        validateRecipeRequest(request)
        ensureUserExists(authorId)

        val recipeId = randomId()
        val timestamp = now()
        RecipesTable.insert {
            it[id] = recipeId
            it[RecipesTable.authorId] = authorId
            it[title] = request.title.trim()
            it[description] = request.description.trim()
            it[category] = request.category.trim()
            it[cookingTimeMinutes] = request.cookingTimeMinutes
            it[ingredients] = request.ingredients.map(String::trim).toDbJson()
            it[steps] = request.steps.map(String::trim).toDbJson()
            it[imageUrls] = request.imageUrls.map(String::trim).toDbJson()
            it[createdAt] = timestamp
            it[updatedAt] = timestamp
        }

        fetchRecipeSnapshots(authorId).first { it.id == recipeId }.toDetailsDto()
    }

    fun updateRecipe(recipeId: UUID, authorId: UUID, request: RecipeUpsertRequest): RecipeDetailsDto = transaction {
        validateRecipeRequest(request)
        val recipe = RecipesTable.selectAll().where { RecipesTable.id eq recipeId }.singleOrNull()
            ?: notFound("Recipe not found")
        if (recipe[RecipesTable.authorId].value != authorId) {
            forbidden("Only the author can edit this recipe")
        }

        RecipesTable.update({ RecipesTable.id eq recipeId }) {
            it[title] = request.title.trim()
            it[description] = request.description.trim()
            it[category] = request.category.trim()
            it[cookingTimeMinutes] = request.cookingTimeMinutes
            it[ingredients] = request.ingredients.map(String::trim).toDbJson()
            it[steps] = request.steps.map(String::trim).toDbJson()
            it[imageUrls] = request.imageUrls.map(String::trim).toDbJson()
            it[updatedAt] = now()
        }

        fetchRecipeSnapshots(authorId).first { it.id == recipeId }.toDetailsDto()
    }

    fun deleteRecipe(recipeId: UUID, actorId: UUID) = transaction {
        val recipe = RecipesTable.selectAll().where { RecipesTable.id eq recipeId }.singleOrNull()
            ?: notFound("Recipe not found")
        if (recipe[RecipesTable.authorId].value != actorId) {
            forbidden("Only the author can delete this recipe")
        }
        RecipesTable.deleteWhere { RecipesTable.id eq recipeId }
    }

    fun setRating(recipeId: UUID, userId: UUID, value: Int): RecipeDetailsDto = transaction {
        ensureAuthenticatedUserExists(userId)
        if (value != 1 && value != -1) {
            badRequest("Rating value must be 1 or -1")
        }
        requireRecipe(recipeId)

        val existing = RatingsTable.selectAll().where {
            (RatingsTable.recipeId eq recipeId) and (RatingsTable.userId eq userId)
        }.singleOrNull()

        when {
            existing == null -> RatingsTable.insert {
                it[id] = randomId()
                it[RatingsTable.recipeId] = recipeId
                it[RatingsTable.userId] = userId
                it[RatingsTable.value] = value
            }
            existing[RatingsTable.value] == value -> RatingsTable.deleteWhere {
                (RatingsTable.recipeId eq recipeId) and (RatingsTable.userId eq userId)
            }
            else -> RatingsTable.update({
                (RatingsTable.recipeId eq recipeId) and (RatingsTable.userId eq userId)
            }) {
                it[RatingsTable.value] = value
            }
        }

        fetchRecipeSnapshots(userId).first { it.id == recipeId }.toDetailsDto()
    }

    fun removeRating(recipeId: UUID, userId: UUID) = transaction {
        ensureAuthenticatedUserExists(userId)
        RatingsTable.deleteWhere {
            (RatingsTable.recipeId eq recipeId) and (RatingsTable.userId eq userId)
        }
    }

    fun addFavorite(recipeId: UUID, userId: UUID): RecipeDetailsDto = transaction {
        ensureAuthenticatedUserExists(userId)
        requireRecipe(recipeId)
        FavoritesTable.insertIgnore {
            it[FavoritesTable.recipeId] = recipeId
            it[FavoritesTable.userId] = userId
        }
        fetchRecipeSnapshots(userId).first { it.id == recipeId }.toDetailsDto()
    }

    fun removeFavorite(recipeId: UUID, userId: UUID) = transaction {
        ensureAuthenticatedUserExists(userId)
        FavoritesTable.deleteWhere {
            (FavoritesTable.recipeId eq recipeId) and (FavoritesTable.userId eq userId)
        }
    }

    fun listFavorites(userId: UUID): List<RecipeSummaryDto> = transaction {
        ensureAuthenticatedUserExists(userId)
        val favoriteIds = FavoritesTable.selectAll().where { FavoritesTable.userId eq userId }
            .map { it[FavoritesTable.recipeId].value }
            .toSet()
        fetchRecipeSnapshots(userId)
            .filter { it.id in favoriteIds }
            .sortedByDescending { it.createdAt }
            .map { it.toSummaryDto() }
    }

    fun listFeed(userId: UUID): List<RecipeSummaryDto> = transaction {
        ensureAuthenticatedUserExists(userId)
        val followingIds = FollowsTable.selectAll().where { FollowsTable.followerId eq userId }
            .map { it[FollowsTable.followingId].value }
            .toSet()
        if (followingIds.isEmpty()) return@transaction emptyList()

        fetchRecipeSnapshots(userId)
            .filter { it.author.id in followingIds.map(UUID::toString).toSet() }
            .sortedByDescending { it.createdAt }
            .map { it.toSummaryDto() }
    }

    fun listRecipesByAuthor(authorId: UUID, currentUserId: UUID?): List<RecipeSummaryDto> = transaction {
        fetchRecipeSnapshots(currentUserId)
            .filter { it.author.id == authorId.toString() }
            .sortedByDescending { it.createdAt }
            .map { it.toSummaryDto() }
    }

    fun isRecipeAuthor(recipeId: UUID, userId: UUID): Boolean = transaction {
        RecipesTable.selectAll().where {
            (RecipesTable.id eq recipeId) and (RecipesTable.authorId eq userId)
        }.count() > 0
    }

    fun getRecipeAuthorId(recipeId: UUID): UUID = transaction {
        RecipesTable.selectAll().where { RecipesTable.id eq recipeId }.singleOrNull()
            ?.get(RecipesTable.authorId)?.value
            ?: notFound("Recipe not found")
    }

    private fun validateRecipeRequest(request: RecipeUpsertRequest) {
        if (request.title.trim().length !in 1..50) {
            badRequest("Title must contain from 1 to 50 characters")
        }
        if (request.description.trim().length !in 1..1000) {
            badRequest("Description must contain from 1 to 1000 characters")
        }
        if (request.category.trim() !in RecipeCategories) {
            badRequest("Unsupported category")
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
            badRequest("A recipe can contain at most 5 image URLs")
        }
    }

    private fun validatePage(page: Int, limit: Int) {
        if (page < 1 || limit !in 1..100) {
            badRequest("Invalid pagination values")
        }
    }

    private fun ensureUserExists(userId: UUID) {
        val exists = UsersTable.selectAll().where { UsersTable.id eq userId }.count() > 0
        if (!exists) {
            notFound("User not found")
        }
    }

    private fun ensureAuthenticatedUserExists(userId: UUID) {
        val exists = UsersTable.selectAll().where { UsersTable.id eq userId }.count() > 0
        if (!exists) {
            unauthorized("Session is no longer valid. Please sign in again")
        }
    }

    private fun requireRecipe(recipeId: UUID) {
        val exists = RecipesTable.selectAll().where { RecipesTable.id eq recipeId }.count() > 0
        if (!exists) {
            notFound("Recipe not found")
        }
    }

    private fun sortComparator(sort: RecipeSort): Comparator<RecipeSnapshot> = when (sort) {
        RecipeSort.NEWEST -> compareByDescending<RecipeSnapshot> { it.createdAt }
        RecipeSort.RATING -> compareByDescending<RecipeSnapshot> { it.rating }
            .thenByDescending { it.createdAt }
    }

    private fun fetchRecipeSnapshots(currentUserId: UUID?): List<RecipeSnapshot> {
        val rows = (RecipesTable innerJoin UsersTable).selectAll().map(::RecipeRow)
        if (rows.isEmpty()) return emptyList()

        val recipeIds = rows.map { it.id }
        val ratings = RatingsTable.selectAll().where { RatingsTable.recipeId inList recipeIds }
            .groupBy { it[RatingsTable.recipeId].value }
        val favorites = currentUserId?.let { userId ->
            FavoritesTable.selectAll().where {
                (FavoritesTable.userId eq userId) and (FavoritesTable.recipeId inList recipeIds)
            }.map { it[FavoritesTable.recipeId].value }.toSet()
        } ?: emptySet()
        val myRatings = currentUserId?.let { userId ->
            RatingsTable.selectAll().where {
                (RatingsTable.userId eq userId) and (RatingsTable.recipeId inList recipeIds)
            }.associate { it[RatingsTable.recipeId].value to it[RatingsTable.value] }
        } ?: emptyMap()

        return rows.map { row ->
            val recipeRatings = ratings[row.id].orEmpty()
            val likes = recipeRatings.count { it[RatingsTable.value] == 1 }
            val dislikes = recipeRatings.count { it[RatingsTable.value] == -1 }
            RecipeSnapshot(
                id = row.id,
                title = row.title,
                description = row.description,
                category = row.category,
                cookingTimeMinutes = row.cookingTimeMinutes,
                ingredients = row.ingredients,
                steps = row.steps,
                imageUrls = row.imageUrls,
                createdAt = row.createdAt,
                updatedAt = row.updatedAt,
                author = row.author,
                likesCount = likes,
                dislikesCount = dislikes,
                rating = likes - dislikes,
                isFavorite = row.id in favorites,
                myRating = myRatings[row.id],
            )
        }
    }
}

private data class RecipeRow(
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
) {
    constructor(row: ResultRow) : this(
        id = row[RecipesTable.id].value,
        title = row[RecipesTable.title],
        description = row[RecipesTable.description],
        category = row[RecipesTable.category],
        cookingTimeMinutes = row[RecipesTable.cookingTimeMinutes],
        ingredients = row[RecipesTable.ingredients].fromDbJson(),
        steps = row[RecipesTable.steps].fromDbJson(),
        imageUrls = row[RecipesTable.imageUrls].fromDbJson(),
        createdAt = row[RecipesTable.createdAt],
        updatedAt = row[RecipesTable.updatedAt],
        author = row.toSummary(),
    )
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
