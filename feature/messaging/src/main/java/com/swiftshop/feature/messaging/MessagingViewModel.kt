package com.swiftshop.feature.messaging

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.model.Conversation
import com.swiftshop.core.model.Message
import com.swiftshop.core.model.UserProfile
import com.swiftshop.domain.auth.ObserveCurrentUserUseCase
import com.swiftshop.domain.messaging.MessagingRepository
import com.swiftshop.domain.profile.ObserveProfileUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConversationDisplay(
    val conversation: Conversation,
    val otherParticipant: UserProfile?
)

sealed interface ConversationsState {
    data object Loading : ConversationsState
    data object Empty : ConversationsState
    data class Loaded(val conversations: List<ConversationDisplay>) : ConversationsState
    data class Error(val message: String) : ConversationsState
}

sealed interface MessagesState {
    data object Loading : MessagesState
    data object Empty : MessagesState
    data class Loaded(val messages: List<Message>) : MessagesState
    data class Error(val message: String) : MessagesState
}

@HiltViewModel
class MessagingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val observeCurrentUser: ObserveCurrentUserUseCase,
    private val messagingRepository: MessagingRepository,
    private val observeProfile: ObserveProfileUseCase
) : ViewModel() {

    private val conversationId: String? = savedStateHandle["conversationId"]

    var currentUserId: String = ""
        private set

    private val _conversationsState = MutableStateFlow<ConversationsState>(ConversationsState.Loading)
    val conversationsState: StateFlow<ConversationsState> = _conversationsState.asStateFlow()

    private val _messagesState = MutableStateFlow<MessagesState>(MessagesState.Loading)
    val messagesState: StateFlow<MessagesState> = _messagesState.asStateFlow()

    init {
        viewModelScope.launch {
            observeCurrentUser().filterNotNull().collect { user ->
                currentUserId = user.uid
                loadConversations()
                if (conversationId != null) {
                    loadMessages(conversationId)
                }
            }
        }
    }

    fun loadConversations() {
        viewModelScope.launch {
            messagingRepository.observeConversations(currentUserId)
                .flatMapLatest { conversations ->
                    if (conversations.isEmpty()) {
                        flowOf(emptyList())
                    } else {
                        combine(
                            conversations.map { conv ->
                                val otherId = conv.participantIds.firstOrNull { it != currentUserId }
                                if (otherId == null) flowOf(ConversationDisplay(conv, null))
                                else observeProfile(otherId).map { ConversationDisplay(conv, it) }
                            }
                        ) { it.toList() }
                    }
                }
                .catch { _conversationsState.value = ConversationsState.Error(it.message ?: "Error") }
                .collect { list ->
                    _conversationsState.value = if (list.isEmpty()) ConversationsState.Empty
                    else ConversationsState.Loaded(list)
                }
        }
    }

    private fun loadMessages(convId: String) {
        viewModelScope.launch {
            messagingRepository.observeMessages(convId)
                .catch { _messagesState.value = MessagesState.Error(it.message ?: "Error") }
                .collect { msgs ->
                    _messagesState.value = if (msgs.isEmpty()) MessagesState.Empty
                    else MessagesState.Loaded(msgs)
                    messagingRepository.markConversationRead(convId, currentUserId)
                    if (msgs.any { it.senderId != currentUserId && !it.isRead }) {
                        messagingRepository.markMessagesRead(convId, currentUserId)
                    }
                }
        }
    }

    fun sendMessage(text: String) {
        val convId = conversationId ?: return
        viewModelScope.launch {
            messagingRepository.sendMessage(
                conversationId = convId,
                senderId = currentUserId,
                text = text
            )
        }
    }
}
