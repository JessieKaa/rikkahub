package me.rerere.rikkahub.ui.pages.familymode

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 隐藏管理员入口触发器：按住目标约 [holdDurationMs] 毫秒后回调。
 *
 * 由 ChatPage/runtime owner 绑定到顶部应用名称；家人锁定时长按不会显示任何提示。
 */
fun Modifier.familyAdminUnlockTrigger(
    enabled: Boolean = true,
    holdDurationMs: Long = 5_000L,
    onTrigger: () -> Unit,
): Modifier {
    if (!enabled) return this
    return pointerInput(holdDurationMs, onTrigger) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val released = withTimeoutOrNull(holdDurationMs) {
                waitForUpOrCancellation()
            }
            if (released == null) {
                onTrigger()
            }
        }
    }
}
