package com.metrickle

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import kotlin.math.abs

internal const val MAX_PROPERTIES = 64
internal const val MAX_PROPERTY_STRING = 1024
internal const val MAX_KEY = 128

internal fun uuid(): String = UUID.randomUUID().toString()

/**
 * Converts caller properties to the wire shape: string (≤1024 chars), finite number, boolean or
 * null; at most 64 keys. Other values are sent as their `toString()`; non-finite numbers are dropped.
 */
internal fun toJsonProps(props: Map<String, Any?>?): LinkedHashMap<String, JsonElement> {
    val out = LinkedHashMap<String, JsonElement>()
    if (props == null) return out
    for ((k, v) in props) {
        if (out.size >= MAX_PROPERTIES) break
        val key = k.take(MAX_KEY)
        out[key] = when (v) {
            null -> JsonNull
            is Boolean -> JsonPrimitive(v)
            is Double -> if (v.isFinite()) JsonPrimitive(v) else continue
            is Float -> if (v.isFinite()) JsonPrimitive(v) else continue
            is Number -> JsonPrimitive(v)
            is JsonPrimitive -> v
            else -> JsonPrimitive(v.toString().take(MAX_PROPERTY_STRING))
        }
    }
    return out
}

/** A u-turn: the user bounced off `from` back to `to` after `dwellMs`. */
internal data class UTurn(val from: String, val to: String, val dwellMs: Long)

internal const val U_TURN_MS = 7_000L

/**
 * U-turn detection (`uturn.ts`): a user lands on A, moves to B, and comes straight back to A.
 * A short stay on B usually means B was not what they expected.
 */
internal class UTurnDetector(private val thresholdMs: Long = U_TURN_MS) {
    private var prev: Pair<String, Long>? = null
    private var cur: Pair<String, Long>? = null

    fun next(path: String, now: Long): UTurn? {
        val c = cur
        if (c?.first == path) return null
        val p = prev
        val hit = if (p != null && c != null && p.first == path && now - c.second < thresholdMs) UTurn(c.first, path, now - c.second) else null
        prev = c
        cur = path to now
        return hit
    }
}

/** Three taps within 1s inside a 30dp box (same rule as the web SDK's rage clicks). Positions in dp. */
internal class RageTapDetector(private val windowMs: Long = 1_000, private val radius: Float = 30f) {
    private data class Tap(val t: Long, val x: Float, val y: Float)
    private var taps = ArrayList<Tap>()

    /** Records a tap; true exactly when it is the third in a burst. */
    fun tap(now: Long, x: Float, y: Float): Boolean {
        taps = taps.filterTo(ArrayList()) { now - it.t < windowMs && abs(it.x - x) < radius && abs(it.y - y) < radius }
        taps.add(Tap(now, x, y))
        return taps.size == 3
    }
}

/** Drops repeats of the same key within a window (`$form_error` bursts from one submit attempt). */
internal class Deduper(private val windowMs: Long = 1_500) {
    private val seen = HashMap<String, Long>()

    fun accept(key: String, now: Long): Boolean {
        val last = seen[key]
        if (last != null && now - last < windowMs) return false
        seen[key] = now
        if (seen.size > 256) seen.entries.removeAll { now - it.value >= windowMs }
        return true
    }
}
