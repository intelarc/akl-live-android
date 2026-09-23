# AKL Live

An Android app for live Auckland Transport buses and trains, built for the
Galaxy S26. It's the phone version of
[akl-departure-board](https://github.com/intelarc/akl-departure-board), the
ESP32 desk display.

## Install

Open **Releases → latest** on the phone, download `AKL-Live.apk` and open it.
Android asks once to allow installs from your browser. Every push to `main`
builds a new signed APK there. Installing it updates the app in place.

## Buses

The 27H at Aldersgate Road, Hillsborough, both directions: stop 8669 to
Britomart and 8664 to Waikowhai. Stops and route can be changed in Settings.

- **A live scene per direction.** The bus drives toward the stop as its arrival
  counts down, and its wheels turn while it's moving. The sky follows the real
  time of day: sunrise, the sun's arc, sunset, then stars and a crescent moon,
  with lit windows and the Sky Tower's beacon. The city lane has the Auckland
  skyline; the Waikowhai lane has houses and pōhutukawa in flower.
- **The next bus in detail.** It shows the expected arrival against the
  timetable, on time or minutes late, and how many stops away the bus is. From
  its GPS it also shows how far away it is in km, its speed, how full it is,
  and its fleet number.
- **The next five buses** with live and scheduled times.
- **A live map of the whole 27H.** Both directions are drawn from the stops'
  real positions, with every bus on its way to you pointing where it's
  heading, tagged with its countdown.

## Trains

A live map of the post-CRL network, drawn after AT's official "Ngā Tereina"
map. It has the red City Rail Link loop (Waitematā, Te Waihorotiu,
Karanga-a-Hape, Grafton, Newmarket, Parnell), and E-W and O-W run side by side
out west. Harbours and volcanic cones sit behind it.

- **Every train, live.** Markers glide between GPS fixes, show their direction
  of travel, and carry an orange or red dot when a train is running 2 or 5+
  minutes late.
- **Pinch, pan and double-tap to zoom.** All 44 station names appear as you
  zoom in, placed so they never overlap.
- **Tap a train** for its destination, speed, delay, how full it is and its
  carriage number. You also get its next ten stops with expected times.
- **Tap a station** for live departures from every platform, with line,
  destination, delay and countdown.
- **Line filters** (E-W, S-C, O-W) with live counts, and a network overview
  showing how many trains on each line are running late.
- Light and dark map, following the system theme. Landscape puts the map
  beside the panel.

## How it's built

Kotlin + Jetpack Compose. Everything is drawn with Compose Canvas, with no
map or chart libraries. Data comes straight from the
[AT developer API](https://dev-portal.at.govt.nz):

| What | Endpoint |
|---|---|
| Stop timetable | `gtfs/v3/stops/{id}/stoptrips` |
| Live delays, stops away | `realtime/legacy/tripupdates?tripid=` |
| Bus and train GPS | `realtime/legacy/vehiclelocations?tripid=` / `?vehicleid=` (all trains are 59xxx) |
| Train destination and stops | `gtfs/v3/trips/{id}` and `.../stoptimes` |
| Station departures | stoptrips for each platform (platforms from `parent_station`) |

Train GPS fixes are snapped onto the nearest station-to-station stretch of the
train's own line on the schematic. `tools/gen_data.py` generates the map
geometry, the station/platform table and the 27H's stop lists into
`app/src/main/java/nz/aryan/akllive/data/`:

    AT_API_KEY=... python tools/gen_data.py

The app polls only while it's on screen: buses every 30 s, trains every 15 s.

## Building

GitHub Actions builds `app-release.apk` on every push (`.github/workflows/build.yml`).
It uses three repository secrets:

- `AT_API_KEY`: built into the APK as the default key (Settings can override it)
- `KEYSTORE_B64`, `KEYSTORE_PASSWORD`: the signing key. It's kept locally in
  `signing/`, which is gitignored. Back it up: updates must be signed with
  the same key.

Local builds need JDK 17, the Android SDK and Gradle 8.11:
`gradle :app:assembleRelease`. Without the secrets, you get a debug-signed
APK with no built-in key.

Not affiliated with Auckland Transport.
