package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.familymode.FamilyModeState
import me.rerere.rikkahub.data.model.Conversation

/**
 * What to do with a content-bearing live notification whose backing conversation is unknown
 * (for example after a process restart cleared in-memory metadata).
 */
enum class FamilyUnknownNotificationAction {
    CANCEL,
    REPLACE_GENERIC,
}

/**
 * Pure family-mode rules for chat notifications.
 *
 * Notifications are content-bearing, so when family mode is locked they must not reveal another
 * assistant's conversation or raw tool parameters. The rules live here so they can be unit-tested
 * without Android notification plumbing.
 *
 * All checks fail closed: a locked state with no recorded family assistant suppresses content
 * instead of falling back to a global/foreign assistant.
 */
object FamilyNotificationGuard {

    /**
     * Family locked/recovery states show a generic body for in-progress tool calls instead of the
     * raw tool input (which may contain file paths, commands or credentials).
     */
    fun hideRawToolInput(state: FamilyModeState): Boolean = state.isFamilyScope

    /**
     * Whether a conversation notification may expose its content in [state].
     *
     * Standard/admin states keep existing behaviour. Locked family states only allow conversations
     * that belong to the recorded family assistant; an unknown (for example deleted) conversation or
     * a missing family assistant is treated as foreign and suppressed.
     */
    fun canShowConversationContent(state: FamilyModeState, conversation: Conversation?): Boolean {
        if (!state.isFamilyScope) return true
        val familyAssistantId = state.familyAssistantId ?: return false
        if (conversation == null) return false
        return conversation.assistantId == familyAssistantId
    }

    /**
     * Fail-closed decision for an unknown old live notification: keep a generic runtime
     * notification only when a real foreground task is running, otherwise cancel it. Never creates
     * a fake forever-running notification without an active task.
     */
    fun actionForUnknownLiveNotification(hasActiveForegroundTask: Boolean): FamilyUnknownNotificationAction =
        if (hasActiveForegroundTask) {
            FamilyUnknownNotificationAction.REPLACE_GENERIC
        } else {
            FamilyUnknownNotificationAction.CANCEL
        }
}
