package com.swiftshop.domain.messaging

import com.swiftshop.core.model.Conversation
import com.swiftshop.core.model.Message
import kotlinx.coroutines.flow.Flow

interface MessagingRepository {
    fun observeConversations(userId: String): Flow<List<Conversation>>
    fun observeMessages(conversationId: String): Flow<List<Message>>
    suspend fun sendMessage(conversationId: String, senderId: String, text: String): Result<String>
    suspend fun markConversationRead(conversationId: String, userId: String): Result<Unit>
    suspend fun getOrCreateConversation(participantIds: List<String>): Result<String>
    suspend fun deleteMessage(messageId: String): Result<Unit>
}
