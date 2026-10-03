package me.rerere.rikkahub.ui.pages.familymode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.LockKeyhole
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.familymode.FamilyAccessLevel
import me.rerere.rikkahub.data.familymode.FamilyConfigError
import me.rerere.rikkahub.data.familymode.FamilyModeController
import me.rerere.rikkahub.data.familymode.FamilyModeFailure
import me.rerere.rikkahub.data.familymode.FamilyModeResult
import me.rerere.rikkahub.data.familymode.FamilyPinCrypto
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

/**
 * 家人模式管理页。导航 worker 需注册 `Screen.FamilyModeSettings` 并渲染本页。
 *
 * [canEditConfiguration] 由调用方（导航/聊天 UI）传入当前是否允许修改配置。
 */
@Composable
fun FamilyModeSettingsPage(
    canEditConfiguration: Boolean = true,
    onCompleteManagement: () -> Unit = {},
) {
    val controller: FamilyModeController = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val state by controller.state.collectAsStateWithLifecycle()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()

    var selectedAssistantId by remember(state.familyAssistantId) {
        mutableStateOf(state.familyAssistantId ?: settings.assistantId)
    }
    var message by remember { mutableStateOf<String?>(null) }
    var showEnableDialog by remember { mutableStateOf(false) }
    var showDisableDialog by remember { mutableStateOf(false) }
    var showChangePinDialog by remember { mutableStateOf(false) }

    fun report(result: FamilyModeResult) {
        message = when (result) {
            FamilyModeResult.Success -> null
            is FamilyModeResult.Failure -> failureText(result.reason)
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("家人模式") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("status") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("本机状态") },
                ) {
                    item(
                        leadingContent = { Icon(HugeIcons.LockKeyhole, null) },
                        headlineContent = { Text(accessLevelText(state.accessLevel)) },
                        supportingContent = {
                            val assistantName = settings.assistants
                                .firstOrNull { it.id == state.familyAssistantId }
                                ?.name
                                ?.ifBlank { null }
                                ?: state.familyAssistantId?.toString()?.take(8)
                                ?: "未指定"
                            Text("家庭助手：$assistantName")
                        },
                    )
                    if (state.settingsError != null) {
                        item(
                            headlineContent = { Text("普通设置读取失败", color = MaterialTheme.colorScheme.error) },
                            supportingContent = { Text(state.settingsError ?: "") },
                        )
                    }
                    state.configError?.let { error ->
                        item(
                            headlineContent = { Text("家庭配置需修复", color = MaterialTheme.colorScheme.error) },
                            supportingContent = { Text(configErrorText(error)) },
                        )
                    }
                }
            }

            if (state.accessLevel == FamilyAccessLevel.STANDARD ||
                state.accessLevel == FamilyAccessLevel.ADMIN_UNLOCKED
            ) {
                item("assistant") {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = { Text("家庭助手") },
                    ) {
                        item(
                            headlineContent = { Text("指定家庭助手") },
                            supportingContent = { Text("家人模式仅使用该助手及其模型") },
                            trailingContent = {
                                if (settings.assistants.isNotEmpty()) {
                                    val current = settings.assistants
                                        .firstOrNull { it.id == selectedAssistantId }
                                        ?: settings.assistants.first()
                                    if (canEditConfiguration) {
                                        Select(
                                            options = settings.assistants,
                                            selectedOption = current,
                                            onOptionSelected = { selectedAssistantId = it.id },
                                            optionToString = { it.name.ifBlank { it.id.toString().take(8) } },
                                        )
                                    } else {
                                        Text(current.name.ifBlank { current.id.toString().take(8) })
                                    }
                                }
                            },
                        )
                        item(
                            headlineContent = { Text("开启家人模式") },
                            supportingContent = { Text("验证助手与模型后原子保存，需要至少 6 位数字 PIN") },
                            trailingContent = {
                                Button(
                                    enabled = canEditConfiguration && state.accessLevel != FamilyAccessLevel.LOADING,
                                    onClick = { showEnableDialog = true },
                                ) {
                                    Text("开启")
                                }
                            },
                        )
                    }
                }
            }

            if (state.isAdminUnlocked) {
                item("management") {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = { Text("管理操作") },
                    ) {
                        item(
                            headlineContent = { Text("修改管理员 PIN") },
                            supportingContent = { Text("需要当前 PIN 二次确认") },
                            trailingContent = {
                                TextButton(
                                    enabled = canEditConfiguration,
                                    onClick = { showChangePinDialog = true },
                                ) {
                                    Text("修改")
                                }
                            },
                        )
                        item(
                            headlineContent = { Text("关闭家人模式") },
                            supportingContent = { Text("关闭后恢复完整管理界面") },
                            trailingContent = {
                                TextButton(
                                    enabled = canEditConfiguration,
                                    onClick = { showDisableDialog = true },
                                ) {
                                    Text("关闭")
                                }
                            },
                        )
                        item(
                            headlineContent = { Text("完成管理") },
                            supportingContent = { Text("撤销管理会话并回到家庭聊天") },
                            trailingContent = {
                                Button(
                                    onClick = {
                                        controller.completeManagement()
                                        onCompleteManagement()
                                    },
                                ) {
                                    Text("完成")
                                }
                            },
                        )
                    }
                }
            }

            message?.let { text ->
                item("message") {
                    Text(
                        text = text,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }

    if (showEnableDialog) {
        FamilyPinSetupDialog(
            title = "设置管理员 PIN",
            onDismiss = { showEnableDialog = false },
            onConfirm = { pin, confirm ->
                showEnableDialog = false
                scope.launch { report(controller.enableFamilyMode(selectedAssistantId, pin, confirm)) }
            },
        )
    }
    if (showChangePinDialog) {
        FamilyPinChangeDialog(
            onDismiss = { showChangePinDialog = false },
            onConfirm = { current, newPin, confirm ->
                showChangePinDialog = false
                scope.launch { report(controller.changePin(current, newPin, confirm)) }
            },
        )
    }
    if (showDisableDialog) {
        AlertDialog(
            onDismissRequest = { showDisableDialog = false },
            title = { Text("关闭家人模式") },
            text = { Text("确认关闭家人模式并恢复完整管理界面？") },
            confirmButton = {
                TextButton(onClick = {
                    showDisableDialog = false
                    scope.launch { report(controller.disableFamilyMode()) }
                }) { Text("关闭") }
            },
            dismissButton = {
                TextButton(onClick = { showDisableDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
internal fun FamilyPinSetupDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (pin: CharArray, confirm: CharArray) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = FamilyPinCrypto.isValidPin(pin.toCharArray()) && pin == confirm
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("至少 6 位数字")
                PinEntryField(value = pin, onValueChange = { pin = it }, label = "PIN")
                PinEntryField(value = confirm, onValueChange = { confirm = it }, label = "确认 PIN")
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onConfirm(pin.toCharArray(), confirm.toCharArray()) },
            ) { Text("确认") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun FamilyPinChangeDialog(
    onDismiss: () -> Unit,
    onConfirm: (current: CharArray, pin: CharArray, confirm: CharArray) -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = current.isNotEmpty() &&
        FamilyPinCrypto.isValidPin(pin.toCharArray()) &&
        pin == confirm
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改管理员 PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PinEntryField(value = current, onValueChange = { current = it }, label = "当前 PIN")
                PinEntryField(value = pin, onValueChange = { pin = it }, label = "新 PIN")
                PinEntryField(value = confirm, onValueChange = { confirm = it }, label = "确认新 PIN")
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onConfirm(current.toCharArray(), pin.toCharArray(), confirm.toCharArray())
                },
            ) { Text("确认") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun PinEntryField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onValueChange(input.filter { it.isDigit() }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        visualTransformation = PasswordVisualTransformation(),
        modifier = modifier.fillMaxWidth(),
    )
}

private fun accessLevelText(level: FamilyAccessLevel): String = when (level) {
    FamilyAccessLevel.LOADING -> "加载中"
    FamilyAccessLevel.STANDARD -> "标准模式（家人模式关闭）"
    FamilyAccessLevel.FAMILY_LOCKED -> "家人模式已开启"
    FamilyAccessLevel.ADMIN_UNLOCKED -> "管理员已解锁"
    FamilyAccessLevel.RECOVERY_LOCKED -> "恢复锁定"
}

internal fun configErrorText(error: FamilyConfigError): String = when (error) {
    FamilyConfigError.SETTINGS_NOT_READY -> "普通设置尚未就绪"
    FamilyConfigError.SETTINGS_UNAVAILABLE -> "普通设置不可用，请重试或导入备份"
    FamilyConfigError.ASSISTANT_MISSING -> "指定家庭助手不存在，请重新指定"
    FamilyConfigError.MODEL_MISSING -> "家庭助手的主模型或全局回退模型不可用"
    FamilyConfigError.UNKNOWN -> "本机模式记录读取失败"
}

internal fun failureText(reason: FamilyModeFailure): String = when (reason) {
    FamilyModeFailure.MANAGEMENT_REQUIRED -> "需要管理员权限"
    FamilyModeFailure.NOT_READY -> "配置尚未就绪"
    FamilyModeFailure.INVALID_PIN -> "PIN 错误"
    FamilyModeFailure.PIN_MISMATCH -> "两次输入的 PIN 不一致"
    FamilyModeFailure.PIN_TOO_SHORT -> "PIN 至少 6 位数字"
    FamilyModeFailure.ASSISTANT_MISSING -> "家庭助手不存在"
    FamilyModeFailure.MODEL_MISSING -> "家庭助手模型不可用"
    FamilyModeFailure.PERSIST_FAILED -> "保存失败，原状态未改变"
    FamilyModeFailure.RECORD_MISSING -> "本机模式记录缺失"
    FamilyModeFailure.NOT_ENABLED -> "家人模式未开启"
    FamilyModeFailure.ALREADY_ENABLED -> "家人模式已开启"
    FamilyModeFailure.PIN_LOCKED_OUT -> "尝试次数过多，请稍后再试"
}
