package com.recipebook.server.features.common

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(val error: String)

class ApiException(
    val statusCode: HttpStatusCode,
    override val message: String,
) : RuntimeException(message)

fun badRequest(message: String): Nothing = throw ApiException(HttpStatusCode.BadRequest, message)
fun unauthorized(message: String = "Unauthorized"): Nothing = throw ApiException(HttpStatusCode.Unauthorized, message)
fun forbidden(message: String = "Forbidden"): Nothing = throw ApiException(HttpStatusCode.Forbidden, message)
fun notFound(message: String): Nothing = throw ApiException(HttpStatusCode.NotFound, message)
fun conflict(message: String): Nothing = throw ApiException(HttpStatusCode.Conflict, message)
fun serviceUnavailable(message: String = "Service unavailable"): Nothing =
    throw ApiException(HttpStatusCode.ServiceUnavailable, message)
