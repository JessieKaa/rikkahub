package me.rerere.rikkahub.ui.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.familymode.FamilyModeController
import me.rerere.rikkahub.data.model.Assistant
import org.koin.compose.koinInject

@Composable
fun rememberAssistantState(
    settings: Settings,
    onUpdateSettings: (Settings) -> Unit,
    canManage: () -> Boolean = { true },
): AssistantState {
    val familyModeController: FamilyModeController = koinInject()
    val latestCanManage by rememberUpdatedState(canManage)
    return remember(settings, onUpdateSettings, familyModeController) {
        AssistantState(
            settings = settings,
            onUpdateSettings = onUpdateSettings,
            // 实时读取最新管理能力：标准模式与管理解锁放行，家人/恢复锁定拒绝。
            canManage = {
                latestCanManage() && familyModeController.state.value.isManagementAllowed
            },
        )
    }
}

class AssistantState(
    private val settings: Settings,
    private val onUpdateSettings: (Settings) -> Unit,
    private val canManage: () -> Boolean = { true },
) {
    private var _currentAssistant by mutableStateOf(
        settings.getCurrentAssistant()
    )
    val currentAssistant get() = _currentAssistant

    fun setSelectAssistant(assistant: Assistant) {
        // 在持久写入前检查最新门禁，拒绝家人锁定后的过期选择回调。
        if (!canManage()) return
        onUpdateSettings(
            settings.copy(
                assistantId = assistant.id
            )
        )
    }
}
