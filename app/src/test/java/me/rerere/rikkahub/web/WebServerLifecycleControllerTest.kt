package me.rerere.rikkahub.web

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/**
 * Focused tests for the serialized web-server lifecycle used by family-mode isolation.
 *
 * They pin pending-start cancellation, explicit start outcomes, completion-aware stop and
 * serialized restart behaviour that WebServerManager relies on when relocking cancels an
 * in-flight start.
 */
class WebServerLifecycleControllerTest {

    private class Rig {
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() +
                Dispatchers.Unconfined +
                CoroutineExceptionHandler { _, error -> errors += error }
        )
        val controller = WebServerLifecycleController(scope)

        fun close() {
            scope.cancel()
        }
    }

    /** Dispatcher that queues work until [runAll] is called, so a job can be cancelled pre-entry. */
    private class PausedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }

        fun runAll() {
            while (queue.isNotEmpty()) {
                queue.removeFirst().run()
            }
        }
    }

    @Test(timeout = 15_000)
    fun `launchStart opens the engine and finishes with the outcome`() {
        val rig = Rig()
        try {
            val events = mutableListOf<String>()

            rig.controller.launchStart(
                open = { token ->
                    events += "open:$token"
                    true
                },
                finish = { token, opened -> events += "finish:$token:$opened" },
            )

            assertEquals(listOf("open:1", "finish:1:true"), events)
            assertTrue(rig.controller.isCurrent(1L))
            assertFalse(rig.controller.isCurrent(2L))
        } finally {
            rig.close()
        }
    }

    @Test(timeout = 15_000)
    fun `launchStart reports failure and does not swallow a non-cancellation open error`() {
        val rig = Rig()
        try {
            val outcomes = mutableListOf<String>()

            rig.controller.launchStart(
                open = { throw IllegalStateException("boom") },
                finish = { token, opened -> outcomes += "$token:$opened" },
            )

            assertEquals(listOf("1:false"), outcomes)
            assertTrue(rig.errors.single() is IllegalStateException)
        } finally {
            rig.close()
        }
    }

    @Test(timeout = 15_000)
    fun `startAndAwait propagates an open failure to the caller`() = runBlocking {
        val rig = Rig()
        try {
            val error = runCatching {
                rig.controller.startAndAwait { throw IllegalStateException("boom") }
            }.exceptionOrNull()

            assertTrue(error is IllegalStateException)
        } finally {
            rig.close()
        }
    }

    @Test(timeout = 15_000)
    fun `startAndAwait propagates caller cancellation while opening`() = runBlocking {
        val rig = Rig()
        try {
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()

            val job = launch {
                rig.controller.startAndAwait {
                    entered.complete(Unit)
                    gate.await()
                    true
                }
            }

            entered.await()
            job.cancelAndJoin()

            assertTrue(job.isCancelled)
        } finally {
            rig.close()
        }
    }

    @Test(timeout = 15_000)
    fun `stop cancels a pending start and finishes its outcome`() = runBlocking {
        val rig = Rig()
        try {
            val blocker = CompletableDeferred<Unit>()
            val opened = mutableListOf<String>()
            val finished = mutableListOf<String>()

            // First start holds the lifecycle lock while suspended.
            rig.controller.launchStart(
                open = { opened += "first"; blocker.await(); true },
                finish = { token, value -> finished += "first:$token:$value" },
            )
            // Second start is queued behind the lock and must be cancelled by stop.
            rig.controller.launchStart(
                open = { opened += "second"; true },
                finish = { token, value -> finished += "second:$token:$value" },
            )

            // Begin stop immediately (UNDISPATCHED) so its token bump and pending-start
            // cancellation happen before the held lock is released; otherwise the queued
            // start could win the race and open.
            val stop = async(start = CoroutineStart.UNDISPATCHED) {
                rig.controller.stopAndAwait { opened += "stop"; true }
            }

            blocker.complete(Unit)

            assertTrue(stop.await())
            assertEquals(listOf("first", "stop"), opened)
            assertEquals(setOf("first:1:true", "second:2:false"), finished.toSet())
        } finally {
            rig.close()
        }
    }

    @Test(timeout = 15_000)
    fun `a start cancelled before dispatch still finishes exactly once`() = runBlocking {
        val paused = PausedDispatcher()
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() + paused + CoroutineExceptionHandler { _, error -> errors += error }
        )
        try {
            val controller = WebServerLifecycleController(scope)
            val outcomes = mutableListOf<String>()

            controller.launchStart(
                open = { outcomes += "open"; true },
                finish = { token, opened -> outcomes += "finish:$opened" },
            )

            // Admit stop synchronously (UNDISPATCHED) so it bumps the epoch and begins
            // cancelling the queued producer. The producer's cancellation dispatch needs the
            // paused dispatcher to run, so pump it before awaiting the stop outcome.
            val stop = async(start = CoroutineStart.UNDISPATCHED) {
                controller.stopAndAwait { true }
            }
            paused.runAll()

            assertTrue(stop.await())
            assertEquals(listOf("finish:false"), outcomes)
            assertTrue(errors.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test(timeout = 15_000)
    fun `stop reports the engine close result`() = runBlocking {
        val rig = Rig()
        try {
            assertTrue(rig.controller.stopAndAwait { true })
            assertFalse(rig.controller.stopAndAwait { false })
        } finally {
            rig.close()
        }
    }

    @Test(timeout = 15_000)
    fun `a failed open does not block a later start`() = runBlocking {
        val rig = Rig()
        try {
            val failed = runCatching {
                rig.controller.startAndAwait { throw IllegalStateException("boom") }
            }
            assertTrue(failed.isFailure)

            assertTrue(rig.controller.startAndAwait { true })
        } finally {
            rig.close()
        }
    }

    @Test(timeout = 15_000)
    fun `restart stops the old engine before opening the new one and cancels pending starts`() = runBlocking {
        val rig = Rig()
        try {
            val blocker = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()

            rig.controller.launchStart(
                open = { events += "old-open"; blocker.await(); true },
                finish = { _, _ -> },
            )
            rig.controller.launchStart(
                open = { events += "queued-open"; true },
                finish = { _, _ -> },
            )

            val restart = async(start = CoroutineStart.UNDISPATCHED) {
                rig.controller.restartAndAwait(
                    stop = { events += "stop"; true },
                    open = { events += "new-open"; true },
                )
            }

            blocker.complete(Unit)

            assertTrue(restart.await())
            assertEquals(listOf("old-open", "stop", "new-open"), events)
        } finally {
            rig.close()
        }
    }

    @Test(timeout = 15_000)
    fun `restart aborts the new open when the stop fails`() = runBlocking {
        val rig = Rig()
        try {
            val events = mutableListOf<String>()

            val result = rig.controller.restartAndAwait(
                stop = { events += "stop"; false },
                open = { events += "new-open"; true },
            )

            assertFalse(result)
            assertEquals(listOf("stop"), events)
        } finally {
            rig.close()
        }
    }

    @Test(timeout = 15_000)
    fun `restart propagates a throwing stop without opening the new engine`() = runBlocking {
        val rig = Rig()
        try {
            val events = mutableListOf<String>()

            val error = runCatching {
                rig.controller.restartAndAwait(
                    stop = { events += "stop"; throw IllegalStateException("stop failed") },
                    open = { events += "new-open"; true },
                )
            }.exceptionOrNull()

            assertTrue(error is IllegalStateException)
            assertEquals(listOf("stop"), events)
        } finally {
            rig.close()
        }
    }
}
