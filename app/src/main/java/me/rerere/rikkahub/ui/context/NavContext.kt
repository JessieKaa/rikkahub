package me.rerere.rikkahub.ui.context

import androidx.compose.runtime.compositionLocalOf
import androidx.navigation3.runtime.NavKey
import me.rerere.rikkahub.Screen

/**
 * Centralized destination permission check for every navigation entry point.
 *
 * Implementations MUST read the *latest* family access state when [isAllowed] is
 * invoked (for example by delegating to a `StateFlow.value` or a
 * `FamilyModePolicy` call), not capture a snapshot at construction time. This is
 * what keeps `navigate`, `clearAndNavigate`, restored stacks, intents and the
 * render boundary consistent while an admin session expires or the app is
 * re-locked.
 *
 * The default implementation allows everything so callers that have not been
 * wired into family mode keep their original standard behaviour.
 */
fun interface NavigationGate {
    fun isAllowed(screen: Screen): Boolean
}

/** Gate that allows every destination; standard-mode behaviour. */
val AllowAllNavigationGate = NavigationGate { true }

/**
 * Pure, testable stack sanitation shared by [Navigator] and the family-mode
 * render boundary.
 *
 * It drops every destination rejected by [isAllowed] while preserving order. A
 * navigation stack must never be empty, so when nothing survives the supplied
 * [fallbackRoot] becomes the only entry. The fallback root is assumed to be
 * allowed by the caller (the family chat root / standard home).
 */
object NavigationStackSanitizer {
    fun sanitize(
        stack: List<Screen>,
        isAllowed: (Screen) -> Boolean,
        fallbackRoot: Screen,
    ): List<Screen> {
        val allowed = stack.filter(isAllowed)
        return allowed.ifEmpty { listOf(fallbackRoot) }
    }
}

/**
 * FIFO buffer for navigation payloads that arrive before the app is ready
 * (settings + family mode loaded). Kept free of Android types so it can be
 * unit-tested.
 */
class PendingNavigationQueue<T> {
    private val items = ArrayDeque<T>()

    val size: Int get() = items.size

    fun isEmpty(): Boolean = items.isEmpty()

    /**
     * Adds [item] at the tail. When [distinct] is true an identical payload that
     * is still queued is ignored, so the same share intent delivered on both the
     * cold and warm path is only consumed once.
     */
    fun enqueue(item: T, distinct: Boolean = false) {
        if (distinct && items.contains(item)) return
        items.addLast(item)
    }

    fun enqueueFirst(item: T) {
        items.addFirst(item)
    }

    fun drain(): List<T> {
        if (items.isEmpty()) return emptyList()
        val drained = items.toList()
        items.clear()
        return drained
    }

    fun clear() {
        items.clear()
    }
}

/**
 * Navigation controller that guards every mutating operation with the latest
 * [NavigationGate] decision.
 *
 * @param backStack the live Navigation3 back stack; all app entries are [Screen].
 * @param gate latest-state permission check. Defaults to allow-all so existing
 *   call sites and tests keep working unchanged.
 */
class Navigator(
    private val backStack: MutableList<NavKey>,
    private val gate: NavigationGate = AllowAllNavigationGate,
) {
    fun navigate(screen: Screen, builder: NavigateOptionsBuilder.() -> Unit = {}) {
        if (!gate.isAllowed(screen)) return

        val options = NavigateOptionsBuilder().apply(builder)

        options.popUpToScreen?.let { target ->
            val targetIndex = backStack.indexOfLast { it == target }
            if (targetIndex != -1) {
                val removeFromIndex = if (options.popUpToInclusive) targetIndex else targetIndex + 1
                repeat(backStack.size - removeFromIndex) {
                    backStack.removeLastOrNull()
                }
            }
        }

        if (options.launchSingleTop && backStack.lastOrNull() == screen) {
            return
        }

        backStack.add(screen)
    }

    fun clearAndNavigate(screen: Screen) {
        if (!gate.isAllowed(screen)) return
        backStack.clear()
        backStack.add(screen)
    }

    fun popBackStack() {
        if (backStack.size > 1) backStack.removeLastOrNull()
    }

    /** Screens currently on the stack, in order. Non-[Screen] keys are ignored. */
    fun currentScreens(): List<Screen> = backStack.filterIsInstance<Screen>()

    fun lastScreen(): Screen? = backStack.lastOrNull() as? Screen

    /**
     * Sanitizes the entire stack in place against the latest gate decision and
     * guarantees a non-empty stack rooted at [fallbackRoot].
     *
     * Used on re-lock, admin-session timeout and process restore, before the
     * destinations are rendered, so a stale management entry can never flash on
     * screen.
     *
     * @return true when the stack was modified.
     */
    fun sanitizeInPlace(fallbackRoot: Screen): Boolean {
        val current = currentScreens()
        val sanitized = NavigationStackSanitizer.sanitize(
            stack = current,
            isAllowed = gate::isAllowed,
            fallbackRoot = fallbackRoot,
        )
        if (sanitized == current && backStack.size == current.size) return false
        backStack.clear()
        backStack.addAll(sanitized)
        return true
    }

    /**
     * Replaces the whole stack with a pre-sanitized list of screens. Entries are
     * filtered through the gate; when nothing remains (or [screens] is empty)
     * the stack is left untouched so the caller can retry after the state is
     * ready.
     *
     * @return true when the stack was modified.
     */
    fun replaceStack(screens: List<Screen>): Boolean {
        val sanitized = screens.filter(gate::isAllowed)
        if (sanitized.isEmpty()) return false
        backStack.clear()
        backStack.addAll(sanitized)
        return true
    }
}

class NavigateOptionsBuilder {
    internal var popUpToScreen: Screen? = null
    internal var popUpToInclusive: Boolean = false
    var launchSingleTop: Boolean = false

    fun popUpTo(screen: Screen, builder: PopUpToBuilder.() -> Unit = {}) {
        val options = PopUpToBuilder().apply(builder)
        popUpToScreen = screen
        popUpToInclusive = options.inclusive
    }
}

class PopUpToBuilder {
    var inclusive: Boolean = false
}

val LocalNavController = compositionLocalOf<Navigator> {
    error("No Navigator provided")
}
