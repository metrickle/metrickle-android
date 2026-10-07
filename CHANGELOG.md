## 0.2.0

- Survey follow-ups: a campaign can invite people who answered into a study (a booked video call or a self-guided test). The built-in Compose sheet shows the invite after the last answer when the answers qualify, and opens the link in the browser. Headless renderers get `followUp`, `qualifies()`, `invite()`, `followUpOffered()` and `followUpAccepted()`.
- Opting out now removes the anonymous id and session id from the device. An opted-out device gets no new anonymous id on launch or after `reset()`; `optIn()` creates one and fetches the config again.
- `optOut()` and `optIn()` both take effect at once: `isOptedOut` is up to date when they return.
- Feedback can be switched off per platform in the dashboard. While it's off, `feedback.isEnabled` is false and `submit` sends nothing. (This shipped in 0.1.0 but wasn't listed.)

## 0.1.0

- Initial release: events, automatic Activity screens and `TrackScreen` for Compose, sessions, a persisted offline queue, TalkBack and font scale context, rage taps, u-turns, form errors, surveys (headless and a built-in WCAG 2.2 AA Compose sheet via `metrickle-compose`) and feedback with an optional screenshot.
