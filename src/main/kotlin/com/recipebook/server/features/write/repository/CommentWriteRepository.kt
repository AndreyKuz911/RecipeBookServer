package com.recipebook.server.features.write.repository

import com.recipebook.server.database.DatabaseFactory
import com.recipebook.server.database.findRecipeAuthorId
import com.recipebook.server.database.requireAuthenticatedUserExists
import com.recipebook.server.database.requireRecipeExists
import com.recipebook.server.features.comments.CommentDto
import com.recipebook.server.features.common.forbidden
import com.recipebook.server.features.write.loadCommentAuthorSummary
import com.recipebook.server.features.write.loadCommentOwnership
import com.recipebook.server.features.write.resolveParentCommentId
import java.sql.Types
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.UUID

class CommentWriteRepository {
    fun createComment(recipeId: UUID, userId: UUID, text: String, rawParentCommentId: String?): CommentDto {
        return DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                connection.requireAuthenticatedUserExists(userId)
                connection.requireRecipeExists(recipeId)
                val parentId = connection.resolveParentCommentId(recipeId, rawParentCommentId)

                val commentId = UUID.randomUUID()
                val createdAt = LocalDateTime.now()
                connection.prepareStatement(
                    """
                    INSERT INTO comments (id, recipe_id, author_id, parent_comment_id, text, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, commentId)
                    statement.setObject(2, recipeId)
                    statement.setObject(3, userId)
                    if (parentId == null) {
                        statement.setNull(4, Types.OTHER)
                    } else {
                        statement.setObject(4, parentId)
                    }
                    statement.setString(5, text)
                    statement.setTimestamp(6, Timestamp.valueOf(createdAt))
                    statement.executeUpdate()
                }

                CommentDto(
                    id = commentId.toString(),
                    recipeId = recipeId.toString(),
                    parentCommentId = parentId?.toString(),
                    text = text,
                    createdAt = createdAt.toString(),
                    author = connection.loadCommentAuthorSummary(userId),
                    replies = emptyList(),
                )
            }
        }
    }

    fun deleteComment(commentId: UUID, actorId: UUID) {
        DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                connection.requireAuthenticatedUserExists(actorId)
                val comment = connection.loadCommentOwnership(commentId)
                val recipeAuthor = connection.findRecipeAuthorId(comment.first)
                if (comment.second != actorId && recipeAuthor != actorId) {
                    forbidden("You cannot delete this comment")
                }
                connection.prepareStatement(
                    "DELETE FROM comments WHERE id = ? OR parent_comment_id = ?",
                ).use { statement ->
                    statement.setObject(1, commentId)
                    statement.setObject(2, commentId)
                    statement.executeUpdate()
                }
            }
        }
    }
}
