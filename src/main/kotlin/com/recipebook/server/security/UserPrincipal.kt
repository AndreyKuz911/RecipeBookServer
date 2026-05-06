package com.recipebook.server.security

import io.ktor.server.auth.Principal
import java.util.UUID

data class UserPrincipal(
    val userId: UUID,
    val email: String,
) : Principal
