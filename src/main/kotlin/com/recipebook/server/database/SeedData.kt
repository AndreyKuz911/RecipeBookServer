package com.recipebook.server.database

import com.recipebook.server.security.PasswordHasher
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.util.UUID

object SeedData {
    fun seedIfNeeded(passwordHasher: PasswordHasher) {
        transaction {
            if (!UsersTable.selectAll().empty()) return@transaction

            val aliceId = UUID.randomUUID()
            val bobId = UUID.randomUUID()
            val soupId = UUID.randomUUID()
            val dessertId = UUID.randomUUID()

            UsersTable.insert {
                it[id] = aliceId
                it[email] = "alice@example.com"
                it[username] = "alice_chef"
                it[passwordHash] = passwordHasher.hash("password123")
                it[bio] = "Люблю простые домашние рецепты."
                it[avatarUrl] = "https://images.unsplash.com/photo-1494790108377-be9c29b29330"
                it[createdAt] = LocalDateTime.now().minusDays(7)
            }
            UsersTable.insert {
                it[id] = bobId
                it[email] = "bob@example.com"
                it[username] = "bob_baker"
                it[passwordHash] = passwordHasher.hash("password123")
                it[bio] = "Пеку десерты и завтраки."
                it[avatarUrl] = "https://images.unsplash.com/photo-1500648767791-00dcc994a43e"
                it[createdAt] = LocalDateTime.now().minusDays(3)
            }

            RecipesTable.insert {
                it[id] = soupId
                it[authorId] = aliceId
                it[title] = "Томатный суп за 20 минут"
                it[description] = "Яркий суп с базиликом и сливками."
                it[category] = "Супы"
                it[cookingTimeMinutes] = 20
                it[ingredients] = listOf(
                    "800 г томатов",
                    "1 луковица",
                    "2 зубчика чеснока",
                    "100 мл сливок",
                ).toDbJson()
                it[steps] = listOf(
                    "Обжарьте лук и чеснок.",
                    "Добавьте томаты и тушите 10 минут.",
                    "Пробейте блендером, добавьте сливки.",
                ).toDbJson()
                it[imageUrls] = listOf(
                    "https://images.unsplash.com/photo-1547592166-23ac45744acd",
                ).toDbJson()
                it[createdAt] = LocalDateTime.now().minusDays(2)
                it[updatedAt] = LocalDateTime.now().minusDays(2)
            }

            RecipesTable.insert {
                it[id] = dessertId
                it[authorId] = bobId
                it[title] = "Банановые панкейки"
                it[description] = "Быстрый сладкий завтрак без сложных ингредиентов."
                it[category] = "Завтраки"
                it[cookingTimeMinutes] = 15
                it[ingredients] = listOf(
                    "2 банана",
                    "2 яйца",
                    "120 г муки",
                    "1 ч.л. разрыхлителя",
                ).toDbJson()
                it[steps] = listOf(
                    "Разомните бананы.",
                    "Смешайте с яйцами и мукой.",
                    "Обжарьте на сухой сковороде.",
                ).toDbJson()
                it[imageUrls] = listOf(
                    "https://images.unsplash.com/photo-1528207776546-365bb710ee93",
                ).toDbJson()
                it[createdAt] = LocalDateTime.now().minusDays(1)
                it[updatedAt] = LocalDateTime.now().minusDays(1)
            }

            RatingsTable.insert {
                it[id] = UUID.randomUUID()
                it[userId] = bobId
                it[recipeId] = soupId
                it[value] = 1
            }
            RatingsTable.insert {
                it[id] = UUID.randomUUID()
                it[userId] = aliceId
                it[recipeId] = dessertId
                it[value] = 1
            }

            FollowsTable.insertIgnore {
                it[followerId] = aliceId
                it[followingId] = bobId
            }

            FavoritesTable.insertIgnore {
                it[userId] = aliceId
                it[recipeId] = dessertId
            }

            CommentsTable.insert {
                it[id] = UUID.randomUUID()
                it[recipeId] = soupId
                it[authorId] = bobId
                it[parentCommentId] = null
                it[text] = "Суп получился очень нежным, спасибо!"
                it[createdAt] = LocalDateTime.now().minusHours(10)
            }
        }
    }
}
