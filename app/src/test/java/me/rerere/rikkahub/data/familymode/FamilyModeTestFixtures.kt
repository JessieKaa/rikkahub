package me.rerere.rikkahub.data.familymode

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsLoadState
import me.rerere.rikkahub.data.model.Assistant
import java.io.IOException
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineDispatcher

internal fun testSettings(
    familyAssistantId: Uuid? = null,
    withModel: Boolean = true,
): Pair<Settings, Uuid> {
    val model = Model(modelId = "test-model", displayName = "Test Model", type = ModelType.CHAT)
    val provider = ProviderSetting.OpenAI(
        name = "Test",
        models = if (withModel) listOf(model) else emptyList(),
    )
    val assistantId = familyAssistantId ?: Uuid.random()
    val assistant = Assistant(id = assistantId, name = "Family", chatModelId = model.id)
    val settings = Settings.dummy().copy(
        providers = listOf(provider),
        assistants = listOf(assistant),
        assistantId = assistantId,
        chatModelId = model.id,
    )
    return settings to assistantId
}

internal class FakeFamilyModeSource(initial: FamilyModeLoad) : FamilyModeSource {
    private val _load = MutableStateFlow(initial)
    override val load: StateFlow<FamilyModeLoad> = _load.asStateFlow()

    var failWrites: Boolean = false
    var writeCount: Int = 0

    override suspend fun read(): FamilyModeRecord =
        (load.value as? FamilyModeLoad.Ready)?.record
            ?: throw IllegalStateException("no readable record")

    override suspend fun write(record: FamilyModeRecord) {
        if (failWrites) throw IOException("simulated write failure")
        writeCount++
        _load.value = FamilyModeLoad.Ready(record)
    }

    override fun retry() = Unit

    fun emit(load: FamilyModeLoad) {
        _load.value = load
    }
}

internal class FakeSettingsSource(
    settings: Settings,
    loadState: SettingsLoadState = SettingsLoadState.Ready(settings),
) : FamilySettingsSource {
    private val _settings = MutableStateFlow(settings)
    private val _load = MutableStateFlow(loadState)

    override val settingsFlow: StateFlow<Settings> = _settings.asStateFlow()
    override val settingsLoadState: StateFlow<SettingsLoadState> = _load.asStateFlow()

    var managementGate: (() -> Boolean)? = null
        private set

    override fun setManagementGate(gate: (() -> Boolean)?) {
        managementGate = gate
    }

    override fun retrySettings() = Unit

    fun update(settings: Settings) {
        _settings.value = settings
    }

    fun setLoadState(state: SettingsLoadState) {
        _load.value = state
    }

    fun isManagementAllowed(): Boolean = managementGate?.invoke() ?: true
}

internal fun awaitState(
    controller: FamilyModeController,
    predicate: (FamilyModeState) -> Boolean,
): FamilyModeState = runBlocking {
    withTimeout(5_000) { controller.state.first(predicate) }
}

/**
 * 可暂停的 PIN 校验实现，用于确定性测试异步验证的过期判定。
 *
 * [verifyStarted] 在进入派生时完成，测试修改记录/生命周期后调用 [releaseVerify] 放行。
 */
internal class ControlledFamilyPinCrypto : FamilyPinCrypto({ 500 }) {
    val verifyStarted = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()

    fun releaseVerify() {
        release.complete(Unit)
    }

    override fun verify(pin: CharArray, record: FamilyPinRecord): Boolean {
        verifyStarted.complete(Unit)
        runBlocking { withTimeout(5_000) { release.await() } }
        return super.verify(pin, record)
    }
}

/**
 * 手动驱动的 [CoroutineDispatcher]，仅在其 [runAll] 被调用时执行排队任务。
 *
 * 用于确定性复现“管理会话撤销后异步收集器尚未重算”的竞态，无需 coroutines-test 依赖。
 */
internal class ManualDispatcher : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        synchronized(queue) { queue.addLast(block) }
    }

    fun runAll() {
        while (true) {
            val block = synchronized(queue) { queue.pollFirst() } ?: return
            block.run()
        }
    }
}
