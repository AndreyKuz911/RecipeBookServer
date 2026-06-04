package com.recipebook.server.database

import com.recipebook.server.features.common.notFound
import com.recipebook.server.features.common.unauthorized
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.sql.Types
import java.util.UUID

internal fun PreparedStatement.bindNullableUuid(index: Int, value: UUID?): Int {
    if (value == null) {
        setNull(index, Types.OTHER)
    } else {
        setObject(index, value)
    }
    return index + 1
}

internal fun bindParams(statement: PreparedStatement, params: List<Any>, startIndex: Int = 1): Int {
    var index = startIndex
    params.forEach { param ->
        when (param) {
            is String -> statement.setString(index, param)
            is Int -> statement.setInt(index, param)
            is UUID -> statement.setObject(index, param)
            is Timestamp -> statement.setTimestamp(index, param)
            else -> statement.setObject(index, param)
        }
        index++
    }
    return index
}

internal fun Connection.requireUserExists(userId: UUID, asUnauthorized: Boolean = false) {
    prepareStatement(
        "SELECT 1 FROM users WHERE id = ? LIMIT 1",
    ).use { statement ->
        statement.setObject(1, userId)
        statement.executeQuery().use { rs ->
            if (!rs.next()) {
                if (asUnauthorized) unauthorized("Session is no longer valid. Please sign in again")
                notFound("User not found")
            }
        }
    }
}

internal fun Connection.requireAuthenticatedUserExists(userId: UUID) {
    requireUserExists(userId = userId, asUnauthorized = true)
}

internal fun Connection.requireRecipeExists(recipeId: UUID) {
    prepareStatement(
        "SELECT 1 FROM recipes WHERE id = ? LIMIT 1",
    ).use { statement ->
        statement.setObject(1, recipeId)
        statement.executeQuery().use { rs ->
            if (!rs.next()) {
                notFound("Recipe not found")
            }
        }
    }
}

internal fun Connection.findRecipeAuthorId(recipeId: UUID): UUID {
    return prepareStatement(
        "SELECT author_id FROM recipes WHERE id = ? LIMIT 1",
    ).use { statement ->
        statement.setObject(1, recipeId)
        statement.executeQuery().use { rs ->
            if (!rs.next()) {
                notFound("Recipe not found")
            }
            rs.getObject("author_id", UUID::class.java)
        }
    }
}
