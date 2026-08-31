package com.swiftshop.domain.messaging

import com.swiftshop.core.model.Conversation
import com.swiftshop.core.model.Message
import kotlinx.coroutines.flow.Flow

class ObserveConversationsUseCase(private val repository: MessagingRepository) {
    operator fun invoke(userId: String): Flow<List<Conversation>> = repository.observeConversations(userId)
}

class ObserveMessagesUseCase(private val repository: MessagingRepository) {
    operator fun invoke(conversationId: String): Flow<List<Message>> = repository.observeMessages(conversationId)
}

class SendMessageUseCase(private val repository: MessagingRepository) {
    suspend operator fun invoke(conversationId: String, senderId: String, text: String): Result<String> =
        repository.sendMessage(conversationId, senderId, text)
}
