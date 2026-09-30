package com.metrickle

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/*
 * Wire types of the Metrickle ingest protocol v1 (`packages/schema/src/ingest.ts`) and the
 * research config (`packages/schema/src/research.ts`). Field names match the TypeScript schema
 * exactly; optional fields are omitted when null.
 */

/** Shared JSON settings: nulls in classes are omitted, unknown server fields are ignored. */
internal val MetrickleJson: Json = Json {
    explicitNulls = false
    encodeDefaults = true
    ignoreUnknownKeys = true
    coerceInputValues = true
}

@Serializable
public enum class EventType {
    @SerialName("page") PAGE,
    @SerialName("screen") SCREEN,
    @SerialName("track") TRACK,
    @SerialName("identify") IDENTIFY,
}

/** One event. `properties` and `traits` hold string (≤1024), finite number, boolean or null values. */
@Serializable
public data class IngestEvent(
    val id: String,
    val type: EventType,
    val name: String,
    val ts: Long,
    val anonymousId: String? = null,
    val userId: String? = null,
    val sessionId: String? = null,
    val url: String? = null,
    val path: String? = null,
    val title: String? = null,
    val referrer: String? = null,
    val properties: JsonObject? = null,
    val traits: JsonObject? = null,
)

@Serializable
public data class Library(val name: String, val version: String)

@Serializable
public data class AppInfo(val version: String? = null, val build: String? = null)

@Serializable
public data class DeviceInfo(
    /** `mobile`, `tablet`, `tv` or `other`. */
    val type: String? = null,
    val model: String? = null,
    val os: String? = null,
    val osVersion: String? = null,
)

/** Screen size in dp. */
@Serializable
public data class ScreenSize(val width: Int, val height: Int)

@Serializable
public data class EventContext(
    val library: Library? = null,
    val platform: String = "android",
    val app: AppInfo? = null,
    val device: DeviceInfo? = null,
    val screen: ScreenSize? = null,
    val locale: String? = null,
    val timezone: String? = null,
    /** Accessibility flags from `A11Y_FLAGS`, omitted when none. */
    val a11y: List<String>? = null,
)

@Serializable
public data class IngestBatch(
    val writeKey: String? = null,
    val sentAt: Long,
    val context: EventContext,
    val events: List<IngestEvent>,
)

// --- Research config (GET /v1/config) ------------------------------------------------------------

@Serializable
public data class Question(
    val id: String,
    /** `nps`, `csat`, `ces`, `rating`, `choice` or `text`. */
    val type: String,
    val prompt: String,
    val required: Boolean = true,
    val choices: List<String>? = null,
    val multiple: Boolean? = null,
    val lowLabel: String? = null,
    val highLabel: String? = null,
    val placeholder: String? = null,
)

@Serializable
public data class Trigger(
    /** `load`, `page` (screen name, trailing `*` wildcard) or `event`. */
    val kind: String,
    val match: String? = null,
    val delayMs: Long = 0,
)

@Serializable
public data class Frequency(
    /** `once`, `until_answered` or `recurring`. */
    val kind: String = "once",
    val days: Int? = null,
)

@Serializable
public data class Targeting(
    val trigger: Trigger,
    val platforms: List<String>? = null,
    val appVersions: List<String>? = null,
    val a11y: List<String>? = null,
    val identifiedOnly: Boolean? = null,
    val sampleRate: Double = 1.0,
    val frequency: Frequency = Frequency(),
)

@Serializable
public data class CampaignConfig(
    val id: String,
    val questions: List<Question>,
    val targeting: Targeting,
    val thankYou: String? = null,
    val version: Int,
)

@Serializable
public data class Branding(val poweredBy: Boolean = true, val accent: String? = null)

@Serializable
public data class FeedbackSettings(
    /** Platforms the app takes feedback from; null (configs from before the setting) means all of them. */
    val platforms: List<String>? = null,
    val enabled: Boolean = false,
    val label: String = "Feedback",
    val position: String = "bottom-right",
    val screenshots: Boolean = true,
    val branding: Branding = Branding(),
)

@Serializable
public data class HeatmapSettings(val enabled: Boolean = false, val sampleRate: Double = 1.0)

@Serializable
public data class ReplaySettings(
    val enabled: Boolean = false,
    val sampleRate: Double = 0.1,
    val requireConsent: Boolean = true,
    val maskText: String = "all",
)

@Serializable
public data class SdkConfig(
    val v: Int,
    val campaigns: List<CampaignConfig> = emptyList(),
    val feedback: FeedbackSettings = FeedbackSettings(),
    val heatmaps: HeatmapSettings = HeatmapSettings(),
    val replay: ReplaySettings = ReplaySettings(),
)

// --- Feedback (POST /v1/feedback) ------------------------------------------------------------------

@Serializable
public data class FeedbackDevice(val type: String? = null, val os: String? = null, val model: String? = null)

@Serializable
public data class FeedbackSubmission(
    val writeKey: String? = null,
    val anonymousId: String? = null,
    val userId: String? = null,
    val sessionId: String? = null,
    /** `bug`, `confusing`, `idea`, `accessibility` or `other`. */
    val category: String,
    val message: String,
    val rating: Int? = null,
    val url: String? = null,
    val path: String? = null,
    val platform: String,
    val appVersion: String? = null,
    val device: FeedbackDevice? = null,
    val screen: ScreenSize? = null,
    val locale: String? = null,
    val a11y: List<String>? = null,
    val screenshot: String? = null,
)
