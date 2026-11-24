#!/bin/sh
# One track's own emulator in a parallel round (docs/architecture.md 5.2 and 5.3).
#
#   sh tools/round/emulator.sh <key> start     make the AVD if needed, take one of the three
#                                              emulator slots, start it, wait until it has booted
#   sh tools/round/emulator.sh <key> stop      flush, power off, give the slot back
#   sh tools/round/emulator.sh <key> status    this track's emulator and every slot
#   sh tools/round/emulator.sh <key> keep      say "still in use" (adb.sh does this on every call)
#
# AVD beamline_<key>, port from env.sh, serial emulator-<port>. `start` waits BL_SLOT_WAIT
# seconds for a slot and for 5 GB of free memory (default 420; exit 75 when the time is up: run
# it again later). Stop the
# emulator whenever you are not using it: six tracks share three slots.
#
# A slot is taken over from another track when its emulator is not running, or when nothing has
# used it for two hours (that emulator is then stopped).

here=$(cd "$(dirname "$0")" && pwd)
key=${1:-}; cmd=${2:-}
[ -n "$key" ] && [ -n "$cmd" ] || { echo "usage: emulator.sh <key> start|stop|status|keep" >&2; exit 2; }
. "$here/env.sh" "$key" || exit 2
. "$here/slots.sh"

serial=emulator-$BL_PORT
out="$BL_REPO/data/r3-$BL_KEY"
avd_home="$HOME/.android/avd"

attached() { adb devices 2>/dev/null | grep -q "^$1[[:space:]]"; }

# True when an emulator process for this AVD exists.
proc_running() {
  powershell.exe -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name like 'qemu-system%' or Name = 'emulator.exe'\" | ForEach-Object { \$_.CommandLine }" 2>/dev/null \
    | tr -d '\r' | grep -q -- "-avd $1\( \|\$\)"
}

kill_procs() {
  powershell.exe -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name like 'qemu-system%' or Name = 'emulator.exe'\" | Where-Object { \$_.CommandLine -match '-avd $1( |\$)' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }" >/dev/null 2>&1
}

power_off() {   # <serial> <avd>
  if attached "$1"; then
    adb -s "$1" shell sync >/dev/null 2>&1
    adb -s "$1" shell reboot -p >/dev/null 2>&1
  fi
  _w=0
  while proc_running "$2" && [ $_w -lt 60 ]; do sleep 3; _w=$((_w + 3)); done
  if proc_running "$2"; then
    adb -s "$1" emu kill >/dev/null 2>&1
    sleep 5
    proc_running "$2" && kill_procs "$2"
  fi
}

# Stale: its emulator is not running (and it has had 3 minutes to start), or unused for 2 hours.
emu_stale() {   # <dir> <key> <pid> <time> <port>
  _age=$(( $(_bl_now) - $(_bl_mtime "$1/owner") ))
  if attached "emulator-$5" || proc_running "beamline_$2"; then
    [ $_age -gt 7200 ] || return 1
    echo "emulator.sh: track '$2' has not used its emulator for $_age s; stopping it" >&2
    power_off "emulator-$5" "beamline_$2"
    return 0
  fi
  [ $_age -gt 180 ]
}

make_avd() {
  [ -d "$avd_home/$BL_AVD.avd" ] && return 0
  echo "emulator.sh: creating AVD $BL_AVD"
  echo no | avdmanager.bat create avd -n "$BL_AVD" -k "system-images;android-36;default;x86_64" -d medium_phone >/dev/null 2>&1
  cfg="$avd_home/$BL_AVD.avd/config.ini"
  [ -f "$cfg" ] || { echo "emulator.sh: avdmanager did not create $BL_AVD" >&2; return 1; }
  for kv in hw.ramSize=3072 hw.gpu.enabled=yes hw.gpu.mode=host disk.dataPartition.size=10G; do
    k=${kv%%=*}
    if grep -q "^$k=" "$cfg"; then sed -i "s/^$k=.*/$kv/" "$cfg"; else echo "$kv" >> "$cfg"; fi
  done
}

wait_boot() {
  _w=0
  while [ $_w -lt 300 ]; do
    if [ "$(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] \
       && adb -s "$serial" shell pm path android >/dev/null 2>&1; then
      return 0
    fi
    proc_running "$BL_AVD" || [ $_w -lt 30 ] || return 1
    sleep 3; _w=$((_w + 3))
  done
  return 1
}

case "$cmd" in
  start)
    mkdir -p "$out"
    make_avd || exit 1
    if attached "$serial" || proc_running "$BL_AVD"; then need=0; else need=5; fi
    slot_wait emulator 3 emu_stale 0 "$BL_PORT" "$need" "${BL_SLOT_WAIT:-420}" emulator.sh || exit 75
    if attached "$serial" || proc_running "$BL_AVD"; then
      echo "emulator.sh: $BL_AVD is already running"
    else
      emu_exe=$(cygpath -w "$BL_SDK/emulator/emulator.exe")
      log_w=$(cygpath -w "$out/emulator.log")
      # Start-Process detaches it from this shell, so it outlives the command that started it.
      powershell.exe -NoProfile -Command "Start-Process -FilePath '$emu_exe' -ArgumentList '-avd','$BL_AVD','-port','$BL_PORT','-no-window','-gpu','host','-memory','3072','-no-snapshot','-no-boot-anim','-no-audio' -WindowStyle Hidden -RedirectStandardOutput '$log_w' -RedirectStandardError '$log_w.err'" \
        || { slot_release emulator "$BL_KEY"; echo "emulator.sh: could not start the emulator" >&2; exit 1; }
    fi
    if wait_boot; then
      echo "emulator.sh: $serial ($BL_AVD) is ready; slot $(basename "$SLOT")"
    else
      echo "emulator.sh: $BL_AVD did not boot in 5 minutes; see data/r3-$BL_KEY/emulator.log" >&2
      power_off "$serial" "$BL_AVD"
      slot_release emulator "$BL_KEY"
      exit 1
    fi
    ;;
  stop)
    power_off "$serial" "$BL_AVD"
    slot_release emulator "$BL_KEY"
    if proc_running "$BL_AVD"; then echo "emulator.sh: $BL_AVD is still running" >&2; exit 1; fi
    echo "emulator.sh: $BL_AVD stopped; slot given back"
    ;;
  keep)
    d=$(slot_of emulator "$BL_KEY") && touch "$d/owner" && echo "emulator.sh: kept $(basename "$d")" \
      || { echo "emulator.sh: track '$BL_KEY' holds no emulator slot" >&2; exit 1; }
    ;;
  status)
    if attached "$serial"; then echo "$serial ($BL_AVD): attached"
    elif proc_running "$BL_AVD"; then echo "$serial ($BL_AVD): process running, not attached yet"
    else echo "$serial ($BL_AVD): not running"; fi
    slot_list
    ;;
  *) echo "usage: emulator.sh <key> start|stop|status|keep" >&2; exit 2 ;;
esac
