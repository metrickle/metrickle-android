package com.metrickle

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClientTest {
    private fun name(e: kotlinx.serialization.json.JsonObject) = e["name"]!!.jsonPrimitive.content

    @Test
    fun rejectsReservedNames() = runTest {
        val c = client()
        assertThrows(IllegalArgumentException::class.java) { c.track("\$pageview") }
        c.track("signup")
    }

    @Test
    fun batchJsonShape() = runTest {
        val transport = FakeTransport()
        val c = client(transport)
        c.register(mapOf("plan" to "pro", "tier" to 1))
        c.screen("Home")
        c.track("signup", mapOf("tier" to 2, "nothing" to null, "nan" to Double.NaN, "long" to "x".repeat(2000)))
        c.track("bare")
        advanceUntilIdle()
        c.flushNow()

        val (method, url, _) = transport.requests.single()
        assertEquals("POST", method)
        assertEquals("https://in.example.com/v1/batch", url) // trailing slash stripped
        val h = transport.headers.single()
        assertEquals("application/json", h["content-type"])
        assertEquals("wk_test", h["x-metrickle-key"])
        assertTrue(h["user-agent"]!!.startsWith("metrickle-android/$SDK_VERSION (Android"))

        val b = transport.batches.single()
        assertEquals(JsonPrimitive("wk_test"), b["writeKey"])
        assertTrue(b["sentAt"]!!.jsonPrimitive.long > 0)
        val ctx = b["context"]!!.jsonObject
        assertEquals("""{"name":"metrickle-android","version":"$SDK_VERSION"}""", ctx["library"].toString())
        assertEquals(JsonPrimitive("android"), ctx["platform"])
        assertEquals("""{"version":"2.4.1","build":"241"}""", ctx["app"].toString())
        assertFalse("absent fields are omitted, not null", ctx.containsKey("a11y"))

        val events = b["events"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("\$screen", "signup", "bare"), events.map(::name))
        val screen = events[0]
        assertEquals(JsonPrimitive("screen"), screen["type"])
        assertEquals(JsonPrimitive("Home"), screen["path"])
        assertEquals(JsonPrimitive("Home"), screen["title"])
        assertFalse(screen.containsKey("referrer"))
        assertFalse(screen.containsKey("userId"))
        assertEquals(36, screen["id"]!!.jsonPrimitive.content.length)
        assertEquals(START, screen["ts"]!!.jsonPrimitive.long)

        val signup = events[1]
        assertEquals(JsonPrimitive("track"), signup["type"])
        assertEquals(JsonPrimitive("Home"), signup["path"]) // current screen
        val p = signup["properties"]!!.jsonObject
        assertEquals(JsonPrimitive("pro"), p["plan"])
        assertEquals(2L, p["tier"]!!.jsonPrimitive.long) // own properties win over super properties
        assertEquals(JsonNull, p["nothing"])
        assertFalse(p.containsKey("nan"))
        assertEquals(1024, p["long"]!!.jsonPrimitive.content.length)
        assertEquals(screen["sessionId"], signup["sessionId"])
        assertEquals(screen["anonymousId"], signup["anonymousId"])
    }

    @Test
    fun dropsEmptyPropertiesAndAppliesBeforeSend() = runTest {
        val transport = FakeTransport()
        val c = client(transport, options = MetrickleOptions(beforeSend = { e -> if (e.name == "secret") null else e.copy(userId = "scrubbed") }))
        c.track("secret")
        c.track("plain")
        advanceUntilIdle()
        c.flushNow()
        val e = transport.events.single()
        assertEquals("plain", name(e))
        assertFalse(e.containsKey("properties"))
        assertEquals(JsonPrimitive("scrubbed"), e["userId"])
    }

    @Test
    fun uTurnSentBeforeScreen() = runTest {
        val transport = FakeTransport()
        val c = client(transport)
        c.screen("A")
        advanceTimeBy(1_000)
        c.screen("B")
        advanceTimeBy(3_000)
        c.screen("A")
        advanceUntilIdle()
        c.flushNow()
        val events = transport.events
        assertEquals(listOf("\$screen", "\$screen", "\$u_turn", "\$screen"), events.map(::name))
        val turn = events[2]
        assertEquals(JsonPrimitive("B"), turn["path"])
        assertEquals(JsonPrimitive("A"), turn["properties"]!!.jsonObject["back_to"])
        assertEquals(3_000L, turn["properties"]!!.jsonObject["dwell_ms"]!!.jsonPrimitive.long)
        assertEquals(JsonPrimitive("B"), events[3]["referrer"])
    }

    @Test
    fun uTurnDetectorRules() {
        val d = UTurnDetector()
        assertNull(d.next("A", 0))
        assertNull(d.next("B", 1_000))
        assertNull(d.next("B", 1_500)) // same screen again is ignored
        assertEquals(UTurn("B", "A", 2_000), d.next("A", 3_000))
        val slow = UTurnDetector()
        slow.next("A", 0)
        slow.next("B", 0)
        assertNull(slow.next("A", 7_000)) // too long on B
    }

    @Test
    fun sessionTimeoutAndPassiveEvents() = runTest {
        val transport = FakeTransport()
        val c = client(transport)
        c.track("one")
        advanceTimeBy(29 * 60_000L)
        c.track("two")
        advanceTimeBy(31 * 60_000L)
        c.appBackground() // passive: keeps the expired id and does not extend it
        advanceTimeBy(1_000)
        c.track("three")
        advanceUntilIdle()
        c.flushNow()
        val ids = transport.events.map { name(it) to it["sessionId"]!!.jsonPrimitive.content }.toMap()
        assertEquals(ids["one"], ids["two"])
        assertEquals(ids["two"], ids["\$app_background"])
        assertNotEquals(ids["two"], ids["three"])
    }

    @Test
    fun retriesWithBackoffAndDrops4xx() = runTest {
        var status = 503
        val transport = FakeTransport(status = { status })
        val c = client(transport)
        c.track("a")
        advanceUntilIdle()
        c.flushNow()
        assertEquals(1, transport.requests.size)
        assertEquals(1_000L, c.retryDelayMs)
        assertEquals(1, c.queued.size)

        // Timer ticks during back-off are skipped.
        c.flushInternalOnClient(this)
        assertEquals(1, transport.requests.size)
        advanceTimeBy(1_001)
        c.flushInternalOnClient(this)
        assertEquals(2, transport.requests.size)
        assertEquals(2_000L, c.retryDelayMs)

        status = 429 // rate limited: retry
        advanceTimeBy(2_001)
        c.flushInternalOnClient(this)
        assertEquals(4_000L, c.retryDelayMs)
        assertEquals(1, c.queued.size)

        status = 400 // will never succeed: drop
        advanceTimeBy(4_001)
        c.flushInternalOnClient(this)
        assertEquals(0L, c.retryDelayMs)
        assertEquals(0, c.queued.size)
    }

    @Test
    fun backoffCapsAt60s() = runTest {
        val transport = FakeTransport(status = { 0 })
        val c = client(transport)
        c.track("a")
        advanceUntilIdle()
        repeat(10) { c.flushNow() }
        assertEquals(60_000L, c.retryDelayMs)
    }

    @Test
    fun failedEventsGoBackToTheFrontInOrder() = runTest {
        var status = 500
        val transport = FakeTransport(status = { status })
        val c = client(transport)
        c.track("a")
        c.track("b")
        advanceUntilIdle()
        c.flushNow()
        c.track("c")
        advanceUntilIdle()
        status = 200
        c.flushNow()
        assertEquals(listOf("a", "b", "c"), transport.batches.last()["events"]!!.jsonArray.map { name(it.jsonObject) })
    }

    @Test
    fun flushesAt20EventsAndSendsAtMost100PerRequest() = runTest {
        val transport = FakeTransport(status = { 500 })
        val c = client(transport)
        repeat(19) { c.track("e$it") }
        advanceUntilIdle()
        assertEquals(0, transport.requests.size)
        c.track("e19")
        advanceUntilIdle()
        assertEquals(1, transport.requests.size)
        repeat(230) { c.track("more$it") }
        advanceUntilIdle()
        transport.status = { 200 }
        c.appBackground() // unloading: sends everything, ignoring back-off
        advanceUntilIdle()
        assertTrue(transport.batches.all { it["events"]!!.jsonArray.size <= 100 })
        val sent = transport.delivered.flatMap { b -> b["events"]!!.jsonArray.map { it.jsonObject["id"] } }
        assertEquals(251, sent.size)
        assertEquals(251, sent.toSet().size)
        assertEquals(0, c.queued.size)
    }

    @Test
    fun queueIsPersistedCappedAndStaleEventsDropped() = runTest {
        val storage = MemoryStore()
        val old = IngestEvent(id = uuid(), type = EventType.TRACK, name = "old", ts = START - 8 * DAY_MS)
        val fresh = IngestEvent(id = uuid(), type = EventType.TRACK, name = "fresh", ts = START - DAY_MS)
        storage.set(Keys.QUEUE, MetrickleJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(IngestEvent.serializer()), listOf(old, fresh)))
        val transport = FakeTransport(status = { 0 })
        val c = client(transport, storage)
        advanceUntilIdle()
        assertEquals(listOf("fresh"), c.queued.map { it.name })
        repeat(1_005) { c.track("e$it") }
        advanceUntilIdle()
        assertEquals(1000, c.queued.size)
        assertEquals("e1004", c.queued.last().name)
        val saved = Json.parseToJsonElement(storage.data[Keys.QUEUE]!!).jsonArray
        assertEquals(1000, saved.size)
    }

    @Test
    fun identifyResetAndPersistence() = runTest {
        val storage = MemoryStore()
        val transport = FakeTransport()
        val c = client(transport, storage)
        advanceUntilIdle()
        val anon = storage.data[Keys.ANON]!!
        c.identify("u_1", mapOf("plan" to "pro"))
        advanceUntilIdle()
        assertEquals("u_1", storage.data[Keys.USER])
        c.flushNow()
        val e = transport.events.single()
        assertEquals(JsonPrimitive("identify"), e["type"])
        assertEquals(JsonPrimitive("\$identify"), e["name"])
        assertEquals(JsonPrimitive("pro"), e["traits"]!!.jsonObject["plan"])
        assertEquals(JsonPrimitive(anon), e["anonymousId"])

        c.reset()
        advanceUntilIdle()
        assertNull(storage.data[Keys.USER])
        assertNull(storage.data[Keys.SESSION])
        assertNotEquals(anon, storage.data[Keys.ANON])
        assertNull(c.identity().userId)

        // A new client restores the persisted ids.
        c.identify("u_2")
        advanceUntilIdle()
        val again = client(FakeTransport(), storage)
        advanceUntilIdle()
        assertEquals("u_2", again.identity().userId)
        assertEquals(storage.data[Keys.ANON], again.identity().anonymousId)
    }

    @Test
    fun optOutStopsEverything() = runTest {
        val storage = MemoryStore()
        val transport = FakeTransport()
        val c = client(transport, storage)
        c.track("before")
        c.optOut()
        c.track("after")
        c.refreshConfig()
        advanceUntilIdle()
        c.flushNow()
        assertEquals(0, transport.requests.size)
        assertEquals("1", storage.data[Keys.OPT_OUT])
        assertFalse(c.feedback.submit("bug", "broken").ok)

        c.optIn()
        c.track("back")
        advanceUntilIdle()
        c.flushNow()
        assertEquals(listOf("back"), transport.events.map(::name))
        assertNull(storage.data[Keys.OPT_OUT])
    }

    @Test
    fun cookielessOmitsIds() = runTest {
        val transport = FakeTransport()
        val c = client(transport, storage = null)
        c.identify("u_1")
        c.track("x")
        advanceUntilIdle()
        c.flushNow()
        for (e in transport.events) {
            assertFalse(e.containsKey("anonymousId"))
            assertFalse(e.containsKey("sessionId"))
        }
    }

    @Test
    fun consentIsPersisted() = runTest {
        val storage = MemoryStore()
        val c = client(storage = storage)
        c.consent(replay = true)
        advanceUntilIdle()
        assertEquals("replay", storage.data[Keys.CONSENT])
        assertTrue(c.hasConsent(ConsentKind.REPLAY))
        c.consent(replay = false)
        advanceUntilIdle()
        assertEquals("", storage.data[Keys.CONSENT])
    }

    @Test
    fun formErrorsCollapseWithin1500ms() = runTest {
        val transport = FakeTransport()
        val c = client(transport)
        c.formError("signup", "email", "invalid")
        c.formError("signup", "email", "invalid")
        c.formError("signup", "password", "too_short")
        advanceTimeBy(1_600)
        c.formError("signup", "email", "invalid")
        advanceUntilIdle()
        c.flushNow()
        val errs = transport.events.filter { name(it) == "\$form_error" }
        assertEquals(3, errs.size)
        assertEquals("""{"form":"signup","field":"email","reason":"invalid"}""", errs[0]["properties"].toString())
    }

    @Test
    fun rageTapDetector() {
        val d = RageTapDetector()
        assertFalse(d.tap(0, 100f, 100f))
        assertFalse(d.tap(200, 110f, 95f))
        assertTrue(d.tap(400, 105f, 120f))
        assertFalse(d.tap(500, 105f, 120f)) // fourth tap in the same burst doesn't fire again
        val far = RageTapDetector()
        far.tap(0, 0f, 0f)
        far.tap(100, 50f, 0f)
        assertFalse(far.tap(200, 100f, 0f))
        val slow = RageTapDetector()
        slow.tap(0, 0f, 0f)
        slow.tap(600, 0f, 0f)
        assertFalse(slow.tap(1_100, 0f, 0f))
    }

    @Test
    fun configFetchAndFeedback() = runTest {
        val transport = FakeTransport(configBody = """{"v":1,"campaigns":[],"feedback":{"branding":{"poweredBy":true,"accent":"#1f6fcf"}}}""")
        val c = client(transport)
        c.screen("Settings")
        c.refreshConfig()
        advanceUntilIdle()
        assertEquals("https://in.example.com/v1/config?key=wk_test", transport.requests.first { it.second.contains("config") }.second)
        assertEquals("#1f6fcf", c.config?.feedback?.branding?.accent)

        val r = c.feedback.submit(FeedbackCategory.BUG, "  Pay button does nothing ", rating = 2)
        assertTrue(r.ok)
        assertEquals("fb_1", r.id)
        val body = Json.parseToJsonElement(transport.requests.last().third!!).jsonObject
        assertEquals(JsonPrimitive("bug"), body["category"])
        assertEquals(JsonPrimitive("Pay button does nothing"), body["message"])
        assertEquals(JsonPrimitive("Settings"), body["path"])
        assertEquals(JsonPrimitive("android"), body["platform"])
        assertEquals(JsonPrimitive("2.4.1"), body["appVersion"])
        assertEquals(2L, body["rating"]!!.jsonPrimitive.long)
        assertFalse(body.containsKey("screenshot"))
    }

    @Test
    fun feedbackSwitchedOffForAndroid() = runTest {
        val transport = FakeTransport(configBody = """{"v":1,"campaigns":[],"feedback":{"platforms":["web","ios"],"enabled":true}}""")
        val c = client(transport)
        assertTrue(c.feedback.isEnabled)
        c.refreshConfig()
        advanceUntilIdle()
        assertFalse(c.feedback.isEnabled)
        assertFalse(c.feedback.submit(FeedbackCategory.BUG, "Broken").ok)
        assertTrue(transport.requests.none { it.second.endsWith("/v1/feedback") })
    }

    @Test
    fun ignoresConfigWithOtherVersion() = runTest {
        val transport = FakeTransport(configBody = """{"v":2,"campaigns":[]}""")
        val c = client(transport)
        c.refreshConfig()
        advanceUntilIdle()
        assertNull(c.config)
    }
}

/** A timer tick: a non-forced flush on the client's dispatcher. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun MetrickleClient.flushInternalOnClient(scope: kotlinx.coroutines.test.TestScope) {
    post { flushInternal(unloading = false) }
    scope.advanceUntilIdle()
}
