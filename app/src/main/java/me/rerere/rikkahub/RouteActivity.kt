package me.rerere.rikkahub

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.dokar.sonner.Toaster
import com.dokar.sonner.rememberToasterState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker
import me.rerere.rikkahub.data.db.MigrationState
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.familymode.EffectiveAssistantResolver
import me.rerere.rikkahub.data.familymode.FamilyModeController
import me.rerere.rikkahub.data.familymode.FamilyModePolicy
import me.rerere.rikkahub.data.familymode.FamilyModeState
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.activity.SafeModeActivity
import me.rerere.rikkahub.ui.components.ui.TTSController
import me.rerere.rikkahub.ui.context.AllowAllNavigationGate
import me.rerere.rikkahub.ui.context.LocalASRState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalSharedTransitionScope
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.NavigationGate
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.context.PendingNavigationQueue
import me.rerere.rikkahub.ui.hooks.readBooleanPreference
import me.rerere.rikkahub.ui.hooks.readStringPreference
import me.rerere.rikkahub.ui.hooks.rememberCustomAsrState
import me.rerere.rikkahub.ui.hooks.rememberCustomTtsState
import me.rerere.rikkahub.ui.pages.assistant.AssistantPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantBasicPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantDetailPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantExtensionsPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantLocalToolPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantMcpPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantMemoryPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantPromptPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantRequestPage
import me.rerere.rikkahub.ui.pages.backup.BackupPage
import me.rerere.rikkahub.ui.pages.chat.ChatPage
import me.rerere.rikkahub.ui.pages.debug.DebugPage
import me.rerere.rikkahub.ui.pages.extensions.ExtensionsPage
import me.rerere.rikkahub.ui.pages.extensions.PromptPage
import me.rerere.rikkahub.ui.pages.extensions.QuickMessagesPage
import me.rerere.rikkahub.ui.pages.extensions.skills.SkillDetailPage
import me.rerere.rikkahub.ui.pages.extensions.skills.SkillsPage
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspacePage
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceDetailPage
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceFileEditorPage
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalPage
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.rikkahub.ui.pages.familymode.FamilyModeSettingsPage
import me.rerere.rikkahub.ui.pages.familymode.FamilyRecoveryPage
import me.rerere.rikkahub.ui.pages.favorite.FavoritePage
import me.rerere.rikkahub.ui.pages.history.HistoryPage
import me.rerere.rikkahub.ui.pages.imggen.ImageGenPage
import me.rerere.rikkahub.ui.pages.log.LogPage
import me.rerere.rikkahub.ui.pages.search.SearchPage
import me.rerere.rikkahub.ui.pages.setting.SettingAboutPage
import me.rerere.rikkahub.ui.pages.setting.SettingPreferencesPage
import me.rerere.rikkahub.ui.pages.setting.SettingPreferencesThemePage
import me.rerere.rikkahub.ui.pages.setting.SettingPreferencesNotificationPage
import me.rerere.rikkahub.ui.pages.setting.SettingPreferencesGeneralPage
import me.rerere.rikkahub.ui.pages.setting.SettingPreferencesNetworkPage
import me.rerere.rikkahub.ui.pages.setting.SettingPreferencesUIPage
import me.rerere.rikkahub.ui.pages.setting.SettingThemePage
import me.rerere.rikkahub.ui.pages.setting.SettingDonatePage
import me.rerere.rikkahub.ui.pages.setting.SettingFilesPage
import me.rerere.rikkahub.ui.pages.setting.SettingMcpPage
import me.rerere.rikkahub.ui.pages.setting.SettingModelPage
import me.rerere.rikkahub.ui.pages.setting.SettingPage
import me.rerere.rikkahub.ui.pages.setting.SettingProviderDetailPage
import me.rerere.rikkahub.ui.pages.setting.SettingProviderPage
import me.rerere.rikkahub.ui.pages.setting.SettingSearchDetailPage
import me.rerere.rikkahub.ui.pages.setting.SettingSearchPage
import me.rerere.rikkahub.ui.pages.setting.SettingSpeechPage
import me.rerere.rikkahub.ui.pages.setting.SettingWebPage
import me.rerere.rikkahub.ui.pages.share.handler.ShareHandlerPage
import me.rerere.rikkahub.ui.pages.stats.StatsPage
import me.rerere.rikkahub.ui.pages.translator.TranslatorPage
import me.rerere.rikkahub.ui.pages.webview.WebViewPage
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import me.rerere.rikkahub.utils.CrashHandler
import me.rerere.rikkahub.utils.base64Encode
import me.rerere.rikkahub.utils.openUsageAccessSettings
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

private const val TAG = "RouteActivity"
private const val ACTION_TRANSLATE = "me.rerere.rikkahub.action.TRANSLATE"
private const val ACTION_IMAGE_GEN = "me.rerere.rikkahub.action.IMAGE_GEN"
private const val SHARE_DEDUP_WINDOW_MS = 2_000L

class RouteActivity : ComponentActivity() {
    private val okHttpClient by inject<OkHttpClient>()
    private val settingsStore by inject<SettingsStore>()
    private val familyModeController by inject<FamilyModeController>()
    private val conversationRepository by inject<ConversationRepository>()
    private var navStack: MutableList<NavKey>? = null
    private val pendingIntents = PendingNavigationQueue<Intent>()
    private val shareWindow = RecentShareSignatureWindow(
        windowMs = SHARE_DEDUP_WINDOW_MS,
        clock = { System.currentTimeMillis() },
    )

    // 稳定回退根：避免 sanitize 每次生成新 UUID 造成返回栈拖动/effect 循环。
    private val familyFallbackRoot: Screen.Chat by lazy {
        Screen.Chat(id = Uuid.random().toString())
    }

    // Volume key listener registry — last registered handler wins
    internal val volumeKeyListeners = mutableListOf<(isVolumeUp: Boolean) -> Boolean>()

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val isVolumeUp = when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> true
                KeyEvent.KEYCODE_VOLUME_DOWN -> false
                else -> return super.dispatchKeyEvent(event)
            }
            if (volumeKeyListeners.lastOrNull()?.invoke(isVolumeUp) == true) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        disableNavigationBarContrast()
        super.onCreate(savedInstanceState)
        if (CrashHandler.hasCrashed(this)) {
            startActivity(Intent(this, SafeModeActivity::class.java))
            finish()
            return
        }
        if (savedInstanceState == null) {
            handleIntent(intent)
        }
        setContent {
            RikkahubTheme {
                setSingletonImageLoaderFactory { context ->
                    ImageLoader.Builder(context)
                        .crossfade(true)
                        .components {
                            add(
                                OkHttpNetworkFetcherFactory(
                                    callFactory = { okHttpClient },
                                    cacheStrategy = { CacheControlCacheStrategy() },
                                )
                            )
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                add(AnimatedImageDecoder.Factory())
                            } else {
                                add(GifDecoder.Factory())
                            }
                            add(SvgDecoder.Factory(scaleToDensity = true))
                        }
                        .build()
                }
                AppRoutes()
            }
        }
    }

    private fun disableNavigationBarContrast() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (navStack == null || !familyModeController.state.value.isReady) {
            // Compose 尚未创建导航栈或访问状态未就绪，待就绪后处理。
            pendingIntents.enqueue(intent, distinct = isShareIntent(intent))
            return
        }
        lifecycleScope.launch { deliverIntent(intent) }
    }

    private fun flushPendingIntents() {
        if (!familyModeController.state.value.isReady) return
        pendingIntents.drain().forEach { handleIntent(it) }
    }

    private fun isShareIntent(intent: Intent): Boolean =
        intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_PROCESS_TEXT

    private suspend fun deliverIntent(intent: Intent) {
        val familyState = familyModeController.state.value
        if (!familyState.isReady) {
            pendingIntents.enqueue(intent, distinct = isShareIntent(intent))
            return
        }
        // 归一化分享会生成新的随机 Chat id，无法靠结构相等去重；在首次挂起前
        // 按内容签名预占，短窗口内重复的同一分享只导入一次，窗口后可再次分享。
        shareSignature(intent)?.let { signature ->
            if (!shareWindow.tryReserve(signature)) return
        }
        val resolved = resolveIntentDestination(intent) ?: return
        val screen = validateDestination(resolved) ?: return
        if (!FamilyModePolicy.isNavigationAllowed(screen, familyModeController.state.value)) return
        val backStack = navStack ?: return
        if (backStack.lastOrNull() == screen) return
        backStack.add(screen)
    }

    private fun shareSignature(intent: Intent): String? = when (intent.action) {
        Intent.ACTION_SEND -> "send|${intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()}|" +
            intent.getStringExtra(Intent.EXTRA_STREAM).orEmpty()
        Intent.ACTION_PROCESS_TEXT ->
            "process|${intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()}"
        else -> null
    }

    private fun resolveIntentDestination(intent: Intent): Screen? = when (intent.action) {
        ACTION_TRANSLATE -> Screen.Translator
        ACTION_IMAGE_GEN -> Screen.ImageGen
        Intent.ACTION_SEND -> shareDestination(
            text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty(),
            streamUri = intent.getStringExtra(Intent.EXTRA_STREAM),
        )
        Intent.ACTION_PROCESS_TEXT -> shareDestination(
            text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty(),
            streamUri = null,
        )
        else -> intent.getStringExtra("conversationId")
            ?.takeIf { runCatching { Uuid.parse(it) }.isSuccess }
            ?.let { Screen.Chat(it) }
    }

    /**
     * 分享只导入家庭聊天：家人锁定状态下直接归一化为 [Screen.Chat] 并保留文本/附件，
     * 绝不展示助手选择；标准/管理状态沿用受限 [Screen.ShareHandler]。
     */
    private fun shareDestination(text: String, streamUri: String?): Screen {
        val shareHandler = Screen.ShareHandler(text = text, streamUri = streamUri)
        if (FamilyModePolicy.isNavigationAllowed(shareHandler, familyModeController.state.value)) {
            return shareHandler
        }
        return familyShareChatDestination(text = text, streamUri = streamUri)
    }

    private suspend fun validateDestination(screen: Screen): Screen? {
        if (screen !is Screen.Chat) return screen
        val id = runCatching { Uuid.parse(screen.id) }.getOrNull() ?: return null
        val familyState = familyModeController.state.value
        if (!EffectiveAssistantResolver.isFamilyRestricted(familyState.accessLevel)) return screen
        val conversation = conversationRepository.getConversationById(id) ?: return screen
        return if (
            EffectiveAssistantResolver.ownsConversation(
                conversation,
                familyState.record,
                familyState.accessLevel,
            )
        ) {
            screen
        } else {
            familyChatRoot()
        }
    }

    /** 重新锁定/超时/进程恢复时，在渲染前清理整条返回栈并保证家庭聊天根。 */
    private suspend fun sanitizeNavigationStack(navigator: Navigator, familyState: FamilyModeState) {
        val current = navigator.currentScreens()
        if (current.isEmpty()) {
            navigator.clearAndNavigate(familyChatRoot())
            return
        }
        val allowed = current.filter { screen ->
            FamilyModePolicy.isNavigationAllowed(screen, familyState) && isOwnedScreen(screen, familyState)
        }
        if (allowed == current) return
        val root = allowed.firstOrNull { it is Screen.Chat } ?: familyChatRoot()
        val sanitized = if (allowed.firstOrNull() is Screen.Chat) allowed else listOf(root) + allowed
        navigator.replaceStack(sanitized)
    }

    private suspend fun isOwnedScreen(screen: Screen, familyState: FamilyModeState): Boolean {
        if (screen !is Screen.Chat) return true
        if (!EffectiveAssistantResolver.isFamilyRestricted(familyState.accessLevel)) return true
        val id = runCatching { Uuid.parse(screen.id) }.getOrNull() ?: return false
        val conversation = conversationRepository.getConversationById(id) ?: return true
        return EffectiveAssistantResolver.ownsConversation(
            conversation,
            familyState.record,
            familyState.accessLevel,
        )
    }

    private fun familyChatRoot(): Screen.Chat = familyFallbackRoot

    @Composable
    fun AppRoutes() {
        val familyState by familyModeController.state.collectAsStateWithLifecycle()
        val webShutdown by familyModeController.webShutdown.collectAsStateWithLifecycle()
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                !familyState.isReady -> FamilyModeLoadingScreen()
                // 配置错误/恢复锁定统一走独立恢复页：STANDARD 下 canUnlockAdmin=false，
                // 因此不要求 PIN，由核心恢复页提供标准导入路径。
                familyState.settingsError != null || familyState.isRecovery -> FamilyModeRecoveryRoot()
                else -> NormalAppRoutes(familyState)
            }
            val shutdownBlocking = webShutdown.inProgress ||
                (webShutdown.timedOut && !webShutdown.completed)
            if (shutdownBlocking) {
                FamilyWebShutdownTransition(
                    timedOut = webShutdown.timedOut && !webShutdown.completed,
                    onRetry = { familyModeController.completeManagement() },
                )
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun NormalAppRoutes(familyState: FamilyModeState) {
        val toastState = rememberToasterState()
        val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
        val tts = rememberCustomTtsState()
        val asr = rememberCustomAsrState()
        val eventBus = koinInject<AppEventBus>()
        LaunchedEffect(tts) {
            eventBus.events.collect { event ->
                when (event) {
                    is AppEvent.Speak -> tts.speak(event.text)
                    is AppEvent.OpenUsageAccessSettings -> this@RouteActivity.openUsageAccessSettings()
                    is AppEvent.ChatGenerationUpdate -> Unit // 由 ChatNotificationManager 消费
                    is AppEvent.ChatGenerationEnded -> Unit // 由 ChatNotificationManager 消费
                }
            }
        }
        val migrationState by DatabaseMigrationTracker.state.collectAsStateWithLifecycle()

        val startScreen = Screen.Chat(
            id = if (EffectiveAssistantResolver.isFamilyRestricted(familyState.accessLevel)) {
                // 家人锁定/恢复：绝不恢复可能属于其他助手的 lastConversationId。
                Uuid.random().toString()
            } else if (readBooleanPreference("create_new_conversation_on_start", true)) {
                Uuid.random().toString()
            } else {
                readStringPreference(
                    "lastConversationId",
                    Uuid.random().toString()
                ) ?: Uuid.random().toString()
            }
        )

        val backStack = rememberNavBackStack(startScreen)
        val pendingManagement = remember { mutableStateOf(false) }
        val gate = remember {
            NavigationGate { screen ->
                val allowed = FamilyModePolicy.isNavigationAllowed(
                    screen,
                    familyModeController.state.value,
                )
                // 隐藏 PIN 验证与状态更新之间存在竞态：记录本次管理入口请求，
                // 待状态真正进入 ADMIN_UNLOCKED 后再执行，绝不提前渲染管理页。
                if (!allowed &&
                    screen == Screen.FamilyModeSettings &&
                    familyModeController.state.value.canUnlockAdmin
                ) {
                    pendingManagement.value = true
                }
                allowed
            }
        }
        val navigator = remember(backStack, gate) { Navigator(backStack, gate) }
        SideEffect {
            navStack = backStack
        }
        LaunchedEffect(familyState.accessLevel, familyState.familyAssistantId, familyState.record) {
            sanitizeNavigationStack(navigator, familyModeController.state.value)
        }
        // 返回栈内容变化时重新校验归属，覆盖普通 navigate 到其他助手对话的路径。
        LaunchedEffect(navigator) {
            snapshotFlow { backStack.toList() }
                .distinctUntilChanged()
                .collect { sanitizeNavigationStack(navigator, familyModeController.state.value) }
        }
        LaunchedEffect(familyState.isAdminUnlocked, pendingManagement.value) {
            if (familyState.isAdminUnlocked && pendingManagement.value) {
                pendingManagement.value = false
                navigator.navigate(Screen.FamilyModeSettings) { launchSingleTop = true }
            }
        }
        LaunchedEffect(familyState.isReady, backStack) {
            flushPendingIntents()
        }

        SharedTransitionLayout {
            CompositionLocalProvider(
                LocalNavController provides navigator,
                LocalSharedTransitionScope provides this,
                LocalSettings provides settings,
                LocalToaster provides toastState,
                LocalTTSState provides tts,
                LocalASRState provides asr,
            ) {
                Toaster(
                    state = toastState,
                    darkTheme = LocalDarkMode.current,
                    richColors = true,
                    alignment = Alignment.TopCenter,
                    showCloseButton = true,
                )
                TTSController()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { testTagsAsResourceId = true }
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    NavDisplay(
                        backStack = backStack,
                        entryDecorators = listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator(),
                        ),
                        modifier = Modifier.fillMaxSize(),
                        onBack = {
                            if (backStack.size > 1) backStack.removeLastOrNull() else finish()
                        },
                        transitionSpec = {
                            if (backStack.size == 1) fadeIn() togetherWith fadeOut()
                            else {
                                slideInHorizontally { it } togetherWith
                                    slideOutHorizontally { -it / 2 } + scaleOut(targetScale = 0.7f) + fadeOut()
                            }
                        },
                        popTransitionSpec = {
                            slideInHorizontally { -it / 2 } + scaleIn(initialScale = 0.7f) + fadeIn() togetherWith
                                slideOutHorizontally { it }
                        },
                        predictivePopTransitionSpec = {
                            slideInHorizontally { -it / 2 } + scaleIn(initialScale = 0.7f) + fadeIn() togetherWith
                                slideOutHorizontally { it }
                        },
                        entryProvider = entryProvider {
                            entry<Screen.Chat>(
                                metadata = NavDisplay.transitionSpec { fadeIn() togetherWith fadeOut() }
                                    + NavDisplay.popTransitionSpec { fadeIn() togetherWith fadeOut() }
                            ) { key ->
                                FamilyChatDestination(
                                    key = key,
                                    familyState = familyState,
                                    navigator = navigator,
                                    fallbackRoot = familyChatRoot(),
                                    repository = conversationRepository,
                                )
                            }

                            entry<Screen.ShareHandler> { key ->
                                ShareHandlerPage(
                                    text = key.text,
                                    image = key.streamUri
                                )
                            }

                            entry<Screen.FamilyModeSettings> {
                                FamilyModeSettingsPage(
                                    canEditConfiguration = familyState.isManagementAllowed,
                                    onCompleteManagement = {
                                        navigator.clearAndNavigate(familyChatRoot())
                                    },
                                )
                            }

                            entry<Screen.History> {
                                HistoryPage()
                            }

                            entry<Screen.Favorite> {
                                FavoritePage()
                            }

                            entry<Screen.Assistant> {
                                AssistantPage()
                            }

                            entry<Screen.AssistantDetail> { key ->
                                AssistantDetailPage(key.id)
                            }

                            entry<Screen.AssistantBasic> { key ->
                                AssistantBasicPage(key.id)
                            }

                            entry<Screen.AssistantPrompt> { key ->
                                AssistantPromptPage(key.id)
                            }

                            entry<Screen.AssistantMemory> { key ->
                                AssistantMemoryPage(key.id)
                            }

                            entry<Screen.AssistantRequest> { key ->
                                AssistantRequestPage(key.id)
                            }

                            entry<Screen.AssistantMcp> { key ->
                                AssistantMcpPage(key.id)
                            }

                            entry<Screen.AssistantLocalTool> { key ->
                                AssistantLocalToolPage(key.id)
                            }

                            entry<Screen.AssistantInjections> { key ->
                                AssistantExtensionsPage(key.id)
                            }

                            entry<Screen.Translator> {
                                TranslatorPage()
                            }

                            entry<Screen.Setting> {
                                SettingPage()
                            }

                            entry<Screen.Backup> {
                                BackupPage()
                            }

                            entry<Screen.ImageGen> {
                                ImageGenPage()
                            }

                            entry<Screen.WebView> { key ->
                                WebViewPage(key.url, key.contentId)
                            }

                            entry<Screen.SettingTheme> {
                                SettingThemePage()
                            }

                            entry<Screen.SettingPreferences> {
                                SettingPreferencesPage()
                            }

                            entry<Screen.SettingPreferencesTheme> {
                                SettingPreferencesThemePage()
                            }

                            entry<Screen.SettingPreferencesNotification> {
                                SettingPreferencesNotificationPage()
                            }

                            entry<Screen.SettingPreferencesGeneral> {
                                SettingPreferencesGeneralPage()
                            }

                            entry<Screen.SettingPreferencesUI> {
                                SettingPreferencesUIPage()
                            }

                            entry<Screen.SettingPreferencesNetwork> {
                                SettingPreferencesNetworkPage()
                            }

                            entry<Screen.SettingProvider> {
                                SettingProviderPage()
                            }

                            entry<Screen.SettingProviderDetail> { key ->
                                val id = Uuid.parse(key.providerId)
                                SettingProviderDetailPage(id = id)
                            }

                            entry<Screen.SettingModels> {
                                SettingModelPage()
                            }

                            entry<Screen.SettingAbout> {
                                SettingAboutPage()
                            }

                            entry<Screen.SettingSearch> {
                                SettingSearchPage()
                            }

                            entry<Screen.SettingSearchDetail> { key ->
                                val id = Uuid.parse(key.serviceId)
                                SettingSearchDetailPage(id)
                            }

                            entry<Screen.SettingSpeech> {
                                SettingSpeechPage()
                            }

                            entry<Screen.SettingMcp> {
                                SettingMcpPage()
                            }

                            entry<Screen.SettingDonate> {
                                SettingDonatePage()
                            }

                            entry<Screen.SettingFiles> {
                                SettingFilesPage()
                            }

                            entry<Screen.SettingWeb> {
                                SettingWebPage()
                            }

                            entry<Screen.Debug> {
                                DebugPage()
                            }

                            entry<Screen.Log> {
                                LogPage()
                            }

                            entry<Screen.Extensions> {
                                ExtensionsPage()
                            }

                            entry<Screen.QuickMessages> {
                                QuickMessagesPage()
                            }

                            entry<Screen.Prompts> {
                                PromptPage()
                            }

                            entry<Screen.Skills> {
                                SkillsPage()
                            }

                            entry<Screen.Workspaces> {
                                WorkspacePage()
                            }

                            entry<Screen.WorkspaceDetail> { key ->
                                WorkspaceDetailPage(key.id)
                            }

                            entry<Screen.WorkspaceTerminal> { key ->
                                WorkspaceTerminalPage(key.id)
                            }

                            entry<Screen.WorkspaceFileEditor> { key ->
                                WorkspaceFileEditorPage(
                                    id = key.id,
                                    area = WorkspaceStorageArea.valueOf(key.area),
                                    path = key.path,
                                )
                            }

                            entry<Screen.SkillDetail> { key ->
                                SkillDetailPage(skillName = key.skillName)
                            }

                            entry<Screen.MessageSearch> {
                                SearchPage()
                            }

                            entry<Screen.Stats> {
                                StatsPage()
                            }
                        }
                    )
                    if (BuildConfig.DEBUG) {
                        Text(
                            text = "[开发模式]",
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        )
                    }
                    AnimatedVisibility(
                        visible = migrationState is MigrationState.Migrating,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        val state = migrationState as? MigrationState.Migrating
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    text = stringResource(R.string.db_migrating),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (state != null) {
                                    Text(
                                        text = "v${state.from} → v${state.to}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    val boundaryScreen = backStack.lastOrNull() as? Screen
                    if (boundaryScreen != null &&
                        !FamilyModePolicy.isNavigationAllowed(
                            boundaryScreen,
                            familyState,
                        )
                    ) {
                        FamilyModeBoundaryFallback()
                    }
                }
            }
        }
    }
}

@Composable
private fun FamilyModeLoadingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator()
            Text(text = "正在加载…", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private object RecoveryRootKey : NavKey

@Composable
private fun FamilyModeRecoveryRoot() {
    val backStack = remember { mutableStateListOf<NavKey>(RecoveryRootKey) }
    val navigator = remember(backStack) { Navigator(backStack, AllowAllNavigationGate) }
    CompositionLocalProvider(LocalNavController provides navigator) {
        when (backStack.lastOrNull()) {
            RecoveryRootKey -> FamilyRecoveryPage(
                onManagementUnlocked = { navigator.navigate(Screen.FamilyModeSettings) },
            )
            else -> FamilyModeSettingsPage(
                canEditConfiguration = true,
                onCompleteManagement = {
                    // 控制器状态变化后由根节点切回家庭聊天。
                },
            )
        }
    }
}

@Composable
private fun FamilyModeBoundaryFallback() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "当前页面不可用", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun FamilyChatDestination(
    key: Screen.Chat,
    familyState: FamilyModeState,
    navigator: Navigator,
    fallbackRoot: Screen.Chat,
    repository: ConversationRepository,
) {
    val conversationId = remember(key.id) { runCatching { Uuid.parse(key.id) }.getOrNull() }
    if (conversationId == null) {
        FamilyModeBoundaryFallback()
        return
    }
    when (rememberFamilyChatOwnership(conversationId, familyState, repository)) {
        FamilyChatOwnership.UNKNOWN -> FamilyModeLoadingScreen()
        FamilyChatOwnership.FOREIGN -> {
            LaunchedEffect(conversationId, familyState.accessLevel) {
                navigator.clearAndNavigate(fallbackRoot)
            }
            FamilyModeBoundaryFallback()
        }
        FamilyChatOwnership.ALLOWED -> ChatPage(
            id = conversationId,
            text = key.text,
            files = key.files.map { it.toUri() },
            nodeId = key.nodeId?.let { runCatching { Uuid.parse(it) }.getOrNull() },
        )
    }
}

/**
 * 在渲染 [ChatPage] 前异步验证对话归属。未知期间显示加载而不是直接渲染，
 * 避免普通的 `Navigator.navigate(Screen.Chat(foreignId))` 在 sanitize 运行前
 * 先渲染其他助手内容；已落库的外来对话返回失败，由调用方回退到家庭根。
 */
@Composable
private fun rememberFamilyChatOwnership(
    conversationId: Uuid,
    familyState: FamilyModeState,
    repository: ConversationRepository,
): FamilyChatOwnership {
    if (!EffectiveAssistantResolver.isFamilyRestricted(familyState.accessLevel)) {
        return FamilyChatOwnership.ALLOWED
    }
    // 显式以全部输入为键：任一键变化时同步回到 UNKNOWN，绝不沿用旧 ALLOWED 值
    // 在本帧渲染 ChatPage。
    val ownership = remember(
        conversationId,
        familyState.accessLevel,
        familyState.familyAssistantId,
        familyState.record,
    ) {
        mutableStateOf(FamilyChatOwnership.UNKNOWN)
    }
    LaunchedEffect(
        conversationId,
        familyState.accessLevel,
        familyState.familyAssistantId,
        familyState.record,
    ) {
        ownership.value = FamilyChatOwnership.UNKNOWN
        ownership.value = try {
            val conversation = repository.getConversationById(conversationId)
            resolveFamilyChatOwnership(
                conversationExists = conversation != null,
                conversationAssistantId = conversation?.assistantId,
                familyAssistantId = familyState.familyAssistantId,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            FamilyChatOwnership.FOREIGN
        }
    }
    return ownership.value
}

@Composable
private fun FamilyWebShutdownTransition(timedOut: Boolean, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            if (timedOut) {
                Text(
                    text = "服务停止未确认",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "内置 Web 服务可能仍在运行，家人界面暂不可用。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onRetry) { Text("重试停止服务") }
            } else {
                CircularProgressIndicator()
                Text(text = "正在切换到家人模式，请稍候…", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

internal enum class FamilyChatOwnership { UNKNOWN, ALLOWED, FOREIGN }

/**
 * 家人模式分享归一化：直接构造家庭聊天而非受限分享页。
 *
 * [Screen.Chat.text] 的既有契约是 base64(明文)（ChatPage 会调用 base64Decode），
 * 因此在构造前必须编码一次；调用方不得重复编码。空文本保持 null，
 * 附件 URI 原样进入 files，不改变标准模式 ShareHandler 路径。
 */
internal fun familyShareChatDestination(text: String, streamUri: String?): Screen.Chat =
    Screen.Chat(
        id = Uuid.random().toString(),
        text = text.ifEmpty { null }?.base64Encode(),
        files = streamUri?.let { listOf(it) } ?: emptyList(),
    )

/**
 * 有界分享去重窗口：仅在 [windowMs] 内抑制紧邻的同一内容签名，
 * 窗口过期后允许再次分享同一内容（A/B/A 中的第二次 A 因中间签名不同而放行）。
 * clock 可注入以便纯测试；[tryReserve] 内完成检查与占位，调用方在主线程首次挂起前
 * 调用即可避免并发 check-then-set。
 */
internal class RecentShareSignatureWindow(
    private val windowMs: Long,
    private val clock: () -> Long,
) {
    private var lastSignature: String? = null
    private var lastReservedAtMs: Long = 0L

    /** 非重复时占位并返回 true；窗口内重复时返回 false 且不更新占位。 */
    fun tryReserve(signature: String): Boolean {
        val now = clock()
        val duplicate = signature == lastSignature && now - lastReservedAtMs <= windowMs
        if (duplicate) return false
        lastSignature = signature
        lastReservedAtMs = now
        return true
    }
}

/**
 * 纯归属判定：新增（未落库）对话属于家庭，其余只有本机家庭助手对话放行；
 * 家庭助手缺失时 fail-safe 视为外来。
 */
internal fun resolveFamilyChatOwnership(
    conversationExists: Boolean,
    conversationAssistantId: Uuid?,
    familyAssistantId: Uuid?,
): FamilyChatOwnership = when {
    !conversationExists -> FamilyChatOwnership.ALLOWED
    familyAssistantId == null -> FamilyChatOwnership.FOREIGN
    conversationAssistantId == familyAssistantId -> FamilyChatOwnership.ALLOWED
    else -> FamilyChatOwnership.FOREIGN
}

sealed interface Screen : NavKey {
    @Serializable
    data class Chat(
        val id: String,
        val text: String? = null,
        val files: List<String> = emptyList(),
        val nodeId: String? = null
    ) : Screen

    @Serializable
    data class ShareHandler(val text: String, val streamUri: String? = null) : Screen

    @Serializable
    data object FamilyModeSettings : Screen

    @Serializable
    data object History : Screen

    @Serializable
    data object Favorite : Screen

    @Serializable
    data object Assistant : Screen

    @Serializable
    data class AssistantDetail(val id: String) : Screen

    @Serializable
    data class AssistantBasic(val id: String) : Screen

    @Serializable
    data class AssistantPrompt(val id: String) : Screen

    @Serializable
    data class AssistantMemory(val id: String) : Screen

    @Serializable
    data class AssistantRequest(val id: String) : Screen

    @Serializable
    data class AssistantMcp(val id: String) : Screen

    @Serializable
    data class AssistantLocalTool(val id: String) : Screen

    @Serializable
    data class AssistantInjections(val id: String) : Screen

    @Serializable
    data object Translator : Screen

    @Serializable
    data object Setting : Screen

    @Serializable
    data object Backup : Screen

    @Serializable
    data object ImageGen : Screen

    @Serializable
    data class WebView(val url: String = "", val contentId: String = "") : Screen

    @Serializable
    data object SettingTheme : Screen

    @Serializable
    data object SettingPreferences : Screen

    @Serializable
    data object SettingPreferencesTheme : Screen

    @Serializable
    data object SettingPreferencesNotification : Screen

    @Serializable
    data object SettingPreferencesGeneral : Screen

    @Serializable
    data object SettingPreferencesUI : Screen

    @Serializable
    data object SettingPreferencesNetwork : Screen

    @Serializable
    data object SettingProvider : Screen

    @Serializable
    data class SettingProviderDetail(val providerId: String) : Screen

    @Serializable
    data object SettingModels : Screen

    @Serializable
    data object SettingAbout : Screen

    @Serializable
    data object SettingSearch : Screen

    @Serializable
    data class SettingSearchDetail(val serviceId: String) : Screen

    @Serializable
    data object SettingSpeech : Screen

    @Serializable
    data object SettingMcp : Screen

    @Serializable
    data object SettingDonate : Screen

    @Serializable
    data object SettingFiles : Screen

    @Serializable
    data object SettingWeb : Screen

    @Serializable
    data object Debug : Screen

    @Serializable
    data object Log : Screen

    @Serializable
    data object Extensions : Screen

    @Serializable
    data object QuickMessages : Screen

    @Serializable
    data object Prompts : Screen

    @Serializable
    data object Skills : Screen

    @Serializable
    data object Workspaces : Screen

    @Serializable
    data class WorkspaceDetail(val id: String) : Screen

    @Serializable
    data class WorkspaceTerminal(val id: String) : Screen

    @Serializable
    data class WorkspaceFileEditor(val id: String, val area: String, val path: String) : Screen

    @Serializable
    data class SkillDetail(val skillName: String) : Screen

    @Serializable
    data object MessageSearch : Screen

    @Serializable
    data object Stats : Screen
}
