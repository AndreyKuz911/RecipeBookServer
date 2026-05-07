package com.recipebook.server

import com.recipebook.server.config.ConfigLoader
import com.recipebook.server.database.DatabaseFactory
import com.recipebook.server.database.SeedData
import com.recipebook.server.features.auth.AuthService
import com.recipebook.server.features.comments.CommentService
import com.recipebook.server.features.read.ReadService
import com.recipebook.server.features.recipes.RecipeService
import com.recipebook.server.features.users.UserService
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

fun Application.module() {
    val appConfig = ConfigLoader.load()
    val passwordHasher = PasswordHasher()
    val userService = UserService()
    val jwtService = JwtService(appConfig.jwt)
    val authService = AuthService(passwordHasher, jwtService, userService)
    val recipeService = RecipeService()
    val commentService = CommentService()
    val readService = ReadService()
    val mutationService = MutationService(readService)

    DatabaseFactory.init(appConfig)
    if (appConfig.autoCreateSchema && appConfig.seedOnStart) {
        SeedData.seedIfNeeded(passwordHasher)
    }

    configureSerialization()
    configureMonitoring()
    configureHttp()
    configureSecurity(appConfig, jwtService)
    configureRouting(authService, userService, recipeService, commentService, readService, mutationService)
}
