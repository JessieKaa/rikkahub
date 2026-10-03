package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.familymode.FamilyAccessLevel
import me.rerere.rikkahub.data.familymode.FamilyModeRecord
import me.rerere.rikkahub.data.familymode.FamilyModeState
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class FamilyNotificationGuardTest {

    private fun state(level: FamilyAccessLevel, familyAssistantId: Uuid?): FamilyModeState =
        FamilyModeState(
            accessLevel = level,
            familyAssistantId = familyAssistantId,
            record = familyAssistantId?.let {
                FamilyModeRecord(familyModeEnabled = true, familyAssistantId = it)
            },
        )

    private fun conversation(assistantId: Uuid) =
        Conversation(id = Uuid.random(), assistantId = assistantId, messageNodes = emptyList())

    @Test
    fun `standard mode shows any conversation`() {
        val state = state(FamilyAccessLevel.STANDARD, null)
        assertTrue(FamilyNotificationGuard.canShowConversationContent(state, conversation(Uuid.random())))
        assertTrue(FamilyNotificationGuard.canShowConversationContent(state, null))
    }

    @Test
    fun `family locked shows owned conversation`() {
        val familyId = Uuid.random()
        val state = state(FamilyAccessLevel.FAMILY_LOCKED, familyId)
        assertTrue(FamilyNotificationGuard.canShowConversationContent(state, conversation(familyId)))
    }

    @Test
    fun `family locked hides foreign conversation`() {
        val state = state(FamilyAccessLevel.FAMILY_LOCKED, Uuid.random())
        assertFalse(FamilyNotificationGuard.canShowConversationContent(state, conversation(Uuid.random())))
    }

    @Test
    fun `family locked with no family assistant fails closed`() {
        val state = state(FamilyAccessLevel.FAMILY_LOCKED, familyAssistantId = null)
        assertFalse(FamilyNotificationGuard.canShowConversationContent(state, conversation(Uuid.random())))
        assertFalse(FamilyNotificationGuard.canShowConversationContent(state, null))
    }

    @Test
    fun `family locked hides unknown conversation`() {
        val state = state(FamilyAccessLevel.FAMILY_LOCKED, Uuid.random())
        assertFalse(FamilyNotificationGuard.canShowConversationContent(state, null))
    }

    @Test
    fun `recovery locked hides foreign conversation`() {
        val state = state(FamilyAccessLevel.RECOVERY_LOCKED, Uuid.random())
        assertFalse(FamilyNotificationGuard.canShowConversationContent(state, conversation(Uuid.random())))
    }

    @Test
    fun `raw tool input hidden only in family scope`() {
        assertTrue(FamilyNotificationGuard.hideRawToolInput(state(FamilyAccessLevel.FAMILY_LOCKED, Uuid.random())))
        assertTrue(FamilyNotificationGuard.hideRawToolInput(state(FamilyAccessLevel.RECOVERY_LOCKED, Uuid.random())))
        assertFalse(FamilyNotificationGuard.hideRawToolInput(state(FamilyAccessLevel.STANDARD, null)))
        assertFalse(FamilyNotificationGuard.hideRawToolInput(state(FamilyAccessLevel.ADMIN_UNLOCKED, Uuid.random())))
    }

    @Test
    fun `unknown live notification is cancelled without an active foreground task`() {
        assertEquals(
            FamilyUnknownNotificationAction.CANCEL,
            FamilyNotificationGuard.actionForUnknownLiveNotification(hasActiveForegroundTask = false),
        )
    }

    @Test
    fun `unknown live notification is replaced generic with an active foreground task`() {
        assertEquals(
            FamilyUnknownNotificationAction.REPLACE_GENERIC,
            FamilyNotificationGuard.actionForUnknownLiveNotification(hasActiveForegroundTask = true),
        )
    }
}
