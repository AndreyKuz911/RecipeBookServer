package com.recipebook.server

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RecipeSearchTest {
    @BeforeTest
    fun setUp() {
        prepareTestDatabase()
    }

    @Test
    fun `search and filters return matching recipes`() {
        val authorId = createUser(email = "chef@example.com", username = "chef")
        createRecipe(authorId, "Куриный суп", "Супы", 20)
        createRecipe(authorId, "Овощной салат", "Салаты", 10)
        createRecipe(authorId, "Паста карбонара", "Горячее", 40)

        val result = recipeServiceFixture().listRecipes(
            page = 1,
            limit = 20,
            query = "суп",
            category = "Супы",
            timeRange = "15-30",
            sort = "newest",
            currentUserId = authorId,
        )

        assertEquals(1, result.total)
        assertEquals("Куриный суп", result.items.single().title)
    }
}
