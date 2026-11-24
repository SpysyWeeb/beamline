#!/bin/sh
# A memory-heavy command of one track in a parallel round: a map build (Planetiler), a routing
# graph build, or anything else expected to use more than 3 GB.
#
#   BL_NEED_GB=10 sh tools/round/heavy.sh <key> <command and its arguments>
#
# One heavy command runs at a time. It waits for the heavy slot and for BL_NEED_GB of free
# memory (default 8; BL_SLOT_WAIT seconds, default 480; exit 75 when the time is up: run it
# again), runs the command from the current folder, and gives the slot back.

here=$(cd "$(dirname "$0")" && pwd)
key=${1:-}
[ -n "$key" ] && [ $# -ge 2 ] || { echo "usage: heavy.sh <key> <command...>" >&2; exit 2; }
shift
. "$here/env.sh" "$key" || exit 2
. "$here/slots.sh"

heavy_stale() {   # <dir> <key> <pid> <time>
  if [ -n "$3" ] && [ "$3" != 0 ]; then
    ! bl_pid_alive "$3"
  else
    [ $(( $(_bl_now) - ${4:-0} )) -gt 10800 ]
  fi
}

mypid=$(cat /proc/$$/winpid 2>/dev/null || echo 0)
slot_wait heavy 1 heavy_stale "$mypid" - "${BL_NEED_GB:-8}" "${BL_SLOT_WAIT:-480}" heavy.sh || exit 75
slot=$SLOT
trap 'rm -rf "$slot"' EXIT
trap 'exit 130' INT TERM HUP

"$@"
rc=$?
exit $rc
