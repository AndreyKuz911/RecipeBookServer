package com.recipebook.server.features.write.repository

import com.recipebook.server.database.DatabaseFactory
import com.recipebook.server.database.requireAuthenticatedUserExists
import com.recipebook.server.features.write.ValidatedProfileUpdate
import com.recipebook.server.features.write.requireUsernameAvailable
import java.util.UUID

class ProfileWriteRepository {
    fun updateProfile(userId: UUID, request: ValidatedProfileUpdate) {
        DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                connection.requireAuthenticatedUserExists(userId)
                connection.requireUsernameAvailable(userId, request.username)
                connection.prepareStatement(
                    """
                    UPDATE users
                    SET username = ?, bio = ?, avatar_url = ?
                    WHERE id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, request.username)
                    statement.setString(2, request.bio)
                    statement.setString(3, request.avatarUrl)
                    statement.setObject(4, userId)
                    statement.executeUpdate()
                }
            }
        }
    }
}
