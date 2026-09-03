package com.swiftshop.core.media

import android.content.Context
import android.graphics.Bitmap
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
 * That's the 🔴 RED backend gap in SWIFT_PHASE9G1_BACKEND_CONTRACT_GAP_REGISTER.md
 * ("Media Transcoding Engine") — it belongs in Cloud Functions per D-006
 * (server-authoritative processing), not the client. This uploader hands off
 * the original asset and the backend gap register item is what's still open.
 */
interface MediaUploader {
    fun uploadImage(ownerId: String, localUri: Uri): Flow<MediaUploadProgress>
    fun uploadVideo(ownerId: String, localUri: Uri): Flow<MediaUploadProgress>
}

@Singleton
class FirebaseMediaUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: FirebaseStorage
) : MediaUploader {

    override fun uploadImage(ownerId: String, localUri: Uri): Flow<MediaUploadProgress> =
        upload(ownerId, localUri, MediaType.IMAGE)

    override fun uploadVideo(ownerId: String, localUri: Uri): Flow<MediaUploadProgress> =
        upload(ownerId, localUri, MediaType.VIDEO)

    private fun upload(ownerId: String, localUri: Uri, type: MediaType): Flow<MediaUploadProgress> = callbackFlow {
        val assetId = UUID.randomUUID().toString()
        val extension = if (type == MediaType.VIDEO) "mp4" else "jpg"
        val path = "media/$ownerId/$assetId.$extension"
        val ref: StorageReference = storage.reference.child(path)

        val thumbnailUrl = if (type == MediaType.VIDEO) {
            runCatching { generateAndUploadThumbnail(ownerId, assetId, localUri, ref.parent!!) }.getOrNull().orEmpty()
        } else ""

        val uploadTask = ref.putFile(localUri)

        uploadTask.addOnProgressListener { snapshot ->
            trySend(MediaUploadProgress.InProgress(snapshot.bytesTransferred, snapshot.totalByteCount))
        }
        uploadTask.addOnSuccessListener {
            // Resolve the download URL, then emit Complete off the callback chain.
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

    /** Extracts a frame at the 1s mark and uploads it alongside the video. Local-only work, no backend dependency. */
    private suspend fun generateAndUploadThumbnail(
        ownerId: String,
        assetId: String,
        videoUri: Uri,
        parentFolder: StorageReference
    ): String {
        val retriever = MediaMetadataRetriever()
        val bitmap: Bitmap? = try {
            retriever.setDataSource(context, videoUri)
            retriever.getFrameAtTime(1_000_000L) // 1 second, in microseconds
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
}
