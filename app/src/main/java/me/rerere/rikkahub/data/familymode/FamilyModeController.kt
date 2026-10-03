package me.rerere.rikkahub.data.familymode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsLoadState
import java.util.concurrent.atomic.AtomicLong
import kotlin.uuid.Uuid

/**
 * 家人模式控制器：内存管理会话、PIN 验证与限速、模式开关、锁定协调与 Web 停服过渡。
 *
 * 有意不依赖 Web 管理层，避免与 ChatService/WebServerManager 形成 DI 环。
 * 通过 [setWebShutdownHandler] 在 RikkaHubApp 完成 DI 后绑定停服回调。
 */
class FamilyModeController(
    private val store: FamilyModeSource,
    private val settingsSource: FamilySettingsSource,
    private val scope: CoroutineScope,
    private val crypto: FamilyPinCrypto = FamilyPinCrypto(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        const val MAX_PIN_ATTEMPTS = 5
        const val PIN_LOCKOUT_MS = 30_000L
    }

    private data class AdminSession(
        val unlockedAtMs: Long,
        val settingsReady: Boolean,
    )

    private val adminSession = MutableStateFlow<AdminSession?>(null)
    private val _lockout = MutableStateFlow(PinLockoutState())
    val lockout: StateFlow<PinLockoutState> = _lockout.asStateFlow()

    private val _state = MutableStateFlow(FamilyModeState())
    val state: StateFlow<FamilyModeState> = _state.asStateFlow()

    private val _webShutdown = MutableStateFlow(FamilyWebShutdownState())
    val webShutdown: StateFlow<FamilyWebShutdownState> = _webShutdown.asStateFlow()

    private val _events = MutableSharedFlow<FamilyModeEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<FamilyModeEvent> = _events.asSharedFlow()

    private var webShutdownHandler: (suspend () -> Boolean)? = null
    private var webShutdownJob: Job? = null

    /** 停服轮次：用于阻止过期任务覆盖新一轮停服的终态。 */
    private val webShutdownGeneration = AtomicLong(0L)

    /** 最近一次进入后台的时间；null 表示当前不在后台（0/负数仍是合法时钟值）。 */
    @Volatile
    private var backgroundedAtMs: Long? = null

    /** 生命周期纪元：后台超时或会话撤销时递增，用于作废在途的异步 PIN 验证。 */
    private val lifecycleEpoch = AtomicLong(0L)

    init {
        // 安装实时管理门禁，避免 SettingsStore 反向依赖控制器。
        settingsSource.setManagementGate { _state.value.isManagementAllowed }
        scope.launch {
            combine(
                store.load,
                settingsSource.settingsFlow,
                settingsSource.settingsLoadState,
                adminSession,
            ) { _, _, _, _ -> Unit }.collect {
                // 从当前值重算，避免在排队期间发布已过期的 ADMIN 快照。
                refreshStateNow()
            }
        }
    }

    /**
     * 用当前来源值同步重算并发布状态。管理门禁与 Web 访问直接读取 [state]，
     * 因此在撤销/授予管理会话后必须立即调用，不能只等异步收集器。
     */
    private fun refreshStateNow() {
        _state.value = computeState(
            load = store.load.value,
            settings = settingsSource.settingsFlow.value,
            settingsLoad = settingsSource.settingsLoadState.value,
            session = adminSession.value,
        )
    }

    // ---------------------------------------------------------------------
    // state
    // ---------------------------------------------------------------------

    private fun computeState(
        load: FamilyModeLoad,
        settings: Settings,
        settingsLoad: SettingsLoadState,
        session: AdminSession?,
    ): FamilyModeState {
        val settingsError = (settingsLoad as? SettingsLoadState.Error)?.cause?.message
        val settingsReady = settingsLoad is SettingsLoadState.Ready

        if (load is FamilyModeLoad.Loading) {
            return FamilyModeState(accessLevel = FamilyAccessLevel.LOADING)
        }
        if (load is FamilyModeLoad.Error) {
            // 本机记录不可信：fail-closed，绝不当成新安装。
            return FamilyModeState(
                accessLevel = FamilyAccessLevel.RECOVERY_LOCKED,
                settingsError = settingsError,
                configError = FamilyConfigError.UNKNOWN,
            )
        }

        val record = (load as FamilyModeLoad.Ready).record
        val familyAssistantId = record.familyAssistantId

        if (settingsLoad is SettingsLoadState.Loading) {
            // 配置尚未就绪：暂停决定渲染目标。
            return FamilyModeState(
                accessLevel = FamilyAccessLevel.LOADING,
                familyAssistantId = familyAssistantId,
                record = record,
            )
        }

        if (!record.familyModeEnabled) {
            return FamilyModeState(
                accessLevel = FamilyAccessLevel.STANDARD,
                familyAssistantId = familyAssistantId,
                record = record,
                settingsError = settingsError,
            )
        }

        if (!settingsReady) {
            // 家人模式开启但普通设置不可用：允许本机 PIN 进入受限恢复，不开放完整管理。
            val recoveryUnlocked = session != null
            return FamilyModeState(
                accessLevel = FamilyAccessLevel.RECOVERY_LOCKED,
                familyAssistantId = familyAssistantId,
                record = record,
                settingsError = settingsError,
                configError = FamilyConfigError.SETTINGS_UNAVAILABLE,
                recoveryUnlocked = recoveryUnlocked,
            )
        }

        val configError = FamilyModePolicy.validateFamilyConfig(settings, familyAssistantId).error
        if (session != null) {
            return FamilyModeState(
                accessLevel = FamilyAccessLevel.ADMIN_UNLOCKED,
                familyAssistantId = familyAssistantId,
                record = record,
                settingsError = settingsError,
                configError = configError,
            )
        }
        return if (configError == null) {
            FamilyModeState(
                accessLevel = FamilyAccessLevel.FAMILY_LOCKED,
                familyAssistantId = familyAssistantId,
                record = record,
                settingsError = settingsError,
            )
        } else {
            FamilyModeState(
                accessLevel = FamilyAccessLevel.RECOVERY_LOCKED,
                familyAssistantId = familyAssistantId,
                record = record,
                settingsError = settingsError,
                configError = configError,
            )
        }
    }

    private fun currentRecord(): FamilyModeRecord? =
        (store.load.value as? FamilyModeLoad.Ready)?.record

    // ---------------------------------------------------------------------
    // PIN / session
    // ---------------------------------------------------------------------

    /**
     * 验证本机 PIN。成功时只在内存中创建管理会话；失败按次数限速。
     * 计算在后台线程执行，异步返回后会再次确认记录仍然有效。
     */
    suspend fun verifyPin(pin: CharArray): Boolean {
        if (isPinLockedOut()) return false
        val recordBefore = currentRecord() ?: return false
        val pinRecord = recordBefore.pin ?: return false
        if (!recordBefore.familyModeEnabled && _state.value.accessLevel != FamilyAccessLevel.STANDARD) {
            return false
        }

        val candidate = pin.copyOf()
        val epochBefore = lifecycleEpoch.get()
        val matches = try {
            withContext(Dispatchers.Default) { crypto.verify(candidate, pinRecord) }
        } finally {
            candidate.fill('\u0000')
        }

        if (!matches) {
            registerFailedAttempt()
            return false
        }

        // 异步返回后再次校验：必须仍是同一 PIN 记录，且未发生后台超时/会话撤销。
        val recordAfter = currentRecord() ?: return false
        if (!recordAfter.familyModeEnabled || recordAfter.pin != pinRecord) return false
        if (lifecycleEpoch.get() != epochBefore) return false

        _lockout.value = PinLockoutState()
        adminSession.value = AdminSession(
            unlockedAtMs = clock(),
            settingsReady = settingsSource.settingsLoadState.value is SettingsLoadState.Ready,
        )
        refreshStateNow()
        _events.tryEmit(FamilyModeEvent.PinUnlocked)
        return true
    }

    private fun isPinLockedOut(): Boolean = clock() < _lockout.value.lockedUntilMs

    private fun registerFailedAttempt() {
        val attempts = _lockout.value.failedAttempts + 1
        _lockout.value = if (attempts >= MAX_PIN_ATTEMPTS) {
            PinLockoutState(failedAttempts = 0, lockedUntilMs = clock() + PIN_LOCKOUT_MS)
        } else {
            PinLockoutState(failedAttempts = attempts, lockedUntilMs = 0L)
        }
    }

    /** 撤销内存管理会话；若家人模式仍开启则同时触发可等待的 Web 停服过渡。 */
    fun lockAdmin(reason: String) {
        if (adminSession.value == null) return
        adminSession.value = null
        lifecycleEpoch.incrementAndGet()
        refreshStateNow()
        _events.tryEmit(FamilyModeEvent.AdminLocked)
        startWebShutdownIfNeeded()
    }

    /** 显式结束管理：撤销会话、清理管理状态并回到家庭根页面。 */
    fun completeManagement() {
        adminSession.value = null
        lifecycleEpoch.incrementAndGet()
        refreshStateNow()
        _events.tryEmit(FamilyModeEvent.ManagementCompleted)
        startWebShutdownIfNeeded()
    }

    // ---------------------------------------------------------------------
    // mode operations
    // ---------------------------------------------------------------------

    private fun canManage(): Boolean = _state.value.isManagementAllowed

    suspend fun enableFamilyMode(
        assistantId: Uuid,
        pin: CharArray,
        confirm: CharArray,
    ): FamilyModeResult {
        if (!canManage()) return FamilyModeResult.Failure(FamilyModeFailure.MANAGEMENT_REQUIRED)
        if (_state.value.accessLevel == FamilyAccessLevel.LOADING) {
            return FamilyModeResult.Failure(FamilyModeFailure.NOT_READY)
        }
        if (!FamilyPinCrypto.isValidPin(pin)) return FamilyModeResult.Failure(FamilyModeFailure.PIN_TOO_SHORT)
        if (!pin.contentEquals(confirm)) return FamilyModeResult.Failure(FamilyModeFailure.PIN_MISMATCH)

        val settings = settingsSource.settingsFlow.value
        val validation = FamilyModePolicy.validateFamilyConfig(settings, assistantId)
        if (!validation.valid) {
            return FamilyModeResult.Failure(
                reason = when (validation.error) {
                    FamilyConfigError.MODEL_MISSING -> FamilyModeFailure.MODEL_MISSING
                    FamilyConfigError.ASSISTANT_MISSING -> FamilyModeFailure.ASSISTANT_MISSING
                    else -> FamilyModeFailure.ASSISTANT_MISSING
                }
            )
        }

        val pinRecord = withContext(Dispatchers.Default) { crypto.createPinRecord(pin) }
        val base = currentRecord() ?: FamilyModeRecord()
        val updated = base.copy(
            version = FAMILY_MODE_RECORD_VERSION,
            familyModeEnabled = true,
            setupCompleted = true,
            familyAssistantId = assistantId,
            pin = pinRecord,
        )
        return try {
            store.write(updated)
            // 开启属于管理动作，保存成功后撤销管理会话。
            adminSession.value = null
            refreshStateNow()
            _events.tryEmit(FamilyModeEvent.ModeEnabled)
            startWebShutdownIfNeeded()
            FamilyModeResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FamilyModeResult.Failure(FamilyModeFailure.PERSIST_FAILED, e.message)
        }
    }

    suspend fun disableFamilyMode(): FamilyModeResult {
        if (!_state.value.isManagementAllowed) {
            return FamilyModeResult.Failure(FamilyModeFailure.MANAGEMENT_REQUIRED)
        }
        val record = currentRecord() ?: return FamilyModeResult.Failure(FamilyModeFailure.RECORD_MISSING)
        if (!record.familyModeEnabled) {
            return FamilyModeResult.Failure(FamilyModeFailure.NOT_ENABLED)
        }
        return try {
            store.write(record.copy(familyModeEnabled = false))
            adminSession.value = null
            refreshStateNow()
            _events.tryEmit(FamilyModeEvent.ModeDisabled)
            FamilyModeResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FamilyModeResult.Failure(FamilyModeFailure.PERSIST_FAILED, e.message)
        }
    }

    suspend fun changePin(
        currentPin: CharArray,
        newPin: CharArray,
        confirm: CharArray,
    ): FamilyModeResult {
        if (!_state.value.isManagementAllowed) {
            return FamilyModeResult.Failure(FamilyModeFailure.MANAGEMENT_REQUIRED)
        }
        if (!FamilyPinCrypto.isValidPin(newPin)) return FamilyModeResult.Failure(FamilyModeFailure.PIN_TOO_SHORT)
        if (!newPin.contentEquals(confirm)) return FamilyModeResult.Failure(FamilyModeFailure.PIN_MISMATCH)

        val record = currentRecord() ?: return FamilyModeResult.Failure(FamilyModeFailure.RECORD_MISSING)
        val existingPin = record.pin
        if (existingPin != null) {
            val current = currentPin.copyOf()
            val matches = try {
                withContext(Dispatchers.Default) { crypto.verify(current, existingPin) }
            } finally {
                current.fill('\u0000')
            }
            if (!matches) return FamilyModeResult.Failure(FamilyModeFailure.INVALID_PIN)
        }

        val newPinRecord = withContext(Dispatchers.Default) { crypto.createPinRecord(newPin) }
        return try {
            store.write(record.copy(pin = newPinRecord, setupCompleted = true))
            refreshStateNow()
            FamilyModeResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FamilyModeResult.Failure(FamilyModeFailure.PERSIST_FAILED, e.message)
        }
    }

    // ---------------------------------------------------------------------
    // lifecycle / retry
    // ---------------------------------------------------------------------

    fun onAppBackground() {
        backgroundedAtMs = clock()
    }

    fun onAppForeground() {
        val backgroundedAt = backgroundedAtMs ?: return
        backgroundedAtMs = null
        val timeout = currentRecord()?.backgroundLockTimeoutMs ?: DEFAULT_BACKGROUND_LOCK_TIMEOUT_MS
        if (clock() - backgroundedAt < timeout) return

        // 超时后即使尚未创建管理会话，也要使此前的异步 PIN 验证失效。
        lifecycleEpoch.incrementAndGet()
        if (adminSession.value != null) {
            lockAdmin("background_timeout")
        }
    }

    /** 重试读取普通设置与本机模式记录（恢复界面使用）。 */
    suspend fun retrySettings() {
        settingsSource.retrySettings()
        store.retry()
    }

    // ---------------------------------------------------------------------
    // web shutdown coordination
    // ---------------------------------------------------------------------

    fun setWebShutdownHandler(handler: suspend () -> Boolean) {
        webShutdownHandler = handler
    }

    private fun startWebShutdownIfNeeded() {
        val record = currentRecord()
        if (record == null || !record.familyModeEnabled) {
            _webShutdown.value = FamilyWebShutdownState(completed = true)
            return
        }
        val handler = webShutdownHandler
        if (handler == null) {
            // Web 管理尚未初始化，控制器保持可用。
            _webShutdown.value = FamilyWebShutdownState(completed = true)
            return
        }
        if (webShutdownJob?.isActive == true) return
        val generation = webShutdownGeneration.incrementAndGet()
        _webShutdown.value = FamilyWebShutdownState(inProgress = true)
        webShutdownJob = scope.launch {
            var stopped = false
            try {
                stopped = handler()
            } catch (e: CancellationException) {
                // 取消也必须先发布可重试终态，再继续传播取消。
                publishTerminalShutdown(generation, stopped = false, timedOut = true)
                throw e
            } catch (_: Throwable) {
                stopped = false
            }
            publishTerminalShutdown(generation, stopped = stopped, timedOut = !stopped)
        }
    }

    private fun publishTerminalShutdown(generation: Long, stopped: Boolean, timedOut: Boolean) {
        // 只允许仍是最新一轮停服的任务发布，避免过期任务覆盖较新状态。
        if (generation != webShutdownGeneration.get()) return
        _webShutdown.value = FamilyWebShutdownState(
            inProgress = false,
            completed = stopped,
            timedOut = timedOut,
        )
    }
}
