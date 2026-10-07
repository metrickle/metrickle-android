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
import org.junit.Assert.assertNull
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

    // --- Follow-ups ------------------------------------------------------------------------------

    @Test
    fun followUpMatchingVectors() {
        val nps = FollowUpWhen(questionId = "nps", min = 0.0, max = 6.0)
        fun score(n: Int?) = AnswerFacts(n?.toDouble(), null)
        fun values(vararg v: String) = AnswerFacts(null, v.toList())
        val cases = listOf(
            Triple(null, emptyMap(), true),
            Triple(nps, mapOf("nps" to score(3)), true),
            Triple(nps, mapOf("nps" to score(0)), true),
            Triple(nps, mapOf("nps" to score(6)), true),
            Triple(nps, mapOf("nps" to score(7)), false),
            Triple(FollowUpWhen(questionId = "nps", min = 9.0), mapOf("nps" to score(10)), true),
            Triple(nps, emptyMap(), false),
            Triple(nps, mapOf("nps" to score(null)), false),
            Triple(FollowUpWhen(questionId = "why", choices = listOf("Price", "Speed")), mapOf("why" to values("Speed", "Other")), true),
            Triple(FollowUpWhen(questionId = "why", choices = listOf("Price")), mapOf("why" to values("Speed")), false),
            Triple(FollowUpWhen(questionId = "why", choices = listOf("Price")), mapOf("why" to score(3)), false),
        )
        for ((condition, answers, expected) in cases) {
            assertEquals("$condition $answers", expected, followUpMatches(condition, answers))
        }
    }

    @Test
    fun followUpDecodesAndAMalformedOneKeepsTheCampaign() {
        fun cfg(followUp: String) = MetrickleJson.decodeFromString(
            SdkConfig.serializer(),
            """{"v":1,"campaigns":[{"id":"c","version":1,"questions":[{"id":"nps","type":"nps","prompt":"P"}],
                "targeting":{"trigger":{"kind":"load"}},"followUp":$followUp}]}""",
        )
        val ok = cfg("""{"studyId":"std_1","kind":"moderated","prompt":"Talk to us?","when":{"questionId":"nps","max":6},"incentive":"A gift card","durationMin":30}""")
        val fu = ok.campaigns.single().followUp!!
        assertEquals("std_1", fu.studyId)
        assertEquals(6.0, fu.condition!!.max!!, 0.0)
        assertEquals(30, fu.durationMin)
        assertEquals("A gift card", fu.incentive)
        for (bad in listOf("""{"kind":"moderated","prompt":"x"}""", """{"studyId":"s","kind":"other","prompt":"x"}""", "42", "\"nope\"", "null", """{"studyId":"s","kind":"unmoderated","prompt":"x","when":"bad"}""")) {
            val c = cfg(bad).campaigns.single()
            assertEquals("c", c.id)
            assertNull(bad, c.followUp)
        }
    }

    private val moderated = FollowUpConfig(studyId = "std_1", kind = "moderated", prompt = "Talk to us?", condition = FollowUpWhen("nps", max = 6.0), durationMin = 30)

    private suspend fun kotlinx.coroutines.test.TestScope.followUpSurvey(
        followUp: FollowUpConfig?,
        transport: FakeTransport = FakeTransport(),
        options: MetrickleOptions = MetrickleOptions(host = "https://in.example.com/"),
    ): Pair<MetrickleClient, ActiveSurvey> {
        val client = client(transport, options = options)
        var survey: ActiveSurvey? = null
        client.surveys.onShow { survey = it }
        client.surveys.engine.setConfigOnClient(client, listOf(campaign().copy(followUp = followUp)))
        advanceUntilIdle()
        return client to survey!!
    }

    private fun invites(t: FakeTransport) = t.requests.filter { it.second.endsWith("/v1/studies/invite") }

    @Test
    fun qualifyingResponseGetsALinkOnceAndRecordsFollowUpEvents() = runTest {
        val transport = FakeTransport()
        val (client, s) = followUpSurvey(moderated, transport)
        client.identify("u_9")
        s.shown()
        s.answer(s.campaign.questions[0], SurveyAnswer(score = 4))
        s.complete()
        assertEquals("std_1", s.followUp?.studyId)
        assertTrue(s.qualifies()) // sees the answer at once, before the SDK thread has run
        assertEquals("https://app.example.com/s/abc", s.invite())
        // Asked once per response, however often the renderer calls it.
        assertEquals("https://app.example.com/s/abc", s.invite())
        assertEquals(1, invites(transport).size)
        val i = transport.requests.indexOfFirst { it.second.endsWith("/v1/studies/invite") }
        assertEquals("POST", transport.requests[i].first)
        assertEquals("https://in.example.com/v1/studies/invite", transport.requests[i].second)
        val headers = transport.headers[i]
        assertEquals("wk_test", headers["x-metrickle-key"])
        assertEquals("application/json", headers["content-type"])
        assertTrue(headers["user-agent"]!!.startsWith("metrickle-android/$SDK_VERSION"))
        val body = Json.parseToJsonElement(transport.requests[i].third!!).jsonObject

        s.followUpOffered()
        s.followUpOffered()
        s.followUpAccepted()
        s.followUpAccepted()
        advanceUntilIdle()
        client.flushNow()
        val response = transport.events.first { it["name"]!!.jsonPrimitive.content == "\$survey_answered" }["properties"]!!.jsonObject["response"]!!
        assertEquals(
            mapOf(
                "writeKey" to JsonPrimitive("wk_test"),
                "studyId" to JsonPrimitive("std_1"),
                "campaignId" to JsonPrimitive("cmp_1"),
                "response" to response,
                "anonymousId" to JsonPrimitive(client.identity().anonymousId!!),
                "userId" to JsonPrimitive("u_9"),
            ),
            body,
        )
        val fu = transport.events.filter { it["name"]!!.jsonPrimitive.content == "\$survey_follow_up" }.map { it["properties"]!!.jsonObject }
        val expected = listOf(false, true).map {
            mapOf("campaign" to JsonPrimitive("cmp_1"), "version" to JsonPrimitive(1), "response" to response, "study" to JsonPrimitive("std_1"), "accepted" to JsonPrimitive(it))
        }
        assertEquals(expected, fu)
    }

    @Test
    fun noInviteForANonMatchingAnswerAFullStudyAnUnsafeLinkOrNoFollowUp() = runTest {
        // Doesn't qualify: no request at all.
        val t1 = FakeTransport()
        val (_, a) = followUpSurvey(moderated, t1)
        a.answer(a.campaign.questions[0], SurveyAnswer(score = 9))
        assertFalse(a.qualifies())
        assertNull(a.invite())
        assertEquals(0, invites(t1).size)

        // 409 study full, a 200 instead of 201, a network error.
        for (reply in listOf(HttpResponse(409, "{}"), HttpResponse(200, "{\"url\":\"https://x.example/s\"}"), HttpResponse(0))) {
            val t = FakeTransport(invite = reply)
            val (_, b) = followUpSurvey(moderated, t)
            b.answer(b.campaign.questions[0], SurveyAnswer(score = 2))
            assertNull(b.invite())
            assertEquals(1, invites(t).size)
        }

        // Unsafe links: javascript:, http from an https host, garbage.
        for (url in listOf("javascript:alert(1)", "http://app.example.com/s/abc", "not a url")) {
            val t = FakeTransport(invite = HttpResponse(201, "{\"url\":\"$url\"}"))
            val (_, c) = followUpSurvey(moderated, t)
            c.answer(c.campaign.questions[0], SurveyAnswer(score = 2))
            assertNull(url, c.invite())
        }

        // No follow-up configured: nothing to offer, nothing recorded.
        val t4 = FakeTransport()
        val (client4, d) = followUpSurvey(null, t4)
        assertFalse(d.qualifies())
        assertNull(d.invite())
        d.followUpOffered()
        d.followUpAccepted()
        advanceUntilIdle()
        client4.flushNow()
        assertFalse(t4.events.any { it["name"]!!.jsonPrimitive.content == "\$survey_follow_up" })
        assertEquals(0, invites(t4).size)
    }

    @Test
    fun httpLinksOnlyFromAnHttpHostAndNoInviteWhenOptedOut() = runTest {
        val t = FakeTransport(invite = HttpResponse(201, "{\"url\":\"http://localhost:8787/s/abc\"}"))
        val (_, s) = followUpSurvey(moderated.copy(condition = null), t, MetrickleOptions(host = "http://localhost:8787"))
        assertTrue(s.qualifies()) // no condition: every response qualifies
        assertEquals("http://localhost:8787/s/abc", s.invite())

        val t2 = FakeTransport()
        val (client2, s2) = followUpSurvey(moderated.copy(condition = null), t2)
        client2.optOut()
        assertNull(s2.invite())
        assertEquals(0, invites(t2).size)
    }

    @Test
    fun safeUrlRules() {
        assertEquals("https://a.example/s?x=1", safeUrl("https://a.example/s?x=1", "https://in.example.com"))
        assertNull(safeUrl("http://a.example/s", "https://in.example.com"))
        assertEquals("http://a.example/s", safeUrl("http://a.example/s", "http://localhost:8787"))
        assertNull(safeUrl("javascript:alert(1)", "http://localhost:8787"))
        assertNull(safeUrl("intent://x#Intent;end", "https://in.example.com"))
        assertNull(safeUrl(null, "https://in.example.com"))
    }
}

/** setConfig must run on the client's dispatcher, like a config fetch. */
internal fun SurveyEngine.setConfigOnClient(client: MetrickleClient, campaigns: List<CampaignConfig>) {
    client.post { setConfig(campaigns) }
}
