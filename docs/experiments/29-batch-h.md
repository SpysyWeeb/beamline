# 29. Next run, batch H: the place itself as the last end; online as a choice, batch O1

Status: **done, 2026-11-20** (part 1 key n, part 2 key m). Two parts: (1) the first bullet of "The
owner's answers after batch G" in docs/next-round.md, the place itself as the last end; (2) batch O1 of
docs/online-design.md (its section 7 row: the switch, the network rule per service, the outbox, Report a
problem). Version **0.1.10 (versionCode 13)**. No new packs. Nothing is committed: the lead commits.
Scratch, shots and logs are in `data/r3-run2/h1/` and `data/r3-run2/h2/` (not in git); the working record
is `data/r3-run2/PROGRESS.md`, sections "Batch H, last end" and "Batch H, O1".

# Part 2: online as a choice, batch O1 (key m)

## The switch (online-design section 2)

- **First run.** Right after the driver notice, one screen "Online features" with two equal cards,
  neither selected: **Offline only** ("Beamline makes no internet requests except the state downloads
  you start.") and **Allow online features** (the text of section 2). A tap on a card is the choice; Back
  does not skip it. Someone updating from a version without it sees it once at the next start (no
  `online.json` yet: the choice is not made). Then the state picker of the first run, as before.
- **Settings, "Online features".** The main switch ("Online features"; under it "Offline only: no internet
  requests except the state downloads you start." or "Beamline uses the internet only for the features
  below, when you use them."), under it "Contribute to OpenStreetMap" (greyed while the main switch is
  off), then "My reports" with a count ("None yet", "1 waiting · 2 sent"; "Not while guiding" while a trip
  is driven). Turning the main switch on (or "Allow online features") turns every feature on; each can then
  be turned off alone. "Find places online" (O4) and the OSM account line (O2) go under it later.
- **Where it is kept.** `files/online.json` (`OnlineSettingsStore`, in `:app`'s `online/` package), not
  the frozen `:core:settings`: only `:app` uses it. A broken or foreign file reads as "Offline only, not
  chosen".

## The network rule per service (section 6)

`OsmHttp` (`app/online/OsmHttp.kt`) is, with `PackHttp`, the only code that opens a connection:

- **Hosts:** `OsmServer.TEST` (`master.apis.dev.openstreetmap.org`) in debug builds and release builds
  made with `-Pbeamline.testExtras=true`; `OsmServer.LIVE` (`api.openstreetmap.org`) only in the plain
  release (`BuildConfig.OSM_SERVER`). No other server can be made outside the file and its tests; every
  address the name resolves to must be public.
- **Request:** one, `POST /api/0.6/notes.json` with a form body `lat`, `lon` (7 decimals), `text`. The text
  and point travel in the body, never in a URL. Headers: `User-Agent: Beamline/0.1.10`, `Accept:
  application/json`, `Accept-Encoding: identity`, and OkHttp's `Content-Type`, `Content-Length`, `Host`,
  `Connection`. No cookies, cache, proxy or redirects; no identifier.
- **Switch:** every request first asks "Online features" and "Contribute to OpenStreetMap"; while either is
  off it throws `OnlineRefused` before any connection.
- **Sockets** are tagged `0x0B1B` (PackHttp `0x0B1A`), so the device accounting tells OSM traffic apart.
- **Nothing logged:** the `online/` package has no log call and builds no exception message from a value
  (`check_private_logs.py` now knows it); `OsmError` carries the status only.
- Documents: architecture-downloads.md 4.1 gets a per-service table and N1-N4 per class, 4.2 items 2, 4, 5;
  architecture.md section 4 N1-N4 and the proof line, PV1, PV2, the `:core:download` and file-table rows,
  decision 6.89; architecture-saved-places.md PV1 (the outbox). `check_network_rule.py scan` allows
  `OsmHttp.kt` (OkHttp, `javax.net`, `InetAddress`).

## The outbox and "My reports" (section 4.5)

`no_backup/osm-outbox.json` (`OutboxStore`, format `beamline-outbox/1`) beside the saved places, under
PV1-PV8: never logged, never in a backup (`allowBackup` false; the no-backup folder), `OutboxItem.toString()`
prints its state only, a test session (`private_data test`) gets its own folder. An item is confirmed by
the form's Send; it is **waiting**, **sent** (the note's number and its link: "Open on OpenStreetMap",
"Remove from this list") or **refused** (an error status that sending again will not change: shown in red,
it can be changed and sent again). A waiting or refused item opens in the form ("Your note": Delete /
Save) until it is sent; an item going out at that moment cannot be changed. `OutboxSender` sends waiting
items oldest first: after Send, when the app comes to the front, and when a network appears while it is in
front (a default-network callback registered in `onStart`, removed in `onStop`). No background service.

## Report a problem (section 4.1)

- **From a long press:** the long press keeps its dropped pin; the pin's sheet gets **"Report a problem
  here"** beside Directions and Save. Decided over a new long-press menu: no second menu competing with
  the pin, at the cost of one more tap.
- **From a place:** **"Report a problem"** at the bottom of the details height (batch G).
- **The form:** the place's name or "The point you pressed: 42.12915, -80.08530", the five quick choices
  (Closed, Wrong hours, Wrong location, Road missing, Turn not allowed: one at a time, a second tap clears
  it), a text box (optional with a choice; 1,900 characters at most), the line "Sent as a public
  OpenStreetMap note at this point, without your name. Local mappers read it and fix the map. It ends with
  the line "via Beamline"." and Send. The note reads "Choice: words", "Choice" or the words.
- **The point:** the pressed point, or the place's point; never the phone's position.
- **The first Send** shows "Notes are public" (section 3: text, point and time are public, no name; only
  the text, the point and the app's name are sent) with Send / Not now; Send there remembers it.
- **Hidden** while the switch is off and while a trip is driven (GUIDING, REROUTING, ARRIVED: "the buttons
  come back when the trip ends"). Notes are anonymous in O1.
- **"via Beamline"** ends every note, without a version: an anonymous note has no other sign of where it
  came from, and it tells mappers the user saw an offline map that may be weeks old (StreetComplete and
  Organic Maps add such a line). The form says so before Send.

## Found and fixed: untagged sockets to a public host

The first online run counted OSM's bytes as untagged (`h2/net/online-result.txt`: tag `0x0` only). An
unconnected `java.net.Socket` gets its descriptor only when something needs it, and Android tags the
descriptor when it is made, with the making thread's tag. `TaggingSocketFactory` set the tag around
`Socket()` and `tagSocket`, but without a network to bind (`bindSocket` makes the descriptor) the
descriptor was made later on OkHttp's thread, untagged. This also applied to `PackHttp` with a public
catalog host; the earlier `0xb1a` proofs (reports 18, 19) used a local catalog, which is bound. The factory
now reads `soTimeout` under the tag, which makes the descriptor there. After the fix OSM's bytes are on
`0xb1b` (below). PackHttp to a public host was not re-measured.

## On the emulator (beamline_m, PA pack; `h2/shots/`, each looked at)

Test-extras release (`h2/apk/beamline-release-tx.apk`) `install -r` over 0.1.9:

| Shot | What it shows |
|---|---|
| 01 | The first-run choice after the update: title, intro, the two equal cards, nothing selected |
| 02 | Settings after "Offline only": the main switch off with the Offline only line, "Contribute to OpenStreetMap" greyed and off, My reports "None yet" |
| 03 | Offline only, long press in Perry Square: the dropped pin with Directions and Save only |
| 04 | Settings, main switch on: both switches on |
| 05, 06 | Long press: "Report a problem here" beside Directions / Save; the form with the pressed point, the five choices, the text box, the public-note line, Send greyed until something is chosen or written |
| 07 | Wi-Fi and data off, text typed, Send: "Notes are public" with Not now / Send |
| 08, 08b | My reports: the note "Waiting to be sent" (and "Send now"); the item open: "Your note", Delete / Save |
| 09 | Wi-Fi and data back on with My reports open: within 15 s, by itself, "Sent · note 411417", Open on OpenStreetMap / Remove from this list |
| 10, 11 | Erie City Hall (map tap) at the details height: "Municipal building", the address, "Report a problem"; its form with "Wrong location" chosen |
| 12 | My reports: note 411419 (Erie City Hall) and 411417, both sent |

**The test server's answer** (`GET /api/0.6/notes/<id>.json`, `h2/net/note-*.json`): note **411417**, open,
anonymous, at 42.1291546, -80.0852982 (the pressed point), text "This is a Beamline development test on
the OSM test server, please ignore.\n\nvia Beamline"; note **411419**, open, anonymous, at Erie City Hall's
point, "Wrong location: Beamline development test on the OSM test server, please ignore.\n\nvia
Beamline". Two test notes in all, both at public places; nothing sent to openstreetmap.org.

**Traffic accounting** (`dumpsys netstats detail` before and after, uid 10153, `data/r3-i/scripts/netstats_uid.py`):

| Run | All of the app's traffic (tag 0x0) | OSM tag 0xb1b | The rest |
|---|---|---|---|
| Offline only: start, the choice, Settings, long press, the section 8 drive | 0 B / 0 B | none | 0 B: **no request at all** |
| Online, before the tag fix (note 411417) | rx 6,743 / tx 1,642 B | none (the bug above) | all of it |
| Online, after the fix (note 411419) | rx 7,043 / tx 2,010 B | rx 6,721 / tx 1,772 B | DNS (tag 0xffffff82, 242 / 198 B) and about 80 / 40 B |

**Section 8 clean drive** (Offline only, `data/r3-run2/m/drive.py default h2/e2e/default day`): banners
221.3 / 250.4 s, **ARRIVED 288.4 s** (as batch G).

**The phone APK** `data/android/apks/batchH/beamline-release.apk` (plain release: OSM server
openstreetmap.org, test extras off): 85,302,569 B, SHA-256
`727f9d62c3d91067bfd418e0a60ad39b76e6a7d59aacc6dedc9a08a082d7ad88`. On beamline_m: batch G's APK put back
with `pm install -r -d` (0.1.9), then `install -r` of this file: Success, 0.1.10 / 13; it starts on the map
(the choice kept: Offline only).

## Checks

- JVM: `OsmHttpTest` 8 (switch off refuses with the server seeing nothing; the request's exact method,
  path, body and header set; no cookie back after a Set-Cookie; error status only; fixed hosts, a private
  address for the test server's name refused; the outbox sends oldest first, keeps number and link, waits
  offline or with the switch off, a refused note can be changed; `OutboxItem` prints no text or point; a
  broken outbox file is kept aside) and `OnlineSettingsTest` 3 pass. Whole JVM suite: in PROGRESS.
- `check_contracts.py` all the same, 44 frozen unchanged; `check_private_logs.py scan` 0 breaches,
  selftest ok; `check_network_rule.py scan` 0, `privacy` 0, `manifest` debug and release 0 problems;
  `sync_data.py --check` all the same.
- `:app:assembleDebug :app:assembleRelease :app:lintRelease`: pass, lint 0 errors, 55 warnings.

## Contract changes (part 2)

- `SearchUi.kt` (quoted whole in architecture.md 2.6, excerpt in architecture-saved-places.md):
  `PlaceAction.REPORT` and `PlaceSheetUi.canReport`.
- The network rule N1-N4 per service (architecture-downloads.md 4.1, 4.2; architecture.md section 4),
  PV1 and PV2's no-log list, decision 6.89; online-design.md status and 4.1 (the "via Beamline" line, the
  pin's button).
- `TaggingSocketFactory(tag = TAG, network)`: the tag as a parameter; `OSM_TAG = 0x0B1B`; the descriptor
  made under the tag.
- `check_network_rule.py` (OsmHttp.kt allowed), `check_private_logs.py` (the `online/` package no-log).
- `:app` 0.1.10 / 13, `BuildConfig.OSM_SERVER`; test extra `--es online offline|online|ask`.

## Not verified (part 2)

- Nothing on the owner's phone. The plain release's live server was never used (no note from it).
- "Hidden while guiding" is in code (`canReport` needs no trip being driven) but not shown on a screen; the
  outbox's send when the app comes to the front was not seen apart from the network callback (shot 09).
  TalkBack, landscape and large fonts not looked at.
- The packet-capture form of the proof was not run; the per-app accounting is the proof used.
- A note refused by OSM (a 4xx status) is shown by the JVM test only.
- The two test notes stay open on the test server (closing one needs an account; O1 makes none).

# Part 1: the place itself as the last end (key n)

Status of this part: **done, 2026-11-20** (key n). "Batch E2's last end: yes, add the place itself,
untied, as the last end of every list, so the nearest street has to pass the check against it (at most
90 s slower, no extra U-turn)". Scratch in `data/r3-run2/h1/`. First written as
`29-batch-h-last-end.md` and folded in here unchanged, except this paragraph.

## What changed and why

Batch E2 (report 26) gave each stop a list of ends tried in order with the plan's Fastest request; the
last end on a list was kept with no check. For a plain place (not a point of interest) whose address
street is out of reach, the list was the nearest street alone, so it could end on the nearest street
with a U-turn: the reference case `address_out_of_reach` (Ulta Beauty given as a plain address) ended
191 m from the place with one U-turn (586.6 s), where 0.1.6's untied route ended 32 m from it with none
(621.9 s).

### 1. The place itself, untied, ends every list (2.11 "Every request" 7)

route_lib `stop_ends` / `RoutePlanner.ends`:

| Stop | Ends, best first |
|---|---|
| point of interest | its lot, its address street, the nearest street, **the place** |
| a place that names its street | that street, the nearest street, **the place** |
| any other tied place | the nearest street, **the place** |
| a map point, a saved or recent pin | exactly as it is (unchanged) |
| a stop already decided | exactly as it is (unchanged) |

"The place" is the stop's own point with no stop tie (route_lib `_tie` "none", Kotlin `Stop.tied` false:
no every_request stop options), marked `_end` "place" / `StopEnd.PLACE`. A point of interest's left-out
boxes (rule 8) stay on it, as on its other ends. So the nearest street now passes rule 5's check like
every other end: at most 90 s slower than the place, no more U-turns, no alley or drive-through lane in
its own last 300 m.

### 2. The place never wins through an alley or a drive-through lane (2.11 "Every request" 5)

The untied place snaps to any edge, a service road, an alley or a drive-through lane included, which is
what rule 2's tie was made to avoid. The owner's C1 rule is "no alleys unless there is no other way".
Decided:

- When the nearest street would lose to the place (slower by more than 90 s, or a U-turn more), the
  place's own last 300 m are asked (one `trace_attributes` call, `edge_walk`, else `map_snap`, as for
  every end). A place route with an alley or a drive-through lane there does **not count** against the
  nearest street, which then goes on to its own tail check.
- When the place would be kept anyway (the nearest street found no way, or its own last 300 m use an
  alley or a lane), the same check of the place's tail decides: with an alley or a lane the nearest street
  is kept instead **when its route finds a way**. Only when the nearest street finds no way at all does
  the place stay with an alley (there is no other way).
- A failed tail call does not count against the place (as for every end).

Why this way and not "drop the place from the list up front when its tail is bad": that costs a tail
call for every stop whose decision reaches the nearest street, even when the street passes easily; this
way the place's tail is asked only when the place would win (at most one more call per stop).
route_lib `_tail_never` and `decide_ends`; Kotlin `RoutePlanner.tailNever` and `decide`, in step.

Every route type and every re-route keep going to the decided end (rule 7 unchanged: the decided stop,
the place included, is `Stop.fixed` and sent as it is).

## The reference case

`address_out_of_reach` (fastest/none): **the place**, 621.9 s, 11.203 km, 32 m from the place, no U-turn
(batch E2: the nearest street, 586.6 s, 191 m, one U-turn). Its arrival side is now asked (the place has
no side options): "asked", the first route kept. As a point of interest Ulta Beauty still ends in its lot.

## Measured on the C1 review's 450 Erie plans (`h1/measure_h1.py` -> `h1/measure_h1.txt`)

150 POIs with an address (seed 11) x 3 origins (Perry Square, exit 24, east Erie), as e2/measure_e2.py;
before = batch E2's route_lib (git 67516ca, copied to `h1/route_lib_e2.py`), now = this route_lib. Fastest
decides the end, Simpler is planned to it (as the app's session does). Each place twice: as a point of
interest (how the app sends a search POI) and as a plain address (no POI flag).

| | as POI, before | as POI, now | as address, before | as address, now |
|---|---|---|---|---|
| plans | 450 | 450 | 450 | 450 |
| decided ends that change | | 0 | | **4** (nearest street -> place) |
| ends: lot / street / nearest / place | 224 / 218 / 8 / - | 224 / 218 / 8 / 0 | 0 / 434 / 16 / - | 0 / 434 / 12 / 4 |
| Fastest U-turns (plans with one) | 3 | 3 | 7 | **3** |
| Simpler U-turns | 0 | 0 | 3 | **1** |
| alley in the last 300 m (Fastest, Simpler) | 0, 0 | 0, 0 | 0, 0 | 0, 0 |
| drive-through lane in the last 300 m | 0, 0 | 0, 0 | 0, 0 | 0, 0 |
| end to place, median / p90 / max m | 22 / 50 / 283 | 22 / 50 / 283 | 35 / 88 / 283 | 34 / 84 / 283 |
| ends over 100 m from the place | 12 | 12 | 39 | 35 |
| Fastest and Simpler end apart | | 0 | | 0 |
| route calls / trace calls per two-type plan | 6.24 / 2.99 | 6.26 / 3.01 | 6.39 / 1.97 | 6.44 / 2.01 |
| two-type plan on the PC, median / p90 ms | 128 / 173 | 128 / 174 | 100 / 145 | 100 / 144 |

The 4 changed ends are two places at Millcreek Mall, Ulta Beauty and Carter's, from Perry Square and from
exit 24: each now ends 32 m from the place with no U-turn instead of 191 m away on Millcreek Mall Boulevard
with one (Fastest +31 to +35 s; Simpler +31 to +66 s: from Perry Square Simpler had no U-turn to the
street either, and it follows the end Fastest decides). From the east origin the nearest street had no U-turn and stays. As points
of interest nothing changes: the lot or the street end passes before the list reaches the nearest street,
and the 8 nearest-street ends pass against the place. No route in either mode, before or after, has an
alley or a drive-through lane in its last 300 m; no tail call failed.

How often the guard of section 2 matters (`h1/scan_place_tail.py` -> `h1/scan_place_tail.txt`): of the 450
untied place routes alone (Fastest, the place as a plain address), **45 reach the place through an alley
(24) or a drive-through lane (21)** (a Taco Bell's lane, churches and an office reached by an alley). In all
45 the decision keeps the address street (it passes before the place is reached), so on this sample the
guard never had to fire; it is proved by the unit tests (below), not by an Erie case.

## Reference plans re-recorded (`android/routing/scripts/reference_routes.py`; diff `h1/diff_ref.py`)

- plans.json: of 291 rows **1 route changes** (`address_out_of_reach` fastest/none, above); 264 rows only
  change their `tie_check` label from absent to "kept" (a plain tied stop now has a choice: nearest street
  or place, and the nearest street passes). No other field changes.
- trips.json: no route changes; only its `meta` request counts (393 -> 666 requests, 297 -> 427 distinct:
  the format-1 presets decide with themselves, so a pool preset now also runs its pool to the place).
- recordings.json.gz, recordings-plans.json.gz: the new requests (the place route, its tail).
- errors.json: `run_errors` now also records the requests the planner asks for the two error trips that
  succeed (Lake Erie, Jamestown): the place route was already there, the tail call of the kept end was
  not, and the Kotlin `recorded errors` test plans them with the planner.
- banner-routes.json.gz, **exit24-osrm.json** (and exit24-osrm-device.json), reorder.json: unchanged
  (byte-identical). The exit 24 golden trace `android/routing/testdata/nav/exit24.json` is not touched.

## Proved (and how to re-run)

- Python: `packbuilder/routing`: `../../.venv/routing/Scripts/python -m unittest test_presets` 32 OK
  (new `test_the_place_itself_untied_is_the_last_end_and_never_wins_with_an_alley`: Ulta's numbers ->
  place; 712 s vs 621.9 -> place, 711 s -> nearest street; the place with an alley or a drive-through lane
  in its tail -> nearest street; the nearest street with no way -> the place even with an alley; a map
  point has no list; at most 2 tail calls). Four count assertions updated (+2 route calls: the place and
  its fallback).
- Kotlin `:routing:core` (`sh tools/round/gradle.sh n :routing:core:test`): 53 tests pass; new
  `PlannerRulesTest` `the place itself is the last end and never wins with an alley or a drive-through
  lane` (the same six cases and the pin); `PlansReferenceTest` compares every plan's end and end point with
  Python (Ulta now `StopEnd.PLACE`, UNTIED, no U-turn); call counts updated in PlannerReferenceTest
  (`extraCalls`: the place route and one tail call), PlannerRulesTest, PresetsFormat2Test.
- `:nav:session:test` passes (15 classes). Whole JVM suite: see data/r3-run2/PROGRESS.md "Batch H, last end".
- `python android/routing/scripts/sync_data.py --check`: all the same (presets.json copied after its note
  changed). `python tools/check_contracts.py`: all the same, 44 frozen files unchanged.

## Contract changes

- docs/architecture.md 2.11 "Every request" 5 (the place never wins through an alley or a lane; call
  counts) and 7 (the place ends every list; `StopEnd.PLACE`; the measure); decision row 6.88.
- packbuilder/routing/presets.json `every_request.tie_check_note` (text only; copied to
  `:routing:core` resources by sync_data). No rule value changes.
- route_lib: `_end` "place", `_tail_never`; `tail_uses` asks nothing for an empty shape (as Kotlin).
- Kotlin: `StopEnd.PLACE("place")`, `RoutePlanner.tailNever`.
- android/routing/README.md; reference_routes.py `run_errors` (and its docstring).

## Not verified

- No emulator or phone run (none needed: the decision is engine-side and identical in Python and Kotlin on
  the reference plans). Plan time on the phone not measured; on the PC the median is unchanged and the
  extra calls are +0.02 to +0.05 route and +0.02 to +0.04 trace calls per plan on the 450.
- The alley guard fired in no Erie plan of the sample; it is shown by the unit tests only.
- A point of interest with no lot and no street end (list: nearest street, place) is covered by the code
  path of a plain place, not by a reference case.
