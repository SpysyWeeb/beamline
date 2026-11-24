# Experiment 10: search on the device (Kotlin port of the search engine and the address lookup)

Date: 2026-11-15, revised the same day after two reviews (section "Review and fixes"). Track: search
on the device (branch `r2b-search`). Code: `android/core/search/`
(the UI-free module), `android/core/search-bench/` (benchmark tooling), `android/demo/search/` (demo app), `android/scripts/copy_search_rules.py`,
`android/scripts/search_demo.py`, conformance inputs and scripts in `android/core/search/conformance/`.
Outputs (not in git): `data/search-conformance/` (reference answers, findings, benchmark results),
`data/search-demo/` (screenshots, device benchmark, memory). Inputs, read only: the search pack
`data/search/pa-search.sqlite` and the device address file `data/addresses/pa-addresses-slim.sqlite`
of the main checkout, built on 2026-11-15 (reports 03 and 04).

**Machine load:** the PC was shared with five other engineers the whole time. CPU load read 77-100 %
at the start and end of every first-round measurement below, and 7-97 % in the review round (two or
three other emulators and several Gradle builds were running). All timings are therefore noisy; compare columns measured close together, and
treat any single number as +-50 %. The emulator is an x86_64 guest on this PC's CPU, so its numbers
say nothing about a real (ARM) phone.

## Result in one paragraph

`:core:search` is a plain Kotlin/JVM module (about 3,200 lines) that ports
`packbuilder/search/query.py` and `packbuilder/addresses/addrlib` line by line, reads every rule from
the builders' own JSON files (copied by a script, never edited), and talks to SQLite through
`androidx.sqlite:sqlite-bundled` 2.7.1. That library has a JVM variant with the same SQLite build
(3.50.1, FTS5, R*Tree) for Windows, Linux and macOS, so the tests run on the plain JVM against the
real Pennsylvania pack with the SQLite the phone will use. **The Kotlin engine gives exactly the
Python answers:** on 1,069 queries (all 493 test-set cases plus 576 sampled prefixes, brand names
with punctuation, numbers, addresses, vocabulary words, hostile strings and bad positions) the top-10
ids are the same in the same order for 1,069 of 1,069, and every field of every result (title,
subtitle, category, coordinates, distance to 0.01 km, score to 4 decimals, house-number fields) and
every parse label is equal; the same holds for the answers made on the emulator (Android's own
Unicode tables, ART and bionic). The three test sets pass at exactly Python's rates (tuned 334 / 341
of 345 top-1 / top-5, 2 data gaps; held-out 94 / 96 of 99, 1 gap; review 48 / 49 of 49), case by
case. The address parser, normaliser and lookup agree with `addrlib` on all 525 typed addresses
(the addresses track's 435 end-to-end variants plus 90 extra strings), both on the pack and on a copy
with the targets removed (435/435 and 294/435 within 100 m, as in report 04), and on every street
(170,448), place (3,449) and house-number (24,774) spelling of the address file and every entry name
(227,152) of the search pack. A negative control with nine planted differences is caught nine times;
since the review, so are a field-only control and a stale reference, each failing the Gradle run.
No difference had to be fixed. **Speed: the Kotlin engine is about as fast as the Python one, not
faster.** Measured interleaved in three rounds (review round), the ratio of the Kotlin to the Python
median over the 444 test-set queries was 0.60, 1.15 and 0.78, and for typing and common whole words
0.62-1.52; the host load swung between 7 and 93 % within minutes and moves both by more than the
engines differ. Both spend most of their time in SQLite (the slow common-word queries are SQLite-bound).
Kotlin on the JVM: warm median 7.5-13.7 ms, p95 34-71 ms; Python 11.6-12.5 / 41-52 ms. On the emulator
(three runs, host load 29-97 %): warm median 7.8-14.7 ms, p95 40-90 ms; typing 1, 2, 3 letters:
median 1.5-2.1 / 0.5-0.8 / 4.0-5.1 ms. Opening both files takes 2-17 ms with a
warm file cache and 90-333 ms with the page cache dropped; the app holds about 30 MB PSS with both
files open. The bundled SQLite adds 1.14-1.28 MB per processor type to the APK (0.62-0.64 MB
gzipped). A demo app searches as you type near Erie or Pittsburgh; the screenshots of the five
required queries look right, and show two ranking weaknesses of the reference itself ("wal" from
Pittsburgh shows no Walmart; a typed house number without a town is located only near the user).

**Review round (same day):** two independent reviews found one serious defect, which is fixed: an
engine shared by several threads crashed the JVM (or spun forever) because nothing serialised the
one SQLite connection; every connection now runs under a lock and a four-thread test gives the
single-thread answers. Also fixed: the conformance test now fails on any field, label, flag or
verdict difference (it asserted only ids and pass rates) and on a stale reference (fingerprint of the
packs and the Python sources); a strict mode turns skipped tests into failures; a busy timeout of 5 s
as in Python; `limit` 0 / negative; a 256-character input cap in `SearchService`; stricter version and
schema checks; the demo closed nothing on rotation; the benchmark code moved out of the shipping
module; the speed claim above was re-measured and corrected. Everything was re-run afterwards with
the same conformance result. Details in "Review and fixes" at the end.

## How to rerun everything

From the repository root on Windows (Git Bash). `<main>` is the checkout that holds the built packs
(read only); the Python 3.12 standard library is enough for every script, the builders' venvs work too.

```
# 0. machine-specific settings (not committed)
printf 'sdk.dir=<Android SDK>\nbeamline.searchDb=<main>/data/search/pa-search.sqlite\nbeamline.addressesDb=<main>/data/addresses/pa-addresses-slim.sqlite\n' > android/local.properties

# 1. rule files: copy from packbuilder/ (and check they are current)
python android/scripts/copy_search_rules.py
python android/scripts/copy_search_rules.py --check

# 2. conformance inputs (committed; regenerate only when the test sets change) and Python reference
python -I android/core/search/conformance/make_queries.py --db <main>/data/search/pa-search.sqlite
python -I android/core/search/conformance/make_reference.py \
    --search-db <main>/data/search/pa-search.sqlite \
    --addresses-db <main>/data/addresses/pa-addresses-slim.sqlite \
    --e2e-cases <main>/data/addresses/reports/e2e_cases.csv           # about 6 min; writes data/search-conformance/

# 3. Kotlin: unit tests, conformance, pass rates (JVM, bundled SQLite); strict = nothing may be skipped
cd android && BEAMLINE_TESTS_STRICT=1 ./gradlew :core:search:test && cd ..   # about 1.5-3 min
python android/core/search/conformance/compare.py                      # independent check, Python only
#    negative controls: each Gradle run must FAIL (9 findings; 4 field-only findings; stale reference)
python android/core/search/conformance/negative_control.py
python android/core/search/conformance/negative_control.py --only title,score,distance,label --dst data/search-conformance-negative-fields
python android/core/search/conformance/negative_control.py --only stale --dst data/search-conformance-negative-stale
cd android
for v in negative negative-fields negative-stale; do
  BEAMLINE_CONFORMANCE_DIR=<repo>/data/search-conformance-$v ./gradlew :core:search:test \
    --tests '*SearchConformanceTest*' --tests '*AddressConformanceTest.sameAnswersAsAddrlib*'
done
cd ..

# 4. latency: Kotlin on the JVM and the Python engine (packbuilder/search/measure.py), interleaved,
#    3 rounds with the CPU load read around each run (about 15-25 min on a loaded PC)
python -I android/core/search/conformance/interleaved_bench.py --db <main>/data/search/pa-search.sqlite --rounds 3
#    one JVM run alone: cd android && ./gradlew :core:search-bench:bench   (data/search-conformance/bench-jvm.json)
python -I android/core/search/conformance/sql_probe.py --db <main>/data/search/pa-search.sqlite --addresses <main>/data/addresses/pa-addresses-slim.sqlite
cd android && BEAMLINE_PROBE=1 ./gradlew :core:search:test --tests '*SqlTimingProbe*' && cd ..

# 5. emulator: own AVD (3 GB), demo app, packs, screenshots, benchmark, device conformance, memory
SDK=<Android SDK>
echo no | $SDK/cmdline-tools/latest/bin/avdmanager.bat create avd -n beamline_search -k "system-images;android-36;default;x86_64" -d medium_phone
#   then in ~/.android/avd/beamline_search.avd/config.ini: hw.ramSize=3072 hw.gpu.enabled=yes hw.gpu.mode=host
$SDK/emulator/emulator -avd beamline_search -port 5610 -no-window -gpu host -memory 3072 -no-snapshot -no-boot-anim -no-audio &
export ANDROID_SERIAL=emulator-5610
cd android && ./gradlew :demo:search:assembleRelease && cd ..
adb install -r android/demo/search/build/outputs/apk/release/search-release.apk
python android/scripts/search_demo.py push --search-db <main>/data/search/pa-search.sqlite --addresses-db <main>/data/addresses/pa-addresses-slim.sqlite
python android/scripts/search_demo.py shots   --out data/search-demo/screenshots
python android/scripts/search_demo.py meminfo --out data/search-demo/meminfo.txt
python android/scripts/search_demo.py bench   --out data/search-demo/bench-device.json      # 1-3 min; run it 2-3 times, note the host load
python android/scripts/search_demo.py dump    --out data/search-demo/kotlin-search-device.json
python android/core/search/conformance/compare.py --kotlin data/search-demo/kotlin-search-device.json
python android/core/search/conformance/bench_tables.py --device data/search-demo/bench-device.json
adb emu kill
```

Cold start on the device (step 5, by hand): `adb root`, then three times
`adb shell am force-stop io.github.spysyweeb.beamline.searchdemo; adb shell "sync; echo 3 > /proc/sys/vm/drop_caches"`
and `adb shell am start -n io.github.spysyweeb.beamline.searchdemo/io.github.spysyweeb.beamline.searchdemo.MainActivity --es q sheetz --es at erie`,
reading `opened in` and the first query time from `adb logcat -s BeamlineSearch`.

## Inputs and tool versions

| Item | Version / identity |
|---|---|
| Search pack | `pa-search.sqlite`, 154,705,920 bytes, built 2026-11-15T15:05:45, schema 1, `meta.addr_key.table_version` 2 |
| Address file | `pa-addresses-slim.sqlite` (the device file), 287,707,136 bytes, `meta.normalisation_table_version` 2, variant slim |
| Python reference | Python 3.12.10 (Windows), SQLite 3.49.1; code of this branch's `packbuilder/` (identical to the main checkout's apart from CRLF line endings in one JSON file there) |
| androidx.sqlite | `sqlite-bundled` 2.7.1 (Google Maven: newest stable; 2.8.0-alpha03 exists); resolves to `sqlite-bundled-jvm` on the JVM, `sqlite-bundled-android` in the app; pulls `androidx.sqlite:sqlite` 2.7.1 and `androidx.annotation:annotation` 1.9.1 |
| SQLite in it | 3.50.1 on both the JVM (Windows x64 DLL) and the emulator (Android x86_64), `COMPILER=clang-19.1.4`, read at run time (below) |
| Build | Gradle 9.9.1, AGP 9.5.1, Kotlin 2.4.21, Temurin 25.0.3 (all as the skeleton); bytecode 17; kotlinx-serialization-json 1.13.0 (JSON tree only, no compiler plugin); JUnit 6.1.3 |
| Emulator | own AVD `beamline_search`, `system-images;android-36;default;x86_64`, 3 GB RAM, 4 vCPUs, host GPU, headless, port 5610 |

## What was built

| Path | What it is |
|---|---|
| `android/core/search/` (`:core:search`) | Kotlin/JVM library, no Android: `SearchEngine` (port of `query.py`), `address.AddressNormalizer` / `AddressParser` / `AddressIndex` (port of `addrlib`), `SearchService` (engine + address lookup for screens, input cap), result types, `db.SqlDb` (read-only SQLite with a statement cache, one lock per connection, 5 s busy timeout) |
| `android/core/search-bench/` (`:core:search-bench`) | `bench.*`: the benchmark (`SearchBench`, JVM entry `BenchMain`, task `bench`), the device conformance dump and the math checksum, shared by the JVM and the demo; kept out of `:core:search` so the app does not carry it (moved there in the review round) |
| `android/core/search/conformance/interleaved_bench.py` | Kotlin JVM and Python benchmarks run back to back, alternating, several rounds, with the CPU load read before and after each (review round) |
| `android/core/search/src/main/resources/io/github/spysyweeb/beamline/search/rules/` | copies of `packbuilder/search/{abbreviations,ranking}.json` and `packbuilder/addresses/addrlib/{us_street_norm,lookup_config}.json`, plus `SOURCES.json` (source path and SHA-256 of each, on LF line endings) |
| `android/scripts/copy_search_rules.py` | makes those copies; `--check` exits 1 when one is out of date. `RulesAndVersionsTest` fails when a copy differs from its recorded hash or from its source |
| `android/core/search/conformance/` | the query list (committed) and the scripts that make the Python reference, compare independently, plant differences, time SQL and print tables (its README lists them) |
| `android/demo/search/` (`:demo:search`) | demo app, application id `io.github.spysyweeb.beamline.searchdemo`, no permissions: a search box, two position chips (downtown Erie 42.1292, -80.0851; Pittsburgh 40.4406, -79.9959, from `packbuilder/search/tests/points.json`), results as you type with distance in miles, category, subtitle and the address accuracy; bench and dump modes for scripts |
| `android/scripts/search_demo.py` | push the two pack files, screenshots, benchmark, device conformance dump, `dumpsys meminfo` |

Changes to existing files, append only: `android/settings.gradle.kts` (+5 lines: `include(":core:search")`,
`include(":demo:search")`, `include(":core:search-bench")` under a comment naming the track) and `android/gradle/libs.versions.toml`
(+9 lines: the `androidx-sqlite-bundled` library with its pinned version, added as a TOML sub-table
`[libraries.androidx-sqlite-bundled]` after the existing tables, so no existing line moves). Nothing in
`:app` or `:core:packs` was touched. `android/local.properties` (ignored by git) gained
`beamline.searchDb` and `beamline.addressesDb` for the tests; environment variables
`BEAMLINE_SEARCH_DB` / `BEAMLINE_ADDRESSES_DB` override them, and tests that need a database are
skipped when neither is set.

### How the port stays equal to Python

The port follows the Python code function by function, in the same order, with the same SQL text.
Everything that decides an answer comes from data: the database tables and the four JSON files. What
had to be reproduced on the Kotlin side, because the reference relies on it:

- **Insertion order and stable sorts.** Candidates live in an insertion-ordered map whose entries keep
  their position when replaced (as a Python dict does); every sort is stable, so ties keep the
  reference's order. JSON objects are read in file order (kotlinx's tree keeps it; a test checks it),
  which matters for the connector list and for "first spelling wins" in the street tables.
- **Python's `round()`** for scores, distances and address scores: exact decimal value, ties to even
  (`BigDecimal(x).setScale(n, HALF_EVEN)`), not `Math.round`.
- **Python 3.12's `sum()`** of floats is compensated (Neumaier) since 3.12; `addrlib.geo` uses it for
  the mean latitude and the line length of interpolated addresses. Ported from CPython 3.12.10's
  `bltinmodule.c`.
- **`unicodedata.combining(c) != 0`** (canonical combining class) has no Java API. It is computed with
  `java.text.Normalizer`: canonical reordering moves a mark only when classes differ, so U+0301 + c and
  c + U+0334 tell whether c has a non-zero class.
- **Floating point.** Python on Windows uses the C runtime's `cos`/`sin`, Java's `Math` uses intrinsics,
  and on Android bionic's libm. Measured on 200,000 inputs: Python's `cos` differs from Java's in the
  last bit for 15-21 % of them, and Python's `x ** 2` differs from `x * x` for 102. Exact equality of
  every score is therefore impossible in general; only exact ties could flip. The engine uses
  `StrictMath` (fdlibm in both OpenJDK and Android), so JVM and device give the same bits: a checksum
  over 100,000 distances is `c4c4a4d388b879f1` on both. In the 1,069 conformance queries no ranking,
  no rounded score and no rounded distance differs; largest coordinate difference of an interpolated
  address: 1.4e-14 degrees.
- **Big house numbers.** Python's `int()` is unbounded; the port keeps the leading integer as a
  `BigInteger` until the `max_house_number` check (as `addrlib`), so "99999999999999999999 Main St"
  is answered, not crashed.
- **Set iteration order.** Python iterates a set of the brand prefix key and its no-space form in
  hash order (random per process for strings). Only the order of brand names in a parse label depends
  on it, never a result; the test compares those names as a set.

`SearchService` is the one piece of new logic (not in the Python reference, and not compared): the
engine locates a house number only when the address file has an exact point for it on one of its
street results. When the query has a house number, the engine located none, and the user typed a town
or ZIP, the service asks the address lookup; an exact or interpolated answer in the typed town goes
first, an answer elsewhere (`locality_match` false, or another state typed) goes last as a guess.
Without a town or ZIP the lookup is not used, because it would pick a street of that name anywhere in
the state rather than near the user. Address answers get a stable negative id (FNV-1a 64 of street
key, number and ZIP). Checked in `SearchServiceTest` ("150 Main St Brookville PA" from Pittsburgh
comes back as an interpolated position in Brookville, 111 km). Since the review the service also cuts
the text to 256 characters before the engine sees it (`MAX_QUERY_CHARS`; the engine's typo step needs
memory that grows with the square of a word's length), and `limit` follows `query.py`'s
`limit or top_n` (0 = the default; a negative limit is refused).

## The bundled SQLite on the JVM and on Android

`androidx.sqlite:sqlite-bundled` is a Kotlin Multiplatform library. Its Google Maven metadata lists a
`-jvm` artifact for 2.7.1; the jar holds `natives/windows_x64/sqliteJni.dll`, `linux_x64`,
`linux_arm64` and `osx_arm64` libraries (checked by listing the jar). A Kotlin/JVM module that depends
on `sqlite-bundled` gets that variant, and an Android app that depends on the module gets the Android
variant (`libsqliteJni.so` per ABI) through Gradle's variant matching; the release APK contains only
`lib/<abi>/libsqliteJni.so`, no desktop library (checked by listing the APK). So no stand-in was needed
for the tests. Read at run time with `SELECT sqlite_version()` and `PRAGMA compile_options`:

| | JVM (Windows x64) | Emulator (Android 16, x86_64) |
|---|---|---|
| SQLite | 3.50.1 | 3.50.1 |
| Compile options | `COMPILER=clang-19.1.4`, `ENABLE_FTS3`, `ENABLE_FTS3_PARENTHESIS`, `ENABLE_FTS4`, `ENABLE_FTS5`, `ENABLE_RTREE`, `ENABLE_STAT4`, `ENABLE_MATH_FUNCTIONS`, `ENABLE_COLUMN_METADATA`, `ENABLE_NORMALIZE`, `THREADSAFE=2`, `TEMP_STORE=3`, `DEFAULT_CACHE_SIZE=-2000`, `DEFAULT_MMAP_SIZE=0`, `MAX_MMAP_SIZE=0x7fff0000`, `MAX_VARIABLE_NUMBER=32766`, `MAX_ATTACHED=10`, `OMIT_DEPRECATED`, `OMIT_PROGRESS_CALLBACK`, `OMIT_SHARED_CACHE`, `SECURE_DELETE`, `SYSTEM_MALLOC`, `MUTEX_W32` (and the usual defaults) | the same list, except `MUTEX_PTHREADS` instead of `MUTEX_W32` |

This confirms on the device what report 03 could only infer from the AndroidX build file (3.50.1 with
FTS5 and R*Tree). What differs between the JVM tests and the device: the native library is a separate
build per platform (same version and options), the JVM uses Java 25's Unicode tables and the device
ICU's (both only reach the engine through `Normalizer`), and memory and speed. The device dump below
shows that none of this changes an answer. Two options matter for the app: `OMIT_PROGRESS_CALLBACK`
and the driver's API (no `interrupt`) mean a running query cannot be stopped; and with
`DEFAULT_MMAP_SIZE=0` the files are read through SQLite's page cache (2 MB per database per
connection), not memory-mapped.

**What it adds to the APK** (demo release APK, 6,237,331 bytes in all; `libsqliteJni.so` is stored
uncompressed and page-aligned, as Android loads it from the APK):

| ABI | `libsqliteJni.so` bytes | gzip -9 (estimate of the download) | ELF LOAD alignment |
|---|---:|---:|---:|
| arm64-v8a | 1,281,456 | 644,459 | 16 KB |
| armeabi-v7a | 1,254,892 | 623,137 | 16 KB |
| x86 | 1,140,764 | 631,433 | 16 KB |
| x86_64 | 1,189,688 | 624,460 | 16 KB |

Plus 17 KB of Java classes in the AAR. AGP reports "Unable to strip ... libsqliteJni.so" (no NDK
installed) and packages the libraries as shipped. A Play app bundle would deliver one ABI.

## Conformance with the Python reference

Method (details in `android/core/search/conformance/README.md`): the Python engines answer every input
and write their full answers; Kotlin tests answer the same inputs with the same positions and compare.

| Check | Inputs | Equal | Notes |
|---|---:|---:|---|
| Search: top-10 ids in order | 1,069 queries | **1,069** | 345 tuned, 99 held-out, 49 review, 576 extra (by group: prefix1-4 36 each, typing 62, brand punctuation 64, numbers 36, addresses 20, words 105, hostile 70, positions 75) |
| Search: every field of every result | 1,069 | 1,069 | title, subtitle, category label and id, kind, lat, lon, distance_km, brand, score (4 dp), how, tier, house number, street name, house located |
| Search: parse labels, out-of-coverage and position flags | 1,069 | 1,069 | brand-name lists compared as sets (see above) |
| Search: test-set verdicts (top-1, top-5, data gap per case) | 493 | 493 | run_tests.py's checker ported to Kotlin (`Checker.kt`) |
| Search on the emulator (device dump) | 1,069 | 1,069 ids, 1,069 all fields | `search_demo.py dump`, checked by `compare.py` |
| Independent check of the JVM answers (Python, no Kotlin) | 1,069 | 1,069 | `compare.py` |
| Addresses: parse fields | 525 strings x 2 files | 1,050 | 435 e2e variants (300 + 75 type-word typos + 60 clean) and 90 extra strings (unit-test cases, spot checks, contract examples, hostile input) |
| Addresses: top-5 lookup answers, every field | 525 x 2 | 1,050 | on the pack and on a copy without the 435 targets (as `step_eval_parser.py`; 492 rows deleted); lat/lon within 1e-9 degrees, all else exact |
| Address normaliser on the pack's spellings | 170,448 streets; 3,449 places; 24,774 house numbers | all | street key, core key and key variants; place key; canonical number and its integer |
| Search key on the pack's names | 227,152 | all | every distinct entry name |
| Negative control (planted differences) | 9 | 9 detected | order swap, title, score +0.0001, distance +0.01, label, verdict, latitude +2e-9 deg, parsed ZIP, dropped result |
| Negative control, field-level only (review round) | 4 | 4 detected, Gradle fails | title, score, distance and label changed with ids and verdicts left equal; the first version of the test passed this |
| Negative control, stale reference (review round) | 1 | detected, Gradle fails | the reference's fingerprint of `query.py` no longer matches the file |

**Match rate: 100 % at every level. There were no differences, so nothing had to be fixed and there is
no list of causes.** (Re-run after the review fixes against a newly made reference: the same counts,
on the JVM and on the emulator.)

What makes the Gradle run alone a faithful gate (tightened in the review round): `SearchConformanceTest`
fails on any id, field, label, flag or verdict difference (the first version asserted only ids and pass
rates, and printed the rest); `AddressConformanceTest` fails on any parse or result difference. Both
reference files and the normaliser dumps carry a fingerprint written by `make_reference.py` (SHA-256 of
the two pack files and of the 14 reference sources: every `packbuilder` module the Python engines
import, their JSON rule files, `points.json` and `search_queries.json`, text hashed with LF line
endings); the tests recompute it and fail when the reference is older than the code or was made from
other pack files. With `BEAMLINE_TESTS_STRICT=1` (or `-Pbeamline.strict=true`) a missing pack,
reference or rule source fails the tests instead of skipping them, so a CI run without the data cannot
pass green. The risks that could have produced differences, and why they did not here, are in
"How the port stays equal to Python" above. Python and Kotlin also use different SQLite builds (3.49.1
and 3.50.1); SQL without a full `ORDER BY` (several queries rely on the plan's row order, and
`LIMIT` cuts ties) gave the same rows in the same order on every input.

Test-set pass rates, Kotlin = Python, case by case (the same as report 03):

| Set | Cases | Top-1 | Top-5 | Data gaps |
|---|---:|---:|---:|---:|
| tuned (`cases.json`) | 345 | 334 (97 %) | 341 (99 %) | 2 |
| held-out (`cases_holdout.json`) | 99 | 94 (95 %) | 96 (97 %) | 1 |
| review (`cases_review.json`, house cases with the slim file attached) | 49 | 48 (98 %) | 49 (100 %) | 0 |

End-to-end addresses (top answer within 100 m of the true point), Kotlin = Python: target in the pack
435/435; target removed 294/435 (67.7 % of the 300 headline variants, 66.7 % of the type-word typos,
68.3 % of the clean ones, the figures of report 04).

### Normaliser version

The port implements `us_street_norm.json` version 2 (the copy in the resources says so). Checked:

- `AddressIndex` reads `meta.normalisation_table_version` of the address file and refuses to open a
  file with another value or none (`IllegalStateException` naming both versions); `SearchEngine` refuses to
  attach an address file whose version differs from the one the search index was keyed with
  (`meta.addr_key.table_version`), as the Python engine does. Since the review it is stricter than
  `query.py`: it also refuses an address file with no version row (or no `meta` table), a pair of packs
  that agree with each other but not with the port's version, and a search database whose
  `meta.schema_version` is not 1 (the schema the port reads). `allowAddrVersionMismatch` still
  overrides the address checks. Tested with small files of versions 1, 2, 3 and none made in the
  test (`RulesAndVersionsTest`), and on the real files (both say 2, schema 1).
- The search pack's `meta.addr_key` records SHA-256 `2305ecb1...` for `normalize.py`, but the committed
  file (and the main checkout's) hashes to `a8a0cf74...`: the code changed after the index was keyed
  without a version change (report 03 warned about this). Its recorded JSON hash `ee245a5c...` is the
  CRLF copy in the main checkout; the committed LF file hashes to `e1915af3...` with the same content.
  To see whether the change mattered, I compared the stored keys with the current code: all 170,448
  `streets.street_key` values of the address file and all 305,507 `street.addr_key` values of the search
  index equal `street_key()` of today's code. So both databases agree with the port, but a version
  number alone cannot catch such a change (decision 2 below).

## What a result carries

`SearchResult`: stable `id` (the entry id of the pack's search database; negative for address-lookup
answers), `kind` (place, street, POI, address), `title`, `subtitle`, `categoryId` (category code such as
`fuel` or `rest_area`, the main OSM tag of an uncategorised POI such as `shop=hairdresser`, the place
type, or the road class), `categoryLabel`, `lat`/`lon`, `distanceKm` (from the returned point), `brandId`,
and for addresses `AddressInfo`: house number, street name, `accuracy` (POINT exact address point,
INTERPOLATED along a TIGER/OSM range, STREET number not found), `localityMatch` (null when no town was
typed, false = a guess elsewhere) and `stateMismatch`. `score`, `how` and `tier` are kept for debugging.
`SearchResponse` adds the parse labels, the time, `outOfCoverage`, `positionIgnored` and the house number
read. Icons are left to the track that maps category ids.

## The demo app and what the screenshots show

Screenshots (1080 x 2400, emulator, release build): `data/search-demo/screenshots/`, ten of the five
required queries at both positions plus one typed letter by letter. I looked at each:

- "wal" near Erie: Walczak Park, Waldinger Park and Walker (neighbourhoods, 3-11 mi) first, then
  Walgreens (2.2 mi) and Walmart Supercenter (2.3 mi), then boroughs and townships 121-251 mi away. The
  parse line shows "wal" read as brand (Walmart, Walgreens) and as the category `urgent_care` (a prefix
  of "walk in clinic").
- "rest stop" near Erie: six rest areas 18-47 mi, Mercer Welcome Center 69 mi, the two Venango County
  rest areas 64 mi, one more at 71 mi; parse: category (service_plaza, welcome_center, rest_area).
- "sheetz" near Erie: ten Sheetz, nearest first (2.3 to 41 mi), with street addresses.
- "4319 peach st" near Erie: 4319 Peach Street, Erie, 2.8 mi (4.43 km, as report 03), marked "Exact
  address point"; then the other Peach Street pieces marked "Number not found: street only".
- "erie city hall" near Erie: one result, Erie City Hall, 626 State Street, 0.0 mi (the Erie point is
  downtown); near Pittsburgh the same, 117 mi.
- Near Pittsburgh: "sheetz" gives Sheetz Car Wash and nine Sheetz 5.2-8.5 mi; "rest stop" gives rest
  areas 11-46 mi, the Mercer Welcome Center at 58 mi, and "Pittsburgh Botanical Garden Welcome Center"
  9.4 mi (a garden's visitor centre tagged as a welcome centre in the data); the list spans 9.4-58 mi.
- Typed "s", "sh", "she", "shee", "sheet" near Erie through `adb shell input` (screenshot
  `11-typed-sheet.png`; the headless emulator showed no on-screen keyboard, so none is in the picture):
  answered at every letter (45, 23, 265, 31, 30 ms; the slow one was the first three-letter query after
  start). At "sheet" the list is mostly Sheetz stores, with "sheetz" shown as a correction, but also two
  Sheetz Car Wash entries (5.1 and 14 mi) and "Albert's Custom Sheet Metal & Welding" (1.1 mi).

Weaknesses these show, all inherited from the Python reference (the port reproduces them on purpose):
**"wal" near Pittsburgh shows no Walmart or Walgreens at all** although the parse recognises both
brands: nine places (Wall borough 12 mi, the hamlets Walkers Mill, Wall Rose and Walkertown 8-27 mi,
three Walker Townships 100-140 mi, Wallaceton 96 mi, Walnutport 232 mi) and one gym ("Walk Run Lift",
1.0 mi) outrank brand prefix hits (the same family as report 03's open "wa" case). **"4319 peach st" near Pittsburgh** lists
Peach Streets around Pittsburgh with "street only", because the house number is looked up only for the
40 best street candidates, all near the user; the Erie address is not found without "erie" in the query.
The repeated "street only" rows for the pieces of one long street should be grouped in the app.

## Measurements

### Kotlin against Python, interleaved (review round, 17:55-18:03)

The first comparison below (one Kotlin run, then one Python run, minutes apart) did not reproduce:
the review measured Kotlin slower than Python for typing and common words in both of its runs. So
`interleaved_bench.py` ran both back to back, three rounds, alternating which went first, and read
the host CPU load before and after each run (PowerShell `Win32_Processor.LoadPercentage`; other
engineers' builds and emulators were running, and the load swung from 7 to 93 % within minutes). Same
protocols as below (`SearchBench` for Kotlin on Temurin 25 with a 1 GB heap, `measure.py --only
latency` and `--only prefix` for Python 3.12.10). Cells: Kotlin / Python median, ms (p95 in brackets).
Outputs in `data/search-conformance/interleaved/`.

| round (order) | host load %, Kotlin run / Python run | 444 test-set queries, warm | cold | 1 letter | 2 letters | 3 letters | common whole words |
|---|---|---|---|---|---|---|---|
| 1 (K, P) | 19->7 / 7->20 | 7.5 / 12.5 (34 / 52) | 9.9 / 15.6 (36 / 52) | 1.5 / 2.4 (7.1 / 11.1) | 1.1 / 1.5 (2.0 / 3.1) | 4.1 / 5.6 (26 / 34) | 65 / 81 (151 / 183) |
| 2 (P, K) | 93->14 / 20->88 | 13.7 / 11.9 (71 / 41) | 18.7 / 14.4 (78 / 43) | 2.0 / 3.1 (11.2 / 13.5) | 1.4 / 2.1 (2.8 / 4.3) | 5.3 / 7.3 (38 / 42) | 105 / 124 (247 / 228) |
| 3 (K, P) | 13->48 / 62->25 | 9.0 / 11.6 (43 / 45) | 12.4 / 14.1 (45 / 46) | 2.7 / 2.4 (15.3 / 10.3) | 1.7 / 1.3 (3.3 / 2.7) | 5.6 / 5.0 (39 / 30) | 110 / 73 (224 / 165) |
| Kotlin / Python median, per round | | 0.60, 1.15, 0.78 | 0.64, 1.30, 0.88 | 0.62, 0.66, 1.13 | 0.76, 0.70, 1.29 | 0.72, 0.72, 1.13 | 0.80, 0.84, 1.52 |

**Conclusion: no consistent difference.** Kotlin was faster in most cells of rounds 1 and 2 and slower
in most of round 3; the review's two runs had Kotlin slower for typing and common words and 1.3x
faster over the 444 queries. The two engines run the same SQL on SQLite builds of the same family;
SQLite time dominates the slow cases (the FTS query behind "road" alone takes 140-250 ms, below), and
the host load moves every figure by more than the engines differ. The earlier "1.3-2x faster" came
from comparing a Kotlin run and a Python run taken at different loads; it is withdrawn.

### On the emulator, three runs (review round, 18:08-18:12)

Demo app in bench mode on my AVD (x86_64 guest, 4 vCPUs, 3 GB), same protocol; the host load read
before -> after each run. An emulator on a shared PC, not a phone.

| run | host load % | 444 queries warm, median / p95 | cold | typing 1 / 2 / 3 letters, median | common words, median / p95 | open engine / + address file / address index (next, ms) | address lookup median / p95 | PSS |
|---|---|---|---|---|---|---|---|---|
| 1 | 88 -> 97 | 14.7 / 89.6 | 16.1 / 93.7 | 2.1 / 0.8 / 5.1 | 50.5 / 175 | 4.9 / 8.8 / 11.6 | 8.7 / 245 | 29.4 MB |
| 2 | 90 -> 34 | 10.0 / 57.7 | 11.1 / 61.5 | 2.0 / 0.8 / 4.9 | 62.4 / 180 | 3.6 / 7.9 / 11.8 | 5.1 / 167 | 29.5 MB |
| 3 | 29 -> 65 | 7.8 / 39.8 | 8.6 / 40.6 | 1.5 / 0.5 / 4.0 | 42.4 / 140 | 2.1 / 4.8 / 5.8 | 6.7 / 193 | 29.4 MB |

With the first engineer's run (19.7 / 91.8 ms warm at 99-100 % host load) and the review's
(7.1 / 34.3 ms at lighter load), the emulator's warm median over the 444 queries ranged 7.1-19.7 ms
in five runs: read single device figures as one point of that range. The math checksum was
`c4c4a4d388b879f1` in every run (as on the JVM).

### First measurement (one loaded run each, before the review)

All with other heavy jobs running (CPU 77-100 % at every reading). JVM: `./gradlew :core:search:bench`
(now `:core:search-bench:bench`)
at 16:33-16:36 (CPU 100 % then 97 %), Temurin 25, heap capped at 1 GB. Python: `measure.py` at 16:37-16:40
(CPU 97 % then 100 %; its own reading at start 88.7 % and 97.2 %). Emulator: bench mode at 16:29-16:31
(host CPU 99 % then 100 %). Protocol (`SearchBench`, the same as `measure.py`): test-set queries
(tuned + held-out) by type, "cold" = a new engine (empty SQLite page cache, OS file cache NOT flushed)
then one run, "warm" = 5 more runs, no address file attached; typing = every 1-letter (26) and
2-letter (676) prefix and the 100 most common 3-letter word starts, plus 8 common whole words, at Erie,
Philadelphia and Altoona, each run twice and the second counted. p95 is `measure.py`'s
(value at index round(0.95 (n-1))).

Query types, ms, median / p95:

| type | n | Python cold | Python warm | Kotlin JVM cold | Kotlin JVM warm | Kotlin emulator cold | Kotlin emulator warm |
|---|---:|---:|---:|---:|---:|---:|---:|
| abbreviation | 18 | 38.1 / 83.8 | 40.1 / 235.2 | 20.7 / 121.3 | 18.0 / 164.9 | 35.7 / 104.3 | 30.8 / 153.9 |
| address | 10 | 41.0 / 73.9 | 41.4 / 112.3 | 31.8 / 65.8 | 21.4 / 60.2 | 33.0 / 94.5 | 30.1 / 77.3 |
| border | 10 | 18.4 / 31.8 | 13.7 / 36.5 | 12.5 / 44.6 | 6.8 / 18.2 | 11.2 / 18.9 | 9.6 / 16.8 |
| brand | 55 | 22.0 / 69.5 | 14.6 / 64.5 | 10.4 / 27.0 | 6.1 / 31.1 | 12.0 / 53.6 | 10.1 / 61.0 |
| category | 87 | 43.3 / 108.1 | 36.1 / 119.6 | 44.9 / 145.9 | 31.0 / 93.8 | 37.4 / 86.2 | 31.4 / 93.4 |
| mixed | 36 | 55.3 / 224.6 | 56.1 / 186.7 | 26.9 / 92.6 | 18.8 / 86.1 | 27.8 / 224.4 | 31.5 / 182.5 |
| pa_specific | 46 | 44.5 / 180.1 | 43.1 / 198.1 | 23.1 / 91.6 | 16.1 / 78.3 | 21.0 / 59.9 | 21.2 / 60.5 |
| partial | 31 | 45.2 / 112.7 | 35.3 / 143.3 | 26.9 / 68.0 | 24.6 / 60.0 | 27.8 / 81.2 | 24.0 / 76.1 |
| place | 74 | 13.0 / 49.8 | 8.8 / 41.1 | 15.7 / 60.2 | 4.8 / 19.1 | 9.7 / 42.5 | 8.1 / 29.9 |
| street | 38 | 51.1 / 113.1 | 44.1 / 132.9 | 38.3 / 160.3 | 28.9 / 85.3 | 62.3 / 113.6 | 58.3 / 112.0 |
| typo | 39 | 33.1 / 93.5 | 23.4 / 135.3 | 11.1 / 42.2 | 7.2 / 36.8 | 14.4 / 66.3 | 12.4 / 61.5 |
| **all** | **444** | **33.6 / 115.7** | **27.2 / 138.2** | **22.8 / 101.0** | **13.7 / 74.2** | **20.6 / 94.5** | **19.7 / 91.8** |

Typing, ms, median / p95 / max:

| group | n | Python | Kotlin JVM | Kotlin emulator |
|---|---:|---:|---:|---:|
| 1 letter | 78 | 5.9 / 22.0 / 55 | 2.4 / 10.6 / 16 | 4.5 / 24.1 / 108 |
| 2 letters | 2,028 | 2.4 / 9.4 / 106 | 1.6 / 3.1 / 35 | 1.3 / 9.8 / 187 |
| 3 letters (100 most common) | 300 | 12.7 / 111.9 / 555 | 5.4 / 40.5 / 52 | 10.8 / 83.3 / 206 |
| common whole words ("street", "road", ...) | 24 | 225.8 / 689.7 / 884 | 99.1 / 232.7 / 260 | 176.8 / 403.8 / 501 |

House numbers with the slim address file attached, warm median of 5, ms (Python and the JVM probe
back to back at 16:40, CPU 100 %; the JVM bench and the emulator from the runs above):

| query (position) | Python | Kotlin JVM probe | Kotlin JVM bench | Kotlin emulator | top result |
|---|---:|---:|---:|---:|---|
| 1 main st (Erie) | 186.1 | 152.9 | 100.4 | 186.6 | Main Street (number not located) |
| 100 main st (Erie) | 193.5 | 116.9 | 109.7 | 243.5 | Main Street (not located) |
| 500 church st (Erie) | 94.3 | 93.9 | 75.1 | 100.5 | Church Street (not located) |
| 123 n main st (Erie) | 91.1 | 68.2 | 58.2 | 113.2 | North Main Street (not located) |
| 4319 peach st (Erie) | 76.8 | 55.7 | 43.1 | 87.0 | 4319 Peach Street (point) |
| 1600 market st philadelphia (Pittsburgh) | 193.6 | 45.7 | 48.3 | 52.0 | 1600 Market Street (point) |
| 600 grant street pittsburgh (Erie) | 82.4 | 38.5 | 32.4 | 39.3 | 600 Grant Street (point) |
| 72 s main st wilkes barre (Wilkes-Barre) | 149.9 | 75.4 | 58.0 | 61.2 | 72 South Main Street (point) |

Where the time goes (JVM probe vs Python, same SQL, back to back, CPU 100 %): the FTS5 query behind the
common-word case, `"road"*` at Philadelphia (83,626 matches) sorted by distance, top 400: 163 ms in
Kotlin, 244 ms in Python; counting the matches alone takes 139 / 180 ms. The engine runs two such
queries for "road" (by distance and by importance): 400 ms in Kotlin, 672 ms in Python. So the slow
cases are SQLite-bound and the port cannot fix them; they need a different index or query shape
(report 03's open "common whole words" item). Light queries ("walmart" 9.6 vs 15.0 ms, "gas" 32 vs
110 ms) show the Kotlin side's own overhead is small.

Address lookup (`AddressIndex`, 16 typed addresses, 5 runs each): parse median 0.47 ms on the JVM,
0.60 ms on the emulator; lookup median 12.0 ms (JVM) / 17.7 ms (emulator), p95 324 / 548 ms, max
342 / 721 ms. The tail is three inputs: "1 Saint Marys St Erie" (324 ms JVM; the street is not in Erie,
so the typo tier scans Erie's street keys and then the state-wide tiers run; answer: an interpolated
point in Phoenixville with `locality_match` false, as the contract says), "123 n mian st, erie pa 16501"
(301 ms, typo tier over the ZIP's and the city's streets, no answer in the real pack) and "10450 US-19
Wexford, PA" (201 ms; answered in Canonsburg as a guess). The locality query behind the typo tier
(`l.city_key = ? OR l.muni_key = ? OR l.zip IN (...)`) has no index on `city_key`/`muni_key`.

Open times, ms (both files: `SearchEngine` opens the search file, reads categories and meta, attaches
the address file; `AddressIndex` opens the address file and reads its 13,722 place keys):

| | engine | engine + address file attached | address index |
|---|---:|---:|---:|
| JVM, first in the process (loads the native library) | 84.0 | 15.9 | 33.1 |
| JVM, next (median of 4) | 7.4 | 8.6 | 11.1 |
| Emulator, warm file cache, first / next | 9.0 / 5.7 | 14.4 / 6.5 | 16.9 / 15.7 |
| Emulator, page cache dropped (`drop_caches`, 3 app starts) | `SearchService.open` (all of it) 180, 90, 333 | | |

After a cold start the first query is slow while SQLite reads the pages it needs: "sheetz" near Erie
took 1,029, 385 and 1,026 ms in the three cold starts (12-37 ms warm), and a first "rest" took 3.1 s
(0.28 s the second time). The host's file cache may still have held the emulator's disk image, so a
real phone's first query can be slower; this was not measured on a phone.

Memory:

- Emulator, demo app with both files open after the ten screenshot queries (`dumpsys meminfo`): TOTAL PSS
  29.8 MB (native heap 14.1 MB, Java heap 4.7 MB, code 3.4 MB), TOTAL RSS 158 MB (shared framework pages
  included). In bench mode after five queries (`Debug.getMemoryInfo`): PSS 28.8 MB, native heap PSS
  15.4 MB, native heap allocated 19.7 MB, Java heap PSS 2.0 MB. The page caches are 2 MB per database per
  connection (search + attached address file + the address index's own connection: up to about 6 MB).
  The OS file cache that holds the database pages is not counted in PSS.
  Review round, fixed demo: 29.8 MB TOTAL PSS again after the ten screenshot queries (native heap
  13.9 MB, Java heap 4.5 MB); after six rotations 28.6 MB (27.2 MB before them), with seven opens and
  six closes in the log, so one service is open at a time.
- JVM: Java heap in use after a full GC with an engine (address file attached) and an address index open
  and used: 3.5 MB. Process working set at the end 101.6 MB, peak 461 MB during the benchmark (Windows
  `Get-Process`; includes the JVM itself and the JIT).

Build and test times (loaded machine): first build of `:core:search` with the dependency downloads 31 s,
then the smoke test 15 s; full `:core:search:test` about 3 min (the conformance tests are most of it: search
33 s for 1,069 queries plus the checker, addresses 55 s for 1,050 lookups, normaliser 20 s); demo release
build 1.5-3.8 min; Python reference 5 min 53 s (search 32 s, addresses 2 x 40 s, normaliser dumps the rest).
Pushing the two files to the emulator: 154.7 MB in 2.1 s and 287.7 MB in 4.1 s.
Review round (lighter load at times): full strict `:core:search:test` (22 tests) 1 min 17 s to 1 min 27 s;
Python reference with fingerprints 6 min 40 s (hashing the inputs 1.7 s); demo release build 1 min 22 s;
device conformance dump 17.2 s for 1,069 queries; interleaved benchmark, three rounds, 8 min 5 s.

## What the on-device engine still lacks for the app

Not built here; listed so the next step can plan them.

1. **Search along a route.** The engine biases by one point (or a named place). Along a route it needs
   the route geometry: candidates from R*Tree boxes along the polyline (or the remaining part of it),
   ranked by detour (distance off the route and how far ahead) rather than distance from the car, and
   categories first ("gas", "rest stop" ahead on I-80). The `along_street` code for "X on <street>" is
   the nearest existing pattern (POIs inside the street's boxes).
2. **Results from two neighbouring packs.** The engine opens one search database. Near a state line
   (Erie is 10 km from Ohio, Youngstown is in Ohio) the app must query each installed pack whose box is
   near the position and merge. Scores are comparable only if both packs use the same `ranking.json` and
   the same `importance` scale; the merge must de-duplicate across packs, and the house-number join and
   address lookup must go to the pack of the street's state. Today a position outside the pack is only
   flagged (`outOfCoverage`). The pack manifest (`manifest.json`, version 1) has no `search` or
   `addresses` part yet, so the pack scanner does not know these files; their keys, formats and versions
   need defining with the packs track.
3. **Recent searches.** No store of recent queries or chosen results. It needs a small writable database
   or file of the app's own (the packs are read-only), keyed by the stable result id plus a copy of
   title and coordinates (ids change when a pack is rebuilt), shown before the first letter is typed.
4. Smaller gaps seen on the way: no way to cancel a running query (`OMIT_PROGRESS_CALLBACK`, no
   interrupt in the driver), so the app should keep queries short and discard stale answers (the demo
   does the latter); one connection per pack file, shared by all threads under a lock (since the
   review), so a phone screen and an Android Auto screen searching at the same time wait for each
   other's statements; the slow common-word and address-typo cases above; the first query after a
   cold start; the two ranking weaknesses in the screenshots; and a typed address without a town is
   resolved only among the streets near the user.

## Licences

Added by this track (checked at the primary sources on 2026-11-15):

| Item | Licence | Checked at | Ships in the app |
|---|---|---|---|
| `androidx.sqlite:sqlite-bundled` 2.7.1 (`-android`, `-jvm`) | Apache-2.0 | the POMs on Google Maven (`<license>The Apache Software License, Version 2.0`) and `META-INF/androidx/sqlite/sqlite-bundled/LICENSE.txt` in the jar | yes |
| `androidx.sqlite:sqlite` 2.7.1 (`-android`, `-jvm`), pulled in | Apache-2.0 | same POMs | yes |
| `androidx.annotation:annotation` 1.9.1, pulled in | Apache-2.0 | `sqlite-android` POM (AndroidX project licence) | yes |
| SQLite 3.50.1 (compiled into `libsqliteJni`) | public domain ("All of the code and documentation in SQLite has been dedicated to the public domain by the authors") | sqlite.org/copyright.html | yes |
| Rule files copied from `packbuilder/` (`abbreviations.json`, `ranking.json`, `us_street_norm.json`, `lookup_config.json`) | this project's licence (written for the project; report 03 and 04) | the files' own notes | yes |
| Already in the project and reused: kotlinx-serialization-json 1.13.0 (Apache-2.0), Compose, AndroidX core/activity/lifecycle, kotlinx-coroutines (Apache-2.0), JUnit 6.1.3 (EPL-2.0, tests only) | as recorded in report 05 | | as before |

Nothing GPL, AGPL or LGPL was added. The packs themselves (ODbL data, ISC iD words, public-domain
address sources) are not part of the app binary; their notices are in the databases (reports 03, 04).

## Files outside the repository and data

- `~/.android/avd/beamline_search.avd` and `.ini`: my emulator, 4.1 GB with the two pack files
  inside; stopped at the end (`adb emu kill`). The other engineers' AVDs and running emulators were not
  touched (adb was always pointed at `emulator-5610`).
- Gradle caches: `androidx.sqlite` 2.7.1 artifacts in `~/.gradle/caches` (normal dependency cache).
- `data/search-conformance/` (302 MB: reference answers 4.6 MB, normaliser dumps 21 MB, the e2e
  hold-out copy of the address file 288 MB, the Kotlin findings, benchmark outputs, tables) and
  `data/search-demo/` (5 MB: screenshots, device benchmark, device answers, meminfo); `data/logs/`.
  `data/search-conformance-negative/` is the negative control (its address file is a hard link).
  Review round: `data/search-conformance-negative-fields/` and `-negative-stale/` (the two new
  controls), `data/search-conformance/interleaved/` (interleaved benchmark outputs and
  `summary.json`), `data/search-demo/screenshots-review/`, `bench-device-review-r1..3.json`,
  `kotlin-search-device-review.json`, `meminfo-review.txt`, and `data/review-fixes/` (the JVM crash
  report of the shared-engine test before the fix, the lock-stress file); the reviewers' own folders
  `data/review-break/` and `data/review-reproduce/` were left as they were.
- No system-wide installs, no change to user-level Gradle settings; Gradle ran with the repository's
  own caps (2 GB heap, 4 workers); the tests and the benchmark JVM are capped at 1 GB.

## Decisions for the project owner

1. **Ranking of brand prefixes while typing.** "wal" from Pittsburgh shows ten places and no Walmart or
   Walgreens although the brands are recognised; from Erie they come 4th and 5th. Recommendation: tune it
   in the Python reference (the source of truth), for example raising brand-prefix hits near the user
   above far-away places of the same prefix, on a fresh test set; the port follows by re-running the
   conformance test (the rule files are data; a change of logic must be made in both).
2. **Normaliser identity, not only its version number.** The search pack records a `normalize.py` hash
   that no longer matches the code, and the version (2) did not change; all keys happen to agree today.
   Recommendation (report 03's decision 1, now with a Kotlin consumer): one shared normaliser module
   with its version bumped on every key-changing edit, and a key-fingerprint in `meta` (for example the
   hash of the keys of a fixed list of a few hundred street spellings) that the app checks against its
   own port at open time.
3. **Addresses without a town.** The `SearchService` rule uses the address lookup only when a town or
   ZIP is typed. Alternative: resolve the number on the engine's street candidates (their `addr_key`)
   with interpolation as well as points, which would place "150 main st" near the user without a town.
   Needs a decision on whether the search track's join should include TIGER ranges.
4. **First-query latency after start.** Opening is fast, but the first queries read many pages (0.4-1 s,
   3 s for one prefix, on the emulator). Options: warm the hot tables in the background at app start
   (read the `poi_prefix`, place-key index and category tables once), or a larger page cache. Measure on
   a real phone first.
5. **Address typo tier speed.** An index on `localities(city_key)` and `localities(muni_key)` in the
   address builder would likely shorten the 0.3-0.7 s tail (estimate; not built, because the address
   file is the addresses track's output).
6. **An input cap in the Python reference too** (from the review). `SearchService` now cuts the text
   to 256 characters; the engine itself, like `query.py`, has none, so the two stay equal on every
   input. Recommendation: put the same cap into `query.py` (and a `max_query_chars` into
   `ranking.json`, so it is data), then move the Kotlin cap from the service into the engine.
7. **Unicode tables** (from the review). To make every phone fold text exactly as the pack builder
   did, ship a small generated fold table (code point -> folded text for the characters that matter)
   instead of `java.text.Normalizer`. Not needed for US names and addresses today; recommended only if
   non-Latin input becomes a goal.
8. **Normaliser fingerprint in the packs** (decision 2, sharpened by the review): the recorded
   `us_street_norm.json` hash `ee245a5c...` is of a CRLF copy; the builders should hash LF-normalised
   bytes (as `copy_search_rules.py` and the new conformance fingerprint do), or the value depends on
   the checkout.

## Not verified

- No real phone; every device figure is from an x86_64 emulator on a loaded PC. ARM speed, storage
  speed and memory pressure on a phone are unknown.
- Address conformance and the normaliser comparison ran on the JVM only; on the emulator only the search
  answers (1,069 queries) and the distance checksum were compared. The address lookup on the device was
  exercised by the benchmark (its 16 answers equal the JVM's) but not compared field by field.
- The `SearchService` merge rule is new behaviour with one test, not a tested ranking.
- Ohio and two-pack search: no Ohio search pack exists yet.
- The screenshots were taken with query and position set by the script (intent extras); one sequence was
  typed letter by letter through `adb shell input` (the headless emulator showed no on-screen keyboard).
- Unicode tables: the engine folds text with `java.text.Normalizer`, which uses the platform's Unicode
  tables (Java 25 on the JVM, the OS's ICU on Android), while the packs were keyed by Python 3.12
  (Unicode 15.0). The review's sweep of every code point found 48 differences on Java 25, all in
  characters not yet assigned in Unicode 15.0; on Android the tables are older the older the phone
  (Android 8.0, API 26, our minSdk, ships ICU 58.2 with Unicode 9.0; Android 14 has Unicode 15.0,
  per developer.android.com's internationalization page). The pack names were all compared on the JVM
  only. Only queries with characters added to Unicode after the phone's version can fold differently;
  plain US addresses and names are ASCII after folding. Not changed (decision 7).
- The single SQLITE_BUSY the review saw was not reproduced from Kotlin (0 in 30,000 reads while six
  Python processes contended for the file, with and without a busy timeout); the mechanism was shown
  with Python readers (below, "Review and fixes").

## Review and fixes

Two independent reviews of the first version ("reproduce" and "break", both: sound with fixes). I
checked each blocker and major finding myself before changing anything; none was disputed. Every
minor finding was fixed except the Unicode one (documented) and the pack builders' fingerprint
(a builder change, decision 8). Afterwards everything was re-run: a new Python reference (Python pass
rates unchanged), the full test suite in strict mode (22 tests pass, the timing probe runs only on
request), `compare.py` on the JVM and on the emulator answers (1,069 / 1,069 ids and fields both), the
three negative controls, the interleaved speed comparison, three device benchmarks, the ten
screenshots (looked at: the same answers as before), a rotation check, `copy_search_rules.py --check`.
My emulator was stopped at the end.

| # | Finding (lens) | Severity | Checked | What was done |
|---|---|---|---|---|
| 1 | One engine used from several threads livelocks (break) | major | **Confirmed, worse**: my four-thread test on one `SearchService` crashed the JVM (`EXCEPTION_ACCESS_VIOLATION` in the SQLite JNI library, from `SqlDb.run` -> `nativeStep`; crash report kept in `data/review-fixes/`). Cause: one connection built with `THREADSAFE=2` and cached statements, shared without a lock | `SqlDb` runs every use of its connection under one `ReentrantLock` and refuses use after `close`; `AddressIndex`'s place cache is a `ConcurrentHashMap`. `ConcurrencyTest`: four threads on one service (160 queries in 3.1 s, all equal to the single-thread answers), four threads on one `AddressIndex`, six engines in parallel; bounded waits and daemon threads, so a regression fails instead of hanging. A running query still cannot be cancelled; documented |
| 2 | Kotlin-versus-Python speed claim does not reproduce (reproduce) | major | **Confirmed**: the "1.3-2x faster" came from runs minutes apart at different loads | `interleaved_bench.py`: three interleaved rounds with the load read around each run. Kotlin / Python median 0.60-1.15 (all queries), 0.62-1.29 (typing), 0.80-1.52 (common words): no consistent difference; the claim is withdrawn and the result paragraph rewritten |
| 3 | Emulator figures 2.8x off a re-run (reproduce) | minor | Confirmed (load) | Three device runs with host load 29-97 %: warm median 7.8-14.7 ms; with the two earlier runs the range is 7.1-19.7 ms; reported as a range |
| 4 | Conformance test asserted only ids and pass rates (reproduce) | minor | Confirmed by reading the test | It now also fails on field, label, flag and verdict differences (the address test already failed on any). New negative control with only field and label changes: Gradle fails (3 field + 1 label differences); the old test passed it |
| 5 | One SQLITE_BUSY while Python processes read the pack (reproduce) | minor | Mechanism confirmed, Kotlin occurrence not reproduced. SQLite 3.50.1 `os_win.c` `winLock()`: a SHARED lock first takes the PENDING byte, 3 tries 1 ms apart, then SQLITE_BUSY. Six Python processes with busy timeout 0 on one file: 115 "database is locked" in 120,000 reads; with Python's default 5 s: 0. The bundled driver's default busy timeout is 0, Python's 5000 ms. A Kotlin reader among six contending Python processes saw 0 in 30,000 reads, with and without a timeout | `SqlDb` sets `PRAGMA busy_timeout=5000` (as Python); `ConcurrencyTest.connectionsHaveABusyTimeout` checks it (and that the driver's own default is 0) |
| 6 | Report prose on screenshots 06, 07, 11 (reproduce) | minor | Confirmed by looking at them | Prose corrected; `11-typed-sheet-keyboard.png` renamed `11-typed-sheet.png` |
| 7 | Stale names: `push_search_demo.py`, `RulesCopyTest` (reproduce, break) | minor | Confirmed for the demo build file and `copy_search_rules.py` (the demo manifest names no script) | Fixed |
| 8 | `SearchEngine` never compares the packs' version with the port's (reproduce) | minor | Confirmed | `SearchEngine` refuses packs keyed with a version other than `StreetTables.version` (stricter than `query.py`, documented); tested with tiny packs of version 3 |
| 9 | `limit <= 0` throws in the service; negative limits throw in the engine (break) | minor | Confirmed, and found more: `limit = 0` gave 0 results in Kotlin but `top_n` in Python (`limit or top_n`) | Engine and service treat 0 as the default (as Python) and refuse negative limits with `IllegalArgumentException` (Python returns arbitrary counts for those); `SearchServiceTest.limits` |
| 10 | No input length cap: 32,000 characters 3.7 s, 80,000 OutOfMemoryError (break) | minor | Confirmed by the code (`deletes1` builds n strings of n characters); not re-run on the old code | `SearchService` cuts the text to 256 characters (never inside a surrogate pair). The engine keeps the reference's behaviour so the two stay equal; decision 6 proposes the cap for `query.py`. Test: 80,000-140,000-character inputs answered in 5-44 ms |
| 11 | Demo leaks the service on every rotation (break) | minor | Confirmed by the code (no close anywhere) | `DisposableEffect` closes the service on the worker after the running query, also when the open finishes after the screen left. On the emulator: six rotations, seven opens, six closes, PSS 27.2 -> 28.6 MB |
| 12 | Conformance guard gaps: no reference fingerprint, silent skips, rule-source check skipped (break) | minor | Confirmed | `make_reference.py` writes a fingerprint (SHA-256 of the packs and of 14 reference sources) into the references; the tests fail on a mismatch (negative control "stale": fails). Strict mode `BEAMLINE_TESTS_STRICT=1` / `-Pbeamline.strict=true` fails instead of skipping. A missing rule source fails `ruleCopiesMatchPackbuilder` |
| 13 | Version checks inconsistent; schema unchecked; stale normaliser hash; CRLF hash (break) | minor | Confirmed | `SearchEngine` refuses an address file without a version row or `meta` table, and a search pack whose `schema_version` is not 1; `missingOrForeignVersionsAreRefused`. The builders' recorded hashes (stale `normalize.py` hash, CRLF-dependent JSON hash) are the builders' to fix: decision 8 |
| 14 | Unicode normalisation follows the platform's tables (break) | minor | Accepted as stated (the review's sweep); Android's ICU versions checked on developer.android.com (API 26: Unicode 9.0) | Documented under "Not verified"; decision 7 (a generated fold table) if non-Latin input matters. No code change |
| 15 | Benchmark tooling in the shipping module (break) | minor | Confirmed | Moved to the new module `:core:search-bench` (task `:core:search-bench:bench`); the demo depends on it, `:core:search` no longer contains it. One appended line in `settings.gradle.kts` |
| - | Python 3.49.1 versus bundled 3.50.1 SQLite (break, "differs") | note | Already in the report | No tie-order difference in 1,069 + 2,684 queries; equality across SQLite versions is not guaranteed in principle (unchanged) |

Files changed in the review round: `db/SqlDb.kt`, `SearchEngine.kt`, `SearchService.kt`,
`address/AddressIndex.kt`; the tests `ConcurrencyTest.kt` (new), `TestEnv.kt`,
`SearchConformanceTest.kt`, `AddressConformanceTest.kt`, `RulesAndVersionsTest.kt`,
`SearchServiceTest.kt`, `SqlTimingProbe.kt`; `core/search/build.gradle.kts` (strict mode, bench task
moved); the new module `core/search-bench/` (the four `bench` files moved unchanged apart from a
comment); the demo's `MainActivity.kt` and `build.gradle.kts`; `conformance/make_reference.py`,
`negative_control.py`, `interleaved_bench.py` (new), `bench_tables.py` and both READMEs;
`scripts/copy_search_rules.py` and `search_demo.py` (comments); `settings.gradle.kts` (+1 line).
`:app`, `:core:packs` and the version catalog were not touched in this round.
