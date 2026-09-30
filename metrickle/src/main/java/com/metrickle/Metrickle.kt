package com.metrickle

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Implement on an Activity to name it for automatic screen tracking, or return null to skip it
 * (e.g. a single-Activity Compose app that calls `TrackScreen` per destination).
 */
public interface MetrickleScreen {
    public val metrickleScreenName: String?
}

/**
 * Entry point. Call [init] once in `Application.onCreate`, then use the static methods from
 * anywhere. Calls made before [init] are ignored.
 *
 * ```
 * Metrickle.init(this, "mk_live_…", MetrickleOptions(debug = BuildConfig.DEBUG))
 * Metrickle.track("checkout_started", mapOf("plan" to "pro"))
 * ```
 */
public object Metrickle {
    @Volatile private var instance: MetrickleClient? = null

    /** The shared client, or null before [init]. */
    @JvmStatic
    public val client: MetrickleClient? get() = instance

    /** Starts the SDK. Later calls return the existing client. */
    @JvmStatic
    @JvmOverloads
    @OptIn(ExperimentalCoroutinesApi::class)
    public fun init(context: Context, writeKey: String, options: MetrickleOptions = MetrickleOptions()): MetrickleClient {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val app = context.applicationContext as Application
            val eventContext = eventContext(app, options)
            val client = MetrickleClient(
                ClientEnv(
                    writeKey = writeKey,
                    options = options,
                    context = eventContext,
                    storage = if (options.cookieless) null else SharedPreferencesStore(app),
                    transport = UrlConnectionTransport,
                    userAgent = userAgent(),
                    dispatcher = Dispatchers.Default.limitedParallelism(1),
                    mainDispatcher = Dispatchers.Main,
                    log = { Log.d("Metrickle", it) },
                ),
            )
            instance = client
            AndroidIntegration(app, client, options).start()
            return client
        }
    }

    private fun shared(): MetrickleClient =
        instance ?: throw IllegalStateException("Call Metrickle.init(context, writeKey) first")

    @JvmStatic @JvmOverloads
    public fun screen(name: String, properties: Map<String, Any?>? = null) { instance?.screen(name, properties) }

    @JvmStatic @JvmOverloads
    public fun track(name: String, properties: Map<String, Any?>? = null) { instance?.track(name, properties) }

    @JvmStatic @JvmOverloads
    public fun identify(userId: String, traits: Map<String, Any?>? = null) { instance?.identify(userId, traits) }

    @JvmStatic public fun reset() { instance?.reset() }
    @JvmStatic public fun register(properties: Map<String, Any?>) { instance?.register(properties) }
    @JvmStatic public fun optOut() { instance?.optOut() }
    @JvmStatic public fun optIn() { instance?.optIn() }
    @JvmStatic public fun consent(replay: Boolean) { instance?.consent(replay) }
    @JvmStatic public fun flush() { instance?.flush() }
    @JvmStatic public fun formError(form: String, field: String, reason: String) { instance?.formError(form, field, reason) }
    @JvmStatic public fun refreshConfig() { instance?.refreshConfig() }

    /** Surveys of the shared client. @throws IllegalStateException before [init]. */
    @JvmStatic public val surveys: Surveys get() = shared().surveys

    /** Feedback of the shared client. @throws IllegalStateException before [init]. */
    @JvmStatic public val feedback: Feedback get() = shared().feedback

    /** `metrickle-android/0.1.0 (Android 14; Pixel 8)`. Must not look like a bot to the server's filter. */
    internal fun userAgent(): String =
        "metrickle-android/$SDK_VERSION (Android ${Build.VERSION.RELEASE}; ${Build.MODEL})".filter { it.code in 0x20..0x7e }

    private fun eventContext(app: Application, options: MetrickleOptions): EventContext {
        val res = app.resources
        val config = res.configuration
        val metrics = res.displayMetrics
        val type = when {
            (config.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION -> "tv"
            config.smallestScreenWidthDp >= 600 -> "tablet"
            else -> "mobile"
        }
        val (version, build) = runCatching {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
            info.versionName to code.toString()
        }.getOrElse { null to null }
        return EventContext(
            platform = "android",
            app = AppInfo(options.appVersion ?: version, options.appBuild ?: build),
            device = DeviceInfo(type = type, model = Build.MODEL, os = "Android", osVersion = Build.VERSION.RELEASE),
            screen = ScreenSize((metrics.widthPixels / metrics.density).roundToInt(), (metrics.heightPixels / metrics.density).roundToInt()),
            locale = Locale.getDefault().toLanguageTag(),
            timezone = TimeZone.getDefault().id,
        )
    }
}

/** Lifecycle, automatic screens, rage taps and accessibility flags. */
internal class AndroidIntegration(
    private val app: Application,
    private val client: MetrickleClient,
    private val options: MetrickleOptions,
) : Application.ActivityLifecycleCallbacks, DefaultLifecycleObserver {
    private val a11y = AccessibilityObserver(app) { flags -> client.setContext { it.copy(a11y = flags.ifEmpty { null }) } }

    fun start() {
        app.registerActivityLifecycleCallbacks(this)
        client.refreshConfig()
        // Lifecycle observers must be added on the main thread; ON_START is replayed if the app is already started.
        client.scope.launch(Dispatchers.Main) {
            a11y.start()
            ProcessLifecycleOwner.get().lifecycle.addObserver(this@AndroidIntegration)
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        // Settings such as font scale mostly change while the app is in the background.
        a11y.refresh()
        client.appForeground()
    }

    override fun onStop(owner: LifecycleOwner) {
        client.appBackground()
    }

    override fun onActivityResumed(activity: Activity) {
        if (options.rageTaps) activity.window?.let { w -> RageTapCallback.install(w) { s, t -> client.rageTap(s, t) } }
        if (options.automaticScreenTracking) screenName(activity)?.let { client.screen(it) }
    }

    /** Explicit name from [MetrickleScreen], else the class name without "Activity" (stable across locales). */
    private fun screenName(activity: Activity): String? {
        if (activity is MetrickleScreen) return activity.metrickleScreenName
        val name = activity.javaClass.simpleName
        return name.removeSuffix("Activity").ifEmpty { name }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
