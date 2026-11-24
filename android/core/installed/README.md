# :core:installed

Plain Kotlin/JVM. Owner in the feature round: track P. `InstalledLibrary` implements `:core:api`'s
`InstalledData` (`docs/architecture.md` 2.2; downloads hand-off: `docs/architecture-downloads.md`
6.6). The contract file `InstalledLibrary.kt` only delegates; the work is in `InstalledCore.kt`.

**One flow for all four parts** of a format-3 pack (`docs/pack-format.md`):

1. inspect: the manifest only; `neededBytes` = parts + 64 KiB + (the merged routing set now + the
   routing part: the merge's peak) + 4 MiB + `free_space_margin_mb`; free space is never a problem.
2. stage into `packs/.staging-<id>-<n>`: an archive is copied and hashed in one pass; a download
   folder (`startInstallFromFolder`) is moved (rename) and hashed there; `addresses` skipped when
   asked (left in the download folder).
3. a preliminary `install-record.json` (`routing_merged` false), the cheap checks, the routing index
   against the routing part (size, SHA-256, and its `state` must be the manifest's). A pack with
   another `pack_id` for an installed state is refused (one routing state per state).
4. `RoutingPackManager.install` (`:routing:packs`, `verifyTarHash = false`) into `routing/`
   (`routing.tar`, `valhalla.json` without `tile_url`): the routing commit.
5. the staged `routing.tar` deleted, the final record (`routing_merged` true), the folder renamed
   into place (an older copy aside, then deleted), **one new generation**.

Removal: refused while held → renamed aside → routing uninstalled (the removed state's, and any
other state no installed pack owns) → generation → folder deleted. On a full phone the routing
uninstall (it rewrites the remaining set beside the old one) is tried again once the folder is
deleted, with a second generation when it worked; if it still does not fit, `remove` says so in
plain words and a later scan finishes it. `remove` and `removeOptionalParts` return their failure
in plain words; they never throw at the caller. `InstallStatus.Failed` is always the installer's
own sentence: routing refusals are worded by `PackException.kind`, an unexpected exception gives a
fixed sentence with its class name only. `holdChanges` returns null while a change (or a repair)
runs; a hold changes `changesHeldBy` but not the generation. Optional parts: `startAddOptionalParts`
moves the house numbers into the installed pack and replaces the record; `removeOptionalParts`
replaces the record, publishes, then deletes the file.

**While changes are held** (a trip): `refresh` changes no file and publishes no new generation
(only a process's first scan is published); what it found (a repair, a changed view) runs with a
rescan as soon as the last hold is released.

**After a crash** (`cleanUp`, on every `refresh`, on the first scan the library starts by itself
when it is made (`refresh` waits for that first scan), and before every change): a staging folder whose
routing commit happened (its `routing.json` equals the routing set's manifest of that state) is
rolled forward (if that fails, the folder is kept, owns its routing state, is listed in
`InstalledSet.issues` and is tried again next time); any other is deleted; `.old-*` is restored
when nothing replaced it; `.removing-*` is deleted; routing states no installed pack owns, and any
whose `routing/packs/<state>.json` cannot be read, are uninstalled. Nothing in `cleanUp` throws:
what it cannot repair now is listed in `issues` (folder, or `routing/<state>`) and a damaged
routing folder never stops a scan.

Tests: `sh tools/round/gradle.sh p :core:installed:test --rerun` (35 tests, about 2.5 minutes).
`InstalledLibraryTest` (24, eight of them for the review findings of report 12 section 9) and
`InstalledFolderTest` (5) run on small synthetic packs
(`MiniStates.kt`); `RealArchivesInstallTest` (3: archives, update, removal, the folder install,
house numbers) and `RealArchivesRulesTest` (3: hold, busy, space with the merge peak, the round-2
version-2 archive, crash recovery at the four points, fingerprint mismatch) install the real PA and
OH archives of `data/r3-packs` (download folders made of hard links, so the originals are never
changed) and are skipped without them. A test makes one library at a time on its folders and ends
it (cancel and join its scope) before the next and before JUnit deletes the temporary folder: two
libraries on the same folders would take each other's staging folders for crash leftovers, and on
Windows a file still open makes the folder undeletable. No log calls (rule PV2).

Integration (I): `InstalledLibrary(File(filesDir, "packs"), File(filesDir, "routing"),
ComposeRules.readers().copy(addrKeyFingerprint = <S's port>), rules, appScope, Dispatchers.IO)`,
one instance for the app; packs and routing on the same volume as the download folders (moves).
