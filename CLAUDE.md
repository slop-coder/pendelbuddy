# Pendel Buddy: notes for Claude

Android app (Kotlin + Compose) for commuting by train in Switzerland. Package and applicationId
`io.github.slop_coder.pendelbuddy`; every Android app of this account uses `io.github.slop_coder.<app>`.
Public repo, Unlicense. What the app does is in `README.md`.

## Rules

- **No personal data in the repo.** Tests, fixtures, code and comments never contain the user's real commute
  (home, work, stations in between, their IDs, lines or train numbers). Use the made-up network: Talwil,
  Musterdorf (8599002 / `ch:1:sloid:99002`), Musterdorf Post (8599001), Zwischenau (8599007), Beispielstadt
  (8599003), Testwil (8599004), Endhausen. A real API answer becomes a fixture only after renaming places,
  station IDs, lines, train numbers and journey refs. Grep the tree and history for the user's station names
  before any push.
- **The API key stays secret from Claude.** The user pastes the OJP key on the phone only (masked dialog,
  FLAG_SECURE, or QR scan); it is stored Keystore-encrypted in `noBackupFilesDir` ([`Secrets`](app/src/main/kotlin/io/github/slop_coder/pendelbuddy/data/Secrets.kt)).
  Never ask for it, read it, log it, call an API with it (no curl), or dump request headers. Debug builds write
  only API *responses* to the app's files (e.g. `files/ojp-last.xml`) for checking the parser.
- Commit identity is repo-local `slop-coder` with the GitHub no-reply address.

## Data sources

- **OJP 2.0**: `POST https://api.opentransportdata.swiss/ojp20`, Bearer key; free tier 50 requests/min,
  20k/day. Used when a key is saved, else transport.opendata.ch (no key, no occupancy).
  Verified on a real answer: `StopPlaceRef` `ch:1:sloid:<didok>` works; `siri:ExpectedDepartureOccupancy` per
  stop (FareClass may have a trailing space); TransferLegs only have a Duration; answers are ~1.4 MB of
  network-wide PtSituations but arrive gzipped at ~25 kB. Each TimedLeg has `JourneyRef` and `OperatingDayRef`.
- **Train Formation Service** (opentransportdata.swiss): the same token is enabled for it. SBB trains only.
- **Weather**: Open-Meteo, `models=meteoswiss_icon_ch2`.
- The daily occupancy ZIP was built once and dropped at the user's request; occupancy comes from OJP.

## Decisions

- Commute only for now (weekend day trips later). Weekdays: to work before the switch time, home after.
- Trips run from the first train to the last (no walks to or from the stop asked for). Tapping a trip opens a
  full-screen trip view with a rating bar per train.
- Ratings 10–110 % in 10 % steps, append-only with the forecast at rating time; CSV export in Settings.
- Occupancy is the worst stretch from boarding to alighting (`IncludeIntermediateStops=true`), shown per stretch
  in the trip view. Badge colors are the official forecast.
- Freshness: delays 10 min, occupancy 12 h and only same-day data, weather 3 h, timetable 35 days and never
  across the December timetable change.
- Downloads: on (re)opening the app, pull to refresh, picking a day or time, and the background prefetch of the
  commute windows (to work 05:30–08:00, home 15:30–18:30) of the working days in the Mo–Su tabs, once a day
  (today and tomorrow on every opening; 1.5 s between background requests). No periodic polling, except for
  the trip in "My trip" (below).

### Planned (2026-11-17)

- Done: bottom tabs Trips · My trip · Ratings · Settings, the request log opened from Settings. "I take this" puts
  a trip in **My trip**: pull to refresh, every 5 min from at most 10 min before departure until arrival, data
  age shown. An exact alarm starts `TripWatchService` (foreground, dataSync) 10 min before; it keeps checking with
  the app closed. `Backend` is the one place that downloads, shared by the view model and the service.
- Done: notify only on material changes (`core/Changes.kt`): 10+ min delay, platform change where the user boards,
  a cancelled train, the train not serving the user's boarding or exit stop, a lost connection (under half of
  OJP's transfer time, which is the planned connection time with margin, not the walk; under the full time is
  "tight": shown, not notified), the trip gone
  from the answer. Not for skipped stops where the user neither boards nor alights. Each change is notified once.
  "Alternatives" sets "I'm at" and the time to where and when the problem is.
- Done: Settings → Testing (only while a trip is set): fake delay, cancellation, new platform, not stopping at
  the exit, trip gone. Put on top of every download until cleared (`Fake`, `MyTrip.real`). The My trip tab is
  hidden while no trip is set.
- Done: the trip screen and My trip share one view (data age, changes, "I take this"); the bottom bar stays on
  every screen. Cancelled trains (or not stopping where you get on or off) are tinted red with the reason in words.
- Ratings are tied to a stop (picked from the clock and the delay, changeable); badges are split into stretches.
- Train map from the formation service: wagons, 1st class, sectors A–F at the user's platform; the sector is
  saved with ratings.

## Build and test

JDK 21 (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk`), Android SDK in `~/Android/Sdk`. The build machine is aarch64:
a native aapt2 is set with `android.aapt2FromMavenOverride` in `~/.gradle/gradle.properties`, and local release
builds need `-Pandroid.enableResourceOptimizations=false`. No emulator: test on the user's phone over adb and
install with `adb install -r app/build/outputs/apk/debug/app-debug.apk` (`installDebug` does not work).

The phone runs the debug build; the release APK has another signature, so switching means uninstalling and losing
the key, ratings and settings. The `minified` build type installs next to it for checking R8.

Releases: tags `v*` build a signed APK in GitHub Actions (`release.yml`). Local release key
`~/.android/pendelbuddy-release.keystore` (alias `pendelbuddy`, password in `~/.android/pendelbuddy-release.password`);
the same key is in the repo secrets. Local signing reads the `PB_KEYSTORE*` / `PB_KEY_*` env vars.
