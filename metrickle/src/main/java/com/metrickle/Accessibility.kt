package com.metrickle

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo

/**
 * Reads `context.a11y` flags (see the table in `docs/NATIVE_SDKS.md`) at start, whenever a
 * setting changes, and on foreground. Switch Access is reported as `keyboard` (non-pointer navigation).
 */
internal class AccessibilityObserver(private val context: Context, private val onChange: (List<String>) -> Unit) {
    private val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    private val main = Handler(Looper.getMainLooper())
    private var last: List<String>? = null

    fun start() {
        refresh()
        am?.addTouchExplorationStateChangeListener { refresh() }
        am?.addAccessibilityStateChangeListener { refresh() }
        val observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        val resolver = context.contentResolver
        runCatching {
            resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
            resolver.registerContentObserver(Settings.Secure.getUriFor(HIGH_TEXT_CONTRAST), false, observer)
            resolver.registerContentObserver(Settings.Secure.getUriFor(INVERSION), false, observer)
        }
        context.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) = refresh(newConfig)
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onLowMemory() = Unit
        })
    }

    fun refresh(config: Configuration = context.resources.configuration) {
        val flags = read(config)
        if (flags == last) return
        last = flags
        onChange(flags)
    }

    private fun read(config: Configuration): List<String> {
        val on = HashSet<String>()
        val resolver = context.contentResolver
        if (am?.isTouchExplorationEnabled == true) on += A11yFlags.SCREEN_READER
        val switchAccess = runCatching {
            am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.any { it.id?.contains("switchaccess", ignoreCase = true) == true }
        }.getOrNull() == true
        val hardKeyboard = config.keyboard != Configuration.KEYBOARD_NOKEYS && config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
        if (switchAccess || hardKeyboard) on += A11yFlags.KEYBOARD
        val animator = runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)
        if (animator == 0f) on += A11yFlags.REDUCED_MOTION
        if (secureInt(HIGH_TEXT_CONTRAST) == 1) on += A11yFlags.HIGH_CONTRAST
        if (secureInt(INVERSION) == 1) on += A11yFlags.INVERTED_COLORS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && config.fontWeightAdjustment >= 300) on += A11yFlags.BOLD_TEXT
        if (config.fontScale > 1.15f) on += A11yFlags.LARGE_TEXT
        return A11yFlags.ALL.filter { it in on }
    }

    private fun secureInt(name: String): Int = runCatching { Settings.Secure.getInt(context.contentResolver, name, 0) }.getOrDefault(0)

    private companion object {
        /** Hidden in the SDK but stable across releases. */
        const val HIGH_TEXT_CONTRAST = "high_text_contrast_enabled"
        const val INVERSION = "accessibility_display_inversion_enabled"
    }
}
