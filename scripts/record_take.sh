#!/usr/bin/env bash
# Records one demo take from a debug build on a USB-connected phone.
#
# Usage: scripts/record_take.sh OUT_DIR SECONDS -- AM_EXTRAS... [++ AM_EXTRAS...]
#   e.g. scripts/record_take.sh takes/voice 40 -- --ez recordAudio true --ei userVoice 16 \
#            --es speakAsUser "Hey, can you cheer me up?"
#
# It clears the app's saved demo audio, starts screenrecord, sends the intent, waits until the
# companion has finished speaking ("speech drained" in the log) or SECONDS have passed, then pulls
# raw.mp4 and the demo/*.pcm files into OUT_DIR for scripts/assemble_demo.py.
set -euo pipefail
ADB=${ADB:-adb}
PKG=io.github.solvai.pocketcompanion
out=$1; secs=$2; shift 2; [[ ${1:-} == -- ]] && shift
mkdir -p "$out/demo"
files=/sdcard/Android/data/$PKG/files
$ADB shell "rm -f $files/demo/*.pcm /sdcard/Movies/pc-take.mp4"
$ADB shell "screenrecord --bit-rate 12000000 --time-limit $((secs + 5)) /sdcard/Movies/pc-take.mp4" &
rec=$!
sleep 1.5
# A "++" argument splits the extras into several intents, sent 2.5 s apart ("++6" waits 6 s).
# adb shell joins its arguments into one remote command line, so quote each one for the phone's shell.
# The log is cleared before each intent, so only the last one's "speech drained" ends the take.
send() { $ADB logcat -c; $ADB shell "am start -n $PKG/.CompanionActivity$1" > /dev/null 2>&1; }
args=""
for a in "$@"; do
  if [[ $a =~ ^\+\+([0-9.]*)$ ]]; then send "$args"; args=""; sleep "${BASH_REMATCH[1]:-2.5}"; continue; fi
  args+=" '${a//\'/\'\\\'\'}'"
done
send "$args"
end=$((SECONDS + secs))
until (( SECONDS >= end )); do
  # Done when she has drained her speech queue and no new sentence started for 4 s.
  if $ADB logcat -d -s PocketDiag:I | grep -q "speech drained"; then
    before=$($ADB shell "ls $files/demo/ 2>/dev/null | wc -l")
    sleep 4
    after=$($ADB shell "ls $files/demo/ 2>/dev/null | wc -l")
    [[ $before == "$after" ]] && break
    $ADB logcat -c
  fi
  sleep 1
done
$ADB shell pkill -INT screenrecord || true
wait $rec || true
sleep 1
$ADB pull /sdcard/Movies/pc-take.mp4 "$out/raw.mp4" > /dev/null
$ADB shell "ls $files/demo/ 2>/dev/null" | while read -r f; do
  [[ $f == *.pcm ]] && $ADB pull "$files/demo/$f" "$out/demo/$f" > /dev/null
done
$ADB logcat -d -s PocketDiag:I | grep -v render > "$out/log.txt" || true
echo "take saved in $out: $(ls "$out/demo" | wc -l) audio clips"
