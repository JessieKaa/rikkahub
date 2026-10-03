package me.rerere.rikkahub.data.analytics

import com.google.firebase.FirebaseApp

/**
 * Pure, JVM-testable construction logic for [AnalyticsTracker].
 *
 * The real Firebase tracker is only used when the *default* Firebase app is actually
 * initialized. Named (non-default) apps are intentionally ignored so that a stray
 * secondary app never makes us believe analytics is configured.
 */
object AnalyticsTrackerFactory {

    fun isDefaultFirebaseAppInitialized(initializedAppNames: Collection<String>): Boolean =
        initializedAppNames.any { it == FirebaseApp.DEFAULT_APP_NAME }

    fun create(
        isDefaultFirebaseAppInitialized: Boolean,
        defaultTrackerProvider: () -> AnalyticsTracker,
    ): AnalyticsTracker =
        if (isDefaultFirebaseAppInitialized) {
            defaultTrackerProvider()
        } else {
            NoOpAnalyticsTracker
        }
}
