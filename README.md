# Pause

An intentional app opener for Android. Pause intercepts the apps you choose and
adds a short moment of friction — a breath, an intention, a time limit, or an
ordered combination of them — before letting you continue. Active hours,
temporary snoozes, retention controls, and local CSV history are also built in.

Built with Kotlin, Jetpack Compose (Material 3), Hilt, Room and DataStore.
No internet permission, no analytics, no accounts, no cloud sync.

[![Download the latest APK](https://img.shields.io/badge/%E2%AC%87%EF%B8%8F_Download-latest_APK-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/torpidno/intentional-open/releases/latest/download/Pause.apk)

## Requirements

| Tool | Version |
| --- | --- |
| Android Studio | Ladybug (2024.2.1) or newer |
| JDK | 17 |
| Android SDK | Platform 35, Build Tools 35.0.0 |
| Min / target SDK | 26 / 35 |
| Gradle | 8.9 (wrapper included) |
| Kotlin | 2.0.21 |

## Install the latest build

Tap the **Download latest APK** button above, or open the
[Latest APK release](https://github.com/torpidno/intentional-open/releases/latest).
Both resolve to the newest build of `main`, so the link never changes:

```
https://github.com/torpidno/intentional-open/releases/latest/download/Pause.apk
```

Open the downloaded `Pause.apk` on the device and allow installs from that
browser or file manager if Android asks. It is a universal **release build**
(R8 minified, signed with the project release key), not a Play Store upload.
`.github/workflows/release-apk.yml` rebuilds and re-uploads it on every push to
`main`.

Builds before 16 November 2026 were signed with the Android debug key, so
uninstall that copy once before installing a release-signed APK. Android refuses
to overwrite an app with a different signing key.

Every build is also kept as its own versioned release
(`v<versionName>-build.<run number>`, for example `v1.0.0-build.12`), so an older
APK stays downloadable from the [Releases](https://github.com/torpidno/intentional-open/releases)
page. The `latest` tag always follows the newest build.

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

## Signing

Release signing reads `keystore.properties` from the project root (store file,
passwords and alias). Keep that file and the keystore private; both are ignored
by Git:

```properties
storeFile=release-keystore.jks
storePassword=…
keyAlias=pause-release
keyPassword=…
```

CI signs with the same identity from repository secrets, so a downloaded APK and
a locally built one share a signature and install over each other:

| Secret | Contents |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | the keystore, base64 encoded (`base64 -w0 release-keystore.jks`) |
| `RELEASE_STORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | key alias, `pause-release` |
| `RELEASE_KEY_PASSWORD` | key password |

The workflow decodes the keystore into `keystore.properties` for the build and
never writes it to the repository. If `RELEASE_KEYSTORE_BASE64` is missing it
warns and falls back to a debug APK rather than failing the release job. Losing
the keystore means you can never publish an installable update again, so keep a
backup of `release-keystore.jks` somewhere outside this repository.

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
Each app can use the global friction sequence or its own ordered sequence. In
**Settings → Default friction** and in an app's sequence dialog, use the up/down
arrows to change the order. The order is stored as an ordered list; older
single-mode and unordered-set preferences still load in canonical order.

The same order is used by the live pause, the history row, and the per-app
dialog. Adding a mode appends it; moving uses the arrow controls, not the
mode's enum ordinal.

Other local controls:

- **Active hours** — choose days and a local start/end time. Outside the window,
  monitored apps open normally. Overnight windows are supported.
- **Snooze** — temporarily skip an individual app for 15 minutes or 1 hour. The
  deadline is checked at interception time, so it resumes automatically even if
  the process was killed.
- **History retention** — keep 30 days, 90 days, or forever. Pruning runs at app
  start, when History opens, and when the setting changes.
- **Export / Import** — write or read a local CSV through Android's system file
  picker. No storage permission, account, or network is involved.

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
        ├── Any enabled combination runs in order; intermediate steps use Next
        └── Active-hours schedule and per-app snooze are checked before launch
        │
        ▼
Room (attempts, outcomes, intentions)  +  DataStore (settings)
```

- **Cooldown:** after continuing, the same app will not be intercepted again for
  the configured grace period (5/10/15 min). A short launch guard also absorbs
  the splash → main-activity event burst so the overlay cannot loop.
- **Schedule and snooze:** the gate checks the global local-time window and the
  per-app snooze deadline before the device/cooldown checks. An inactive window
  or live snooze returns without showing the overlay; no alarm is needed for a
  snooze to expire.
- **History maintenance:** retention deletes rows older than the configured
  window through the local Room DAO. CSV export includes every row; import
  assigns fresh local IDs and can reconstruct history on a fresh install.
- **Durable sessions:** the active app, limit, absolute end time, and attempt
  id are stored in DataStore. A restart restores the remaining timer and repairs
  an interrupted start: the session's history row is completed and stale
  `PENDING` rows are closed. The app launch is not replayed automatically; the
  restored cooldown lets the user open the app without a duplicate pause, while
  the timer still enforces the limit. An AlarmManager fallback and boot/update
  receiver deliver a local notification if the process is gone. The overlay
  remains the primary reminder while the process is alive.
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
  packages, lock/screen-off, duplicate-event debounce, launch guard, cooldown,
  active-hours, and snooze policy.
- `CooldownTrackerTest` — per-app cooldowns, extension without shortening,
  exact expiry, clearing and invalid durations.
- `SessionLimitManagerTest` — virtual-time timer emission, cancellation,
  replacement, durable persistence, failure cleanup and restart restoration.
- `SessionStartupReconcilerTest` — real in-memory Room coverage of repairing an
  interrupted start: the active attempt becomes continued and stale pending
  rows become walked-away, while the restored cooldown is armed.
- `DataStoreSessionPersistenceTest` — a file-backed Preferences DataStore with
  an injected read failure and an injected first-write failure, verifying the
  production wrapper's read fallback and the manager's cleanup write.
- `InterventionViewModelTest` — a gated persistence write proves the target-app
  effect waits for success; a failed write keeps the app closed, exposes the
  retry state and launches only after a successful retry. It also recreates the
  ViewModel from `SavedStateHandle` to prove the step, intention and limit
  survive process death.
- `PauseSettingsTest` — order-preserving default and per-app sequences,
  legacy override decoding, reordering, schedule windows (including overnight),
  snooze deadlines, and the empty-sequence fallback.
- `SettingsRepositoryTest` — file-backed DataStore proof that the ordered
  default key, app append/reorder behavior, per-app order, legacy unordered-set
  fallback, schedule, snooze, and retention values persist correctly.
- `InterceptionRepositoryTest` — Robolectric + in-memory Room coverage of the
  local-day boundaries, attempt/walked-away counts, most-triggered app, the
  record-then-complete flow, full-sequence history, archive import/export, and
  retention deletion/clear.
- `HistoryCsvTest` — quoted commas, quotes, embedded newlines, CRLF, malformed
  rows, and header validation for the local archive.
- `HistoryRetentionManagerTest` — 30/90-day cutoffs and the forever policy.
- `PauseDatabaseMigrationTest` — creates a real v1 SQLite database, runs the
  1→2 migration, and verifies old history survives while the sequence column
  becomes available.
- Room exports the v2 schema to `app/schemas/`; it is committed as the baseline
  for future migrations and drift checks.

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
- `InterventionErrorDialogInstrumentedTest` renders the session-start error
  dialog with Compose on-device and verifies its modal semantics, Retry and
  Keep app closed actions, and focus on the dialog actions.
- `AccessibilityInterceptionInstrumentedTest` enables the real accessibility
  service, monitors the system Settings app, opens it, verifies the pause screen
  appears over it, closes the pause and checks the Room log.

The interception test writes secure accessibility settings and the overlay
app-op and temporarily enables Pause, then restores the accessibility service
list. Run it on a disposable test emulator, not a personal device. It is skipped
when the Settings package is unavailable.

## Continuous integration

[`.github/workflows/android.yml`](.github/workflows/android.yml) runs on every
push and pull request:

- JVM unit, migration, DataStore failure, CSV, and retention tests.
- Debug build and Android-test APK compilation.
- Instrumented pause, accessibility, and error-dialog tests on API 26 and API 35
  emulators, with reports uploaded when a run fails.

The emulator jobs need hardware acceleration. GitHub-hosted Ubuntu runners ship
`/dev/kvm`, but the `kvm` group has no members, so the workflow first installs a
udev rule that makes `/dev/kvm` usable. Without it the emulator silently falls
back to software emulation, where installing the APK alone exceeds ddmlib's
shell timeout and no test ever runs (`ShellCommandUnresponsiveException`).

[`.github/workflows/release-apk.yml`](.github/workflows/release-apk.yml) builds
the signed release APK on every push to `main` and publishes it as the
`Pause.apk` asset of the `latest` release, which is what the download button
serves. The same APK is attached to a versioned release tag as a snapshot.

## Project layout

```
app/src/main/java/com/pause/app/
├── data/
│   ├── local/          Room entity, DAO, database, type converters
│   ├── export/         Local CSV history archive
│   ├── preferences/    DataStore settings + repository
│   └── repository/     Interception history, retention, installed apps
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

.github/workflows/      Unit + API 26/35 emulator CI
app/schemas/            Exported Room schemas (v2 baseline)

app/src/test/java/com/pause/app/
├── data/               Room, DataStore, CSV, retention and rules tests
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
- History export/import uses Android's Storage Access Framework and a local CSV
  file. The archive never leaves the device unless the user chooses where to
  put it; there is no upload path.
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
