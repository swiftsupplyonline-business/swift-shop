package com.swiftshop.domain.auth

import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthValidationTest {

    private val mockRepo = mockk<AuthRepository>(relaxed = true)

    @Test
    fun `sign up rejects blank email`() = runTest {
        val useCase = SignUpUseCase(mockRepo)
        val result = useCase("", "password123", "password123", "Name")
        assertTrue(result.isFailure)
        assertEquals("Email is required", result.exceptionOrNull()?.message)
    }

    @Test
    fun `sign up rejects short password`() = runTest {
        val useCase = SignUpUseCase(mockRepo)
        val result = useCase("test@test.com", "short", "short", "Name")
        assertTrue(result.isFailure)
    }

    @Test
    fun `sign up rejects password mismatch`() = runTest {
        val useCase = SignUpUseCase(mockRepo)
        val result = useCase("test@test.com", "password123", "different123", "Name")
        assertTrue(result.isFailure)
        assertEquals("Passwords do not match", result.exceptionOrNull()?.message)
    }

    @Test
    fun `sign up rejects blank name`() = runTest {
        val useCase = SignUpUseCase(mockRepo)
        val result = useCase("test@test.com", "password123", "password123", "")
        assertTrue(result.isFailure)
        assertEquals("Name is required", result.exceptionOrNull()?.message)
    }
}
