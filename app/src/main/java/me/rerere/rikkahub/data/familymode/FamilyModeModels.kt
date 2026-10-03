package me.rerere.rikkahub.data.familymode

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsLoadState
import kotlin.uuid.Uuid

/**
 * 本机访问状态。普通设置（[Settings]）与本机家人模式记录相互独立。
 *
 * - [LOADING] 普通设置或本机模式记录尚未就绪，导航与目的页面应暂停。
 * - [STANDARD] 家人模式关闭（或确认为新安装），保留完整管理界面。
 * - [FAMILY_LOCKED] 家人模式开启、配置有效、未解锁管理会话。
 * - [ADMIN_UNLOCKED] 家人模式开启且本次内存管理会话有效。
 * - [RECOVERY_LOCKED] 关键配置缺失或本机记录读取/解析失败，禁止直接进入完整管理。
 */
enum class FamilyAccessLevel {
    LOADING,
    STANDARD,
    FAMILY_LOCKED,
    ADMIN_UNLOCKED,
    RECOVERY_LOCKED,
}

const val FAMILY_MODE_RECORD_VERSION = 1
const val DEFAULT_BACKGROUND_LOCK_TIMEOUT_MS = 60_000L

/**
 * PIN 校验信息。仅存储派生结果，绝不落盘明文 PIN。
 */
@Serializable
data class FamilyPinRecord(
    val algorithm: String = "PBKDF2WithHmacSHA256",
    val version: Int = 1,
    /** Base64 编码的随机盐。 */
    val salt: String,
    val iterations: Int,
    val keyLengthBits: Int = 256,
    /** Base64 编码的派生摘要。 */
    val hash: String,
)

/**
 * 本机家人模式记录，独立于普通配置备份，存放于 noBackupFilesDir。
 */
@Serializable
data class FamilyModeRecord(
    val version: Int = FAMILY_MODE_RECORD_VERSION,
    val familyModeEnabled: Boolean = false,
    val setupCompleted: Boolean = false,
    val familyAssistantId: Uuid? = null,
    val pin: FamilyPinRecord? = null,
    val backgroundLockTimeoutMs: Long = DEFAULT_BACKGROUND_LOCK_TIMEOUT_MS,
)

sealed interface FamilyModeLoad {
    data object Loading : FamilyModeLoad
    data class Ready(val record: FamilyModeRecord) : FamilyModeLoad
    data class Error(val cause: Throwable) : FamilyModeLoad
}

enum class FamilyConfigError {
    SETTINGS_NOT_READY,
    SETTINGS_UNAVAILABLE,
    ASSISTANT_MISSING,
    MODEL_MISSING,
    UNKNOWN,
}

data class FamilyConfigValidation(
    val valid: Boolean,
    val assistantId: Uuid? = null,
    val error: FamilyConfigError? = null,
)

/**
 * 暴露给 UI/导航的不可变能力快照。策略集中在此计算，避免各组件读取零散布尔值。
 */
data class FamilyModeState(
    val accessLevel: FamilyAccessLevel = FamilyAccessLevel.LOADING,
    val familyAssistantId: Uuid? = null,
    val record: FamilyModeRecord? = null,
    val settingsError: String? = null,
    val configError: FamilyConfigError? = null,
    /** 设置不可用但已通过本机 PIN 验证时的受限恢复权限。 */
    val recoveryUnlocked: Boolean = false,
) {
    /**
     * 是否允许修改管理配置。STANDARD（家人模式关闭）与 ADMIN_UNLOCKED 均为 true，
     * 家人锁定/恢复锁定/加载中为 false。管理门禁（SettingsStore）也使用该语义。
     */
    val isManagementAllowed: Boolean
        get() = accessLevel == FamilyAccessLevel.STANDARD ||
            accessLevel == FamilyAccessLevel.ADMIN_UNLOCKED

    /** 仅管理员会话：用于“完成管理”等只应在解锁后出现的操作。 */
    val isAdminUnlocked: Boolean
        get() = accessLevel == FamilyAccessLevel.ADMIN_UNLOCKED

    val isFamilyScope: Boolean
        get() = accessLevel == FamilyAccessLevel.FAMILY_LOCKED ||
            accessLevel == FamilyAccessLevel.RECOVERY_LOCKED

    val canUnlockAdmin: Boolean
        get() = record?.pin != null &&
            (accessLevel == FamilyAccessLevel.FAMILY_LOCKED ||
                accessLevel == FamilyAccessLevel.RECOVERY_LOCKED)

    val isWebStartAllowed: Boolean
        get() = accessLevel == FamilyAccessLevel.STANDARD ||
            accessLevel == FamilyAccessLevel.ADMIN_UNLOCKED

    val isReady: Boolean
        get() = accessLevel != FamilyAccessLevel.LOADING

    val isRecovery: Boolean
        get() = accessLevel == FamilyAccessLevel.RECOVERY_LOCKED
}

/**
 * 重新锁定后 Web 服务停止过渡状态。停服未确认前 UI 显示通用过渡提示。
 */
data class FamilyWebShutdownState(
    val inProgress: Boolean = false,
    val completed: Boolean = false,
    val timedOut: Boolean = false,
)

data class PinLockoutState(
    val failedAttempts: Int = 0,
    val lockedUntilMs: Long = 0L,
)

enum class FamilyModeFailure {
    MANAGEMENT_REQUIRED,
    NOT_READY,
    INVALID_PIN,
    PIN_MISMATCH,
    PIN_TOO_SHORT,
    ASSISTANT_MISSING,
    MODEL_MISSING,
    PERSIST_FAILED,
    RECORD_MISSING,
    NOT_ENABLED,
    ALREADY_ENABLED,
    PIN_LOCKED_OUT,
}

sealed interface FamilyModeResult {
    data object Success : FamilyModeResult
    data class Failure(
        val reason: FamilyModeFailure,
        val message: String? = null,
    ) : FamilyModeResult
}

sealed interface FamilyModeEvent {
    data object PinUnlocked : FamilyModeEvent
    data object AdminLocked : FamilyModeEvent
    data object ManagementCompleted : FamilyModeEvent
    data object ModeEnabled : FamilyModeEvent
    data object ModeDisabled : FamilyModeEvent
}

/** 本机模式记录的读写来源；便于在 JVM 测试中替换。 */
interface FamilyModeSource {
    val load: StateFlow<FamilyModeLoad>
    suspend fun read(): FamilyModeRecord
    suspend fun write(record: FamilyModeRecord)
    fun retry()
}

/**
 * 控制器的普通设置只读依赖，避免直接耦合 [me.rerere.rikkahub.data.datastore.SettingsStore]。
 * [me.rerere.rikkahub.data.datastore.SettingsStore] 实现该接口。
 */
interface FamilySettingsSource {
    val settingsFlow: StateFlow<Settings>
    val settingsLoadState: StateFlow<SettingsLoadState>

    /** 安装管理写入门禁。传入 null 表示清除。 */
    fun setManagementGate(gate: (() -> Boolean)?)

    /** 重新开始收集普通设置。 */
    fun retrySettings()
}
