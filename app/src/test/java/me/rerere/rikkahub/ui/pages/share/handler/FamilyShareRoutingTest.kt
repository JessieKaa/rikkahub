package me.rerere.rikkahub.ui.pages.share.handler

import me.rerere.rikkahub.data.familymode.FamilyAccessLevel
import me.rerere.rikkahub.data.familymode.FamilyModeRecord
import me.rerere.rikkahub.data.familymode.FamilyModeState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class FamilyShareRoutingTest {

    private fun state(level: FamilyAccessLevel, familyAssistantId: Uuid? = Uuid.random()): FamilyModeState =
        FamilyModeState(
            accessLevel = level,
            familyAssistantId = familyAssistantId,
            record = familyAssistantId?.let {
                FamilyModeRecord(familyModeEnabled = true, familyAssistantId = it)
            },
        )

    @Test
    fun `family locked auto routes to the fixed family chat`() {
        assertTrue(FamilyShareRouting.shouldAutoRouteToFamilyChat(state(FamilyAccessLevel.FAMILY_LOCKED)))
    }

    @Test
    fun `family locked without a family assistant fails closed`() {
        assertFalse(
            FamilyShareRouting.shouldAutoRouteToFamilyChat(
                state(FamilyAccessLevel.FAMILY_LOCKED, familyAssistantId = null)
            )
        )
    }

    @Test
    fun `standard and admin do not auto route`() {
        assertFalse(FamilyShareRouting.shouldAutoRouteToFamilyChat(state(FamilyAccessLevel.STANDARD)))
        assertFalse(FamilyShareRouting.shouldAutoRouteToFamilyChat(state(FamilyAccessLevel.ADMIN_UNLOCKED)))
    }

    @Test
    fun `recovery locked does not auto route`() {
        assertFalse(FamilyShareRouting.shouldAutoRouteToFamilyChat(state(FamilyAccessLevel.RECOVERY_LOCKED)))
    }

    @Test
    fun `assistant picker only shown in standard or admin`() {
        assertTrue(FamilyShareRouting.canShowAssistantPicker(state(FamilyAccessLevel.STANDARD)))
        assertTrue(FamilyShareRouting.canShowAssistantPicker(state(FamilyAccessLevel.ADMIN_UNLOCKED)))
        assertFalse(FamilyShareRouting.canShowAssistantPicker(state(FamilyAccessLevel.FAMILY_LOCKED)))
        assertFalse(FamilyShareRouting.canShowAssistantPicker(state(FamilyAccessLevel.RECOVERY_LOCKED)))
        assertFalse(FamilyShareRouting.canShowAssistantPicker(state(FamilyAccessLevel.LOADING)))
    }

    @Test
    fun `loading state is not ready`() {
        assertFalse(FamilyShareRouting.isReady(state(FamilyAccessLevel.LOADING)))
        assertTrue(FamilyShareRouting.isReady(state(FamilyAccessLevel.STANDARD)))
    }
}
