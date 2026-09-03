package com.swiftshop.core.media

/**
 * Canonical media contract for the Swift ecosystem.
 *
 * This closes CC-005 in SWIFT_PHASE9G1_CONTRACT_CONFLICT_REGISTER.md ("No
 * platform-wide asset tracking or metadata... Define MediaAsset model with
 * processing/moderation status"). Until now, Shop stored raw `mediaUrls:
 * List<String>` with no shape describing type, processing state, or
 * moderation — this is the missing shared shape. Swift Admin should adopt
 * the same field names when it implements its side of this contract.
 */
data class MediaAsset(
    val id: String = "",
    val ownerId: String = "",
    val type: MediaType = MediaType.IMAGE,
    val storagePath: String = "",       // Firebase Storage path, e.g. "media/{ownerId}/{id}.jpg"
    val url: String = "",               // Resolved download URL once uploaded
    val thumbnailUrl: String = "",       // Populated for VIDEO assets
    val processingStatus: MediaProcessingStatus = MediaProcessingStatus.PENDING,
    val moderationStatus: MediaModerationStatus = MediaModerationStatus.PENDING,
    val sizeBytes: Long = 0L,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val durationMs: Long = 0L,          // VIDEO only
    val createdAt: Long = System.currentTimeMillis()
)

enum class MediaType { IMAGE, VIDEO }

enum class MediaProcessingStatus { PENDING, UPLOADING, UPLOADED, FAILED }

/** Mirrors the moderation surface Swift Admin needs per the Media capability matrix. */
enum class MediaModerationStatus { PENDING, APPROVED, REJECTED }

/** Emitted while an upload is in-flight so screens can render a progress bar. */
sealed class MediaUploadProgress {
    data class InProgress(val bytesSent: Long, val totalBytes: Long) : MediaUploadProgress() {
        val percent: Int get() = if (totalBytes <= 0) 0 else ((bytesSent * 100) / totalBytes).toInt()
    }
    data class Complete(val asset: MediaAsset) : MediaUploadProgress()
    data class Failed(val message: String) : MediaUploadProgress()
}
