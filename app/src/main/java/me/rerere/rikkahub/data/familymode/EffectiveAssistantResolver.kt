package me.rerere.rikkahub.data.familymode

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

/**
 * 有效助手/模型解析的唯一来源。
 *
 * 家人锁定/恢复状态使用本机记录的 [FamilyModeRecord.familyAssistantId]；
 * 其他状态沿用全局选择。调用生成服务前应再次校验对话归属。
 */
object EffectiveAssistantResolver {

    fun isFamilyRestricted(accessLevel: FamilyAccessLevel): Boolean =
        accessLevel == FamilyAccessLevel.FAMILY_LOCKED ||
            accessLevel == FamilyAccessLevel.RECOVERY_LOCKED

    fun effectiveAssistantId(
        record: FamilyModeRecord?,
        accessLevel: FamilyAccessLevel,
        settings: Settings,
    ): Uuid? {
        return if (isFamilyRestricted(accessLevel)) {
            // 家人锁定：只信任本机记录；缺失时返回 null，绝不回退到全局任意助手。
            record?.familyAssistantId
        } else {
            settings.assistantId
        }
    }

    fun effectiveAssistant(
        record: FamilyModeRecord?,
        accessLevel: FamilyAccessLevel,
        settings: Settings,
    ): Assistant? {
        val id = effectiveAssistantId(record, accessLevel, settings) ?: return null
        if (isFamilyRestricted(accessLevel)) {
            // 家庭助手缺失时返回 null，不静默使用其他助手。
            return settings.assistants.find { it.id == id }
        }
        return settings.assistants.find { it.id == id } ?: settings.assistants.firstOrNull()
    }

    fun effectiveModel(
        record: FamilyModeRecord?,
        accessLevel: FamilyAccessLevel,
        settings: Settings,
    ): me.rerere.ai.provider.Model? {
        val assistant = effectiveAssistant(record, accessLevel, settings) ?: return null
        return settings.findModelById(assistant.chatModelId, settings.chatModelId)
    }

    /**
     * 家人锁定状态下只有属于家庭助手的对话可被打开或展示。
     * 标准/管理状态返回 true，保留原有能力。
     */
    fun ownsConversation(
        conversation: Conversation,
        record: FamilyModeRecord?,
        accessLevel: FamilyAccessLevel,
    ): Boolean {
        if (!isFamilyRestricted(accessLevel)) return true
        // 家人锁定但家庭助手缺失：fail-safe，不声明任意对话归属。
        val familyAssistantId = record?.familyAssistantId ?: return false
        return conversation.assistantId == familyAssistantId
    }
}
