package com.swiftshop.domain.profile

import com.swiftshop.core.model.Achievement
import com.swiftshop.core.model.UserProfile
import kotlinx.coroutines.flow.Flow

interface ProfileRepository {
    fun observeProfile(uid: String): Flow<UserProfile>
    suspend fun updateProfile(profile: UserProfile): Result<Unit>
    suspend fun followUser(targetUid: String): Result<Unit>
    suspend fun unfollowUser(targetUid: String): Result<Unit>
    suspend fun isFollowing(targetUid: String): Boolean
    fun observeFollowers(uid: String): Flow<List<String>>
    fun observeFollowing(uid: String): Flow<List<String>>
    suspend fun reportUser(uid: String, reason: String): Result<Unit>
    suspend fun blockUser(uid: String): Result<Unit>
    fun getAchievements(uid: String): Flow<List<Achievement>>
}
