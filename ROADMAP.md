# Pause roadmap

Pause is deliberately offline: no internet permission, no accounts, no cloud
sync. Every item below must preserve that boundary and keep history local.

Effort estimates: **S** = one focused change, **M** = a screen or persistence
change, **L** = new tracking or platform work.

## Shipped

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
- **Multi-step ViewModel, Compose, and emulator tests:** JVM/Robolectric
  coverage of `InterventionViewModel` (unlock rules, 3-step sequences,
  final-only session start, mid-sequence walk-away, daily-limit blocking, and
  process-death restoration) and `HomeViewModel` (range switching, profile
  selection, daily limits, midnight rollover), Compose UI tests for
  `InterventionScreen`, `FrictionSequenceEditor`, `AppFrictionDialog`,
  `DailyLimitDialog`, and `StrictModeChallengeDialog`, plus a two-step
  (`INTENTION → TIME_LIMIT`) emulator test in `PauseScreenInstrumentedTest`.
- **Profiles:** Named local configuration bundles (monitored/paused apps,
  default and per-app friction sequences, and weekday/time schedules) with Work,
  Evening, Weekend, and custom presets, atomic DataStore profile switching on
  Home and Settings, and optional schedule-based auto-switching.
- **Daily total limits:** Optional per-app daily usage budgets backed by
  Android's `UsageStatsManager` (with an optional Usage Access setup card; never
  reads screen content or accesses the network), local Room v3 `daily_usage`
  storage (`MIGRATION_2_3` + exported `3.json` schema), Home usage badges and
  limit picker, and a midnight-resetting budget-blocked pause screen.
- **Stats ranges and trends:** Local Room range queries powering **Today**,
  **7 days**, and **30 days** dashboard views with walk-away rates, top
  triggered apps, and timezone-accurate time-of-day insights.
- **Optional strict mode:** Opt-in, always-recoverable challenge (countdown
  delay or always-displayed typed phrase) guarding weakening changes across
  Home, App Picker, and Settings while letting tightening actions apply
  immediately.
- Room migrations preserving existing history through v1→v2→v3, covered by real
  SQLite migration tests (`1→3` and `2→3`), exported v2/v3 schemas, and CI on
  API 26/35.

## Next product work

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
- **Schema drift guard (shipped):** Room exports and commits v2 and v3 schemas;
  keep the real-SQLite migration tests current when adding future versions.
- **CI and device matrix (shipped):** Unit, migration, DataStore, Compose UI,
  Debug, Android-test APK, and API 26/35 instrumented runs. Add API 36 when the
  toolchain supports it.
- **Platform updates (M–L):** target/compile SDK 36, AGP/Gradle/Kotlin updates,
  and a fresh Play target-SDK review.
- **Session fallback hardening (M):** Add a foreground-service or
  exact-alarm strategy if notification timing proves too loose on aggressive
  OEMs; keep the local-only constraint.
- **Local diagnostics (S–M):** An opt-in, exportable debug log for OEM
  accessibility/battery issues; no network and no screen content.
