package com.recipebook.server

import com.recipebook.server.config.ConfigLoader
import com.recipebook.server.database.DatabaseFactory
import com.recipebook.server.database.SeedData
import com.recipebook.server.features.auth.AuthService
import com.recipebook.server.features.news.NewsService
import com.recipebook.server.features.read.ReadService
import com.recipebook.server.features.write.MutationService
import com.recipebook.server.plugins.configureHttp
import com.recipebook.server.plugins.configureMonitoring
import com.recipebook.server.plugins.configureSecurity
import com.recipebook.server.plugins.configureSerialization
import com.recipebook.server.routing.configureRouting
import com.recipebook.server.security.JwtService
import com.recipebook.server.security.PasswordHasher
import io.ktor.server.netty.EngineMain
import io.ktor.server.application.Application

fun main(args: Array<String>) {
    EngineMain.main(args)
}

@Suppress("unused")
fun Application.module() {
    val appConfig = ConfigLoader.load()
    val passwordHasher = PasswordHasher()
    val jwtService = JwtService(appConfig.jwt)
    val authService = AuthService(passwordHasher, jwtService)
    val readService = ReadService()
    val mutationService = MutationService(readService)
    val newsService = NewsService()

    runCatching {
        DatabaseFactory.init(appConfig)
        if (appConfig.autoCreateSchema && appConfig.seedOnStart) {
            SeedData.seedIfNeeded(passwordHasher)
        }
    }.onFailure { error ->
        environment.log.warn(
            "Database warm-up on startup failed: {}. The server will retry on the first request.",
            error.message ?: error::class.simpleName ?: "unknown error",
        )
    }

    configureSerialization()
    configureMonitoring()
    configureHttp()
    configureSecurity(appConfig, jwtService)
    configureRouting(authService, readService, mutationService, newsService)
}
