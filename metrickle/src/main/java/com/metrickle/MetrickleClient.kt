package com.metrickle

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList

/** This SDK's version, from `VERSION_NAME` in gradle.properties. */
public val SDK_VERSION: String = BuildConfig.SDK_VERSION
public const val LIBRARY_NAME: String = "metrickle-android"

internal object Keys {
    const val ANON = "mk_aid"
    const val USER = "mk_uid"
    const val SESSION = "mk_sid"
    const val OPT_OUT = "mk_optout"
    const val CONSENT = "mk_consent"
    const val SURVEYS = "mk_surveys"
    const val QUEUE = "mk_queue"
}

/** Telemetry reported on its own schedule: never starts or extends a session. */
private val PASSIVE = setOf("\$web_vital", "\$app_background")
internal const val MAX_QUEUE = 1000
internal const val MAX_BATCH = 100
internal const val FLUSH_AT = 20
internal const val MAX_EVENT_AGE_MS = 7 * 86_400_000L
internal const val MAX_BACKOFF_MS = 60_000L
internal const val CONFIG_TTL_MS = 5 * 60_000L

@Serializable
internal data class SessionState(val id: String, val last: Long)

/** Everything the client needs from the platform. Android fills it in [Metrickle.init]; tests use fakes. */
internal class ClientEnv(
    val writeKey: String,
    val options: MetrickleOptions,
    val context: EventContext,
    /** Null in cookieless mode. */
    val storage: KeyValueStore?,
    val transport: Transport,
    val userAgent: String,
    /** Serial dispatcher: all client state is confined to it. */
    val dispatcher: CoroutineDispatcher,
    /** Where survey renderers are called (the UI thread on Android). */
    val mainDispatcher: CoroutineDispatcher,
    val clock: () -> Long = System::currentTimeMillis,
    val log: (String) -> Unit = {},
    val startTimer: Boolean = true,
)

/**
 * The Metrickle client, a port of `packages/sdk/src/core.ts`. Every public method is safe to call
 * from any thread and returns immediately: work runs in order on a single background dispatcher,
 * so the UI thread never waits for disk or network. Use it through [Metrickle] or keep a reference
 * from [Metrickle.init].
 */
public class MetrickleClient internal constructor(private val env: ClientEnv) {
    internal val scope = CoroutineScope(SupervisorJob() + env.dispatcher)
    private val storage = env.storage
    private val sessionTimeout = env.options.sessionTimeoutMs

    private val queue = ArrayDeque<IngestEvent>()
    private val superProps = LinkedHashMap<String, JsonElement>()
    private val listeners = CopyOnWriteArrayList<(IngestEvent) -> Unit>()
    private val uTurn = UTurnDetector()
    private val formErrors = Deduper()
    private var session: SessionState? = null
    private var flushing = false
    private var backoffMs = 0L
    private var nextAttemptAt = 0L
    private var persistJob: Job? = null
    private var lastConfigAt = 0L

    @Volatile private var anonymousId: String? = null
    @Volatile private var userId: String? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var optedOut = false
    /** The latest optOut()/optIn() call, set at once so restoring stored state never overrides it. */
    @Volatile private var optChoice: Boolean? = null
    @Volatile private var consents: Set<String> = emptySet()
    @Volatile private var context: EventContext = env.context

    /** The current screen name, attached to events that don't set their own path. */
    @Volatile public var currentScreen: String? = null
        private set

    /** The last `SdkConfig` served for this write key (null until fetched). */
    @Volatile public var config: SdkConfig? = null
        private set

    /** In-app surveys: headless rendering via `onShow`, or the `metrickle-compose` host. */
    public val surveys: Surveys = Surveys(SurveyEngine(this))

    /** "Report a problem" submissions. */
    public val feedback: Feedback = Feedback(this)

    init {
        scope.launch { restore() }
        if (env.startTimer) {
            scope.launch {
                while (isActive) {
                    delay(env.options.flushIntervalMs)
                    flushInternal(unloading = false)
                }
            }
        }
    }

    // --- Accessors ------------------------------------------------------------------------------

    /** Ingest origin, without a trailing slash. */
    public val host: String get() = env.options.host.trimEnd('/')
    public val writeKey: String get() = env.writeKey
    public val isOptedOut: Boolean get() = optedOut
    public val isCookieless: Boolean get() = storage == null
    internal val hasStorage: Boolean get() = storage != null
    internal val store: KeyValueStore? get() = storage
    internal val clock: () -> Long get() = env.clock
    internal val mainDispatcher: CoroutineDispatcher get() = env.mainDispatcher
    internal val transport: Transport get() = env.transport
    internal val userAgent: String get() = env.userAgent
    internal fun log(msg: String) { if (env.options.debug) env.log(msg) }

    /** Current ids. Reading them does not extend the session. */
    public fun identity(): Identity = Identity(anonymousId, userId, sessionId)

    /** The device context sent with each batch. */
    public fun getContext(): EventContext = context

    /** Accessibility flags currently observed on the device. */
    public val a11yFlags: List<String> get() = context.a11y ?: emptyList()

    /** Updates the context sent with later batches, e.g. when an accessibility setting changes. */
    internal fun setContext(update: (EventContext) -> EventContext) {
        context = update(context)
    }

    /** Called for every event after it is queued (survey triggers). Runs on the SDK's thread. */
    internal fun onEvent(fn: (IngestEvent) -> Unit) { listeners.add(fn) }

    internal fun post(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    // --- Public API -----------------------------------------------------------------------------

    /** Records a screen view: `$screen` with `path` = `title` = [name] and `referrer` = the previous screen. Sends `$u_turn` first when the user bounced A → B → A within 7s. */
    @JvmOverloads
    public fun screen(name: String, properties: Map<String, Any?>? = null) {
        val now = env.clock()
        val props = toJsonProps(properties)
        post {
            uTurn.next(name, now)?.let { turn ->
                capture(EventType.TRACK, "\$u_turn", now, path = turn.from, properties = props("back_to" to turn.to, "dwell_ms" to turn.dwellMs))
            }
            capture(EventType.SCREEN, "\$screen", now, path = name, title = name, referrer = currentScreen, properties = props)
            currentScreen = name
        }
    }

    /**
     * Records a custom event.
     * @throws IllegalArgumentException when [name] starts with `$` (reserved for Metrickle).
     */
    @JvmOverloads
    public fun track(name: String, properties: Map<String, Any?>? = null) {
        require(!name.startsWith("$")) { "metrickle: event names starting with \$ are reserved" }
        require(name.isNotEmpty()) { "metrickle: event name is empty" }
        val now = env.clock()
        val props = toJsonProps(properties)
        post { capture(EventType.TRACK, name, now, properties = props) }
    }

    /** Persists the user id and sends `$identify` with [traits]. */
    @JvmOverloads
    public fun identify(userId: String, traits: Map<String, Any?>? = null) {
        val now = env.clock()
        val t = toJsonProps(traits)
        post {
            this@MetrickleClient.userId = userId
            storage?.set(Keys.USER, userId)
            capture(EventType.IDENTIFY, "\$identify", now, traits = t)
        }
    }

    /**
     * Call on logout: forgets the user and session and starts a new anonymous identity. While opted
     * out, no new anonymous id is created ([optIn] creates one).
     */
    public fun reset() {
        post {
            userId = null
            session = null
            sessionId = null
            anonymousId = if (storage != null && !optedOut) uuid() else null
            storage?.remove(Keys.USER)
            storage?.remove(Keys.SESSION)
            val id = anonymousId
            if (id != null) storage?.set(Keys.ANON, id) else storage?.remove(Keys.ANON)
        }
    }

    /** Properties merged into every later event (an event's own properties win). */
    public fun register(properties: Map<String, Any?>) {
        val props = toJsonProps(properties)
        post { superProps.putAll(props) }
    }

    /**
     * Stops all collection and network calls, clears the queue, removes the anonymous and session
     * ids from the device, and remembers the choice. Your own user id ([identify]) and consent are
     * kept. Takes effect at once: [isOptedOut] is true when this returns.
     */
    public fun optOut() {
        optChoice = true
        optedOut = true
        post {
            optedOut = true
            queue.clear()
            anonymousId = null
            session = null
            sessionId = null
            storage?.set(Keys.OPT_OUT, "1")
            storage?.remove(Keys.QUEUE)
            storage?.remove(Keys.ANON)
            storage?.remove(Keys.SESSION)
        }
    }

    /**
     * Resumes collection after [optOut]: creates a new anonymous id and fetches the config again
     * (surveys, the feedback switch). Takes effect at once: [isOptedOut] is false when this returns.
     */
    public fun optIn() {
        optChoice = false
        optedOut = false
        post {
            optedOut = false
            storage?.remove(Keys.OPT_OUT)
            val s = storage
            if (s != null && anonymousId == null) anonymousId = uuid().also { s.set(Keys.ANON, it) }
            fetchConfig()
        }
    }

    /** Records the user's consent choice for session replay (kept for parity; native replay does not exist yet). */
    public fun consent(replay: Boolean) {
        post {
            consents = if (replay) consents + ConsentKind.REPLAY else consents - ConsentKind.REPLAY
            storage?.set(Keys.CONSENT, consents.joinToString(","))
        }
    }

    public fun hasConsent(kind: String): Boolean = consents.contains(kind)

    /** Reports a validation error; duplicates of the same form, field and reason within 1.5s are collapsed. Never pass field contents. */
    public fun formError(form: String, field: String, reason: String) {
        val now = env.clock()
        post {
            if (!formErrors.accept("$form|$field|$reason", now)) return@post
            capture(EventType.TRACK, "\$form_error", now, properties = props("form" to form, "field" to field, "reason" to reason))
        }
    }

    /** Sends queued events now (in the background). */
    public fun flush() {
        post { flushInternal(unloading = false, force = true) }
    }

    /** Sends queued events and waits for the request to finish. */
    public suspend fun flushNow() {
        post { flushInternal(unloading = false, force = true) }.join()
    }

    /** Re-fetches campaigns and settings (done automatically at launch and on foreground after 5 minutes). */
    public fun refreshConfig() {
        post { fetchConfig() }
    }

    /** Waits for work queued so far to finish (tests, shutdown). */
    public suspend fun awaitIdle() {
        post { }.join()
    }

    // --- Internal events (automatic capture) ----------------------------------------------------

    internal fun appForeground() {
        val now = env.clock()
        post {
            capture(EventType.TRACK, "\$app_open", now)
            if (now - lastConfigAt > CONFIG_TTL_MS) fetchConfig()
        }
    }

    internal fun appBackground() {
        val now = env.clock()
        post {
            capture(EventType.TRACK, "\$app_background", now)
            persistQueueNow()
            flushInternal(unloading = true)
        }
    }

    internal fun rageTap(selector: String?, text: String?) {
        val now = env.clock()
        post { capture(EventType.TRACK, "\$rage_click", now, properties = props("selector" to selector, "text" to text?.take(80))) }
    }

    /** Captures an event from Metrickle's own modules (surveys). Call from any thread. */
    internal fun captureAsync(type: EventType, name: String, path: String? = null, properties: Map<String, JsonElement>? = null) {
        val now = env.clock()
        post { capture(type, name, now, path = path, properties = properties) }
    }

    // --- Core -----------------------------------------------------------------------------------

    private fun restore() {
        val s = storage ?: return
        // An optOut()/optIn() made before restoring wins over the stored choice (it is applied after this).
        optedOut = optChoice ?: (s.get(Keys.OPT_OUT) == "1")
        consents = s.get(Keys.CONSENT)?.split(",")?.filter { it == ConsentKind.REPLAY }?.toSet().orEmpty()
        userId = s.get(Keys.USER)
        // An opted-out device gets no anonymous id and no session; optIn() creates a new id.
        if (!optedOut) {
            anonymousId = s.get(Keys.ANON) ?: uuid().also { s.set(Keys.ANON, it) }
            session = s.get(Keys.SESSION)?.let { runCatching { MetrickleJson.decodeFromString(SessionState.serializer(), it) }.getOrNull() }
            sessionId = session?.id
            val saved = s.get(Keys.QUEUE)?.let {
                runCatching { MetrickleJson.decodeFromString(ListSerializer(IngestEvent.serializer()), it) }.getOrNull()
            }.orEmpty()
            val cutoff = env.clock() - MAX_EVENT_AGE_MS
            queue.addAll(saved.filter { it.ts >= cutoff }.takeLast(MAX_QUEUE))
        } else {
            // Ids left by an SDK version that kept them after opting out.
            s.remove(Keys.ANON)
            s.remove(Keys.SESSION)
            s.remove(Keys.QUEUE)
        }
    }

    /** Session id with inactivity timeout. Null in cookieless mode (the server derives one). */
    private fun touchSession(now: Long, passive: Boolean): String? {
        val s = storage ?: return null
        val current = session
        if (passive && current != null) return current.id
        val next = if (current == null || now - current.last > sessionTimeout) SessionState(uuid(), now) else current.copy(last = now)
        session = next
        sessionId = next.id
        s.set(Keys.SESSION, MetrickleJson.encodeToString(SessionState.serializer(), next))
        return next.id
    }

    /** Builds, filters and queues one event. Runs on the SDK's thread. */
    internal fun capture(
        type: EventType,
        name: String,
        now: Long,
        path: String? = null,
        title: String? = null,
        referrer: String? = null,
        properties: Map<String, JsonElement>? = null,
        traits: Map<String, JsonElement>? = null,
    ) {
        if (optedOut) return
        val merged = LinkedHashMap(superProps).apply { properties?.let { putAll(it) } }
        while (merged.size > MAX_PROPERTIES) merged.remove(merged.keys.first())
        var event: IngestEvent? = IngestEvent(
            id = uuid(),
            type = type,
            name = name,
            ts = now,
            anonymousId = anonymousId,
            userId = userId,
            sessionId = touchSession(now, name in PASSIVE),
            path = path ?: currentScreen,
            title = title,
            referrer = referrer,
            properties = merged.takeIf { it.isNotEmpty() }?.let(::JsonObject),
            traits = traits?.takeIf { it.isNotEmpty() }?.let(::JsonObject),
        )
        env.options.beforeSend?.let { hook -> event = event?.let { runCatching { hook.process(it) }.getOrElse { e -> log("beforeSend failed: $e"); it } } }
        val e = event ?: return
        if (queue.size >= MAX_QUEUE) queue.removeFirst()
        queue.addLast(e)
        log("queued ${MetrickleJson.encodeToString(IngestEvent.serializer(), e)}")
        for (l in listeners) {
            try {
                l(e)
            } catch (err: Exception) {
                log("listener failed: $err")
            }
        }
        schedulePersist()
        if (queue.size >= FLUSH_AT) post { flushInternal(unloading = false) }
    }

    internal fun batchJson(events: List<IngestEvent>): String = MetrickleJson.encodeToString(
        IngestBatch.serializer(),
        IngestBatch(
            writeKey = env.writeKey,
            sentAt = env.clock(),
            context = context.copy(library = Library(LIBRARY_NAME, SDK_VERSION)),
            events = events,
        ),
    )

    private suspend fun send(events: List<IngestEvent>): Boolean {
        val res = env.transport.request(
            "POST",
            "$host/v1/batch",
            mapOf("content-type" to "application/json", "x-metrickle-key" to env.writeKey, "user-agent" to env.userAgent),
            batchJson(events),
        )
        // 4xx other than 429 will never succeed: drop instead of retrying forever.
        val ok = res.status in 200..299 || (res.status in 400..499 && res.status != 429)
        log("sent ${events.size} events: ${if (res.status == 0) "network error" else res.status}")
        return ok
    }

    private fun take(): List<IngestEvent> {
        val cutoff = env.clock() - MAX_EVENT_AGE_MS
        queue.removeAll { it.ts < cutoff }
        val n = minOf(MAX_BATCH, queue.size)
        return List(n) { queue.removeFirst() }
    }

    private fun putBack(events: List<IngestEvent>) {
        if (optedOut) return
        events.asReversed().forEach { queue.addFirst(it) }
        while (queue.size > MAX_QUEUE) queue.removeFirst()
    }

    /**
     * Sends queued events. Failures go back to the front of the queue and back off 1s, 2s, 4s…
     * up to 60s. `unloading` (app going to background) sends everything now, ignoring back-off.
     */
    internal suspend fun flushInternal(unloading: Boolean, force: Boolean = false) {
        if (optedOut) return
        if (unloading) {
            while (queue.isNotEmpty() && !optedOut) {
                val events = take()
                if (events.isEmpty()) break
                if (!send(events)) {
                    putBack(events)
                    break
                }
            }
            persistQueueNow()
            return
        }
        if (flushing || queue.isEmpty()) return
        if (!force && env.clock() < nextAttemptAt) return
        flushing = true
        val events = take()
        try {
            if (events.isEmpty()) return
            if (send(events)) {
                backoffMs = 0
                nextAttemptAt = 0
            } else {
                putBack(events)
                backoffMs = if (backoffMs == 0L) 1_000 else minOf(MAX_BACKOFF_MS, backoffMs * 2)
                nextAttemptAt = env.clock() + backoffMs
            }
        } finally {
            flushing = false
            persistQueueNow()
        }
    }

    internal val retryDelayMs: Long get() = backoffMs
    internal val queued: List<IngestEvent> get() = queue.toList()

    private fun schedulePersist() {
        if (storage == null || persistJob?.isActive == true) return
        persistJob = post {
            delay(1_000)
            persistQueueNow()
        }
    }

    /** Saves the queue so events survive the process being killed. */
    private fun persistQueueNow() {
        val s = storage ?: return
        if (queue.isEmpty() || optedOut) s.remove(Keys.QUEUE)
        else s.set(Keys.QUEUE, MetrickleJson.encodeToString(ListSerializer(IngestEvent.serializer()), queue.toList()))
    }

    private suspend fun fetchConfig() {
        if (optedOut) return
        lastConfigAt = env.clock()
        val key = URLEncoder.encode(env.writeKey, "UTF-8")
        val res = env.transport.request("GET", "$host/v1/config?key=$key", mapOf("user-agent" to env.userAgent, "accept" to "application/json"), null)
        if (res.status !in 200..299 || res.body == null) return
        val cfg = runCatching { MetrickleJson.decodeFromString(SdkConfig.serializer(), res.body) }.getOrNull() ?: return
        if (cfg.v != 1) return
        config = cfg
        surveys.engine.setConfig(cfg.campaigns)
    }

    /** Stops the timer after a final flush. The client can't be used afterwards. */
    public suspend fun shutdown() {
        withContext(env.dispatcher) { flushInternal(unloading = true) }
        scope.cancel()
    }
}

/** Current ids; null in cookieless mode or before they are restored. */
public data class Identity(val anonymousId: String?, val userId: String?, val sessionId: String?)

internal fun props(vararg pairs: Pair<String, Any?>): Map<String, JsonElement> = pairs.associate { (k, v) ->
    k to when (v) {
        null -> JsonNull
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is JsonElement -> v
        else -> JsonPrimitive(v.toString())
    }
}
