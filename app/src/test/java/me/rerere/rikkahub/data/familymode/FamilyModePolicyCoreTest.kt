package me.rerere.rikkahub.data.familymode

import me.rerere.rikkahub.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class FamilyModePolicyCoreTest {

    private val familyId = Uuid.random()

    private fun state(level: FamilyAccessLevel) = FamilyModeState(
        accessLevel = level,
        familyAssistantId = familyId,
        record = FamilyModeRecord(
            familyModeEnabled = level != FamilyAccessLevel.STANDARD,
            setupCompleted = true,
            familyAssistantId = familyId,
        ),
    )

    @Test
    fun `standard and admin allow any screen`() {
        val screen = Screen.Setting
        assertTrue(FamilyModePolicy.isNavigationAllowed(screen, state(FamilyAccessLevel.STANDARD)))
        assertTrue(FamilyModePolicy.isNavigationAllowed(screen, state(FamilyAccessLevel.ADMIN_UNLOCKED)))
    }

    @Test
    fun `loading denies all navigation`() {
        assertFalse(FamilyModePolicy.isNavigationAllowed(Screen.History, state(FamilyAccessLevel.LOADING)))
    }

    @Test
    fun `family whitelist allows chat history search favorite and webview`() {
        val locked = state(FamilyAccessLevel.FAMILY_LOCKED)
        assertTrue(FamilyModePolicy.isNavigationAllowed(Screen.Chat(Uuid.random().toString()), locked))
        assertTrue(FamilyModePolicy.isNavigationAllowed(Screen.History, locked))
        assertTrue(FamilyModePolicy.isNavigationAllowed(Screen.MessageSearch, locked))
        assertTrue(FamilyModePolicy.isNavigationAllowed(Screen.Favorite, locked))
        assertTrue(FamilyModePolicy.isNavigationAllowed(Screen.WebView("https://example.com"), locked))
    }

    @Test
    fun `family whitelist denies management screens by default`() {
        val locked = state(FamilyAccessLevel.FAMILY_LOCKED)
        assertFalse(FamilyModePolicy.isNavigationAllowed(Screen.Setting, locked))
        assertFalse(FamilyModePolicy.isNavigationAllowed(Screen.Assistant, locked))
        assertFalse(FamilyModePolicy.isNavigationAllowed(Screen.Log, locked))
        assertFalse(FamilyModePolicy.isNavigationAllowed(Screen.Backup, locked))
        assertFalse(FamilyModePolicy.isNavigationAllowed(Screen.Translator, locked))
    }

    @Test
    fun `chat route requires parseable uuid`() {
        assertTrue(FamilyModePolicy.isFamilyScreenAllowed(Screen.Chat(Uuid.random().toString())))
        assertFalse(FamilyModePolicy.isFamilyScreenAllowed(Screen.Chat("not-a-uuid")))
        assertFalse(FamilyModePolicy.isFamilyScreenAllowed(Screen.Chat("")))
    }

    @Test
    fun `validate family config succeeds with assistant and resolvable model`() {
        val (settings, id) = testSettings()
        val validation = FamilyModePolicy.validateFamilyConfig(settings, id)
        assertTrue(validation.valid)
        assertNull(validation.error)
        assertEquals(id, validation.assistantId)
    }

    @Test
    fun `validate family config reports missing assistant`() {
        val (settings, _) = testSettings()
        val validation = FamilyModePolicy.validateFamilyConfig(settings, Uuid.random())
        assertFalse(validation.valid)
        assertEquals(FamilyConfigError.ASSISTANT_MISSING, validation.error)
    }

    @Test
    fun `validate family config reports missing model`() {
        val (settings, id) = testSettings(withModel = false)
        val validation = FamilyModePolicy.validateFamilyConfig(settings, id)
        assertFalse(validation.valid)
        assertEquals(FamilyConfigError.MODEL_MISSING, validation.error)
    }

    @Test
    fun `validate family config reports missing assistant id`() {
        val (settings, _) = testSettings()
        val validation = FamilyModePolicy.validateFamilyConfig(settings, null)
        assertFalse(validation.valid)
        assertEquals(FamilyConfigError.ASSISTANT_MISSING, validation.error)
    }
}
