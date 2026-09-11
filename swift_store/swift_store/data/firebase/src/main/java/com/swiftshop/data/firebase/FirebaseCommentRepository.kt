package com.swiftshop.data.firebase

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.swiftshop.core.model.Comment
import com.swiftshop.core.model.UserProfile
import com.swiftshop.domain.feed.CommentRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseCommentRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) : CommentRepository {

    override fun getRootComments(postId: String): Flow<List<Comment>> = 
        firestore.collection("comments")
            .whereEqualTo("postId", postId)
            .whereEqualTo("parentCommentId", "")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .snapshots()
            .map { snapshot ->
                val comments = snapshot.toObjects(FirestoreCommentDto::class.java).map { it.toDomain() }
                joinProfiles(comments)
            }

    override fun getReplies(postId: String, parentCommentId: String): Flow<List<Comment>> =
        firestore.collection("comments")
            .whereEqualTo("postId", postId)
            .whereEqualTo("parentCommentId", parentCommentId)
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .snapshots()
            .map { snapshot ->
                val comments = snapshot.toObjects(FirestoreCommentDto::class.java).map { it.toDomain() }
                joinProfiles(comments)
            }

    override suspend fun addComment(comment: Comment): Result<String> = runCatching {
        val docRef = firestore.collection("comments").document()
        val finalComment = comment.copy(id = docRef.id)
        
        firestore.runTransaction { transaction ->
            // Add comment
            transaction.set(docRef, finalComment.toFirestore())
            
            // Increment comment count on post
            val postRef = firestore.collection("posts").document(comment.postId)
            transaction.update(postRef, "commentCount", com.google.firebase.firestore.FieldValue.increment(1))
            
            // Increment reply count on parent if it's a reply
            if (comment.parentCommentId.isNotBlank()) {
                val parentRef = firestore.collection("comments").document(comment.parentCommentId)
                transaction.update(parentRef, "replyCount", com.google.firebase.firestore.FieldValue.increment(1))
            }
        }.await()
        
        docRef.id
    }

    override suspend fun deleteComment(commentId: String, postId: String, parentId: String?): Result<Unit> = runCatching {
        firestore.runTransaction { transaction ->
            transaction.delete(firestore.collection("comments").document(commentId))
            
            // Decrement comment count on post
            val postRef = firestore.collection("posts").document(postId)
            transaction.update(postRef, "commentCount", com.google.firebase.firestore.FieldValue.increment(-1))
            
            // Decrement reply count on parent if it's a reply
            if (!parentId.isNullOrBlank()) {
                val parentRef = firestore.collection("comments").document(parentId)
                transaction.update(parentRef, "replyCount", com.google.firebase.firestore.FieldValue.increment(-1))
            }
        }.await()
    }

    private suspend fun joinProfiles(comments: List<Comment>): List<Comment> {
        if (comments.isEmpty()) return comments
        val uids = comments.map { it.authorId }.toSet()
        val profiles = fetchProfilesMap(uids)
        return comments.map { comment ->
            profiles[comment.authorId]?.let { profile ->
                comment.copy(
                    authorName = profile.displayName,
                    authorAvatarUrl = profile.avatarUrl
                )
            } ?: comment
        }
    }

    private suspend fun fetchProfilesMap(uids: Set<String>): Map<String, UserProfile> {
        if (uids.isEmpty()) return emptyMap()
        return uids.chunked(30).flatMap { chunk ->
            val snapshots = firestore.collection("profiles")
                .whereIn(com.google.firebase.firestore.FieldPath.documentId(), chunk.toList())
                .get()
                .await()
            snapshots.documents.mapNotNull { doc ->
                doc.toObject(FirestoreUserProfile::class.java)?.toDomain(doc.id)
            }
        }.associateBy { it.uid }
    }

    private fun Query.snapshots(): Flow<com.google.firebase.firestore.QuerySnapshot> = callbackFlow {
        val subscription = addSnapshotListener { snapshot, _ ->
            if (snapshot != null) trySend(snapshot)
        }
        awaitClose { subscription.remove() }
    }
}

data class FirestoreCommentDto(
    val id: String = "",
    val postId: String = "",
    val authorId: String = "",
    val text: String = "",
    val parentCommentId: String = "",
    val replyCount: Int = 0,
    val likeCount: Int = 0,
    val createdAt: Any? = null
) {
    fun toDomain() = Comment(id, postId, authorId, "", "", text, parentCommentId, replyCount, likeCount, tsToLong(createdAt))
}

fun Comment.toFirestore() = mapOf(
    "id" to id,
    "postId" to postId,
    "authorId" to authorId,
    "text" to text,
    "parentCommentId" to parentCommentId,
    "replyCount" to replyCount,
    "likeCount" to likeCount,
    "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
)
