# Pendel Buddy

An Android app for commuting by train in Switzerland. On a weekday it shows the next trains to work in the morning and
home in the afternoon: leave time, arrival, changes, platform, delays and how full the train is expected to be.

- **Fastest arrival**: the earliest arrival among the trains you can still catch (and how long until it leaves), with the temperature at the
  destination on arrival, the day's maximum and the sky (sunny, cloudy, rain, snow…).
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
