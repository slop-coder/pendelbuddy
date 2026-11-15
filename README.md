# Pendel Buddy

An Android app for commuting by train in Switzerland. On a weekday it shows the next trains to work in the morning and
home in the afternoon: leave time, arrival, changes, platform, delays and how full the train is expected to be.

- **Day and time**: tabs Mo–Su (the next such day) and a time picker with morning/evening shortcuts. The working
  week's commute (to work 05:30–08:00, home 15:30–18:30) is fetched ahead, so those times need no request.
- **Weather card**: the picked day at your destination, hour by hour, with rain or snow by intensity.
- **Trip list**: train badges colored by expected occupancy (worst stretch of each ride), travel time from green
  (fastest shown) to red (slowest).
- **I'm at…**: pick the station you are at (home, a station in between such as a breakfast stop, work, or any other)
  to see the trips from there. Picking the destination plans the trip back.
- **Fullness**: the occupancy forecast for your class (1st/2nd) where the timetable has one, plus your own ratings:
  tap a trip and rate a train from 10% (almost empty) to 110% (no seats, many standing). Your average for that train on
  the same kind of day is shown on later trips.
- **Offline**: trips are saved, and the whole commute is fetched once a day. Without internet the app shows today's
  saved trips, or the timetable of the same weekday from an earlier week. Delays are hidden after 10 minutes, occupancy
  after 12 hours or on a copied timetable, weather after 3 hours. Data from before the December timetable change is
  never used.

## Data

- Timetable: [transport.opendata.ch](https://transport.opendata.ch/) (no key).
- Weather: [Open-Meteo](https://open-meteo.com/) with the MeteoSwiss ICON-CH2 model, CC BY 4.0.

## Build

Needs JDK 21 and the Android SDK (`local.properties` with `sdk.dir`).

    ./gradlew :app:testDebugUnitTest :app:assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk

A `minified` build type (shrunk like a release, debug-signed, id suffix `.minified`) installs next to the debug app to
check R8: `./gradlew :app:assembleMinified`.

### Releases

Every push to `main` runs the unit tests and a debug build (`.github/workflows/ci.yml`). Pushing a tag `v*` builds a
signed release APK and attaches it to a GitHub Release (`release.yml`). Secrets: `KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

The OJP API key is not part of the app or the repo: each user pastes (or scans) their own in the app's settings.

## Licence

Public domain ([Unlicense](LICENSE)). Timetable data: opentransportdata.swiss / transport.opendata.ch; weather:
Open-Meteo with MeteoSwiss ICON-CH (CC BY 4.0).
