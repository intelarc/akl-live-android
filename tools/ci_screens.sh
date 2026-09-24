#!/bin/bash
# Run inside the emulator job (.github/workflows/screenshots.yml).
set -x
mkdir -p shots
shot() { adb exec-out screencap -p > "shots/$1.png"; }

# tap the centre of the first on-screen element whose text is $1
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  adb shell cat /sdcard/ui.xml > shots/ui.xml
  b=$(grep -o "text=\"$1\"[^>]*bounds=\"[^\"]*\"" shots/ui.xml | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+')
  set -- $b
  [ -n "$4" ] && adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

adb logcat -c
adb install -r AKL-Live.apk
adb shell am start -n nz.aryan.akllive/.MainActivity
sleep 35
shot 01-buses
read W H <<< "$(adb shell wm size | grep -o '[0-9]\+x[0-9]\+' | tr x ' ')"
adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 600
sleep 3
shot 02-buses-lower
adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 5)) 600
sleep 8
shot 03-route-map

# the full-screen route map, satellite then streets
tap_text "Open map"
sleep 15
shot 04-bus-map-satellite
tap_text "Map"
sleep 12
shot 05-bus-map-streets
tap_text "Satellite"
sleep 2
adb shell input keyevent KEYCODE_BACK
sleep 2

tap_text "Trains"
sleep 30
shot 06-trains
# zoom in on the City Rail Link
adb shell input tap $((W / 2)) $((H / 4))
adb shell input tap $((W / 2)) $((H / 4))
sleep 3
shot 07-trains-zoomed
# open a station's departures (a label is a fine target once zoomed)
tap_text "Waitematā" || true
adb shell input tap $((W / 2)) $((H / 5))
sleep 12
shot 08-station
# every train where it really is
tap_text "Satellite"
sleep 15
shot 09-trains-satellite
tap_text "Diagram"
sleep 2

# every bus in Auckland, then a route search
tap_text "Live"
sleep 30
shot 10-live
tap_text "Route, fleet number or model"
sleep 1
adb shell input text "27H"
adb shell input keyevent KEYCODE_ENTER
sleep 6
shot 11-live-27h

# the fleet list and a model's page
tap_text "Fleet"
sleep 10
shot 12-fleet
tap_text "CRRC eT12 MAX" || adb shell input tap $((W / 2)) $((H * 2 / 5))
sleep 12
shot 13-model

tap_text "Settings"
sleep 3
shot 14-settings

adb shell dumpsys window | grep -i "mCurrentFocus" > shots/focus.txt
adb logcat -d -v brief AndroidRuntime:E '*:S' > shots/crash.txt
adb logcat -d > shots/logcat.txt
exit 0
