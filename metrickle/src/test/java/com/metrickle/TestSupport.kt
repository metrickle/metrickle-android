package com.metrickle

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Records requests and answers with scripted statuses (default 200). */
class FakeTransport(
    var status: (String) -> Int = { 200 },
    var configBody: String? = null,
    /** Answer to `POST /v1/studies/invite`. */
    var invite: HttpResponse = HttpResponse(201, "{\"url\":\"https://app.example.com/s/abc\"}"),
) : Transport {
    val requests = mutableListOf<Triple<String, String, String?>>()
    val headers = mutableListOf<Map<String, String>>()
    /** Batches answered with a 2xx. */
    val delivered = mutableListOf<JsonObject>()

    override suspend fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
        requests += Triple(method, url, body)
        this.headers += headers
        if (url.contains("/v1/config")) return if (configBody != null) HttpResponse(200, configBody) else HttpResponse(404)
        if (url.endsWith("/v1/studies/invite")) return invite
        val code = status(url)
        if (code in 200..299 && url.endsWith("/v1/batch")) delivered += Json.parseToJsonElement(body!!).jsonObject
        return HttpResponse(code, "{\"id\":\"fb_1\"}")
    }

    val batches: List<JsonObject>
        get() = requests.filter { it.second.endsWith("/v1/batch") }.map { Json.parseToJsonElement(it.third!!).jsonObject }

    val events: List<JsonObject> get() = batches.flatMap { b -> b["events"]!!.jsonArray.map { it.jsonObject } }
}

const val START = 1_700_000_000_000L

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun TestScope.client(
    transport: Transport = FakeTransport(),
    storage: KeyValueStore? = MemoryStore(),
    options: MetrickleOptions = MetrickleOptions(host = "https://in.example.com/"),
    context: EventContext = EventContext(platform = "android", app = AppInfo("2.4.1", "241")),
): MetrickleClient {
    val dispatcher = StandardTestDispatcher(testScheduler)
    return MetrickleClient(
        ClientEnv(
            writeKey = "wk_test",
            options = options,
            context = context,
            storage = storage,
            transport = transport,
            userAgent = "metrickle-android/$SDK_VERSION (Android 14; Pixel 8)",
            dispatcher = dispatcher,
            mainDispatcher = dispatcher,
            clock = { START + testScheduler.currentTime },
            startTimer = false,
        ),
    )
}
