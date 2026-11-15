# Pause

An intentional app opener for Android. Pause intercepts the apps you choose and
adds a short moment of friction — a breath, an intention, a time limit, or an
ordered combination of them — before letting you continue.

Built with Kotlin, Jetpack Compose (Material 3), Hilt, Room and DataStore.
No internet permission, no analytics, no accounts, no cloud sync.

## Requirements

| Tool | Version |
| --- | --- |
| Android Studio | Ladybug (2024.2.1) or newer |
| JDK | 17 |
| Android SDK | Platform 35, Build Tools 35.0.0 |
| Min / target SDK | 26 / 35 |
| Gradle | 8.9 (wrapper included) |
| Kotlin | 2.0.21 |

## Build

```bash
# From the repository root
./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Install it on a connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A release build enables R8:

```bash
./gradlew :app:assembleRelease
```

Release signing reads `keystore.properties` from the project root (store file,
passwords and alias). Keep that file and the keystore private; both are ignored
by Git.

### Opening in Android Studio

1. **File → Open** and select the project root.
2. Let Gradle sync. `local.properties` is generated automatically by Studio;
   if you build from the command line, create it yourself:

   ```properties
   sdk.dir=/absolute/path/to/Android/sdk
   ```

3. Run the `app` configuration on an API 26+ device or emulator.

## First-run setup

Pause needs four system permissions. The app walks you through each one in
**Settings → Setup** and re-checks their status every time you return:

1. **Accessibility service** — detects when a monitored app enters the
   foreground. Enable *Pause app opener* under **Settings → Accessibility**.
2. **Display over other apps** — lets Pause draw the pause surface and the
   session-end reminder. Open the card's **Grant** button.
3. **Battery optimisation** — exempts Pause so the OS does not kill the
   service. Use **Grant**, or the *Battery settings* fallback on OEMs that do
   not support the direct request dialog.
4. **Notifications** — allows a local session-end reminder if Android kills the
   process, the screen is locked, or the overlay cannot be shown. This is still
   fully offline.

Then tap **Add apps**, search for the apps that pull you in, and select them.
Each app can use the global friction sequence or its own ordered sequence.

### Testing the interception

1. Add a test app (for example the browser) in **Add apps**.
2. Press home, then open that app.
3. The pause screen should appear within a fraction of a second.

If nothing appears, verify all four permissions are green and that the master
toggle on the home screen is on.

## How it works

```
AccessibilityService (TYPE_WINDOW_STATE_CHANGED only)
        │  package filter narrowed to monitored apps at runtime
        ▼
PauseInterventionActivity (full-screen, own task, excluded from recents)
        │
        ├── Breathing pause: end-timestamp countdown, CTA unlocks
        ├── Intention check: reason chip or note required
        ├── Session limit: 5 / 15 / 30 min, timer + reminder overlay
        └── Any enabled combination runs in order; intermediate steps use Next
        │
        ▼
Room (attempts, outcomes, intentions)  +  DataStore (settings)
```

- **Cooldown:** after continuing, the same app will not be intercepted again for
  the configured grace period (5/10/15 min). A short launch guard also absorbs
  the splash → main-activity event burst so the overlay cannot loop.
- **Durable sessions:** the active app, limit, and absolute end time are stored
  in DataStore. A restart restores the remaining timer; an AlarmManager
  fallback and boot/update receiver deliver a local notification if the app
  process is gone. The overlay remains the primary reminder while the process
  is alive.
- **Ignored sources:** launchers (resolved dynamically), System UI, the
  permission controller and Pause itself.
- **Lifecycle safety:** the service never shows the pause on the lock screen or
  with the display off, and it debounces duplicate window events.
- **Battery:** the XML config subscribes to window-state changes only, disables
  window-content retrieval, and the service narrows
  `AccessibilityServiceInfo.packageNames` to the selected apps. With no apps
  selected the framework wakes the service for nothing.

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

- `InterceptionGateTest` — enable/disable, monitored and paused apps, ignored
  packages, lock/screen-off, duplicate-event debounce, launch guard and
  cooldown policy.
- `CooldownTrackerTest` — per-app cooldowns, extension without shortening,
  exact expiry, clearing and invalid durations.
- `SessionLimitManagerTest` — virtual-time timer emission, cancellation,
  replacement, durable persistence, failure cleanup and restart restoration.
- `InterventionViewModelTest` — a gated persistence write proves the target-app
  effect waits for success; a failed write keeps the app closed, exposes the
  retry state and launches only after a successful retry. It also recreates the
  ViewModel from `SavedStateHandle` to prove the step, intention and limit
  survive process death.
- `PauseSettingsTest` — ordered/deduplicated default sequences, per-app
  sequences, and the empty-sequence fallback.
- `InterceptionRepositoryTest` — Robolectric + in-memory Room coverage of the
  local-day boundaries, attempt/walked-away counts, most-triggered app, the
  record-then-complete flow, and full-sequence history.
- `PauseDatabaseMigrationTest` — creates a real v1 SQLite database, runs the
  1→2 migration, and verifies old history survives while the sequence column
  becomes available.

The Robolectric test downloads an Android runtime jar on its first run.

### Instrumented tests (emulator)

```bash
./gradlew :app:connectedDebugAndroidTest
```

Requires a booted API 26+ emulator (API 35 recommended) with the English locale.

- `PauseScreenInstrumentedTest` launches the pause screen in breathing,
  intention and time-limit modes. It verifies the Continue CTA is locked until
  its condition is met, that walking away returns Home and records a
  `WALKED_AWAY` row, and that an intention or time limit unlocks Continue.
- `AccessibilityInterceptionInstrumentedTest` enables the real accessibility
  service, monitors the system Settings app, opens it, verifies the pause screen
  appears over it, closes the pause and checks the Room log.

The interception test writes secure accessibility settings and the overlay
app-op and temporarily enables Pause, then restores the accessibility service
list. Run it on a disposable test emulator, not a personal device. It is skipped
when the Settings package is unavailable.

## Project layout

```
app/src/main/java/com/pause/app/
├── data/
│   ├── local/          Room entity, DAO, database, type converters
│   ├── preferences/    DataStore settings + repository
│   └── repository/     Interception history, installed-app catalog
├── di/                 Hilt modules and qualifiers
├── domain/model/       FrictionMode, outcomes, UI models
├── service/            Accessibility service, interception gate, cooldowns,
│                       session timers, session-end overlay
├── ui/
│   ├── home/           Dashboard + ViewModel
│   ├── apps/           App picker + ViewModel
│   ├── history/        Local pause history + filters
│   ├── settings/       Permission onboarding, preferences
│   ├── intervention/   Full-screen pause Activity, ViewModel and UI
│   ├── components/     Shared Compose pieces
│   ├── navigation/     NavHost
│   └── theme/          Material 3 theme, dynamic color, dark mode
├── util/               Permission checks, settings intents, time source
├── MainActivity.kt
└── PauseApplication.kt

app/src/test/java/com/pause/app/
├── data/               Robolectric + in-memory Room stats tests
├── service/            Gate, cooldown and session-limit unit tests
├── ui/                 Pause ViewModel persistence-order tests
└── util/               Fake monotonic clock

app/src/androidTest/java/com/pause/app/
├── service/            Real accessibility interception test
├── testing/            Shell helpers, service controller, entry point access
└── ui/                 Pause-screen UI tests

app/src/debug/java/com/pause/app/testing/
└── PauseTestEntryPoint.kt   Hilt access for instrumented tests (debug only)
```

## Privacy

- `android.permission.INTERNET` is **not** declared. The app cannot make a
  network request, even accidentally.
- No analytics, crash reporting, ads or remote config SDKs.
- Session reminders use local Android notifications and AlarmManager; they do
  not make a network request.
- `allowBackup="false"` and `data_extraction_rules.xml` exclude all app data
  from cloud backup and device transfer.
- Package visibility uses a narrow `<queries>` filter for launcher activities,
  not `QUERY_ALL_PACKAGES`.

## Notes and limitations

- Accessibility behaviour varies slightly by OEM. On aggressive skins (Xiaomi,
  Samsung, Oppo), also allow autostart / background activity for Pause in the
  OEM battery settings, and keep the battery-optimisation exemption on.
- Android may restrict background activity starts from the service if the
  overlay permission is missing; the accessibility-service route plus the
  overlay grant is the reliable combination.
- Session timers and the current pause step/selections are persisted and
  restored after process death. A local notification is the fallback for a
  session reminder; Android can still cancel alarms after a force-stop or apply
  OEM-specific background restrictions.
- If a chosen session deadline cannot be persisted, Pause does not open the
  target app. The pause screen shows an error with Retry and Keep app closed
  actions; retry persists again before any launch.
- Notification delivery requires the notification permission on Android 13+.
  The overlay remains available when the process is alive and the device is
  unlocked.
- The app has no onboarding navigation flow beyond the settings screen; the
  dashboard surfaces a **Finish setting up** card until all permissions are on.

See [ROADMAP.md](ROADMAP.md) for the prioritized next steps.
