#!/bin/sh
# Wait for a note on the round's board (data/r3-board/), written by another track.
#
#   sh tools/round/wait-board.sh <file name> [seconds]
#
# Exits 0 and prints the note as soon as data/r3-board/<file name> exists; exits 75 when it has
# not appeared after the given time (default 300 seconds). With 0 seconds it only looks once.

here=$(cd "$(dirname "$0")" && pwd)
name=${1:-}
[ -n "$name" ] || { echo "usage: wait-board.sh <file name> [seconds]" >&2; exit 2; }
board=$(cd "$here/../.." && pwd)/data/r3-board
wait_s=${2:-300}
waited=0
while [ ! -f "$board/$name" ]; do
  if [ $waited -ge "$wait_s" ]; then
    echo "wait-board.sh: $name is not on the board yet (waited $waited s). Notes there now:" >&2
    ls "$board" 2>/dev/null >&2
    exit 75
  fi
  sleep 10
  waited=$((waited + 10))
done
cat "$board/$name"
