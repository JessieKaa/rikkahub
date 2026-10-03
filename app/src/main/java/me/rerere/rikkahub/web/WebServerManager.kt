package me.rerere.rikkahub.web

import android.content.Context
import android.util.Log
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.web.startWebServer
import java.net.ServerSocket

private const val TAG = "WebServerManager"
private const val HOST_ALL_INTERFACES = "0.0.0.0"
private const val HOST_LOOPBACK = "127.0.0.1"

data class WebServerState(
    val isRunning: Boolean = false,
    val isLoading: Boolean = false,
    val port: Int = 8080,
    val serviceName: String = DEFAULT_SERVICE_NAME,
    val localhostOnly: Boolean = false,
    val hostname: String? = null,
    val address: String? = null,
    val error: String? = null
)

class WebServerManager(
    private val context: Context,
    private val appScope: AppScope,
    private val chatService: ChatService,
    private val conversationRepo: ConversationRepository,
    private val folderRepo: FolderRepository,
    private val settingsStore: SettingsStore,
    private val filesManager: FilesManager
) {
    private val lifecycle = WebServerLifecycleController(appScope)

    @Volatile
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val nsdRegistrar = NsdServiceRegistrar(context)

    @Volatile
    private var stopping = false

    /**
     * Latest family-mode access gate. Fail-closed until [setAccessCheck] wires it to the
     * family-mode controller, so no code path can start the engine without an explicit
     * STANDARD/ADMIN_UNLOCKED decision.
     */
    @Volatile
    private var accessCheck: () -> Boolean = { false }

    private val _state = MutableStateFlow(WebServerState())
    val state: StateFlow<WebServerState> = _state.asStateFlow()

    /**
     * Bind the latest access decision. The root owner registers this with the
     * family-mode controller (`{ controller.state.value.isWebStartAllowed }`).
     */
    fun setAccessCheck(check: () -> Boolean) {
        accessCheck = check
    }

    /** Latest access decision, evaluated on every call (used by WebServerService guards). */
    fun isWebStartAllowed(): Boolean = evaluateAccess()

    /** Management HTTP gate: access allowed, engine running and not in the middle of stopping. */
    internal fun isManagementAllowed(): Boolean = evaluateAccess() && server != null && !stopping

    private fun evaluateAccess(): Boolean = runCatching { accessCheck() }.getOrDefault(false)

    fun start(
        port: Int = 8080,
        serviceName: String = DEFAULT_SERVICE_NAME,
        localhostOnly: Boolean = false
    ) {
        lifecycle.launchStart(
            open = { token -> openEngine(token, port, serviceName, localhostOnly) },
            finish = { token, opened -> onStartFinished(token, opened) },
        )
    }

    /**
     * Await a single start attempt and return its explicit outcome. Used by
     * WebServerService so it can stop the foreground service on a real failure rather than
     * inferring it from intermediate state emissions.
     */
    suspend fun startAndAwait(
        port: Int = 8080,
        serviceName: String = DEFAULT_SERVICE_NAME,
        localhostOnly: Boolean = false
    ): Boolean {
        return lifecycle.startAndAwait { token ->
            openEngine(token, port, serviceName, localhostOnly)
        }
    }

    fun reportError(message: String) {
        _state.value = _state.value.copy(isRunning = false, isLoading = false, error = message)
    }

    fun stop() {
        appScope.launch {
            stopAndAwait()
        }
    }

    /**
     * Stop the engine and only return after it is closed (or failed to close). Callers can
     * keep the UI in a transition/recovery state when this returns false.
     */
    suspend fun stopAndAwait(): Boolean {
        return lifecycle.stopAndAwait { closeEngine() }
    }

    fun restart(
        port: Int = _state.value.port,
        serviceName: String = _state.value.serviceName,
        localhostOnly: Boolean = _state.value.localhostOnly
    ) {
        appScope.launch {
            restartAndAwait(port, serviceName, localhostOnly)
        }
    }

    /**
     * Completion-aware, serialized restart. Pending starts are cancelled and the old
     * engine is closed before the new one is opened.
     */
    suspend fun restartAndAwait(
        port: Int = _state.value.port,
        serviceName: String = _state.value.serviceName,
        localhostOnly: Boolean = _state.value.localhostOnly
    ): Boolean {
        return lifecycle.restartAndAwait(
            stop = { closeEngine() },
            open = { token -> openEngine(token, port, serviceName, localhostOnly) },
        )
    }

    private suspend fun openEngine(
        token: Long,
        port: Int,
        serviceName: String,
        localhostOnly: Boolean
    ): Boolean {
        if (!lifecycle.isCurrent(token)) return false
        if (server != null) {
            // A handle can outlive a failed stop. Only treat it as healthy when the state
            // port is still bound; otherwise clear the stale handle so the next start is not
            // a silent no-op.
            val statePort = _state.value.port
            if (_state.value.isRunning && !isPortAvailable(statePort)) {
                Log.w(TAG, "Server already running")
                return true
            }
            Log.w(TAG, "Clearing stale web server handle (port $statePort is free)")
            server = null
        }

        // 仅本机模式绑定回环地址
        val host = if (localhostOnly) HOST_LOOPBACK else HOST_ALL_INTERFACES
        val baseState = WebServerState(
            port = port,
            serviceName = serviceName,
            localhostOnly = localhostOnly
        )
        _state.value = baseState.copy(isLoading = true)

        if (!evaluateAccess()) {
            Log.w(TAG, "Web server start blocked by current access mode")
            _state.value = baseState.copy(error = "Web server is disabled in the current mode")
            return false
        }
        if (!isPortAvailable(port)) {
            Log.w(TAG, "Port $port is already in use")
            _state.value = baseState.copy(error = "Port $port is already in use")
            return false
        }
        // Revalidate the latest gate immediately before the engine opens so a relock that
        // happened during the port check cannot admit the server.
        if (!lifecycle.isCurrent(token) || !evaluateAccess()) {
            Log.w(TAG, "Web server start cancelled before opening the engine")
            _state.value = baseState.copy(error = "Web server start cancelled")
            return false
        }

        return try {
            Log.i(TAG, "Starting web server on $host:$port")
            val started = startWebServer(port = port, host = host) {
                configureWebApi(
                    context = context,
                    chatService = chatService,
                    conversationRepo = conversationRepo,
                    folderRepo = folderRepo,
                    settingsStore = settingsStore,
                    filesManager = filesManager,
                    isManagementAllowed = ::isManagementAllowed,
                )
            }.start(wait = false)
            server = started
            _state.value = baseState.copy(isRunning = true)
            // 仅局域网模式注册 mDNS
            if (!localhostOnly) {
                try {
                    nsdRegistrar.register(
                        port = port,
                        serviceName = serviceName,
                        onRegistered = { info ->
                            _state.value = _state.value.copy(
                                serviceName = info.serviceName,
                                hostname = info.hostname,
                                address = info.address.hostAddress
                            )
                        }
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "NSD register failed", e)
                }
            }
            Log.i(TAG, "Web server started successfully on $host:$port")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start web server", e)
            server = null
            _state.value = baseState.copy(error = e.message)
            false
        }
    }

    private fun onStartFinished(token: Long, opened: Boolean) {
        if (!lifecycle.isCurrent(token)) return
        if (!opened && !_state.value.isRunning) {
            _state.value = _state.value.copy(isLoading = false)
        }
    }

    private suspend fun closeEngine(): Boolean {
        val port = _state.value.port
        stopping = true
        _state.value = _state.value.copy(isLoading = true, error = null)
        val closed = try {
            attemptStopEngine(port)
        } finally {
            stopping = false
        }
        if (!closed) {
            // Closure is unconfirmed: keep the handle and the running flag so a later stop
            // can retry, and leave an explicit error for the transition/recovery UI.
            Log.w(TAG, "Web server shutdown not confirmed")
            _state.value = _state.value.copy(isLoading = false, error = "Web server did not stop")
            return false
        }
        val nsdError = try {
            nsdRegistrar.unregister()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "NSD unregister failed", e)
            e.message
        }
        _state.value = _state.value.copy(
            isRunning = false,
            isLoading = false,
            hostname = null,
            address = null,
            error = nsdError,
        )
        Log.i(TAG, "Web server stopped")
        // Engine/port closure is confirmed here; NSD cleanup is reported separately via
        // state.error and must not mask a successful stop.
        return true
    }

    /**
     * Best-effort engine shutdown with explicit confirmation.
     *
     * Returns true only when closure is confirmed: either the engine's `stop` returned or
     * the port is free after a failed `stop` (the handle may be stale). A false result
     * keeps the handle for a later retry instead of claiming the port is closed.
     */
    private fun attemptStopEngine(port: Int): Boolean {
        val handle = server ?: return true
        repeat(2) { attempt ->
            try {
                Log.i(TAG, "Stopping web server (attempt ${attempt + 1})")
                handle.stop(1000, 2000)
                server = null
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop web server (attempt ${attempt + 1})", e)
            }
        }
        // The handle reported failure. If the port is free the engine did close; clear the
        // stale handle so the next start is not a silent no-op. Otherwise keep it and let
        // the caller report failure.
        return if (isPortAvailable(port)) {
            Log.w(TAG, "Web server handle failed but port $port is free; treating as closed")
            server = null
            true
        } else {
            false
        }
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (e: Exception) {
            false
        }
    }
}
