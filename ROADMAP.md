# Pause roadmap

Pause is deliberately offline: no internet permission, no accounts, no cloud
sync. Every item below must preserve that boundary and keep history local.

Effort estimates: **S** = one focused change, **M** = a screen or persistence
change, **L** = new tracking or platform work.

## Shipped in this pass

- Ordered multi-friction defaults and per-app sequences, including up/down
  controls and order-preserving history/active sequences.
- Process-death pause-step restore and startup reconciliation of interrupted
  session starts.
- Local history screen with outcome filters and the full friction sequence.
- Durable session limits: persisted deadline, restart restore, boot/update
  rescheduling, and a notification fallback when the process or overlay is
  unavailable.
- Notification setup card and local notification channel.
- Global active-hours schedule with selectable days and overnight windows.
- Per-app 15-minute / 1-hour snooze with automatic gate-level expiry.
- 30/90/forever history retention, clear history, and local CSV export/import
  through the Storage Access Framework.
- Room migration that preserves existing history while adding the sequence
  column, covered by a real v1→v2 SQLite migration test; exported v2 schema and
  CI on API 26/35.

## Top five next

### 1. Multi-step ViewModel and Compose tests (M)

**Why:** The new sequence is the highest-risk logic in the app, and the current
instrumented tests cover single-mode pauses only.

**How:** Use fake settings/history/session dependencies to test unlock rules,
Next behavior, final-only session start, intention persistence, cancellation,
and process-death restoration. Add Compose tests for the per-app dialog, the
settings checkboxes, and the step header/CTA.

**Done when:** The sequence paths are exercised on JVM/Robolectric and the
instrumented suite covers at least one two-step pause on an emulator.

### 2. Profiles (L)

**Why:** Work, Evening, and Weekend need different app sets, sequences, and
schedules. The current global schedule is a single window.

**How:** Store named profile bundles locally and switch them manually or by a
schedule. Keep one active profile at a time; no account or sync.

**Done when:** Switching profiles updates the gate's settings atomically and a
profile's schedule can activate it.

### 3. Daily total limits (L)

**Why:** A per-session limit does not cap a whole day of short visits.

**How:** Track foreground time locally per app and enforce a daily budget
before opening. Keep the data in Room and never sample screen content.

**Done when:** A budget expiry blocks a new pause with a clear local message and
resets at local midnight.

### 4. Stats ranges and trends (S–M)

**Why:** Today-only counts hide longer-term patterns.

**How:** Add 7-day and 30-day aggregates, per-app trends, and time-of-day
insights to the existing local Room queries.

**Done when:** The dashboard can switch ranges without any network call.

### 5. Optional strict mode (M)

**Why:** A friction can be bypassed by disabling Pause or removing the app.

**How:** Add an opt-in, recoverable delay or typed phrase before those changes.

**Done when:** Strict mode is clearly recoverable and never traps a user.

## Later product work

- **Manual pause and Quick Settings tile (S–M):** Start a breathing exercise on
  demand and offer a tile/shortcut for the master switch.
- **Session reminder upgrades (M):** One-minute warning, extend, close-app
  action, and a richer notification.
- **Custom durations (S):** Free-form breathing, grace, and limit values.
- **Guided onboarding and diagnostics (M):** First-run setup, OEM
  battery/autostart guidance, live service/overlay/notification health checks.

## Experience and accessibility

- **Theme preference (S):** System/Light/Dark and optional dynamic color;
  `PauseTheme` already accepts `dynamicColor`.
- **Accessibility polish (S–M):** Merge semantics for friction rows, verify
  TalkBack order, respect reduce-motion for the breathing ring, and test large
  fonts.
- **Localization/RTL (M):** The manifest supports RTL but copy is English-only.
- **FLAG_SECURE (S):** Hide pause/intention/history content from screenshots and
  the recents thumbnail.
- **Play Store accessibility disclosure (M):** Prominent disclosure and consent
  before enabling the accessibility service, if distributing publicly.

## Engineering and reliability

- **Sequence reducer (S–M):** Extract the multi-step logic from the ViewModel
  into a pure state machine with exhaustive tests.
- **Full sequence logging (shipped):** History now reads a sequence column;
  extend analytics/aggregates if the dashboard needs mode-specific insights.
- **Schema drift guard (shipped):** Room exports and commits v2; keep the
  real-SQLite migration test current when adding version 3.
- **CI and device matrix (shipped):** Unit, migration, Debug, Android-test APK,
  and API 26/35 instrumented runs. Add API 36 when the toolchain supports it.
- **Platform updates (M–L):** target/compile SDK 36, AGP/Gradle/Kotlin updates,
  and a fresh Play target-SDK review.
- **Session fallback hardening (M):** Add a foreground-service or
  exact-alarm strategy if notification timing proves too loose on aggressive
  OEMs; keep the local-only constraint.
- **Local diagnostics (S–M):** An opt-in, exportable debug log for OEM
  accessibility/battery issues; no network and no screen content.
