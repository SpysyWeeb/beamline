# 30. Next run, batch O2: OSM sign-in, notes under the user's name, fixing a place's details

Status: **done, 2026-11-20** (key m). Batch O2 of docs/online-design.md (its section 7 row: OSM sign-in
and sign-out, Fix a place's details with the week-hours editor, "Closed permanently" and the conflict
check, notes under the user's name, showing your own edits) and the first-run text of "The owner's
answers after batch H" in docs/next-round.md. Version **0.1.11 (versionCode 14)**. No new packs. Nothing
is committed: the lead commits. Scratch, shots and logs are in `data/r3-run2/o2/` (not in git); the
working record is `data/r3-run2/PROGRESS.md`, section "Batch O2". Everything on the emulator went to
OSM's test server only, with the owner's throwaway test account ("the test account" below).

## Sign-in and sign-out (online-design 4.4)

- **Where.** Settings, "Online features", under "Contribute to OpenStreetMap": "Sign in to OpenStreetMap"
  ("Needed to fix a place. Notes can go without it. You sign in on OpenStreetMap's own page."; greyed
  while the switch is off or a trip is driven), or "Signed in as <name> · Sign out". And when the user
  taps "Edit on OpenStreetMap" signed out: a dialog "Sign in to edit" with **Sign in to OpenStreetMap**,
  **Cancel** and **Send a note** (answer 6: the note form opens with "Closed" chosen); after a sign-in
  started there the edit form opens by itself.
- **How.** OAuth 2 authorization code with PKCE (RFC 7636, S256; 32 random bytes as the verifier) and a
  random state. The sign-in page `<sign-in host>/oauth2/authorize` opens in the browser (an `ACTION_VIEW`
  intent: Custom Tabs would need a new library, and `libs.versions.toml` is frozen). The redirect
  `io.github.spysyweeb.beamline://oauth` comes back to `MainActivity` (singleTask, a `BROWSABLE` intent
  filter) and is accepted only for the sign-in this app started: the open sign-in is kept sealed in
  `no_backup/osm-signin.bin` (it survives the process being killed while the browser is open), its state
  must match, it is used once, and it must be younger than 15 minutes. Then `POST /oauth2/token` (code,
  verifier, client id, redirect; a public client, no secret) and `GET /api/0.6/user/details.json` for the
  display name.
- **The token** and the name are sealed with AES-256-GCM under a key of the Android Keystore (alias
  `beamline-osm-account`, never leaves it) in `no_backup/osm-account.bin`: never plain on disk, never
  logged, never in a backup (`allowBackup=false`, and no_backup in any case). A file that cannot be opened
  reads as signed out and is deleted. **Sign out** deletes the file at once and, with the switch on, asks
  OSM to revoke the token (`POST /oauth2/revoke`).
- **Client ids** (public) in `app/build.gradle.kts`: the test server's in debug builds and release builds
  made with test extras, the live one only in the plain release (`BuildConfig.OSM_CLIENT_ID`).

## Notes under the user's name (4.1, answer 2)

Signed in, the note form shows **"Post with my OSM name"**, checked to start with, and its line says
"under your OSM name, so mappers can ask you about it". Checked, the note goes with the token
(`Authorization: Bearer`); unchecked or signed out, anonymously as in O1. A note marked with the name that
waits in the outbox while the user signs out goes anonymously (less is sent, never more). The public-note
notice has a second text for a named note.

## Fix a place's details (4.2)

- **Where.** "Edit on OpenStreetMap" at the details height of a place whose pack entry has an OSM object
  (`poi.osm`, read now with the place's details: `PoiDetails.osm`, `PlaceSheetUi.osm`), under "Report a
  problem", while "Contribute to OpenStreetMap" is on and no trip is driven (GUIDING, REROUTING, ARRIVED).
  A node or way opens the form; a relation opens its page on the build's OSM website instead.
- **Before the form** the app reads the place (`GET /api/0.6/node|way/<id>.json`). If its name, hours,
  phone or website differ from the pack's, the form says "This place changed on OpenStreetMap since your
  map was made. The form shows its current values." Offline, the form starts from the pack's values and
  says that Beamline reads the place again before the edit goes.
- **The form**: Name; **Opening hours** as a week editor (Monday to Sunday, each a switch for open or
  closed and one or more "from - to" times, "Add hours", "Same as Monday"); Phone; Website; **Closed
  permanently**. The week editor writes the usual short OSM form ("Mo-Fr 08:00-18:00; Sa 08:00-13:00";
  equal days grouped, two days in a row with a comma, a whole week round the clock "24/7", times past
  midnight "18:00-02:00") and every text it writes is read back with the app's own hours parser
  (`OpeningHours`, Simon Poole's OpeningHoursParser) to the same week. Hours the editor cannot express
  without loss (holiday rules, comments, months, variable or open-ended times, anything the parser cannot
  read) are shown read-only with "They stay as they are; they can be changed on openstreetmap.org." The
  hours are written only when the week was changed, so a text such as "Mo-Fr 8:00-18:00" is never
  rewritten by just opening the form. The phone and website change the key the place uses
  (`contact:phone` / `contact:website` when it has only those).
- **"Closed permanently"** (OSM wiki Key:disused: and Lifecycle_prefix): each main key the place has
  (`amenity`, `shop`, `craft`, `office`, `leisure`, `tourism`, `healthcare`, `club`) moves under
  `disused:` with its value (`shop=clothes` becomes `disused:shop=clothes`); `opening_hours`, `phone`,
  `contact:phone`, `website`, `contact:website` are removed; the name and every other tag stay (the wiki:
  "The name is still relevant"). Offered only for a place with one of those keys and only after the place
  was read from OSM (offline the box is greyed with a line saying why). The summary names each change.
- **The summary** ("Your changes"): one line a change ("Hours: 08:00-22:00 → Mo 08:00-21:00; Tu-Su
  08:00-22:00"), "Nothing is changed yet." when nothing is, and the line "Uploaded to OpenStreetMap under
  your OSM name as one change with the comment "…". Everyone can see it." **Upload**: the first time
  a notice "Edits are public" (section 3) with Upload / Not now.
- **The upload**: one changeset (`comment` such as "Updated hours and phone" or "Marked a place as closed
  permanently", `created_by=Beamline <version>`, `source=survey`), the element with the version read and
  its tags changed (a node's position and a way's nodes sent back unchanged), then the changeset is closed
  (also after an error). Afterwards "Uploaded…" with the changeset's link and "Open the change on
  OpenStreetMap".
- **A conflict.** Right after the summary the element just read is uploaded as read, so a change made on
  OSM in between is OSM's 409: the form comes back with "Someone changed this place on OpenStreetMap after
  you made your edit…", "Now on OpenStreetMap" with the current values of the keys the user changed, and
  the user's changes on the new values; Next and Upload again.

## The outbox for edits, and your own edits shown (4.5, answer 4)

- Upload always goes through the outbox (one path): the edit is stored (`kind: "edit"` in
  `no_backup/osm-outbox.json`: the object, the version read or none, the edited keys' starting values,
  the changes, the comment) and sent at once when it can be. Offline it waits ("Your edit waits in My
  reports and goes by itself when you're online and Beamline is open") and goes by itself, as notes do,
  when the app comes to the front or a network appears while it is open; signed out it waits for the
  account ("Waiting: sign in to OpenStreetMap to send it").
- **At send time** the place is read again; if it changed since the edit was made (a newer version; for an
  edit made offline from the pack's values, a changed value of an edited key) the edit is not uploaded but
  waits as **"Changed on OpenStreetMap meanwhile: open it to look again"**; opened, it is the form with the
  current values and the user's changes on them.
- **My reports** lists notes and edits ("Edit · Subway", "Note · Erie City Hall"), newest first;
  an edit can be opened, changed or deleted until it went; a sent one keeps "Uploaded · changeset
  N" and its link.
- **Your own edits**: sent edits stay in the outbox (no_backup, PV1-PV8) and the place sheet shows them at
  the details height under **"Your edit, waiting for the next map update"**, one line a change ("Hours:
  Mo 08:00-21:00; Tu-Su 08:00-22:00", "Closed permanently"), until an installed pack shows the same values
  (then the edit is marked as in the pack and no longer shown).

## The first-run text (the owner's answers after batch H)

"Allow online features" now reads: "You can report map problems and fix places on OpenStreetMap.
Beamline sends only what each feature needs, when you use it, and tells you what that is first." Finding
places online comes back into it with batch O4. Settings' "Contribute to OpenStreetMap" line: "Report map
problems as public notes and fix places' details on OpenStreetMap."

## The network rule (online-design section 6; architecture-downloads.md 4.1)

`OsmHttp` stays the one OSM class. Hosts (N2): the test server (API and sign-in) in test builds;
`api.openstreetmap.org` and, new, `www.openstreetmap.org` (token and revoke) only in the plain release.
Requests (N3), each refused before any connection while the switch is off (N4): the note; `POST
/oauth2/token`, `POST /oauth2/revoke`, `GET /api/0.6/user/details.json`; `GET /api/0.6/node|way/<id>.json`,
`PUT /api/0.6/changeset/create`, `PUT /api/0.6/node|way/<id>`, `PUT /api/0.6/changeset/<id>/close`; and in
test builds only, refused for openstreetmap.org, `PUT /api/0.6/node/create` for the test objects (a test
extra, below). `check_network_rule.py scan` now also requires every web address written in `OsmHttp.kt`
to be `https://` to one of the three fixed OSM hosts.

## Test objects on the test server

The pack's OSM ids do not exist on the test server, so a test extra of test builds (`--es osm_test
<base64 JSON>`, signed in, test server only; `data/r3-run2/o2/osm_test.sh`) makes or changes a node with
the signed-in account, and maps a pack id to it (`--es osm_map n<pack>=n<test>`). Two objects, at the
public places they copy, each tagged `note=Beamline development test object on the OSM test server, please
ignore`: **n4365850187** (the pack's Subway, 425 State Street, pack `n7570266250`) and **n4365850188**
(Primo Tailoring, 421 State Street, pack `n9121037167`).

## On the emulator (beamline_m, PA pack; `o2/shots/`, each looked at)

Test build `o2/apk/beamline-release-tx.apk` (`:app:assembleRelease -Pbeamline.testExtras=true`) `install
-r` over 0.1.10: Success.

| Shot | What it shows |
|---|---|
| 01 | Offline only: Settings, both switches off, "Sign in to OpenStreetMap" greyed, "My reports 2 sent" |
| 02 | Online on: both switches on, "Sign in to OpenStreetMap" active (signed out) |
| 03 | The browser (WebView shell) on the test server's login page: "Log in to OpenStreetMap to access Beamline test." |
| 04 | After the test account was typed in there (`o2/type_login.py`, prints nothing of it): "Authorization Required", read_prefs, write_api, write_notes, Authorize / Deny |
| 05 | After Authorize: back in Beamline's Settings, "Signed in as <the test account's name> · Sign out" |
| 06 | Subway's details height: hours, phone, website, address, "Report a problem", "Edit on OpenStreetMap" |
| 07 | The note form with "Wrong hours", the text, "Post with my OSM name" checked and its line |
| 08 | My reports: "Note · Subway, Sent · note 411472" |
| 09, 10 | The edit form: name, the week editor (Monday 08:00 to 22:00 …, "Same as Monday", "Add hours"), phone, website, "Closed permanently" with its line, Next |
| 11 | The summary: "Hours: 08:00-22:00 → Mo 08:00-21:00; Tu-Su 08:00-22:00", "Phone: +1 814-456-2718 → +1 814-456-2799", comment "Updated hours and phone" |
| 12 | "Edits are public" notice with Not now / Upload |
| 13 | "Uploaded…", the changeset link `…/changeset/732642`, "Open the change on OpenStreetMap" |
| 14 | The place sheet: "Your edit, waiting for the next map update", the two lines |
| 15 | The conflict: notice, "Now on OpenStreetMap: Website: https://example.org/beamline-test", the form with the user's change |
| 16 | The summary again: "Website: https://example.org/beamline-test → https://www.subway.com/" (uploaded: changeset 732651) |
| 17, 18 | Wi-Fi and data off: the form from the map's values with the offline line; after Upload "Your edit waits in My reports…" |
| 19 | My reports: "Name: Subway → Subway Erie test, Edit · Subway, Waiting to be sent" above the sent ones |
| 20 | Network back with My reports open: within 15 s "Uploaded · changeset 732656" |
| 21, 22 | Primo Tailoring with "Closed permanently": hours, phone, website hidden; summary "shop=clothes becomes disused:shop=clothes", "Removed: opening_hours, phone, website", "The name stays…" |
| 23 | After Sign out: "Sign in to OpenStreetMap" again |
| 24 | Signed out, "Edit on OpenStreetMap": "Sign in to edit" with Sign in / Cancel / Send a note |
| 25 | "Send a note": the note form with "Closed" chosen, without the name box |

**On the test server** (`o2/net/*.json`, user fields stripped): note **411472** opened with a user (the
test account), text "Wrong hours: Beamline development test on the OSM test server, please ignore." and
"via Beamline", at the place's point. Changeset **732642** closed, 1 change, `comment=Updated hours and
phone`, `created_by=Beamline 0.1.11`, `source=survey`; node n4365850187 v2 with the new hours and phone
and every other tag kept. The conflict's changeset **732646** opened and closed with 0 changes (OSM's 409);
**732651** then carried it. The offline edit: **732656**, node v5 with the new name. Closed permanently:
**732659**, node n4365850188 v2 tags exactly `disused:shop=clothes`, `name`, `note`.

**The token on disk** (the debug build, same key and data, `run-as`): `no_backup/osm-account.bin` 102
bytes, neither "token" nor the account's name in it; `osm-signin.bin` gone after the sign-in. After Sign
out the account file is gone (`run-as ls no_backup/`).

**Traffic accounting** (`dumpsys netstats detail`, uid 10153, `data/r3-i/scripts/netstats_uid.py`):
- **Offline only** (cold start, Settings, the section 8 drive): no bytes at all, no tag
  (`o2/net/offline-result.txt`). The O1 proof holds.
- **Online** (from the switch through sign-in, the note and all edits): tag `0xb1b` rx 62,777 / tx 23,694
  B of the uid's 63,554 / 24,011; DNS (`0xffffff82`) 337 / 277; the rest 440 / 40 B. (The script's
  FAIL line counts everything but `0xb1a` as untagged; it was written for pack downloads.)
- **One pack download under `0x0B1A`** (batch H's open point): the release builds have downloads off (the
  only catalog configured is a local http one, which a release drops), so the debug build with the local
  catalog (`python -m http.server` over `data/catalog/site`, stopped after): Ohio started, cancelled at
  about 300 MB and deleted. Tag `0xb1a` rx 334,978,475 / tx 2,988,398 B of the uid's 334,992,190 /
  2,992,607; the rest 13.7 / 4.2 KB of which `0xb1b` 13.0 / 3.8 KB (no OSM action in that window: most
  likely the minutes-earlier OSM traffic reaching netstats late) -> within 16 KB.

**Section 8 clean drive** (Offline only, `data/r3-run2/m/drive.py default o2/e2e day`): banners 170.2,
221.3, 250.4 s, **ARRIVED at 288.4 s** (`o2/drive.log`).

**The phone APK** `data/android/apks/batchO2/beamline-release.apk` (plain release: OSM server live,
the live client id, no test extras): **85,335,485 B**, SHA-256
`c148854043bfc76c70df05923398880b89419247368e523b2ada283ec9e77786`. On beamline_m, after `pm install -r -d`
of batch H's APK (0.1.10), `install -r` of it: Success, 0.1.11 / 14; it starts on the map, Offline only,
signed out.

## Checks

- JVM, `:app` online package: `OsmAuthTest` 5 (PKCE against RFC 7636 appendix B, the sign-in address
  carries the challenge and not the verifier, only this sign-in's redirect accepted: other state, no
  state, other address or scheme, a "Deny", too old; the token and the name not in the sealed file, read
  back with the key, unreadable with another key and then deleted, sign-out deletes; the open sign-in
  sealed too), `WeekHoursTest` 4 (the writer's forms; nine texts round-trip through the parser; holidays,
  comments, months, sunrise, open ends, unreadable text left read-only; clock texts), `OsmEditTest` 9 (the
  token request's exact form, no secret, no cookie; every new request refused with the switch off; an
  edit's exact four requests, XML and changeset tags, the other tags kept, the link and the local copy;
  409 asks again without a second read and closes the changeset; a newer version found before sending
  waits without opening a changeset; offline-made edits stop only on a changed edited key; edits wait
  signed out or offline; a note under the name only when asked and signed in; Closed permanently's exact
  tags, summary and comment; the form's keys), plus O1's `OsmHttpTest` 8 and `OnlineSettingsTest` 3:
  `sh tools/round/gradle.sh m :app:testDebugUnitTest --tests '*online*'`.
- The whole JVM suite (`BEAMLINE_TESTS_STRICT=1 … test --continue`): BUILD SUCCESSFUL; 810 results (batch H
  792), 0 failures, 1 skipped (`o2/all-tests.log`).
- `python tools/check_contracts.py`: all the same, 44 frozen files unchanged. `check_private_logs.py scan`:
  0 breaches (selftest ok). `check_network_rule.py scan`: 0 findings; `privacy`: 0; merged manifests debug
  and release: 0 problems. `android/routing/scripts/sync_data.py --check`: same.
- `:app:assembleDebug :app:assembleRelease :app:lintRelease`: pass, lint 0 errors, 60 warnings (55 before;
  the five new are `Log` lines of the test extras and the sign-in redirect, "LogNotTimber").

## Contract changes

- `docs/architecture.md`: 2.6 `SearchUi.kt` (quoted whole): `PlaceAction.EDIT_OSM`; `PlaceSheetUi.osm`,
  `poi`, `canEditOsm`, `ownEdit`; the `PoiDetails` import. Section 4: N1-N4 and the proof line for O2,
  PV1 (the account file, the edits in the outbox). Decision **6.90** (new).
- `docs/architecture-saved-places.md`: the `SearchUi.kt` excerpt (as above); PV1 (the OSM account and the
  edits beside the saved places under PV1-PV8).
- `docs/architecture-downloads.md` 4.1: the per-service table's `OsmHttp` row (hosts, requests), N2, N3,
  N4; 4.2 item 2 (the source lint's host check) and item 4 (the new JVM tests).
- `docs/online-design.md`: status (O2 built), section 2 the "Allow online features" text, 4.2 the exact
  tags of "Closed permanently", 4.4 what was built.
- Not quoted contracts that changed: `PoiDetails` gains `osm` (`:core:search`; `SearchEngine.poiDetails`
  reads `poi.osm` and keeps a POI that has only an OSM object); `OsmServer.authBase`; `OutboxItem.withName`
  / `edit`, `OutboxState.REVIEW`; `OnlineSettings.editNoticeSeen` (`online.json` key `edit_notice_seen`);
  the outbox file's `kind: "edit"` items; `android/scripts/check_network_rule.py` (the host check);
  `AndroidManifest.xml` (the redirect's intent filter); `app/build.gradle.kts` (0.1.11 / 14, the two client
  ids, `OSM_CLIENT_ID`).

## Not verified

- Nothing on the owner's phone; the plain release never signed in to or posted on openstreetmap.org (by
  design); its live client id and the www.openstreetmap.org token endpoint are not exercised.
- The browser was the emulator's WebView shell (there is no Chrome on beamline_m); with Chrome or another
  browser the redirect is the standard one but not seen here. Custom Tabs are not used.
- Whether OSM accepted the revoke request at sign-out: it was sent (switch on) but its answer is not
  looked at, and the old token was not tried afterwards.
- The named variant of the public-note notice: not seen (the notice was already accepted in O1).
- "Changed meanwhile" found at send time (REVIEW without a 409) on a device: JVM test only; on the device
  the conflict was OSM's 409.
- A relation's "Edit on OpenStreetMap" (opens the website), the hours' read-only case and a refused edit
  (4xx other than 409) on a device: code and JVM only.
- A pack download from a public catalog host: none exists; measured with the debug build's local catalog.
- "Hidden while guiding" for "Edit on OpenStreetMap" not shown on a screen (the same condition as O1's
  report entry).
- TalkBack, landscape and large fonts on the new screens not looked at.
- The test objects n4365850187, n4365850188, notes 411417, 411419, 411472 stay on the test server.
