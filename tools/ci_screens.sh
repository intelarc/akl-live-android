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
shot 1-buses
read W H <<< "$(adb shell wm size | grep -o '[0-9]\+x[0-9]\+' | tr x ' ')"
adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 600
sleep 3
shot 2-buses-lower
adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 5)) 600
sleep 3
shot 3-route-map

tap_text "Trains"
sleep 30
shot 4-trains
# zoom in on the City Rail Link
adb shell input tap $((W / 2)) $((H / 4))
adb shell input tap $((W / 2)) $((H / 4))
sleep 3
shot 5-trains-zoomed
# open a station's departures (a label is a fine target once zoomed)
tap_text "Waitematā" || true
adb shell input tap $((W / 2)) $((H / 5))
sleep 12
shot 6-station

tap_text "Settings"
sleep 3
shot 7-settings

adb shell dumpsys window | grep -i "mCurrentFocus" > shots/focus.txt
adb logcat -d -v brief AndroidRuntime:E '*:S' > shots/crash.txt
adb logcat -d > shots/logcat.txt
exit 0
