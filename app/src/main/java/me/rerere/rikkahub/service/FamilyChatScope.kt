package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.familymode.EffectiveAssistantResolver
import me.rerere.rikkahub.data.familymode.FamilyAccessLevel
import me.rerere.rikkahub.data.familymode.FamilyModeState
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

/**
 * 聊天运行时使用的家人模式只读策略。
 *
 * 这里刻意只做“读取当前状态并派生能力”的纯函数，不复制核心 [FamilyModeState] 的字段，
 * 也不修改任何配置。标准模式与管理解锁状态保留完整能力；家人锁定/恢复状态收起并拒绝
 * 管理写入，同时把助手/模型/对话归属统一收敛到 [EffectiveAssistantResolver]。
 */
object FamilyChatScope {
    /** 是否允许修改聊天相关配置（模型、搜索、推理、提示词绑定等）。 */
    fun canEditConfiguration(state: FamilyModeState): Boolean =
        state.accessLevel == FamilyAccessLevel.STANDARD || state.isManagementAllowed

    /** 当前应当用于生成与展示的助手 ID；家人锁定且记录缺失时为 null（不回退全局）。 */
    fun effectiveAssistantId(state: FamilyModeState, settings: Settings): Uuid? =
        EffectiveAssistantResolver.effectiveAssistantId(state.record, state.accessLevel, settings)

    /** 家人锁定状态下只有属于家庭助手的对话可被打开、展示或写入。 */
    fun ownsConversation(state: FamilyModeState, conversation: Conversation): Boolean =
        EffectiveAssistantResolver.ownsConversation(conversation, state.record, state.accessLevel)

    /**
     * 家人锁定/恢复时保留拥有者控制的对话行为字段，只允许普通聊天数据（消息、分支、
     * 待处理工具状态等）通过。用于拒绝锁定后仍到达的过期行为编辑回调。
     */
    fun preserveProtectedConversationFields(
        current: Conversation,
        incoming: Conversation,
    ): Conversation = incoming.copy(
        assistantId = current.assistantId,
        customSystemPrompt = current.customSystemPrompt,
        modeInjectionIds = current.modeInjectionIds,
        lorebookIds = current.lorebookIds,
        workspaceCwd = current.workspaceCwd,
    )

    /**
     * 解析一次已准入生成的助手：
     * - 对话原始助手存在时始终使用它，让 relock 前准入的任务可以完成；
     * - 原始助手缺失且处于家人状态时返回 null，绝不回退任意全局助手；
     * - 其余状态回退全局当前助手。
     */
    fun resolveAdmittedAssistant(
        conversationAssistant: Assistant?,
        currentAssistant: Assistant,
        familyScope: Boolean,
    ): Assistant? = conversationAssistant ?: if (familyScope) null else currentAssistant
}
