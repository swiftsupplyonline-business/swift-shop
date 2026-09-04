package com.swiftshop.data.firebase

import com.google.firebase.auth.FirebaseAuth
import java.util.Date
import com.google.firebase.firestore.FirebaseFirestore
import com.swiftshop.core.model.Achievement
import com.swiftshop.core.model.UserProfile
import com.swiftshop.core.model.UserTier
import com.swiftshop.domain.profile.ProfileRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseProfileRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) : ProfileRepository {

    override fun observeProfile(uid: String): Flow<UserProfile> = callbackFlow {
        if (uid.isBlank()) {
            trySend(UserProfile())
            awaitClose {}
            return@callbackFlow
        }
        val subscription = firestore.collection("profiles").document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val profile = snapshot?.toObject(FirestoreUserProfile::class.java)?.toDomain(uid)
                    ?: UserProfile(uid = uid)
                trySend(profile)
            }
        awaitClose { subscription.remove() }
    }

    override suspend fun updateProfile(profile: UserProfile): Result<Unit> = runCatching {
        val uid = profile.uid
        if (uid.isBlank()) throw IllegalArgumentException("UID cannot be blank")

        // 1. Coordinated Firestore Update (Atomic Batch)
        val batch = firestore.batch()
        
        val profileRef = firestore.collection("profiles").document(uid)
        val userRef = firestore.collection("users").document(uid)
        
        batch.update(profileRef, profile.toFirestoreUpdateMap())
        batch.update(userRef, mapOf(
            "displayName" to profile.displayName,
            "photoUrl" to profile.avatarUrl,
            "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
        ))
        
        batch.commit().await()

        // 2. Coordinated Firebase Auth Update (Best effort sync)
        auth.currentUser?.let { fbUser ->
            if (fbUser.uid == uid) {
                val profileUpdates = com.google.firebase.auth.userProfileChangeRequest {
                    displayName = profile.displayName
                    photoUri = android.net.Uri.parse(profile.avatarUrl)
                }
                fbUser.updateProfile(profileUpdates).await()
            }
        }
        Unit
    }

    override suspend fun followUser(targetUid: String): Result<Unit> = runCatching {
        val currentUid = auth.currentUser?.uid ?: throw IllegalStateException("Not signed in")
        val followId = "${currentUid}_$targetUid"
        firestore.collection("follows").document(followId)
            .set(mapOf(
                "followerId" to currentUid,
                "followedId" to targetUid,
                "createdAt" to System.currentTimeMillis()
            ))
            .await()
    }

    override suspend fun unfollowUser(targetUid: String): Result<Unit> = runCatching {
        val currentUid = auth.currentUser?.uid ?: throw IllegalStateException("Not signed in")
        val followId = "${currentUid}_$targetUid"
        firestore.collection("follows").document(followId)
            .delete()
            .await()
    }

    override suspend fun isFollowing(targetUid: String): Boolean {
        val currentUid = auth.currentUser?.uid ?: return false
        val followId = "${currentUid}_$targetUid"
        return firestore.collection("follows").document(followId).get().await().exists()
    }

    override fun observeFollowers(uid: String): Flow<List<String>> = callbackFlow {
        val subscription = firestore.collection("follows")
            .whereEqualTo("followedId", uid)
            .addSnapshotListener { snapshot, _ ->
                val followers = snapshot?.documents?.mapNotNull { it.getString("followerId") } ?: emptyList()
                trySend(followers)
            }
        awaitClose { subscription.remove() }
    }

    override fun observeFollowing(uid: String): Flow<List<String>> = callbackFlow {
        val subscription = firestore.collection("follows")
            .whereEqualTo("followerId", uid)
            .addSnapshotListener { snapshot, _ ->
                val following = snapshot?.documents?.mapNotNull { it.getString("followedId") } ?: emptyList()
                trySend(following)
            }
        awaitClose { subscription.remove() }
    }

    override suspend fun reportUser(uid: String, reason: String): Result<Unit> = runCatching {
        val currentUid = auth.currentUser?.uid ?: "anonymous"
        firestore.collection("reports").add(mapOf(
            "reporterId" to currentUid,
            "targetId" to uid,
            "targetType" to "USER",
            "reason" to reason,
            "status" to "OPEN",
            "createdAt" to System.currentTimeMillis()
        )).await()
        Unit
    }

    override suspend fun blockUser(uid: String): Result<Unit> = runCatching {
        // Blocks are stored in a subcollection of the user's profile for privacy/Rules
        val currentUid = auth.currentUser?.uid ?: throw IllegalStateException("Not signed in")
        firestore.collection("users").document(currentUid)
            .collection("blockedUsers").document(uid)
            .set(mapOf("blockedAt" to System.currentTimeMillis()))
            .await()
    }

    override fun getAchievements(uid: String): Flow<List<Achievement>> = callbackFlow {
        if (uid.isBlank()) { trySend(emptyList()); awaitClose {}; return@callbackFlow }
        // Achievements can be a subcollection or a list in profile. Assuming subcollection for scale.
        val subscription = firestore.collection("profiles").document(uid)
            .collection("achievements")
            .addSnapshotListener { snapshot, _ ->
                val list = snapshot?.toObjects(FirestoreAchievement::class.java)?.map { it.toDomain() } ?: emptyList()
                trySend(list)
            }
        awaitClose { subscription.remove() }
    }
}

// â”€â”€â”€ DTOs â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

data class FirestoreUserProfile(
    val displayName: String = "",
    val coverUrl: String = "",
    val avatarUrl: String = "",
    val bio: String = "",
    val location: String = "",
    val followerCount: Int = 0,
    val followingCount: Int = 0,
    val shopCount: Int = 0,
    val postCount: Int = 0,
    val activeListingCount: Int = 0,
    val reputationScore: Float = 0f,
    val tier: String = "BASIC",
    val totalDeliveries: Int = 0
) {
    fun toDomain(uid: String) = UserProfile(
        uid = uid,
        displayName = displayName,
        coverUrl = coverUrl,
        avatarUrl = avatarUrl,
        bio = bio,
        location = location,
        followerCount = followerCount,
        followingCount = followingCount,
        shopCount = shopCount,
        postCount = postCount,
        activeListingCount = activeListingCount,
        reputationScore = reputationScore,
        tier = runCatching { UserTier.valueOf(tier) }.getOrDefault(UserTier.BASIC),
        totalDeliveries = totalDeliveries
    )
}

fun UserProfile.toFirestore() = mapOf(
    "displayName" to displayName,
    "coverUrl" to coverUrl,
    "avatarUrl" to avatarUrl,
    "bio" to bio,
    "location" to location,
    "followerCount" to followerCount,
    "followingCount" to followingCount,
    "shopCount" to shopCount,
    "postCount" to postCount,
    "activeListingCount" to activeListingCount,
    "reputationScore" to reputationScore,
    "tier" to tier.name,
    "totalDeliveries" to totalDeliveries
)

fun UserProfile.toFirestoreUpdateMap() = mapOf(
    "displayName" to displayName,
    "bio" to bio,
    "location" to location,
    "avatarUrl" to avatarUrl,
    "coverUrl" to coverUrl
)

data class FirestoreAchievement(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val iconUrl: String = "",
    val earnedAt: Date = Date(0)
) {
    fun toDomain() = Achievement(id, title, description, iconUrl, earnedAt.time)
}


