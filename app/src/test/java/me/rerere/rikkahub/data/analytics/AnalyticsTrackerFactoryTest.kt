package me.rerere.rikkahub.data.analytics

import android.os.Bundle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AnalyticsTrackerFactoryTest {

    @Test
    fun `named non-default app is not treated as default configured`() {
        assertFalse(
            AnalyticsTrackerFactory.isDefaultFirebaseAppInitialized(listOf("named-app"))
        )
    }

    @Test
    fun `no initialized apps is not treated as default configured`() {
        assertFalse(AnalyticsTrackerFactory.isDefaultFirebaseAppInitialized(emptyList()))
    }

    @Test
    fun `default app is recognized even alongside named apps`() {
        assertTrue(
            AnalyticsTrackerFactory.isDefaultFirebaseAppInitialized(
                listOf("named-app", DEFAULT_APP_NAME)
            )
        )
    }

    @Test
    fun `create returns no-op without touching the provider when default is absent`() {
        var providerCalled = false
        val tracker = AnalyticsTrackerFactory.create(
            isDefaultFirebaseAppInitialized = false,
            defaultTrackerProvider = {
                providerCalled = true
                fail("default tracker provider must not be called")
                NoOpAnalyticsTracker
            },
        )

        assertSame(NoOpAnalyticsTracker, tracker)
        assertFalse(providerCalled)
    }

    @Test
    fun `create returns the provided tracker when the default app is configured`() {
        val real = RecordingTracker()
        val tracker = AnalyticsTrackerFactory.create(
            isDefaultFirebaseAppInitialized = true,
            defaultTrackerProvider = { real },
        )

        assertSame(real, tracker)
    }

    @Test
    fun `no-op tracker accepts events without an Android runtime`() {
        NoOpAnalyticsTracker.logEvent("ai_send_message")
        NoOpAnalyticsTracker.logEvent("ai_send_message", null)
    }

    private class RecordingTracker : AnalyticsTracker {
        override fun logEvent(name: String, params: Bundle?) = Unit
    }

    private companion object {
        // Mirrors the SDK's public default-app name; keeps this test independent of FirebaseApp.
        const val DEFAULT_APP_NAME = "[DEFAULT]"
    }
}
