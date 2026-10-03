package me.rerere.rikkahub.data.familymode

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.data.datastore.SettingsLoadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class FamilyModeControllerCoreTest {

    private val crypto = FamilyPinCrypto { 500 }

    private fun scope() = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private fun pinRecord(pin: String = "123456") = crypto.createPinRecord(pin.toCharArray())

    private fun controller(
        store: FakeFamilyModeSource,
        settings: FakeSettingsSource,
        clock: () -> Long = { 0L },
    ) = FamilyModeController(
        store = store,
        settingsSource = settings,
        scope = scope(),
        crypto = crypto,
        clock = clock,
    )

    private fun enabledRecord(familyAssistantId: Uuid): FamilyModeRecord = FamilyModeRecord(
        familyModeEnabled = true,
        setupCompleted = true,
        familyAssistantId = familyAssistantId,
        pin = pinRecord(),
    )

    @Test
    fun `standard state when family mode disabled`() {
        val (settings, _) = testSettings()
        val c = controller(
            FakeFamilyModeSource(FamilyModeLoad.Ready(FamilyModeRecord())),
            FakeSettingsSource(settings),
        )
        val state = awaitState(c) { it.isReady }
        assertEquals(FamilyAccessLevel.STANDARD, state.accessLevel)
        assertTrue(state.isWebStartAllowed)
        assertTrue(state.isManagementAllowed)
        assertFalse(state.isAdminUnlocked)
        assertFalse(state.canUnlockAdmin)
    }

    @Test
    fun `family locked when enabled and config valid`() {
        val (settings, id) = testSettings()
        val c = controller(
            FakeFamilyModeSource(FamilyModeLoad.Ready(enabledRecord(id))),
            FakeSettingsSource(settings),
        )
        val state = awaitState(c) { it.accessLevel == FamilyAccessLevel.FAMILY_LOCKED }
        assertTrue(state.canUnlockAdmin)
        assertFalse(state.isManagementAllowed)
        assertFalse(state.isWebStartAllowed)
    }

    @Test
    fun `recovery locked when family assistant missing`() {
        val (settings, _) = testSettings()
        val c = controller(
            FakeFamilyModeSource(FamilyModeLoad.Ready(enabledRecord(Uuid.random()))),
            FakeSettingsSource(settings),
        )
        val state = awaitState(c) { it.accessLevel == FamilyAccessLevel.RECOVERY_LOCKED }
        assertEquals(FamilyConfigError.ASSISTANT_MISSING, state.configError)
        assertTrue(state.canUnlockAdmin)
        assertFalse(state.isWebStartAllowed)
    }

    @Test
    fun `record read error is fail closed`() {
        val (settings, _) = testSettings()
        val c = controller(
            FakeFamilyModeSource(FamilyModeLoad.Error(IOException("corrupt"))),
            FakeSettingsSource(settings),
        )
        val state = awaitState(c) { it.accessLevel == FamilyAccessLevel.RECOVERY_LOCKED }
        assertFalse(state.isWebStartAllowed)
        assertFalse(state.canUnlockAdmin)
    }

    @Test
    fun `settings error with valid local pin allows restricted recovery only`() {
        val (settings, id) = testSettings()
        val settingsSource = FakeSettingsSource(settings, SettingsLoadState.Error(IOException("boom")))
        val c = controller(
            FakeFamilyModeSource(FamilyModeLoad.Ready(enabledRecord(id))),
            settingsSource,
        )
        awaitState(c) { it.accessLevel == FamilyAccessLevel.RECOVERY_LOCKED }
        assertTrue(runBlocking { c.verifyPin("123456".toCharArray()) })
        val state = awaitState(c) { it.recoveryUnlocked }
        assertEquals(FamilyAccessLevel.RECOVERY_LOCKED, state.accessLevel)
        assertFalse(state.isManagementAllowed)
    }

    @Test
    fun `wrong pin fails and locks out after five attempts`() {
        val (settings, id) = testSettings()
        var now = 0L
        val c = controller(
            FakeFamilyModeSource(FamilyModeLoad.Ready(enabledRecord(id))),
            FakeSettingsSource(settings),
            clock = { now },
        )
        awaitState(c) { it.accessLevel == FamilyAccessLevel.FAMILY_LOCKED }

        repeat(5) {
            assertFalse(runBlocking { c.verifyPin("000000".toCharArray()) })
        }
        assertFalse(runBlocking { c.verifyPin("123456".toCharArray()) })

        now += FamilyModeController.PIN_LOCKOUT_MS + 1
        assertTrue(runBlocking { c.verifyPin("123456".toCharArray()) })
        awaitState(c) { it.isManagementAllowed }
    }

    @Test
    fun `enable family mode validates config and persists`() {
        val (settings, id) = testSettings()
        val store = FakeFamilyModeSource(FamilyModeLoad.Ready(FamilyModeRecord()))
        val c = controller(store, FakeSettingsSource(settings))
        awaitState(c) { it.accessLevel == FamilyAccessLevel.STANDARD }

        val result = runBlocking {
            c.enableFamilyMode(id, "123456".toCharArray(), "123456".toCharArray())
        }
        assertEquals(FamilyModeResult.Success, result)
        assertEquals(1, store.writeCount)
        val state = awaitState(c) { it.accessLevel == FamilyAccessLevel.FAMILY_LOCKED }
        assertEquals(true, state.record?.familyModeEnabled)
        assertEquals(id, state.familyAssistantId)
    }

    @Test
    fun `enable family mode rejects mismatched pin`() {
        val (settings, id) = testSettings()
        val store = FakeFamilyModeSource(FamilyModeLoad.Ready(FamilyModeRecord()))
        val c = controller(store, FakeSettingsSource(settings))
        awaitState(c) { it.accessLevel == FamilyAccessLevel.STANDARD }

        val result = runBlocking {
            c.enableFamilyMode(id, "123456".toCharArray(), "654321".toCharArray())
        }
        assertEquals(
            FamilyModeResult.Failure(FamilyModeFailure.PIN_MISMATCH),
            result,
        )
        assertEquals(0, store.writeCount)
    }

    @Test
    fun `enable family mode fails when model missing and does not persist`() {
        val (settings, id) = testSettings(withModel = false)
        val store = FakeFamilyModeSource(FamilyModeLoad.Ready(FamilyModeRecord()))
        val c = controller(store, FakeSettingsSource(settings))
        awaitState(c) { it.accessLevel == FamilyAccessLevel.STANDARD }

        val result = runBlocking {
            c.enableFamilyMode(id, "123456".toCharArray(), "123456".toCharArray())
        }
        assertEquals(FamilyModeResult.Failure(FamilyModeFailure.MODEL_MISSING), result)
        assertEquals(0, store.writeCount)
    }

    @Test
    fun `disable requires admin and restores standard`() {
        val (settings, id) = testSettings()
        val c = controller(
            FakeFamilyModeSource(FamilyModeLoad.Ready(enabledRecord(id))),
            FakeSettingsSource(settings),
        )
        awaitState(c) { it.accessLevel == FamilyAccessLevel.FAMILY_LOCKED }
        assertEquals(
            FamilyModeResult.Failure(FamilyModeFailure.MANAGEMENT_REQUIRED),
            runBlocking { c.disableFamilyMode() },
        )

        assertTrue(runBlocking { c.verifyPin("123456".toCharArray()) })
        awaitState(c) { it.isManagementAllowed }
        assertEquals(FamilyModeResult.Success, runBlocking { c.disableFamilyMode() })
        awaitState(c) { it.accessLevel == FamilyAccessLevel.STANDARD }
    }

    @Test
    fun `lock admin revokes session and awaits web shutdown`() {
        val (settings, id) = testSettings()
        val c = controller(
            FakeFamilyModeSource(FamilyModeLoad.Ready(enabledRecord(id))),
            FakeSettingsSource(settings),
        )
        val shutdownCalled = AtomicBoolean(false)
        c.setWebShutdownHandler {
            shutdownCalled.set(true)
            true
        }
        assertTrue(runBlocking { c.verifyPin("123456".toCharArray()) })
        awaitState(c) { it.isManagementAllowed }

        c.lockAdmin("test")
        val state = awaitState(c) { it.accessLevel == FamilyAccessLevel.FAMILY_LOCKED }
        assertFalse(state.isManagementAllowed)
        runBlocking {
            withTimeout(5_000) {
                while (!shutdownCalled.get()) delay(10)
            }
        }
        val shutdown = awaitShutdownCompleted(c)
        assertTrue(shutdown.completed)
    }

    @Test
    fun `background timeout locks admin session`() {
        val (settings, id) = testSettings()
        var now = 0L
        val c = controller(
            FakeFamilyModeSource(FamilyModeLoad.Ready(enabledRecord(id))),
            FakeSettingsSource(settings),
            clock = { now },
        )
        assertTrue(runBlocking { c.verifyPin("123456".toCharArray()) })
        awaitState(c) { it.isManagementAllowed }

        c.onAppBackground()
        now += DEFAULT_BACKGROUND_LOCK_TIMEOUT_MS + 1
        c.onAppForeground()

        awaitState(c) { it.accessLevel == FamilyAccessLevel.FAMILY_LOCKED }
    }

    @Test
    fun `short background keeps admin session`() {
        val (settings, id) = testSettings()
        var now = 0L
        val c = controller(
            FakeFamilyModeSource(FamilyModeLoad.Ready(enabledRecord(id))),
            FakeSettingsSource(settings),
            clock = { now },
        )
        assertTrue(runBlocking { c.verifyPin("123456".toCharArray()) })
        awaitState(c) { it.isManagementAllowed }

        c.onAppBackground()
        now += 1_000L
        c.onAppForeground()
        assertTrue(c.state.value.isManagementAllowed)
    }

    @Test
    fun `stale pin verification does not unlock after record change`() = runBlocking {
        val (settings, id) = testSettings()
        val crypto = ControlledFamilyPinCrypto()
        val store = FakeFamilyModeSource(
            FamilyModeLoad.Ready(
                FamilyModeRecord(
                    familyModeEnabled = true,
                    setupCompleted = true,
                    familyAssistantId = id,
                    pin = crypto.createPinRecord("123456".toCharArray()),
                )
            )
        )
        val c = FamilyModeController(
            store = store,
            settingsSource = FakeSettingsSource(settings),
            scope = scope(),
            crypto = crypto,
            clock = { 0L },
        )
        awaitState(c) { it.accessLevel == FamilyAccessLevel.FAMILY_LOCKED }

        var verified = false
        val job = launch { verified = c.verifyPin("123456".toCharArray()) }
        withTimeout(5_000) { crypto.verifyStarted.await() }

        // Swap to a different PIN record while the verification is in flight.
        store.emit(
            FamilyModeLoad.Ready(
                FamilyModeRecord(
                    familyModeEnabled = true,
                    setupCompleted = true,
                    familyAssistantId = id,
                    pin = FamilyPinCrypto { 500 }.createPinRecord("654321".toCharArray()),
                )
            )
        )
        crypto.releaseVerify()
        withTimeout(5_000) { job.join() }
        assertFalse(verified)
        assertFalse(c.state.value.isAdminUnlocked)
    }

    @Test
    fun `background timeout invalidates in-flight pin verification without session`() = runBlocking {
        val (settings, id) = testSettings()
        val crypto = ControlledFamilyPinCrypto()
        var now = 0L
        val store = FakeFamilyModeSource(
            FamilyModeLoad.Ready(
                FamilyModeRecord(
                    familyModeEnabled = true,
                    setupCompleted = true,
                    familyAssistantId = id,
                    pin = crypto.createPinRecord("123456".toCharArray()),
                )
            )
        )
        val c = FamilyModeController(
            store = store,
            settingsSource = FakeSettingsSource(settings),
            scope = scope(),
            crypto = crypto,
            clock = { now },
        )
        awaitState(c) { it.accessLevel == FamilyAccessLevel.FAMILY_LOCKED }

        var verified = false
        val job = launch { verified = c.verifyPin("123456".toCharArray()) }
        withTimeout(5_000) { crypto.verifyStarted.await() }

        c.onAppBackground()
        now += DEFAULT_BACKGROUND_LOCK_TIMEOUT_MS + 1
        c.onAppForeground()
        crypto.releaseVerify()
        withTimeout(5_000) { job.join() }
        assertFalse(verified)
        assertFalse(c.state.value.isAdminUnlocked)
    }

    @Test
    fun `throwing web shutdown handler publishes retryable terminal state`() {
        val (settings, id) = testSettings()
        val c = controller(
            FakeFamilyModeSource(FamilyModeLoad.Ready(enabledRecord(id))),
            FakeSettingsSource(settings),
        )
        val calls = AtomicInteger(0)
        c.setWebShutdownHandler {
            calls.incrementAndGet()
            throw IllegalStateException("boom")
        }
        assertTrue(runBlocking { c.verifyPin("123456".toCharArray()) })
        awaitState(c) { it.isManagementAllowed }

        c.lockAdmin("test")
        val first = awaitShutdownCompleted(c)
        assertFalse(first.inProgress)
        assertFalse(first.completed)
        assertTrue(first.timedOut)
        assertEquals(1, calls.get())

        // Retry remains available: a second shutdown can start and complete.
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        c.setWebShutdownHandler {
            calls.incrementAndGet()
            started.complete(Unit)
            gate.await()
            true
        }
        c.completeManagement()
        runBlocking { withTimeout(5_000) { started.await() } }
        assertTrue(c.webShutdown.value.inProgress)
        assertEquals(2, calls.get())
        gate.complete(Unit)
        val second = awaitShutdownCompleted(c)
        assertFalse(second.inProgress)
        assertTrue(second.completed)
        assertFalse(second.timedOut)
    }

    @Test
    fun `canceled web shutdown handler publishes retryable terminal state`() {
        val (settings, id) = testSettings()
        val c = controller(
            FakeFamilyModeSource(FamilyModeLoad.Ready(enabledRecord(id))),
            FakeSettingsSource(settings),
        )
        c.setWebShutdownHandler { throw CancellationException("canceled") }
        assertTrue(runBlocking { c.verifyPin("123456".toCharArray()) })
        awaitState(c) { it.isManagementAllowed }

        c.lockAdmin("test")
        val state = awaitShutdownCompleted(c)
        assertFalse(state.inProgress)
        assertFalse(state.completed)
        assertTrue(state.timedOut)
    }

    @Test
    fun `synchronous lock revokes gate and web permission before collector drains`() {
        val (settings, id) = testSettings()
        val dispatcher = ManualDispatcher()
        val crypto = FamilyPinCrypto { 500 }
        val settingsSource = FakeSettingsSource(settings)
        val store = FakeFamilyModeSource(
            FamilyModeLoad.Ready(
                FamilyModeRecord(
                    familyModeEnabled = true,
                    setupCompleted = true,
                    familyAssistantId = id,
                    pin = crypto.createPinRecord("123456".toCharArray()),
                )
            )
        )
        val c = FamilyModeController(
            store = store,
            settingsSource = settingsSource,
            scope = CoroutineScope(dispatcher + SupervisorJob()),
            crypto = crypto,
            clock = { 0L },
        )

        assertTrue(runBlocking { c.verifyPin("123456".toCharArray()) })
        assertTrue(c.state.value.isAdminUnlocked)
        assertTrue(c.state.value.isWebStartAllowed)
        assertTrue(settingsSource.isManagementAllowed())

        // 不驱动异步收集器：锁必须同步生效。
        c.lockAdmin("test")
        assertFalse(c.state.value.isManagementAllowed)
        assertFalse(c.state.value.isWebStartAllowed)
        assertFalse(settingsSource.isManagementAllowed())
        assertFalse(c.state.value.isAdminUnlocked)

        // 排出排队的（过期）收集器任务，不得重新开放权限。
        dispatcher.runAll()
        assertFalse(c.state.value.isManagementAllowed)
        assertFalse(c.state.value.isWebStartAllowed)
        assertFalse(settingsSource.isManagementAllowed())
        assertFalse(c.state.value.isAdminUnlocked)
    }

    @Test
    fun `synchronous complete management revokes gate before collector drains`() {
        val (settings, id) = testSettings()
        val dispatcher = ManualDispatcher()
        val crypto = FamilyPinCrypto { 500 }
        val settingsSource = FakeSettingsSource(settings)
        val store = FakeFamilyModeSource(
            FamilyModeLoad.Ready(
                FamilyModeRecord(
                    familyModeEnabled = true,
                    setupCompleted = true,
                    familyAssistantId = id,
                    pin = crypto.createPinRecord("123456".toCharArray()),
                )
            )
        )
        val c = FamilyModeController(
            store = store,
            settingsSource = settingsSource,
            scope = CoroutineScope(dispatcher + SupervisorJob()),
            crypto = crypto,
            clock = { 0L },
        )

        assertTrue(runBlocking { c.verifyPin("123456".toCharArray()) })
        assertTrue(settingsSource.isManagementAllowed())

        c.completeManagement()
        assertFalse(c.state.value.isManagementAllowed)
        assertFalse(settingsSource.isManagementAllowed())

        dispatcher.runAll()
        assertFalse(c.state.value.isManagementAllowed)
        assertFalse(settingsSource.isManagementAllowed())
    }

    private fun awaitShutdownCompleted(c: FamilyModeController): FamilyWebShutdownState = runBlocking {
        withTimeout(5_000) { c.webShutdown.first { !it.inProgress && (it.completed || it.timedOut) } }
    }
}
