package com.recipebook.server.features.write.repository

import com.recipebook.server.database.DatabaseFactory
import com.recipebook.server.database.requireAuthenticatedUserExists
import com.recipebook.server.database.requireUserExists
import java.util.UUID

class SocialWriteRepository {
    fun follow(followerId: UUID, followingId: UUID) {
        DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                connection.requireAuthenticatedUserExists(followerId)
                connection.requireUserExists(userId = followingId, asUnauthorized = false)
                connection.prepareStatement(
                    """
                    INSERT INTO follows (follower_id, following_id)
                    VALUES (?, ?)
                    ON CONFLICT DO NOTHING
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, followerId)
                    statement.setObject(2, followingId)
                    statement.executeUpdate()
                }
            }
        }
    }

    fun unfollow(followerId: UUID, followingId: UUID) {
        DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                connection.requireAuthenticatedUserExists(followerId)
                connection.prepareStatement(
                    "DELETE FROM follows WHERE follower_id = ? AND following_id = ?",
                ).use { statement ->
                    statement.setObject(1, followerId)
                    statement.setObject(2, followingId)
                    statement.executeUpdate()
                }
            }
        }
    }
}
