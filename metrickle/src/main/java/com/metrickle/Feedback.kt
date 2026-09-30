package com.metrickle

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.PixelCopy
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/** Result of [Feedback.submit]. */
public data class FeedbackResult(val ok: Boolean, val id: String? = null)

/** Categories accepted by `POST /v1/feedback`. */
public object FeedbackCategory {
    public const val BUG: String = "bug"
    public const val CONFUSING: String = "confusing"
    public const val IDEA: String = "idea"
    public const val ACCESSIBILITY: String = "accessibility"
    public const val OTHER: String = "other"
}

/** Max decoded screenshot size accepted by the server. */
internal const val MAX_SCREENSHOT_BYTES = 2 * 1024 * 1024
private const val MAX_EDGE = 1280

/** "Report a problem" (`feedback.ts`). Session, screen, device, app version, locale and a11y flags are attached automatically. */
public class Feedback internal constructor(private val client: MetrickleClient) {
    internal fun body(category: String, message: String, rating: Int?, screenshot: String?, path: String?): FeedbackSubmission {
        val ctx = client.getContext()
        val id = client.identity()
        return FeedbackSubmission(
            writeKey = client.writeKey,
            anonymousId = id.anonymousId,
            userId = id.userId,
            sessionId = id.sessionId,
            category = category,
            message = message,
            rating = rating,
            path = path ?: client.currentScreen,
            platform = ctx.platform,
            appVersion = ctx.app?.version,
            device = ctx.device?.let { FeedbackDevice(it.type, it.os, it.model) },
            screen = ctx.screen,
            locale = ctx.locale,
            a11y = ctx.a11y,
            screenshot = screenshot?.takeIf { it.length <= (MAX_SCREENSHOT_BYTES * 4 + 2) / 3 + 64 },
        )
    }

    /**
     * False when feedback is switched off for Android in the dashboard; [submit] then sends nothing.
     * Use it to hide your own feedback button. True until the config has loaded.
     */
    public val isEnabled: Boolean
        get() = client.config?.feedback?.platforms?.contains("android") ?: true

    /**
     * Sends a report. [category] is one of [FeedbackCategory]; [rating] is 1–5; [screenshot] is a
     * `data:image/jpeg|png;base64,…` URL, e.g. from [captureScreenshot]. Returns `ok = false` on
     * any failure, after `optOut()`, while [isEnabled] is false, or when [message] is blank.
     */
    @JvmOverloads
    public suspend fun submit(category: String, message: String, rating: Int? = null, screenshot: String? = null, path: String? = null): FeedbackResult {
        if (client.isOptedOut || !isEnabled || message.isBlank()) return FeedbackResult(false)
        val json = MetrickleJson.encodeToString(FeedbackSubmission.serializer(), body(category, message.trim().take(4000), rating, screenshot, path))
        val res = client.transport.request(
            "POST",
            "${client.host}/v1/feedback",
            mapOf("content-type" to "application/json", "user-agent" to client.userAgent),
            json,
        )
        client.log("feedback: ${res.status}")
        if (res.status !in 200..299) return FeedbackResult(false)
        val id = runCatching { MetrickleJson.parseToJsonElement(res.body ?: "").jsonObject["id"]?.jsonPrimitive?.content }.getOrNull()
        return FeedbackResult(true, id)
    }

    /** Java-friendly [submit]; [callback] runs on the main thread. */
    @JvmOverloads
    public fun submitAsync(category: String, message: String, rating: Int? = null, screenshot: String? = null, callback: ((FeedbackResult) -> Unit)? = null) {
        client.scope.launch {
            val r = submit(category, message, rating, screenshot)
            if (callback != null) withContext(client.mainDispatcher) { callback(r) }
        }
    }

    /**
     * Captures [activity]'s window as a JPEG data URL (quality 70, ≤ 1280px on the long edge) for
     * [submit]. Only call this after the user chose to attach a screenshot: it may contain personal
     * data. Returns null for `FLAG_SECURE` windows or on failure. Uses PixelCopy on API 26+.
     */
    public suspend fun captureScreenshot(activity: Activity): String? {
        val bitmap = withContext(Dispatchers.Main) { capture(activity) } ?: return null
        return withContext(Dispatchers.Default) {
            try {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
                val bytes = out.toByteArray()
                if (bytes.size > MAX_SCREENSHOT_BYTES) null else "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            } finally {
                bitmap.recycle()
            }
        }
    }

    /** Java-friendly [captureScreenshot]; [callback] runs on the main thread. */
    public fun captureScreenshotAsync(activity: Activity, callback: (String?) -> Unit) {
        client.scope.launch {
            val r = captureScreenshot(activity)
            withContext(client.mainDispatcher) { callback(r) }
        }
    }

    private suspend fun capture(activity: Activity): Bitmap? {
        val window = activity.window ?: return null
        if (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) return null
        val view = window.decorView
        if (view.width <= 0 || view.height <= 0) return null
        val scale = minOf(1f, MAX_EDGE.toFloat() / max(view.width, view.height))
        val w = (view.width * scale).roundToInt().coerceAtLeast(1)
        val h = (view.height * scale).roundToInt().coerceAtLeast(1)
        return try {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // PixelCopy scales the window into the smaller bitmap.
                val ok = suspendCancellableCoroutine { cont ->
                    PixelCopy.request(window, bitmap, { result -> cont.resume(result == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
                }
                if (ok) bitmap else drawInto(bitmap, view, scale)
            } else {
                drawInto(bitmap, view, scale)
            }
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun drawInto(bitmap: Bitmap, view: android.view.View, scale: Float): Bitmap {
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        view.draw(canvas)
        return bitmap
    }
}
