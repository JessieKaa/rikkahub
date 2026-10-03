package me.rerere.rikkahub.data.familymode

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class FamilyModeRecordSerializationCoreTest {

    @Test
    fun `record round trips through json`() {
        val pin = FamilyPinRecord(salt = "c2FsdA==", iterations = 1000, hash = "aGFzaA==")
        val familyId = Uuid.random()
        val record = FamilyModeRecord(
            familyModeEnabled = true,
            setupCompleted = true,
            familyAssistantId = familyId,
            pin = pin,
            backgroundLockTimeoutMs = 90_000L,
        )

        val json = JsonInstant.encodeToString(record)
        val decoded = JsonInstant.decodeFromString<FamilyModeRecord>(json)

        assertEquals(record, decoded)
        assertEquals(familyId, decoded.familyAssistantId)
        assertEquals(pin, decoded.pin)
        assertEquals(90_000L, decoded.backgroundLockTimeoutMs)
    }

    @Test
    fun `missing fields fall back to safe defaults`() {
        val decoded = JsonInstant.decodeFromString<FamilyModeRecord>("{}")
        assertEquals(FAMILY_MODE_RECORD_VERSION, decoded.version)
        assertEquals(false, decoded.familyModeEnabled)
        assertEquals(false, decoded.setupCompleted)
        assertNull(decoded.familyAssistantId)
        assertNull(decoded.pin)
        assertEquals(DEFAULT_BACKGROUND_LOCK_TIMEOUT_MS, decoded.backgroundLockTimeoutMs)
    }

    @Test
    fun `serialized record never contains a plaintext pin`() {
        val json = JsonInstant.encodeToString(
            FamilyModeRecord(
                familyModeEnabled = true,
                setupCompleted = true,
                familyAssistantId = Uuid.random(),
                pin = FamilyPinRecord(salt = "c2FsdA==", iterations = 10_000, hash = "aGFzaA=="),
            )
        )
        assertTrue(json.isNotBlank())
        assertEquals(false, json.contains("123456"))
    }
}
