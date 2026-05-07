package com.recipebook.server.features.users

import com.recipebook.server.database.FollowsTable
import com.recipebook.server.database.DatabaseFactory
import com.recipebook.server.database.RecipesTable
import com.recipebook.server.database.UsersTable
import com.recipebook.server.features.common.badRequest
import com.recipebook.server.features.common.conflict
import com.recipebook.server.features.common.notFound
import com.recipebook.server.features.common.unauthorized
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

class UserService {
    fun getCurrentProfile(userId: UUID): UserProfileDto = dbTx {
        ensureAuthenticatedUserExists(userId)
        getProfileInternal(userId, userId)
    }

    fun getProfile(targetUserId: UUID, viewerId: UUID?): UserProfileDto = dbTx {
        getProfileInternal(targetUserId, viewerId)
    }

    fun updateProfile(userId: UUID, request: UpdateProfileRequest): UserProfileDto = dbTx {
        ensureAuthenticatedUserExists(userId)
        validateUpdate(request)
        val existing = UsersTable.selectAll().where { UsersTable.id eq userId }.singleOrNull()
            ?: notFound("User not found")

        val normalizedUsername = request.username.trim()
        val duplicate = UsersTable.selectAll().where {
            (UsersTable.username eq normalizedUsername) and (UsersTable.id neq userId)
        }.count() > 0
        if (duplicate) {
            conflict("Username is already taken")
        }

        UsersTable.update({ UsersTable.id eq userId }) {
            it[username] = normalizedUsername
            it[bio] = request.bio?.trim()?.ifBlank { null }
            it[avatarUrl] = request.avatarUrl?.trim()?.ifBlank { null }
        }

        getProfileInternal(existing[UsersTable.id].value, existing[UsersTable.id].value)
    }

    fun follow(followerId: UUID, followingId: UUID) = dbTx {
        ensureAuthenticatedUserExists(followerId)
        if (followerId == followingId) {
            badRequest("You cannot follow yourself")
        }
        ensureUserExists(followingId)
        FollowsTable.insertIgnore {
            it[FollowsTable.followerId] = followerId
            it[FollowsTable.followingId] = followingId
        }
    }

    fun unfollow(followerId: UUID, followingId: UUID) = dbTx {
        ensureAuthenticatedUserExists(followerId)
        FollowsTable.deleteWhere {
            (FollowsTable.followerId eq followerId) and (FollowsTable.followingId eq followingId)
        }
    }

    fun getUserSummary(userId: UUID): UserSummaryDto = dbTx {
        val row = UsersTable.selectAll().where { UsersTable.id eq userId }.singleOrNull()
            ?: notFound("User not found")
        row.toSummary()
    }

    fun ensureUserExists(userId: UUID) {
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

    private fun validateUpdate(request: UpdateProfileRequest) {
        val username = request.username.trim()
        if (username.length !in 3..50) {
            badRequest("Username must contain from 3 to 50 characters")
        }
    }

    private fun getProfileInternal(targetUserId: UUID, viewerId: UUID?): UserProfileDto {
        val user = UsersTable.selectAll().where { UsersTable.id eq targetUserId }.singleOrNull()
            ?: notFound("User not found")
        val recipesCount = RecipesTable.selectAll().where { RecipesTable.authorId eq targetUserId }.count().toInt()
        val followersCount = FollowsTable.selectAll().where { FollowsTable.followingId eq targetUserId }.count().toInt()
        val followingCount = FollowsTable.selectAll().where { FollowsTable.followerId eq targetUserId }.count().toInt()
        val isFollowing = viewerId != null && viewerId != targetUserId &&
            FollowsTable.selectAll().where {
                (FollowsTable.followerId eq viewerId) and (FollowsTable.followingId eq targetUserId)
            }.count() > 0

        return UserProfileDto(
            id = user[UsersTable.id].value.toString(),
            email = user[UsersTable.email],
            username = user[UsersTable.username],
            bio = user[UsersTable.bio],
            avatarUrl = user[UsersTable.avatarUrl],
            createdAt = user[UsersTable.createdAt].toString(),
            recipesCount = recipesCount,
            followersCount = followersCount,
            followingCount = followingCount,
            isFollowing = isFollowing,
        )
    }

    private inline fun <T> dbTx(crossinline block: () -> T): T {
        return DatabaseFactory.withDbRetry {
            transaction { block() }
        }
    }
}

fun ResultRow.toSummary(): UserSummaryDto = UserSummaryDto(
    id = this[UsersTable.id].value.toString(),
    username = this[UsersTable.username],
    avatarUrl = this[UsersTable.avatarUrl],
)
