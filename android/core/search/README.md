# :core:search (offline search on the device)

Plain Kotlin/JVM, no Android, no UI. The Kotlin port of `packbuilder/search/query.py` (search
engine) and `packbuilder/addresses/addrlib` (address normaliser, parser, house-number lookup).
Results and measurements: `docs/experiments/10-search-on-device.md`.

| Class | What it does |
|---|---|
| `SearchEngine` | free-text search over a pack's search database (`pa-search.sqlite`), optionally with the address file attached for house numbers: same answers as `query.py` |
| `SearchEngine.signals`, `SearchHub.signalsIn` (item 36) | the pack's traffic lights (table `signal`) in the 0.01 degree cells asked for; the navigation session reads them once for a guided route (`RouteLights.build`). A pack built before the table has none |
| `StreetLines` (item 31) | the lines of the streets (pack table `street_line`), read with the formulas of `packbuilder/search/lines.py`: a street's distance is to the nearest point of its line, two streets and a word between them ("state st and 12th") give the place where they meet, `SearchEngine.nearStreet` / `SearchHub.streetAt` name the street at a point (a dropped pin). A pack without the table answers as before |
| `address.AddressIndex` | typed-address lookup over a pack's address file (`pa-addresses-slim.sqlite`): parse, then exact point, interpolated position or street; refuses a file keyed with another normaliser version |
| `SearchService` | what a screen calls: the engine, plus the address lookup for typed house numbers the engine could not place (a new rule, not in the Python reference; see its KDoc) |
| `SearchEngine.poiDetails`, `PoiDetails`, `SearchHub.details` (batch B) | OSM's opening hours, phone and website of POI entries by id (rule S10), read apart from the search, so its answers and the conformance reference do not change |
| `SearchEngine.poisNamedNear` / `detailsNamedNear`, `SearchHub.detailsAt` (batch C2) | rule S10 for a place without a search id (a POI tapped on the map, a saved place, a recent): the POIs whose name has the same key within `details.match_within_m` (100 m) in the installed pack covering the point; their details when all that have any agree (a store and its fuel canopies), else nothing. `PoiDetailsPackTest` (the real PA pack) |
| `hours.OpeningHours` (batch B) | rule S10: an `opening_hours` text parsed with `ch.poole:OpeningHoursParser` (MIT) and evaluated over a subset that needs no guess (weekdays, times also past midnight, `24/7`, `off`, `;` and `,`; holiday-only rules left out and said); everything else refused; the status at a local time (open, closing soon from one hour before, closed) with the next change, and the week. `OpeningHoursTest` (8) and `PoiDetailsPackTest` (the real PA pack: Sheetz near Erie, 97.1 % of the places with hours readable) |
| `SearchResult`, `AddressInfo`, `SearchResponse` | what a result row needs: stable id, title, subtitle, category id and label, distance, coordinates, exact / interpolated / street |
| `db.SqlDb` | read-only SQLite through `androidx.sqlite:sqlite-bundled` (the same SQLite build on the JVM and on Android) |
| `SearchHub` (round 3) | the installed packs' search: one `SearchService` per pack per `InstalledSet` generation (old handles closed before the next call), the fingerprint check at open (`addr_key_fingerprint` against `AddrKeyFingerprint.app()`), the warm-up read (decision 6.17), rules S3-S5 through `MultiPackSearch`; `problems` (why a pack was refused) and `openGeneration` are snapshots any thread may read without waiting for a running query |
| `MultiPackSearch`, `PackResult`, `MultiSearchResponse` (round 3) | S3 (which packs: bounds grown by `merge.pack_margin_km`, else the nearest) and S4 (merge by score with equal `ranking_sha256`, else pack-first; duplicates within `merge.dedupe_m` keep the higher score; `merge.limit`) |
| `AddrKeyFingerprint` (round 3) | the street-key fingerprint of docs/architecture.md 2.11 over the bundled `fingerprint_spellings.json` with this port's `AddressNormalizer` |
| `RecentPlaces` (round 3, contract) | recents.json (`beamline-recents/1`), newest first, at most 30, same place = pack+search id or the same folded name within 50 m; atomic writes on `Dispatchers.IO` (docs/architecture.md 1.2), no thread of its own |
| `saved.SavedPlaces` (round 3, contract) | the user's saved places (`saved-places.json`, `beamline-saved-places/1`; docs/architecture-saved-places.md SP50-SP56): structure-only reading, the persisted BROKEN notice, read-only NEWER files, atomic writes on its writer, names (`savedNameKey`, grapheme count), same place, matching (SP20). A file that exists but cannot be read (an I/O error) is left untouched and the store is read-only with the BROKEN notice in that process only; only a text that was read and fails the structure check is moved aside |
| `awaitStoreWrites()` | blocks until every write the stores have submitted is done (tests that delete their folder, a clean shutdown); counts pending writes, so it is right on a pool such as `Dispatchers.IO` |
| `saved.SavedPlacesFile` (round 3) | the file's codec (also the export format of 3.6) |
| `foldText`, `SearchAppRules` (round 3) | the one search fold (the engine's names and the place-name rules); `search_app.json` typed (the `saved` part is `SavedRules`) |
| `:core:search-bench` (separate module) | `bench.SearchBench` latency benchmark (`./gradlew :core:search-bench:bench` on the JVM, the demo app on a device) and `bench.ConformanceDump` (device answers for `compare.py`); not shipped with the search module |

The rule files in `src/main/resources/io/github/spysyweeb/beamline/search/rules/` are copies of
`packbuilder/search/{abbreviations,ranking,search_app}.json`,
`packbuilder/addresses/addrlib/{us_street_norm,lookup_config,fingerprint_spellings}.json` and
`assets/poi_icons.json`, made by
`android/scripts/copy_search_rules.py`. Never edit them here: change the file in `packbuilder/`
and run the script (`RulesAndVersionsTest` fails when a copy drifts).

```kotlin
SearchService.open("…/pa-search.sqlite", "…/pa-addresses-slim.sqlite").use { s ->
    val r = s.search("4319 peach st", 42.1292, -80.0851)   // safe from several threads (serialised per connection)
    r.results.forEach { println("${it.title} ${it.distanceKm} km ${it.address?.accuracy}") }
}
```

Tests (`./gradlew :core:search:test`; during a parallel round `sh tools/round/gradle.sh <key>
:core:search:test`, which also sets the two databases) need `beamline.searchDb` and
`beamline.addressesDb` in `android/local.properties` (or `BEAMLINE_SEARCH_DB` and
`BEAMLINE_ADDRESSES_DB`); the conformance tests also need the reference answers
(`conformance/README.md`). Without them those tests are skipped; with `BEAMLINE_TESTS_STRICT=1`
(or `-Pbeamline.strict=true`) they fail instead, which is what CI and a final check should use.
`RulesAndVersionsTest.missingOrForeignVersionsAreRefused` needs no pack at all since round 3
(it makes tiny databases itself).

Thread safety: `SearchEngine`, `AddressIndex` and `SearchService` may be shared between threads;
every use of a connection runs under one lock in `db.SqlDb`. A running query cannot be interrupted
(the bundled SQLite omits the progress callback), so a screen should keep queries on one worker and
drop stale answers. The engine itself cuts the text to `ranking.json` `search.max_query_chars` (256)
code points, exactly as `query.py` does (rule S5; the conformance set has 7 "cap" queries); the
service has no cap of its own any more. `SearchHub` serialises its calls and is meant for the
`beamline-search` thread. Nothing in this module logs (rule PV2; `android/scripts/check_private_logs.py
scan --module core/search`).

Round 3 tests (no pack needed unless noted): `QueryCapTest`, `RecentPlacesTest`, `AtomicWriteTest`,
`saved.SavedPlacesTest` (docs/architecture-saved-places.md 10.1 items 1-16 and an unreadable file,
with the fixtures in `src/test/resources/saved/` that `packbuilder/search/tests/test_saved_places.py`
reads too), `saved.SavedPlacesLeakTest` (PV3 item 2), `MultiPackTest` (S3, S4, the fingerprint,
generations, reads of `problems` during a call; the real-file parts need track P's
`data/r3-packs/{pa,oh}-search.sqlite` and `data/r3-packs/us-{pa,oh}/`). The strict run of round 3
used a reference regenerated into `data/r3-s/search-conformance` (`BEAMLINE_CONFORMANCE_DIR`): 70
tests, 1 skipped (`SqlTimingProbe`, a measurement probe).

A known ranking trait (inherited from `query.py`, not a port difference): "4319 peach st" near Erie
lists the neighbourhood "Peach Street Terrace" (name/prefix 1.828) above the exact address "4319
Peach Street" (1.763). `ranking.json` was frozen for round 3 (track P hashes it into the packs), so
this is a case for the next round's tuning, not a change here.
