package com.recipebook.server.features.auth

import com.recipebook.server.database.DatabaseFactory
import com.recipebook.server.features.common.badRequest
import com.recipebook.server.features.common.conflict
import com.recipebook.server.features.common.serviceUnavailable
import com.recipebook.server.features.common.unauthorized
import com.recipebook.server.features.users.UserProfileDto
import com.recipebook.server.security.JwtService
import com.recipebook.server.security.PasswordHasher
import java.net.SocketException
import java.net.SocketTimeoutException
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.sql.Types
import java.time.LocalDateTime
import java.util.UUID

class AuthService(
    private val passwordHasher: PasswordHasher,
    private val jwtService: JwtService,
) {
    fun register(request: RegisterRequest): AuthResponse {
        validateRegister(request)

        val normalizedEmail = request.email.trim().lowercase()
        val normalizedUsername = request.username.trim()

        return withSqlRetry {
            DatabaseFactory.dataSource().connection.use { connection ->
                registerInternal(connection, normalizedEmail, normalizedUsername, request.password)
            }
        }
    }

    private fun registerInternal(
        connection: Connection,
        normalizedEmail: String,
        normalizedUsername: String,
        rawPassword: String,
    ): AuthResponse {
        val userId = UUID.randomUUID()
        val nowValue = LocalDateTime.now()
        try {
            connection.prepareStatement(
                """
                INSERT INTO users(id, email, username, password_hash, bio, avatar_url, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId, Types.OTHER)
                statement.setString(2, normalizedEmail)
                statement.setString(3, normalizedUsername)
                statement.setString(4, passwordHasher.hash(rawPassword))
                statement.setString(5, null)
                statement.setString(6, null)
                statement.setTimestamp(7, Timestamp.valueOf(nowValue))
                statement.executeUpdate()
            }
            val token = jwtService.createToken(userId, normalizedEmail)
            return AuthResponse(
                token = token,
                user = UserProfileDto(
                    id = userId.toString(),
                    email = normalizedEmail,
                    username = normalizedUsername,
                    bio = null,
                    avatarUrl = null,
                    createdAt = nowValue.toString(),
                    recipesCount = 0,
                    followersCount = 0,
                    followingCount = 0,
                    isFollowing = false,
                ),
            )
        } catch (e: SQLException) {
            if (e.sqlState == "23505") {
                val message = e.message.orEmpty()
                if (message.contains("email", ignoreCase = true)) {
                    conflict("Email is already registered")
                }
                if (message.contains("username", ignoreCase = true)) {
                    conflict("Username is already taken")
                }
                conflict("User already exists")
            }
            throw e
        } catch (e: Exception) {
            throw e
        }
    }

    fun login(request: LoginRequest): AuthResponse {
        if (request.email.isBlank() || request.password.isBlank()) {
            badRequest("Email and password are required")
        }

        val normalizedEmail = request.email.trim().lowercase()
        return withSqlRetry {
            DatabaseFactory.dataSource().connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT id, email, username, password_hash, bio, avatar_url, created_at
                    FROM users
                    WHERE email = ?
                    LIMIT 1
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, normalizedEmail)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) unauthorized("Invalid credentials")
                        val passwordHash = rs.getString("password_hash")
                        if (!passwordHasher.verify(request.password, passwordHash)) {
                            unauthorized("Invalid credentials")
                        }

                        val userId = rs.getObject("id", UUID::class.java)
                        val token = jwtService.createToken(userId, rs.getString("email"))
                        AuthResponse(
                            token = token,
                            user = UserProfileDto(
                                id = userId.toString(),
                                email = rs.getString("email"),
                                username = rs.getString("username"),
                                bio = rs.getString("bio"),
                                avatarUrl = rs.getString("avatar_url"),
                                createdAt = rs.getTimestamp("created_at").toLocalDateTime().toString(),
                                recipesCount = 0,
                                followersCount = 0,
                                followingCount = 0,
                                isFollowing = false,
                            ),
                        )
                    }
                }
            }
        }
    }

    private inline fun <T> withSqlRetry(maxAttempts: Int = 2, block: () -> T): T {
        var lastError: SQLException? = null
        repeat(maxAttempts) { index ->
            try {
                return block()
            } catch (sqlEx: SQLException) {
                lastError = sqlEx
                val retriable = isRetriableConnectionFailure(sqlEx)
                val isLastAttempt = index == maxAttempts - 1
                if (!retriable) {
                    throw sqlEx
                }
                if (isLastAttempt) {
                    serviceUnavailable("Temporary database connectivity issue. Please retry.")
                } else {
                    Thread.sleep(400L * (index + 1))
                }
            }
        }
        throw lastError ?: IllegalStateException("Database operation failed")
    }

    private fun isRetriableConnectionFailure(error: SQLException): Boolean {
        if (
            error.sqlState == "08006" ||
            error.sqlState == "08001" ||
            error.sqlState == "57P01" ||
            error.sqlState == "57014" ||
            error.sqlState == "55P03"
        ) {
            return true
        }
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is SocketException || cause is SocketTimeoutException) return true
            cause = cause.cause
        }
        return false
    }

    private fun validateRegister(request: RegisterRequest) {
        if (!EMAIL_REGEX.matches(request.email.trim())) {
            badRequest("Invalid email format")
        }
        if (request.username.trim().length !in 3..50) {
            badRequest("Username must contain from 3 to 50 characters")
        }
        if (request.password.length < 6) {
            badRequest("Password must contain at least 6 characters")
        }
    }

    companion object {
        private val EMAIL_REGEX = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$")
    }
}
