package com.recipebook.server.plugins

import com.auth0.jwt.JWT
import com.recipebook.server.config.AppConfig
import com.recipebook.server.features.common.unauthorized
import com.recipebook.server.security.JwtService
import com.recipebook.server.security.UserPrincipal
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import java.util.UUID

fun Application.configureSecurity(appConfig: AppConfig, jwtService: JwtService) {
    install(io.ktor.server.auth.Authentication) {
        jwt {
            realm = appConfig.jwt.realm
            verifier(
                JWT.require(jwtService.algorithm())
                    .withIssuer(appConfig.jwt.issuer)
                    .withAudience(appConfig.jwt.audience)
                    .build(),
            )
            validate { credential ->
                val userId = credential.payload.getClaim("userId").asString()
                val email = credential.payload.getClaim("email").asString()
                if (userId.isNullOrBlank() || email.isNullOrBlank()) {
                    null
                } else {
                    UserPrincipal(UUID.fromString(userId), email)
                }
            }
        }
    }
}
