package com.swiftshop.data.firebase

import java.util.Date
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.swiftshop.core.database.DatabaseManager
import com.swiftshop.core.datastore.PreferenceManager
import com.swiftshop.core.model.User
import com.swiftshop.core.model.UserAccountStatus
import com.swiftshop.core.model.UserTier
import com.swiftshop.domain.auth.AuthRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseAuthRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val databaseManager: DatabaseManager,
    private val preferenceManager: PreferenceManager
) : AuthRepository {

    override val currentUser: Flow<User?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            val fbUser = firebaseAuth.currentUser
            if (fbUser == null) {
                trySend(null)
            } else {
                val fallbackUser = User(
                    uid = fbUser.uid,
                    email = fbUser.email ?: "",
                    displayName = fbUser.displayName ?: "",
                    photoUrl = fbUser.photoUrl?.toString() ?: ""
                )
                // Fetch full user profile from Firestore
                firestore.collection("users").document(fbUser.uid)
                    .get()
                    .addOnSuccessListener { doc ->
                        val user = doc.toObject(FirestoreUser::class.java)?.toDomain(fbUser.uid) ?: fallbackUser
                        trySend(user)
                    }
                    .addOnFailureListener { e ->
                        Timber.e(e, "Failed to fetch user profile, using fallback")
                        trySend(fallbackUser)
                    }
            }
        }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    override suspend fun signInWithEmail(email: String, password: String): Result<User> =
        runCatching {
            val result = auth.signInWithEmailAndPassword(email, password).await()
            val fbUser = result.user ?: throw IllegalStateException("Auth returned null user")
            getUserFromFirestore(fbUser.uid) ?: User(
                uid = fbUser.uid,
                email = fbUser.email ?: "",
                displayName = fbUser.displayName ?: ""
            )
        }

    override suspend fun signUpWithEmail(
        email: String, password: String, displayName: String
    ): Result<User> = runCatching {
        val result = auth.createUserWithEmailAndPassword(email, password).await()
        val fbUser = result.user ?: throw IllegalStateException("Auth returned null user")

        val user = User(
            uid = fbUser.uid,
            email = email,
            displayName = displayName,
            tier = UserTier.BASIC,
            accountStatus = UserAccountStatus.ACTIVE,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        // Write user document only. The wallet is already provisioned
        // server-side by the `provisionNewUser` beforeUserCreated Cloud
        // Function (functions/src/auth.ts) before this line ever runs — it
        // fires during account creation, ahead of this client code. Writing
        // /wallets/{uid} here too was not just architecturally wrong (client
        // must never write financial state), it was redundant with what the
        // backend already does, and since /wallets/{uid} is
        // `allow write: if false`, that redundant write denied the *entire*
        // batch — including this necessary /users/{uid} write. That was the
        // actual root cause of "Not signed in" after signup.
        firestore.collection("users").document(fbUser.uid)
            .set(user.toFirestore())
            .await()

        user
    }

    override suspend fun signInWithPhone(
        phoneNumber: String, verificationCode: String
    ): Result<User> = runCatching {
        // verificationCode here is "verificationId::smsCode"
        val parts = verificationCode.split("::")
        val credential = PhoneAuthProvider.getCredential(parts[0], parts[1])
        val result = auth.signInWithCredential(credential).await()
        val fbUser = result.user ?: throw IllegalStateException("Auth returned null user")
        getUserFromFirestore(fbUser.uid) ?: User(uid = fbUser.uid, phoneNumber = phoneNumber)
    }

    override suspend fun requestPhoneVerification(phoneNumber: String): Result<String> =
        Result.success("PHONE_VERIFICATION_REQUIRES_ACTIVITY_CONTEXT")
    // Real implementation requires Activity context — use PhoneAuthProvider.verifyPhoneNumber

    override suspend fun signOut(): Result<Unit> = runCatching {
        auth.signOut()
        // Invalidate user-scoped caches
        databaseManager.clearAllCaches()
        preferenceManager.clearAll()
    }

    override suspend fun sendPasswordResetEmail(email: String): Result<Unit> =
        runCatching { auth.sendPasswordResetEmail(email).await() }

    override suspend fun deleteAccount(): Result<Unit> = runCatching {
        auth.currentUser?.delete()?.await() ?: throw IllegalStateException("No current user")
    }

    override fun isSignedIn(): Boolean = auth.currentUser != null

    override suspend fun refreshSession(): Result<Unit> = runCatching {
        auth.currentUser?.getIdToken(true)?.await()
        Unit
    }

    private suspend fun getUserFromFirestore(uid: String): User? =
        runCatching {
            firestore.collection("users").document(uid).get().await()
                .toObject(FirestoreUser::class.java)?.toDomain(uid)
        }.getOrNull()
}

// ─── Firestore serialization helpers ─────────────────────────────────────────

data class FirestoreUser(

    val uid: String = "",
    val email: String = "",
    val phoneNumber: String = "",
    val displayName: String = "",
    val photoUrl: String = "",
    val tier: String = "BASIC",
    val isVerified: Boolean = false,
    val accountStatus: String = "ACTIVE",
    val createdAt: Any? = null,
    val updatedAt: Any? = null
) {
    fun toDomain(uid: String) = User(
        uid = uid,
        email = email,
        phoneNumber = phoneNumber,
        displayName = displayName,
        photoUrl = photoUrl,
        tier = runCatching { UserTier.valueOf(tier) }.getOrDefault(UserTier.BASIC),
        isVerified = isVerified,
        accountStatus = runCatching { UserAccountStatus.valueOf(accountStatus) }.getOrDefault(UserAccountStatus.ACTIVE),
        createdAt = tsToLong(createdAt),
        updatedAt = tsToLong(updatedAt)
    )
}


fun User.toFirestore() = mapOf(
    "uid" to uid,
    "email" to email,
    "phoneNumber" to phoneNumber,
    "displayName" to displayName,
    "photoUrl" to photoUrl,
    "tier" to tier.name,
    "isVerified" to isVerified,
    "accountStatus" to accountStatus.name,
    "createdAt" to createdAt,
    "updatedAt" to updatedAt
)
