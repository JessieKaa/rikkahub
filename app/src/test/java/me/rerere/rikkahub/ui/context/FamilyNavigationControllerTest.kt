package me.rerere.rikkahub.ui.context

import me.rerere.rikkahub.FamilyChatOwnership
import me.rerere.rikkahub.RecentShareSignatureWindow
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.familyShareChatDestination
import me.rerere.rikkahub.resolveFamilyChatOwnership
import me.rerere.rikkahub.utils.base64Decode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused unit tests for the centralized family-mode navigation gate.
 *
 * These cover the pure gate/stack logic that the router relies on so that
 * family-mode re-locking cannot leave a management destination reachable,
 * whether the stack is built by [Navigator.navigate]/[Navigator.clearAndNavigate]
 * or restored/replaced at the render boundary.
 */
class FamilyNavigationControllerTest {

    private val familyChat = Screen.Chat(id = "11111111-1111-1111-1111-111111111111")
    private val otherChat = Screen.Chat(id = "22222222-2222-2222-2222-222222222222")

    private fun mutableGate(isAllowed: (Screen) -> Boolean): NavigationGate = NavigationGate(isAllowed)

    // region gate

    @Test
    fun `default gate allows navigation for unchanged callers`() {
        val stack = mutableListOf<androidx.navigation3.runtime.NavKey>(familyChat)
        val navigator = Navigator(stack)

        navigator.navigate(Screen.Setting)

        assertEquals(listOf(familyChat, Screen.Setting), stack.toList())
    }

    @Test
    fun `denied destination is a no-op for navigate and clearAndNavigate`() {
        val stack = mutableListOf<androidx.navigation3.runtime.NavKey>(familyChat)
        val allowed = setOf<Screen>(familyChat)
        val navigator = Navigator(stack, mutableGate { it in allowed })

        navigator.navigate(Screen.Setting)
        navigator.navigate(Screen.Assistant)
        assertEquals(listOf(familyChat), stack.toList())

        navigator.clearAndNavigate(Screen.Backup)
        assertEquals(listOf(familyChat), stack.toList())
    }

    @Test
    fun `gate reads latest state at call time`() {
        var locked = true
        val stack = mutableListOf<androidx.navigation3.runtime.NavKey>(familyChat)
        val navigator = Navigator(stack, mutableGate { !locked })

        navigator.navigate(Screen.Setting)
        assertFalse(stack.contains(Screen.Setting))

        locked = false
        navigator.navigate(Screen.Setting)
        assertTrue(stack.contains(Screen.Setting))
    }

    @Test
    fun `denied destination does not pop the existing stack`() {
        val stack = mutableListOf<androidx.navigation3.runtime.NavKey>(
            familyChat,
            Screen.History,
        )
        val navigator = Navigator(stack, mutableGate { it is Screen.Chat })

        navigator.navigate(Screen.Setting)

        // popUpTo must not run for a denied target; the stack is untouched.
        assertEquals(listOf(familyChat, Screen.History), stack.toList())
    }

    // endregion

    // region sanitizer

    @Test
    fun `sanitizer drops denied entries while preserving allowed order`() {
        val allowed = setOf<Screen>(familyChat, Screen.History, Screen.MessageSearch)
        val sanitized = NavigationStackSanitizer.sanitize(
            stack = listOf(familyChat, Screen.Setting, Screen.History, Screen.Backup),
            isAllowed = { it in allowed },
            fallbackRoot = familyChat,
        )

        assertEquals(listOf(familyChat, Screen.History), sanitized)
    }

    @Test
    fun `sanitizer falls back to the safe root when nothing is allowed`() {
        val sanitized = NavigationStackSanitizer.sanitize(
            stack = listOf(Screen.Setting, Screen.Backup),
            isAllowed = { false },
            fallbackRoot = familyChat,
        )

        assertEquals(listOf(familyChat), sanitized)
    }

    @Test
    fun `sanitizer keeps an already-safe family stack unchanged`() {
        val allowed = setOf<Screen>(familyChat, Screen.History)
        val stack = listOf(familyChat, Screen.History)
        val sanitized = NavigationStackSanitizer.sanitize(
            stack = stack,
            isAllowed = { it in allowed },
            fallbackRoot = familyChat,
        )

        assertEquals(stack, sanitized)
    }

    // endregion

    // region Navigator sanitize / replace

    @Test
    fun `sanitizeInPlace rewrites a mixed stack and reports change`() {
        val stack = mutableListOf<androidx.navigation3.runtime.NavKey>(
            familyChat,
            Screen.Setting,
            Screen.History,
        )
        val navigator = Navigator(stack, mutableGate { it is Screen.Chat || it == Screen.History })

        val changed = navigator.sanitizeInPlace(familyChat)

        assertTrue(changed)
        assertEquals(listOf(familyChat, Screen.History), stack.toList())
    }

    @Test
    fun `sanitizeInPlace resets to family root after relock`() {
        val stack = mutableListOf<androidx.navigation3.runtime.NavKey>(
            Screen.Setting,
            Screen.Backup,
        )
        // Fully locked: only the family chat root is allowed.
        val navigator = Navigator(stack, mutableGate { it == familyChat })

        val changed = navigator.sanitizeInPlace(familyChat)

        assertTrue(changed)
        assertEquals(listOf(familyChat), stack.toList())
    }

    @Test
    fun `sanitizeInPlace reports no change for an already-clean stack`() {
        val stack = mutableListOf<androidx.navigation3.runtime.NavKey>(familyChat, Screen.History)
        val navigator = Navigator(stack, mutableGate { it is Screen.Chat || it == Screen.History })

        assertFalse(navigator.sanitizeInPlace(familyChat))
        assertEquals(listOf(familyChat, Screen.History), stack.toList())
    }

    @Test
    fun `replaceStack filters through the gate`() {
        val stack = mutableListOf<androidx.navigation3.runtime.NavKey>(familyChat)
        val navigator = Navigator(stack, mutableGate { it is Screen.Chat })

        val changed = navigator.replaceStack(listOf(familyChat, Screen.Setting, otherChat))

        assertTrue(changed)
        assertEquals(listOf(familyChat, otherChat), stack.toList())
    }

    @Test
    fun `replaceStack keeps the existing stack when nothing is allowed`() {
        val stack = mutableListOf<androidx.navigation3.runtime.NavKey>(familyChat)
        val navigator = Navigator(stack, mutableGate { it is Screen.Chat })

        val changed = navigator.replaceStack(listOf(Screen.Setting, Screen.Backup))

        assertFalse(changed)
        assertEquals(listOf(familyChat), stack.toList())
    }

    // endregion

    // region pending queue

    @Test
    fun `pending queue drains payloads in arrival order`() {
        val queue = PendingNavigationQueue<Screen>()
        queue.enqueue(Screen.History)
        queue.enqueue(Screen.Favorite)

        assertEquals(2, queue.size)
        assertEquals(listOf(Screen.History, Screen.Favorite), queue.drain())
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `pending queue distinct drops duplicate share payloads`() {
        val queue = PendingNavigationQueue<Screen>()
        val share = Screen.ShareHandler(text = "hello", streamUri = null)

        queue.enqueue(share, distinct = true)
        queue.enqueue(share, distinct = true)

        assertEquals(1, queue.size)
    }

    @Test
    fun `pending queue keeps distinct payloads with different content`() {
        val queue = PendingNavigationQueue<Screen>()
        queue.enqueue(Screen.ShareHandler(text = "hello"), distinct = true)
        queue.enqueue(Screen.ShareHandler(text = "world"), distinct = true)

        assertEquals(2, queue.size)
    }

    @Test
    fun `empty queue drains to an empty list`() {
        val queue = PendingNavigationQueue<Screen>()
        assertTrue(queue.drain().isEmpty())
    }

    // endregion

    // region family chat ownership (render-boundary regression)

    @Test
    fun `new untracked chat is allowed so sharing payload survives`() {
        assertEquals(
            FamilyChatOwnership.ALLOWED,
            resolveFamilyChatOwnership(
                conversationExists = false,
                conversationAssistantId = null,
                familyAssistantId = kotlin.uuid.Uuid.random(),
            ),
        )
    }

    @Test
    fun `owned family chat is allowed`() {
        val family = kotlin.uuid.Uuid.random()
        assertEquals(
            FamilyChatOwnership.ALLOWED,
            resolveFamilyChatOwnership(
                conversationExists = true,
                conversationAssistantId = family,
                familyAssistantId = family,
            ),
        )
    }

    @Test
    fun `foreign assistant chat is rejected before rendering`() {
        assertEquals(
            FamilyChatOwnership.FOREIGN,
            resolveFamilyChatOwnership(
                conversationExists = true,
                conversationAssistantId = kotlin.uuid.Uuid.random(),
                familyAssistantId = kotlin.uuid.Uuid.random(),
            ),
        )
    }

    @Test
    fun `missing family assistant fails safe to foreign`() {
        assertEquals(
            FamilyChatOwnership.FOREIGN,
            resolveFamilyChatOwnership(
                conversationExists = true,
                conversationAssistantId = kotlin.uuid.Uuid.random(),
                familyAssistantId = null,
            ),
        )
    }

    // endregion

    // region share signature window (bounded dedup)

    @Test
    fun `share window suppresses duplicate inside window`() {
        var now = 1_000L
        val window = RecentShareSignatureWindow(windowMs = 2_000L, clock = { now })
        assertTrue(window.tryReserve("a"))
        now += 1_000L
        assertFalse(window.tryReserve("a"))
    }

    @Test
    fun `share window allows identical share after expiry`() {
        var now = 1_000L
        val window = RecentShareSignatureWindow(windowMs = 2_000L, clock = { now })
        assertTrue(window.tryReserve("a"))
        now += 2_001L
        assertTrue(window.tryReserve("a"))
    }

    @Test
    fun `share window allows interleaved identical share A B A`() {
        var now = 1_000L
        val window = RecentShareSignatureWindow(windowMs = 10_000L, clock = { now })
        assertTrue(window.tryReserve("a"))
        now += 10L
        assertTrue(window.tryReserve("b"))
        now += 10L
        assertTrue(window.tryReserve("a"))
    }

    @Test
    fun `share window zero clock suppresses immediate duplicate`() {
        val window = RecentShareSignatureWindow(windowMs = 2_000L, clock = { 0L })
        assertTrue(window.tryReserve("a"))
        assertFalse(window.tryReserve("a"))
    }

    @Test
    fun `share window boundary includes exactly window duration`() {
        var now = 0L
        val window = RecentShareSignatureWindow(windowMs = 2_000L, clock = { now })
        assertTrue(window.tryReserve("a"))
        now = 2_000L
        assertFalse(window.tryReserve("a"))
        now = 2_001L
        assertTrue(window.tryReserve("a"))
    }

    // endregion

    // region family share normalization (real-device crash regression)

    @Test
    fun `family share chat encodes hyphen spaces and unicode once`() {
        val original = "hello-world café 你好 \uD83D\uDE80"
        val chat = familyShareChatDestination(text = original, streamUri = null)

        assertEquals(original, chat.text?.base64Decode())
        assertFalse(chat.text == original) // stored form is encoded, decode is exactly once
    }

    @Test
    fun `family share chat preserves stream uri in files`() {
        val chat = familyShareChatDestination(
            text = "attachment-caption",
            streamUri = "content://media/image/1",
        )

        assertEquals(listOf("content://media/image/1"), chat.files)
        assertEquals("attachment-caption", chat.text?.base64Decode())
    }

    @Test
    fun `family share chat empty text stays null and no files`() {
        val chat = familyShareChatDestination(text = "", streamUri = null)

        assertNull(chat.text)
        assertTrue(chat.files.isEmpty())
    }

    @Test
    fun `family share chat payload survives exactly one encode decode roundtrip`() {
        val original = "a-b c\n\tline2 \u00E9\u00E8 \u4F60\u597D"
        val chat = familyShareChatDestination(text = original, streamUri = null)

        // Double encoding would make the first decode yield a base64 layer, not the original.
        assertEquals(original, chat.text?.base64Decode())
    }

    // endregion
}
