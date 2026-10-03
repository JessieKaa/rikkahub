package me.rerere.rikkahub.ui.activity

import me.rerere.rikkahub.data.familymode.FamilyAccessLevel
import me.rerere.rikkahub.data.familymode.FamilyModeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyShortcutVisibilityTest {

    private fun state(level: FamilyAccessLevel) = FamilyModeState(accessLevel = level)

    @Test
    fun `non chat shortcuts enabled for standard and admin`() {
        assertTrue(FamilyShortcutVisibility.areNonChatShortcutsEnabled(state(FamilyAccessLevel.STANDARD)))
        assertTrue(FamilyShortcutVisibility.areNonChatShortcutsEnabled(state(FamilyAccessLevel.ADMIN_UNLOCKED)))
    }

    @Test
    fun `non chat shortcuts disabled while loading family or recovery`() {
        assertFalse(FamilyShortcutVisibility.areNonChatShortcutsEnabled(state(FamilyAccessLevel.LOADING)))
        assertFalse(FamilyShortcutVisibility.areNonChatShortcutsEnabled(state(FamilyAccessLevel.FAMILY_LOCKED)))
        assertFalse(FamilyShortcutVisibility.areNonChatShortcutsEnabled(state(FamilyAccessLevel.RECOVERY_LOCKED)))
    }

    @Test
    fun `dynamic ids avoid the removed manifest ids and keep camera out`() {
        assertEquals(
            listOf("dynamic_translator", "dynamic_image_gen"),
            FamilyShortcutVisibility.dynamicNonChatShortcutIds,
        )
        assertFalse(FamilyShortcutVisibility.dynamicNonChatShortcutIds.contains("camera"))
        // Must not reuse the legacy manifest ids, which cannot be manipulated via APIs.
        assertFalse(FamilyShortcutVisibility.dynamicNonChatShortcutIds.contains("translator"))
        assertFalse(FamilyShortcutVisibility.dynamicNonChatShortcutIds.contains("image_gen"))
    }

    @Test
    fun `id constants match the published list`() {
        assertEquals("dynamic_translator", FamilyShortcutVisibility.ID_TRANSLATOR)
        assertEquals("dynamic_image_gen", FamilyShortcutVisibility.ID_IMAGE_GEN)
    }
}
