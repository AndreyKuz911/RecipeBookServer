package com.recipebook.server.features.read

import com.recipebook.server.database.bindNullableUuid
import com.recipebook.server.features.common.notFound
import com.recipebook.server.features.common.unauthorized
import com.recipebook.server.features.users.UserProfileDto
import java.util.UUID

internal fun queryProfile(
    connection: java.sql.Connection,
    targetUserId: UUID,
    viewerId: UUID?,
): UserProfileDto {
    return connection.prepareStatement(
        """
        SELECT
          u.id,
          u.email,
          u.username,
          u.bio,
          u.avatar_url,
          u.created_at,
          (SELECT COUNT(*) FROM recipes r WHERE r.author_id = u.id) AS recipes_count,
          (SELECT COUNT(*) FROM follows f WHERE f.following_id = u.id) AS followers_count,
          (SELECT COUNT(*) FROM follows f WHERE f.follower_id = u.id) AS following_count,
          CASE
            WHEN ? IS NULL OR ? = u.id THEN FALSE
            ELSE EXISTS(
              SELECT 1
              FROM follows f2
              WHERE f2.follower_id = ? AND f2.following_id = u.id
            )
          END AS is_following
        FROM users u
        WHERE u.id = ?
        """.trimIndent(),
    ).use { statement ->
        statement.bindNullableUuid(1, viewerId)
        statement.bindNullableUuid(2, viewerId)
        statement.bindNullableUuid(3, viewerId)
        statement.setObject(4, targetUserId)

        statement.executeQuery().use { rs ->
            if (!rs.next()) {
                notFound("User not found")
            }
            UserProfileDto(
                id = rs.getObject("id", UUID::class.java).toString(),
                email = rs.getString("email"),
                username = rs.getString("username"),
                bio = rs.getString("bio"),
                avatarUrl = rs.getString("avatar_url"),
                createdAt = rs.getTimestamp("created_at").toLocalDateTime().toString(),
                recipesCount = rs.getInt("recipes_count"),
                followersCount = rs.getInt("followers_count"),
                followingCount = rs.getInt("following_count"),
                isFollowing = rs.getBoolean("is_following"),
            )
        }
    }
}

internal fun queryCurrentProfile(
    connection: java.sql.Connection,
    userId: UUID,
): UserProfileDto {
    return connection.prepareStatement(
        """
        SELECT
          u.id,
          u.email,
          u.username,
          u.bio,
          u.avatar_url,
          u.created_at,
          (SELECT COUNT(*) FROM recipes r WHERE r.author_id = u.id) AS recipes_count,
          (SELECT COUNT(*) FROM follows f WHERE f.following_id = u.id) AS followers_count,
          (SELECT COUNT(*) FROM follows f WHERE f.follower_id = u.id) AS following_count
        FROM users u
        WHERE u.id = ?
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId)
        statement.executeQuery().use { rs ->
            if (!rs.next()) {
                unauthorized("Session is no longer valid. Please sign in again")
            }
            UserProfileDto(
                id = rs.getObject("id", UUID::class.java).toString(),
                email = rs.getString("email"),
                username = rs.getString("username"),
                bio = rs.getString("bio"),
                avatarUrl = rs.getString("avatar_url"),
                createdAt = rs.getTimestamp("created_at").toLocalDateTime().toString(),
                recipesCount = rs.getInt("recipes_count"),
                followersCount = rs.getInt("followers_count"),
                followingCount = rs.getInt("following_count"),
                isFollowing = false,
            )
        }
    }
}
