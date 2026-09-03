package com.swiftshop.feature.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.model.*
import com.swiftshop.domain.auth.ObserveCurrentUserUseCase
import com.swiftshop.domain.auth.SignOutUseCase
import com.swiftshop.domain.wallet.ObserveWalletUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ProfileUiState {
    data object Loading : ProfileUiState
    data class Success(
        val user: User,
        val profile: UserProfile,
        val shops: List<Shop>,
        val recentListings: List<Listing>,
        val recentPosts: List<FeedPost>,
        val isOwnProfile: Boolean
    ) : ProfileUiState
    data class Error(val message: String) : ProfileUiState
}

sealed interface WalletUiState {
    data object Loading : WalletUiState
    data class Loaded(val wallet: Wallet) : WalletUiState
    data class Error(val message: String) : WalletUiState
}

sealed interface ActionState {
    data object Idle : ActionState
    data object Loading : ActionState
    data class Success(val message: String) : ActionState
    data class Error(val message: String) : ActionState
}

@HiltViewModel
class ProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val observeCurrentUser: ObserveCurrentUserUseCase,
    private val observeProfile: com.swiftshop.domain.profile.ObserveProfileUseCase,
    private val followUser: com.swiftshop.domain.profile.FollowUserUseCase,
    private val unfollowUser: com.swiftshop.domain.profile.UnfollowUserUseCase,
    private val profileRepository: com.swiftshop.domain.profile.ProfileRepository,
    private val observeWallet: ObserveWalletUseCase,
    private val commerceRepository: com.swiftshop.domain.commerce.CommerceRepository,
    private val getUserListings: com.swiftshop.domain.commerce.GetUserListingsUseCase,
    private val getUserPosts: com.swiftshop.domain.feed.GetUserPostsUseCase,
    private val initiateSubscription: com.swiftshop.domain.commerce.InitiateSubscriptionUseCase,
    private val signOut: SignOutUseCase
) : ViewModel() {

    // uid from nav arg (null = own profile)
    private val targetUid: String? = savedStateHandle["uid"]

    private val _uiState = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    private val _walletState = MutableStateFlow<WalletUiState>(WalletUiState.Loading)
    val walletState: StateFlow<WalletUiState> = _walletState.asStateFlow()

    private val _actionState = MutableStateFlow<ActionState>(ActionState.Idle)
    val actionState: StateFlow<ActionState> = _actionState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            observeCurrentUser()
                .flatMapLatest { user ->
                    if (user == null || user.uid.isBlank()) {
                        flowOf(ProfileUiState.Error("User not signed in"))
                    } else {
                        val currentUser = user
                        val isOwnProfile = targetUid.isNullOrBlank() || targetUid == currentUser.uid
                        val uid = if (isOwnProfile) currentUser.uid else targetUid!!

                        observeProfile(uid).flatMapLatest { profile ->
                            getUserPosts(uid).map { posts ->
                                val shops = commerceRepository.getUserShops(uid).first()
                                val listings = getUserListings(uid).getOrDefault(emptyList())
                                val wallet = if (isOwnProfile)
                                    runCatching { WalletUiState.Loaded(observeWallet(uid).first()) as WalletUiState }
                                        .getOrElse { WalletUiState.Error(it.message ?: "Wallet error") }
                                else WalletUiState.Loading
                                val isFollowing = if (!isOwnProfile)
                                    profileRepository.observeFollowers(uid).first().contains(currentUser.uid)
                                else false

                                _walletState.value = wallet

                                val userForState = if (isOwnProfile) currentUser else User(
                                    uid = profile.uid,
                                    displayName = profile.displayName,
                                    photoUrl = profile.avatarUrl,
                                    tier = profile.tier
                                )
                                ProfileUiState.Success(
                                    user = userForState,
                                    profile = profile.copy(isFollowedByMe = isFollowing),
                                    shops = shops,
                                    recentListings = listings,
                                    recentPosts = posts,
                                    isOwnProfile = isOwnProfile
                                ) as ProfileUiState
                            }
                        }
                    }
                }.catch { e ->
                    _uiState.value = ProfileUiState.Error(e.message ?: "Unknown error")
                }.collect { state ->
                    _uiState.value = state
                }
        }
    }


    fun toggleFollow() {
        val state = _uiState.value as? ProfileUiState.Success ?: return
        if (state.isOwnProfile) return
        viewModelScope.launch {
            if (state.profile.isFollowedByMe) unfollowUser(state.profile.uid)
            else followUser(state.profile.uid)
        }
    }

    fun upgradeTier(targetTier: UserTier) {
        viewModelScope.launch {
            _actionState.value = ActionState.Loading
            initiateSubscription(targetTier).fold(
                onSuccess = { _actionState.value = ActionState.Success("Upgrade request received (ID: ${it.take(8)})") },
                onFailure = { _actionState.value = ActionState.Error(it.message ?: "Request failed") }
            )
        }
    }

    fun clearActionState() { _actionState.value = ActionState.Idle }

    fun signOut() { viewModelScope.launch { signOut.invoke() } }
}
