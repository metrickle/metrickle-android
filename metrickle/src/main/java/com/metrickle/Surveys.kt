package com.metrickle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/*
 * Headless survey engine, a port of `packages/sdk/src/surveys.ts`. It decides *whether and when*
 * to show a campaign (trigger, targeting, sampling, frequency caps) and turns answers into
 * `$survey_*` events. Rendering is the app's job (`surveys.onShow`) or the `metrickle-compose`
 * `MetrickleSurveyHost`.
 */

/** An answer to one question. */
public data class SurveyAnswer @JvmOverloads constructor(
    val score: Int? = null,
    /** Choice answers. */
    val values: List<String>? = null,
    val text: String? = null,
)

/** A survey that should be shown now. Methods are callable from any thread. */
public interface ActiveSurvey {
    public val campaign: CampaignConfig
    /** Call once the survey is actually on screen. */
    public fun shown()
    public fun answer(question: Question, answer: SurveyAnswer)
    /** Call after the last answer. */
    public fun complete()
    /** The user closed it; [atIndex] is the question they were on. */
    public fun dismiss(atIndex: Int)

    /**
     * The campaign's follow-up: an invite into a study (a booked video call or a self-guided
     * test), offered after the last answer. Only present while the study is recruiting.
     */
    public val followUp: FollowUpConfig? get() = null

    /** Whether this response qualifies for the follow-up (false when there is none). Call after the last answer. */
    public fun qualifies(): Boolean = false

    /**
     * Asks for the respondent's personal study link (asked once per response). Returns null when
     * there's no follow-up, the response doesn't qualify, the study stopped recruiting or is full,
     * or the request failed: then show the plain thank-you. Open the link in the browser.
     */
    public suspend fun invite(): String? = null

    /** Call when the invite is on screen. */
    public fun followUpOffered() {}

    /** Call when the respondent opens the link. */
    public fun followUpAccepted() {}
}

/** Renders a survey. Called on the main thread. */
public fun interface SurveyRenderer {
    public fun render(survey: ActiveSurvey)
}

/** Handle returned by listener registrations. */
public fun interface Subscription {
    public fun cancel()
}

@Serializable
internal data class CampaignState(val shown: Long? = null, val answered: Long? = null, val dismissed: Long? = null)

@Serializable
internal data class SurveyState(val last: Long? = null, val c: Map<String, CampaignState> = emptyMap())

internal const val DAY_MS = 86_400_000L

/** Never show two surveys within this window, whatever their own caps say. */
internal const val GLOBAL_COOLDOWN_MS = DAY_MS
private const val MAX_TEXT = 1000

/** Deterministic [0, 1) from a string (FNV-1a over UTF-16 code units), so sampling is stable per user. */
internal fun unitHash(s: String): Double {
    var h = 0x811c9dc5.toInt()
    for (ch in s) {
        h = h xor ch.code
        h *= 0x01000193
    }
    return (h.toLong() and 0xffffffffL).toDouble() / 4294967296.0
}

internal fun matchPattern(pattern: String, value: String?): Boolean {
    if (value == null) return false
    return if (pattern.endsWith("*")) value.startsWith(pattern.dropLast(1)) else value == pattern
}

/** What one answer said, for a follow-up's condition. */
internal data class AnswerFacts(val score: Double?, val values: List<String>?)

/** Port of `followUpMatches` (`packages/schema/src/constants.ts`). No condition: every response qualifies. */
internal fun followUpMatches(condition: FollowUpWhen?, answers: Map<String, AnswerFacts>): Boolean {
    if (condition == null) return true
    val a = answers[condition.questionId] ?: return false
    val choices = condition.choices
    if (!choices.isNullOrEmpty()) return a.values?.any { it in choices } == true
    val score = a.score ?: return false
    return (condition.min == null || score >= condition.min) && (condition.max == null || score <= condition.max)
}

/** A personal link is only ever opened if it's https (http only when the host is): never `javascript:` or similar. */
internal fun safeUrl(url: String?, host: String): String? {
    if (url == null) return null
    val u = runCatching { URI(url) }.getOrNull() ?: return null
    val scheme = u.scheme?.lowercase()
    if (u.host.isNullOrEmpty()) return null
    return url.takeIf { scheme == "https" || (scheme == "http" && host.startsWith("http:")) }
}

internal data class EligibilityContext(
    val anonymousId: String?,
    val userId: String?,
    val platform: String,
    val appVersion: String?,
    val a11y: List<String>,
    val now: Long,
)

/** Whether a campaign's targeting and caps allow showing it now. Pure, for testing. */
internal fun eligible(c: CampaignConfig, ctx: EligibilityContext, state: SurveyState): Boolean {
    val t = c.targeting
    if (t.identifiedOnly == true && ctx.userId == null) return false
    if (!t.platforms.isNullOrEmpty() && ctx.platform !in t.platforms) return false
    if (!t.appVersions.isNullOrEmpty() && t.appVersions.none { matchPattern(it, ctx.appVersion) }) return false
    if (!t.a11y.isNullOrEmpty() && t.a11y.none { it in ctx.a11y }) return false
    if (unitHash("${ctx.anonymousId ?: ctx.userId ?: ""}:${c.id}") >= t.sampleRate) return false
    val last = state.last
    if (last != null && last != 0L && ctx.now - last < GLOBAL_COOLDOWN_MS) return false
    val s = state.c[c.id]
    val shown = s?.shown
    if (shown == null || shown == 0L) return true
    val days = (t.frequency.days ?: if (t.frequency.kind == "recurring") 90 else 30) * DAY_MS
    return when (t.frequency.kind) {
        "once" -> false
        "until_answered" -> (s.answered ?: 0L) == 0L && ctx.now - maxOf(shown, s.dismissed ?: 0L) >= days
        "recurring" -> ctx.now - shown >= days
        else -> false
    }
}

/** Runs on the client's serial dispatcher; renderers are called on the main dispatcher. */
internal class SurveyEngine(private val client: MetrickleClient) {
    @Volatile private var campaigns: List<CampaignConfig> = emptyList()
    private var state = SurveyState()
    private var loaded = false
    private var active: String? = null
    private val pending = HashSet<String>()
    private var lastPath: String? = null

    @Volatile var custom: SurveyRenderer? = null
    @Volatile var builtIn: SurveyRenderer? = null
    private val renderer: SurveyRenderer? get() = custom ?: builtIn

    init {
        client.onEvent(::handle)
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val raw = client.store?.get(Keys.SURVEYS) ?: return
        state = runCatching { MetrickleJson.decodeFromString(SurveyState.serializer(), raw) }.getOrNull() ?: SurveyState()
    }

    private fun save() {
        client.store?.set(Keys.SURVEYS, MetrickleJson.encodeToString(SurveyState.serializer(), state))
    }

    /** A renderer was registered. Surveys only trigger once something can show them. */
    fun rendererChanged() = client.post { check(Trig.Load) }

    fun setConfig(list: List<CampaignConfig>) {
        campaigns = list
        check(Trig.Load)
    }

    val current: List<CampaignConfig> get() = campaigns

    /** Shows a campaign now, ignoring targeting and caps (QA and previews). */
    fun show(campaignId: String) = client.post {
        load()
        campaigns.firstOrNull { it.id == campaignId }?.let(::present)
    }

    private sealed interface Trig {
        object Load : Trig
        data class Page(val path: String?) : Trig
        data class Event(val name: String) : Trig
    }

    private fun handle(e: IngestEvent) {
        if (e.name.startsWith("\$survey_")) return
        if (e.type == EventType.PAGE || e.type == EventType.SCREEN) {
            lastPath = e.path
            check(Trig.Page(e.path))
            check(Trig.Load)
        } else {
            check(Trig.Event(e.name))
        }
    }

    private fun check(trigger: Trig) {
        // Surveys need memory for frequency caps, so cookieless and opted-out clients never see them.
        if (renderer == null || !client.hasStorage || client.isOptedOut || active != null) return
        for (c in campaigns) {
            val t = c.targeting.trigger
            val kind = when (trigger) { Trig.Load -> "load"; is Trig.Page -> "page"; is Trig.Event -> "event" }
            if (t.kind != kind || c.id in pending) continue
            if (trigger is Trig.Page && !matchPattern(t.match ?: "", trigger.path)) continue
            if (trigger is Trig.Event && t.match != trigger.name) continue
            pending.add(c.id)
            client.scope.launch {
                load()
                delay(t.delayMs)
                pending.remove(c.id)
                if (active != null || !isEligible(c)) return@launch
                present(c)
            }
            return
        }
    }

    private fun isEligible(c: CampaignConfig): Boolean {
        val id = client.identity()
        val ctx = client.getContext()
        return eligible(c, EligibilityContext(id.anonymousId, id.userId, ctx.platform, ctx.app?.version, client.a11yFlags, client.clock()), state)
    }

    private fun record(id: String, update: (CampaignState) -> CampaignState) {
        state = state.copy(c = state.c + (id to update(state.c[id] ?: CampaignState())))
        save()
    }

    private fun present(c: CampaignConfig) {
        val r = renderer ?: return
        active = c.id
        val response = uuid()
        val base = props("campaign" to c.id, "version" to c.version, "response" to response)
        val path = lastPath
        var answered = 0
        // This response's answers by question, for the follow-up condition. Written on the caller's
        // thread so qualifies() sees an answer given just before it.
        val answers = ConcurrentHashMap<String, AnswerFacts>()
        val fu = c.followUp
        val inviteLock = Any()
        var link: Deferred<String?>? = null
        val offered = AtomicBoolean(false)
        val accepted = AtomicBoolean(false)
        fun followUpEvent(yes: Boolean) {
            client.captureAsync(EventType.TRACK, "\$survey_follow_up", path = path, properties = base + props("study" to fu!!.studyId, "accepted" to yes))
        }
        val survey = object : ActiveSurvey {
            override val campaign = c
            override val followUp: FollowUpConfig? = fu
            override fun shown() {
                client.post {
                    val now = client.clock()
                    state = state.copy(last = now)
                    record(c.id) { it.copy(shown = now) }
                    client.capture(EventType.TRACK, "\$survey_shown", now, path = path, properties = base)
                }
            }

            override fun answer(question: Question, answer: SurveyAnswer) {
                answers[question.id] = AnswerFacts(answer.score?.toDouble(), answer.values?.takeIf { it.isNotEmpty() })
                client.post {
                    answered++
                    val now = client.clock()
                    val last = question.id == c.questions.lastOrNull()?.id
                    val text = answer.text?.trim()
                    val p: Map<String, JsonElement> = base + props(
                        "question" to question.id,
                        "type" to question.type,
                        "score" to answer.score,
                        "value" to answer.values?.takeIf { it.isNotEmpty() }?.joinToString("|")?.take(1024),
                        "text" to text?.takeIf { it.isNotEmpty() }?.take(MAX_TEXT),
                        "completed" to (if (last) JsonPrimitive(true) else JsonNull),
                    )
                    client.capture(EventType.TRACK, "\$survey_answered", now, path = path, properties = p)
                    record(c.id) { it.copy(answered = now) }
                }
            }

            override fun complete() {
                client.post { if (active == c.id) active = null }
            }

            override fun dismiss(atIndex: Int) {
                client.post {
                    if (active == c.id) active = null
                    val now = client.clock()
                    record(c.id) { it.copy(dismissed = now) }
                    client.capture(EventType.TRACK, "\$survey_dismissed", now, path = path, properties = base + props("at" to atIndex, "answered" to answered))
                }
            }

            override fun qualifies(): Boolean = fu != null && followUpMatches(fu.condition, answers)

            override suspend fun invite(): String? {
                if (fu == null || client.isOptedOut || !qualifies()) return null
                val pending = synchronized(inviteLock) {
                    link ?: client.scope.async {
                        val id = client.identity()
                        requestInvite(client, fu.studyId, c.id, response, id.anonymousId, id.userId)
                    }.also { link = it }
                }
                return try {
                    pending.await()
                } catch (e: CancellationException) {
                    // The caller was cancelled (rethrow); or the client shut down (no link).
                    currentCoroutineContext().ensureActive()
                    null
                }
            }

            override fun followUpOffered() {
                if (fu != null && offered.compareAndSet(false, true)) followUpEvent(false)
            }

            override fun followUpAccepted() {
                if (fu != null && accepted.compareAndSet(false, true)) followUpEvent(true)
            }
        }
        client.scope.launch(client.mainDispatcher) {
            try {
                r.render(survey)
            } catch (e: Exception) {
                client.log("survey renderer failed: $e")
                client.post { if (active == c.id) active = null }
            }
        }
    }
}

/** `POST /v1/studies/invite`: the respondent's personal study link, or null for anything but a 201 with a safe link. */
internal suspend fun requestInvite(
    client: MetrickleClient,
    studyId: String,
    campaignId: String,
    response: String,
    anonymousId: String?,
    userId: String?,
): String? = try {
    val body = JsonObject(
        buildMap {
            put("writeKey", JsonPrimitive(client.writeKey))
            put("studyId", JsonPrimitive(studyId))
            put("campaignId", JsonPrimitive(campaignId))
            put("response", JsonPrimitive(response))
            anonymousId?.let { put("anonymousId", JsonPrimitive(it)) }
            userId?.let { put("userId", JsonPrimitive(it)) }
        },
    )
    val res = client.transport.request(
        "POST",
        "${client.host}/v1/studies/invite",
        mapOf("content-type" to "application/json", "x-metrickle-key" to client.writeKey, "user-agent" to client.userAgent),
        body.toString(),
    )
    if (res.status != 201 || res.body == null) {
        null
    } else {
        val url = MetrickleJson.parseToJsonElement(res.body).jsonObject["url"]?.jsonPrimitive?.contentOrNull
        safeUrl(url, client.host)
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    client.log("study invite failed: $e")
    null
}

/** Public survey API: `Metrickle.surveys` / `client.surveys`. */
public class Surveys internal constructor(internal val engine: SurveyEngine) {
    /**
     * Renders surveys with your own UI. Called on the main thread when a campaign's trigger and
     * targeting match: call `shown()` once it is on screen, `answer()` per question, then
     * `complete()` or `dismiss()`. Takes precedence over `MetrickleSurveyHost`.
     */
    public fun onShow(renderer: SurveyRenderer): Subscription {
        engine.custom = renderer
        engine.rendererChanged()
        return Subscription { if (engine.custom === renderer) engine.custom = null }
    }

    /** Shows an active campaign now, ignoring targeting and caps (QA and previews). */
    public fun show(campaignId: String) {
        engine.show(campaignId)
    }

    /** Campaigns from the last config fetch. */
    public val campaigns: List<CampaignConfig> get() = engine.current

    /** Registers the built-in renderer (used by `metrickle-compose`'s `MetrickleSurveyHost`). */
    @MetrickleInternalApi
    public fun setBuiltInRenderer(renderer: SurveyRenderer): Subscription {
        engine.builtIn = renderer
        engine.rendererChanged()
        return Subscription { if (engine.builtIn === renderer) engine.builtIn = null }
    }
}
