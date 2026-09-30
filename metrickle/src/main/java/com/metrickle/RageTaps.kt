package com.metrickle

import android.annotation.TargetApi
import android.os.Build
import android.os.SystemClock
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import kotlin.math.abs

/**
 * Identifiers for tap targets that aren't Views (Compose). `Modifier.metrickleTag("id")` notes
 * its tag on each pointer down; a rage tap within 500ms uses the innermost tag as its selector.
 */
@MetrickleInternalApi
public object TapTargets {
    @Volatile private var last: Pair<String, Long>? = null

    public fun note(tag: String) {
        last = tag to SystemClock.uptimeMillis()
    }

    internal fun recent(downTime: Long): String? = last?.takeIf { abs(it.second - downTime) < 500 }?.first
}

/**
 * Wraps an Activity's [Window.Callback] (delegating everything) to observe taps. Three taps
 * within 1s inside 30dp send `$rage_click` with the tapped view's identifier; text field
 * contents are never read.
 */
internal class RageTapCallback(
    val delegate: Window.Callback,
    private val window: Window,
    private val onRage: (selector: String?, text: String?) -> Unit,
) : Window.Callback by delegate {
    private val detector = RageTapDetector()
    private val density = window.context.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(window.context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        try {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                }
                MotionEvent.ACTION_UP -> if (abs(event.rawX - downX) < slop && abs(event.rawY - downY) < slop) {
                    if (detector.tap(event.eventTime, event.rawX / density, event.rawY / density)) report(event)
                }
            }
        } catch (_: RuntimeException) {
            // Never break the app's touch handling.
        }
        return delegate.dispatchTouchEvent(event)
    }

    private fun report(event: MotionEvent) {
        val hit = hitView(window.decorView, event.rawX.toInt(), event.rawY.toInt())
        val tag = TapTargets.recent(event.downTime)
        val selector = tag ?: hit?.let(::selector)
        onRage(selector, hit?.let(::label))
    }

    // Kotlin delegation skips Java default methods; forward them explicitly.
    @TargetApi(Build.VERSION_CODES.N)
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>?, menu: Menu?, deviceId: Int) =
        delegate.onProvideKeyboardShortcuts(data, menu, deviceId)

    @TargetApi(Build.VERSION_CODES.O)
    override fun onPointerCaptureChanged(hasCapture: Boolean) = delegate.onPointerCaptureChanged(hasCapture)

    companion object {
        fun install(window: Window, onRage: (String?, String?) -> Unit) {
            val cb = window.callback ?: return
            if (cb is RageTapCallback) return
            window.callback = RageTapCallback(cb, window, onRage)
        }

        /** The deepest visible view under the point (screen coordinates). */
        fun hitView(root: View, x: Int, y: Int): View? {
            if (root.visibility != View.VISIBLE || !contains(root, x, y)) return null
            if (root is ViewGroup) {
                for (i in root.childCount - 1 downTo 0) {
                    hitView(root.getChildAt(i), x, y)?.let { return it }
                }
            }
            return root
        }

        private val loc = IntArray(2)
        private fun contains(v: View, x: Int, y: Int): Boolean {
            v.getLocationOnScreen(loc)
            return x >= loc[0] && y >= loc[1] && x < loc[0] + v.width && y < loc[1] + v.height
        }

        /** The nearest id resource entry name, else the class of the nearest view with a content description, else the view's class. */
        fun selector(view: View): String {
            var v: View? = view
            while (v != null) {
                if (v.id != View.NO_ID) {
                    runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()?.let { return it }
                }
                v = v.parent as? View
            }
            return (labelled(view) ?: view).javaClass.simpleName
        }

        /** The nearest content description (≤ 80 chars). Never text field contents. */
        fun label(view: View): String? = labelled(view)?.contentDescription?.toString()?.trim()?.take(80)?.takeIf { it.isNotEmpty() }

        private fun labelled(view: View): View? {
            var v: View? = view
            while (v != null) {
                if (v !is EditText && !v.contentDescription.isNullOrBlank()) return v
                v = v.parent as? View
            }
            return null
        }
    }
}
