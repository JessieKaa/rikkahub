package me.rerere.rikkahub.service

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.familymode.FamilyModeController
import me.rerere.rikkahub.data.familymode.FamilyModeState
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.cancelNotification
import me.rerere.rikkahub.utils.sendNotification
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

// Live Update 通知节流间隔：流式输出每个chunk都会触发一次更新，
// notify() 是 binder IPC 且系统本身会对高频更新限流，必须在应用侧节流
private const val LIVE_UPDATE_NOTIFICATION_THROTTLE_MS = 1000L

private const val GENERATION_DONE_NOTIFICATION_ID = 1
private const val GENERIC_LIVE_NOTIFICATION_REQUEST_CODE = 0

/**
 * 订阅 [AppEventBus] 上的聊天生成事件，负责后台生成相关的系统通知
 * （Live Update 进度通知和生成完成通知）。
 *
 * 家人模式收敛：
 * - 只对属于家庭助手的对话展示内容；其他助手的内容被抑制/替换为通用通知。
 * - 工具执行中的原始参数在家人锁定时不展示。
 * - 重新锁定时立即清理/替换已发出的内容通知。
 */
class ChatNotificationManager(
    private val context: Application,
    appScope: AppScope,
    eventBus: AppEventBus,
    private val settingsStore: SettingsStore,
) : KoinComponent {
    private val familyModeController: FamilyModeController by lazy { get<FamilyModeController>() }
    private val conversationRepository: ConversationRepository by lazy { get<ConversationRepository>() }

    private val isForeground = MutableStateFlow(false)
    private val liveUpdateLastSentAt = ConcurrentHashMap<Uuid, Long>()

    private data class LiveNotificationInfo(
        val conversationId: Uuid,
        val lastMessage: UIMessage,
        val senderName: String,
    )

    @Volatile
    private var lastLiveInfo: LiveNotificationInfo? = null

    @Volatile
    private var lastDoneConversationId: Uuid? = null

    init {
        // ProcessLifecycleOwner 要求在主线程注册观察者
        appScope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> isForeground.value = true
                        Lifecycle.Event.ON_STOP -> isForeground.value = false
                        else -> {}
                    }
                }
            )
        }
        appScope.launch(Dispatchers.Default) {
            eventBus.events.collect { event ->
                when (event) {
                    is AppEvent.ChatGenerationUpdate -> handleGenerationUpdate(event)
                    is AppEvent.ChatGenerationEnded -> handleGenerationEnded(event)
                    else -> {}
                }
            }
        }
        // React to access-state changes so already-issued notification previews are sanitized
        // (family-owned) or replaced with a generic runtime notification (foreign assistant).
        appScope.launch(Dispatchers.Default) {
            familyModeController.state.collect { state ->
                if (state.isFamilyScope) {
                    refreshNotificationsForState(state)
                }
            }
        }
    }

    private suspend fun handleGenerationUpdate(event: AppEvent.ChatGenerationUpdate) {
        if (isForeground.value) return
        val displaySetting = settingsStore.settingsFlow.value.displaySetting
        if (!displaySetting.enableNotificationOnMessageGeneration) return
        if (!displaySetting.enableLiveUpdateNotification) return

        val now = SystemClock.elapsedRealtime()
        val lastSentAt = liveUpdateLastSentAt[event.conversationId]
        if (lastSentAt != null && now - lastSentAt < LIVE_UPDATE_NOTIFICATION_THROTTLE_MS) return
        liveUpdateLastSentAt[event.conversationId] = now

        val state = familyModeController.state.value
        if (!isConversationVisible(state, event.conversationId)) return

        lastLiveInfo = LiveNotificationInfo(event.conversationId, event.lastMessage, event.senderName)
        sendLiveUpdateNotification(event.conversationId, event.lastMessage, event.senderName)
    }

    private suspend fun handleGenerationEnded(event: AppEvent.ChatGenerationEnded) {
        cancelLiveUpdateNotification(event.conversationId)

        val contentPreview = event.contentPreview ?: return
        if (isForeground.value) return
        if (!settingsStore.settingsFlow.value.displaySetting.enableNotificationOnMessageGeneration) return

        val state = familyModeController.state.value
        if (!isConversationVisible(state, event.conversationId)) return

        lastDoneConversationId = event.conversationId
        sendGenerationDoneNotification(event.conversationId, event.senderName, contentPreview)
    }

    /**
     * A conversation notification may only expose content when it belongs to the family assistant
     * (or when family mode is not locked). Deleted/unknown conversations are treated as foreign.
     */
    private suspend fun isConversationVisible(state: FamilyModeState, conversationId: Uuid): Boolean {
        if (!state.isFamilyScope) return true
        val conversation = runCatching { conversationRepository.getConversationById(conversationId) }.getOrNull()
        return FamilyNotificationGuard.canShowConversationContent(state, conversation)
    }

    private suspend fun refreshNotificationsForState(state: FamilyModeState) {
        // Completion notifications are one-shot content. When ownership is unknown (process restart
        // cleared in-memory metadata) fail closed and cancel it.
        val doneId = lastDoneConversationId
        if (doneId == null || !isConversationVisible(state, doneId)) {
            context.cancelNotification(GENERATION_DONE_NOTIFICATION_ID)
            lastDoneConversationId = null
        }
        // Live notifications must keep a real generation alive without leaking content. For unknown
        // metadata, only replace with the generic runtime notification when a foreground task
        // actually exists; otherwise cancel it (no fake forever-running notification).
        val live = lastLiveInfo
        if (live == null) {
            when (FamilyNotificationGuard.actionForUnknownLiveNotification(
                hasActiveForegroundTask = ChatGenerationForegroundService.running.value
            )) {
                FamilyUnknownNotificationAction.REPLACE_GENERIC -> sendGenericLiveNotification()
                FamilyUnknownNotificationAction.CANCEL -> context.cancelNotification(
                    ChatGenerationForegroundService.NOTIFICATION_ID
                )
            }
            return
        }
        if (isConversationVisible(state, live.conversationId)) {
            // Known family-owned content may remain, but redacted.
            sendLiveUpdateNotification(live.conversationId, live.lastMessage, live.senderName)
        } else if (ChatGenerationForegroundService.running.value) {
            sendGenericLiveNotification()
        } else {
            context.cancelNotification(ChatGenerationForegroundService.NOTIFICATION_ID)
        }
    }

    private fun sendGenerationDoneNotification(
        conversationId: Uuid,
        senderName: String,
        contentPreview: String
    ) {
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = GENERATION_DONE_NOTIFICATION_ID
        ) {
            title = senderName
            content = contentPreview
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = getPendingIntent(context, conversationId)
        }
    }

    private fun sendLiveUpdateNotification(
        conversationId: Uuid,
        lastMessage: UIMessage,
        senderName: String
    ) {
        // 确定当前状态
        val hideRawToolInput = FamilyNotificationGuard.hideRawToolInput(familyModeController.state.value)
        val (chipText, statusText, contentText) = determineNotificationContent(lastMessage.parts, hideRawToolInput)

        context.sendNotification(
            channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            // 更新前台服务正在使用的同一条通知，避免重复显示生成进度。
            notificationId = ChatGenerationForegroundService.NOTIFICATION_ID
        ) {
            title = senderName
            content = contentText
            subText = statusText
            ongoing = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_PROGRESS
            useBigTextStyle = true
            contentIntent = getPendingIntent(context, conversationId)
            requestPromotedOngoing = true
            shortCriticalText = chipText
        }
    }

    /** Generic runtime notification used when a foreign assistant task must keep running. */
    private fun sendGenericLiveNotification() {
        context.sendNotification(
            channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            notificationId = ChatGenerationForegroundService.NOTIFICATION_ID
        ) {
            title = context.getString(R.string.app_name)
            content = context.getString(R.string.notification_live_update_title)
            ongoing = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_PROGRESS
            contentIntent = getGenericPendingIntent(context)
        }
    }

    private fun determineNotificationContent(
        parts: List<UIMessagePart>,
        hideRawToolInput: Boolean,
    ): Triple<String, String, String> {
        // 检查最近的 part 来确定状态
        val lastReasoning = parts.filterIsInstance<UIMessagePart.Reasoning>().lastOrNull()
        val lastTool = parts.filterIsInstance<UIMessagePart.Tool>().lastOrNull()
        val lastText = parts.filterIsInstance<UIMessagePart.Text>().lastOrNull()

        return when {
            // 正在执行工具
            lastTool != null && !lastTool.isExecuted -> {
                if (hideRawToolInput) {
                    // Family scope: never surface tool names or raw parameters.
                    Triple(
                        context.getString(R.string.notification_live_update_chip_tool),
                        context.getString(R.string.notification_live_update_title),
                        ""
                    )
                } else {
                    val toolName = lastTool.toolName.substringAfterLast("__")
                    Triple(
                        context.getString(R.string.notification_live_update_chip_tool),
                        context.getString(R.string.notification_live_update_tool, toolName),
                        lastTool.input.take(100)
                    )
                }
            }
            // 正在思考（Reasoning 未结束）
            lastReasoning != null && lastReasoning.finishedAt == null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_thinking),
                    context.getString(R.string.notification_live_update_thinking),
                    lastReasoning.reasoning.takeLast(200)
                )
            }
            // 正在写回复
            lastText != null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_writing),
                    lastText.text.takeLast(200)
                )
            }
            // 默认状态
            else -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_title),
                    ""
                )
            }
        }
    }

    private fun cancelLiveUpdateNotification(conversationId: Uuid) {
        liveUpdateLastSentAt.remove(conversationId)
        if (lastLiveInfo?.conversationId == conversationId) {
            lastLiveInfo = null
        }
        // 前台服务持有通知时系统会保留它；启动失败时则清理普通 ongoing 通知。
        context.cancelNotification(ChatGenerationForegroundService.NOTIFICATION_ID)
    }

    private fun getPendingIntent(context: Context, conversationId: Uuid): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun getGenericPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            GENERIC_LIVE_NOTIFICATION_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
