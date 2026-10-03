package me.rerere.rikkahub.ui.pages.familymode

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.familymode.FamilyAccessLevel
import me.rerere.rikkahub.data.familymode.FamilyModeController
import me.rerere.rikkahub.data.sync.BackupManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.pages.backup.components.BackupDialog
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

/**
 * 恢复锁定页面：配置失效或本机记录损坏时的受限入口。
 *
 * 持有效本机 PIN 时可重试或从本地配置备份导入（默认不含聊天数据库），
 * 本机 PIN 与家人模式记录保存在 noBackupFilesDir，不会随普通配置备份覆盖。
 * 恢复验证只提供受限修复，不等于正常管理会话。
 */
@Composable
fun FamilyRecoveryPage(
    canEditConfiguration: Boolean = true,
    onRetry: () -> Unit = {},
    onManagementUnlocked: () -> Unit = {},
) {
    val controller: FamilyModeController = koinInject()
    val backupManager: BackupManager = koinInject()
    val state by controller.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var showUnlock by remember { mutableStateOf(false) }
    var retrying by remember { mutableStateOf(false) }
    var includeDatabase by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showRestart by remember { mutableStateOf(false) }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isImporting = true
            message = null
            val tempFile = File(context.cacheDir, "family_recovery_${System.currentTimeMillis()}.zip")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("无法读取所选文件")

                // 实际操作前复查最新状态：仅受限恢复或标准设置错误状态允许导入。
                val latest = controller.state.value
                val allowed = latest.recoveryUnlocked ||
                    (latest.accessLevel == FamilyAccessLevel.STANDARD && latest.settingsError != null)
                if (!allowed) {
                    throw IllegalStateException("恢复权限已失效，请重新验证管理员 PIN")
                }

                backupManager.stageRestore(
                    archive = tempFile,
                    includeDatabase = includeDatabase,
                    includeFiles = true,
                )
                showRestart = true
            } catch (e: Exception) {
                message = "导入失败：${e.message ?: "未知错误"}"
            } finally {
                tempFile.delete()
                isImporting = false
            }
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("家人模式恢复") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = "服务暂时不可用，请联系管理员。",
                style = MaterialTheme.typography.titleMedium,
            )
            state.configError?.let {
                Text(configErrorText(it), color = MaterialTheme.colorScheme.error)
            }
            state.settingsError?.let {
                Text("普通设置读取失败：$it", color = MaterialTheme.colorScheme.error)
            }

            if (state.canUnlockAdmin && canEditConfiguration) {
                Button(onClick = { showUnlock = true }) {
                    Text("验证管理员 PIN")
                }
            }

            if (state.recoveryUnlocked && canEditConfiguration) {
                Text("已通过本机 PIN 验证，可执行受限修复。")
            }

            TextButton(
                enabled = !retrying,
                onClick = {
                    retrying = true
                    scope.launch {
                        controller.retrySettings()
                        retrying = false
                        onRetry()
                    }
                },
            ) {
                if (retrying) {
                    CircularProgressIndicator()
                } else {
                    Text("重试加载配置")
                }
            }

            val importAllowed = canEditConfiguration && (
                state.recoveryUnlocked ||
                    (state.accessLevel == FamilyAccessLevel.STANDARD && state.settingsError != null)
                )
            if (importAllowed) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("包含聊天数据库")
                    Switch(
                        checked = includeDatabase,
                        onCheckedChange = { includeDatabase = it },
                    )
                }
                Text(
                    text = "默认不导入聊天数据库。本机 PIN 与家人模式记录不会被覆盖。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    enabled = !isImporting,
                    onClick = {
                        message = null
                        openDocumentLauncher.launch(
                            arrayOf("application/zip", "application/octet-stream")
                        )
                    },
                ) {
                    if (isImporting) {
                        CircularProgressIndicator()
                    } else {
                        Text("从本地配置备份导入")
                    }
                }
            }

            message?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            if (state.recoveryUnlocked && canEditConfiguration) {
                TextButton(onClick = onManagementUnlocked) {
                    Text("进入受限修复")
                }
            }
        }
    }

    if (showUnlock) {
        AdminUnlockDialog(
            onDismiss = { showUnlock = false },
            onUnlocked = {
                showUnlock = false
                onManagementUnlocked()
            },
        )
    }

    if (showRestart) {
        BackupDialog()
    }
}
