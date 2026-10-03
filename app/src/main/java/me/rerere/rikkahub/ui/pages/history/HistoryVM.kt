package me.rerere.rikkahub.ui.pages.history

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.familymode.EffectiveAssistantResolver
import me.rerere.rikkahub.data.familymode.FamilyModeController
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.FamilyChatScope
import kotlin.uuid.Uuid

private const val TAG = "HistoryVM"

class HistoryVM(
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsStore,
    private val chatService: ChatService,
    private val familyModeController: FamilyModeController,
) : ViewModel() {
    val assistant = combine(settingsStore.settingsFlow, familyModeController.state) { settings, state ->
        EffectiveAssistantResolver.effectiveAssistant(state.record, state.accessLevel, settings)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val conversations = assistant.flatMapLatest { assistant ->
        conversationRepo.getConversationsOfAssistant(assistant?.id ?: Uuid.random())
    }.catch {
        Log.e(TAG, "Error: ${it.message}")
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun deleteConversation(conversation: Conversation) {
        viewModelScope.launch {
            if (!FamilyChatScope.ownsConversation(familyModeController.state.value, conversation)) return@launch
            conversationRepo.deleteConversation(conversation)
        }
    }

    fun deleteAllConversations() {
        val assistant = assistant.value ?: return
        viewModelScope.launch {
            conversationRepo.deleteConversationOfAssistant(assistant.id)
        }
    }

    fun togglePinStatus(conversationId: Uuid) {
        viewModelScope.launch {
            chatService.toggleConversationPinned(conversationId)
        }
    }

    fun getPinnedConversations(): Flow<List<Conversation>> =
        combine(conversationRepo.getPinnedConversations(), familyModeController.state) { pinned, state ->
            pinned.filter { FamilyChatScope.ownsConversation(state, it) }
        }

    fun restoreConversation(conversation: Conversation) {
        viewModelScope.launch {
            if (!FamilyChatScope.ownsConversation(familyModeController.state.value, conversation)) return@launch
            conversationRepo.insertConversation(conversation)
        }
    }

    suspend fun getFullConversation(conversationId: Uuid): Conversation? {
        val state = familyModeController.state.value
        if (!state.isReady) return null
        val conversation = conversationRepo.getConversationById(conversationId) ?: return null
        // 最新状态下的归属校验；拒绝锁定后仍读取其他助手对话。
        if (!FamilyChatScope.ownsConversation(state, conversation)) return null
        return conversation
    }
}
