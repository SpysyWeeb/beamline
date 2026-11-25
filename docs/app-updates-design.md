# App updates from GitHub (item 41) — design draft

The owner, 2026-11-24: "Next we'll work on having app updates run like openpilot, pulling from
GitHub when available." The owner answered section 7's questions on 2026-11-24. *Built so far (2026-11-24):* the release script
`tools/release.py` (section 5; a dry run builds and checks both APKs, `--publish` makes the release),
the `-Pbeamline.abi` build property, `AppUpdate` (`:core:download`: reads `update.json`, picks the
phone's APK, never a downgrade) and `AppUpdater` (`:core:download`: the daily check and the download
through `PackHttp`, so under its network rules, with the size and SHA-256 checked; tests against a local
server), and in the app (0.1.26) `AppUpdates` (`app/update/`): Settings, Online features, "Check for app
updates" (an online.json from before takes the main switch) and "Check for an update now"; the daily check
on start; downloads by themselves on an unmetered network only; the chip "Beamline 0.1.x is ready ·
Install" (hidden while guiding); before installing, the package name, a higher version code and the
installed app's signing certificate; `PackageInstaller` with `USER_ACTION_NOT_REQUIRED` on Android 12+;
leaving the app installs only when Beamline installed the current version itself (silent); the
"Updated to" chip once after a newer version starts. Debug and test-extras builds have no update
address. *Not yet tried on a phone:* the install and its dialogs need a published release (section 8).

## 1. What it does

Beamline finds out that a newer version is published, downloads it in the background, and installs
it at a moment that never interrupts driving: like openpilot, which fetches an update while it runs
and switches to it on the next start.

## 2. Where updates come from

- **GitHub Releases of this repository** (public since 2026-11-24). One release per version, tag
  `v0.1.25`, with:
  - `beamline-0.1.25-arm64.apk` (every current phone; about 25 MB) and `beamline-0.1.25.apk`
    (universal, about 85 MB, for the rare 32-bit or x86 device);
  - `update.json`: `{"format": "beamline-update/1", "version_code": 28, "version_name": "0.1.25",
    "min_sdk": 26, "published_utc": "...", "notes": "...", "apks": {"arm64-v8a": {"file": "...",
    "size": ..., "sha256": "..."}, "universal": {...}}}`.
- The app reads `https://github.com/SpysyWeeb/beamline/releases/latest/download/update.json`:
  GitHub sends that address to the newest release (not a pre-release). No API call, no rate limit,
  no account, nothing about the phone is sent (the same posture as the packs catalog).

## 3. Trust

- **Android's own rule is the main guard**: an update installs only when it is signed with the same
  key as the installed app (the owner's key, kept off GitHub). A file from anyone else is refused
  by the system.
- Before installing, the app also checks the download's SHA-256 against `update.json`, that its
  version code is higher than the installed one (never a downgrade), and that its signing
  certificate is the installed one (`PackageManager.getPackageArchiveInfo`), so a bad file is
  dropped with a clear message instead of a system error.
- `update.json` itself is not signed (as the packs catalog); a later step can add a signature.

## 4. When it checks, downloads and installs

- **Check**: once a day when the app opens, under Settings, Online features, "Check for app
  updates" (on with the main switch, like "Check for map updates"); and "Check for an update now" in
  the same section of Settings (with a line saying what it found).
- **Download**: on Wi-Fi by itself; on mobile data only after a tap (the downloads rule). Never
  while guiding. Kept in the app's cache; the last `update.json` is kept too, so an update found or
  downloaded earlier is not forgotten when Android ends the app (a cache Android clears is fetched
  again). A failed check is tried again at the next start; a version the app or Android refused is
  never fetched again (a newer release replaces it).
- **Install**: never while guiding, one at a time. The map shows a chip "Beamline 0.1.25 is ready ·
  Install"; a tap installs (the release notes are on the release page, not in the app yet). Leaving
  the app also installs, but only when it can be silent (not a setting: the owner's answer 2); on
  Android 8-11 only the tap installs. If Android still asks while the app is in the background, the
  dialog cannot open there: the session is dropped and the chip offers it again.
- Android asks the user to allow "Install unknown apps" for Beamline once (Settings opens at the
  right page). The first update is confirmed by the user in a system dialog. From then on, on
  Android 12 and later, Beamline is the app that installed itself, so it can ask Android to install
  later updates without the dialog (`PackageInstaller`, `USER_ACTION_NOT_REQUIRED`); Android may
  still ask in some cases, and the app then shows the dialog.

## 5. Publishing a release (on the PC)

A script `tools/release.py`, run only on the owner's word: builds both APKs signed with the owner's
key, checks them, writes `update.json`, creates the GitHub release with `gh` and uploads the files.
GitHub's builders never see the key, so releases are made on the owner's PC, not in CI.

## 6. Not in this design

Several update channels (openpilot's release / staging / nightly branches), rollback to an older
version, delta updates, updates for the comma 3X software.

## 7. Questions for the owner

1. **Silent installs**: after the first update, install without asking (when allowed), or always
   show the system dialog?
2. **When**: install on a tap of the chip only, or also by itself when the app is left (never while
   guiding either way)?
3. **Channels**: one channel (every published release), or a second "test" channel for builds you
   try first, like openpilot's branches?
4. **Releases**: publish a release after every batch you approve, or only when you say so?

**The owner's answers (2026-11-24):** 1. silent when Android allows it, after the first update the
user confirms (a short note "Updated to 0.1.25" afterwards); 2. install on a tap of the chip, or by
itself when the user leaves the app and it is not guiding; 3. one channel; 4. a release is published
after every batch the owner approves.

## 8. Rollout

1. 0.1.26 (the first with the updater) goes on the owner's phone over USB, the last time.
2. When the owner approves it, `python tools/release.py --notes "..." --publish` makes release v0.1.26.
3. The next approved batch (0.1.27) is the real test: the phone finds it, Android asks once to allow
   "Install unknown apps" for Beamline and to confirm the install; later updates should be silent.
   A test build installed over USB makes the shell the installer again, so the next update asks once more.
