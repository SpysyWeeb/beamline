# Source this file (after env.sh). Slot locks for a parallel round, under data/locks/.
#
# A slot is a folder  data/locks/<kind>-slot-<n>  with one file `owner`:
#     <key> <windows pid or 0> <unix time taken> <extra or -> <GB it is expected to use>
# Slots are taken and given up only while holding  data/locks/mutex  (mkdir is atomic), so two
# tracks can never take the same slot, and a slot whose owner is gone can be taken over safely.
#
# Memory guard: a slot is given only when the PC has enough free memory for it, counting what
# slots taken in the last two minutes are about to use but do not use yet. When no slot of any
# kind is held, the guard is skipped (nothing of the round is running, so waiting cannot help).
#
#   slot_take <kind> <count> <stale-test function> <pid> <extra> <need GB>
#                                              -> sets SLOT, returns 0; 1 if none, 3 if memory
#   slot_release <kind> <key>                  -> removes this key's slot(s) of that kind
#   slot_of <kind> <key>                       -> prints this key's slot folder
#   slot_list                                  -> one line per held slot

BL_LOCKS="$BL_REPO/data/locks"
BL_YOUNG_S=120

_bl_now() { date +%s; }

_bl_mtime() { stat -c %Y "$1" 2>/dev/null || echo 0; }

# True when a Windows process with this pid exists.
bl_pid_alive() {
  [ -n "$1" ] && [ "$1" != 0 ] || return 1
  tasklist //FI "PID eq $1" //NH 2>/dev/null | grep -q "[[:space:]]$1[[:space:]]"
}

# Free physical memory in whole GB (what Task Manager calls "available").
bl_free_gb() {
  powershell.exe -NoProfile -Command "[int][math]::Floor((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1MB)" 2>/dev/null | tr -d '\r'
}

_bl_mutex_take() {
  mkdir -p "$BL_LOCKS"
  _t=0
  while ! mkdir "$BL_LOCKS/mutex" 2>/dev/null; do
    # A mutex is held for a second or two; one older than 45 s was left by a killed shell.
    if [ $(( $(_bl_now) - $(_bl_mtime "$BL_LOCKS/mutex") )) -gt 45 ]; then
      rmdir "$BL_LOCKS/mutex" 2>/dev/null
      continue
    fi
    sleep 0.2
    _t=$((_t + 1))
    [ $_t -lt 450 ] || { echo "slots: could not take data/locks/mutex in 90 s" >&2; return 1; }
  done
  return 0
}

_bl_mutex_give() { rmdir "$BL_LOCKS/mutex" 2>/dev/null; }

# Enough memory for <need GB>? Call with the mutex held.
_bl_memory_ok() {
  _need=$1
  [ "$_need" -gt 0 ] 2>/dev/null || return 0
  _held=0; _soon=0; _now=$(_bl_now)
  for _m in "$BL_LOCKS"/*-slot-*; do
    [ -f "$_m/owner" ] || continue
    _mk=; _mp=; _mt=0; _mx=; _mr=0
    read -r _mk _mp _mt _mx _mr < "$_m/owner"
    _held=$((_held + 1))
    [ $((_now - ${_mt:-0})) -lt $BL_YOUNG_S ] && _soon=$((_soon + ${_mr:-0}))
  done
  [ $_held -gt 0 ] || return 0
  _free=$(bl_free_gb)
  [ -n "$_free" ] || return 0
  if [ $((_free - _soon)) -lt "$_need" ]; then
    BL_MEMORY_NOTE="free $_free GB, $_soon GB about to be used by slots just taken, $_need GB needed"
    return 1
  fi
  return 0
}

slot_take() {
  _kind=$1; _count=$2; _stale=$3; _pid=$4; _extra=${5:--}; _need=${6:-0}
  SLOT=; BL_MEMORY_NOTE=
  _bl_mutex_take || return 1
  _rc=1
  _n=1
  while [ $_n -le "$_count" ]; do
    _d="$BL_LOCKS/$_kind-slot-$_n"
    if [ -d "$_d" ]; then
      _okey=; _opid=; _otime=; _oextra=; _ores=
      [ -f "$_d/owner" ] && read -r _okey _opid _otime _oextra _ores < "$_d/owner"
      if [ "$_okey" = "$BL_KEY" ]; then
        # Our own slot from an earlier call (a killed shell, or the emulator still running).
        echo "$BL_KEY $_pid $(_bl_now) $_extra $_need" > "$_d/owner"; SLOT=$_d; break
      fi
      if "$_stale" "$_d" "$_okey" "$_opid" "$_otime" "$_oextra"; then
        echo "slots: taking over $_kind slot $_n, left behind by track '$_okey'" >&2
        rm -rf "$_d"
      fi
    fi
    if [ ! -d "$_d" ]; then
      if ! _bl_memory_ok "$_need"; then _rc=3; break; fi
      if mkdir "$_d" 2>/dev/null; then
        echo "$BL_KEY $_pid $(_bl_now) $_extra $_need" > "$_d/owner"; SLOT=$_d; break
      fi
    fi
    _n=$((_n + 1))
  done
  _bl_mutex_give
  [ -n "$SLOT" ] && return 0
  return $_rc
}

slot_of() {
  for _d in "$BL_LOCKS/$1"-slot-*; do
    [ -f "$_d/owner" ] || continue
    read -r _okey _rest < "$_d/owner"
    [ "$_okey" = "$2" ] && { echo "$_d"; return 0; }
  done
  return 1
}

slot_release() {
  _bl_mutex_take || return 1
  for _d in "$BL_LOCKS/$1"-slot-*; do
    [ -f "$_d/owner" ] || continue
    read -r _okey _rest < "$_d/owner"
    [ "$_okey" = "$2" ] && rm -rf "$_d"
  done
  _bl_mutex_give
}

slot_list() {
  for _d in "$BL_LOCKS"/*-slot-*; do
    [ -f "$_d/owner" ] || continue
    read -r _okey _opid _otime _oextra _ores < "$_d/owner"
    echo "$(basename "$_d"): track $_okey, taken $(( $(_bl_now) - ${_otime:-0} )) s ago, last used $(( $(_bl_now) - $(_bl_mtime "$_d/owner") )) s ago"
  done
}

# Wait for a slot.   slot_wait <kind> <count> <stale fn> <pid> <extra> <need GB> <seconds> <what>
# Returns 0 with SLOT set, or 75 when the time is up.
slot_wait() {
  _sw_wait=$7; _sw_what=$8; _sw_waited=0
  while :; do
    slot_take "$1" "$2" "$3" "$4" "$5" "$6" && return 0
    _sw_rc=$?
    if [ $_sw_waited -ge "$_sw_wait" ]; then
      if [ $_sw_rc -eq 3 ]; then
        echo "$_sw_what: not enough free memory after $_sw_wait s ($BL_MEMORY_NOTE); do other work and run this again" >&2
      else
        echo "$_sw_what: every slot stayed busy for $_sw_wait s; do other work and run this again" >&2
      fi
      slot_list >&2
      return 75
    fi
    if [ $_sw_waited -eq 0 ]; then
      if [ $_sw_rc -eq 3 ]; then echo "$_sw_what: waiting for free memory ($BL_MEMORY_NOTE)" >&2
      else echo "$_sw_what: every slot is busy; waiting (up to $_sw_wait s)" >&2; fi
    fi
    sleep 10
    _sw_waited=$((_sw_waited + 10))
  done
}
