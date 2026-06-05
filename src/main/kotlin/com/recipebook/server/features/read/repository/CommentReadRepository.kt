package com.recipebook.server.features.read.repository

import com.recipebook.server.database.DatabaseFactory
import com.recipebook.server.features.comments.CommentDto
import com.recipebook.server.features.read.queryComments
import java.util.UUID

class CommentReadRepository {
    fun listComments(recipeId: UUID): List<CommentDto> {
        return DatabaseFactory.withDbRetry(maxAttempts = 2) {
            DatabaseFactory.dataSource().connection.use { connection ->
                queryComments(connection, recipeId)
            }
        }
    }
}
