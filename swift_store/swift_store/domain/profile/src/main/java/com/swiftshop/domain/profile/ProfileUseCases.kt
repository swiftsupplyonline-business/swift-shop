package com.swiftshop.domain.profile

import com.swiftshop.core.model.UserProfile
import kotlinx.coroutines.flow.Flow

class ObserveProfileUseCase(private val repository: ProfileRepository) {
    operator fun invoke(uid: String): Flow<UserProfile> = repository.observeProfile(uid)
}

class UpdateProfileUseCase(private val repository: ProfileRepository) {
    suspend operator fun invoke(profile: UserProfile): Result<Unit> = repository.updateProfile(profile)
}

class FollowUserUseCase(private val repository: ProfileRepository) {
    suspend operator fun invoke(targetUid: String): Result<Unit> = repository.followUser(targetUid)
}

class UnfollowUserUseCase(private val repository: ProfileRepository) {
    suspend operator fun invoke(targetUid: String): Result<Unit> = repository.unfollowUser(targetUid)
}


class IsFollowingUseCase(private val repository: ProfileRepository) {
    suspend operator fun invoke(targetUid: String): Boolean = repository.isFollowing(targetUid)
}
