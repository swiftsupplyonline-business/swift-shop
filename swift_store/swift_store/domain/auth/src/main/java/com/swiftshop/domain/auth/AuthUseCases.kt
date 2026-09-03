package com.swiftshop.domain.auth

import com.swiftshop.core.model.User
import kotlinx.coroutines.flow.Flow

// ─── Repository Interface (implemented in data layer) ─────────────────────────

interface AuthRepository {
    val currentUser: Flow<User?>
    suspend fun signInWithEmail(email: String, password: String): Result<User>
    suspend fun signUpWithEmail(email: String, password: String, displayName: String): Result<User>
    suspend fun signInWithPhone(phoneNumber: String, verificationCode: String): Result<User>
    suspend fun requestPhoneVerification(phoneNumber: String): Result<String> // returns verificationId
    suspend fun signOut(): Result<Unit>
    suspend fun sendPasswordResetEmail(email: String): Result<Unit>
    suspend fun deleteAccount(): Result<Unit>
    fun isSignedIn(): Boolean
    suspend fun refreshSession(): Result<Unit>
}

// ─── Use Cases ────────────────────────────────────────────────────────────────

class SignInWithEmailUseCase(private val repository: AuthRepository) {
    suspend operator fun invoke(email: String, password: String): Result<User> {
        if (email.isBlank()) return Result.failure(IllegalArgumentException("Email is required"))
        if (password.length < 8) return Result.failure(IllegalArgumentException("Password too short"))
        return repository.signInWithEmail(email.trim().lowercase(), password)
    }
}

class SignUpUseCase(private val repository: AuthRepository) {
    suspend operator fun invoke(
        email: String, password: String,
        confirmPassword: String, displayName: String
    ): Result<User> {
        if (email.isBlank()) return Result.failure(IllegalArgumentException("Email is required"))
        if (displayName.isBlank()) return Result.failure(IllegalArgumentException("Name is required"))
        if (password.length < 8) return Result.failure(IllegalArgumentException("Password must be at least 8 characters"))
        if (password != confirmPassword) return Result.failure(IllegalArgumentException("Passwords do not match"))
        return repository.signUpWithEmail(email.trim().lowercase(), password, displayName.trim())
    }
}

class SignOutUseCase(private val repository: AuthRepository) {
    suspend operator fun invoke(): Result<Unit> = repository.signOut()
}

class ObserveCurrentUserUseCase(private val repository: AuthRepository) {
    operator fun invoke(): Flow<User?> = repository.currentUser
}
