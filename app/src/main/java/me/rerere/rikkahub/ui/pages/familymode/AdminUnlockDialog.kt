package me.rerere.rikkahub.ui.pages.familymode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.LockKeyhole
import me.rerere.rikkahub.data.familymode.FamilyModeController
import me.rerere.rikkahub.data.familymode.FamilyPinCrypto
import org.koin.compose.koinInject

/**
 * 隐藏的管理员解锁弹窗。输入与解锁状态仅保存在内存中。
 */
@Composable
fun AdminUnlockDialog(
    onDismiss: () -> Unit,
    onUnlocked: () -> Unit,
) {
    val controller: FamilyModeController = koinInject()
    val lockout by controller.lockout.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val lockedOut = lockout.lockedUntilMs > 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(HugeIcons.LockKeyhole, null) },
        title = { Text("管理员验证") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("请输入本机管理员 PIN")
                PinEntryField(
                    value = pin,
                    onValueChange = {
                        pin = it
                        error = null
                    },
                    label = "PIN",
                )
                if (lockedOut) {
                    Text("尝试次数过多，请稍后再试", color = MaterialTheme.colorScheme.error)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !lockedOut && pin.length >= FamilyPinCrypto.MIN_PIN_LENGTH,
                onClick = {
                    scope.launch {
                        val unlocked = controller.verifyPin(pin.toCharArray())
                        if (unlocked) {
                            pin = ""
                            error = null
                            onUnlocked()
                        } else {
                            error = "PIN 错误或已限速"
                        }
                    }
                },
            ) { Text("解锁") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
