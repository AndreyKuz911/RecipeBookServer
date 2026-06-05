package com.recipebook.server

import com.recipebook.server.config.JwtConfig
import com.recipebook.server.database.DatabaseFactory
import com.recipebook.server.database.UsersTable
import com.recipebook.server.features.auth.AuthService
import com.recipebook.server.features.read.ReadService
import com.recipebook.server.features.read.repository.CommentReadRepository
import com.recipebook.server.features.read.repository.ProfileReadRepository
import com.recipebook.server.features.read.repository.RecipeReadRepository
import com.recipebook.server.features.recipes.RecipeUpsertRequest
import com.recipebook.server.features.write.MutationService
import com.recipebook.server.features.write.repository.CommentWriteRepository
import com.recipebook.server.features.write.repository.ProfileWriteRepository
import com.recipebook.server.features.write.repository.RecipeWriteRepository
import com.recipebook.server.features.write.repository.SocialWriteRepository
import com.recipebook.server.security.JwtService
import com.recipebook.server.security.PasswordHasher
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.util.UUID

private const val TEST_DB_URL = "jdbc:h2:mem:recipebook;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"

fun prepareTestDatabase() {
    DatabaseFactory.connectForTests(TEST_DB_URL)
}

fun authServiceFixture(): AuthService {
    return AuthService(
        passwordHasher = PasswordHasher(),
        jwtService = JwtService(
            JwtConfig(
                secret = "test-secret",
                issuer = "test-issuer",
                audience = "test-audience",
                realm = "test-realm",
            ),
        ),
    )
}

fun recipeServiceFixture(): RecipeAppServiceFixture {
    val readService = ReadService(
        recipeReadRepository = RecipeReadRepository(),
        profileReadRepository = ProfileReadRepository(),
        commentReadRepository = CommentReadRepository(),
    )
    return RecipeAppServiceFixture(
        readService = readService,
        mutationService = MutationService(
            readService = readService,
            profileWriteRepository = ProfileWriteRepository(),
            recipeWriteRepository = RecipeWriteRepository(),
            commentWriteRepository = CommentWriteRepository(),
            socialWriteRepository = SocialWriteRepository(),
        ),
    )
}

fun createUser(
    email: String = "user_${UUID.randomUUID()}@example.com",
    username: String = "user_${UUID.randomUUID().toString().take(8)}",
    passwordHash: String = PasswordHasher().hash("password123"),
): UUID = transaction {
    val id = UUID.randomUUID()
    UsersTable.insert {
        it[UsersTable.id] = id
        it[UsersTable.email] = email
        it[UsersTable.username] = username
        it[UsersTable.passwordHash] = passwordHash
        it[UsersTable.bio] = null
        it[UsersTable.avatarUrl] = null
        it[UsersTable.createdAt] = LocalDateTime.now()
    }
    id
}

fun createRecipe(authorId: UUID, title: String, category: String, cookingTimeMinutes: Int): UUID {
    val recipeService = recipeServiceFixture()
    return recipeService.createRecipe(
        authorId = authorId,
        request = RecipeUpsertRequest(
            title = title,
            description = "$title description",
            category = category,
            cookingTimeMinutes = cookingTimeMinutes,
            ingredients = listOf("Ingredient"),
            steps = listOf("Step 1"),
            imageUrls = listOf("https://example.com/image.jpg"),
        ),
    ).id.let(UUID::fromString)
}

class RecipeAppServiceFixture(
    private val readService: ReadService,
    private val mutationService: MutationService,
) {
    fun listRecipes(
        page: Int,
        limit: Int,
        query: String?,
        category: String?,
        timeRange: String?,
        sort: String?,
        currentUserId: UUID?,
    ) = readService.listRecipes(page, limit, query, category, timeRange, sort, currentUserId)

    fun getRecipe(recipeId: UUID, currentUserId: UUID?) = readService.getRecipe(recipeId, currentUserId)

    fun createRecipe(authorId: UUID, request: RecipeUpsertRequest) =
        mutationService.createRecipe(authorId, request)

    fun deleteRecipe(recipeId: UUID, actorId: UUID) =
        mutationService.deleteRecipe(recipeId, actorId)
}
