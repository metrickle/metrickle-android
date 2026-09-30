package com.metrickle

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DAY = 86_400_000L

/** Same vectors as `packages/sdk/src/surveys.test.ts`. */
@OptIn(ExperimentalCoroutinesApi::class)
class SurveysTest {
    private fun campaign(id: String = "cmp_1", patch: Targeting.() -> Targeting = { this }) = CampaignConfig(
        id = id,
        version = 1,
        questions = listOf(
            Question(id = "nps", type = "nps", prompt = "How likely are you to recommend us?", required = true),
            Question(id = "why", type = "text", prompt = "Why?", required = false),
        ),
        targeting = Targeting(trigger = Trigger("load", delayMs = 0), sampleRate = 1.0, frequency = Frequency("once")).patch(),
    )

    private val base = EligibilityContext(anonymousId = "anon-1", userId = null, platform = "web", appVersion = "2.4.1", a11y = emptyList(), now = 100 * DAY)
    private val empty = SurveyState()

    @Test
    fun unitHashMatchesJs() {
        // Reference values computed with packages/sdk/src/surveys.ts.
        assertEquals(0.5043428998906165, unitHash(""), 0.0)
        assertEquals(0.10740100289694965, unitHash("anon-1:cmp_1"), 0.0)
        assertEquals(0.8908105595037341, unitHash("a"), 0.0)
        assertEquals(0.06869439058937132, unitHash("user-42:cmp_xyz"), 0.0)
        assertEquals(0.32371679367497563, unitHash("héllo:😀"), 0.0)
    }

    @Test
    fun matchPatternPrefixAndExact() {
        assertTrue(matchPattern("/checkout*", "/checkout/done"))
        assertTrue(matchPattern("Home", "Home"))
        assertFalse(matchPattern("Home", "HomeDetail"))
        assertFalse(matchPattern("*", null))
        assertTrue(matchPattern("*", ""))
    }

    @Test
    fun targeting() {
        assertTrue(eligible(campaign(), base, empty))
        assertFalse(eligible(campaign { copy(platforms = listOf("ios")) }, base, empty))
        assertTrue(eligible(campaign { copy(appVersions = listOf("2.4*")) }, base, empty))
        assertFalse(eligible(campaign { copy(appVersions = listOf("2.3*", "3.0.0")) }, base, empty))
        assertFalse(eligible(campaign { copy(a11y = listOf("screen_reader")) }, base, empty))
        assertTrue(eligible(campaign { copy(a11y = listOf("screen_reader")) }, base.copy(a11y = listOf("screen_reader")), empty))
        assertFalse(eligible(campaign { copy(identifiedOnly = true) }, base, empty))
        assertTrue(eligible(campaign { copy(identifiedOnly = true) }, base.copy(userId = "u1"), empty))
        assertFalse(eligible(campaign { copy(sampleRate = 0.0) }, base, empty))
        // Sampling is deterministic per user and campaign.
        val r = unitHash("anon-1:cmp_1")
        assertTrue(eligible(campaign { copy(sampleRate = r + 0.001) }, base, empty))
        assertFalse(eligible(campaign { copy(sampleRate = r) }, base, empty))
    }

    @Test
    fun frequencyCapsAndGlobalCooldown() {
        val now = base.now
        fun shown(s: CampaignState, last: Long = now - 40 * DAY) = SurveyState(last, mapOf("cmp_1" to s))
        assertFalse(eligible(campaign(), base, shown(CampaignState(shown = now - 400 * DAY)))) // once
        val ua = campaign { copy(frequency = Frequency("until_answered", 30)) }
        assertTrue(eligible(ua, base, shown(CampaignState(shown = now - 40 * DAY, dismissed = now - 40 * DAY))))
        assertFalse(eligible(ua, base, shown(CampaignState(shown = now - 10 * DAY), now - 10 * DAY)))
        assertFalse(eligible(ua, base, shown(CampaignState(shown = now - 40 * DAY, answered = now - 40 * DAY))))
        val rec = campaign { copy(frequency = Frequency("recurring", 30)) }
        assertTrue(eligible(rec, base, shown(CampaignState(shown = now - 31 * DAY, answered = now - 31 * DAY))))
        assertFalse(eligible(rec, base, shown(CampaignState(shown = now - 29 * DAY))))
        // Another survey shown in the last day blocks everything.
        assertFalse(eligible(campaign("cmp_2"), base, SurveyState(last = now - GLOBAL_COOLDOWN_MS + 1000)))
    }

    @Test
    fun configDecodesServerShape() {
        val json = """{"v":1,"campaigns":[{"id":"c","version":2,"questions":[{"id":"q","type":"nps","prompt":"P","required":true,"lowLabel":"Not likely"}],
            "targeting":{"trigger":{"kind":"page","match":"Home*","delayMs":500},"sampleRate":0.5,"frequency":{"kind":"recurring","days":7}},"extra":1}],
            "feedback":{"enabled":true,"label":"Feedback","position":"bottom-right","screenshots":true,"branding":{"poweredBy":false,"accent":"#1f6fcf"}},
            "heatmaps":{"enabled":false,"sampleRate":1},"replay":{"enabled":false,"sampleRate":0.1,"requireConsent":true,"maskText":"all"}}"""
        val cfg = MetrickleJson.decodeFromString(SdkConfig.serializer(), json)
        assertEquals(1, cfg.v)
        assertEquals("Home*", cfg.campaigns[0].targeting.trigger.match)
        assertEquals(500L, cfg.campaigns[0].targeting.trigger.delayMs)
        assertEquals("Not likely", cfg.campaigns[0].questions[0].lowLabel)
        assertEquals("#1f6fcf", cfg.feedback.branding.accent)
    }

    @Test
    fun enginePageTriggerShowsOnceAndAnswersBecomeEvents() = runTest {
        val transport = FakeTransport()
        val storage = MemoryStore()
        val client = client(transport, storage)
        val shownSurveys = mutableListOf<ActiveSurvey>()
        client.surveys.onShow { shownSurveys += it }
        advanceUntilIdle()
        client.surveys.engine.setConfigOnClient(client, listOf(campaign { copy(trigger = Trigger("page", "/checkout*", 0)) }))

        client.screen("/pricing")
        advanceUntilIdle()
        assertEquals(0, shownSurveys.size)

        client.screen("/checkout/done")
        advanceUntilIdle()
        assertEquals(1, shownSurveys.size)
        val s = shownSurveys[0]
        s.shown()
        s.answer(s.campaign.questions[0], SurveyAnswer(score = 3))
        s.answer(s.campaign.questions[1], SurveyAnswer(text = "  The pay button did nothing  "))
        s.complete()
        advanceUntilIdle()

        // Frequency "once": never again, even on the trigger screen.
        client.screen("/checkout/again")
        advanceUntilIdle()
        assertEquals(1, shownSurveys.size)

        client.flushNow()
        val events = transport.events.filter { it["name"]!!.jsonPrimitive.content.startsWith("\$survey_") }
        assertEquals(listOf("\$survey_shown", "\$survey_answered", "\$survey_answered"), events.map { it["name"]!!.jsonPrimitive.content })
        val nps = events[1]["properties"]!!.jsonObject
        val why = events[2]["properties"]!!.jsonObject
        assertEquals(JsonPrimitive("cmp_1"), nps["campaign"])
        assertEquals(JsonPrimitive("nps"), nps["question"])
        assertEquals(JsonPrimitive("nps"), nps["type"])
        assertEquals(3, nps["score"]!!.jsonPrimitive.long.toInt())
        assertEquals(JsonNull, nps["completed"])
        assertEquals(JsonNull, nps["value"])
        assertEquals(JsonPrimitive("why"), why["question"])
        assertEquals(JsonPrimitive("The pay button did nothing"), why["text"])
        assertEquals(JsonPrimitive(true), why["completed"])
        assertEquals(nps["response"], why["response"])
        assertEquals(JsonPrimitive("/checkout/done"), events[1]["path"])
        val state = Json.parseToJsonElement(storage.data[Keys.SURVEYS]!!).jsonObject
        assertNotNull(state["c"]!!.jsonObject["cmp_1"]!!.jsonObject["shown"]!!.jsonPrimitive.long)
    }

    @Test
    fun dismissRecordsPosition() = runTest {
        val transport = FakeTransport()
        val client = client(transport)
        var survey: ActiveSurvey? = null
        client.surveys.onShow { survey = it }
        client.surveys.engine.setConfigOnClient(client, listOf(campaign()))
        advanceUntilIdle()
        survey!!.shown()
        survey!!.dismiss(0)
        advanceUntilIdle()
        client.flushNow()
        val dismissed = transport.events.single { it["name"]!!.jsonPrimitive.content == "\$survey_dismissed" }["properties"]!!.jsonObject
        assertEquals(0L, dismissed["at"]!!.jsonPrimitive.long)
        assertEquals(0L, dismissed["answered"]!!.jsonPrimitive.long)
    }

    @Test
    fun delayedTriggerWaits() = runTest {
        val client = client()
        var shown = 0
        client.surveys.onShow { shown++ }
        client.surveys.engine.setConfigOnClient(client, listOf(campaign { copy(trigger = Trigger("event", "purchase", 5_000)) }))
        client.track("purchase")
        testScheduler.advanceTimeBy(4_000)
        testScheduler.runCurrent()
        assertEquals(0, shown)
        advanceUntilIdle()
        assertEquals(1, shown)
    }

    @Test
    fun cookielessClientsNeverShowSurveys() = runTest {
        val client = client(storage = null)
        var shown = 0
        client.surveys.onShow { shown++ }
        client.surveys.engine.setConfigOnClient(client, listOf(campaign()))
        client.screen("/")
        advanceUntilIdle()
        assertEquals(0, shown)
    }
}

/** setConfig must run on the client's dispatcher, like a config fetch. */
internal fun SurveyEngine.setConfigOnClient(client: MetrickleClient, campaigns: List<CampaignConfig>) {
    client.post { setConfig(campaigns) }
}
