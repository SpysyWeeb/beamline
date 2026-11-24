# Source this file; do not run it:   . tools/round/env.sh <key>
#
# Sets the environment one track of a parallel round needs (docs/architecture.md 5.2 and 5.3):
#   BL_KEY, BL_PORT, BL_AVD, BL_REPO, BL_SDK
#   ANDROID_SERIAL=emulator-<port>   so adb, Gradle device tasks and the scripts under
#                                    android/scripts can only ever reach the track's own emulator
#   BEAMLINE_SEARCH_DB, BEAMLINE_ADDRESSES_DB   when the two databases exist
#   PATH gains the SDK's emulator and cmdline-tools folders (adb stays the one already on PATH)
#
# Keys and ports: p 5560, n 5562, s 5564, d 5566, m 5568, a 5570, i 5572, g 5574; 0b 5598 is
# the orchestrator's key and names an emulator that is never started.

case "${1:-}" in
  p) BL_PORT=5560 ;;
  n) BL_PORT=5562 ;;
  s) BL_PORT=5564 ;;
  d) BL_PORT=5566 ;;
  m) BL_PORT=5568 ;;
  a) BL_PORT=5570 ;;
  i) BL_PORT=5572 ;;
  g) BL_PORT=5574 ;;
  0b) BL_PORT=5598 ;;
  *) echo "env.sh: unknown track key '${1:-}' (p n s d m a i g 0b)" >&2; return 2 2>/dev/null || exit 2 ;;
esac
BL_KEY=$1
BL_AVD=beamline_$BL_KEY

# The repository root is two folders above this file.
_bl_self=${BASH_SOURCE:-$0}
BL_REPO=$(cd "$(dirname "$_bl_self")/../.." && { pwd -W 2>/dev/null || pwd; })
unset _bl_self

# The SDK folder comes from the git-ignored android/local.properties (sdk.dir).
BL_SDK=$(sed -n 's/^sdk\.dir=//p' "$BL_REPO/android/local.properties" 2>/dev/null | tr -d '\r' | sed 's/\\\\/\//g; s/\\:/:/g' | head -1)
if [ -z "$BL_SDK" ] || [ ! -d "$BL_SDK" ]; then
  echo "env.sh: sdk.dir is missing from android/local.properties, or is not a folder" >&2
  return 2 2>/dev/null || exit 2
fi

ANDROID_SERIAL=emulator-$BL_PORT
export BL_KEY BL_PORT BL_AVD BL_REPO BL_SDK ANDROID_SERIAL

_bl_sdk_unix=$(cygpath -u "$BL_SDK" 2>/dev/null || echo "$BL_SDK")
case ":$PATH:" in
  *":$_bl_sdk_unix/emulator:"*) ;;
  *) PATH="$PATH:$_bl_sdk_unix/emulator:$_bl_sdk_unix/cmdline-tools/latest/bin" ;;
esac
unset _bl_sdk_unix
export PATH

if [ -f "$BL_REPO/data/search/pa-search.sqlite" ]; then
  BEAMLINE_SEARCH_DB="$BL_REPO/data/search/pa-search.sqlite"; export BEAMLINE_SEARCH_DB
fi
if [ -f "$BL_REPO/data/addresses/pa-addresses-slim.sqlite" ]; then
  BEAMLINE_ADDRESSES_DB="$BL_REPO/data/addresses/pa-addresses-slim.sqlite"; export BEAMLINE_ADDRESSES_DB
fi
