package me.rerere.rikkahub.ui.pages.favorite

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 收藏预览范围决策的 fail-closed 回归测试。
 */
class FavoriteScopeDecisionTest {

    private val assistantId = Uuid.random()

    @Test
    fun `not ready denies all previews`() {
        assertEquals(
            FavoriteScopeDecision.Deny,
            favoriteScopeDecision(isReady = false, isFamilyScope = false, familyAssistantId = null),
        )
    }

    @Test
    fun `family scope with missing assistant denies all previews`() {
        assertEquals(
            FavoriteScopeDecision.Deny,
            favoriteScopeDecision(isReady = true, isFamilyScope = true, familyAssistantId = null),
        )
    }

    @Test
    fun `family scope with assistant restricts to family`() {
        assertEquals(
            FavoriteScopeDecision.Family(assistantId),
            favoriteScopeDecision(isReady = true, isFamilyScope = true, familyAssistantId = assistantId),
        )
    }

    @Test
    fun `standard scope allows all previews`() {
        assertEquals(
            FavoriteScopeDecision.All,
            favoriteScopeDecision(isReady = true, isFamilyScope = false, familyAssistantId = null),
        )
    }
}
