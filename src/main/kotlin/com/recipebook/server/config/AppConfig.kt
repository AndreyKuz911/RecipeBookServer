package com.recipebook.server.config

import io.github.cdimascio.dotenv.dotenv

data class JwtConfig(
    val secret: String,
    val issuer: String,
    val audience: String,
    val realm: String,
)

data class AppConfig(
    val databaseUrl: String,
    val jwt: JwtConfig,
    val autoCreateSchema: Boolean,
    val seedOnStart: Boolean,
)

object ConfigLoader {
    fun load(): AppConfig {
        val dotenv = dotenv {
            ignoreIfMalformed = true
            ignoreIfMissing = true
        }

        fun get(name: String, default: String? = null): String {
            return System.getenv(name)
                ?: dotenv[name]
                ?: default
                ?: error("Missing required config: $name")
        }

        return AppConfig(
            databaseUrl = normalizeJdbcUrl(get("DATABASE_URL")),
            jwt = JwtConfig(
                secret = get("JWT_SECRET"),
                issuer = get("JWT_ISSUER", "recipebook-server"),
                audience = get("JWT_AUDIENCE", "recipebook-client"),
                realm = get("JWT_REALM", "RecipeBook API"),
            ),
            autoCreateSchema = get("AUTO_CREATE_SCHEMA", "true").toBooleanStrictOrNull() ?: true,
            seedOnStart = get("SEED_ON_START", "true").toBooleanStrictOrNull() ?: true,
        )
    }

    private fun normalizeJdbcUrl(url: String): String {
        return if (url.startsWith("jdbc:")) {
            url
        } else {
            "jdbc:$url"
        }
    }
}
