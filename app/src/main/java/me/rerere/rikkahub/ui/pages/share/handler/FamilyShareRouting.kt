package me.rerere.rikkahub.ui.pages.share.handler

import me.rerere.rikkahub.data.familymode.FamilyAccessLevel
import me.rerere.rikkahub.data.familymode.FamilyModeState

/**
 * Pure share-screen routing rules for family mode.
 *
 * The root activity normally normalizes SEND/PROCESS_TEXT straight into the family chat, so this
 * screen is usually only reached in standard/admin mode. It still guards defensively: when it is
 * reached while family mode is locked it must never expose the assistant list or mutate the global
 * selected assistant.
 */
object FamilyShareRouting {

    /** The family mode record/settings are loaded and a decision can be made. */
    fun isReady(state: FamilyModeState): Boolean = state.isReady

    /**
     * Family locked: import the shared text/attachments straight into the fixed family chat.
     * Recovery locked is intentionally excluded; that state is handled by the recovery screen.
     * A missing family assistant id fails closed (no auto-route to a foreign assistant).
     */
    fun shouldAutoRouteToFamilyChat(state: FamilyModeState): Boolean =
        state.accessLevel == FamilyAccessLevel.FAMILY_LOCKED &&
            state.familyAssistantId != null

    /**
     * Only standard mode and an active admin session may pick an assistant. Family/recovery
     * locked, loading and unknown states never render the assistant picker.
     */
    fun canShowAssistantPicker(state: FamilyModeState): Boolean =
        state.accessLevel == FamilyAccessLevel.STANDARD ||
            state.accessLevel == FamilyAccessLevel.ADMIN_UNLOCKED
}
