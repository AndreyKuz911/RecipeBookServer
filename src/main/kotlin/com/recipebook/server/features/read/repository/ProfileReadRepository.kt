package com.recipebook.server.features.read.repository

import com.recipebook.server.database.DatabaseFactory
import com.recipebook.server.features.read.queryCurrentProfile
import com.recipebook.server.features.read.queryProfile
import com.recipebook.server.features.users.UserProfileDto
import java.util.UUID

class ProfileReadRepository {
    fun getProfile(targetUserId: UUID, viewerId: UUID?): UserProfileDto {
        return DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                queryProfile(connection, targetUserId, viewerId)
            }
        }
    }

    fun getCurrentProfile(userId: UUID): UserProfileDto {
        return DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                queryCurrentProfile(connection, userId)
            }
        }
    }
}
