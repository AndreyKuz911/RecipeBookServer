package com.recipebook.server.features.comments

import com.recipebook.server.database.CommentsTable
import com.recipebook.server.database.RecipesTable
import com.recipebook.server.database.UsersTable
import com.recipebook.server.database.now
import com.recipebook.server.database.randomId
import com.recipebook.server.features.common.badRequest
import com.recipebook.server.features.common.forbidden
import com.recipebook.server.features.common.notFound
import com.recipebook.server.features.common.unauthorized
import com.recipebook.server.features.users.toSummary
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class CommentService {
    fun listComments(recipeId: UUID): List<CommentDto> = transaction {
        ensureRecipeExists(recipeId)
        val rows = (CommentsTable innerJoin UsersTable).selectAll()
            .where { CommentsTable.recipeId eq recipeId }
            .sortedByDescending { it[CommentsTable.createdAt] }
        val mapped = rows.map(::toCommentFlat)
        val repliesByParent = mapped.filter { it.parentCommentId != null }.groupBy { it.parentCommentId }

        mapped.filter { it.parentCommentId == null }.map { comment ->
            comment.copy(replies = repliesByParent[comment.id].orEmpty())
        }
    }

    fun createComment(recipeId: UUID, authorId: UUID, request: CreateCommentRequest): CommentDto = transaction {
        ensureAuthenticatedUserExists(authorId)
        val text = request.text.trim()
        if (text.isBlank() || text.length > 500) {
            badRequest("Comment must contain from 1 to 500 characters")
        }
        ensureRecipeExists(recipeId)

        val parentId = request.parentCommentId?.let(UUID::fromString)
        if (parentId != null) {
            val parent = CommentsTable.selectAll().where {
                (CommentsTable.id eq parentId) and (CommentsTable.recipeId eq recipeId)
            }.singleOrNull() ?: badRequest("Parent comment not found")
            if (parent[CommentsTable.parentCommentId] != null) {
                badRequest("Only one nesting level is supported")
            }
        }

        val commentId = randomId()
        CommentsTable.insert {
            it[id] = commentId
            it[CommentsTable.recipeId] = recipeId
            it[CommentsTable.authorId] = authorId
            it[parentCommentId] = parentId
            it[CommentsTable.text] = text
            it[createdAt] = now()
        }

        val row = (CommentsTable innerJoin UsersTable).selectAll().where { CommentsTable.id eq commentId }.single()
        toCommentFlat(row)
    }

    fun deleteComment(commentId: UUID, actorId: UUID) = transaction {
        val comment = CommentsTable.selectAll().where { CommentsTable.id eq commentId }.singleOrNull()
            ?: notFound("Comment not found")

        val recipe = RecipesTable.selectAll().where { RecipesTable.id eq comment[CommentsTable.recipeId].value }.single()
        val canDelete = comment[CommentsTable.authorId].value == actorId || recipe[RecipesTable.authorId].value == actorId
        if (!canDelete) {
            forbidden("You cannot delete this comment")
        }

        CommentsTable.deleteWhere {
            (CommentsTable.id eq commentId) or (CommentsTable.parentCommentId eq commentId)
        }
    }

    private fun ensureRecipeExists(recipeId: UUID) {
        val exists = RecipesTable.selectAll().where { RecipesTable.id eq recipeId }.count() > 0
        if (!exists) {
            notFound("Recipe not found")
        }
    }

    private fun ensureAuthenticatedUserExists(userId: UUID) {
        val exists = UsersTable.selectAll().where { UsersTable.id eq userId }.count() > 0
        if (!exists) {
            unauthorized("Session is no longer valid. Please sign in again")
        }
    }

    private fun toCommentFlat(row: ResultRow): CommentDto = CommentDto(
        id = row[CommentsTable.id].value.toString(),
        recipeId = row[CommentsTable.recipeId].value.toString(),
        parentCommentId = row[CommentsTable.parentCommentId]?.toString(),
        text = row[CommentsTable.text],
        createdAt = row[CommentsTable.createdAt].toString(),
        author = row.toSummary(),
    )
}
