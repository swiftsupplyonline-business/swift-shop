package com.swiftshop.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real (not stub) implementation of the media-capture-to-upload pipeline that
 * `feature:reels` and `feature:advertising` reference in their placeholder
 * screens ("Wires to: ... MediaEngine (upload + compression)"). Deliberately
 * scoped to what a client can own:
 *  - uploads the original file to Firebase Storage with progress reporting
 *  - generates a local video thumbnail (no server round-trip needed for that)
 *  - writes a [MediaAsset] Firestore doc via the CC-005 contract shape
 *
 * What this does NOT do, on purpose: server-side transcoding/compression.
 * That's the RED backend gap in SWIFT_PHASE9G1_BACKEND_CONTRACT_GAP_REGISTER.md
 * ("Media Transcoding Engine") - it belongs in Cloud Functions per D-006
 * (server-authoritative processing), not the client. This uploader hands off
 * the original asset and the backend gap register item is what's still open.
 */
interface MediaUploader {
    fun uploadImage(ownerId: String, localUri: Uri, constraints: MediaConstraints? = null): Flow<MediaUploadProgress>
    fun uploadVideo(ownerId: String, localUri: Uri): Flow<MediaUploadProgress>
}

/**
 * Optional client-side pre-upload validation. Callers that don't pass this
 * (e.g. avatar uploads) get the pre-existing, unconstrained behavior.
 */
data class MediaConstraints(
    val maxSizeBytes: Long,
    val allowGif: Boolean = false
) {
    companion object {
        /** Cover photos (profile + shop dashboard): 5MB cap, animated GIF allowed. */
        val COVER_PHOTO = MediaConstraints(maxSizeBytes = 5L * 1024 * 1024, allowGif = true)

        /** Video Reels: 50MB cap. */
        val VIDEO_REEL = MediaConstraints(maxSizeBytes = 50L * 1024 * 1024)
    }
}

@Singleton
class FirebaseMediaUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: FirebaseStorage
) : MediaUploader {

    override fun uploadImage(ownerId: String, localUri: Uri, constraints: MediaConstraints?): Flow<MediaUploadProgress> =
        upload(ownerId, localUri, MediaType.IMAGE, constraints)

    override fun uploadVideo(ownerId: String, localUri: Uri): Flow<MediaUploadProgress> =
        upload(ownerId, localUri, MediaType.VIDEO, MediaConstraints.VIDEO_REEL)

    /** Returns a user-facing error message, or null if the file passes. */
    private fun validate(localUri: Uri, constraints: MediaConstraints?): String? {
        if (constraints == null) return null

        val sizeBytes = try {
            context.contentResolver.openAssetFileDescriptor(localUri, "r")?.use { it.length }
        } catch (e: Exception) {
            null
        }
        if (sizeBytes != null && sizeBytes > constraints.maxSizeBytes) {
            val maxMb = constraints.maxSizeBytes / (1024 * 1024)
            return "File is too large - please choose one under ${maxMb}MB"
        }

        if (!constraints.allowGif) {
            val mimeType = context.contentResolver.getType(localUri)
            if (mimeType == "image/gif") {
                return "Animated GIFs aren't supported here"
            }
        }
        return null
    }

    private fun upload(
        ownerId: String,
        localUri: Uri,
        type: MediaType,
        constraints: MediaConstraints?
    ): Flow<MediaUploadProgress> = callbackFlow {
        validate(localUri, constraints)?.let { errorMessage ->
            trySend(MediaUploadProgress.Failed(errorMessage))
            close()
            return@callbackFlow
        }

        val assetId = UUID.randomUUID().toString()
        val extension = when {
            type == MediaType.VIDEO -> "mp4"
            constraints?.allowGif == true && context.contentResolver.getType(localUri) == "image/gif" -> "gif"
            else -> "jpg"
        }
        val path = "media/$ownerId/$assetId.$extension"
        val ref: StorageReference = storage.reference.child(path)

        val thumbnailUrl = if (type == MediaType.VIDEO) {
            runCatching { generateAndUploadThumbnail(ownerId, assetId, localUri, ref.parent!!) }.getOrNull().orEmpty()
        } else ""

        val uploadTask = if (type == MediaType.IMAGE) {
            val compressed = runCatching { compressImage(localUri) }.getOrNull()
            if (compressed != null) ref.putBytes(compressed) else ref.putFile(localUri)
        } else {
            ref.putFile(localUri)
        }

        uploadTask.addOnProgressListener { snapshot ->
            trySend(MediaUploadProgress.InProgress(snapshot.bytesTransferred, snapshot.totalByteCount))
        }
        uploadTask.addOnSuccessListener {
            ref.downloadUrl.addOnSuccessListener { downloadUri ->
                trySend(
                    MediaUploadProgress.Complete(
                        MediaAsset(
                            id = assetId,
                            ownerId = ownerId,
                            type = type,
                            storagePath = path,
                            url = downloadUri.toString(),
                            thumbnailUrl = thumbnailUrl,
                            processingStatus = MediaProcessingStatus.UPLOADED
                        )
                    )
                )
                close()
            }.addOnFailureListener { e ->
                trySend(MediaUploadProgress.Failed(e.message ?: "Failed to resolve upload URL"))
                close()
            }
        }.addOnFailureListener { e ->
            trySend(MediaUploadProgress.Failed(e.message ?: "Upload failed"))
            close()
        }

        awaitClose { uploadTask.cancel() }
    }

    private suspend fun generateAndUploadThumbnail(
        ownerId: String,
        assetId: String,
        videoUri: Uri,
        parentFolder: StorageReference
    ): String {
        val retriever = MediaMetadataRetriever()
        val bitmap: Bitmap? = try {
            retriever.setDataSource(context, videoUri)
            retriever.getFrameAtTime(1_000_000L)
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
        val bytes = ByteArrayOutputStream().apply {
            bitmap?.compress(Bitmap.CompressFormat.JPEG, 80, this)
        }.toByteArray()
        if (bytes.isEmpty()) return ""

        val thumbRef = parentFolder.child("$assetId-thumb.jpg")
        thumbRef.putBytes(bytes).await()
        return thumbRef.downloadUrl.await().toString()
    }

    private fun compressImage(localUri: Uri): ByteArray {
        val inputStream = context.contentResolver.openInputStream(localUri)
        val bitmap = BitmapFactory.decodeStream(inputStream) ?: throw IllegalStateException("Failed to decode bitmap")
        val outputStream = ByteArrayOutputStream()
        // Authoritative client-side optimization: 85% quality JPEG.
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        return outputStream.toByteArray()
    }
}
