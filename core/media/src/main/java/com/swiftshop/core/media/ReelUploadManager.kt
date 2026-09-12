package com.swiftshop.core.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * State coordinator for Reel uploads.
 * Bridges the gap between the Create screen and the Feed screen to preserve progress visibility.
 */
@Singleton
class ReelUploadManager @Inject constructor() {
    private val _uploadProgress = MutableStateFlow<MediaUploadProgress?>(null)
    val uploadProgress = _uploadProgress.asStateFlow()

    fun updateProgress(progress: MediaUploadProgress?) {
        _uploadProgress.value = progress
    }

    /**
     * Resets the upload progress to IDLE (null).
     * Should be called after terminal success, failure, or cancellation.
     */
    fun clear() {
        _uploadProgress.value = null
    }
}
