package me.rerere.rikkahub.data.analytics

import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * App-owned analytics seam.
 *
 * Production builds delegate to Firebase Analytics. Private/debug builds that do not
 * configure Firebase receive [NoOpAnalyticsTracker] instead, so the chat flow never
 * touches an uninitialized Firebase default app.
 */
interface AnalyticsTracker {
    fun logEvent(name: String, params: Bundle? = null)
}

/** No-op implementation used when no default Firebase app is configured. */
object NoOpAnalyticsTracker : AnalyticsTracker {
    override fun logEvent(name: String, params: Bundle?) = Unit
}

/** Delegates to the existing Firebase Analytics SDK. */
class FirebaseAnalyticsTracker(
    private val analytics: FirebaseAnalytics,
) : AnalyticsTracker {
    override fun logEvent(name: String, params: Bundle?) {
        analytics.logEvent(name, params)
    }
}
