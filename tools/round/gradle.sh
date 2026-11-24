#!/bin/sh
# One track's Gradle run in a parallel round (docs/architecture.md 5.3).
#
#   sh tools/round/gradle.sh <key> <tasks named by project path, and options>
#   sh tools/round/gradle.sh s :core:search:test :feature:search:assembleDebugAndroidTest
#
# It waits for one of the three build slots and for 7 GB of free memory (BL_SLOT_WAIT seconds,
# default 360; exit 75 when the time is up: run it again), then runs the wrapper from android/
# with the track's own build root, project cache and Kotlin folder, --configure-on-demand and
# the 10-minute daemon idle time, and gives the slot back. ANDROID_SERIAL is the track's own
# emulator, so a device task can never reach another device. A slot whose shell is gone (a
# killed command) is taken over.
#
# Never run ./gradlew by hand in a round, and never ./gradlew --stop.

here=$(cd "$(dirname "$0")" && pwd)
key=${1:-}
[ -n "$key" ] || { echo "usage: gradle.sh <key> <tasks...>" >&2; exit 2; }
shift
. "$here/env.sh" "$key" || exit 2
. "$here/slots.sh"

for a in "$@"; do
  case "$a" in
    --stop) echo "gradle.sh: --stop would stop every track's daemon; not allowed" >&2; exit 2 ;;
    --project-cache-dir|--project-cache-dir=*|-Pbeamline.buildRoot=*|-Pkotlin.project.persistent.dir=*)
      echo "gradle.sh: '$a' is set by this script" >&2; exit 2 ;;
  esac
done

# Stale when the shell that took the slot is gone (or, with no pid recorded, after 45 minutes).
gradle_stale() {   # <dir> <key> <pid> <time>
  if [ -n "$3" ] && [ "$3" != 0 ]; then
    ! bl_pid_alive "$3"
  else
    [ $(( $(_bl_now) - ${4:-0} )) -gt 2700 ]
  fi
}

mypid=$(cat /proc/$$/winpid 2>/dev/null || echo 0)
slot_wait gradle 3 gradle_stale "$mypid" - 7 "${BL_SLOT_WAIT:-360}" gradle.sh || exit 75
slot=$SLOT
trap 'rm -rf "$slot"' EXIT
trap 'exit 130' INT TERM HUP

mkdir -p "$BL_REPO/data/gradle/$BL_KEY"
cd "$BL_REPO/android" || exit 2
./gradlew \
  --project-cache-dir "../data/gradle/$BL_KEY/cache" \
  "-Pbeamline.buildRoot=../data/gradle/$BL_KEY/build" \
  "-Pkotlin.project.persistent.dir=../data/gradle/$BL_KEY/kotlin" \
  --configure-on-demand \
  -Dorg.gradle.daemon.idletimeout=600000 \
  "$@"
rc=$?
exit $rc
