package me.rerere.rikkahub.ui.pages.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.familymode.FamilyModeController
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.FamilyChatScope
import me.rerere.rikkahub.ui.hooks.readStringPreference
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import kotlin.uuid.Uuid

private const val SORT_ORDER_PREF_KEY = "search_page_sort_order"

enum class MessageSearchScope {
    CURRENT_ASSISTANT,
    ALL_ASSISTANTS,
}

private data class SearchRequest(
    val query: String,
    val sort: MessageSearchSort,
    val scope: MessageSearchScope,
    val assistantId: Uuid?,
    val debounce: Boolean,
)

/**
 * 纯函数：将请求的作用域/助手 ID 夹紧到当前访问状态。
 *
 * 家人锁定/恢复时，无论 UI 旧状态为何，都强制单助手范围并只使用解析出的家庭助手；
 * 助手缺失时返回 null（调用方会因此不发布结果）。
 */
internal fun resolveSearchRequest(
    requestedScope: MessageSearchScope,
    currentAssistantId: Uuid?,
    familyScope: Boolean,
    familyAssistantId: Uuid?,
): Pair<MessageSearchScope, Uuid?> = if (familyScope) {
    MessageSearchScope.CURRENT_ASSISTANT to familyAssistantId
} else when (requestedScope) {
    MessageSearchScope.CURRENT_ASSISTANT ->
        MessageSearchScope.CURRENT_ASSISTANT to currentAssistantId
    MessageSearchScope.ALL_ASSISTANTS ->
        MessageSearchScope.ALL_ASSISTANTS to null
}

class SearchVM(
    private val context: Application,
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsStore,
    private val familyModeController: FamilyModeController,
) : ViewModel() {
    private val searchRequests = Channel<SearchRequest>(Channel.CONFLATED)
    private var currentAssistantId: Uuid? = null
    private var searchGeneration = 0

    var searchQuery by mutableStateOf("")
        private set
    var searchScope by mutableStateOf(MessageSearchScope.CURRENT_ASSISTANT)
        private set
    var sortOrder by mutableStateOf(
        runCatching {
            MessageSearchSort.valueOf(
                context.readStringPreference(SORT_ORDER_PREF_KEY, MessageSearchSort.RELEVANCE.name)!!
            )
        }.getOrDefault(MessageSearchSort.RELEVANCE)
    )
        private set
    var results by mutableStateOf<List<MessageSearchResult>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var isRebuilding by mutableStateOf(false)
        private set
    var rebuildProgress by mutableStateOf(0 to 0)
        private set

    init {
        viewModelScope.launch {
            searchRequests.receiveAsFlow().collectLatest { request -> performSearch(request) }
        }
        viewModelScope.launch {
            combine(settingsStore.settingsFlow, familyModeController.state) { settings, state ->
                FamilyChatScope.effectiveAssistantId(state, settings)
            }
                .distinctUntilChanged()
                .collect { assistantId ->
                    currentAssistantId = assistantId
                    if (searchScope == MessageSearchScope.CURRENT_ASSISTANT) {
                        search()
                    }
                }
        }
        // 进入家人锁定/恢复时：强制单助手范围、清空陈旧结果并重跑查询，
        // 同时递增 generation 使在途结果失效。
        viewModelScope.launch {
            familyModeController.state
                .map { it.isFamilyScope }
                .distinctUntilChanged()
                .collect { familyScope ->
                    if (familyScope) {
                        searchGeneration++
                        if (searchScope != MessageSearchScope.CURRENT_ASSISTANT) {
                            searchScope = MessageSearchScope.CURRENT_ASSISTANT
                        }
                        results = emptyList()
                        isLoading = false
                        if (searchQuery.isNotBlank()) search()
                    }
                }
        }
    }

    fun onQueryChange(query: String) {
        searchQuery = query
        requestSearch(debounce = true)
    }

    fun onScopeChange(scope: MessageSearchScope) {
        // 家人锁定搜索范围固定为家庭助手，拒绝切换到全部助手。
        if (familyModeController.state.value.isFamilyScope &&
            scope == MessageSearchScope.ALL_ASSISTANTS
        ) return
        if (searchScope == scope) return
        searchScope = scope
        search()
    }

    fun onSortChange(sort: MessageSearchSort) {
        if (sortOrder == sort) return
        sortOrder = sort
        context.writeStringPreference(SORT_ORDER_PREF_KEY, sort.name)
        search()
    }

    fun search() {
        requestSearch()
    }

    private fun requestSearch(debounce: Boolean = false) {
        val state = familyModeController.state.value
        val (scope, assistantId) = resolveSearchRequest(
            requestedScope = searchScope,
            currentAssistantId = currentAssistantId,
            familyScope = state.isFamilyScope,
            familyAssistantId = FamilyChatScope.effectiveAssistantId(state, settingsStore.settingsFlow.value),
        )
        searchRequests.trySend(
            SearchRequest(
                query = searchQuery,
                sort = sortOrder,
                scope = scope,
                assistantId = assistantId,
                debounce = debounce,
            )
        )
    }

    fun rebuildIndex() {
        // 家人锁定状态收起索引维护入口并拒绝重建。
        if (familyModeController.state.value.isFamilyScope) return
        viewModelScope.launch {
            isRebuilding = true
            rebuildProgress = 0 to 0
            try {
                conversationRepo.rebuildAllIndexes { current, total ->
                    rebuildProgress = current to total
                }
            } finally {
                isRebuilding = false
            }
        }
    }

    private suspend fun performSearch(request: SearchRequest) {
        val generation = ++searchGeneration
        results = emptyList()
        if (request.query.isBlank() ||
            (request.scope == MessageSearchScope.CURRENT_ASSISTANT && request.assistantId == null)
        ) {
            return
        }
        isLoading = true
        try {
            if (request.debounce) delay(300L)
            val outcome = conversationRepo.searchMessages(request.query, request.sort, request.assistantId)
            // 过期（重新锁定/新查询）的在途结果不得再发布。
            if (generation != searchGeneration) return
            results = outcome
        } finally {
            if (generation == searchGeneration) isLoading = false
        }
    }
}
