package me.rerere.rikkahub.web

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Serializes Web server start/stop/restart transitions so that family-mode relocking can
 * cancel a pending start instead of racing it.
 *
 * Every transition receives a monotonically increasing token. Stop and restart bump the
 * token, which makes any start still waiting for the lifecycle lock stale; the start body
 * is expected to re-check [isCurrent] and the latest access gate immediately before it
 * opens the engine. Stop and restart only report completion after their `stop`/`open`
 * callbacks return, so callers observe the real engine shutdown rather than the mere
 * intention to shut down.
 *
 * This class has no Android dependencies so the race behaviour can be unit-tested with a
 * fake engine.
 */
internal class WebServerLifecycleController(
    private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    private val tokenSource = AtomicLong(0L)

    @Volatile
    private var pendingStart: Job? = null

    /** Advance the transition token and return the new value. */
    fun nextToken(): Long = tokenSource.incrementAndGet()

    /** True when [token] is still the latest transition token. */
    fun isCurrent(token: Long): Boolean = tokenSource.get() == token

    /**
     * Launch an asynchronous start under a fresh token. [open] runs while the lifecycle
     * lock is held and returns true when the engine is open; [finish] is always invoked
     * exactly once with the outcome, including when the producer is cancelled.
     */
    fun launchStart(
        open: suspend (token: Long) -> Boolean,
        finish: (token: Long, opened: Boolean) -> Unit,
    ) {
        val token = nextToken()
        val completed = AtomicBoolean(false)

        fun complete(opened: Boolean) {
            if (completed.compareAndSet(false, true)) {
                finish(token, opened)
            }
        }

        val job = scope.launch {
            try {
                val opened = lock.withLock {
                    if (!isCurrent(token)) false else open(token)
                }
                complete(opened)
            } catch (e: CancellationException) {
                // The producer was cancelled (stop/restart/rescope). Deliver a terminal
                // outcome so callers do not keep a stale loading state, then propagate.
                complete(false)
                throw e
            } catch (e: Throwable) {
                complete(false)
                throw e
            }
        }
        // Covers cancellation before the body is ever dispatched: the body never runs, so
        // the exactly-once hook has to deliver the terminal outcome here.
        job.invokeOnCompletion { complete(false) }
        pendingStart = job
    }

    /**
     * Suspend until a single start attempt completes. Used by WebServerService so it can
     * react to the explicit open result instead of inferring failure from intermediate
     * StateFlow emissions. Cancellation from the caller propagates.
     */
    suspend fun startAndAwait(open: suspend (token: Long) -> Boolean): Boolean {
        val token = nextToken()
        return lock.withLock {
            if (!isCurrent(token)) false else open(token)
        }
    }

    /**
     * Cancel/await any pending start and then close the engine while holding the lifecycle
     * lock. Returns the result of [stop].
     */
    suspend fun stopAndAwait(stop: suspend () -> Boolean): Boolean {
        nextToken()
        val pending = pendingStart
        pendingStart = null
        pending?.cancelAndJoin()
        return lock.withLock { stop() }
    }

    /**
     * Serialize a stop followed by a start. Any pending start is cancelled first and the
     * new start only opens when the token is still current and [open] accepts it.
     */
    suspend fun restartAndAwait(
        stop: suspend () -> Boolean,
        open: suspend (token: Long) -> Boolean,
    ): Boolean {
        val token = nextToken()
        val pending = pendingStart
        pendingStart = null
        pending?.cancelAndJoin()
        return lock.withLock {
            if (!stop()) return@withLock false
            if (!isCurrent(token)) return@withLock false
            open(token)
        }
    }
}
