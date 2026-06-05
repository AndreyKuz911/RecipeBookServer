package com.recipebook.server.features.write

import com.recipebook.server.database.findRecipeAuthorId
import com.recipebook.server.features.common.badRequest
import com.recipebook.server.features.common.conflict
import com.recipebook.server.features.common.forbidden
import com.recipebook.server.features.common.notFound
import com.recipebook.server.features.users.UserSummaryDto
import java.sql.Connection
import java.util.UUID

internal fun Connection.requireRecipeAuthor(recipeId: UUID, actorId: UUID, action: String) {
    if (findRecipeAuthorId(recipeId) != actorId) {
        forbidden("Only the author can $action this recipe")
    }
}

internal fun Connection.requireUsernameAvailable(userId: UUID, username: String) {
    val duplicate = prepareStatement(
        "SELECT 1 FROM users WHERE username = ? AND id <> ? LIMIT 1",
    ).use { statement ->
        statement.setString(1, username)
        statement.setObject(2, userId)
        statement.executeQuery().use { rs -> rs.next() }
    }
    if (duplicate) {
        conflict("Username is already taken")
    }
}

internal fun Connection.resolveParentCommentId(recipeId: UUID, rawParentCommentId: String?): UUID? {
    val parentId = rawParentCommentId?.let { raw ->
        runCatching { UUID.fromString(raw) }.getOrElse { badRequest("Invalid parentCommentId") }
    } ?: return null

    val parentCommentId = prepareStatement(
        """
        SELECT parent_comment_id
        FROM comments
        WHERE id = ? AND recipe_id = ?
        LIMIT 1
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, parentId)
        statement.setObject(2, recipeId)
        statement.executeQuery().use { rs ->
            if (!rs.next()) {
                badRequest("Parent comment not found")
            }
            rs.getObject("parent_comment_id", UUID::class.java)
        }
    }

    if (parentCommentId != null) {
        badRequest("Only one nesting level is supported")
    }
    return parentId
}

internal fun Connection.loadCommentAuthorSummary(userId: UUID): UserSummaryDto {
    return prepareStatement(
        "SELECT id, username, avatar_url FROM users WHERE id = ? LIMIT 1",
    ).use { statement ->
        statement.setObject(1, userId)
        statement.executeQuery().use { rs ->
            if (!rs.next()) {
                notFound("User not found")
            }
            UserSummaryDto(
                id = rs.getObject("id", UUID::class.java).toString(),
                username = rs.getString("username"),
                avatarUrl = rs.getString("avatar_url"),
            )
        }
    }
}

internal fun Connection.loadCommentOwnership(commentId: UUID): Pair<UUID, UUID> {
    return prepareStatement(
        "SELECT recipe_id, author_id FROM comments WHERE id = ? LIMIT 1",
    ).use { statement ->
        statement.setObject(1, commentId)
        statement.executeQuery().use { rs ->
            if (!rs.next()) {
                notFound("Comment not found")
            }
            rs.getObject("recipe_id", UUID::class.java) to rs.getObject("author_id", UUID::class.java)
        }
    }
}
