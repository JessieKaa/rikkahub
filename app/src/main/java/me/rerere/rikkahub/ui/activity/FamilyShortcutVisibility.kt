package me.rerere.rikkahub.ui.activity

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.familymode.FamilyAccessLevel
import me.rerere.rikkahub.data.familymode.FamilyModeState

/**
 * Dynamic visibility for the translator / image-generation launcher shortcuts.
 *
 * These two shortcuts are published as *dynamic* shortcuts (never manifest/static) because Android
 * rejects API enable/disable of manifest shortcut ids (“Manifest shortcut ID=… may not be
 * manipulated via APIs”). The static `camera` shortcut in `res/xml/shortcuts.xml` is untouched and
 * stays available in every access level.
 *
 * - STANDARD / ADMIN_UNLOCKED: publish the dynamic shortcuts and re-enable any pinned instances.
 * - LOADING / FAMILY_LOCKED / RECOVERY_LOCKED: remove them from the dynamic list and disable any
 *   pinned instances.
 *
 * Uses [ShortcutManagerCompat.addDynamicShortcuts] (not `setDynamicShortcuts`) so unrelated
 * dynamic shortcuts published by other features are preserved.
 *
 * Migration limitation: installs that previously pinned the legacy manifest ids
 * (`translator` / `image_gen`) may keep those launcher tiles. They are not manipulated here because
 * older ROMs still treat them as immutable; their intents remain gated by the root family-mode
 * navigation policy.
 *
 * The root owner (`RikkaHubApp`) calls [updateFamilyShortcutVisibility] whenever
 * `FamilyModeController.state` emits; that call signature is stable.
 */
object FamilyShortcutVisibility {

    private const val TAG = "FamilyShortcutVisibility"
    private const val ACTION_TRANSLATE = "me.rerere.rikkahub.action.TRANSLATE"
    private const val ACTION_IMAGE_GEN = "me.rerere.rikkahub.action.IMAGE_GEN"
    private const val DISABLED_MESSAGE = "家人模式中不可用"

    /** New dynamic shortcut ids; deliberately different from the removed manifest ids. */
    const val ID_TRANSLATOR = "dynamic_translator"
    const val ID_IMAGE_GEN = "dynamic_image_gen"

    /** Dynamic shortcut ids that are hidden outside standard/admin access. */
    val dynamicNonChatShortcutIds: List<String> = listOf(ID_TRANSLATOR, ID_IMAGE_GEN)

    /** Whether the non-chat shortcuts should be enabled for [state]. */
    fun areNonChatShortcutsEnabled(state: FamilyModeState): Boolean =
        state.accessLevel == FamilyAccessLevel.STANDARD ||
            state.accessLevel == FamilyAccessLevel.ADMIN_UNLOCKED

    fun updateFamilyShortcutVisibility(context: Context, state: FamilyModeState) {
        if (areNonChatShortcutsEnabled(state)) {
            publishDynamicShortcuts(context)
        } else {
            hideDynamicShortcuts(context)
        }
    }

    /** Builds the dynamic shortcuts equivalent to the former static XML declarations. */
    fun dynamicShortcuts(context: Context): List<ShortcutInfoCompat> = listOf(
        ShortcutInfoCompat.Builder(context, ID_TRANSLATOR)
            .setShortLabel(context.getString(R.string.translator_page_title))
            .setLongLabel(context.getString(R.string.translator_page_title))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_translate))
            .setIntent(appIntent(context, ACTION_TRANSLATE))
            .build(),
        ShortcutInfoCompat.Builder(context, ID_IMAGE_GEN)
            .setShortLabel(context.getString(R.string.imggen_page_title))
            .setLongLabel(context.getString(R.string.imggen_page_title))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_image_generation))
            .setIntent(appIntent(context, ACTION_IMAGE_GEN))
            .build(),
    )

    private fun publishDynamicShortcuts(context: Context) {
        val shortcuts = dynamicShortcuts(context)
        runCatching {
            ShortcutManagerCompat.addDynamicShortcuts(context, shortcuts)
            // Re-enable any pinned instance that was disabled while family mode was locked.
            ShortcutManagerCompat.enableShortcuts(context, shortcuts)
        }.onFailure {
            Log.e(TAG, "Failed to publish family dynamic shortcuts", it)
        }
    }

    private fun hideDynamicShortcuts(context: Context) {
        val ids = dynamicNonChatShortcutIds
        runCatching {
            // Drop from the dynamic list; pinned instances survive and are disabled next.
            ShortcutManagerCompat.removeDynamicShortcuts(context, ids)
            ShortcutManagerCompat.disableShortcuts(context, ids, DISABLED_MESSAGE)
        }.onFailure {
            Log.e(TAG, "Failed to hide family dynamic shortcuts", it)
        }
    }

    /**
     * Explicit component built from the current `packageName` (applicationId) so debug/release
     * builds target the correct [RouteActivity].
     */
    private fun appIntent(context: Context, action: String): Intent =
        Intent().apply {
            setClassName(context.packageName, RouteActivity::class.java.name)
            this.action = action
        }
}
