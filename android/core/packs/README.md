# :core:packs

Plain Kotlin/JVM (no Android). Owner in the feature round: track P. Pack format 3:
`docs/pack-format.md`; contract: `docs/architecture.md` 2.2 and 2.11.

| File | What |
|---|---|
| `PackManifest.kt` | `manifest.json` as types: `PackManifest` (`SUPPORTED_VERSION` 3, `pack_version`, `creditFor(skipped)`), the parts `MapPart`, `RoutingPart`, `routingIndex`, `SearchPart`, `AddressesPart`. Version 2 is refused: "manifest_version 2 is an old pack format; rebuild the pack". |
| `Readers.kt` | What the app reads: required parts; map, routing and routing index formats refuse the pack; search and addresses are judged alone (`partProblem`); `addrKeyFingerprint` (the app's street-key fingerprint, set by the integrator from the search port) makes a search part built with another normaliser unusable. `PartHeaders`: the cheap header checks. |
| `PackScanner.kt` | Finds installed packs (cheap checks only); `InstallRecord` (`skipped_parts`, `routing_merged`, `absentParts`). |
| `StyleComposer.kt` | The MapLibre style from the app's template, the overview and the packs. `compose(..., packs: List<PackInfo>)` (JVM name `composeInstalled`) is the round-3 entry: one source per pack (`pmtiles://file://<mapFile>`), credits split into items, each once, `ComposedMap.creditLine`. The older overload over `InstalledPack` stays for `:routing:demo`. |
| `TarReader.kt` | A streaming ustar/pax reader (archives). |
| `PackInstaller.kt`, `PackLibrary.kt` | The round-2 installer and library, kept so `:app` compiles until the integrator moves it to `:core:installed`'s `InstalledLibrary`. They read format 3 too but keep `routing.tar` in the pack folder. |

Tests: `sh tools/round/gradle.sh p :core:packs:test` (JUnit 5). `RealPacksManifestTest` reads the
real archives in `data/r3-packs` (system property `beamline.r3Packs`) and is skipped without them.
No log calls (rule PV2).
