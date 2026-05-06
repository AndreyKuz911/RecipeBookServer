package com.recipebook.server.routing

import com.recipebook.server.features.auth.AuthService
import com.recipebook.server.features.auth.LoginRequest
import com.recipebook.server.features.auth.RegisterRequest
import com.recipebook.server.features.comments.CommentService
import com.recipebook.server.features.comments.CreateCommentRequest
import com.recipebook.server.features.common.badRequest
import com.recipebook.server.features.recipes.RatingRequest
import com.recipebook.server.features.recipes.RecipeService
import com.recipebook.server.features.recipes.RecipeUpsertRequest
import com.recipebook.server.features.users.UpdateProfileRequest
import com.recipebook.server.features.users.UserService
import com.recipebook.server.security.UserPrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.http.content.staticFiles
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.cio.writeChannel
import io.ktor.utils.io.copyAndClose
import java.io.File
import java.util.UUID

fun Application.configureRouting(
    authService: AuthService,
    userService: UserService,
    recipeService: RecipeService,
    commentService: CommentService,
) {
    routing {
        staticFiles("/uploads", File("uploads"))

        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }

        route("/auth") {
            post("/register") {
                val request = call.receive<RegisterRequest>()
                call.respond(HttpStatusCode.Created, authService.register(request))
            }
            post("/login") {
                val request = call.receive<LoginRequest>()
                call.respond(authService.login(request))
            }
        }

        route("/recipes") {
            authenticate(optional = true) {
                get {
                    val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                    val query = call.request.queryParameters["query"]
                    val category = call.request.queryParameters["category"]
                    val timeRange = call.request.queryParameters["timeRange"]
                    val sort = call.request.queryParameters["sort"]
                    val currentUserId = call.principal<UserPrincipal>()?.userId

                    call.respond(
                        recipeService.listRecipes(
                            page = page,
                            limit = limit,
                            query = query,
                            category = category,
                            timeRange = timeRange,
                            sort = sort,
                            currentUserId = currentUserId,
                        ),
                    )
                }

                get("/{id}") {
                    val recipeId = call.uuidParam("id")
                    call.respond(recipeService.getRecipe(recipeId, call.principal<UserPrincipal>()?.userId))
                }

                get("/{id}/comments") {
                    val recipeId = call.uuidParam("id")
                    call.respond(commentService.listComments(recipeId))
                }
            }

            authenticate {
                post {
                    val userId = call.requirePrincipal().userId
                    val request = call.receive<RecipeUpsertRequest>()
                    call.respond(HttpStatusCode.Created, recipeService.createRecipe(userId, request))
                }
                put("/{id}") {
                    val userId = call.requirePrincipal().userId
                    val recipeId = call.uuidParam("id")
                    val request = call.receive<RecipeUpsertRequest>()
                    call.respond(recipeService.updateRecipe(recipeId, userId, request))
                }
                delete("/{id}") {
                    val userId = call.requirePrincipal().userId
                    val recipeId = call.uuidParam("id")
                    recipeService.deleteRecipe(recipeId, userId)
                    call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
                }
                post("/{id}/rating") {
                    val userId = call.requirePrincipal().userId
                    val recipeId = call.uuidParam("id")
                    val request = call.receive<RatingRequest>()
                    call.respond(recipeService.setRating(recipeId, userId, request.value))
                }
                delete("/{id}/rating") {
                    val userId = call.requirePrincipal().userId
                    val recipeId = call.uuidParam("id")
                    recipeService.removeRating(recipeId, userId)
                    call.respond(HttpStatusCode.OK, mapOf("status" to "removed"))
                }
                post("/{id}/favorite") {
                    val userId = call.requirePrincipal().userId
                    val recipeId = call.uuidParam("id")
                    call.respond(recipeService.addFavorite(recipeId, userId))
                }
                delete("/{id}/favorite") {
                    val userId = call.requirePrincipal().userId
                    val recipeId = call.uuidParam("id")
                    recipeService.removeFavorite(recipeId, userId)
                    call.respond(HttpStatusCode.OK, mapOf("status" to "removed"))
                }
                post("/{id}/comments") {
                    val userId = call.requirePrincipal().userId
                    val recipeId = call.uuidParam("id")
                    val request = call.receive<CreateCommentRequest>()
                    call.respond(HttpStatusCode.Created, commentService.createComment(recipeId, userId, request))
                }
            }
        }

        authenticate {
            route("/media") {
                post("/upload") {
                    val uploadDir = File("uploads").apply { mkdirs() }
                    var uploadedPath: String? = null
                    val multipart = call.receiveMultipart()
                    multipart.forEachPart { part ->
                        when (part) {
                            is PartData.FileItem -> {
                                val originalName = part.originalFileName.orEmpty()
                                val extension = originalName.substringAfterLast('.', "")
                                    .lowercase()
                                    .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
                                    ?.let { ".$it" }
                                    ?: ".jpg"
                                val fileName = "${UUID.randomUUID()}$extension"
                                val targetFile = File(uploadDir, fileName)
                                part.provider().copyAndClose(targetFile.writeChannel())
                                uploadedPath = "/uploads/$fileName"
                            }
                            else -> Unit
                        }
                        part.dispose()
                    }

                    val path = uploadedPath ?: badRequest("File is required")
                    call.respond(mapOf("url" to path))
                }
            }

            route("/users") {
                get("/me") {
                    call.respond(userService.getCurrentProfile(call.requirePrincipal().userId))
                }
                put("/me") {
                    val request = call.receive<UpdateProfileRequest>()
                    call.respond(userService.updateProfile(call.requirePrincipal().userId, request))
                }
                get("/{id}") {
                    val targetId = call.uuidParam("id")
                    call.respond(userService.getProfile(targetId, call.requirePrincipal().userId))
                }
                get("/{id}/recipes") {
                    val targetId = call.uuidParam("id")
                    call.respond(recipeService.listRecipesByAuthor(targetId, call.requirePrincipal().userId))
                }
                post("/{id}/follow") {
                    val targetId = call.uuidParam("id")
                    userService.follow(call.requirePrincipal().userId, targetId)
                    call.respond(HttpStatusCode.OK, mapOf("status" to "followed"))
                }
                delete("/{id}/follow") {
                    val targetId = call.uuidParam("id")
                    userService.unfollow(call.requirePrincipal().userId, targetId)
                    call.respond(HttpStatusCode.OK, mapOf("status" to "unfollowed"))
                }
            }

            get("/feed") {
                call.respond(recipeService.listFeed(call.requirePrincipal().userId))
            }

            get("/favorites") {
                call.respond(recipeService.listFavorites(call.requirePrincipal().userId))
            }

            delete("/comments/{id}") {
                val commentId = call.uuidParam("id")
                commentService.deleteComment(commentId, call.requirePrincipal().userId)
                call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.requirePrincipal(): UserPrincipal {
    return principal<UserPrincipal>() ?: badRequest("Missing auth principal")
}

private fun io.ktor.server.application.ApplicationCall.uuidParam(name: String): UUID {
    return parameters[name]?.let {
        runCatching { UUID.fromString(it) }.getOrElse { _ -> badRequest("Invalid $name") }
    } ?: badRequest("Missing $name")
}
