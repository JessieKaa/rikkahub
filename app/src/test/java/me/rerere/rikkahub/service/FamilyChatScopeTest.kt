package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.familymode.FamilyAccessLevel
import me.rerere.rikkahub.data.familymode.FamilyModeRecord
import me.rerere.rikkahub.data.familymode.FamilyModeState
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 家人模式聊天运行时纯策略测试。
 *
 * 覆盖：标准/管理状态保留配置能力；家人锁定/恢复状态固定助手来源、限定对话归属；
 * 以及 LOADING 不授予配置能力。
 */
class FamilyChatScopeTest {

    private val familyAssistantId = Uuid.random()
    private val otherAssistantId = Uuid.random()

    private fun state(
        level: FamilyAccessLevel,
        familyId: Uuid? = familyAssistantId,
    ): FamilyModeState = FamilyModeState(
        accessLevel = level,
        familyAssistantId = familyId,
        record = FamilyModeRecord(
            familyModeEnabled = level != FamilyAccessLevel.STANDARD,
            familyAssistantId = familyId,
        ),
    )

    @Test
    fun `standard and admin keep configuration access`() {
        assertTrue(FamilyChatScope.canEditConfiguration(state(FamilyAccessLevel.STANDARD)))
        assertTrue(FamilyChatScope.canEditConfiguration(state(FamilyAccessLevel.ADMIN_UNLOCKED)))
    }

    @Test
    fun `family and recovery lock configuration access`() {
        assertFalse(FamilyChatScope.canEditConfiguration(state(FamilyAccessLevel.FAMILY_LOCKED)))
        assertFalse(FamilyChatScope.canEditConfiguration(state(FamilyAccessLevel.RECOVERY_LOCKED)))
        assertFalse(FamilyChatScope.canEditConfiguration(state(FamilyAccessLevel.LOADING)))
    }

    @Test
    fun `family scope resolves fixed family assistant`() {
        val settings = Settings(assistantId = otherAssistantId)
        assertEquals(
            familyAssistantId,
            FamilyChatScope.effectiveAssistantId(state(FamilyAccessLevel.FAMILY_LOCKED), settings),
        )
        assertEquals(
            familyAssistantId,
            FamilyChatScope.effectiveAssistantId(state(FamilyAccessLevel.RECOVERY_LOCKED), settings),
        )
    }

    @Test
    fun `standard and admin use global assistant selection`() {
        val settings = Settings(assistantId = otherAssistantId)
        assertEquals(
            otherAssistantId,
            FamilyChatScope.effectiveAssistantId(state(FamilyAccessLevel.STANDARD), settings),
        )
        assertEquals(
            otherAssistantId,
            FamilyChatScope.effectiveAssistantId(state(FamilyAccessLevel.ADMIN_UNLOCKED), settings),
        )
    }

    @Test
    fun `ownership is restricted to family assistant when locked`() {
        val locked = state(FamilyAccessLevel.FAMILY_LOCKED)
        assertTrue(
            FamilyChatScope.ownsConversation(
                locked,
                Conversation.ofId(Uuid.random(), assistantId = familyAssistantId),
            )
        )
        assertFalse(
            FamilyChatScope.ownsConversation(
                locked,
                Conversation.ofId(Uuid.random(), assistantId = otherAssistantId),
            )
        )
    }

    @Test
    fun `standard ownership allows any assistant conversation`() {
        val standard = state(FamilyAccessLevel.STANDARD)
        assertTrue(
            FamilyChatScope.ownsConversation(
                standard,
                Conversation.ofId(Uuid.random(), assistantId = otherAssistantId),
            )
        )
    }

    @Test
    fun `missing family assistant in locked scope resolves null and denies ownership`() {
        val settings = Settings(assistantId = otherAssistantId)
        val locked = state(FamilyAccessLevel.FAMILY_LOCKED, familyId = null)
        assertEquals(null, FamilyChatScope.effectiveAssistantId(locked, settings))
        assertFalse(
            FamilyChatScope.ownsConversation(
                locked,
                Conversation.ofId(Uuid.random(), assistantId = familyAssistantId),
            )
        )
    }

    @Test
    fun `locked scope preserves owner-controlled conversation fields`() {
        val current = Conversation.ofId(
            id = Uuid.random(),
            assistantId = familyAssistantId,
            newConversation = true,
        ).copy(
            customSystemPrompt = "owner prompt",
            modeInjectionIds = setOf(Uuid.random()),
            lorebookIds = setOf(Uuid.random()),
            workspaceCwd = "/owner/workspace",
        )
        val stale = current.copy(
            assistantId = otherAssistantId,
            customSystemPrompt = "stale prompt",
            modeInjectionIds = emptySet(),
            lorebookIds = emptySet(),
            workspaceCwd = "/stale/workspace",
        )

        val merged = FamilyChatScope.preserveProtectedConversationFields(current, stale)

        assertEquals(current.assistantId, merged.assistantId)
        assertEquals(current.customSystemPrompt, merged.customSystemPrompt)
        assertEquals(current.modeInjectionIds, merged.modeInjectionIds)
        assertEquals(current.lorebookIds, merged.lorebookIds)
        assertEquals(current.workspaceCwd, merged.workspaceCwd)
        assertEquals(current.messageNodes, merged.messageNodes)
    }

    @Test
    fun `admitted assistant keeps original or denies missing family fallback`() {
        val current = Settings.dummy().getCurrentAssistant()
        assertEquals(
            current,
            FamilyChatScope.resolveAdmittedAssistant(current, current, familyScope = true),
        )
        assertEquals(
            null,
            FamilyChatScope.resolveAdmittedAssistant(null, current, familyScope = true),
        )
        assertEquals(
            current,
            FamilyChatScope.resolveAdmittedAssistant(null, current, familyScope = false),
        )
    }
}
