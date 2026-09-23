package com.swiftshop.data.firebase

import java.util.Date
import com.google.firebase.firestore.FirebaseFirestore
import com.swiftshop.core.model.Conversation
import com.swiftshop.core.model.Message
import com.swiftshop.domain.messaging.MessagingRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseMessagingRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) : MessagingRepository {

    override fun observeConversations(userId: String): Flow<List<Conversation>> = callbackFlow {
        val subscription = firestore.collection("conversations")
            .whereArrayContains("participantIds", userId)
            .orderBy("lastMessageAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, _ ->
                val list = snapshot?.toObjects(FirestoreConversation::class.java)?.map { it.toDomain() } ?: emptyList()
                trySend(list)
            }
        awaitClose { subscription.remove() }
    }

    override fun observeMessages(conversationId: String): Flow<List<Message>> = callbackFlow {
        val subscription = firestore.collection("messages")
            .whereEqualTo("conversationId", conversationId)
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, _ ->
                val list = snapshot?.toObjects(FirestoreMessage::class.java)?.map { it.toDomain() } ?: emptyList()
                trySend(list)
            }
        awaitClose { subscription.remove() }
    }

    override suspend fun sendMessage(conversationId: String, senderId: String, text: String): Result<String> = runCatching {
        val doc = firestore.collection("messages").document()
        val message = Message(
            id = doc.id,
            conversationId = conversationId,
            senderId = senderId,
            text = text,
            createdAt = System.currentTimeMillis()
        )

        val convRef = firestore.collection("conversations").document(conversationId)
        val convSnapshot = convRef.get().await()
        val participantIds = convSnapshot.get("participantIds") as? List<String> ?: emptyList()
        val recipientIds = participantIds.filter { it != senderId }

        val updates = mutableMapOf<String, Any>(
            "lastMessage" to text,
            "lastMessageAt" to message.createdAt
        )
        recipientIds.forEach { recipientId ->
            updates["unreadCounts.$recipientId"] = com.google.firebase.firestore.FieldValue.increment(1)
        }

        val batch = firestore.batch()
        batch.set(doc, message.toFirestore())
        batch.update(convRef, updates)
        batch.commit().await()
        doc.id
    }

    override suspend fun markConversationRead(conversationId: String, userId: String): Result<Unit> = runCatching {
        firestore.collection("conversations").document(conversationId)
            .update("unreadCounts.$userId", 0L)
            .await()
    }

    override suspend fun getOrCreateConversation(participantIds: List<String>): Result<String> = runCatching {
        // Deterministic ID for 1-on-1 chats to prevent duplicates
        val sortedIds = participantIds.sorted()
        val convId = sortedIds.joinToString("_")
        
        val doc = firestore.collection("conversations").document(convId)
        val snapshot = doc.get().await()
        
        if (!snapshot.exists()) {
            val conv = Conversation(convId, sortedIds, "", System.currentTimeMillis(), sortedIds.associateWith { 0 }, "")
            doc.set(conv.toFirestore()).await()
        }
        convId
    }

    override suspend fun markMessagesRead(conversationId: String, readerId: String): Result<Unit> = runCatching {
        val unreadDocs = firestore.collection("messages")
            .whereEqualTo("conversationId", conversationId)
            .whereEqualTo("isRead", false)
            .get().await()

        val toMark = unreadDocs.documents.filter { it.getString("senderId") != readerId }
        if (toMark.isEmpty()) return@runCatching

        val batch = firestore.batch()
        toMark.forEach { batch.update(it.reference, "isRead", true) }
        batch.commit().await()
    }

    override suspend fun deleteMessage(messageId: String): Result<Unit> = runCatching {
        firestore.collection("messages").document(messageId).delete().await()
    }
}

data class FirestoreConversation(
    val id: String = "",
    val participantIds: List<String> = emptyList(),
    val lastMessage: String = "",
    val lastMessageAt: Date = Date(0),
    val unreadCounts: Map<String, Long> = emptyMap(),
    val deliveryRouteId: String = ""
) {
    fun toDomain() = Conversation(
        id, participantIds, lastMessage, lastMessageAt.time,
        unreadCounts.mapValues { it.value.toInt() }, deliveryRouteId
    )
}

fun Conversation.toFirestore() = mapOf(
    "id" to id, "participantIds" to participantIds, "lastMessage" to lastMessage,
    "lastMessageAt" to lastMessageAt, "unreadCounts" to unreadCounts,
    "deliveryRouteId" to deliveryRouteId
)

data class FirestoreMessage(
    val id: String = "",
    val conversationId: String = "",
    val senderId: String = "",
    val text: String = "",
    val attachmentUrl: String = "",
    val isRead: Boolean = false,
    val createdAt: Date = Date(0),
    val isEncrypted: Boolean = false,
    val encryptedPayload: String? = null,
    val encryptionVersion: String? = null,
    val keyVersion: String? = null
) {
    fun toDomain() = Message(
        id, conversationId, senderId, text, attachmentUrl, isRead, createdAt.time,
        isEncrypted, encryptedPayload, encryptionVersion, keyVersion
    )
}

fun Message.toFirestore() = mapOf(
    "id" to id, "conversationId" to conversationId, "senderId" to senderId,
    "text" to text, "attachmentUrl" to attachmentUrl, "isRead" to isRead, "createdAt" to createdAt,
    "isEncrypted" to isEncrypted, "encryptedPayload" to encryptedPayload,
    "encryptionVersion" to encryptionVersion, "keyVersion" to keyVersion
)
