#!/bin/sh
# adb for one track's own emulator, and nothing else.
#
#   sh tools/round/adb.sh <key> <adb arguments...>
#
# Always runs `adb -s emulator-<port of the key> ...`. It refuses a serial of the caller's own
# and the commands that act on the adb server or on every device, because a phone is attached
# to the same PC and the other tracks share the server.

here=$(cd "$(dirname "$0")" && pwd)
key=${1:-}
[ -n "$key" ] || { echo "usage: adb.sh <key> <adb arguments...>" >&2; exit 2; }
shift
. "$here/env.sh" "$key" || exit 2

for a in "$@"; do
  case "$a" in
    -s|-d|-e|-t|-H|-P|-L|--one-device)
      echo "adb.sh: '$a' is not allowed; the device is always emulator-$BL_PORT" >&2; exit 2 ;;
  esac
done
case "${1:-}" in
  kill-server|start-server|reconnect|connect|disconnect|pair|usb|tcpip|nodaemon|server|host-features|mdns|devices|wait-for-any-device)
    if [ "${1:-}" = devices ]; then
      # Listing is harmless, but print only this track's line so no other serial is shown.
      adb devices | grep "^emulator-$BL_PORT[[:space:]]" || echo "emulator-$BL_PORT is not attached"
      exit 0
    fi
    echo "adb.sh: 'adb $1' is not allowed in a round (shared adb server, phone attached)" >&2; exit 2 ;;
esac

# Using the emulator counts as "still in use" for its slot (emulator.sh).
for d in "$BL_REPO"/data/locks/emulator-slot-*; do
  [ -f "$d/owner" ] || continue
  read -r okey rest < "$d/owner"
  [ "$okey" = "$BL_KEY" ] && touch "$d/owner"
done

# Git Bash would rewrite a device path such as /sdcard/Download into a Windows path. With this
# set it does not; give local files as relative paths or as C:/... paths.
MSYS_NO_PATHCONV=1; export MSYS_NO_PATHCONV

exec adb -s "emulator-$BL_PORT" "$@"
