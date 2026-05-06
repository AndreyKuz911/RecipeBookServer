package com.recipebook.server

import com.recipebook.server.features.common.ApiException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RecipeAuthorizationTest {
    @BeforeTest
    fun setUp() {
        prepareTestDatabase()
    }

    @Test
    fun `author can delete recipe`() {
        val authorId = createUser(email = "author@example.com", username = "author")
        val recipeId = createRecipe(authorId, "Авторский суп", "Супы", 25)

        recipeServiceFixture().deleteRecipe(recipeId, authorId)

        val error = assertFailsWith<ApiException> {
            recipeServiceFixture().getRecipe(recipeId, authorId)
        }
        assertEquals("Recipe not found", error.message)
    }

    @Test
    fun `other user cannot delete recipe`() {
        val authorId = createUser(email = "author@example.com", username = "author")
        val otherId = createUser(email = "other@example.com", username = "other")
        val recipeId = createRecipe(authorId, "Тыквенный суп", "Супы", 35)

        val error = assertFailsWith<ApiException> {
            recipeServiceFixture().deleteRecipe(recipeId, otherId)
        }

        assertEquals("Only the author can delete this recipe", error.message)
    }
}
