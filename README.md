# Metrickle for Android

Native Android SDK for [Metrickle](https://metrickle.com): accessibility-first UX research and conversion analytics. It follows the same [event model](https://metrickle.com/developers/events) as the other Metrickle SDKs, so a funnel, task or survey means the same thing on Android, iOS, Flutter and the web.

- `metrickle`: the core SDK. It covers events, sessions, automatic screens, rage taps, form errors, accessibility context, the survey engine (headless) and feedback. It needs Kotlin coroutines, kotlinx.serialization and `androidx.lifecycle:lifecycle-process`. It has no HTTP library (it uses `HttpURLConnection`).
- `metrickle-compose` adds Jetpack Compose helpers: `TrackScreen`, `Modifier.metrickleTag` and `MetrickleSurveyHost`, a built-in survey sheet that meets WCAG 2.2 AA.

minSdk 23, compileSdk 36.

## Install

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.metrickle:metrickle:0.2.0")
    implementation("com.metrickle:metrickle-compose:0.2.0") // optional, Compose apps
}
```

The library declares `INTERNET`. Its consumer ProGuard/R8 rules keep the serializable wire models.

## Quick start

### Views

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Metrickle.init(this, "mk_live_…", MetrickleOptions(debug = BuildConfig.DEBUG))
    }
}

// Anywhere, from any thread:
Metrickle.track("checkout_started", mapOf("plan" to "pro"))
Metrickle.identify("user_123", mapOf("plan" to "pro"))
Metrickle.formError(form = "signup", field = "email", reason = "invalid") // never the field's contents
Metrickle.reset() // on logout
```

Each Activity is tracked as a screen when it resumes. The screen name is the class name without `Activity`, so `CheckoutActivity` becomes `Checkout`. Class names stay the same in every locale. To choose a name or skip an Activity, implement `MetrickleScreen`:

```kotlin
class CheckoutActivity : AppCompatActivity(), MetrickleScreen {
    override val metrickleScreenName: String? = "Checkout" // null skips this Activity
}
```

Rage taps (3 taps within 1s inside 30dp) report the tapped view's id resource name as `selector`, and its content description as `text` (at most 80 characters). The SDK never reads text field contents. The SDK only observes the Activity window. Dialogs and popups have their own windows, so taps in them aren't observed.

Java:

```java
Metrickle.init(this, "mk_live_…");
Metrickle.track("checkout_started");
```

### Compose

Single-Activity Compose apps should turn off Activity screens and name each destination:

```kotlin
Metrickle.init(this, "mk_live_…", MetrickleOptions(automaticScreenTracking = false))

@Composable
fun CheckoutScreen() {
    TrackScreen("Checkout")
    Button(onClick = pay, modifier = Modifier.metrickleTag("pay_button")) { Text("Pay") }
}
```

`Modifier.metrickleTag(id)` sets `testTag` and gives rage taps a `selector`. Compose has no view ids.

## Revenue from Stripe or RevenueCat

Connect your RevenueCat project (or Stripe account) in the Metrickle dashboard under Integrations → Revenue. Purchases, renewals, refunds and cancels then arrive server-side on the person you identified, so a refund takes back the task they completed. Log in to RevenueCat with the same id:

```kotlin
Metrickle.identify(user.id)
Purchases.sharedInstance.logInWith(user.id)
// Or keep RevenueCat's id and name the Metrickle user:
// Purchases.sharedInstance.setAttributes(mapOf("metrickle_user_id" to user.id))
```

## Options

| Option | Default | |
|---|---|---|
| `host` | `https://in.metrickle.com` | Ingest origin |
| `cookieless` | `false` | Nothing is persisted: no anonymous or session id (the server derives a daily-rotating hash), no offline queue and no surveys |
| `flushIntervalMs` | `5000` | The SDK also flushes at 20 queued events and when the app goes to the background |
| `sessionTimeoutMs` | 30 min | Inactivity before a new session starts |
| `automaticScreenTracking` | `true` | `$screen` on Activity resume |
| `rageTaps` | `true` | `$rage_click` detection |
| `debug` | `false` | Logs every queued event and send result (Logcat tag `Metrickle`) |
| `beforeSend` | `null` | `BeforeSend { event -> event or null }` to edit or drop an event before it's queued |
| `appVersion` / `appBuild` | from the package | Override `versionName` / `versionCode` |

Other API: `register(props)` (super properties), `optOut()` / `optIn()`, `consent(replay = true)`, `flush()`, `refreshConfig()`, and `Metrickle.client` for the `MetrickleClient` (`identity()`, `getContext()`, `a11yFlags`, `config`, `suspend flushNow()`).

Automatic events: `$app_open` on launch and each return to the foreground, `$app_background` and then a flush, `$screen`, `$u_turn` (A → B → A within 7s), `$rage_click` and `$form_error`. The accessibility context is sent with each batch: `screen_reader` (TalkBack), `keyboard` (a hardware keyboard or Switch Access), `reduced_motion`, `high_contrast`, `inverted_colors`, `bold_text` and `large_text`.

The SDK persists events in `SharedPreferences("metrickle")`: up to 1000, for 7 days. Events queued before the app is killed are sent on the next launch. Failed sends retry with backoff (1s doubling to 60s).

## Surveys

Surveys are authored in the dashboard. The SDK decides when to show them: trigger, targeting, sampling, frequency caps and a 24h global cooldown. There are two ways to render them. **Without either, surveys never show.**

**Built-in sheet** (Compose): place the host once at your root, inside your `MaterialTheme`:

```kotlin
setContent {
    AppTheme {
        Box { AppNavHost(); MetrickleSurveyHost() }
    }
}
```

The sheet is a Material 3 `ModalBottomSheet`:

- It is announced once as "Survey" and focus moves to the question heading when it opens.
- Scale questions are radio groups of targets at least 48dp. Each option's label includes the end labels ("0, Not at all likely").
- Choice questions use native radio buttons or checkboxes. Text questions use a labelled multiline field.
- It follows font scale (it scrolls instead of truncating) and your theme's dark mode.
- It does not animate when animations are removed.
- It has a visible Close button, and back or Escape dismisses it. A thank-you message ends the survey.
- If the campaign has a follow-up and the answers qualify, the Submit button says "One moment…" (focus stays on it) while the sheet asks for the person's study link, for up to 5 seconds. With a link, it shows the invite: focus moves to its heading, and TalkBack reads it. "Choose a time" (a video call) or "Take part" (a self-guided test) opens the link in the browser. "No thanks" goes to the thank-you. Without a link, it shows the thank-you.
- It uses the brand accent only when the accent reaches 4.5:1 against the sheet. Otherwise it uses your theme's `primary`.

**Headless**: render with your own UI. A custom renderer takes precedence over the host.

```kotlin
val sub = Metrickle.surveys.onShow { survey ->   // main thread
    showMySurveyUi(survey.campaign,
        onShown = { survey.shown() },
        onAnswer = { q, a -> survey.answer(q, a) }, // SurveyAnswer(score =, values =, text =)
        onDone = { survey.complete() },
        onClose = { index -> survey.dismiss(index) })
}
Metrickle.surveys.show("cmp_123") // QA: show now, ignoring targeting
```

### Follow-ups (study invites)

A campaign can invite people who answered into a study: a booked video call (`moderated`) or a self-guided test on the web (`unmoderated`). `survey.followUp` is set only while the study is recruiting. `when` (the `condition` property) limits the invite to some answers, for example NPS 0–6. The built-in sheet handles all of this. With your own UI, after the last answer:

```kotlin
survey.complete()
val fu = survey.followUp
if (fu != null && survey.qualifies()) {
    lifecycleScope.launch {
        // The personal link, asked once per response. Null when the study is full or the request failed.
        val url = withTimeoutOrNull(5_000) { survey.invite() }
        if (url == null) return@launch showThankYou()
        showInvite(fu.prompt, fu.kind, fu.durationMin, fu.incentive) // then call survey.followUpOffered()
        // When they accept: survey.followUpAccepted(), then open url in the browser (Intent.ACTION_VIEW).
    }
}
```

`invite()` only returns `https` links (or `http` when your `host` is `http`). `followUpOffered()` and `followUpAccepted()` each record `$survey_follow_up` once per response.

## Feedback

```kotlin
lifecycleScope.launch {
    // Only after the user chose to attach a screenshot: it may contain personal data.
    val shot = Metrickle.feedback.captureScreenshot(activity) // JPEG 70, ≤ 1280px, data URL; null for FLAG_SECURE windows
    val result = Metrickle.feedback.submit(FeedbackCategory.BUG, "The pay button did nothing", rating = 2, screenshot = shot)
}
```

Feedback can be switched off per platform under Settings → Research in the dashboard. While it's off, `Metrickle.feedback.isEnabled` is false and `submit` sends nothing, so use it to hide your feedback button.

Session, screen, device, app version, locale and accessibility flags are attached automatically. Java callers can use `submitAsync(...)` and `captureScreenshotAsync(activity, callback)`. A built-in feedback sheet is future work.

## Privacy

- No Android Advertising ID, device serials or other hardware identifiers. `anonymousId` is a random UUID in app storage, removed when the app is uninstalled.
- The SDK never captures text field contents, only identifiers you pass (`formError`) and content descriptions.
- After `optOut()` the SDK makes no network calls at all. It clears the queue and removes the anonymous id and session id from the device. It keeps your own user id (from `identify`) and the consent choice. `isOptedOut` changes as soon as `optOut()` or `optIn()` returns.
- An opted-out device gets no anonymous id, also on later launches and after `reset()`. `optIn()` creates a new one and fetches the config again.
- `cookieless = true` persists nothing.

### Google Play Data safety (summary)

This depends on your configuration and on what you pass in `track`, `identify` and `feedback`. With the defaults:

| Data type | Collected | Shared | Purpose |
|---|---|---|---|
| App interactions (screens, taps, events) | Yes | No | Analytics |
| Device or other IDs (random app-scoped `anonymousId`, not a device ID) | Yes | No | Analytics |
| Diagnostics (OS version, device model, accessibility settings) | Yes | No | Analytics |
| User IDs (only if you call `identify`) | Optional | No | Analytics |
| Other user-generated content (survey answers, feedback text) | Optional | No | Analytics, app functionality |
| Photos or screenshots (only if you attach `captureScreenshot`) | Optional | No | App functionality |

Data is encrypted in transit (HTTPS). Users can opt out, and you can offer deletion through your Metrickle workspace.

## Development

```sh
./gradlew :metrickle:testDebugUnitTest :metrickle:assembleRelease :metrickle-compose:assembleRelease
```

Create `local.properties` with `sdk.dir=/path/to/Android/sdk`. The file is gitignored. The unit tests run on the plain JVM. They use the same survey vectors as `packages/sdk/src/surveys.test.ts`.
