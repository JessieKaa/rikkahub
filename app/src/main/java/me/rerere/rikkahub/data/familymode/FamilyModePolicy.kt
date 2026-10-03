package me.rerere.rikkahub.data.familymode

import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import kotlin.uuid.Uuid

/**
 * 家人模式纯策略：页面白名单、家庭配置校验。
 *
 * 所有判断只依赖传入参数，方便单元测试，并保证导航与操作提交时可用最新状态重新校验。
 */
object FamilyModePolicy {

    /** 未知页面默认拒绝；仅家人聊天及白名单辅助页面在锁定状态放行。 */
    fun isNavigationAllowed(screen: Screen, state: FamilyModeState): Boolean =
        when (state.accessLevel) {
            FamilyAccessLevel.LOADING -> false
            FamilyAccessLevel.STANDARD,
            FamilyAccessLevel.ADMIN_UNLOCKED -> true
            FamilyAccessLevel.FAMILY_LOCKED,
            FamilyAccessLevel.RECOVERY_LOCKED -> isFamilyScreenAllowed(screen)
        }

    /**
     * 家人锁定白名单。
     *
     * 聊天路由需要合法 UUID；历史/搜索/收藏限定家庭助手范围由各自的 VM/Repository 继续校验。
     */
    fun isFamilyScreenAllowed(screen: Screen): Boolean =
        when (screen) {
            is Screen.Chat -> runCatching { Uuid.parse(screen.id) }.isSuccess
            Screen.History,
            Screen.MessageSearch,
            Screen.Favorite -> true
            is Screen.WebView -> true
            else -> false
        }

    /**
     * 启用家人模式前校验家庭配置：助手存在且主模型或全局回退模型可解析。
     */
    fun validateFamilyConfig(
        settings: Settings,
        familyAssistantId: Uuid?,
    ): FamilyConfigValidation {
        if (familyAssistantId == null) {
            return FamilyConfigValidation(false, null, FamilyConfigError.ASSISTANT_MISSING)
        }
        val assistant = settings.assistants.find { it.id == familyAssistantId }
            ?: return FamilyConfigValidation(false, familyAssistantId, FamilyConfigError.ASSISTANT_MISSING)
        val model = settings.findModelById(assistant.chatModelId, settings.chatModelId)
            ?: return FamilyConfigValidation(false, familyAssistantId, FamilyConfigError.MODEL_MISSING)
        return FamilyConfigValidation(true, familyAssistantId, null)
    }
}
