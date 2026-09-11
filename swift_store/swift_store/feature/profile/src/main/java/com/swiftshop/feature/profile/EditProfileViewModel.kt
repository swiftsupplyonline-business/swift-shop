package com.swiftshop.feature.profile

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.media.MediaUploadProgress
import com.swiftshop.core.media.MediaUploader
import com.swiftshop.core.model.UserProfile
import com.swiftshop.domain.auth.ObserveCurrentUserUseCase
import com.swiftshop.domain.profile.ObserveProfileUseCase
import com.swiftshop.domain.profile.UpdateProfileUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface EditProfileUiState {
    data object Loading : EditProfileUiState
    data class Success(val profile: UserProfile) : EditProfileUiState
    data class Error(val message: String) : EditProfileUiState
}

sealed interface EditProfileSaveState {
    data object Idle : EditProfileSaveState
    data object Saving : EditProfileSaveState
    data object Success : EditProfileSaveState
    data class Error(val message: String) : EditProfileSaveState
}

@HiltViewModel
class EditProfileViewModel @Inject constructor(
    private val observeCurrentUser: ObserveCurrentUserUseCase,
    private val observeProfile: ObserveProfileUseCase,
    private val updateProfile: UpdateProfileUseCase,
    private val mediaUploader: MediaUploader
) : ViewModel() {

    private val _uiState = MutableStateFlow<EditProfileUiState>(EditProfileUiState.Loading)
    val uiState: StateFlow<EditProfileUiState> = _uiState.asStateFlow()

    private val _saveState = MutableStateFlow<EditProfileSaveState>(EditProfileSaveState.Idle)
    val saveState: StateFlow<EditProfileSaveState> = _saveState.asStateFlow()

    private val _uploadProgress = MutableStateFlow<Int?>(null)
    val uploadProgress: StateFlow<Int?> = _uploadProgress.asStateFlow()

    private var currentProfile: UserProfile? = null

    // Form fields
    private val _displayName = MutableStateFlow("")
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    private val _bio = MutableStateFlow("")
    val bio: StateFlow<String> = _bio.asStateFlow()

    private val _location = MutableStateFlow("")
    val location: StateFlow<String> = _location.asStateFlow()

    private val _avatarUrl = MutableStateFlow("")
    val avatarUrl: StateFlow<String> = _avatarUrl.asStateFlow()

    private val _coverUrl = MutableStateFlow("")
    val coverUrl: StateFlow<String> = _coverUrl.asStateFlow()

    init {
        loadProfile()
    }

    private fun loadProfile() {
        viewModelScope.launch {
            observeCurrentUser()
                .filterNotNull()
                .flatMapLatest { user ->
                    if (user.uid.isBlank()) {
                        flowOf(EditProfileUiState.Error("User not authenticated"))
                    } else {
                        observeProfile(user.uid).map { profile ->
                            currentProfile = profile
                            _displayName.value = profile.displayName
                            _bio.value = profile.bio
                            _location.value = profile.location
                            _avatarUrl.value = profile.avatarUrl
                            _coverUrl.value = profile.coverUrl
                            EditProfileUiState.Success(profile)
                        }
                    }
                }
                .catch { e ->
                    _uiState.value = EditProfileUiState.Error(e.message ?: "Failed to load profile")
                }
                .collect { state ->
                    _uiState.value = state
                }
        }
    }

    fun onDisplayNameChange(name: String) { _displayName.value = name }
    fun onBioChange(bio: String) { _bio.value = bio }
    fun onLocationChange(loc: String) { _location.value = loc }

    fun onAvatarSelected(uri: Uri) {
        val uid = currentProfile?.uid ?: return
        if (uid.isBlank()) return

        viewModelScope.launch {
            mediaUploader.uploadImage(uid, uri).collect { progress ->
                when (progress) {
                    is MediaUploadProgress.InProgress -> {
                        _uploadProgress.value = progress.percent
                    }
                    is MediaUploadProgress.Complete -> {
                        _avatarUrl.value = progress.asset.url
                        _uploadProgress.value = null
                    }
                    is MediaUploadProgress.Failed -> {
                        _saveState.value = EditProfileSaveState.Error("Avatar upload failed: ${progress.message}")
                        _uploadProgress.value = null
                    }
                }
            }
        }
    }

    fun onCoverSelected(uri: Uri) {
        val uid = currentProfile?.uid ?: return
        if (uid.isBlank()) return

        viewModelScope.launch {
            mediaUploader.uploadImage(uid, uri).collect { progress ->
                when (progress) {
                    is MediaUploadProgress.InProgress -> {
                        _uploadProgress.value = progress.percent
                    }
                    is MediaUploadProgress.Complete -> {
                        _coverUrl.value = progress.asset.url
                        _uploadProgress.value = null
                    }
                    is MediaUploadProgress.Failed -> {
                        _saveState.value = EditProfileSaveState.Error("Cover upload failed: ${progress.message}")
                        _uploadProgress.value = null
                    }
                }
            }
        }
    }

    fun save() {
        val profile = currentProfile ?: return
        if (profile.uid.isBlank()) {
            _saveState.value = EditProfileSaveState.Error("Auth safety violation: blank UID")
            return
        }

        viewModelScope.launch {
            _saveState.value = EditProfileSaveState.Saving
            val updatedProfile = profile.copy(
                displayName = _displayName.value,
                bio = _bio.value,
                location = _location.value,
                avatarUrl = _avatarUrl.value,
                coverUrl = _coverUrl.value
            )

            updateProfile(updatedProfile).fold(
                onSuccess = { _saveState.value = EditProfileSaveState.Success },
                onFailure = { _saveState.value = EditProfileSaveState.Error(it.message ?: "Save failed") }
            )
        }
    }

    fun clearSaveState() { _saveState.value = EditProfileSaveState.Idle }
}
