package me.rerere.rikkahub.ui.pages.search

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 搜索范围夹紧的纯函数回归测试。
 *
 * 家人锁定/恢复时，无论请求的旧状态为何，都必须强制单助手并只使用家庭助手；
 * 家庭助手缺失时返回 null 助手（调用方不会发布结果）。
 */
class SearchScopeTest {

    private val assistantId = Uuid.random()

    @Test
    fun `family scope forces current assistant with family id`() {
        assertEquals(
            MessageSearchScope.CURRENT_ASSISTANT to assistantId,
            resolveSearchRequest(
                requestedScope = MessageSearchScope.ALL_ASSISTANTS,
                currentAssistantId = assistantId,
                familyScope = true,
                familyAssistantId = assistantId,
            ),
        )
    }

    @Test
    fun `family scope with missing assistant yields null assistant`() {
        assertEquals(
            MessageSearchScope.CURRENT_ASSISTANT to null,
            resolveSearchRequest(
                requestedScope = MessageSearchScope.ALL_ASSISTANTS,
                currentAssistantId = assistantId,
                familyScope = true,
                familyAssistantId = null,
            ),
        )
    }

    @Test
    fun `standard scope preserves requested scope`() {
        assertEquals(
            MessageSearchScope.ALL_ASSISTANTS to null,
            resolveSearchRequest(
                requestedScope = MessageSearchScope.ALL_ASSISTANTS,
                currentAssistantId = assistantId,
                familyScope = false,
                familyAssistantId = assistantId,
            ),
        )
        assertEquals(
            MessageSearchScope.CURRENT_ASSISTANT to assistantId,
            resolveSearchRequest(
                requestedScope = MessageSearchScope.CURRENT_ASSISTANT,
                currentAssistantId = assistantId,
                familyScope = false,
                familyAssistantId = null,
            ),
        )
    }
}
