package com.recipebook.server

import com.recipebook.server.features.auth.LoginRequest
import com.recipebook.server.features.auth.RegisterRequest
import com.recipebook.server.features.common.ApiException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AuthServiceTest {
    private lateinit var authService: com.recipebook.server.features.auth.AuthService

    @BeforeTest
    fun setUp() {
        prepareTestDatabase()
        authService = authServiceFixture()
    }

    @Test
    fun `successful registration returns token and user`() {
        val response = authService.register(
            RegisterRequest(
                email = "new@example.com",
                username = "new_user",
                password = "password123",
            ),
        )

        assertTrue(response.token.isNotBlank())
        assertEquals("new@example.com", response.user.email)
        assertEquals("new_user", response.user.username)
    }

    @Test
    fun `registration with existing email fails`() {
        authService.register(RegisterRequest("dup@example.com", "dup_user", "password123"))

        val error = assertFailsWith<ApiException> {
            authService.register(RegisterRequest("dup@example.com", "other_user", "password123"))
        }

        assertEquals("Email is already registered", error.message)
    }

    @Test
    fun `login with correct password succeeds`() {
        authService.register(RegisterRequest("chef@example.com", "chef_user", "password123"))

        val response = authService.login(LoginRequest("chef@example.com", "password123"))

        assertTrue(response.token.isNotBlank())
        assertEquals("chef@example.com", response.user.email)
    }

    @Test
    fun `login with wrong password fails`() {
        authService.register(RegisterRequest("chef@example.com", "chef_user", "password123"))

        val error = assertFailsWith<ApiException> {
            authService.login(LoginRequest("chef@example.com", "wrong-password"))
        }

        assertEquals("Invalid credentials", error.message)
    }
}
