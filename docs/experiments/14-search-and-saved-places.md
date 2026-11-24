# Experiment 14: search and saved places (track S, feature round)

Date: 2026-11-16 (night, unattended). Track S, built in two stages by two engineers: stage 1 (the
JVM side, then most of stage 2), stage 2 (review of all of it, the fixes below, the device run, this
report); then two reviews, and a third engineer who checked and fixed their findings (section
"Review and fixes", which supersedes the figures and hand-offs it names in the older sections).
Contract: `docs/architecture.md` 2.6, 2.12, 2.14, 3.8, 4, 5.1 S, 6.10-6.12, 6.17, 6.20,
6.54-6.69 and `docs/architecture-saved-places.md` (all of it). Code: `android/core/search/`,
`android/feature/search/`, `android/scripts/{copy_search_rules,check_private_logs}.py`,
`packbuilder/search/{query.py,ranking.json,saved_places.py,tests/test_saved_places.py}`,
`android/core/search/conformance/make_queries.py`. Scratch and evidence (not in git): `data/r3-s/`
(PROGRESS.md, logs, the regenerated conformance reference, the screenshots and their day/night
pairs, the accessibility dumps, the device timing).

## What exists now

- **Input cap (S5, 6.10).** `ranking.json` `search.max_query_chars` = 256 (board note `s-ranking.md`,
  written first; P hashed it into `ranking_sha256`). `query.py` cuts `text[:256]` (code points) before
  anything else; the Kotlin engine does the same in `SearchEngine.search` (`capCodePoints`); the
  service's own cap is gone. The answer's `query` is the cut text in both.
- **Conformance.** `make_queries.py` gained 7 "cap" queries (longer than 256 code points: an emoji at
  the cut, a combining mark, a house number with a long tail), appended after every random draw so the
  1,069 old queries are unchanged (checked). Reference regenerated into `data/r3-s/search-conformance`.
- **`RulesAndVersionsTest.missingOrForeignVersionsAreRefused`** no longer needs a pack (tiny
  databases only); the real-pack refusal moved to its own test, which looks the pack up outside
  `assertThrows`.
- **`SearchHub`** (per-generation `SearchService` handles, old ones closed before the next call;
  lazy open; warm-up read at open, 6.17), **the fingerprint check at open** (`AddrKeyFingerprint` over
  P's `fingerprint_spellings.json`, copied in by `copy_search_rules.py`; a search part or address
  file keyed otherwise, or a search part without the key, is refused for that pack only, with
  `PackInfo.searchProblem`'s own words "built with another address normaliser"), **`MultiPackSearch`**
  (S3, S4).
- **`RecentPlaces`** (`beamline-recents/1`), **`SavedPlaces`** (SP50-SP56), `SavedRules`, the
  `SavedPlacesFile` codec, `savedNameKey` and the grapheme count, `foldText`, the matcher (SP20);
  `packbuilder/search/saved_places.py` (standard library; `--check` prints counts only).
- **`android/scripts/check_private_logs.py`** `scan`, `logcat`, `manifest`, `selftest` (PV3); board
  note `s-private-logs.md`.
- **`:feature:search`**: `SearchFeature` (`SearchController.kt`), `SavedPlacesFeature`
  (`SavedController.kt`), the six composables (`SearchViews.kt`), sprite icons cut from the style's
  sheets (`SpriteIcons.kt`, `poi_icons.json` copied into `:core:search`'s rules), the keyboard flag of
  PV8, accessibility and layout of SP5 and SP6; the `androidTest` assets line in the build file.

### What stage 2 changed after looking at everything

Stage 2 re-ran stage 1's tests once, read the controllers and composables against the contract and
looked at every stage-1 screenshot (31 day/night pairs); the device run and G's board note found more.
Nine things were found and fixed:

1. **Night buttons hard to read.** White text on the night accent (#5AA5FF) is about 2.5:1. Filled
   buttons now use dark text (#0B1A2E) by night; by day white on #2A74D8 stays (about 4.6:1).
2. **An off button looked on by night.** A disabled filled button was the accent at 40 % with white
   text, which by night still read as a live blue button ("Set work" before a tap, Save with a
   problem). Off is now a grey fill with grey text, by day and night.
3. **"You already have a place called mom's."** The line named the typed text. It now names the
   saved place that holds the name ("You already have a place called Mom's.").
4. **A refused pick in SAVE mode said nothing.** "Change location" onto a place already saved under
   another name set `SearchUi.message`, but the screen drew the message only in NO_RESULTS and
   NO_SEARCH_DATA, so the tap seemed to do nothing. The screen now shows it as a polite problem line
   above the rows ("This place is already saved as Quokka Shop."). New JVM test
   `SearchFeatureTest.aRefusedChangeOfLocationSaysWhy`, new screenshot `search-move-refused`.
5. **The "Show on the map" switch was two screen-reader stops.** The labelled row and the Switch
   inside it were separate nodes, the Switch without a label (SP5 item 3). The row is now one
   toggleable with role Switch, labelled by its text, and the Switch only draws.
6. **A read-only list (SP53) still showed the switch,** which could do nothing. A writable full list
   always has its Home and Work rows (or "Set home" / "Set work"), so a full list with no rows is a
   read-only store: it now shows only its notice. New screenshot `saved-list-newer`.
7. **Small layout fixes:** the pill is fully rounded at any height; in stacked rows (font scale above
   1.3, SP6) the icon stays beside the title instead of floating in the middle.

8. **G's network-rule scan had one finding in S's `:core:search-bench`** (board note
   `g-network-rule.md`): `BenchMain` started PowerShell to read its own working set. It now reads its
   memory in-process (`/proc/self/status` VmHWM/VmRSS where present, else the JVM's committed virtual
   memory); the JSON keys changed accordingly (nothing reads them). `check_network_rule.py scan` is
   at 0 findings on the whole tree (board note `s-network-scan.md`). The bench compiles; it was not run.
9. **Accessibility found on the device:** a description set on a clickable (the pill, rows, "Edit",
   chips, the "x" buttons, the switch row) left the drawn words as a second child, so a screen reader
   could read a label twice; the drawn words are now cleared from semantics, and the search field's
   drawn hint too (its description says it). The dialog's tap-outside scrim was a clickable box with
   no name; it is now a plain tap gesture.

Stage 2 also added the device checks stage 1 had left to reading: SP5's accessibility tree and tap
targets, the device timing of 6.17, and a tap on each text field for PV8 (below). The device run now
uses P's format-3 PA files (`data/r3-packs/us-pa/search.sqlite` and `addresses.sqlite`, both
fingerprinted) instead of the round-2 address file.

## Review and fixes

Two reviews followed stage 2: one re-ran the proof ("reproduce", verdict sound, two minor points),
one read the code against the contract ("contract", verdict sound with fixes: one major, four
minor). The engineer who finished the track checked each finding against the code before acting on
it. All seven were right; none was refuted. Each code fix (C2-C5) has a JVM test that fails on
the old behaviour, shown by putting that behaviour back by hand and running the tests (the
mutation run, below).

| # | Review | Finding | Checked | What was done | Test that catches it |
|---|---|---|---|---|---|
| C1 | contract, **major** | The report handed PV8's visual check (Gboard's incognito mark) to "I's phone check (10.3 step 17 and PV7)", but step 17 holds only `uiautomator dump` and TalkBack; nothing else in the documents names Gboard or the mark, so the check had no owner | Right: a grep of `docs/architecture*.md` finds "Gboard"/"incognito" only in PV8, 5.1 S (and 6.2 S) and 10.4. This PC has one system image, `system-images;android-36;default;x86_64` (AOSP, keyboard LatinIME), and `tools/round/emulator.sh` builds every AVD from it; Gboard comes only through Google Play | The false hand-off sentence is corrected here ("Not done") and in the board note `s-search-feature.md`. Request `data/architecture/requests/s.md`: a step 18 in 10.3 (phone, test set: Gboard's mark on the three fields), PV8's "Checked by" and 10.4 changed to the flags on the emulator, 5.1 S / 6.2 S and section 8 to match. **The lead applied it as asked** (board note `lead-s-request-applied.md`; `check_contracts.py` all the same afterwards): S's PV8 check is now the editor flags, which the device test asserts; Gboard's mark is I's step 18 on the phone | none possible here (no Gboard); the flags stay asserted on the device |
| C2 | contract, minor | NO_SEARCH_DATA said "Search needs a state on this phone. Add a state ..." also when states are installed but none can be searched (P's `searchProblem`, or refused by the hub at open), so the driver would add the state they already have; `SearchHub.problems` was `@Synchronized`, so a main-thread reader would wait for a running query | Right: `SearchController.refresh` used one text for every NO_SEARCH_DATA; the getters were synchronized on the hub that a query holds | The controller remembers whether the set it learned NO_SEARCH_DATA on had packs; then the line is "Search can't use the installed maps. Update them, or add a state, on the States screen." (S's words; the button stays "Add a state", 3.8 S7, and leads to the States screen where updates are). Nothing installed keeps the old line. `problems` and `openGeneration` are now volatile snapshots, never blocking. Not done: exposing the hub's reason to `:app` (it would need a new public member in the contract file `SearchUi.kt`, which only the lead may change; the States screen already shows P's `searchProblem`) | `SearchFeatureTest.installedButNotSearchableSaysSo`; `MultiPackTest.problemsAreReadWhileACallRuns` (reads during a blocked open, 2 s limit); device: `search-not-searchable` (the real hub refusing P's PA files when it expects another fingerprint) and `search-no-data` |
| C3 | contract, minor | An `IOException` while reading a valid `saved-places.json` was treated as a broken structure: the file moved aside, a new empty list started | Right: `SavedStore.init` mapped the read exception to `Decoded.Broken` | A failed read (`IOException` or `SecurityException`) now leaves the file untouched; the store is read-only with the BROKEN notice in this process only, the same as SP52's rename-failure branch. Only a text that was read and fails the structure check is moved aside. The next start reads the file again | `SavedPlacesTest.anUnreadableFileIsLeftAloneAndTheStoreIsReadOnly` (a folder in the file's place: no copy, no new file, every change refused, the list back once it is readable) |
| C4 | contract, minor | `recents.json` was written on a private daemon thread "beamline-store-writer", which `docs/architecture.md` 1.2 does not list (JSON stores run on `Dispatchers.IO`) | Right | `RecentPlaces` passes `Dispatchers.IO.asExecutor()` (`LatestTextWriter.io`; the contract file's imports are untouched). The writer's waits used a marker task, which on a pool can run before the writes, so `awaitIdle` and `awaitStoreWrites()` now count pending writes (`PendingCount`) and are right on any executor | `RecentPlacesTest.writesRunOnNoThreadOutsideTheThreadTable` (no "beamline-..." thread beyond 1.2's after writes); `AtomicWriteTest` (300 writes on an 8-thread pool and on `Dispatchers.IO`: the newest text is on disk when the waits return) |
| C5 | contract, minor | When the APK has no sprite sheet, `getOrPut` with a null value re-read and re-parsed the sheet for every icon, on the main thread | Right: Kotlin's `getOrPut` treats a stored null as absent | `SpriteCache` remembers a missing sheet and a missing icon (`containsKey`), so each is looked for once per process | `SpriteCacheTest` (no sheet: one read per theme; JSON without its PNG: one read of each for any number of icons) |
| R1 | reproduce, minor | The warm-up timing (6.17) was one run; the reviewer's re-run was 3-5 times slower, so 57.7 ms must not stand as the figure | Right: one sample on a shared PC is not a measurement | Measured again in two ways (below): 6 rounds of a hub with and one without the warm-up in one process, and 6 + 6 runs each in a new process after dropping the emulator's page cache. The single figures of stage 2 are withdrawn | the timing is written by the device test (`timing.txt`, `timing-cold.txt`) |
| R2 | reproduce, minor | "4319 peach st" near Erie lists the neighbourhood "Peach Street Terrace" above the exact address "4319 Peach Street" | Right, and inherited: `packbuilder/search/cli.py "4319 peach st" --db data/r3-packs/us-pa/search.sqlite --addresses data/r3-packs/us-pa/addresses.sqlite --near 42.0971,-80.07578` gives the same order (Terrace `name/prefix 1.828`, the address `name/exact 1.763`) | Recorded as a known ranking trait (here, in `android/core/search/README.md`, open questions). `ranking.json` is frozen for this round (P hashed it into the packs after `s-ranking.md`), so no change now; an address-above-place case belongs in the next round's tuning set | (none this round) |

**Mutation run** (the old behaviour of C2-C5 put back by hand in the five main files, the new tests
run, then the fixed files copied back and checked equal by MD5; log
`data/r3-s/mutation/mutation-run.log`): `SavedPlacesTest.anUnreadableFileIsLeftAloneAndTheStoreIsReadOnly`,
`RecentPlacesTest.writesRunOnNoThreadOutsideTheThreadTable`, `MultiPackTest.problemsAreReadWhileACallRuns`,
`SearchFeatureTest.installedButNotSearchableSaysSo` and `SpriteCacheTest.aMissingSheetIsLookedForOncePerTheme`
failed; `AtomicWriteTest` passed (it guards the new waits, not the thread). The second
`SpriteCacheTest` case passed under the mutation because it asked one icon name only; it now asks
three names, which a sheet-level miss cannot hide.

**Warm-up at open (6.17), measured again** on `beamline_s` (API 36, x86_64, P's format-3 PA search
and address files; shared PC, so indicative only). In one process (`timing.txt`, 6 rounds, a hub
with and one without the warm-up in alternating order, OS page cache warm from the push):

| | open and check (ms) | "sheetz", first query (ms) | open + first query (ms) |
|---|---|---|---|
| with warm-up: min / median / max | 29.9 / 39.7 / 135.5 | 13.2 / 19.4 / 41.4 | 43.0 / 59.1 / 176.8 |
| without: min / median / max | 19.1 / 29.8 / 53.0 | 15.8 / 20.3 / 34.5 | 34.8 / 56.6 / 72.6 |

Each run in a new process after `sync; echo 3 > /proc/sys/vm/drop_caches` (root on the emulator's
userdebug image, never on a phone; `data/r3-s/device-cold-timing.sh`, results
`data/r3-s/timing-cold.txt`), 6 runs each, alternating:

| cold page cache | making the hub (fingerprint of 1,482 spellings) | open and check | "sheetz" | "4319 peach st" | open + first query |
|---|---|---|---|---|---|
| with warm-up: min / median / max | 243.6 / 251.4 / 262.7 | 321.4 / 364.6 / 422.3 | 138.1 / 171.4 / 219.4 | 170.9 / 202.2 / 211.7 | 463.2 / 537.5 / 641.7 |
| without: min / median / max | 242.8 / 248.7 / 270.1 | 110.1 / 114.9 / 119.6 | 160.3 / 185.3 / 193.7 | 182.3 / 213.4 / 225.3 | 274.5 / 299.4 / 311.3 |

What this says, for the emulator only: the warm-up as built costs about 250 ms at open with a cold
cache and saves about 14 ms on the first query (medians), so it does not pay for itself here, even
if the driver's typing hides the open. The emulator's disk is a file on the PC (cached by Windows),
so a phone's flash after a reboot may differ; 6.17 says the figure is measured on the phone at
integration, and the warm-up stays as the contract decides. Also seen: making the hub, which
computes the street-key fingerprint over 1,482 spellings, takes about 250 ms in every new process,
on the search thread when the screen first opens. Both are open questions below. (The stage-2
figures "hub 445.6 ms, prepare 227.9 ms, sheetz 57.7 ms" were single runs and are withdrawn.)

**Other items left partly done, looked at again:**

- The merge fixtures of 5.1 S (two PA copies with other `ranking_sha256`, a hand-made Conneaut pack
  with a synthetic fingerprint) are still not built. 5.1 S asks for them "until P's first-wave files
  exist"; P's files existed before the merge tests were written, so every merge rule runs on them
  and on synthetic rows. A useful fixture would make those tests independent of `data/r3-packs/`,
  but a hand-made pack needs the builder's whole index step (contentless FTS5, R*Tree, vocabulary,
  spelling tables) over a cut extract: not a small job, and not done tonight. The ranking-hash
  fallback reads `PackInfo.searchRankingSha256`, not the file's meta, so a file copy with another
  hash would test nothing the existing test does not.
- `RulesAndVersionsTest.missingOrForeignVersionsAreRefused` with no database configured still
  cannot be run through `tools/round/gradle.sh` (it sources `env.sh`, which sets
  `BEAMLINE_SEARCH_DB` whenever the file exists, and the build reads the environment first). The
  test makes its own tiny databases and names no `TestEnv` call, so the configuration cannot reach it.
- The search bench after stage 2's `BenchMain` change was run: `BUILD SUCCESSFUL`, 49.7 s, the
  memory section filled in-process (details under "Not done or not verified").

## How it is proved (commands run, and what they showed)

Final runs by the engineer who finished the track, 2026-11-16 04:00-04:25 (stage 2's runs at
03:25-03:30 gave the same, with the smaller test counts of before the review fixes), from the
repository root in Git Bash.
`<abs>` is the repository root as Windows sees it (`$(pwd -W)`).

| What | Command | Result |
|---|---|---|
| Strict `:core:search` (conformance after the cap moved), `:feature:search` JVM, the test APK, the modules that compile `:core:search` | `export BEAMLINE_TESTS_STRICT=1 BEAMLINE_CONFORMANCE_DIR=<abs>/data/r3-s/search-conformance` then `sh tools/round/gradle.sh s :core:search:test :feature:search:testDebugUnitTest --rerun :feature:search:assembleDebugAndroidTest :core:search-bench:compileKotlin :demo:search:compileDebugKotlin` | BUILD SUCCESSFUL. `:core:search` 70 tests, 0 failures, 1 skipped (`SqlTimingProbe`, a measurement probe); conformance 1,076 queries, 1,076 top-10 ids equal, 0 field, label, flag or verdict differences, "cap" 7/7. `:feature:search` 37 tests, 0 failures (`SearchFeatureTest` 21, `SavedPlacesFeatureTest` 13, `SavedLeakTest` 1, `SpriteCacheTest` 2). Log `data/r3-s/stage3-final-test.log` (stage 2: 66 and 34, `data/r3-s/stage2-final-test.log`) |
| Independent check of the reference (stage 1) | `python -I android/core/search/conformance/compare.py --dir <abs>/data/r3-s/search-conformance` | 1,076 queries, every field equal |
| Python reader | `.venv/search/Scripts/python -m pytest -p no:cacheprovider packbuilder/search/tests/test_saved_places.py` | 104 passed |
| Privacy scan on S's modules | `python android/scripts/check_private_logs.py scan --module core/search --module feature/search` | 32 files, 0 marked lines, 0 breaches |
| G's network rule (after the bench change) | `python android/scripts/check_network_rule.py scan` and `privacy`; `sh tools/round/gradle.sh s :core:search-bench:compileKotlin` | `scan: 0 finding(s)` (whole tree); 0 private IPv4 literals; bench compiles (not run) |
| 10.2: each check fails once on a scratch input and passes on clean code | `python android/scripts/check_private_logs.py selftest` | "all checks behave" (22 cases: the 10 scan breaches of 10.2 and more caught, 2 clean trees pass, manifest and rules cases, `logcat` passes on "142.136 ms" and fails on "at 42.1365") |
| Whole shipped tree (for I) | `python android/scripts/check_private_logs.py scan` | 163 files in 22 modules, 18 breaches, all in `:app` (the lines PV2 names for I's rewrite), 1 marked line (G's, an exception class name) |
| Rule copies | `python android/scripts/copy_search_rules.py --check` | "all copies match packbuilder/" (rows include `search_app.json`, `fingerprint_spellings.json`, `poi_icons.json`) |
| Contract | `python tools/check_contracts.py` | 39 contract files, 20 excerpts, 5 data files: all the same; 39 frozen files unchanged |
| Test APK has no network permission (docs/architecture.md 4) | the packaged androidTest manifest under `data/gradle/s/build/feature/search/intermediates/packaged_manifests/` | only `REORDER_TASKS` and its own `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` |
| Device: screenshots, accessibility, keyboard flags, timing | `sh tools/round/emulator.sh s start`, `sh data/r3-s/device-screens.sh push` (installs the test APK with `tools/round/adb.sh s install -r`, pushes P's format-3 `data/r3-packs/us-pa/search.sqlite` and `addresses.sqlite` into the test APK's files folder, runs `SearchScreensTest` with `am instrument`, pulls `screens/`), `sh tools/round/emulator.sh s stop` | `OK (6 tests)` in 51.7 s on `beamline_s` (API 36, x86_64; `coldOpenTiming` skipped by its assumption in a plain run): 64 screenshots (32 day/night pairs, the two NO_SEARCH_DATA screens new), 3 keyboard captures with their `dumpsys`, 14 accessibility dumps, `timing.txt` (log `data/r3-s/stage3-device-2.log`; stage 2: `OK (5 tests)`). Then `sh data/r3-s/device-cold-timing.sh 6`: 12 runs of `coldOpenTiming`, each `OK (1 test)`, `data/r3-s/timing-cold.txt`. Emulator stopped, slot given back |

Key tests by contract item: S3/S4 and the fingerprint in `MultiPackTest` ("conneaut" near Erie
returns Ohio's Conneaut and Pennsylvania rows merged by score; the South State Line Road of both packs,
99 m apart, is one row; another ranking hash falls back to pack-first; a mismatched fingerprint
refuses that pack; old handles are closed on a new generation; P's format-3 pack folders with house
numbers); recents in `RecentPlacesTest`; the companion's 10.1 items 1-17 in `saved.SavedPlacesTest`
and `saved.SavedPlacesLeakTest`; items 1-8 of `:feature:search` in `SearchFeatureTest`,
`SavedPlacesFeatureTest` and `SavedLeakTest`. The name key is the same in Kotlin and Python on a
shared list of 63 names (`src/test/resources/saved/saved-name-keys.json`) and the SP20 answers on a
shared case list (`saved-matching.json`); the v1 fixture re-encodes byte-identically.

**SP5 on the device** (`SearchScreensTest.accessibilityLabelsStatesAndTargets`, dumps in
`data/r3-s/screens/a11y-*.txt`): the test reads the window's accessibility tree through
`UiAutomation` (what TalkBack is given) and computes for every tap target what a screen reader
reads. Results: the pill reads "Search places or addresses" once; the search START reads, in
order, "Back", "Search places or addresses", "Home, Erie Maritime Museum, 100 East Front Street,
Erie, 2.8 mi", "Set work", "Saved places, 2 places", "Gas", "Rest stops", "Food", "Coffee", "Clear",
"Erie Art Museum, 20 East 5th Street, Erie, 2.4 mi", "Remove from recents"; the saved places screen
has one switch stop "Show on the map" (checkable, on), "Edit Home" and "Edit Quokka Shop" as their
own stops; the save dialog's chips are radio buttons ("Other" checked with no click of its own,
"Home" and "Work" not checked); the pick bar's "Zoom in to choose the exact spot." is a polite live
region; the Edit menu reads "Rename", "Change location", "Make home", "Make work", "Remove",
"Cancel"; every tap target has a name, and every enabled one on these seven screens is at least
48 by 48 dp. TalkBack itself is not on the emulator image, so it was not run.

**PV8 on the device** (`SearchScreensTest.keyboardDoesNotLearnOnTheThreeFields`): for each of the
three fields the test taps the field and reads the editor the keyboard is bound to from
`dumpsys input_method`: search `imeOptions=0x3000003`, save dialog name and rename
`imeOptions=0x3000006`, all three `inputType=0x80001`. `0x1000000` is
`IME_FLAG_NO_PERSONALIZED_LEARNING` (set on all three); `0x80000` is `TYPE_TEXT_FLAG_NO_SUGGESTIONS`
and `TYPE_TEXT_FLAG_AUTO_CORRECT` (`0x8000`) is clear. The bound keyboard is the AOSP
`com.android.inputmethod.latin/.LatinIME`, which reports `mInputShown=true`, but no keyboard is drawn
in the screen captures (the emulator has a hardware keyboard).

## Screenshots, looked at

All in `data/r3-s/screens/` (not in git; the finisher's final run, whose 30 earlier pairs and 3
keyboard captures are pixel-equal to stage 2's within 30 levels, checked with PIL against
`data/r3-s/pairs/`; the 2 new pairs, in `data/r3-s/pairs-stage3/`, were looked at by the finisher),
`<name>-day.png` and `<name>-night.png`, 945 x 1680 px
(360 x 640 dp), made with P's synced sprite sheets (`p-sprites.md` was on the board before any of
them, so no stand-in icons were needed). Stage 2 looked at every one of the 30 pairs of the final
run, side by side (`.venv/art/Scripts/python data/r3-s/pairs.py`, written to `data/r3-s/pairs/`),
and at the three keyboard captures. What each shows (day and night alike unless said):

| Screenshot | What it shows | Contract item |
|---|---|---|
| `pill` | the pill on a map-coloured background: magnifier, "Search places or addresses", fully rounded | S8, SP2 |
| `search-start-empty` | "Set home", "Set work", "Saved places" / "None yet", the shortcuts (Gas, Rest stops, Food, Coffee...), "Recent" with "Clear" and two recents with distances and "x" | 5.1 S "empty screen with shortcuts and recents"; 10.4 empty slots |
| `search-start-set` | "Home" with "Erie Maritime Museum, 100 East Front Street, Erie" and 2.8 mi; "Work" with "42.08418, -80.17321"; "Saved places" / "3 places"; shortcuts; recents | 10.4 set slots |
| `search-start-broken` | the BROKEN notice with "OK" above "Set home" | 10.4 BROKEN notice, SP52 |
| `search-save-start` | header "Set work", "Use my position", "Choose on the map", recents, no shortcuts | 10.4 SAVE START, SP23 |
| `search-addstop-driving` | ADD_STOP while driving: Home, Work, "Saved places" at the top, no cursor in the field | SP4 |
| `search-sheetz` | 9 visible "Sheetz" rows near Erie (0.5 mi to 36 mi), fuel and convenience icons | 5.1 S "sheetz" |
| `search-rest-stop` | rest areas (18-62 mi), a welcome center, rest-area icons | 5.1 S "rest stop" |
| `search-4319-peach-st` | "4319 Peach Street" with "Exact address", then Peach Street rows with "Street only"; the first row is the neighbourhood "Peach Street Terrace", ranked above the address exactly as `query.py` ranks it (review R2) | 5.1 S "4319 peach st near Erie" |
| `search-home` | the saved "Home" row (house icon) first, a divider, then the engine's Homecrest, Homeland... | 10.4 typed "home", SP21 |
| `search-no-data` (finisher) | nothing installed: Home and Work with "Outside the installed maps", "Saved places" / "4 places", "Search needs a state on this phone. Add a state to search its places and addresses.", "Add a state", the recents | 3.8 S7, NO_SEARCH_DATA |
| `search-not-searchable` (finisher) | P's PA files installed, the real hub refusing them (it expects another fingerprint): "Search can't use the installed maps. Update them, or add a state, on the States screen.", "Add a state", the saved rows and recents | review C2 |
| `search-move-refused` | "New location for Mom's", "This place is already saved as Quokka Shop." in the problem colour, the mall row noted "Saved" | stage 2 fix 4, SP55 |
| `sheet-routable` | Sheetz, 3814 Liberty Street, 0.5 mi, "Directions" and "Save" | 5.1 S routable sheet, SP19 |
| `sheet-outside` | Point State Park, Pittsburgh, "Outside the installed maps", "Add a state" and "Save" (the test's routing tiles cover only Erie) | 5.1 S sheet outside the maps |
| `sheet-saved-home-trip` | the sheet of the saved Home with a trip: house icon, "Add stop", "Directions", "Edit" | SP19, S7 |
| `sheet-saved-as` | the museum found by search: "2.8 mi · Saved as Home", "Directions", "Edit" | SP19 |
| `saved-list-full` | switch "Show on the map" on; Home with Edit; "Set work"; Mom's; Point park with "Outside the installed maps"; Quokka Shop; "Add a place" | 10.4 full list |
| `saved-list-pick-only` | the same places, no switch, no Edit, no "Set work", no "Add a place" | 10.4 pick-only, SP4 |
| `saved-list-newer` | only the NEWER notice with "OK" (no switch, no rows) | SP53, stage 2 fix 6 |
| `saved-list-long-name-1.0` | "Quokka Shop on the Pe..." on one line, description on two, 2.3 mi and Edit at the end | 10.4 40 characters at 1.0, SP6 |
| `saved-list-long-name-1.5` | stacked rows: title with Edit at its end, description in two lines then "...", the distance on its own line | 10.4 40 characters at 1.5, SP6 |
| `dialog-save-other` | "Save place", Erie Zoo and its address, chips Home / Work / Other (Other chosen), the name "Erie Zoo", Cancel / Save | 10.4 save dialog Other |
| `dialog-save-home-replace` | Home chosen, no name field, "This replaces your home: Erie Maritime Museum, 100 East Front Street, Erie.", "Replace" | 10.4 Home with replace line, SP28 |
| `dialog-menu-home` | "Home": "Change location", "Remove home", Cancel | 10.4 menu for Home |
| `dialog-menu-other` | "Quokka Shop": Rename, Change location, Make home, Make work, Remove | 10.4 menu for an other place |
| `dialog-confirm-make-home` | "Make Quokka Shop your home?", "Your current home (Erie Maritime Museum, 100 East Front Street, Erie) will be removed. It will be called Home.", "Make home" | 10.4 a confirm |
| `dialog-rename-taken` | "Rename", the name "mom's", "You already have a place called Mom's.", Save off (grey) | 2.3, stage 2 fixes 2 and 3 |
| `pick-before` | "Tap the map at your work" at the top, "No point chosen yet", Cancel, "Set work" off | 10.4 pick before a tap |
| `pick-z17` | "Point on the map", "42.08418, -80.17321", "Set work" on | 10.4 after a tap at zoom 17 |
| `pick-z14` | the same point, "Zoom in to choose the exact spot.", "Set work" off | 10.4 after a tap at zoom 14, SP24 |
| `pick-poi-z12` | a POI tap at zoom 12: "Erie Maritime Museum", its coordinates, "Set work" on | SP24 |
| `keyboard-search`, `keyboard-save-name`, `keyboard-rename` (full screen, day only) | each field focused with its cursor; no keyboard drawn (see PV8 above) | PV8 (the flags, not the incognito mark) |

Before stage 2's fixes the night screenshots showed the defects 1 and 2 above (white on bright blue;
"Set work" before a tap looking enabled by night); the final run shows them fixed.

## Measurements (indicative only: shared PC, shared emulator)

Decision 6.17 (the warm-up at open): the repeated measurements of "Review and fixes" (R1) replace
stage 2's single runs, which are withdrawn ("hub 445.6 ms, prepare 227.9 ms, sheetz 57.7 ms,
4319 peach st 84.8 ms" in its final run, "394.8 / 238.3 / 60.9 / 75.2 ms" in the run before; the
reproduce review's re-run of the same test gave 290.3 / 395.2 / 218.9 / 312.0 ms). In short, on
the emulator with a cold page cache (medians of 6): making the hub 249-251 ms; open and check 114.9 ms
without the warm-up, 364.6 ms with it; the first query "sheetz" 185.3 ms without, 171.4 ms with it.
A single figure varies several-fold on this shared PC, so none of these is the figure for 6.17: that
is I's phone run at integration, as 6.17 says.

JVM on this PC, PA search file only (`MultiPackTest.capAndWarmUp`, prints one line per strict run,
OS file cache warm): open and warm-up 105, 110 and 150 ms, first query after it 64, 58 and 35 ms in
three runs (stage 2's final, the finisher's first and final). The hub is made and warmed on the
search thread when the search screen opens (`SearchFeature.open` → `prepare`), not at app start.
Other times: regenerating the conformance reference took 3 min (stage 1); the device class took
62.6 s for 5 tests (stage 2) and 51.7 s for 6 (the finisher's run, one of them skipped by design).

## Definition of done, item by item

From `docs/architecture.md` 5.1 S "Done when" and `docs/architecture-saved-places.md` 6.2 S:

| # | Item | State | Proof |
|---|---|---|---|
| 1 | Strict `:core:search:test` passes, conformance equal after the cap moved | done | final strict run (finisher, `data/r3-s/stage3-final-test.log`): 70 tests, 0 failures, 1 skipped probe; 1,076/1,076, 0 differences, cap 7/7 |
| 2 | Merge tests on fixtures, and on P's PA and OH files (Erie gives PA and Conneaut, Ohio; duplicates across the line merged; other ranking hashes fall back to pack-first) | partly: done on P's files, fixtures not built | `MultiPackTest` 12 tests pass in the strict run (the pure merge tests on synthetic rows, then P's PA and OH files); the fixture packs of 5.1 S were not made because P's files existed first (5.1 S: "until P's first-wave files exist"; looked at again in "Review and fixes") |
| 3 | Fingerprint mismatch refused | done | `MultiPackTest.fingerprintMismatchIsRefused`; on the device P's format-3 files pass the check (`hub.problems` empty, asserted) |
| 4 | Recents: order, de-dup, max, broken file | done | `RecentPlacesTest` (6 tests: the 5, and writes on `Dispatchers.IO` with no thread of their own, review C4); `AtomicWriteTest` |
| 5 | The controller: debounce, stale answers dropped, shortcuts, NO_SEARCH_DATA with OpenStates, ADD_STOP and SET_START, OPEN_STATES for an outside place | done | `SearchFeatureTest` 21 tests (`onlyTheNewestPendingTextIsSearched`, `anAnswerForAnOlderTextIsDropped`, `shortcutsSearchTheirTermAtOnce`, `noResultsAndNoSearchData`, `installedButNotSearchableSaysSo`, `picksInEachModeAndRecents`, `placeSheetActions`, ...) |
| 6 | Emulator screenshots by day and night, looked at: empty screen with shortcuts and recents, "sheetz", "rest stop", "4319 peach st" near Erie, a routable sheet and one outside the maps | done | `search-start-empty`, `search-sheetz`, `search-rest-stop`, `search-4319-peach-st`, `sheet-routable`, `sheet-outside`, each pair looked at by stage 2 (table above); the finisher's final run is pixel-equal to them (PIL, 30 levels) |
| 7 | `RulesAndVersionsTest.missingOrForeignVersionsAreRefused` fixed | done (by construction) | the test now makes its own tiny databases and calls no `TestEnv.require*`; passes in every run. Not run with no database configured: `tools/round/env.sh` always sets it (see "Not verified"; both reviews accepted this) |
| 8 | The JVM tests of the companion's 10.1, leak tests included | done | `:core:search` `saved.SavedPlacesTest` (items 1-16, and an unreadable file, review C3), `saved.SavedPlacesLeakTest` (17); `:feature:search` items 1-8 in `SearchFeatureTest`, `SavedPlacesFeatureTest`, `SavedLeakTest`; all pass |
| 9 | The checks of its 10.2 | done | `scan` 0 breaches on S's modules; `selftest` shows each 10.2 case failing once and clean code passing; `manifest` checked by `selftest` only (the merged `:app` manifest is I's, 5.4 item 14) |
| 10 | `copy_search_rules.py --check` covers `search_app.json` | done | "all copies match packbuilder/"; `RulesAndVersionsTest.ruleCopiesMatchPackbuilder` too |
| 11 | `python -m pytest packbuilder/search/tests/test_saved_places.py` passes | done | 104 passed (`.venv/search`) |
| 12 | `check_private_logs.py scan` passes on S's modules | done | 32 files, 0 breaches |
| 13 | Emulator screenshots of the companion's 10.4, day and night, looked at | done | every 10.4 S case is in the table above and was looked at; since the lead applied request `s.md`, 10.4 asks S for "the three text fields focused, with the editor flags of PV8": `keyboard-search`, `keyboard-save-name`, `keyboard-rename` and their `dumpsys` (the incognito mark itself is 10.3 step 18, I's, on the phone) |
| 14 | The keyboard check of PV8 | done (as amended) | 5.1 S now reads "the keyboard check of PV8 done (the editor flags on the emulator; Gboard's mark is I's, 10.3 step 18)": the flags are asserted on the device for all three fields (`IME_FLAG_NO_PERSONALIZED_LEARNING` set, no suggestions, no autocorrect, from the bound editor; again in the finisher's run). Gboard's mark was not seen (no Gboard on this PC's only image); it had no owner until review C1 and the request `data/architecture/requests/s.md`, which the lead applied |
| 15 | Delivers: `SearchFeature`, `SearchBar`, `SearchScreen`, `PlaceSheet` with category icons by `poi_icons.json`; the androidTest assets line; `SavedPlacesFeature` and every composable listed; SP5, SP6 | done | the code, the JVM tests, the screenshots; SP5 on the device as above (TalkBack itself not run) |
| 16 | Rule 11: contract check, own tests, emulator stopped, report, READMEs, PROGRESS | done | `check_contracts.py` all the same, 39 frozen files unchanged; `emulator.sh s status`: not running, no slot of S; `android/core/search/README.md`, `android/feature/search/README.md`; `data/r3-s/PROGRESS.md` (all as of the finisher's last runs) |

## Choices made where the contract left a gap

- The cap counts Unicode code points (Python's slice), cut before every other step.
- No position: S3 asks every pack (by pack id). Duplicates (S4): same kind, same folded title (exact
  title when the fold is empty), within `dedupe_m`; the higher score is kept in the place of the first seen.
- A search part without `addr_key_fingerprint` is refused; an address file without `key_fingerprint`
  is accepted (the search part's key and the table version check pin it). P's format-3 files have both.
- Texts the contract does not give: NO_RESULTS "No places found"; NO_SEARCH_DATA "Search needs a state
  on this phone. Add a state to search its places and addresses." (shown in START too when no pack can
  be searched; the shortcuts are hidden then, the saved rows stay); the INTERPOLATED note "Estimated
  address"; "Recent", "Clear", "Remove from recents", "Close"; "This place can't be changed now." for
  a place that disappeared under an open dialog or a read-only store. After the review (C2): with
  states installed but none searchable, NO_SEARCH_DATA's line is "Search can't use the installed
  maps. Update them, or add a state, on the States screen." (the button stays "Add a state", as
  3.8 S7 gives it; the States screen is where a state is updated).
- "You already have a place called {name}." names the saved place that has the name, not the typed
  text (stage 2; the contract does not say which).
- A refusal of a pick in SAVE mode is shown as a problem line at the top of the search screen
  (stage 2; the contract gives the texts, not where they show on that screen).
- A full saved places list with no rows (a read-only store) hides its switch (stage 2, SP53).
- Icons for rows without a POI category: place `townspot`, street `arrow`, address and other POIs
  `building`. Distances only with a GPS position, for every row (as SP10 says for saved rows).
- SEARCHING keeps the previous engine rows until the new answer; a failing search ends in NO_RESULTS.
- Close in SAVE mode entered from an empty slot goes back to GO_TO START. The save dialog for a point
  opens with an empty name and Save off, with no problem line until the user types. `openSave` of a
  SAVED place opens its Edit menu.
- Saved-place file reading: names must be non-empty strings, not trimmed (structure only, the same in
  Python); a JSON null subtitle or category is broken.
- Recents are written on `Dispatchers.IO` (`RecentPlaces` takes no executor; until the review C4 they
  were written on a daemon thread of their own); listed with source RECENT; a SAVED place is never added.
- A `saved-places.json` that exists but cannot be read (an I/O error, a folder in its place, no
  permission) is left untouched; the store is read-only with the BROKEN notice for that process only,
  as SP52 says for a file that cannot be moved aside (review C3). The contract names only a damaged
  structure, and moving a good file aside on a passing read error would hide the driver's list.
- `SearchHub.problems` is not passed to `:app` (review C2): that would need a new public member in the
  contract file `SearchUi.kt` (`SearchFeature`), which only the lead may change, and the States
  screen already shows P's `searchProblem`. It is now a
  snapshot any thread can read, ready if the contract later asks for it.
- Colours: the map's panel colours of `:app`'s round buttons (`MapScreen.kt`), night when the theme's
  background is dark; filled buttons by night carry dark text (stage 2).
- `pytest` 9.2.0 (MIT) installed into `.venv/search` for the Python test (stage 1).
- The merge fixtures of 5.1 S (two PA copies with other hashes, a hand-made Conneaut pack) were not
  built: P's real PA and OH files existed before that step (5.1 S asks for fixtures "until P's
  first-wave files exist"), so the merge tests run on them; the pure tests cover the rules on
  synthetic rows; the ranking-hash fallback reads `PackInfo.searchRankingSha256`, so a file copy
  with another hash in its meta would add nothing (looked at again by the finisher, kept).
- PV8 is proved from the editor the keyboard is bound to (`dumpsys input_method`), because the
  emulator image has no Gboard (below).

## Not done or not verified

- **PV8's visual check:** the emulator image has the AOSP keyboard (LatinIME), not Gboard, so there
  is no incognito mark to see, and with the emulated hardware keyboard no soft keyboard is drawn
  even though the keyboard reports it shown. Proved instead from the bound editor's flags on all
  three fields (above). **Correction (review C1):** stage 2 wrote here that the Gboard look was "for
  I's phone check (10.3 step 17 and PV7)"; that was wrong, step 17 holds only `uiautomator dump` and
  TalkBack, and no step of any document names the mark. Track S asked for a step 18 of 10.3 (on the
  phone, in the test set) and for PV8's "Checked by" to match (`data/architecture/requests/s.md`);
  the lead applied it (`lead-s-request-applied.md`), so the mark is now I's step 18 and S's check is
  the flags. The mark itself is still unseen until I's phone run. This PC has no other system image
  than the AOSP one (`system-images;android-36;default;x86_64`).
- **TalkBack** was not run (not on the image). What it is given was checked on the device from the
  accessibility tree (above); how it speaks it is I's step 17 on the phone.
- **`logcat` and `manifest`** of `check_private_logs.py` ran only in `selftest` here; their real
  inputs (the emulator journey's logs, the merged `:app` manifest) are I's.
- **An engine test through `SearchFeature` on the JVM** is not possible in `:feature:search` (an
  Android library's unit tests get the Android variant of sqlite-bundled); the engine through the
  controller is proved on the emulator ("sheetz", "rest stop", "4319 peach st", "home", the sheets).
- **`RulesAndVersionsTest` without a configured database** was not run: `tools/round/env.sh`
  always sets `BEAMLINE_SEARCH_DB` when the file exists and the build reads the environment first.
  The fixed test no longer reads the pack at all; its real-pack half looks the pack up outside
  `assertThrows`, so it skips (or fails in strict mode) for the right reason.
- **The merge fixtures of 5.1 S** (two PA copies with other hashes, a hand-made Conneaut pack) were
  not built (choice below).
- **The warm-up's benefit on a phone** is not known: on the emulator it was measured with and
  without, warm and cold (review R1), and did not pay for itself; the phone figure is I's (6.17).
- **`keyboard=?`** in the finisher's `keyboard-*.txt` records: the test's pattern for the bound
  keyboard (`mCurMethodId=`) found nothing in this run's `dumpsys`, whose client lines name
  `com.android.inputmethod.latin/.LatinIME` (in `keyboard-*-dumpsys.txt`). The flags, which the
  test asserts, are as before; only the record line lacks the keyboard's name.
- **The search bench**, unrun since stage 2 changed `BenchMain`, was run by the finisher:
  `BEAMLINE_BENCH_OUT=<abs>/data/r3-s/bench-jvm.json sh tools/round/gradle.sh s :core:search-bench:bench`
  -> BUILD SUCCESSFUL, written in 49.7 s; the memory section is filled in-process (Java heap after GC
  3.7 MB, process committed 209.2 MB; no `/proc` on Windows, so no RSS keys), all queries median
  8.68 ms cold and 6.46 ms warm (444 queries, JVM on this PC).
- **The screenshots are of the composables in a 360 x 640 dp box**, not of the app: the pill beside
  the Settings button, the pick bar with the my-location button, the place sheet over a real map and
  the search screen over the map are I's (docs/architecture-saved-places.md 10.3).
- **SP45's switch, SP16's map pick and SP25's hand-backs** are proved on the JVM and in the
  screenshots of the bars; the markers they change are M's and I's.

## Open questions

The saved-place icons are P's drafts (owner question 12.3); the markers-by-default question (12.4) is
M's and I's. For the reviewers: the texts marked "S's words" above (now including the line for
installed but unsearchable states), and the fingerprint policy (a search part without the key is
refused). New after the reviews:

- **PV8's Gboard mark** (settled): request `data/architecture/requests/s.md` was applied by the
  lead; the mark is step 18 of 10.3, in I's phone run (the owner's phone is the only device here
  with Gboard).
- **Decision 6.17, the warm-up at open** (I at integration, then the lead): on the emulator it cost
  about 250 ms at open with a cold cache and saved about 14 ms on the first query. If the phone
  shows the same, either warm the pages the first queries actually read, or drop the warm-up.
- **The fingerprint is computed in every new process** (about 250 ms on the emulator, on the search
  thread when search first opens). 2.11 asks the app's port to compute it; a cached value keyed by
  the bundled spelling file's hash would keep the check and skip the cost (a contract question for
  the lead, not changed here).
- **Ranking: an exact address below a place named like its street** ("4319 peach st" near Erie:
  "Peach Street Terrace" first). Inherited from `query.py`; a case for the next round's tuning set,
  since `ranking.json` is frozen for this round.
