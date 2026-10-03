package me.rerere.rikkahub.data.familymode

import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class EffectiveAssistantResolverCoreTest {

    @Test
    fun `standard mode follows global assistant selection`() {
        val (settings, _) = testSettings()
        val otherId = Uuid.random()
        val record = FamilyModeRecord(
            familyModeEnabled = true,
            familyAssistantId = otherId,
        )
        assertEquals(
            settings.assistantId,
            EffectiveAssistantResolver.effectiveAssistantId(record, FamilyAccessLevel.STANDARD, settings),
        )
    }

    @Test
    fun `family locked mode uses recorded family assistant`() {
        val (settings, _) = testSettings()
        val familyId = Uuid.random()
        val updated = settings.copy(
            assistants = settings.assistants + settings.assistants.first().copy(id = familyId),
        )
        val record = FamilyModeRecord(familyModeEnabled = true, familyAssistantId = familyId)
        assertEquals(
            familyId,
            EffectiveAssistantResolver.effectiveAssistantId(record, FamilyAccessLevel.FAMILY_LOCKED, updated),
        )
    }

    @Test
    fun `effective model resolves from family assistant`() {
        val (settings, familyId) = testSettings()
        val record = FamilyModeRecord(familyModeEnabled = true, familyAssistantId = familyId)
        val model = EffectiveAssistantResolver.effectiveModel(record, FamilyAccessLevel.FAMILY_LOCKED, settings)
        assertNotNull(model)
        assertEquals(settings.assistants.first().chatModelId, model?.id)
    }

    @Test
    fun `family restricted without recorded assistant fails safe`() {
        val (settings, _) = testSettings()
        val record = FamilyModeRecord(familyModeEnabled = true, familyAssistantId = null)
        assertNull(EffectiveAssistantResolver.effectiveAssistantId(record, FamilyAccessLevel.FAMILY_LOCKED, settings))
        assertNull(EffectiveAssistantResolver.effectiveAssistant(record, FamilyAccessLevel.FAMILY_LOCKED, settings))
        assertNull(EffectiveAssistantResolver.effectiveModel(record, FamilyAccessLevel.FAMILY_LOCKED, settings))
    }

    @Test
    fun `conversation ownership restricted to family assistant when locked`() {
        val (settings, familyId) = testSettings()
        val record = FamilyModeRecord(familyModeEnabled = true, familyAssistantId = familyId)
        val owned = Conversation(id = Uuid.random(), assistantId = familyId, messageNodes = emptyList())
        val foreign = Conversation(id = Uuid.random(), assistantId = Uuid.random(), messageNodes = emptyList())
        assertTrue(EffectiveAssistantResolver.ownsConversation(owned, record, FamilyAccessLevel.FAMILY_LOCKED))
        assertFalse(EffectiveAssistantResolver.ownsConversation(foreign, record, FamilyAccessLevel.FAMILY_LOCKED))
        assertTrue(EffectiveAssistantResolver.ownsConversation(foreign, record, FamilyAccessLevel.ADMIN_UNLOCKED))
    }
}
