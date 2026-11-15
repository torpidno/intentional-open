# Pause roadmap

Pause is deliberately offline: no internet permission, no accounts, no cloud
sync. Every item below must preserve that boundary and keep history local.

Effort estimates: **S** = one focused change, **M** = a screen or persistence
change, **L** = new tracking or platform work.

## Shipped in this pass

- Ordered multi-friction defaults and per-app sequences.
- Local history screen with outcome filters and the full friction sequence.
- Durable session limits: persisted deadline, restart restore, boot/update
  rescheduling, and a notification fallback when the process or overlay is
  unavailable.
- Notification setup card and local notification channel.
- Room migration that preserves existing history while adding the sequence
  column, covered by a real v1→v2 SQLite migration test.

## Top five next

### 1. Reorder the friction sequence (S–M)

**Why:** The sequence is currently canonical: Breathing → Intention → Session
limit. Some users will want the time limit before the intention, or breathing
after a limit.

**How:** Store per-app and default sequences as ordered lists (not string sets),
add up/down controls or drag-and-drop in Settings and the app override dialog,
and keep the current order for existing preferences as the migration default.

**Done when:** A user can reorder any enabled sequence, the pause follows that
order, and legacy values still load in canonical order.

### 2. Save the current pause step across process death (S)

**Why:** Rotation is safe because the ViewModel survives, but a process kill
while the pause is visible restarts the sequence at step one.

**How:** Put `stepIndex`, selected reason, note, selected limit, and the mode
list in `SavedStateHandle`; add a small state machine/reducer so the transitions
are testable.

**Done when:** A process-death recreation resumes the same step and selections,
and the final action still starts the limit only once.

### 3. Multi-step ViewModel and Compose tests (M)

**Why:** The new sequence is the highest-risk logic in the app, and the current
instrumented tests cover single-mode pauses only.

**How:** Use fake settings/history/session dependencies to test unlock rules,
Next behavior, final-only session start, intention persistence, cancellation,
and process-death restoration. Add Compose tests for the per-app dialog, the
settings checkboxes, and the step header/CTA.

**Done when:** The sequence paths are exercised on JVM/Robolectric and the
instrumented suite covers at least one two-step pause on an emulator.

### 4. History retention and local export (S–M)

**Why:** The history screen is useful, but users need control over how long it
is kept and how to move it.

**How:** Surface a 30/90/forever retention setting and call the existing
`deleteOlderThan` DAO method; add JSON/CSV export and import through the Storage
Access Framework. No cloud provider.

**Done when:** Retention runs on a schedule, “Clear history” works, and an
export can be imported on a fresh install.

### 5. Schedules and temporary snooze (M)

**Why:** Monitoring every app at every hour is rarely what people want. The
current per-app switch is indefinite.

**How:** Evaluate local time windows at interception time; add 15-minute and
1-hour snoozes with automatic resume. Store windows and snooze deadlines in
DataStore.

**Done when:** A monitored app opens without a pause outside its window, and a
snoozed app resumes automatically without losing its sequence.

## Later product work

- **Daily total limits (L):** Track foreground time per app and enforce a daily
  budget alongside per-session limits.
- **Profiles (L):** Work, Evening, and Weekend bundles containing app sets,
  sequences, and schedules.
- **Optional strict mode (M):** A short delay or typed phrase before disabling
  Pause or removing a friction. Keep it opt-in and recoverable.
- **Manual pause and Quick Settings tile (S–M):** Start a breathing exercise on
  demand and offer a tile/shortcut for the master switch.
- **Session reminder upgrades (M):** One-minute warning, extend, close-app
  action, and a richer notification.
- **Stats ranges and trends (S–M):** Today/7-day/30-day views, per-app trends,
  and “most triggered” time of day.
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
- **Room schema export and migration tests (M):** Turn on `exportSchema`,
  commit schemas, and test 1→2 and future migrations with real SQLite.
- **CI and device matrix (M):** Build, unit tests, lint, release checks, and
  Compose/instrumented runs on API 26/35/36.
- **Platform updates (M–L):** target/compile SDK 36, AGP/Gradle/Kotlin updates,
  and a fresh Play target-SDK review.
- **Session fallback hardening (M):** Add a foreground-service or
  exact-alarm strategy if notification timing proves too loose on aggressive
  OEMs; keep the local-only constraint.
- **Local diagnostics (S–M):** An opt-in, exportable debug log for OEM
  accessibility/battery issues; no network and no screen content.
