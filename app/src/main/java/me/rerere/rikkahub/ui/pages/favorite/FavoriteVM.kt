package me.rerere.rikkahub.ui.pages.favorite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.data.familymode.FamilyModeController
import me.rerere.rikkahub.data.favorite.NodeFavoriteAdapter
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.FavoriteType
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.service.FamilyChatScope
import kotlin.uuid.Uuid

data class NodeFavoriteListItem(
    val id: String,
    val refKey: String,
    val conversationId: Uuid,
    val nodeId: Uuid,
    val conversationTitle: String,
    val preview: String,
    val createdAt: Long,
)

/** 收藏预览的可见范围。 */
internal sealed interface FavoritePreviewScope {
    /** 标准/管理状态：不过滤。 */
    data object All : FavoritePreviewScope

    /** 仅这些对话可见；空集表示拒绝全部（fail-closed）。 */
    data class Allowed(val conversationIds: Set<Uuid>) : FavoritePreviewScope

    fun allows(conversationId: Uuid): Boolean = when (this) {
        All -> true
        is Allowed -> conversationId in conversationIds
    }
}

/** 由家人状态导出的收藏范围决策。 */
internal sealed interface FavoriteScopeDecision {
    data object All : FavoriteScopeDecision
    data object Deny : FavoriteScopeDecision
    data class Family(val assistantId: Uuid) : FavoriteScopeDecision
}

/**
 * 纯函数：由家人状态决定收藏预览范围。
 *
 * - 状态未就绪：拒绝全部，避免加载窗口 fail-open。
 * - 家人锁定/恢复：仅允许指定家庭助手；助手缺失时为 [FavoriteScopeDecision.Deny]，绝不放行全部。
 * - 标准/管理：不过滤。
 */
internal fun favoriteScopeDecision(
    isReady: Boolean,
    isFamilyScope: Boolean,
    familyAssistantId: Uuid?,
): FavoriteScopeDecision = when {
    !isReady -> FavoriteScopeDecision.Deny
    !isFamilyScope -> FavoriteScopeDecision.All
    familyAssistantId == null -> FavoriteScopeDecision.Deny
    else -> FavoriteScopeDecision.Family(familyAssistantId)
}

class FavoriteVM(
    private val favoriteRepository: FavoriteRepository,
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsStore,
    private val familyModeController: FamilyModeController,
) : ViewModel() {

    private val favoriteScope: Flow<FavoritePreviewScope> =
        combine(settingsStore.settingsFlow, familyModeController.state) { settings, state ->
            val familyAssistantId = if (state.isFamilyScope) {
                FamilyChatScope.effectiveAssistantId(state, settings)
            } else {
                null
            }
            favoriteScopeDecision(
                isReady = state.isReady,
                isFamilyScope = state.isFamilyScope,
                familyAssistantId = familyAssistantId,
            )
        }
            .distinctUntilChanged()
            .flatMapLatest { decision ->
                when (decision) {
                    FavoriteScopeDecision.All -> flowOf(FavoritePreviewScope.All)
                    FavoriteScopeDecision.Deny -> flowOf(FavoritePreviewScope.Allowed(emptySet()))
                    is FavoriteScopeDecision.Family ->
                        conversationRepo.getConversationsOfAssistant(decision.assistantId)
                            .map<List<Conversation>, FavoritePreviewScope> { conversations ->
                                FavoritePreviewScope.Allowed(conversations.mapTo(mutableSetOf()) { it.id })
                            }
                            // 成员集合解析前先拒绝，避免短暂显示外来收藏。
                            .onStart { emit(FavoritePreviewScope.Allowed(emptySet())) }
                }
            }

    val nodeFavorites = combine(
        favoriteRepository.listByType(FavoriteType.NODE),
        favoriteScope,
    ) { favorites, scope ->
        favorites.mapNotNull { entity ->
            val ref = NodeFavoriteAdapter.decodeRef(entity) ?: return@mapNotNull null
            // 在渲染标题/预览前先按归属过滤，避免暴露其他助手内容。
            if (!scope.allows(ref.conversationId)) return@mapNotNull null
            val meta = NodeFavoriteAdapter.decodeMeta(entity)

            NodeFavoriteListItem(
                id = entity.id,
                refKey = entity.refKey,
                conversationId = ref.conversationId,
                nodeId = ref.nodeId,
                conversationTitle = meta?.title.orEmpty(),
                preview = meta?.previewText ?: "",
                createdAt = entity.createdAt,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 执行时按最新状态判断某个对话是否对当前访问状态不可见。 */
    internal suspend fun isConversationDeniedNow(conversationId: Uuid): Boolean {
        val state = familyModeController.state.value
        if (!state.isReady) return true
        if (!state.isFamilyScope) return false
        val conversation = conversationRepo.getConversationById(conversationId) ?: return true
        return !FamilyChatScope.ownsConversation(state, conversation)
    }

    suspend fun removeFavorite(refKey: String): Boolean {
        val entity = favoriteRepository.getByRefKey(refKey) ?: return false
        val ref = NodeFavoriteAdapter.decodeRef(entity) ?: return false
        if (isConversationDeniedNow(ref.conversationId)) return false
        favoriteRepository.deleteByRefKey(refKey)
        return true
    }

    suspend fun getEntityByRefKey(refKey: String): FavoriteEntity? {
        val entity = favoriteRepository.getByRefKey(refKey) ?: return null
        val ref = NodeFavoriteAdapter.decodeRef(entity) ?: return null
        if (isConversationDeniedNow(ref.conversationId)) return null
        return entity
    }

    suspend fun restoreFavorite(entity: FavoriteEntity): Boolean {
        val ref = NodeFavoriteAdapter.decodeRef(entity) ?: return false
        if (isConversationDeniedNow(ref.conversationId)) return false
        favoriteRepository.upsert(entity)
        return true
    }
}
