package com.metrickle

/** Called before each event is queued; return null to drop it (e.g. PII scrubbing). Runs on the SDK's thread. */
public fun interface BeforeSend {
    public fun process(event: IngestEvent): IngestEvent?
}

/**
 * Settings for [Metrickle.init].
 *
 * @property host Ingest origin. Trailing slash is stripped.
 * @property cookieless Persist nothing: no anonymous or session id (the server derives a daily-rotating hash), no offline queue, no surveys.
 * @property flushIntervalMs How often queued events are sent.
 * @property sessionTimeoutMs Inactivity before a new session starts.
 * @property automaticScreenTracking Send `$screen` when an Activity resumes. Compose apps with one Activity should turn this off and use `TrackScreen` per destination.
 * @property rageTaps Detect `$rage_click` (3 taps within 1s inside 30dp).
 * @property debug Log every queued event and each send result to Logcat (tag `Metrickle`).
 * @property beforeSend Edit or drop events before they are queued.
 * @property appVersion Overrides the version name read from the package.
 * @property appBuild Overrides the version code read from the package.
 */
public data class MetrickleOptions @JvmOverloads constructor(
    val host: String = DEFAULT_HOST,
    val cookieless: Boolean = false,
    val flushIntervalMs: Long = 5_000,
    val sessionTimeoutMs: Long = 30 * 60_000L,
    val automaticScreenTracking: Boolean = true,
    val rageTaps: Boolean = true,
    val debug: Boolean = false,
    val beforeSend: BeforeSend? = null,
    val appVersion: String? = null,
    val appBuild: String? = null,
) {
    public companion object {
        public const val DEFAULT_HOST: String = "https://in.metrickle.com"
    }
}

/** Research features that need the user's explicit consent before they run. */
public object ConsentKind {
    public const val REPLAY: String = "replay"
}

/** Accessibility flags (`A11Y_FLAGS` in the schema), in canonical order. */
public object A11yFlags {
    public const val SCREEN_READER: String = "screen_reader"
    public const val KEYBOARD: String = "keyboard"
    public const val REDUCED_MOTION: String = "reduced_motion"
    public const val REDUCED_TRANSPARENCY: String = "reduced_transparency"
    public const val HIGH_CONTRAST: String = "high_contrast"
    public const val FORCED_COLORS: String = "forced_colors"
    public const val INVERTED_COLORS: String = "inverted_colors"
    public const val GRAYSCALE: String = "grayscale"
    public const val BOLD_TEXT: String = "bold_text"
    public const val LARGE_TEXT: String = "large_text"
    public const val ZOOMED: String = "zoomed"

    @JvmField
    public val ALL: List<String> = listOf(
        SCREEN_READER, KEYBOARD, REDUCED_MOTION, REDUCED_TRANSPARENCY, HIGH_CONTRAST,
        FORCED_COLORS, INVERTED_COLORS, GRAYSCALE, BOLD_TEXT, LARGE_TEXT, ZOOMED,
    )
}

/** Opts a class into APIs meant for Metrickle's own modules (e.g. `metrickle-compose`). */
@RequiresOptIn(message = "Used by Metrickle's own modules; may change without notice.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class MetrickleInternalApi
