package com.recipebook.server.features.read

import com.recipebook.server.database.requireRecipeExists
import com.recipebook.server.features.comments.CommentDto
import com.recipebook.server.features.users.UserSummaryDto
import java.util.UUID

internal fun queryComments(
    connection: java.sql.Connection,
    recipeId: UUID,
): List<CommentDto> {
    connection.requireRecipeExists(recipeId)
    val flat = connection.prepareStatement(
        """
        SELECT
          c.id,
          c.recipe_id,
          c.parent_comment_id,
          c.text,
          c.created_at,
          u.id AS author_id,
          u.username AS author_username,
          u.avatar_url AS author_avatar
        FROM comments c
        JOIN users u ON u.id = c.author_id
        WHERE c.recipe_id = ?
        ORDER BY c.created_at DESC
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, recipeId)
        statement.executeQuery().use { rs ->
            mutableListOf<CommentDto>().apply {
                while (rs.next()) {
                    add(
                        CommentDto(
                            id = rs.getObject("id", UUID::class.java).toString(),
                            recipeId = rs.getObject("recipe_id", UUID::class.java).toString(),
                            parentCommentId = rs.getObject("parent_comment_id", UUID::class.java)?.toString(),
                            text = rs.getString("text"),
                            createdAt = rs.getTimestamp("created_at").toLocalDateTime().toString(),
                            author = UserSummaryDto(
                                id = rs.getObject("author_id", UUID::class.java).toString(),
                                username = rs.getString("author_username"),
                                avatarUrl = rs.getString("author_avatar"),
                            ),
                        ),
                    )
                }
            }
        }
    }

    val repliesByParent = flat.filter { it.parentCommentId != null }.groupBy { it.parentCommentId }
    return flat.filter { it.parentCommentId == null }.map { comment ->
        comment.copy(replies = repliesByParent[comment.id].orEmpty())
    }
}
