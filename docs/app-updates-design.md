# App updates from GitHub (item 41) — design draft

The owner, 2026-11-24: "Next we'll work on having app updates run like openpilot, pulling from
GitHub when available." The owner answered section 7's questions on 2026-11-24. *Built so far (2026-11-24):* the release script
`tools/release.py` (section 5; a dry run builds and checks both APKs, `--publish` makes the release),
the `-Pbeamline.abi` build property, `AppUpdate` (`:core:download`: reads `update.json`, picks the
phone's APK, never a downgrade) and `AppUpdater` (`:core:download`: the daily check and the download
through `PackHttp`, so under its network rules, with the size and SHA-256 checked; tests against a local
server). Still to build in the app: the switch, the Wi-Fi rule, the signing-certificate check, the
install with `PackageInstaller`, the chip and the "Updated to" note (section 4).

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
  updates" (on with the main switch, like "Check for map updates"); and from About, "Check now".
- **Download**: on Wi-Fi by itself; on mobile data only after a tap (the downloads rule). Never
  while guiding. Kept in the app's own cache until installed or replaced.
- **Install**: never while guiding. The map shows a chip "Beamline 0.1.25 is ready" (About shows
  the notes); a tap installs, and the app restarts into the new version. Optional, like openpilot:
  "install when I leave the app".
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
