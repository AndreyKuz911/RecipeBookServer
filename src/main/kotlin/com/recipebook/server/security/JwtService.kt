package com.recipebook.server.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.recipebook.server.config.JwtConfig
import java.util.Date
import java.util.UUID

class JwtService(private val config: JwtConfig) {
    private val algorithm = Algorithm.HMAC256(config.secret)

    fun algorithm(): Algorithm = algorithm

    fun createToken(userId: UUID, email: String): String {
        val now = System.currentTimeMillis()
        return JWT.create()
            .withAudience(config.audience)
            .withIssuer(config.issuer)
            .withClaim("userId", userId.toString())
            .withClaim("email", email)
            .withExpiresAt(Date(now + ACCESS_TOKEN_TTL_MILLIS))
            .sign(algorithm)
    }

    companion object {
        private const val ACCESS_TOKEN_TTL_MILLIS = 1000L * 60 * 60 * 24 * 7
    }
}
