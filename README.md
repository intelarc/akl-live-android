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
  with lit windows and the Sky Tower's beacon. The weather is Auckland's real
  weather ([Open-Meteo](https://open-meteo.com)): cloud cover greys the sky, and
  it drizzles, rains, fogs over or flashes with thunder when Auckland does. The
  city lane has the skyline with Rangitoto behind; the Waikowhai lane has houses,
  pōhutukawa in flower and Maungakiekie's obelisk.
- **The next bus in detail.** It shows the expected arrival against the
  timetable, on time or minutes late, and a track of the stops between the bus
  and you with the last one it passed. From its GPS it also shows how far away
  it is in km, its speed and how full it is.
- **What bus is coming.** The fleet number in AT's feed (`TR3884`, `NB5760`)
  gives the operator and, where the fleet list knows it, the model: a CRRC
  eT12 MAX, an eD12 MAX double-decker, an Enviro200... Electric buses get a ⚡,
  and the scene draws the right bus (double-deckers, no exhaust on electrics).
- **The next five buses** with live and scheduled times and models.
- **A live satellite map of the whole 27H.** Both directions over real aerial
  photos (or a street map), every bus on its way to you gliding between GPS
  fixes and pointing where it's heading. Tap it for full screen, then tap a bus
  to see what it is. Pull down anywhere to refresh.

## Live

Every bus in Auckland on one map, about a thousand at a time, coloured by
operator and gliding between GPS fixes. Zoom in and each one shows its heading
and route number.

- **Search** by route (`27` finds the 27H, 27W and 27T), fleet number
  (`NB5075`) or model (`eT12`), and the map frames what it found.
- **Filters** for electric buses, double-deckers or one operator, with live counts.
- **Tap a bus** for its route and destination, how late it's running, its
  speed, how full it is and what model it is. Tap the model for its page.

## Fleet

Every bus model on the network (the CRRC eT12 MAX, Geely C13E, Enviro500 and
the rest), sorted by how many are out right now.

- **Each model has its own page:** a drawing of it (double-deckers, tri-axles,
  electrics), specs such as length, seats, motor and top speed, a short
  history, the fleet numbers each operator uses, and a live map of every one of
  them on the road, with a list you can tap to find each bus.
- Buses whose fleet numbers aren't in the list yet are grouped under **Not
  identified yet**, with their own map.

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
- **Diagram, Satellite or Map.** Switch from the schematic to every train at
  its real GPS position over aerial photos or a street map.
- Light and dark map, following the system theme. Landscape puts the map
  beside the panel.

## How it's built

Kotlin + Jetpack Compose. The scenes, the train diagram and the vehicle
markers are drawn with Compose Canvas. The real maps are
[MapLibre](https://maplibre.org) (open source), with:

- satellite: [Esri World Imagery](https://www.arcgis.com/home/item.html?id=10df2279f9684e4a9f6a7f08febac2a9),
  or with a free [LINZ Basemaps](https://basemaps.linz.govt.nz) key, Toitū Te
  Whenua LINZ's aerial photos (7.5 cm in Auckland, CC BY 4.0). Paste a key in
  Settings, or build one in with a `LINZ_API_KEY` repository secret.
- streets: [OpenFreeMap](https://openfreemap.org) (OpenStreetMap data).

Transit data comes straight from the
[AT developer API](https://dev-portal.at.govt.nz):

| What | Endpoint |
|---|---|
| Stop timetable | `gtfs/v3/stops/{id}/stoptrips` |
| Live delays, stops away | `realtime/legacy/tripupdates?tripid=` |
| Bus and train GPS | `realtime/legacy/vehiclelocations?tripid=` / `?vehicleid=` (all trains are 59xxx) |
| Train destination and stops | `gtfs/v3/trips/{id}` and `.../stoptimes` |
| Station departures | stoptrips for each platform (platforms from `parent_station`) |
| Every bus (Live, Fleet) | `realtime/legacy/vehiclelocations`, the whole feed (~80 KB gzipped) |
| Bus operator and model | the vehicle label (`TR3884`) looked up in `app/src/main/assets/fleet.tsv` and `models.json` |

Train GPS fixes are snapped onto the nearest station-to-station stretch of the
train's own line on the schematic. `tools/gen_data.py` generates the map
geometry, the station/platform table and the 27H's stop lists into
`app/src/main/java/nz/aryan/akllive/data/`:

    AT_API_KEY=... python tools/gen_data.py

The app polls only while it's on screen: buses every 30 s, trains every 15 s,
and the whole network every 20 s, only while Live or Fleet is open.

AT's feed reports train speeds in m/s, as GTFS-realtime specifies, but bus
speeds in km/h. Both were checked against how far vehicles actually moved
between fixes.

### The fleet list

AT's feed doesn't say what model a bus is, only its fleet number with the
operator's code in front (NB NZ Bus, GB Go Bus, RT Ritchies, HE Howick &
Eastern, TR Tranzurban, BA Bayes, WB Waiheke). `fleet.tsv` maps fleet number
ranges to models, and `models.json` describes each model. Both come from the
[AT Metro Wiki](https://atmetro.fandom.com)'s model and operator pages (CC BY-SA).
They cover about three quarters of the buses on the road. Every CI build runs
`tools/fleet_survey.py`, which lists each
range on the road right now, the routes it's running and whether the table
knows it, so gaps are easy to fill in:

    AT_API_KEY=... python tools/fleet_survey.py

## Building

GitHub Actions builds `app-release.apk` on every push to any branch
(`.github/workflows/build.yml`); only `main` publishes it to the release.
It uses these repository secrets:

- `AT_API_KEY`: built into the APK as the default key (Settings can override it)
- `LINZ_API_KEY` (optional): a LINZ Basemaps key for the sharper aerials
- `KEYSTORE_B64`, `KEYSTORE_PASSWORD`: the signing key. It's kept locally in
  `signing/`, which is gitignored. Back it up: updates must be signed with
  the same key.

The Screenshots workflow (run it by hand) drives every screen in an emulator
with live data; pick `build` to shoot a branch instead of the latest release.

Local builds need JDK 17, the Android SDK and Gradle 8.11:
`gradle :app:assembleRelease`. Without the secrets, you get a debug-signed
APK with no built-in key.

Not affiliated with Auckland Transport.
