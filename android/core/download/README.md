# :core:download

Getting state packs, the part without Android (`docs/architecture-downloads.md` sections 2-6): the
catalog format and its parser, the address and network rules, `PackHttp` (the only code of the app
that opens network connections, rule N1), the catalog cache, `DownloadEngine` (the state machine of
6.4: resume, hashing, unpacking, free space, process death) and the hand-off to the installer.
Plain Kotlin/JVM. Owner during the feature round: track G. Report:
`docs/experiments/18-getting-packs.md`. The format the server speaks is `docs/catalog-format.md`.

## Files

The contract files (their text is frozen except the bodies, `tools/check_contracts.py`) delegate to
internal files, so the frozen text stays as quoted:

| Contract file | Work is in | What |
|---|---|---|
| `Catalog.kt` (`CatalogFormat.parse`) | `CatalogParser.kt` | `beamline-catalog/1` and the reader rules of 2.3 |
| `net/CatalogAddress.kt` | `net/AddressRules.kt` | the configured address, its scope, the path rule, `redirectAllowed` (3.1) |
| `net/PackHttp.kt` | itself (OkHttp may only appear in this file, N1) and `net/NetRules.kt` | one GET under N2/N3: its own resolver and address check, redirects followed by hand (at most 5), no cookies, no cache, `Content-Encoding` refused, `ClockProblem` from the cause chain |
| `DownloadRules.kt` | `RulesFile.kt` | `download_rules.json` (copied from `packbuilder/catalog/`; never edit the copy) |
| `CatalogSource.kt` | `CatalogStore.kt` | the list: cache in `<state dir>/catalog.json` + `catalog-meta.json`, 6-hour rule, `If-None-Match` |
| `DownloadEngine.kt` | `EngineCore.kt`, `JobRecord.kt` | the state machine; `job.json` (`beamline-download-job/1`) written atomically on every change of phase or reason |
| `PackHandoff.kt` (`VirtualTar`) | `TarStream.kt` | folder hand-off (P's move install) or the archive stand-in fed a ustar made on the fly |
| `Space.kt`, `Job.kt` | themselves | the free-space formulas of 2.6; the job's types |

`continuesByItself()` (public, `EngineCore.kt`) is the rule `GetPacks.onAppVisible` uses: PAUSED for
APP_ENDED, SYSTEM_STOPPED or SERVER_UNREACHABLE continues without a tap.

## Using it (what `:feature:states` does)

```kotlin
val address = CatalogAddress.parse(catalogUrl, allowCleartext)          // null: downloads off
val http = PackHttp(address, rules, socketFactory)                       // Android: TaggingSocketFactory
val source = CatalogSource(address, http, stateDir, rules, minVersion, maxVersion, network)
val engine = DownloadEngine(downloadsDir, http, source, handoffFor(installed), installed, network, freeBytes, rules, scope, Dispatchers.IO)
engine.recover()                      // once per process, after the installer's first scan
engine.start(pack, JobKind.INSTALL, includeOptional, allowMetered, firstRun)
engine.run()                          // in the foreground service, on Dispatchers.IO
```

Everything that matters after process death is on disk: `job.json` and the bytes (the file length is
the progress). The engine never fetches on its own (rule N4): only `run()` makes requests.

`start`, `pause`, `stopForSystem`, `resume`, `cancel` and `dismiss` are synchronous and do file work
(`job.json` written with fsync; `cancel` deletes the job folder): call them off the main thread.
`:feature:states` runs them one at a time on `Dispatchers.IO` (`GetPacksCore.actions`).

A switch to a newer build after a 404 (6.4) replaces the job in place: the next value of `job` has
the same `packId`, a higher `packVersion` and the same kind and choices, with no DONE, FAILED or
null in between (`EngineStateTest.aGoneFileSwitchesToTheNewerBuild` asserts this; `DownloadJob` has
no field for it, so the screen recognises the switch from two values in a row). A finished job (DONE,
or FAILED until `dismiss`) stays the current job until the next `start`.

## Tests

```
sh tools/round/gradle.sh g :core:download:test
```

62 JVM tests, one or more per row of 10.1, against `TestPackServer` (test sources:
`com.sun.net.httpserver` on a free loopback port with Range, ETag, `If-Match`, `If-None-Match`,
redirects, a request log and faults) and the real `PackHttp`, on a virtual clock, with a fake
`InstalledData` (`Fixtures.kt`). Fixtures: `catalog-v1.json` (PA's real sizes and hashes) and
`python-ustar.tar` (made by `packbuilder/catalog/tests/make_tar_fixture.py`). Results:
`data/gradle/g/build/core/download/test-results/test/`.

## Notes

- Logging: a handful of lines through `DownloadLog` (java.util.logging), each marked `// log-ok:`
  and holding only pack ids, part names, enum names, counts and exception class names (rule N3,
  PV2). Never a host, a URL, a server's answer or the installer's words.
- Runtime classpath added by this module: OkHttp 5.3.2 and its Okio 3.16.4 (both Apache-2.0),
  `kotlinx-serialization-json` (Apache-2.0, already used by other modules).
