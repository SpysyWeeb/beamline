# Beamline: getting state packs in the app (companion contract, track G)

Date: 2026-11-15. Status: a companion contract of `docs/architecture.md` for the feature round,
adopted in Round G0 (`docs/architecture.md` section 0 and appendix B). Every module, contract file
and data file named here exists in the tree, compiles, and equals the code shown here (checked with
`python tools/check_contracts.py`, which reads this document together with the main one).
`docs/architecture.md` holds what crosses tracks: the module map and dependency directions,
ownership and definitions of done, the permission list and the network rule in short, persistence,
shared files and emulators, the integration checklist, decisions 6.39-6.53, the owner's questions
and the end-to-end test; this document holds track G's journeys and exact texts, the catalog and
file formats, the network rule in full, the Kotlin contracts and the tests, and section 4.3 says
where the main document carries each part. A `// FILE:` block lives in one of the documents only.
This document is frozen like the main one: changes go through the orchestrator (`docs/architecture.md`
5.2), and a difference between the documents is a defect for the orchestrator to fix. The other
companion, `docs/architecture-saved-places.md` (Round SP0), places the search bar, with this
design's map chip under it, and sets the privacy and logging rules every module follows (its section
4, rules PV1-PV8). Package root `P` =
`io.github.spysyweeb.beamline`; Kotlin paths are under `android/`, other paths are from the
repository root.

The owner's decision (2026-11-15): "When the app boots for the first time, the user can just select
the state they are in (either from a list, or by tapping the state on the map), and everything is
done automatically (downloaded and installed)." Read with the lead engineer's conditions: the app
uses the network for two things only (the list of states and the packs), sends nothing about the
user, the phone or its position, keeps map, routing and search on the phone, keeps "install from a
file" as the fallback, and fetches from the build PC until the owner allows publishing to GitHub
Releases.

How to read: section 0 indexes the decisions (numbered 6.39-6.53 in `docs/architecture.md`); 1 is
what the user sees, word for word; 2 the catalog; 3 safety; 4 the network rule; 5 the Android
mechanism; 6 modules and code contracts; 7 the PC tools; 8 configuration; 9 ownership and Round
G0's shared-file changes; 10 tests; 11 what is left out; 12 the owner's questions; appendix A the
measurements; appendix B the outside sources. "Verified" = read in the named source or measured
here; "inferred" = the design's reasoning; *(estimate)* = not measured.

## 0. Decisions

The decisions of this design are 6.39-6.53 in `docs/architecture.md` section 6, where each has
its question, decision and reason. Where their details are here:

| # | Question | Sections |
|---|---|---|
| 6.39 | One archive per state, or the parts? | 2.1, 2.2, 2.4 |
| 6.40 | Compress for transfer? | 2.2, appendix A |
| 6.41 | The existing `.tar` archive | 1.4 ("Using a file"), 2.2 |
| 6.42 | Android mechanism | 5 |
| 6.43 | Resume | 2.4, 2.5 |
| 6.44 | Catalog signing | 3.3 |
| 6.45 | Network rule | 4 |
| 6.46 | First-run picker | 1.1, 6.7, 6.8 |
| 6.47 | Skipping | 1.5 |
| 6.48 | Updates | 1.3 |
| 6.49 | Mobile data | 1.1 step 4, 5.2 |
| 6.50 | One job at a time | 1.2, 11 |
| 6.51 | What P must add | 6.6 |
| 6.52 | When downloaded bytes are deleted | 1.4, 6.4 |
| 6.53 | House numbers after install | 1.2, 6.6 |

## 1. What the user sees

All texts below are exact; they are made in `StatesPresenter` (6.7) and the notification builder,
and the JVM tests compare them. Sizes are decimal (1 GB = 1,000,000,000 bytes): below 10 MB with
one decimal ("8.5 MB"), below 1,000 MB as a whole number ("553 MB"), from 1 GB with one decimal
("1.0 GB"), below 1 MB "less than 1 MB". Dates are "Nov 14, 2026" (en-US, `MMM d, yyyy`). Durations
come from `UnitFormatter.duration` (rule 2.9 of `docs/architecture.md`: formatting exists once). Numbers
below are Pennsylvania's: 548 MB measured (appendix A) plus a placeholder of 31 MB for house numbers
(80 MB unpacked) until P's format-3 packs exist, so "579 MB" to download and "1.1 GB" on the phone.

### 1.1 First run

1. **Splash and driver notice** (track A, unchanged): splash, reveal, then `FirstRunNotice` with
   "I understand". Nothing touches the network before the notice is accepted.
2. **"Choose your state"** (track G, `StatesScreen(mode = FIRST_RUN)`), shown while
   `GetPacks.showFirstRunPicker` is true: only after the first scan of the packs folder
   (`InstalledSet.scanned`), only while no pack is on the phone (none installed and none listed in
   `InstalledSet.issues`), and either the user has not yet picked a state, a file or "Not now", or a
   download started on this screen is not finished (1.5). The catalog is fetched as this screen
   opens. Top to bottom in portrait (in landscape the map is on the left, the list on the right):
   - Title: **Choose your state**
   - Line: "Beamline downloads the maps, directions and search for your state once. After that it
     works without a signal."
   - While the list loads, a line under it: "Getting the list of states…"
   - The **US map** card (at most 40 % of the screen height): all 50 states and DC, Alaska and
     Hawaii in insets at the lower left; postal codes on states wide enough to hold them; pinch and
     double-tap zoom; a "Whole country" chip appears when zoomed in. Colours and outlines by status
     (6.8). A tap outside every state (the sea, Canada, Mexico) shows "Tap a state, or pick it from
     the list." under the map for 3 seconds.
   - A search field: "Search states" (filters the list by name or postal code).
   - The **list**: section "Available" (each row: name, line "579 MB download · data from Nov 14,
     2026"), section "Not available yet" (line "Not available yet"), and if any, "Needs a newer
     Beamline" (line "Needs a newer version of Beamline").
   - A **bar fixed to the bottom of the screen**, outside the scrolling list, so it is always
     visible and reached without passing the 51 rows (also first in TalkBack's order after the
     title): two text buttons, **Use a file instead** and **Not now**.
   Map and list are always both on the screen and always agree: a tap on a state on the map
   selects it (the list scrolls to its row, the row is highlighted); a tap on a row selects it (the
   map zooms to the state and outlines it). Either way the **state sheet** opens. A tap never
   starts a download.
3. **A tap near small states** (DC, RI, DE, CT, NJ and others that are small on screen) opens a
   chooser first: title "Which one?", up to four rows, nearest first ("District of Columbia",
   "Maryland", "Virginia"). Picking one selects it as above.
4. **The state sheet** for an available state (Wi-Fi, enough space):

   > **Pennsylvania**
   > Maps, directions and search for Pennsylvania, from OpenStreetMap data of Nov 14, 2026.
   > Download: 579 MB
   > On this phone: 1.1 GB. Free now: 24 GB.
   > [x] **House numbers** — Find addresses like "4319 Peach St". Adds 31 MB.
   >     Sources: © OpenStreetMap contributors; U.S. Census Bureau TIGER/Line; USDOT NAD; City of Scranton
   > **[Download]**  [Cancel]
   > Map data © OpenStreetMap contributors, available under the Open Database License.
   > Downloading tells the download server your IP address and which state you chose. Nothing else is sent.

   - The house-number box starts as `Settings.includeAddresses` (default ticked: the owner's
     decision); changing it updates both sizes at once and writes `Settings.includeAddresses`. The
     "Sources" line under it is the optional part's `credit` from the catalog, verbatim. The row
     is absent when the pack has no optional part.
   - **On mobile data** (a connected network without "not metered"): an extra line "You're on
     mobile data. This download uses 579 MB of your data plan." and the buttons become **[Download
     on Wi-Fi only]** (starts the job; it waits up to 6 hours for an unmetered network) and [Use
     mobile data] (starts it now).
   - **Offline** (possible only with a cached list): "You're offline. The download starts when
     you're connected." and **[Download when online]**.
   - **Not enough space** (the estimate of 2.6 is above the free space): the "On this phone" line
     becomes "Needs about 1.9 GB free. This phone has 0.8 GB free." and the button **[Free up
     space]** (opens the system storage screen, `ACTION_MANAGE_STORAGE` with
     `EXTRA_REQUESTED_BYTES`); [Cancel].
   - **While the list is still loading** (a tap on the map or a row before it arrives): the sheet
     shows the state's name and "Getting the list of states…", and fills in when the list arrives.
   - A sheet shows and downloads the catalog entry it opened with: a list refresh while it is open
     (6 h rule, Check again) does not change its sizes; the next sheet uses the new list.
   - The credit line is the catalog's licence notice and the last line is the privacy line, both
     always shown.
5. **Download tapped.** `GetPacks` creates the job and starts `DownloadService`; the screen turns
   into the **progress view** at once:

   > **Downloading Pennsylvania**
   > [progress bar]
   > 238 MB of 579 MB · about 3 min left
   > You can leave Beamline or lock your phone. The download keeps going.
   > [Pause]  [Cancel]

   The map card stays (the state outlined), and so do the search field and the list below the
   progress view; a tap on another state opens its sheet with Download disabled and the line "One
   state at a time. Pennsylvania is downloading." The fixed bottom bar shows **Use a file instead**
   and **Look at the map while you wait** (in place of Not now).
   Then, on Android 13+, if the notification permission was never asked, the system asks once.
   The download is already running behind the dialog, and the answer changes only the
   notifications. If it is refused, the progress view adds the line "Notifications are off, so
   Beamline can't tell you when it's done." with **[Turn on]** (opens the app's notification
   settings, `Settings.ACTION_APP_NOTIFICATION_SETTINGS`).
   The second line by phase (6.4): "Getting ready…" (CHECKING, bar indeterminate); "238 MB of 579 MB"
   plus " · about 3 min left" once a speed is known (`UnitFormatter.duration`, "less than a minute
   left" under 60 s); "Unpacking routing data…" / "Unpacking search data…" / "Unpacking house
   numbers…" (UNPACKING); then **Installing Pennsylvania** with "Checking the files…" (installer
   steps CHECKING and COPYING), "Combining routing data…" (MERGING_ROUTING), "Finishing…"
   (FINISHING); no Pause or Cancel while installing. Waiting, paused and stopped downloads show the
   texts of 1.4 in the same view.
   **Cancel** asks first: "Stop downloading Pennsylvania?" / "The 238 MB downloaded so far are
   deleted." **[Delete]** [Keep downloading]. On the first-run screen, Delete returns to the picker
   (list, map and the Not now button back) and clears the remembered first-run choice, so the next
   start shows the picker again.
6. **Installed.** On the first-run screen the picker closes by itself and the map opens fitted to
   Pennsylvania's bounds, with a snackbar "Pennsylvania is ready." If the user had chosen "Look at
   the map while you wait", the same happens on the map: snackbar plus camera fit. The request
   (`GetPacks.showOnMap`) is held until I reports it done and is kept in `getpacks/ui.json`, so a
   download that finishes while Beamline is in the background fits the map and shows the snackbar
   the next time the map opens, whether from the "ready" notification or from the launcher.
7. **Leaving the app or locking the phone during the download: yes, both.** The foreground service
   keeps the process at foreground priority, holds a partial wake lock while bytes move, and keeps
   network access in Doze (verified in today's AOSP source: processes in the foreground-service
   state get the "power restricted network" capability, appendix B; for Android 8-10 inferred).
   The notification (channel "Downloads", low importance, silent, ongoing) shows: title
   "Downloading Pennsylvania", text "238 MB of 579 MB", a progress bar, the action **Pause** (and
   **Use mobile data** while it waits for Wi-Fi); tapping it opens the States screen. Cancel is only
   in the app, behind its confirmation. At the end the ongoing notification goes and a normal one
   stays: "Pennsylvania is ready" / "Tap to open the map." When the download stops for any reason
   other than finishing (the user's Pause included), the ongoing notification is replaced by a
   normal low-importance one with the text 1.4 gives that stop, by default "Download paused ·
   43 %" / "Open Beamline to continue." (tap: the States screen), so a Pause from the lock screen
   leaves a reminder. **Coming back** shows the current phase at once (the screen reads the engine's
   state). If the process ended in between (Android reclaimed it, the app was swiped from Recents,
   force-stopped or updated, the phone restarted), opening the app continues the job from where it
   stopped ("Continuing the download…", then the same view); the server log shows a `Range` request
   starting at the bytes already on the phone.

### 1.2 Adding and removing a state later

The **States screen** (`StatesScreen(mode = MANAGE)`) replaces today's `PackManagerScreen`. It is
opened from Settings → States, from the map's chip (1.5), from search's `OpenStates`
(`docs/architecture.md` 2.6) and from the download notifications. Top to bottom:

- Title **States**.
- Section **On this phone**: one row per installed pack: "Pennsylvania" / "On this phone · data from
  Nov 14, 2026 · 1.1 GB" / "Built Nov 15, 2026" (since 0.1.16: the date in the pack's `pack_version`, so a
  rebuilt pack of the same OpenStreetMap day is told apart; absent for a pack without one; also in the
  map sheet of an installed state) / "House numbers: included" or "House numbers: not included" (from
  `PackInfo.addressesFile`; the second line is absent for a pack that has no house-number part),
  text button **Remove**, and the house-number action below. A pack being downloaded or updated
  shows its progress line here ("Downloading · 43 %", "Waiting for Wi-Fi · 43 %", "Paused · 43 %",
  "Installing…") and the progress view of 1.1 step 5 sits at the top of the screen. Packs copied in
  other ways keep today's "copied outside the app, not checked" note.
- Section **Add a state**: the same map card, search field and list as on the first run, without
  "Not now".
- Footer: "List checked Nov 15, 2026." with a text button **Check again**.
- The fixed bottom bar: **Use a file instead**.

Adding: exactly 1.1 steps 2-5. When installed: the row moves to "On this phone" and a banner says
"Ohio is ready." with **[Show on map]** (closes the screen, fits the map to Ohio) and [OK].
While another state is downloading, the sheet's Download button is disabled with the line "One
state at a time. Pennsylvania is downloading."

Removing: **Remove** → dialog "Remove Pennsylvania?" / "Its maps, directions and search are deleted
from this phone. You can download it again." **[Remove]** [Keep]. During a trip
(`InstalledSet.changesHeldBy` not null) Remove is disabled with the line "You can't remove a state
during a trip."; while a download job for that state exists (an update or house numbers) it is
disabled with "Pennsylvania is being updated. Cancel the update to remove it." If the installer
still refuses, the banner says "Couldn't remove Pennsylvania. Nothing was changed." with
**[Details]** (the installer's own words, on screen only: never logged, rule N3).

**House numbers after install** (decision 6.53). For a state without them:

- When the list offers the same build as the installed one (equal `pack_version`) and P's
  optional-part install exists (`InstalledData.changesOptionalParts`): text button **Add house
  numbers · 31 MB** → sheet "Add house numbers to Pennsylvania" / "Find addresses like "4319 Peach
  St"." / "Download: 31 MB. On this phone: 80 MB more." / the part's "Sources" line / **[Add]**
  [Cancel], with the mobile-data, offline and space lines of 1.1 step 4. Add starts a download job
  of kind ADD_OPTIONAL: the manifest and the house-number part only, handed to
  `startAddOptionalParts` (the rest of the pack is not touched or fetched again). Progress as in
  1.1 step 5, with the title "Adding house numbers to Pennsylvania".
- When the list has a newer build, the row offers **Update** (1.3), whose house-number box does
  both.
- Otherwise (no list, or P's optional-part install not there yet) the row says "To add house
  numbers, remove Pennsylvania and download it again." so the user knows why an address search
  finds nothing.

For a state with them: text button **Remove house numbers** → dialog "Remove house numbers from
Pennsylvania?" / "Searching for addresses like "4319 Peach St" stops working. This frees 80 MB."
**[Remove]** [Keep] → `removeOptionalParts`; disabled during a trip as Remove is, and absent
before P's optional-part install exists.

### 1.3 A newer pack for an installed state

Whenever the list is loaded, an installed pack whose catalog entry is newer (catalog `pack_version`
greater than `PackInfo.packVersion`; for a pack without a version, a later `osm_snapshot`) shows
"Update available · data from Dec 1, 2026 · 560 MB download" and a text button **Update**; on the
map it stays coloured as installed. Update → sheet:

> **Update Pennsylvania**
> New OpenStreetMap data of Dec 1, 2026. Yours is from Nov 14, 2026.
> Download: 560 MB
> Your Pennsylvania keeps working until the update is installed.
> [x] House numbers — Adds 31 MB.
> **[Update]**  [Not now]

The house-number box starts as the installed pack has it (ticked when `addressesFile != null`),
with the part's "Sources" line under it. The credit line, the privacy line and the mobile-data,
offline and not-enough-space lines of 1.1 step 4 apply as they are; the space estimate counts the
old pack, which stays on the phone until the new one is in place (2.6). The flow is the download
flow; the installer replaces the old pack atomically (`docs/architecture.md` 2.2 rule 1). Nothing announces
updates outside the States screen: the app never fetches the list on its own (rule N4).

### 1.4 Every failure

"The app" column says what the code does; texts are exact. "Use a file instead" is in the fixed
bottom bar of the first-run screen at all times (1.1 step 2), and named again where it helps. The
rule behind the "files" column is decision 6.52: downloaded bytes are deleted only when they can
never be installed, or when the user cancels.

| Situation | What the user sees | What the app does next |
|---|---|---|
| No network on the first run (no cached list) | Banner: "You're offline. Connect to the internet to download your state, or use a file." [Try again]. The map and the list show every state, neutral, rows without a size line. A tap opens a small sheet: "Connect to the internet to see the download for Pennsylvania." [Try again] [Use a file instead] | Fetches again when the phone gets a usable network (5.2) while the screen is open, or on Try again; nothing else |
| The list cannot be fetched (server unreachable, timeout, HTTP error, unreadable list) | No cached list: banner "Can't load the list of states right now. Try again later, or use a file." [Try again]; a tap on a state: "Can't load the list of states right now." [Try again] [Use a file instead]. With a cached list: the cached list is used and the footer says "List from Nov 10, 2026. Couldn't check for changes." | Downloads from the cached list are allowed (they pause as "Couldn't reach the download server" if it is still away); no automatic retry loop |
| The phone's date or time is wrong (the server's certificate is "not yet valid" or "expired" for this clock) | List: banner "Can't check the list of states: your phone's date or time looks wrong. Check it in Settings, then try again." [Try again]. Download: **Download paused** / "Your phone's date or time looks wrong, so the download server can't be checked. Check it in Settings, then tap Continue." [Continue] [Cancel] | `CatalogProblem.CLOCK`; PAUSED(CLOCK), files kept (a certificate-date failure anywhere in the exception's cause chain; no retry loop, since retrying cannot help) |
| This build has no catalog address | Banner: "Downloads are off in this build. Use a file instead: a state file (.tar) copied to this phone." (the file is the state's pack archive, `docs/pack-format.md`) | No network use at all |
| The server's list is a newer format (`beamline-catalog/2` or later) | Banner: "This version of Beamline can't read the list of states. Update Beamline, or use a file." | Cached list (if any) still shown and usable |
| One entry is newer than the app (manifest version, part encoding) | Row line "Needs a newer version of Beamline"; sheet "This download needs a newer version of Beamline." [Use a file instead] | No download offered |
| One entry is older than the app reads, or broken | Listed under "Not available yet" | No download offered (2.3 rule 3) |
| The tapped state has no pack | Sheet: "There's no download for Rhode Island yet." and, when neighbours have one, "Nearby states you can download: Connecticut, Massachusetts." [Use a file instead] [Close] | Nothing; no "notify me" (it would need to send something about the user) |
| Not enough space, before starting | Sheet as in 1.1 step 4 ("Needs about 1.9 GB free…", [Free up space]) | Download not offered until the estimate fits |
| Not enough space, found at CHECKING or during the download | Progress view: **Not enough space** / "Pennsylvania needs 1.9 GB free. This phone has 0.8 GB free." [Free up space] [Continue] [Cancel]; notification "Download paused" / "Not enough space. Open Beamline to see what's needed." | PAUSED(NO_SPACE); files kept; Continue checks again |
| Mobile data only | Sheet choice of 1.1 step 4. If Wi-Fi is lost mid-download: **Waiting for Wi-Fi** / "238 MB of 579 MB. The download goes on when you're on Wi-Fi." [Use mobile data] [Cancel]; same in the notification (action Use mobile data) | WAITING(NOT_UNMETERED); goes on by itself on an unmetered network, or at once after "Use mobile data"; after 6 h of waiting, PAUSED(SYSTEM_STOPPED) with files kept and the "Download paused" notification |
| Network lost half way | **Waiting for a connection** / "238 MB of 579 MB. The download goes on when you're online." | WAITING(NO_NETWORK), no request made; after 6 h of waiting, PAUSED(SYSTEM_STOPPED) with files kept and the notification "Download paused" / "Open Beamline to continue." |
| Short errors while connected | **Connection lost** / "Trying again in a moment." | WAITING(RETRYING) with back-off 2, 5, 10, 30, 60, 60… s, reset after any byte gained |
| The server does not answer (the PC asleep, a firewall prompt pending, the host down, 5xx or 429 every time) | After 8 failed attempts (the back-off adds up to 287 s, plus up to 15 s of connect timeout each: about 5-7 minutes): **Couldn't reach the download server** / "238 MB of 579 MB. Your progress is kept." [Continue] [Cancel]; notification "Download paused" / "Couldn't reach the download server. Open Beamline to continue." | `max_attempts_without_progress` (8) attempts with no byte gained → PAUSED(SERVER_UNREACHABLE), files kept; continued by itself the next time the app is opened |
| The process ended half way (Android reclaimed it, a swipe from Recents, Force stop or the task manager's Stop, an app update) or the phone restarted | On the next start of the app: "Continuing the download…", then the progress view (Pause and Cancel are there for a user who meant to stop it) | `recover` turns the job into PAUSED(APP_ENDED); `onAppVisible` continues it (service started from the visible app); resumes with `Range`. The exit reason is not used: `REASON_USER_REQUESTED` covers Force stop, a swipe from Recents and, before Android 14, an app update alike (appendix B) |
| The process ended twice in a row during the same download without progress (for example a crash while unpacking) | On the next start: **Download paused** / "Beamline closed twice while working on Pennsylvania." [Continue] [Cancel] | PAUSED(KEEPS_STOPPING), not continued by itself, so a job that crashes the app cannot crash it at every start (5.2) |
| Android 15+ stops the service after 6 h in 24 h | Notification "Download paused" / "Open Beamline to continue." | `onTimeout` → PAUSED(SYSTEM_STOPPED), `stopSelf()` within a second; continues when the app is opened |
| A file's SHA-256 is wrong | Nothing the first time (the bar steps back by that part); the second time **Download stopped** / "Part of Pennsylvania arrived damaged twice. The parts that arrived whole are kept. Try again later." [Try again] [Cancel] | First mismatch: that file deleted and downloaded again from byte 0; second: PAUSED(DAMAGED): that part's files deleted, every verified part kept; Try again fetches only the missing part |
| The server's file changed under a resume (412, or a new ETag) | Nothing (the bar steps back) | That file restarts from byte 0 |
| A file is gone from the server (404 or 410, at CHECKING or during the download) | If a newer build of the same state is listed: "A newer Pennsylvania was published. Downloading that one." Else **Pennsylvania is no longer offered** / "The list of states changed. Check the list again." [Check again] | Re-fetches the list; switches the job to the newer build (new folder, old files deleted; the house-number choice, the mobile-data choice and the first-run flag carry over) or FAILED(NO_LONGER_OFFERED), files deleted |
| The pack's `manifest.json` disagrees with the list (found at CHECKING, before any part is fetched) | **Download failed** / "The list of states and the download server disagree about Pennsylvania. Check the list again later." [Check again] [OK] | FAILED(LIST_MISMATCH); only the manifest was fetched |
| The installer rejects the pack | **Pennsylvania can't be installed** / "Nothing was changed on this phone." [Details] [OK]; Details shows the installer's own words (on screen only: never logged, rule N3) | FAILED(REJECTED); job folder deleted (a retry would fetch the same bytes) unless the reason is space, then PAUSED(NO_SPACE) with files kept |
| Downloaded during a trip | **Pennsylvania is downloaded** / "It will be installed when your trip ends." | WAITING(TRIP_IN_PROGRESS); the service stops; installed when `changesHeldBy` becomes null (in-process), or on the next app start |
| The server breaks the rules (redirect to http or to a private address, too many redirects, a body longer than declared, a `Content-Encoding`, a 4xx other than 404, 410, 412 and 416) | **Download paused** / "The download server sent something Beamline doesn't accept. Your progress is kept. Try again later." [Continue] [Cancel] | PAUSED(BAD_RESPONSE), files kept; logged as that reason with the file's catalog-relative path (rule N3) |

Other transitions (each is one case of the engine's or the presenter's tests, 10.1 and 10.2):

| When | What happens |
|---|---|
| Pause during UNPACKING | `pause()` also stops inflating at the next buffer; the half-written part file is deleted, its verified transfer file kept, so Continue inflates it again (seconds, no network) |
| Pause during INSTALLING | Not offered (no Pause or Cancel while installing). If the installer reports `Cancelled` anyway (another caller), the job becomes PAUSED(USER) with its files kept (the folder install moves them back) |
| A tap on another state while a job runs | Its sheet opens with Download disabled: "One state at a time. Pennsylvania is downloading." (first run and States screen alike) |
| Remove or Remove house numbers on a state with a job | Disabled with "Pennsylvania is being updated. Cancel the update to remove it." |
| The list refreshes while a sheet is open | The open sheet keeps the entry it opened with (1.1 step 4) |
| A job is switched to a newer build (404) | `include_optional`, `allow_metered`, `first_run` and the kind carry over to the new job |
| The job's state is installed already at its version or newer, with every part it chose (a crash after the installer's rename and before the job ended) | At `recover` and at CHECKING the job becomes DONE: folder deleted, nothing fetched |
| A job whose `pack_id` is installed for another `state` | Refused at CHECKING as FAILED(LIST_MISMATCH) (2.3 rule 3 already ties `id` to `state`) |

Using a file (from any of the above, and from the first-run screen): the system file picker for
`.tar` (`application/x-tar`, `application/octet-stream`) → the installer reads the manifest
(`inspect`) → sheet "Pennsylvania" / "From us-pa-2026-11-14.tar, OpenStreetMap data of Nov 14,
2026." / "Needs 1.9 GB free. Free now: 24 GB." / house-number box when the archive has that part /
[Install] → the installer's progress with "Keep Beamline open until the install has finished." and
the screen kept on (`docs/architecture.md` section 4, unchanged for file installs) → on the first run the map
opens on the state, as in 1.1 step 6.

### 1.5 Skipping

**Not now** closes the picker and shows the overview map of the whole US with no state. Decided:
allowed, because a person with no network and no pack file must still be able to open the app, the
overview is worth looking at, and every way to a state stays one tap away: the chip on the map (I
shows `GetPacks.mapChip` under the search pill, `docs/architecture-saved-places.md` SP2), search's
"Add a state" (`docs/architecture.md` S7), Settings → States. The choice is remembered
(`getpacks/ui.json`, 6.5): the picker is not forced again on the next start. It is also remembered
when a download or a file install is started, and cleared when a first-run download is cancelled or
its failure message dismissed (the picker then comes back, so the user can choose again). A phone
that already has any pack folder (installed before this version, or copied in, even one listed among
the issues) never sees the picker; the choice is written then.

**Where a download shows after the first screen is gone.** One rule, so a restart, a pause or a
failure always has a place:

- While nothing is installed and a download started on the first-run screen is not finished
  (any phase, including PAUSED, and FAILED until its message is dismissed), every start of the
  app shows the first-run screen in its progress view (`showFirstRunPicker` stays true). "Look at
  the map while you wait" goes to the map for this visit only.
- On the map, the chip shows the job whenever one exists: "Downloading Pennsylvania · 43 %",
  "Waiting for Wi-Fi · Pennsylvania", "Download paused · Pennsylvania", "Installing
  Pennsylvania…", "Pennsylvania wasn't installed" (FAILED, until dismissed). With no job and no
  state installed it says "Choose your state"; with a state installed and no job there is no chip.
  A tap opens the States screen, whose top shows the progress view. The chip sits under the search
  pill and shows only while the pill does (`docs/architecture-saved-places.md` SP1, SP2): during
  route choice, guidance and arrival (PLANNING, CHOOSING, GUIDING, REROUTING, ARRIVED), and while a
  search screen, the saved places screen or a map pick is open, it is not shown, so it never covers
  the route preview or the turn banner; the download's notification (1.1 step 7, and 1.4's "It will
  be installed when your trip ends.") carries the job meanwhile, and the chip comes back with the
  pill.

## 2. The catalog

The full reference goes into a new `docs/catalog-format.md` (G writes it from this section, with the
Python snippet of 2.5), next to `docs/pack-format.md`. The format is our own (no OsmAnd or Organic
Maps format or server is used; `docs/architecture.md` 7, "Independence").

### 2.1 Layout on the server

GitHub release assets sit flat in one folder per release, so the layout copies that, and the same
`catalog.json` bytes work on the build PC and on GitHub:

```
<scope>/catalog/catalog.json                      the list (the configured address)
<scope>/us-pa-202611152241/manifest.json          one folder (one GitHub release) per pack build
<scope>/us-pa-202611152241/routing.json.gz
<scope>/us-pa-202611152241/map.pmtiles
<scope>/us-pa-202611152241/routing.tar.gz
<scope>/us-pa-202611152241/search.sqlite.gz
<scope>/us-pa-202611152241/addresses.sqlite.gz
```

`<scope>` is `https://github.com/<owner>/<data repo>/releases/download/` on GitHub (release tags
`catalog` and `us-pa-202611152241`) and `http://<host>:8765/` from the build PC. The catalog names
each file by a path relative to its own address, `../us-pa-202611152241/map.pmtiles`.

### 2.2 Per-part files, compression and the archive

Decisions 6.39 and 6.40, from the measurements of appendix A (Pennsylvania, z14 map, the parts of
`data/android/archives-z14/us-pa-2026-11-14.tar`):

| Part | Size unpacked | Transfer | Transfer size |
|---|---|---|---|
| `map.pmtiles` | 244,703,514 | as is (gzip saves 0.06 %) | 244,703,514 |
| `routing.tar` | 634,429,440 | gzip -6 | 240,430,789 (37.9 %) |
| `search.sqlite` | 154,705,920 | gzip -6 | 63,346,936 (40.9 %) |
| whole pack | 1,033,838,874 | | **548,481,239 (53.1 %)** |

- **Why parts, not one archive.** (a) Resume works per file with plain `Range`; a broken part costs
  only itself. (b) "Skip house numbers" means not fetching that file. (c) No extraction: the
  archive way stores the tar and then the unpacked pack, about twice the space, unless the install
  streams from the network, which cannot resume. (d) GitHub release assets must each be under
  2 GiB (2,147,483,648 bytes); PA's tar fits, but California's routing part alone would be about
  2.28 GB raw *(estimate: PA's routing/PBF ratio 1.90 times California's 1.2 GB extract)*; gzipped
  it would be about 0.86 GB *(estimate)*. (e) The pack manifest already carries size and SHA-256 per
  part, so the files need no new checks.
- **Why gzip, and only gzip.** Routing and search shrink to 38-41 % with gzip -6; the map is
  already compressed tiles. Transfer time halves (at 10 Mbit/s: 13.8 min raw against 7.3 min;
  computed), and so does the load on the free host. gzip is in `java.util.zip` and Python's `gzip`;
  zstd -19 would save another 49 MB (9 % of the gzip transfer) but needs a native library in the
  APK; xz is slower still. Level 6, not 9: level 9 saved 2.5 MB more for 5 times the build time.
  The encoding field leaves room for zstd later (11).
- **Space cost of compression.** A gzip file is kept until it is unpacked (so a resume never loses
  it), then deleted: the peak adds the largest gzip file, 240.4 MB for PA. That is below the
  installer's own peak (it writes a new merged routing tile set at least as large as the new
  routing part, 634 MB), so it does not raise the total need (2.6).
- **Unpack cost.** On this PC, unpacking and hashing routing and search took 5.6 s in Python;
  the phone is slower *(estimate: 2-4 times, 11-23 s)*, against minutes of transfer saved.
- **Builder rule** (`build_catalog.py`, 7.1): gzip a part when it is at least 65,536 bytes and gzip
  -6 makes it 90 % or less of its size; else serve it as is. Gzip files carry no name and no time
  (`gzip.GzipFile(filename="", mode="wb", compresslevel=6, mtime=0)`), so a re-run on the same
  machine gives the same bytes. A different zlib build (another Python, another computer) may
  compress the same input to different bytes, so equal hashes across machines are not promised.
  The rules that keep published hashes stable are therefore: a re-run reuses finished transfer
  files through `transfer.json` (7.1) instead of compressing again, and **a published release is
  never rebuilt**: a changed pack is a new build with a new `pack_version`, in a new release.
- **The archive** stays exactly as in `docs/pack-format.md` (uncompressed ustar, manifest first) and
  is what "Use a file instead" reads. The catalog builder reads either a pack folder or an archive;
  the archive is not published by this design (11).

### 2.3 `catalog.json`, format `beamline-catalog/1`

UTF-8 without BOM, LF, one JSON object. Example (PA, hashes shortened here only):

```json
{
  "format": "beamline-catalog/1",
  "serial": 20261115224107,
  "generated_utc": "2026-11-15T22:41:07Z",
  "licence": {
    "id": "ODbL-1.0",
    "name": "Open Database License 1.0",
    "url": "https://opendatacommons.org/licenses/odbl/1-0/",
    "notice": "Map data © OpenStreetMap contributors, available under the Open Database License."
  },
  "packs": [
    {
      "id": "us-pa",
      "state": "PA",
      "name": "Pennsylvania",
      "pack_version": 202611152241,
      "manifest_version": 3,
      "osm_snapshot": "2026-11-14",
      "credit": "© OpenStreetMap contributors",
      "bounds": [-80.51985, 39.7198, -74.68956, 42.51607],
      "manifest": {"path": "../us-pa-202611152241/manifest.json", "encoding": "identity", "bytes": 2210, "sha256": "…"},
      "parts": [
        {"name": "routing_index", "file": "routing.json", "optional": false, "bytes": 51259, "sha256": "…",
         "credit": "© OpenStreetMap contributors",
         "transfer": {"path": "../us-pa-202611152241/routing.json.gz", "encoding": "gzip", "bytes": 9000, "sha256": "…"}},
        {"name": "addresses", "file": "addresses.sqlite", "optional": true, "bytes": 80000000, "sha256": "…",
         "credit": "© OpenStreetMap contributors; U.S. Census Bureau TIGER/Line; USDOT NAD; City of Scranton",
         "transfer": {"path": "../us-pa-202611152241/addresses.sqlite.gz", "encoding": "gzip", "bytes": 31000000, "sha256": "…"}},
        {"name": "search", "file": "search.sqlite", "optional": false, "bytes": 154705920, "sha256": "92f09d9a15bf…",
         "credit": "© OpenStreetMap contributors",
         "transfer": {"path": "../us-pa-202611152241/search.sqlite.gz", "encoding": "gzip", "bytes": 63346936, "sha256": "…"}},
        {"name": "routing", "file": "routing.tar", "optional": false, "bytes": 634429440, "sha256": "3347f2e97e10…",
         "credit": "© OpenStreetMap contributors",
         "transfer": {"path": "../us-pa-202611152241/routing.tar.gz", "encoding": "gzip", "bytes": 240430789, "sha256": "…"}},
        {"name": "map", "file": "map.pmtiles", "optional": false, "bytes": 244703514, "sha256": "13a23f6a6bb4…",
         "credit": "© OpenStreetMap contributors",
         "transfer": {"path": "../us-pa-202611152241/map.pmtiles", "encoding": "identity", "bytes": 244703514, "sha256": "13a23f6a6bb4…"}}
      ]
    }
  ]
}
```

(The `routing_index` and `addresses` sizes are placeholders: P's format-3 pack does not exist yet.)

**Top-level fields** (all required unless marked):

| Key | Type | Meaning |
|---|---|---|
| `format` | string | `beamline-catalog/<major>`. A reader refuses a major it does not know (NEWER_FORMAT) and reads everything else; unknown keys are ignored at every level, so fields can be added without a new major. |
| `serial` | integer | The builder's UTC time as `YYYYMMDDhhmmss`, always larger than the previous serial it wrote (previous + 1 if the clock is behind). A format-1 client records it and **uses the list it fetched whatever its serial**: with one unsigned host a lower serial protects against nothing, and refusing it could lock a client onto an old list for good (a builder clock that was once ahead). The signed format 2 makes "ignore a lower serial" its rollback protection (3.3). |
| `generated_utc` | string | ISO 8601 UTC, `Z`, at most 32 characters. |
| `licence` | object | `id` (SPDX), `name` (at most 100 characters), `url` (https, at most 200; the app shows the notice and never opens this URL; other clients that open it must check that it is https), `notice` (one line, at most 300 characters, shown with every download). A licence that breaks these refuses the whole list (NOT_A_CATALOG). |
| `packs` | array | One entry per pack, at most one per `state` in format 1. |
| `signature` | — | Not a key. Reserved: a detached `catalog.json.sig` next to the catalog (3.3). Format-1 clients never fetch it. |

**Per pack:**

| Key | Type | Meaning |
|---|---|---|
| `id` | string | The manifest's `pack_id` (`[a-z0-9][a-z0-9._-]{0,63}`); in format 1 exactly `us-` followed by `state` in lower case, so one state's entry can never name another state's pack. |
| `state` | string | USPS code, two capital letters (the picker's key). |
| `name` | string | Shown to the user; at most 64 characters. |
| `pack_version` | integer | The manifest's `pack_version` (format 3: the build time `YYYYMMDDhhmm` UTC); for a format-2 manifest the builder derives it from `created_utc` the same way. Higher = newer build of the same `id`. |
| `manifest_version` | integer | The manifest's layout version. An app reads the versions from its minimum to its maximum (today both are `PackManifest.SUPPORTED_VERSION`: the installer refuses older and newer alike). Above its maximum: "Needs a newer version" (NEEDS_NEWER_APP); below its minimum: "Not available yet" (OLD_FORMAT). |
| `osm_snapshot` | string | `YYYY-MM-DD`, the data date shown to the user. |
| `credit` | string | The manifest's `attribution`; at most 300 characters. |
| `bounds` | array | `[west, south, east, north]`, as the manifest. |
| `manifest` | object | A transfer object (below) for `manifest.json`; encoding always `identity`. |
| `parts` | array | One object per manifest part, in download order (ascending transfer size). |

**Per part:** `name` (manifest part name, `[a-z][a-z0-9_]{0,31}`), `file` (the manifest's `file`;
the plain-file-name rule of `docs/pack-format.md`), `optional` (true only for parts the pack format
calls skippable: `addresses`), `bytes` and `sha256` (of the file as installed; must equal the
manifest's), `credit` (optional: the part's `attribution`, at most 300 characters), `transfer`.

**Transfer object:** `path` (relative to the catalog URL, rule below), `encoding` (`identity` or
`gzip`; `identity` means the transfer file is the part file and its `bytes` and `sha256` equal the
part's), `bytes`, `sha256` (of the transfer file as served).

**Rules a reader enforces** (`CatalogFormat.parse`, the Python client, the builder's `--check`):

1. Size at most 2 MiB (`catalog_max_bytes`); JSON object; `format` as above.
2. Every `path` matches `^(\.\./)?[A-Za-z0-9][A-Za-z0-9._-]*(/[A-Za-z0-9][A-Za-z0-9._-]*)*$` and,
   resolved against the catalog URL (RFC 3986), stays inside the scope (the catalog's folder's
   parent). One bad path refuses **the whole list** (OUTSIDE_SCOPE): it means a broken or hostile
   server.
3. Per entry (a failure makes only that entry unusable: BROKEN, OLD_FORMAT or NEEDS_NEWER_APP):
   required fields present and typed; strings within their lengths; `id` equal to `us-` +
   `state` in lower case; `bytes` from 0 to `max_file_bytes` (8 GiB - 1); `sha256` 64 lowercase
   hex digits; `encoding` known; `manifest_version` from the app's minimum to its maximum; `id`,
   `state` and part `file` names unique; part names unique; no part `file` named
   `manifest.json`, `install-record.json` or `job.json`.
4. After downloading `manifest.json` (its own size and hash checked), its `pack_id`,
   `pack_version` (when present), `state`, `osm_snapshot` and every chosen part's `file`, `bytes`
   and `sha256` must equal the catalog's; otherwise the job fails as LIST_MISMATCH (the server's
   list and its files disagree; nothing but the manifest was fetched).

### 2.4 Client algorithm (normative, device-neutral)

For one pack, with the chosen parts (all, or all but the optional ones):

1. GET the manifest (small, no resume) and check it (rule 4 above).
2. For each chosen part, in catalog order: local name of the transfer file
   `.transfer-<part name>`; if the final file `<file>` already exists with the right size and the
   job record says verified, skip. Else:
   - If a `.transfer-*` file exists with N bytes (0 < N < transfer `bytes`), re-hash those N bytes,
     then GET with `Range: bytes=N-` and `If-Match: <saved ETag>` when an ETag was saved. 206 whose
     `Content-Range` starts at N: append. 200: the server ignored the range: truncate and take the
     whole body. 412, or a strong ETag that differs from the saved one: truncate, start again at 0.
     416 with `Content-Range: bytes */<size>` and N = size: complete. Redirects are followed only
     as 3.1 allows (https to https, every resolved address public, at most 5). Never store a
     redirect target; every attempt starts at the catalog-relative URL.
   - Read at most `bytes - N` more bytes; more is an error. Update the hash with every byte written.
     A transfer that ends early keeps its bytes for the next attempt.
   - At the end the size and SHA-256 must equal the transfer object's; on a mismatch delete the file
     and fetch again once (`hash_retries`), then stop with that part missing (the parts already
     verified stay).
   - `gzip`: inflate the transfer file into `<file>` (at most the part's `bytes`; more is an error),
     hashing the output; size and SHA-256 must equal the part's; then delete the transfer file.
     `identity`: rename the transfer file to `<file>`.
3. The folder now holds `manifest.json` and the chosen part files: a pack folder in the layout of
   `docs/pack-format.md`, ready for the installer (the Android app) or for direct use (a later
   comma 3X client).

### 2.5 What a Python client needs

Standard library only (`json`, `urllib.request`, `hashlib`, `gzip`, `ipaddress`, `socket`, `os`).
The **normative** reference client is `packbuilder/catalog/catalog_client.py` (7.3): it also
checks the catalog host's own addresses, the debug server's same-host redirect rule and the rules
of 2.3. The core of one resumable file, for `docs/catalog-format.md` (this exact text was run here
against a local Range server: a cut transfer keeps its bytes and resumes, a complete file ends with
416, an ignored range and a 412 start again from 0, and redirects to `http://127.0.0.1`,
`https://127.0.0.1` and `https://10.200.0.5` are refused):

```python
import hashlib, ipaddress, os, socket, urllib.error, urllib.parse, urllib.request

class Redirects(urllib.request.HTTPRedirectHandler):
    max_redirections = 5                                   # 3.1: at most 5 hops
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        u = urllib.parse.urlsplit(newurl)                  # 3.1: https only, every address public
        if u.scheme != "https" or not all(ipaddress.ip_address(a[4][0]).is_global
                                          for a in socket.getaddrinfo(u.hostname, u.port or 443)):
            raise urllib.error.HTTPError(newurl, code, "redirect refused", headers, fp)
        return super().redirect_request(req, fp, code, msg, headers, newurl)

def fetch(url, dest, size, sha256, etag=None):
    """One transfer file (2.4). Returns the ETag to keep for the next resume."""
    have = os.path.getsize(dest) if os.path.exists(dest) else 0
    h = hashlib.sha256()
    if have:
        with open(dest, "rb") as f:
            for b in iter(lambda: f.read(1 << 20), b""): h.update(b)
    req = urllib.request.Request(url, headers={"User-Agent": "Beamline-Packs/1", "Accept-Encoding": "identity"})
    if have:
        req.add_header("Range", f"bytes={have}-")
        if etag: req.add_header("If-Match", etag)
    try:
        r = urllib.request.build_opener(Redirects).open(req, timeout=30)
    except urllib.error.HTTPError as e:
        if e.code == 416 and have == size and e.headers.get("Content-Range") == f"bytes */{size}":
            r = None                                       # already complete
        elif e.code == 412:                                # the file changed: start again at 0
            os.remove(dest); return fetch(url, dest, size, sha256)
        else:
            raise
    if r is not None:
        with r:
            new_etag = r.headers.get("ETag")
            if r.status == 200 or (etag and new_etag and not new_etag.startswith("W/") and new_etag != etag):
                have, h = 0, hashlib.sha256()              # range ignored, or another file: start over
            elif not r.headers.get("Content-Range", "").startswith(f"bytes {have}-"):
                raise ValueError("206 does not start where asked")
            etag = new_etag
            with open(dest, "r+b" if have else "wb") as out:
                out.seek(have); out.truncate()
                for b in iter(lambda: r.read(1 << 20), b""):
                    have += len(b)
                    if have > size: raise ValueError("longer than the catalog says")
                    out.write(b); h.update(b)
    if have < size: raise ConnectionError("cut short; call again to resume")    # bytes kept
    if h.hexdigest() != sha256: os.remove(dest); raise ValueError("damaged")
    return etag
```

### 2.6 Free space

`Space` in `:core:download` (code in 6.3). Let R = the unpacked pack (manifest plus the parts the
job fetches), G = the largest gzip transfer file among them, D = bytes already in the job folder,
M = `space_margin_bytes` (200 MB, as P's `free_space_margin_mb`), S = the merged routing tile set
already on the phone (`GetPacksConfig.routingSetBytes`: I gives the size of the files in
`InstalledLibrary`'s routing folder; 0 when no state is installed). Bytes still needed from now to
the end of the install = (R − D) + max(G + M, I), where I is what the installer needs beyond the
parts:

- before the manifest is fetched (the sheets' estimate): the installer writes a new merged tile set
  (`routing.tar.new`, `docs/architecture.md` 2.2 rule 1) beside the old one, as large as the old one plus the
  new routing part, so moves: S + routing part + M; copies (stand-in): R + S + routing part + M. For
  an update the old pack and its tiles stay until the switch, so the same formula holds (an upper
  bound: the replaced state's tiles leave the new set). Adding house numbers to an installed state
  (kind ADD_OPTIONAL): R is that part only and I = M;
- at CHECKING, from the installer's own figure (`inspect` → `ArchiveInfo.neededBytes`, which
  includes the routing merge's peak, minus `addressesBytes` when skipped): moves: `neededBytes − R`;
  copies: `neededBytes`.

With Pennsylvania installed, the estimate for a second state includes S = 634 MB (PA's merged
tile set); leaving S out would let the sheet say "fits" and CHECKING then pause at once.

The free space is `GetPacksConfig.freeBytes(downloadsDir)`, the same function the integrator gives
`InstalledLibrary`: **`File.usableSpace`** (the installer's default in `docs/architecture.md` 2.2), the bytes
a write can use now. Not `StorageManager.getAllocatableBytes`: it counts cached files the system
deletes only when the app calls `allocateBytes` (verified, StorageManager reference), so comparing
with it and then writing could end in ENOSPC while the screen said there was room. The user frees
cache space through **[Free up space]** (`ACTION_MANAGE_STORAGE` with `EXTRA_REQUESTED_BYTES`). The
check runs at the sheet (estimate), at CHECKING (exact, before any part is fetched) and on Continue.
A write failing with ENOSPC pauses the job (PAUSED(NO_SPACE)), and the figures shown are the exact
need and `usableSpace` at that moment.

## 3. Integrity and safety

### 3.1 Transport

- **Public builds: HTTPS only**, the platform's trust store, no pinning (GitHub's and Azure's
  certificates rotate). Release builds have no network security config, so the platform default
  (no cleartext for target 28+, verified) applies; `CatalogAddress.parse` refuses `http` unless
  the build allows it (`allowCleartext` = debug) and the host is a private or loopback IPv4 literal,
  and refuses a private or loopback host of any scheme in a build that does not allow it.
- **Debug builds:** plain HTTP only to `10.0.2.2`, `127.0.0.1`, `localhost` and the one private
  address configured for the owner's phone, enforced twice: by the generated debug network security
  config (8.2) and by `CatalogAddress`.
- Redirects are followed by `PackHttp` itself, at most 5, each checked (`redirectAllowed`): from
  https only to https; from the debug http server only to the same host and port. 301, 302, 303,
  307 and 308 are followed as GET.
- **Every connection goes to public addresses only.** `PackHttp` resolves host names through its
  own resolver and refuses, before connecting, any address that is loopback, private
  (site-local), link-local, any-local, multicast or IPv6 unique-local (`fc00::/7`), whether the
  name came from the configuration or from a redirect, and whether it is a literal or a public
  name whose DNS record points into a home network. The one exception is the debug build's own
  local server: when the configured catalog host is a loopback or private IPv4 literal
  (`CatalogAddress.local`), that exact host is allowed, and only it. So a server cannot make the
  phone fetch from the user's own network by a redirect or by DNS.

### 3.2 Hashes

- Every file is checked twice on its way in: the transfer file's SHA-256 against the catalog
  (before unpacking), the unpacked part's SHA-256 against the catalog and the manifest (while
  unpacking). The installer checks size and SHA-256 again as it takes the parts (P's rule
  "verified at install"; one read pass, 4.6 s for the whole PA pack on this PC).
- The manifest itself is fetched by size and hash from the catalog; its fields must equal the
  catalog's (2.3 rule 4).

### 3.3 Signing: room left, not used in format 1

- Today one host, over HTTPS, owned by the owner. A signature would add protection only against
  someone who controls that host (a stolen GitHub account or token). Python's standard library has
  no public-key verification (its crypto modules are `hashlib`, `hmac`, `secrets`; appendix B), and
  Android's platform `Signature` has Ed25519 only from API 33 (ECDSA P-256 from all levels;
  research, appendix B). A signed format now would either break "standard library only" for the
  Python client or force an algorithm choice for little gain.
- Room left: `catalog.json.sig` beside the catalog is reserved (detached signature over the exact
  bytes); a format-2 catalog would make it required, with two public keys in the app (current and
  spare) and the `serial` rule as rollback protection.
- **Rule for later:** before any second host (a mirror, a CDN, someone else's server) is added,
  signing becomes required. Written into section 11.

### 3.4 What a broken or hostile server can and cannot do

Can:

- Refuse service or serve garbage: downloads fail with the texts of 1.4; the installed states,
  routing, search and the overview keep working (nothing at run time depends on the network).
- Learn the phone's IP address, the time, which state's files are fetched, the fixed User-Agent and
  TLS details. Nothing else is sent (rule N3). On GitHub this reaches two hosts: `github.com`
  answers with a redirect to `release-assets.githubusercontent.com`, which serves the file (probed
  here, appendix B). The sheet says so in one line (1.1 step 4).
- Without a signature, serve a fake but self-consistent list and files (with hashes it made up):
  those files are then installed if P's installer accepts them (format, names, bounds, checksums
  against their own manifest). That is the risk 3.3 accepts for one HTTPS host owned by the owner.
- Feed malformed data to native parsers: an accepted pack's files are read by SQLite, Valhalla and
  MapLibre's native code after only the installer's checks (sizes, checksums against their own
  manifest, headers: `docs/pack-format.md` section 3), not after a full validation. A parser bug
  could then crash the app or worse. This is accepted for one owner-controlled HTTPS host under
  decision 6.44, and it is one more reason why signing comes before any second host.
- Waste bandwidth and space up to the sizes it declares: the user sees and confirms the download
  size before anything large moves; files above 8 GiB − 1 and lists above 2 MiB are refused.

Cannot:

- Make the app contact any address outside the catalog's scope (except an HTTPS redirect target
  whose addresses are all public, 3.1), send anything but the GETs of rule N3, or use HTTP in a
  release build.
- Make one state's download replace another state's pack: in format 1 an entry's `id` must be
  `us-` + its `state` (2.3), the manifest must match the entry, and a job whose `pack_id` is
  installed for another state is refused (1.4).
- Put long or odd text on screen: every catalog string has a length limit (2.3), and the app never
  opens the licence URL.
- Write outside the job folder: local file names never come from URLs; they are
  `.transfer-<part name>` (part names `[a-z][a-z0-9_]{0,31}`) and the manifest's `file` names,
  checked with the pack format's plain-file-name rule; the job folder is
  `<downloads>/<pack_id>-<pack_version>` with `pack_id` checked by its regular expression; before
  any write the canonical path is checked to be inside that folder.
- Exhaust memory: everything streams through one 256 KiB buffer; no part is ever in memory; a
  gzip stream may not inflate beyond the part's declared `bytes` (no "zip bomb").
- Ship code: packs are data (PMTiles, SQLite, a Valhalla tile tar, JSON). Nothing in a pack is
  executed or loaded as code; no WebView, no dynamic code loading; packs reach MapLibre, SQLite and
  Valhalla only after P's installer has checked them, exactly as a sideloaded file does today (the
  parser risk above remains).
- Downgrade silently: an update is offered only for a higher `pack_version`, and the user taps it.

## 4. The network rule

### 4.1 The rule (replaces "no INTERNET")

Since batch O1 (2026-11-20, docs/online-design.md section 6) the rule has **one class per service**,
and every class but the packs' is gated by the user's switch. The services today:

| Class | Service | Hosts (N2) | Requests (N3) | Switch (N4) | Socket tag |
|---|---|---|---|---|---|
| `PackHttp` (`:core:download`) | state packs | the configured catalog address | `GET` of the catalog and pack files | none: only downloads the user starts | `0x0B1A` |
| `OsmHttp` (`:app`, `app/online/OsmHttp.kt`) | OpenStreetMap notes (batch O1), the sign-in and edits of a place (batch O2), adding a place (batch O3) | `master.apis.dev.openstreetmap.org` (OSM's test server: API and sign-in) in debug builds and release builds made with test extras; only in the plain release `api.openstreetmap.org` (API) and `www.openstreetmap.org` (sign-in: token and revoke) | `POST /api/0.6/notes.json` (a new note, anonymous or with the user's token); `POST /oauth2/token` (the code of a sign-in this app started, with its PKCE verifier), `POST /oauth2/revoke` (sign-out), `GET /api/0.6/user/details.json` (the user's name); `GET /api/0.6/node|way/<id>.json`, `PUT /api/0.6/changeset/create`, `PUT /api/0.6/node|way/<id>`, `PUT /api/0.6/changeset/<id>/close` (one edit); batch O3: `GET /api/0.6/map.json?bbox=` (OSM's objects in a box about 120 m across around the pressed point, no account: the duplicate check), `PUT /api/0.6/node/create` (a new place, in its own changeset); test builds only, refused for openstreetmap.org: `PUT /api/0.6/node/create` and `DELETE /api/0.6/node/<id>` (test objects) | "Online features" and "Contribute to OpenStreetMap" both on | `0x0B1B` |
| `LookupHttp` (batch O4, not built) | online search | the one lookup host chosen then | the search request | "Find places online" | (then) |

What follows says it for `PackHttp` in full; `OsmHttp` keeps the same shape (no cookies, no
cache, no proxy, no identifiers, public addresses only, tagged sockets, nothing logged of what it
sends) with its own hosts, request and switch, as the table says and its KDoc spells out.

- **N1 — one class per service.** Only `io.github.spysyweeb.beamline.download.net.PackHttp`
  (`android/core/download/src/main/kotlin/io/github/spysyweeb/beamline/download/net/PackHttp.kt`)
  and, for OSM, `io.github.spysyweeb.beamline.app.online.OsmHttp`
  (`android/app/src/main/kotlin/io/github/spysyweeb/beamline/app/online/OsmHttp.kt`) open network
  connections. They are the only files that make requests with OkHttp; the one other
  file allowed to touch OkHttp is I's `android/app/src/main/kotlin/io/github/spysyweeb/beamline/app/OfflineMapHttp.kt`,
  which gives MapLibre a client that refuses every request (N5). On Android each class gets its
  sockets from `TaggingSocketFactory` (`:feature:states`), specified exactly because
  `TrafficStats.tagSocket` applies the *calling thread's* tag and OkHttp creates sockets on its own
  threads (verified, TrafficStats reference):
  - `createSocket()` (no arguments; OkHttp asks for an unconnected socket, connects it itself and
    layers TLS on top): `val old = TrafficStats.getAndSetThreadStatsTag(0x0B1A)`; `val s = Socket()`;
    when the catalog host is local, `network.bindSocket(s)` to the network chosen in 5.2 (verified:
    the socket must not be connected yet); `TrafficStats.tagSocket(s)`; return `s`; restore `old`
    in `finally`.
  - Every other `createSocket` overload connects at once, before it could be bound, and throws
    `UnsupportedOperationException`.
  (`OsmHttp` passes its own tag, `0x0B1B`, and no network to bind.)
- **N2 — where.** `OsmHttp`: only its build's fixed OSM hosts (the table above: the API host and
  the sign-in host); every address a name resolves to must be public. The sign-in page itself
  (`<sign-in host>/oauth2/authorize`) opens in the user's browser, not through `OsmHttp`. `PackHttp`: only the configured catalog URL, URLs that resolve inside its scope (2.3 rule 2),
  and redirect targets allowed by `CatalogAddress.redirectAllowed` (3.1); every address a host
  resolves to must be public, except the debug build's own local server (3.1). Host names are
  resolved by `PackHttp`'s resolver (the system's `InetAddress.getAllByName`; addresses are checked
  before any connection).
- **N3 — what.** `OsmHttp`: only the requests the OSM API defines for what the batches built (the
  table above: a note's text and point in a form body; the sign-in's code, verifier, client id and
  redirect address; an edit's element with its changed tags and a changeset with `comment`,
  `created_by=Beamline <version>`, `source=survey`; batch O3: the box of the map request, which is
  the pressed point grown by about 60 m, and a new node with its tags at the pressed point); headers `User-Agent: Beamline/<version>`,
  `Accept`, `Accept-Encoding: identity`, with an account `Authorization: Bearer <token>`, and the
  transport headers; nothing else of docs/online-design.md section 3: never the phone's position, a
  saved place or an identifier. The token is never logged and never leaves except to the OSM hosts.
  `PackHttp`: `GET` only. Headers: `User-Agent: Beamline-Packs/1` (fixed: no app version, no
  device, no locale), `Accept-Encoding: identity`, and only when needed `Range: bytes=N-`,
  `If-Match: <ETag>` (with a range), `If-None-Match: <ETag>` (the catalog), plus the transport
  headers OkHttp adds (`Host`, `Connection`). No cookies (no cookie jar), no `Authorization`, no
  body, no HTTP cache, no position, no identifiers of any kind. The URLs the app builds (the
  catalog address and the catalog-relative paths) carry no query string; a redirect target is
  followed as the server gives it (GitHub's carries a long signed query string, appendix B) and is
  never stored or logged. App logs name a request by its catalog-relative path only (a pack folder
  and a file name), never the host, a query or a redirect target. A log line holds only such paths,
  pack ids, enum names, sizes and other numbers: never an exception's message, a server's answer or
  the installer's words (those are shown under Details only); an exception is logged by its class
  name. A line that prints such a value carries the `// log-ok:` mark of rule PV2 of
  `docs/architecture-saved-places.md`, the logging rule of every module.
- **N4 — when.** `OsmHttp`: only after the user's Send, Upload, Sign in or Sign out (and the
  edit form's reading of the place it opens on; batch O3: the add form's reading of OSM's map of the
  spot), or for a note, edit or new place the user confirmed that waits
  in the outbox, while the app is open (when it comes to the front or a network appears; no
  background service); and it refuses every request, before any connection, while its switch is
  off (sign-out then only deletes the token on the phone). With
  "Offline only" no request but the pack downloads ever leaves. `PackHttp`: only after a user action: the first-run picker or the States screen opening
  (one catalog fetch, made at every opening unless the list is under a minute old (0.1.32, the owner:
  "check if the user opens the states tab"), then at most every 6 h while it stays open, once more when a network appears
  while it shows a failed list, or "Check again"); a download,
  update or continue the user started, including its automatic continuation (network back, app
  reopened). Nothing at app start, nothing in the background, no analytics, no crash reports.
- **N5 — everything else offline.** MapLibre gets an HTTP client that cannot connect: in
  `Application.onCreate`, right after `MapLibre.getInstance` and before any map view exists, I calls
  `HttpRequestImpl.setOkHttpClient(OfflineMapHttp.client)` (the setter takes a `Call.Factory`;
  verified in the MapLibre Native Android 13.7.1 API reference), where `OfflineMapHttp.client` is
  an `OkHttpClient` whose only application interceptor throws `IOException("map network use is
  off")`. An application interceptor runs before OkHttp makes a connection, so no socket is opened
  (inferred from OkHttp's interceptor design, proved by the JVM test of 4.2 item 3 and the device
  accounting of item 5). `MapLibre.setConnected(false)` stays, but is not relied on: its reference
  describes it only as an override of the connectivity state. The styles the app composes point
  only at local files (`asset://`, `file://`, `pmtiles://asset://`, `pmtiles://file://`);
  Valhalla's configuration never sets `tile_url` (valhalla-mobile attaches its HTTP tile getter
  only when it is set, report 05); Ferrostar, used only through `NavigationController` (never
  `FerrostarCore` or `HttpClientProvider`), was dropped on 2026-11-17 (the source lint still refuses
  both names). Adding `INTERNET` removes the old guard that made any
  stray request fail by itself, so these become checked rules, and the proof is the device's own
  per-app traffic accounting (4.2 item 5), which sees every socket of the app, Java or native,
  ours or a library's.

### 4.2 How it is proved (reviewer and CI)

1. **Merged manifest** (`python android/scripts/check_network_rule.py manifest <merged
   AndroidManifest.xml> --variant debug|release`): the permissions are exactly
   `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, `FOREGROUND_SERVICE`,
   `FOREGROUND_SERVICE_LOCATION`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `INTERNET`,
   `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, plus permissions defined under the app's own id (androidx
   core's `<id>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`); never `ACCESS_WIFI_STATE`,
   `RECEIVE_BOOT_COMPLETED`, `ACCESS_BACKGROUND_LOCATION`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`;
   no `android:usesCleartextTraffic`; a `networkSecurityConfig` only in the debug variant; exactly
   two services with a foreground type (`GuidanceService` location, `DownloadService` dataSync).
   The merged manifest is `app/build/intermediates/merged_manifest/<variant>/process<Variant>MainManifest/AndroidManifest.xml`
   under the build root (I confirms the path for AGP 9.5 once and writes it into the script's help).
2. **Source lint** (`check_network_rule.py scan`; since batch O1 it also allows `app/online/OsmHttp.kt`:
   OkHttp, `javax.net`, `InetAddress`; since batch O2 every web address written in that file must be
   `https://` to one of the three fixed OSM hosts of 4.1, N2). A lint, not a proof: it catches a slip in our
   own sources and cannot see dependencies or code that hides its intent. Over
   `android/**/src/main/**/*.{kt,java}` (build folders excluded), none of these regular expressions
   matches: `\bokhttp3\.`,
   `\bjava\.net\.(URL|URLConnection|HttpURLConnection|Socket|ServerSocket|DatagramSocket|InetAddress|Inet4Address|Inet6Address)\b`,
   `\bimport\s+java\.net\.\*`, `\bimport\s+javax\.net\.\*`, `\.toURL\(\)`, `\.openStream\(\)`,
   `\bjava\.nio\.channels\.(SocketChannel|DatagramChannel|ServerSocketChannel)\b`,
   `\bjava\.net\.http\.`, `\bjavax\.net\.`, `\bandroid\.net\.http\.`, `\borg\.chromium\.net\.`,
   `\bio\.ktor\.`, `\bDownloadManager\b`, `\bWebView\b`, `\bFerrostarCore\b`,
   `\bHttpClientProvider\b`, `\bRuntime\.getRuntime\(\)\.exec\b`, `\bProcessBuilder\b`,
   `setConnected\(\s*true`; except in `download/net/PackHttp.kt` (OkHttp, `javax.net`,
   `InetAddress`), `feature/states/TaggingSocketFactory.kt` (`java.net.Socket`,
   `javax.net.SocketFactory`) and `app/OfflineMapHttp.kt` (`okhttp3.`). (`java.net.URI`, `java.net.SocketTimeoutException` and
   `android.net.ConnectivityManager` do not match and are allowed anywhere.) Test sources are not
   scanned (G's test server uses `com.sun.net.httpserver`). `check_network_rule.py privacy` lists
   the git-tracked files (`git ls-files`, read-only) and fails on any private IPv4 literal other
   than `10.0.2.2`, `127.0.0.1` and the documentation examples `192.168.200.20` and `10.200.0.5`,
   so the home network's address never reaches a commit. A network range written with its prefix
   length (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `127.0.0.0/8`, as `CatalogAddress`'s
   KDoc names them) is not an address and passes (Round G0: those four are in the tree).
3. **JVM tests of the rest of N5** (they test what the app really builds, not templates):
   - `ComposedStyleOfflineTest` (I, `:app` unit tests): composes day and night with
     `StyleComposer.compose` from the app's real style assets, the real style base URL the app
     passes, the bundled overview and an installed test pack; every `sources.*.url`,
     `sources.*.tiles[]`, `glyphs` and `sprite` value starts with `asset://`, `file://`,
     `pmtiles://asset://` or `pmtiles://file://` (an allow-list, so no other scheme passes).
   - `OfflineMapHttpTest` (I, `:app` unit tests): a request through `OfflineMapHttp.client` to a
     local test server fails with `IOException` and the server sees no connection.
   - Engine configuration: the routing track's existing `ModelAndDataTest` asserts that
     `EngineConfig` sets `mjolnir.tile_url` to `""`; P's install test (9.2) asserts the same of the
     `valhalla.json` it writes for the merged set (`RoutingSet.engineConfigFile`).
4. **JVM test** `OsmHttpTest` in `:app` (batch O1): with the switch off a request is refused and
   the test server sees nothing; the note request's exact method, path, body and header set; no
   cookie sent back; only the two fixed hosts, and a private address for their name refused; the
   outbox sends oldest first, keeps each note's number and link, waits when there is no network or
   the switch is off. `OnlineSettingsTest`: the main switch turns every feature on, each turns off alone.
   Batch O2: `OsmEditTest` (every new request refused with the switch off; the token request's
   exact form, no secret; a note under the name only when asked and signed in; an edit's exact
   requests and XML; 409 and changed-meanwhile; edits wait offline or signed out), `OsmAuthTest`
   (PKCE S256, the state check, the token sealed on disk), `WeekHoursTest`.
   **JVM test** `NetworkRuleTest` in `:core:download` (10.1): the exact header set recorded by the
   test server; a URL outside the scope refused with `NetworkRuleRefused` before any connection
   (the server sees nothing); through the test server, a redirect to another port and a sixth hop
   refused; through `redirectAllowed`, https → http refused, https → another public https host
   allowed; with a resolver that answers `10.200.0.5`, `127.0.0.1`, `169.254.1.1` or `fd00::1` for a
   public name, the request is refused before any connection; with a local catalog address, only
   its own host is allowed; no cookie sent back after a `Set-Cookie`.
5. **Device proof** (I's E2E, 10.4 row 10; since batch O1 run once with "Offline only", where the
   rule below must hold, and once with the features on, where the extra bytes must be on tag `0xb1b`
   only and go to the OSM test server only):
   - **Per-app accounting** (the proof): before the run `adb shell dumpsys netstats detail` is read
     once; after the whole E2E (first launch, notice, picker, download, map, search, a simulated
     drive) it is read again. For the app's UID, the bytes on tag `0x0` (all of the UID's traffic)
     minus the bytes on tag `0xb1a` must be no more than 16 KB in each direction. The kernel counts
     every socket of the UID, Java or native, ours or a library's, so untagged traffic there means
     some code other than `PackHttp` used the network (inferred from how netstats accounts tagged
     traffic; the margin covers rounding of the counters).
   - **Packet capture** (emulator): the AVD is started with `-tcpdump <file>` (verified option:
     it captures every packet of the virtual LAN) on an image without Google services, and the app
     runs the same journey from first launch. Every destination in the capture other than the dev
     server (`10.0.2.2:8765`) and DNS must also appear in a baseline capture of the same image left
     idle for the same time without the app.
   - **StrictMode** (an early warning while developing, not the proof): debug builds set
     `StrictMode.VmPolicy.Builder().detectUntaggedSockets().penaltyLog()` (API 26, verified) in
     `Application.onCreate`; the journey must log no "Untagged socket" line. It sees Java sockets
     only, and only in debug builds.

1, 2 run in I's checklist (`docs/architecture.md` 5.4 item 14) and in any later CI; 3 and 4 run with the
JVM tests of `:app`, `:core:packs`/`:core:installed` and `:core:download`; 5 in the E2E.

### 4.3 Where `docs/architecture.md` carries this design

Round G0 made these changes to the main contract (its appendix B records the step); the details
stay here.

| `docs/architecture.md` | What it says about downloads |
|---|---|
| Header, 0 | The goal "choose your state on the first run (list or map); the app downloads and installs it"; Round G0 done; seven parallel tracks, G the seventh. |
| 1, 1.1, 1.2 | The modules `:core:download` (JVM), `:feature:states` (Android library, Compose) and `:demo:states` (not shipped), all G's; their dependency directions; downloads on `Dispatchers.IO` inside `DownloadService`; the States screen is G's, not `:app`'s. |
| 2.2 | The `:core:api` additions of 6.6, inside the whole-file block of `InstalledData.kt` (the excerpts of 6.6 here are checked against the same file). |
| 2.10, 2.11, 2.12 | The States screen (G) reads and writes `Settings.includeAddresses`; manifest v3 `pack_version` and `"source": "download"`; the per-part transfer (this document, `docs/catalog-format.md`) beside the archive; the new format identifiers; the persisted files of 6.5. |
| 2.13 | The list of this document's contract files and data file. |
| 4 | The permission list of 4.2 item 1, rules N1-N5 in short, `OfflineMapHttp`, `DownloadService` beside `GuidanceService`, when `POST_NOTIFICATIONS` is asked, keep-screen-on for file installs only. |
| 5.1 | G's row and definition of done (9.1, 10.1-10.3); P's added work (9.2); I's added work (9.4); report 18. |
| 5.2, 5.3 | The frozen list with this document and G's contract files; `download_rules.json` copied by `sync_data.py --only download_rules.json`; emulator `beamline_g` on port 5574; G's data folders; seven tracks building in one checkout (since the review of Rounds G0 and SP0: build and emulator slots with a memory guard, through the scripts of `tools/round/`). |
| 5.4 | The integration items of 9.4 and the checks of 4.2 items 1-3. |
| 6 | 6.28 (files and downloads), decisions 6.39-6.53 (the index in section 0), questions 7-9 of 6.38 (section 12). |
| 7, 8 | What is left for later (section 11); the catalog for a comma 3X client; the end-to-end set-up through the first-run picker and the download journeys of 10.4. |

## 5. The Android mechanism

### 5.1 Choice

**The app's own foreground service, type `dataSync`, started from a tap in the visible app,
running `DownloadEngine.run()`.** Compared (sources in appendix B):

| Option | Against it here |
|---|---|
| WorkManager long-running worker (the android-download research's pick) | From Android 16, jobs running alongside a foreground service follow the job runtime quota (active bucket: "up to 20 minutes in a rolling 60 minute period"; verified on the power-details page), and Google's long-running-worker page says such workers "can exhaust your app's job quota" and names launching the foreground service directly as the alternative (verified). A 0.5-1 GB download on a slow link runs longer than that. It would also merge `RECEIVE_BOOT_COMPLETED` and a job scheduler into an app that needs neither. |
| User-initiated data transfer job (API 34+) | Google's recommendation for user-started downloads, exempt from quotas; but no Jetpack wrapper, so a second code path below API 34. Kept as the later upgrade (11): the engine does not care who runs it. |
| `DownloadManager` | No streaming hash, no control over resume validators or redirects, its notification text is not ours, and it resumes only with `If-Match` from the original URL (research). |

Where I depart from the android-download research: it recommended WorkManager; the Android 16
quota note on Google's own WorkManager page, verified here, outweighs WorkManager's convenience
for long transfers. I follow its other findings (OkHttp, `If-Match`, re-resolving the URL,
`NOT_METERED`, app-specific external storage, debug-only cleartext).

### 5.2 How it runs

- **Start:** `GetPacks` calls `DownloadEngine.start(...)`, then
  `ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))`,
  always from a tap or from `onAppVisible` (the app is visible, so the start is allowed on Android
  12+, and being in the foreground resets the six-hour budget). If the start still throws
  `ForegroundServiceStartNotAllowedException` (defensive; also when the in-process watcher starts an
  install after a trip while the app is in the background), `GetPacks` runs `engine.run()` in its
  own scope and the progress view adds "Keep Beamline open until the download has finished."
- **The service** (`DownloadService : LifecycleService`, not exported): in `onStartCommand` it
  calls `ServiceCompat.startForeground(this, 4210, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)`
  at once, then launches `engine.run()` in `lifecycleScope` on `Dispatchers.IO`; it observes
  `engine.job` and updates the notification at most once per second; when `engine.needsService`
  turns false it posts the end notification and calls `stopSelf()`. Returns `START_NOT_STICKY`
  (continuation is the app's job, from a visible state). `onTimeout(startId, fgsType)` (API 35+)
  calls `engine.stopForSystem()` and `stopSelf()` (verified requirement: stop within a few seconds).
- **Wake lock:** one `PowerManager.PARTIAL_WAKE_LOCK` "beamline:download", acquired with a 10-minute
  timeout when bytes start moving, re-acquired every 60 s while they move, released when the job
  waits, pauses or ends. No Wi-Fi lock (11).
- **Network:** `AndroidNetworkWatcher` (in `:feature:states`, needs `ACCESS_NETWORK_STATE`)
  publishes `NetworkState(connected, unmetered, detail)` by the kind of catalog host, because
  "validated" means that Android found the *general internet* reachable (verified,
  `NetworkCapabilities` reference), which says nothing about a server on the home network:
  - **Public host** (every release build): `registerDefaultNetworkCallback`; connected = INTERNET
    and VALIDATED; unmetered = NOT_METERED. Unmetered-only means the capability, not "Wi-Fi" (a
    Wi-Fi hotspot can be metered; research).
  - **Loopback host** (debug, USB with `adb reverse`, which carries the traffic over the cable):
    always connected and unmetered.
  - **Private IPv4 host** (debug: the emulator's `10.0.2.2`, the PC on the home Wi-Fi):
    `registerNetworkCallback` for Wi-Fi and Ethernet networks; connected = such a network is up,
    validated or not (a home network whose internet link is down still reaches the PC); unmetered =
    true (the traffic stays in the home network). `PackHttp`'s sockets are bound to that network
    (`Network.bindSocket`, N1), so the request goes over Wi-Fi even when Android has made mobile
    data the default network because the Wi-Fi has no internet. With no Wi-Fi or Ethernet network
    (an emulator on cellular only), the default network is used, validated or not, and unmetered is
    that network's NOT_METERED (the traffic does not stay in the home network then). Inferred from the platform's behaviour; the phone run (10.4) proves it.
  - In debug builds the offline banner adds a second line with `detail`, naming the condition
    that failed ("no Wi-Fi or Ethernet network", "default network not validated", "metered"),
    never an address.
  The engine waits (WAITING NO_NETWORK or NOT_UNMETERED) instead of failing, and goes on when the
  flow changes; each wait ends after `wait_for_network_max_min` (6 h) in PAUSED(SYSTEM_STOPPED),
  so the service never runs for a wait without end.
- **Pause / resume / cancel:** in the app (buttons of 1.1 and 1.4) and from the notification, whose
  actions send explicit intents `ACTION_PAUSE` and `ACTION_USE_MOBILE_DATA` to the service
  (`PendingIntent.FLAG_IMMUTABLE`); Cancel is only in the app, after its confirmation (1.1 step 5).
  Pause stops the transfer (`PackHttp.cancelAll`) and any unpacking at its next buffer, and keeps
  the files; Continue goes back to CHECKING; Cancel deletes the job folder. Whenever the job stops
  short of DONE (a pause of any reason, a failure), the service replaces its ongoing notification
  by a normal low-importance one ("Download paused · 43 %" / "Open Beamline to continue.", or the
  failure's title) before `stopSelf()`.
- **Progress to the UI:** `engine.job: StateFlow<DownloadJob?>` lives in the process (`GetPacks` is
  created once in `AppGraph`); the screen collects it; the service collects it for the
  notification. No broadcast, no IPC.
- **Process death:** the job record and the bytes on disk are the state (6.5). `recover()` runs
  once per process, after the packs folder's first scan: a job whose state is already installed at
  its version (1.4, "Other transitions") becomes DONE; a job that was running becomes
  PAUSED(APP_ENDED), whatever ended the process. The exit reason is not consulted:
  `ApplicationExitInfo.REASON_USER_REQUESTED` covers Force stop and a swipe from Recents alike,
  and before Android 14 also an app update (verified, ApplicationExitInfo reference), so it cannot
  tell "the user wanted the download stopped" from "the user closed the app". A user who did want
  it stopped sees the progress view with Pause and Cancel the next time they open the app.
  **Breaker:** `job.json` counts the process deaths during the job (`deaths_without_progress`); the
  count goes back to 0 whenever the job gains `sync_every_bytes` on disk or a part is finished.
  When it reaches `max_deaths_without_progress` (2), `recover()` makes the job
  PAUSED(KEEPS_STOPPING) instead, which is not continued by itself: a job that crashes the process
  (a bad file, memory) cannot crash the app at every start. `onAppVisible()` continues
  APP_ENDED, SYSTEM_STOPPED and SERVER_UNREACHABLE jobs by starting the service; the other pauses
  wait for a tap. No boot receiver: after a restart the download continues when the user next
  opens the app (1.4).
- **Notification permission:** on Android 13+ asked once, right after the first Download has
  started (1.1 step 5), so the dialog never holds up the start; the service runs without it (the
  notice then shows only in the system's task manager; research), and the progress view says so.
- **Separate from guidance:** `DownloadService` is G's; `GuidanceService` stays I's, type
  `location`. Both may run at once (a download during a trip); the install waits for the trip.

### 5.3 Manifest entries

In `android/feature/states/src/main/AndroidManifest.xml` (G's, merged into `:app` and
`:demo:states`; Round G0 left it empty, and G writes this when it writes `DownloadService`. Found
in Round G0's build: OkHttp's Android AAR (`okhttp-android` 5.3.2, which every Android APK with
`:core:download` on its classpath gets for `PackHttp`) declares `INTERNET` itself, so `:demo:states`
and `:feature:states`'s own test APK hold `INTERNET` already; they are the downloader's hosts, and
`:app` gets it from the same AAR once I wires `:feature:states`):

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- State pack downloads only: docs/architecture-downloads.md, section 4. -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <application>
        <service
            android:name="io.github.spysyweeb.beamline.feature.states.DownloadService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
    </application>
</manifest>
```

`dataSync` has no runtime prerequisite beyond `FOREGROUND_SERVICE_DATA_SYNC` (verified, service
types page). The `:app` manifest change is in 9.3.

## 6. Modules and contracts

### 6.1 Modules

| Module | Folder | Package | Kind | What |
|---|---|---|---|---|
| `:core:download` | `android/core/download` | `P.download`, `P.download.net` | JVM | Catalog format and parser, address rules, `PackHttp`, catalog cache, `DownloadEngine` (state machine, resume, hashing, unpacking, space), `PackHandoff` with the stand-in, `download_rules.json`. Tested on the JVM against a local Range server. |
| `:feature:states` | `android/feature/states` | `P.feature.states` (+ `.map`, `.model`) | Android lib, Compose | `GetPacks` (wiring and in-process controller), `StatesScreen` (first run and States), the drawn US map and its hit test, the sheets, `DownloadService`, the notification, `AndroidNetworkWatcher`, `TaggingSocketFactory`, the file picker for "Use a file instead", the asset `states/us_states.json`. |
| `:demo:states` | `android/demo/states` | `P.states.demo` | Android app, not shipped (id `P.statesdemo`) | Host for G's device checks with a fake `InstalledData` (10.3). |

### 6.2 Contract: catalog, address, HTTP (`:core:download`)

The `// FILE:` blocks of 6.2, 6.3, 6.8 and 6.9 were written into the tree from this document in
Round G0 (`python tools/check_contracts.py --write`) and built by Gradle with the whole project
(`docs/architecture.md` appendix B). Before that, the design's own check had compiled the blocks of
6.2, 6.3 and 6.8 with the Kotlin 2.4.21 compiler against `:core:api` with the additions of 6.6 (165
classes, 64 of them G's, no errors or warnings). The `.kt` files carry the `// FILE:` line as their
first line, as those of `docs/architecture.md` do.

```kotlin
// FILE: core/download/src/main/kotlin/io/github/spysyweeb/beamline/download/Catalog.kt
package io.github.spysyweeb.beamline.download

import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.download.net.CatalogAddress

/** How one file travels (docs/catalog-format.md). [path] is relative to the catalog's address. */
data class TransferFile(
    val path: String,
    /** "identity" (the file as installed) or "gzip" (one gzip member of it). */
    val encoding: String,
    /** Size of the file on the server. */
    val bytes: Long,
    /** SHA-256 of the file on the server, 64 lowercase hex digits. */
    val sha256: String,
)

/** One part of a pack, as named in its manifest (`parts.<name>`). */
data class CatalogPart(
    /** "map", "routing", "routing_index", "search", "addresses", or a later part. */
    val name: String,
    /** The file name inside the pack folder (the manifest's `file`). */
    val file: String,
    /** True for a part the user may skip ("addresses"). */
    val optional: Boolean,
    /** Size and SHA-256 of the file as installed: equal to the manifest's. */
    val bytes: Long,
    val sha256: String,
    val transfer: TransferFile,
    /** The part's own credit (the manifest part's `attribution`); null when it has none. */
    val credit: String?,
)

/** One state pack the server offers. */
data class CatalogPack(
    /** The manifest's pack_id, "us-pa". */
    val id: String,
    /** USPS code, "PA". */
    val state: String,
    val name: String,
    /** The manifest's pack_version (YYYYMMDDhhmm of its build, UTC). */
    val packVersion: Long,
    val manifestVersion: Int,
    /** "2026-11-14": the date of the OpenStreetMap data. */
    val osmSnapshot: String,
    /** The pack's credit line (manifest `attribution`). */
    val credit: String,
    val bounds: Bounds,
    /** The pack's manifest.json, always "identity". */
    val manifest: TransferFile,
    /** In download order: the manifest's parts, small ones first. */
    val parts: List<CatalogPart>,
) {
    fun chosenParts(includeOptional: Boolean): List<CatalogPart> = parts.filter { includeOptional || !it.optional }

    /** Bytes that travel over the network. */
    fun downloadBytes(includeOptional: Boolean): Long = manifest.bytes + chosenParts(includeOptional).sumOf { it.transfer.bytes }

    /** Bytes of the pack folder once everything is unpacked (before the installer merges routing). */
    fun unpackedBytes(includeOptional: Boolean): Long = manifest.bytes + chosenParts(includeOptional).sumOf { it.bytes }

    /** The optional parts' download size; 0 when there are none. */
    fun optionalDownloadBytes(): Long = parts.filter { it.optional }.sumOf { it.transfer.bytes }
}

data class CatalogLicence(
    /** SPDX id, "ODbL-1.0". */
    val id: String,
    val name: String,
    val url: String,
    /** Shown with the download ("Map data © OpenStreetMap contributors, available under ..."). */
    val notice: String,
)

/** NEEDS_NEWER_APP: "Needs a newer version". OLD_FORMAT and BROKEN: listed as "Not available yet". */
enum class Unusable { NEEDS_NEWER_APP, OLD_FORMAT, BROKEN }

/** An entry this app lists but cannot download; [state] and [name] are null when unreadable. */
data class UnusablePack(val id: String, val state: String?, val name: String?, val reason: Unusable, val detail: String)

data class Catalog(
    /** Grows with every catalog the builder writes (its UTC time as YYYYMMDDhhmmss). */
    val serial: Long,
    val generatedUtc: String,
    val licence: CatalogLicence,
    val packs: List<CatalogPack>,
    val unusable: List<UnusablePack>,
)

enum class CatalogRefusal {
    /** `format` is beamline-catalog/N with N > 1. */
    NEWER_FORMAT,
    /** Not JSON, not an object, no or another `format`, a required top-level field missing or too long, a licence URL that is not https. */
    NOT_A_CATALOG,
    /** Larger than DownloadRules.catalogMaxBytes. */
    TOO_LARGE,
    /** A path breaks the path rule or leaves the catalog's scope: the whole list is refused. */
    OUTSIDE_SCOPE,
}

sealed interface CatalogParse {
    data class Ok(val catalog: Catalog) : CatalogParse
    data class Refused(val reason: CatalogRefusal, val detail: String) : CatalogParse
}

object CatalogFormat {
    const val FORMAT = "beamline-catalog/1"
    const val FORMAT_PREFIX = "beamline-catalog/"
    val ENCODINGS: Set<String> = setOf("identity", "gzip")

    /** Longest accepted strings (section 2.3): an entry over a limit is BROKEN, a licence over one refuses the list. */
    const val MAX_NAME = 64
    const val MAX_CREDIT = 300
    const val MAX_LICENCE_NAME = 100
    const val MAX_LICENCE_URL = 200
    const val MAX_NOTICE = 300
    const val MAX_GENERATED_UTC = 32

    /**
     * Parses and checks a catalog (rules in section 2.3 of docs/architecture-downloads.md). Entries this app
     * cannot use go to [Catalog.unusable]: a manifest_version above [maxManifestVersion] or an unknown
     * encoding is NEEDS_NEWER_APP; one below [minManifestVersion] is OLD_FORMAT; a missing,
     * malformed or too long field, a size above [maxFileBytes], an id other than "us-" + the state in
     * lower case, a duplicate id or state is BROKEN. Paths are checked with [CatalogAddress.resolve].
     */
    fun parse(
        bytes: ByteArray,
        address: CatalogAddress,
        minManifestVersion: Int,
        maxManifestVersion: Int,
        maxCatalogBytes: Long,
        maxFileBytes: Long,
    ): CatalogParse = TODO("track G")
}
```

```kotlin
// FILE: core/download/src/main/kotlin/io/github/spysyweeb/beamline/download/net/CatalogAddress.kt
package io.github.spysyweeb.beamline.download.net

/**
 * The configured catalog address and every URL it may lead to (network rule N2).
 *
 * [url] is the catalog's own URL as configured. [scope] is the folder above the catalog's folder
 * (`https://github.com/O/R/releases/download/catalog/catalog.json` has the scope
 * `https://github.com/O/R/releases/download/`; `http://10.0.2.2:8765/catalog/catalog.json` has
 * `http://10.0.2.2:8765/`). Every file a catalog names must resolve inside [scope]. [local]: the
 * host is a loopback or private IPv4 literal or "localhost" (debug builds only): PackHttp then allows
 * that one host's address and AndroidNetworkWatcher uses the rules for a local server (section 5.2).
 */
class CatalogAddress private constructor(val url: String, val scope: String, val cleartext: Boolean, val local: Boolean) {

    /**
     * The absolute URL of a catalog-relative [path]. Throws IllegalArgumentException when [path] does
     * not match [PATH_RULE] or the result is not inside [scope].
     */
    fun resolve(path: String): String = TODO("track G")

    /** Whether [absoluteUrl] is the catalog itself or inside [scope]. */
    fun contains(absoluteUrl: String): Boolean = TODO("track G")

    companion object {
        /**
         * One relative path: an optional single leading "../", then segments of letters, digits, '.',
         * '_' and '-', each starting with a letter or digit. No query, fragment, scheme or "//".
         */
        val PATH_RULE = Regex("^(\\.\\./)?[A-Za-z0-9][A-Za-z0-9._-]*(/[A-Za-z0-9][A-Za-z0-9._-]*)*$")

        /**
         * Null for a blank [raw]: downloads are off in this build. Throws IllegalArgumentException
         * for anything else the rules refuse: a scheme other than https, or http when
         * [allowCleartext] is false or the host is not a private or loopback IPv4 literal; a private
         * or loopback host when [allowCleartext] is false; a user name or password, a query or a
         * fragment in the URL; a path that does not end in ".json".
         */
        fun parse(raw: String, allowCleartext: Boolean): CatalogAddress? = TODO("track G")

        /** "localhost", or an IPv4 literal in 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16 or 127.0.0.0/8. */
        fun isPrivateOrLoopback(host: String): Boolean = TODO("track G")

        /**
         * Whether a redirect from [from] to [to] may be followed, judged on the URLs: from https only
         * to https on a host that is not a private, loopback or link-local (169.254.0.0/16) IPv4
         * literal or an IPv6 literal; from http (a debug build's own server) only to the same scheme,
         * host and port. PackHttp checks the resolved addresses as well (PackHttp.isPublic). The
         * hop count is PackHttp's.
         */
        fun redirectAllowed(from: String, to: String): Boolean = TODO("track G")
    }
}
```

```kotlin
// FILE: core/download/src/main/kotlin/io/github/spysyweeb/beamline/download/net/PackHttp.kt
package io.github.spysyweeb.beamline.download.net

import io.github.spysyweeb.beamline.download.DownloadRules
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import javax.net.SocketFactory

/** One GET. Nothing else is ever sent (network rule N3). */
data class PackRequest(
    /** Absolute URL: [CatalogAddress.url] or a URL inside its scope. */
    val url: String,
    /** When > 0: "Range: bytes=<rangeFrom>-". */
    val rangeFrom: Long = 0,
    /** With a range: "If-Match: <ifMatch>" (a strong ETag from an earlier response for the same file). */
    val ifMatch: String? = null,
    /** For the catalog: "If-None-Match: <ifNoneMatch>". */
    val ifNoneMatch: String? = null,
)

/** The answer after redirects. The caller reads [body] and closes the response. */
class PackResponse(
    val status: Int,
    /** The URL that answered (after redirects); never stored. */
    val finalUrl: String,
    /** A strong ETag, or null (weak or missing). */
    val etag: String?,
    val contentLength: Long?,
    /** From Content-Range on a 206: the first byte. */
    val rangeStart: Long?,
    /** From Content-Range on a 206 or 416: the full size. */
    val totalLength: Long?,
    val body: InputStream,
) : Closeable {
    override fun close() = body.close()
}

/** Thrown before any connection is made for a request or redirect the network rule refuses. */
class NetworkRuleRefused(message: String) : Exception(message)

/** TLS failed because a certificate is not yet valid or has expired by this phone's clock. */
class ClockProblem(cause: Throwable) : java.io.IOException(cause)

/**
 * The only code in the app that opens network connections (network rule N1). It sends exactly
 * the requests of rule N3 to the addresses of rule N2 and follows at most
 * [DownloadRules.maxRedirects] redirects itself, checking each with [CatalogAddress.redirectAllowed].
 * Every host name goes through [resolve]; a request whose host has an address that is not
 * [isPublic] is refused with NetworkRuleRefused before any connection, except the configured
 * host itself when [CatalogAddress.local]. A response with a Content-Encoding other than identity
 * is an error. On Android [socketFactory] tags every socket (TrafficStats tag 0x0B1A) and, for a
 * local host, binds it to the chosen network (rule N1), so the device accounting of section 4.2
 * can tell its traffic apart.
 */
class PackHttp(
    private val address: CatalogAddress,
    private val rules: DownloadRules,
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    /** Host name to addresses: the system resolver; tests answer what they like. */
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) {
    /** Blocking; call on an IO thread. Throws NetworkRuleRefused, or IOException for network errors. */
    fun get(request: PackRequest): PackResponse = TODO("track G")

    /** Cancels every call in flight (pause, cancel, process shutting down); they throw IOException. */
    fun cancelAll(): Unit = TODO("track G")

    companion object {
        /** False for loopback, site-local (private), link-local, any-local, multicast and IPv6 unique-local (fc00::/7) addresses. */
        fun isPublic(a: InetAddress): Boolean = TODO("track G")
    }
}
```

Implementation notes for `PackHttp` (G's, not contract): one `OkHttpClient` with
`followRedirects(false)`, `followSslRedirects(false)`, `retryOnConnectionFailure(true)`, no cache,
`CookieJar.NO_COOKIES`, `proxy(Proxy.NO_PROXY)` (so the address rule applies to the connection
actually made), the timeouts of the rules, `socketFactory(...)`, and an `okhttp3.Dns` that calls
`resolve` and applies the address rule (OkHttp's `Dns` maps a host name to the list of addresses
it will try; javadoc in appendix B); every request built with exactly the N3 headers (setting
`Accept-Encoding: identity` explicitly also stops OkHttp's transparent gzip); a strong ETag is one
without the `W/` prefix. A TLS failure whose cause chain holds
`java.security.cert.CertificateNotYetValidException` or `CertificateExpiredException` is thrown
as `ClockProblem` (the phone's clock, 1.4; that the platform's TLS stack puts these in the chain is
inferred, and the classifier is tested with built exception chains).

### 6.3 Contract: rules, catalog source, jobs, engine, hand-off, space (`:core:download`)

The downloader's rules (G's file `packbuilder/catalog/download_rules.json`, the single source;
copied into `:core:download`'s resources `io/github/spysyweeb/beamline/download/download_rules.json`
by `python android/routing/scripts/sync_data.py --only download_rules.json`, never edited there; G's
tests compare the copy with the source, as N's and M's do for their rule files):

<!-- FILE: packbuilder/catalog/download_rules.json -->
```json
{
  "format": "beamline-download-rules/1",
  "note": "Rules of the state-pack downloader (docs/architecture-downloads.md 2.3, 2.4, 2.6, 5.2 and 6.3; user_agent is the one of network rule N3). Read by android/core/download (copied there by android/routing/scripts/sync_data.py; edit this file, not the copy). Times in seconds unless named _ms or _min; sizes in bytes. Every value is a first guess for the phone test, not a measurement.",
  "user_agent": "Beamline-Packs/1",
  "connect_timeout_s": 15,
  "read_timeout_s": 30,
  "max_redirects": 5,
  "buffer_bytes": 262144,
  "sync_every_bytes": 67108864,
  "progress_every_ms": 500,
  "retry_backoff_s": [2, 5, 10, 30, 60],
  "max_attempts_without_progress": 8,
  "wait_for_network_max_min": 360,
  "max_deaths_without_progress": 2,
  "catalog_max_bytes": 2097152,
  "catalog_refresh_after_min": 360,
  "space_margin_bytes": 200000000,
  "max_file_bytes": 8589934591,
  "hash_retries": 1,
  "speed_window_s": 20
}
```

```kotlin
// FILE: core/download/src/main/kotlin/io/github/spysyweeb/beamline/download/DownloadRules.kt
package io.github.spysyweeb.beamline.download

/**
 * download_rules.json (format beamline-download-rules/1; source: packbuilder/catalog/download_rules.json,
 * copied into this module's resources by android/routing/scripts/sync_data.py). The defaults are the
 * file's values; field = JSON key in the comment. Unknown keys (such as "note") are ignored.
 */
data class DownloadRules(
    val userAgent: String = "Beamline-Packs/1",                      // user_agent
    val connectTimeoutS: Int = 15,                                   // connect_timeout_s
    val readTimeoutS: Int = 30,                                      // read_timeout_s
    val maxRedirects: Int = 5,                                       // max_redirects
    val bufferBytes: Int = 262_144,                                  // buffer_bytes
    val syncEveryBytes: Long = 67_108_864,                           // sync_every_bytes
    val progressEveryMs: Long = 500,                                 // progress_every_ms
    val retryBackoffS: List<Int> = listOf(2, 5, 10, 30, 60),         // retry_backoff_s (the last value repeats)
    val maxAttemptsWithoutProgress: Int = 8,                         // max_attempts_without_progress
    val waitForNetworkMaxMin: Int = 360,                             // wait_for_network_max_min (NO_NETWORK and NOT_UNMETERED)
    val maxDeathsWithoutProgress: Int = 2,                           // max_deaths_without_progress
    val catalogMaxBytes: Long = 2_097_152,                           // catalog_max_bytes
    val catalogRefreshAfterMin: Int = 360,                           // catalog_refresh_after_min
    val spaceMarginBytes: Long = 200_000_000,                        // space_margin_bytes
    val maxFileBytes: Long = 8_589_934_591,                          // max_file_bytes
    val hashRetries: Int = 1,                                        // hash_retries
    val speedWindowS: Int = 20,                                      // speed_window_s
) {
    companion object {
        const val FORMAT = "beamline-download-rules/1"
        fun parse(text: String): DownloadRules = TODO("track G")
        fun bundled(): DownloadRules = TODO("track G")
    }
}

/**
 * The phone's connection as the downloader needs it (from ConnectivityManager on Android, by the
 * kind of catalog host: section 5.2 of docs/architecture-downloads.md).
 */
data class NetworkState(
    /**
     * Public host: a default network with NET_CAPABILITY_INTERNET and NET_CAPABILITY_VALIDATED.
     * Local host (debug): loopback always; a private address when a Wi-Fi or Ethernet network (else
     * the default network) is up, validated or not.
     */
    val connected: Boolean,
    /** Public host: that network has NET_CAPABILITY_NOT_METERED. Local host: true over loopback or a Wi-Fi or Ethernet network; the default network's NOT_METERED when neither exists. */
    val unmetered: Boolean,
    /** For the debug banner: which condition is missing ("default network not validated"); never an address. */
    val detail: String = "",
) {
    companion object {
        val NONE = NetworkState(connected = false, unmetered = false)
    }
}
```

```kotlin
// FILE: core/download/src/main/kotlin/io/github/spysyweeb/beamline/download/CatalogSource.kt
package io.github.spysyweeb.beamline.download

import io.github.spysyweeb.beamline.download.net.CatalogAddress
import io.github.spysyweeb.beamline.download.net.PackHttp
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class CatalogView(
    val catalog: Catalog,
    /** When this list was last fetched or confirmed (304) from the server, epoch ms. */
    val checkedAtMs: Long,
    /** True while only the cached copy is known in this process. */
    val fromCache: Boolean,
)

enum class CatalogProblem {
    /** NetworkState.connected is false. */
    NO_NETWORK,
    /** DNS, connect or read failed, or a timeout. */
    UNREACHABLE,
    /** The server's certificate is not yet valid or has expired by this phone's clock (ClockProblem). */
    CLOCK,
    /** HTTP status other than 200 and 304. */
    SERVER_ERROR,
    /** CatalogRefusal.NEWER_FORMAT. */
    NEWER_FORMAT,
    /** Any other CatalogRefusal. */
    BROKEN,
}

sealed interface CatalogState {
    /** No catalog address in this build. */
    data object Off : CatalogState
    data object NotLoaded : CatalogState
    data class Loading(val cached: CatalogView?) : CatalogState
    data class Ready(val view: CatalogView) : CatalogState
    data class Failed(val problem: CatalogProblem, val cached: CatalogView?) : CatalogState
}

/**
 * The list of states: fetched from [address] with [http], cached in [cacheDir] (catalog.json, the
 * bytes as received, and catalog-meta.json: {"url", "etag", "serial", "checked_ms"}). A cache made
 * for another URL is ignored. Format 1 uses the list it fetched whatever its serial (section 2.3);
 * a serial lower than the cached one is only logged. Fetches only when asked: never on its own.
 */
class CatalogSource(
    private val address: CatalogAddress,
    private val http: PackHttp,
    private val cacheDir: File,
    private val rules: DownloadRules,
    private val minManifestVersion: Int,
    private val maxManifestVersion: Int,
    private val network: StateFlow<NetworkState>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val state: StateFlow<CatalogState> get() = TODO("track G")

    /** Reads the cache only (no network): NotLoaded or Ready(fromCache = true). */
    suspend fun loadCache(): Unit = TODO("track G")

    /**
     * Fetches when [force], when the cache is older than catalog_refresh_after_min, or when its
     * checked_ms lies in the future (the phone's clock was set back); sends If-None-Match with the
     * cached ETag. On failure the cached view stays available in Failed.
     */
    suspend fun refresh(force: Boolean): Unit = TODO("track G")
}
```

```kotlin
// FILE: core/download/src/main/kotlin/io/github/spysyweeb/beamline/download/Job.kt
package io.github.spysyweeb.beamline.download

import io.github.spysyweeb.beamline.api.data.InstallStep

/** Where a download job is (state machine in section 6.4 of docs/architecture-downloads.md). */
enum class JobPhase {
    /** Fetching manifest.json, asking the installer (inspect), checking free space. */
    CHECKING,
    DOWNLOADING,
    /** Not moving, will go on by itself: see [DownloadJob.wait]. */
    WAITING,
    /** Inflating a gzip part into its file. */
    UNPACKING,
    /** Every chosen part is in the job folder and verified; the installer has not taken it yet. */
    READY,
    /** The installer is working on it (its InstallStatus.Running). */
    INSTALLING,
    /** Installed; the job folder is gone. */
    DONE,
    /** Stopped with its files kept; see [DownloadJob.pause]. Continued by a tap, or by itself for some reasons (section 5.2). */
    PAUSED,
    /** Ended without installing, because the files can never be installed: see [DownloadJob.fail]. Files are deleted; job.json stays until [DownloadEngine.dismiss]. */
    FAILED,
}

/** What a job installs. */
enum class JobKind {
    /** A state not on the phone. */
    INSTALL,
    /** A newer build of an installed state (it replaces the old pack atomically). */
    UPDATE,
    /** Only the optional parts (house numbers) of the installed build: InstalledData.startAddOptionalParts. */
    ADD_OPTIONAL,
}

enum class WaitReason {
    /** NetworkState.connected is false; ends after wait_for_network_max_min. */
    NO_NETWORK,
    /** Only a metered network and the user has not allowed mobile data for this job; ends after wait_for_network_max_min. */
    NOT_UNMETERED,
    /** A network error; the next attempt comes after [DownloadJob.retryAtMs]. */
    RETRYING,
    /** The installer is busy with another install (a file the user picked). */
    INSTALLER_BUSY,
    /** Changes are held (InstalledSet.changesHeldBy, "Not while guiding"). */
    TRIP_IN_PROGRESS,
}

enum class PauseReason {
    /** The user tapped Pause, or the installer reported Cancelled. */
    USER,
    /** Not enough free space ([DownloadJob.needBytes], [DownloadJob.freeBytes]), before or during the download. */
    NO_SPACE,
    /** The system stopped the service (Android 15+ six-hour limit) or a wait passed wait_for_network_max_min. Continued at the next app start. */
    SYSTEM_STOPPED,
    /** The app's process ended while the job was running, for any reason. Continued at the next app start. */
    APP_ENDED,
    /** The process ended max_deaths_without_progress times during the job without progress. Not continued by itself. */
    KEEPS_STOPPING,
    /** max_attempts_without_progress attempts (network errors, timeouts, 5xx, 429) gained no byte. Continued at the next app start. */
    SERVER_UNREACHABLE,
    /** The server broke the rules (a refused redirect, too many hops, a body longer than declared, a Content-Encoding, another 4xx). */
    BAD_RESPONSE,
    /** A part's SHA-256 was wrong after hash_retries + 1 downloads; that part's files are deleted, the verified parts kept. */
    DAMAGED,
    /** TLS failed on the certificate's dates (ClockProblem): the phone's date or time is wrong. */
    CLOCK,
}

enum class FailReason {
    /** The server no longer offers this pack and has no newer one of the same id. */
    NO_LONGER_OFFERED,
    /** The pack's manifest.json disagrees with the catalog entry, or its pack_id is installed for another state (found at CHECKING). */
    LIST_MISMATCH,
    /** The installer refused the pack ([DownloadJob.detail] has its words, shown under Details). */
    REJECTED,
    /** The pack's manifest version, a part's encoding or a format is newer than this app reads. */
    NEEDS_NEWER_APP,
}

data class JobProgress(
    /** Bytes of the chosen transfer files on disk now. */
    val doneBytes: Long,
    /** CatalogPack.downloadBytes(includeOptional). */
    val totalBytes: Long,
    /** The part being fetched or unpacked; null between parts. */
    val part: String?,
    /** Average over the last speed_window_s; null while unknown or not moving. */
    val bytesPerSecond: Long?,
)

data class DownloadJob(
    val packId: String,
    val packVersion: Long,
    val name: String,
    /** USPS code. */
    val state: String,
    val osmSnapshot: String,
    val kind: JobKind,
    val includeOptional: Boolean,
    val allowMetered: Boolean,
    /** Started from the first-run screen: the map opens on the state once it is installed. */
    val firstRun: Boolean,
    val phase: JobPhase,
    val progress: JobProgress,
    val wait: WaitReason? = null,
    /** With RETRYING: when the next attempt starts, epoch ms. */
    val retryAtMs: Long? = null,
    val pause: PauseReason? = null,
    val fail: FailReason? = null,
    /** With REJECTED: the installer's words; with BAD_RESPONSE: the rule broken. Shown under Details only: never as the main text, never logged (rule N3). */
    val detail: String? = null,
    /** With PAUSED(NO_SPACE): bytes needed in all and bytes free. */
    val needBytes: Long? = null,
    val freeBytes: Long? = null,
    /** With INSTALLING: the installer's step and its byte counts. */
    val installStep: InstallStep? = null,
    val installDoneBytes: Long = 0,
    val installTotalBytes: Long = 0,
)

enum class StartRefusal {
    /** No catalog address in this build. */
    DOWNLOADS_OFF,
    /** Another job is not finished (one job at a time). */
    ANOTHER_JOB,
    /** The entry is in Catalog.unusable. */
    NEEDS_NEWER_APP,
    /** ADD_OPTIONAL while the installer cannot add parts, or the installed build differs from the entry. */
    NOT_SUPPORTED,
}

sealed interface StartResult {
    data object Started : StartResult
    data class Refused(val why: StartRefusal) : StartResult
}
```

```kotlin
// FILE: core/download/src/main/kotlin/io/github/spysyweeb/beamline/download/DownloadEngine.kt
package io.github.spysyweeb.beamline.download

import io.github.spysyweeb.beamline.api.data.InstalledData
import io.github.spysyweeb.beamline.download.net.PackHttp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Downloads one state pack at a time into [root] (`<external files>/downloads/`, the volume of the
 * packs folder) and hands it to the installer through [handoff]. Persists everything it needs to
 * go on after process death in `<root>/<pack_id>-<pack_version>/job.json` (section 6.5); the bytes
 * themselves are the truth for progress. UI-free and Android-free: [network] and [freeBytes] come
 * from the platform; [run] is called by the Android service.
 */
class DownloadEngine(
    private val root: File,
    private val http: PackHttp,
    private val catalogs: CatalogSource,
    private val handoff: PackHandoff,
    private val installed: InstalledData,
    private val network: StateFlow<NetworkState>,
    private val freeBytes: (File) -> Long,
    private val rules: DownloadRules,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The one job (null when there is none). Updated at most every progress_every_ms while bytes move. */
    val job: StateFlow<DownloadJob?> get() = TODO("track G")

    /**
     * True while [job] is in CHECKING, DOWNLOADING, UNPACKING, READY (not held), INSTALLING, or
     * WAITING for NO_NETWORK, NOT_UNMETERED (each at most wait_for_network_max_min) or RETRYING
     * (at most max_attempts_without_progress attempts): the service must run.
     */
    val needsService: StateFlow<Boolean> get() = TODO("track G")

    /**
     * Once per process, before any other call: waits for the installer's first scan
     * (InstalledSet.scanned), reads job.json files, deletes folders of finished or unreadable jobs
     * and every folder in [root] that is not a job (a FAILED job keeps its job.json until
     * [dismiss]). A job whose pack is installed
     * at its pack_version or newer with every part it chose becomes DONE (folder deleted). A job
     * that was running gets one more death counted: PAUSED(KEEPS_STOPPING) when the count reaches
     * max_deaths_without_progress, else PAUSED(APP_ENDED). The exit reason is not consulted.
     */
    suspend fun recover(): Unit = TODO("track G")

    /**
     * Creates the job (phase CHECKING) and persists it; the caller then starts the service.
     * ADD_OPTIONAL fetches the manifest and the optional parts only, and is refused (NOT_SUPPORTED)
     * unless the installer adds parts and the installed pack has [pack]'s pack_version.
     */
    fun start(pack: CatalogPack, kind: JobKind, includeOptional: Boolean, allowMetered: Boolean, firstRun: Boolean): StartResult = TODO("track G")

    /**
     * Works on the job until it is DONE, FAILED or PAUSED, or WAITING for TRIP_IN_PROGRESS or
     * INSTALLER_BUSY (then the in-process watcher of GetPacks finishes it). Returns at once when there
     * is nothing to do. Cancelling the calling coroutine stops the work and leaves the job as it is
     * on disk (the service's end, the process's end).
     */
    suspend fun run(): Unit = TODO("track G")

    /**
     * PAUSED(USER): stops the transfer and any unpacking at its next buffer (a half-inflated part
     * file is deleted, its verified transfer file kept); keeps the files. Not while INSTALLING.
     */
    fun pause(): Unit = TODO("track G")

    /** From PAUSED back to CHECKING; [allowMetered] non-null changes the job's choice. */
    fun resume(allowMetered: Boolean? = null): Unit = TODO("track G")

    /** "Use mobile data" for this job (WAITING NOT_UNMETERED goes on at once). */
    fun allowMetered(): Unit = TODO("track G")

    /** Stops and deletes the job folder; [job] becomes null. The UI confirms first. Not possible while INSTALLING. */
    fun cancel(): Unit = TODO("track G")

    /** Forgets a DONE or FAILED job ([job] becomes null); a FAILED job's folder (job.json only) is deleted. */
    fun dismiss(): Unit = TODO("track G")

    /** The service got Service.onTimeout (Android 15+): PAUSED(SYSTEM_STOPPED) within a second. */
    fun stopForSystem(): Unit = TODO("track G")
}
```

```kotlin
// FILE: core/download/src/main/kotlin/io/github/spysyweeb/beamline/download/PackHandoff.kt
package io.github.spysyweeb.beamline.download

import io.github.spysyweeb.beamline.api.data.ArchiveInfo
import io.github.spysyweeb.beamline.api.data.InstalledData
import io.github.spysyweeb.beamline.api.data.PackArchive
import io.github.spysyweeb.beamline.api.data.PackFolder
import java.io.File
import java.io.InputStream

/** Gives a finished download to the installer (track P's InstalledData). */
interface PackHandoff {
    /** True: the installer moves the parts (no second copy). False: it copies them from a stream. */
    val moves: Boolean

    /** The installer's reading of [folder]'s manifest.json; nothing is copied. */
    suspend fun inspect(folder: File, label: String): ArchiveInfo

    /** Starts the install of [folder]; false when the installer is busy or changes are held. */
    fun start(folder: File, label: String, includeOptional: Boolean): Boolean

    /** Adds the optional parts in [folder] to the installed pack (JobKind.ADD_OPTIONAL); false as [start]. */
    fun startAddOptional(folder: File, label: String): Boolean
}

/** P's folder install (InstalledData.startInstallFromFolder): parts are moved. */
class FolderHandoff(private val installed: InstalledData) : PackHandoff {
    override val moves: Boolean get() = true
    override suspend fun inspect(folder: File, label: String): ArchiveInfo =
        installed.inspect(PackArchive(label) { VirtualTar.stream(listOf("manifest.json" to File(folder, "manifest.json"))) })
    override fun start(folder: File, label: String, includeOptional: Boolean): Boolean =
        installed.startInstallFromFolder(PackFolder(label, folder.absolutePath), includeOptional)
    override fun startAddOptional(folder: File, label: String): Boolean =
        installed.startAddOptionalParts(PackFolder(label, folder.absolutePath))
}

/**
 * The stand-in until P's folder install exists: the frozen archive install, fed a tar made on the
 * fly from the folder (manifest.json first, then the parts in manifest order). The installer copies,
 * so the parts need their space twice; [Space] counts that.
 */
class ArchiveStreamHandoff(private val installed: InstalledData) : PackHandoff {
    override val moves: Boolean get() = false
    override suspend fun inspect(folder: File, label: String): ArchiveInfo =
        installed.inspect(PackArchive(label) { VirtualTar.stream(listOf("manifest.json" to File(folder, "manifest.json"))) })
    override fun start(folder: File, label: String, includeOptional: Boolean): Boolean =
        installed.startInstall(PackArchive(label) { VirtualTar.stream(VirtualTar.packFiles(folder)) }, includeOptional)
    /** The frozen archive install cannot add a part: the UI hides "Add house numbers" (1.2). */
    override fun startAddOptional(folder: File, label: String): Boolean = false
}

fun handoffFor(installed: InstalledData): PackHandoff =
    if (installed.installsFromFolder) FolderHandoff(installed) else ArchiveStreamHandoff(installed)

/** An uncompressed ustar stream made from files on disk while it is read (nothing is written). */
object VirtualTar {
    /**
     * ustar: per file a 512-byte header (name as given, mode 0644, uid and gid 0, empty user and group
     * names, size and mtime in octal, type '0', magic "ustar\u000000"), the bytes, zero padding to 512;
     * then two zero blocks. Names must be plain file names of at most 100 bytes.
     */
    fun stream(files: List<Pair<String, File>>): InputStream = TODO("track G")

    /** manifest.json, then every part file the manifest names that exists in [folder], in manifest order. */
    fun packFiles(folder: File): List<Pair<String, File>> = TODO("track G")
}
```

```kotlin
// FILE: core/download/src/main/kotlin/io/github/spysyweeb/beamline/download/Space.kt
package io.github.spysyweeb.beamline.download

import io.github.spysyweeb.beamline.api.data.ArchiveInfo

/**
 * How many more bytes a download needs, from now until its install has finished (section 2.6 of
 * docs/architecture-downloads.md). R = the manifest plus the parts the job fetches, unpacked; G = the
 * largest gzip transfer file among them (kept while it is unpacked); D = what is already in the job
 * folder; M = space_margin_bytes. The download's own peak is R + G + M; the install's is what the
 * installer needs beyond the parts when it moves them, or its whole need when it copies them.
 */
object Space {
    /** The parts a job fetches: ADD_OPTIONAL only the optional ones. */
    fun parts(pack: CatalogPack, kind: JobKind, includeOptional: Boolean): List<CatalogPart> =
        if (kind == JobKind.ADD_OPTIONAL) pack.parts.filter { it.optional } else pack.chosenParts(includeOptional)

    /**
     * Before the manifest is fetched (the sheets). [routingSetBytes] is the merged routing tile set
     * on the phone now: the installer writes a new one beside it, as large as it plus the new
     * routing part (an upper bound for an update, whose old tiles leave the new set).
     */
    fun estimate(pack: CatalogPack, kind: JobKind, includeOptional: Boolean, moves: Boolean, routingSetBytes: Long, onDiskBytes: Long, marginBytes: Long): Long {
        val parts = parts(pack, kind, includeOptional)
        val r = pack.manifest.bytes + parts.sumOf { it.bytes }
        val routing = parts.filter { it.name == "routing" }.sumOf { it.bytes }
        val newRoutingSet = if (routing > 0) routingSetBytes + routing else 0
        val install = (if (moves) 0 else r) + newRoutingSet + marginBytes
        return need(r, largestGzip(parts), onDiskBytes, marginBytes, install)
    }

    /** At CHECKING, with the installer's own figure ([info] from PackHandoff.inspect, which includes the routing merge's peak). */
    fun exact(pack: CatalogPack, kind: JobKind, includeOptional: Boolean, moves: Boolean, onDiskBytes: Long, marginBytes: Long, info: ArchiveInfo): Long {
        val parts = parts(pack, kind, includeOptional)
        val r = pack.manifest.bytes + parts.sumOf { it.bytes }
        val install = if (kind == JobKind.ADD_OPTIONAL) marginBytes else {
            val installerNeed = info.neededBytes - if (includeOptional) 0 else (info.addressesBytes ?: 0)
            if (moves) maxOf(0, installerNeed - r) else installerNeed
        }
        return need(r, largestGzip(parts), onDiskBytes, marginBytes, install)
    }

    private fun largestGzip(parts: List<CatalogPart>): Long =
        parts.filter { it.transfer.encoding == "gzip" }.maxOfOrNull { it.transfer.bytes } ?: 0

    private fun need(r: Long, g: Long, d: Long, m: Long, install: Long): Long =
        maxOf(0, r - d) + maxOf(g + m, install)
}
```

Worked example (PA, house numbers left out because their size is unknown, moves): R = 1,033.8 MB,
G = 240.4 MB, M = 200 MB. First state on the phone (S = 0): estimate I = 634.4 + 200 = 834.4 MB;
need = 1,033.8 + max(440.4, 834.4) = **1.87 GB** free before starting ("Needs about 1.9 GB
free"). With the stand-in (copies) it would be 1,033.8 + 1,033.8 + 634.4 + 200 = 2.90 GB. The same
pack added when a merged tile set of 634.4 MB is already on the phone: I = 634.4 + 634.4 + 200 =
1,468.9 MB, need = **2.50 GB**.

### 6.4 The download state machine

```
              start()                    every part done and verified
   (none) ───────────► CHECKING ──► DOWNLOADING ⇄ UNPACKING ──────────► READY ──► INSTALLING ──► DONE
                          │  ▲          │   ▲                            │  ▲          │
            space short   │  │ resume() │   │ network back / Wi-Fi /     │  │ hold     │ installer
            ──────────────┘  │          ▼   │ retry time / allowMetered  ▼  │ cleared  ▼ refused
         PAUSED(NO_SPACE)    │       WAITING(NO_NETWORK, NOT_UNMETERED,  WAITING(TRIP_IN_PROGRESS,
                             │               RETRYING)                    INSTALLER_BUSY)    FAILED(REJECTED)
   files kept:  PAUSED(USER, NO_SPACE, SYSTEM_STOPPED, APP_ENDED, KEEPS_STOPPING, SERVER_UNREACHABLE,
                       BAD_RESPONSE, DAMAGED, CLOCK) ◄── pause(), ENOSPC, onTimeout, wait > 6 h, recover(),
                                                         no byte in 8 attempts, a broken rule, 2nd bad hash, clock
   files gone:  FAILED(NO_LONGER_OFFERED, LIST_MISMATCH, NEEDS_NEWER_APP) ◄── from CHECKING, DOWNLOADING
                cancel() (confirmed) from any phase but INSTALLING ──► (none), folder deleted
                already installed (recover, CHECKING) ──► DONE, folder deleted
```

Transitions in words (the engine's tests, 10.1, have one case per line):

1. `start` → CHECKING, `job.json` written. Refused (no job created) when downloads are off,
   another job exists, the entry is unusable, or an ADD_OPTIONAL job is not possible.
2. CHECKING: wait for a network (WAITING NO_NETWORK); if the pack is already installed at the
   job's version or newer with every part the job chose → DONE (folder deleted); GET the manifest
   (404/410 as in 3; a certificate-date failure → PAUSED(CLOCK)); check it against the catalog and
   that its `pack_id` is not installed for another state (FAILED LIST_MISMATCH on a difference);
   `handoff.inspect` (FAILED NEEDS_NEWER_APP when the problems name a newer version, FAILED
   REJECTED for other problems); `Space.exact` against `freeBytes(root)` (PAUSED NO_SPACE when
   short) → DOWNLOADING.
3. DOWNLOADING: per part as in 2.4. Metered network without `allowMetered` → WAITING(NOT_UNMETERED);
   no network → WAITING(NO_NETWORK); an IOException, a timeout or a 5xx/429 → WAITING(RETRYING)
   with the next back-off step (reset after any byte gained); `max_attempts_without_progress`
   attempts without a byte gained → PAUSED(SERVER_UNREACHABLE). `ClockProblem` → PAUSED(CLOCK).
   404/410 → re-fetch the catalog: a newer `pack_version` of the same id → a new job for it (old
   folder deleted; kind, `include_optional`, `allow_metered` and `first_run` carried over); else
   FAILED(NO_LONGER_OFFERED). A refused redirect, too many hops, a body longer than declared, a
   `Content-Encoding` or another 4xx → PAUSED(BAD_RESPONSE). ENOSPC → PAUSED(NO_SPACE). Hash
   mismatch → that part again from 0 once, then PAUSED(DAMAGED) with that part's files deleted and
   the verified parts kept.
4. UNPACKING (gzip parts, right after their transfer file is verified) → back to DOWNLOADING for
   the next part; output hash mismatch handled like 3. `pause()` stops it at the next buffer and
   deletes the half-written part file (the transfer file stays).
5. READY: `changesHeldBy != null` → WAITING(TRIP_IN_PROGRESS); `handoff.start` (or
   `handoff.startAddOptional` for ADD_OPTIONAL) false while `install` is Running →
   WAITING(INSTALLER_BUSY); true → INSTALLING.
6. INSTALLING follows `InstalledData.install`: Running → step and bytes copied into the job;
   Done → DONE (job folder deleted, `installed.dismissInstallResult()` called, a "show on map"
   request recorded when `firstRun`); Failed → FAILED(REJECTED) with its message as `detail`, or
   PAUSED(NO_SPACE) with files kept when `freeBytes` is below the exact need; Cancelled →
   PAUSED(USER).
7. WAITING(NO_NETWORK) or WAITING(NOT_UNMETERED) longer than `wait_for_network_max_min` →
   PAUSED(SYSTEM_STOPPED).
8. `recover()` (5.2): already installed → DONE; a job that was running → PAUSED(APP_ENDED), or
   PAUSED(KEEPS_STOPPING) at `max_deaths_without_progress`. `onAppVisible` resumes APP_ENDED,
   SYSTEM_STOPPED and SERVER_UNREACHABLE; every other pause waits for Continue.

### 6.5 What is persisted, where

| What | Where | Written |
|---|---|---|
| Job record | `<downloads>/<pack_id>-<pack_version>/job.json`, format `beamline-download-job/1`: `catalog_url`, `catalog_serial`, the whole catalog entry (JSON as received), `kind`, `include_optional`, `allow_metered`, `first_run`, `phase`, `wait`, `pause`, `fail`, `detail`, `deaths_without_progress`, `bytes_at_last_death`, per part `{"etag", "transfer_verified", "verified", "hash_failures"}` | Atomically (temporary file, fsync, rename) on every change of phase, reason, ETag, verified flag or death count; never for byte progress |
| Bytes | `<downloads>/<pack_id>-<pack_version>/.transfer-<part>`, `<file>`, `manifest.json` | Streamed; fsync every `sync_every_bytes` and at the end of each file; the file length is the progress |
| Catalog cache | `<files>/getpacks/catalog.json` (bytes as received), `catalog-meta.json` (`url`, `etag`, `serial`, `checked_ms`) | After every accepted fetch |
| First-run choice, notification asked, pending "show on map" | `<files>/getpacks/ui.json` (`{"first_run_picker_done": true, "notifications_asked": true, "show_on_map": "us-pa"}`) | Choice: on Not now, Download, Use a file, or when the first scan finds any pack; cleared when a first-run download is cancelled or its failure dismissed. Notification: on the permission request. `show_on_map`: when a first-run job is DONE; removed when I reports the camera moved (6.9) |

`<downloads>` = `getExternalFilesDir(null)/downloads` (same volume as `packs/`, so the installer's
move is a rename; removed with the app). A job folder never outlives its job: DONE and cancelled
jobs delete it; a FAILED job deletes its downloaded files at once and keeps only `job.json` (a few
KB) until the user dismisses the message, so the message survives a restart; `recover` deletes
folders without a readable `job.json`. Only one job folder may exist; a second one found by
`recover` (a crash between switching versions) is deleted.

### 6.6 What track P provides (contract), and the stand-in

**Additions to `:core:api`** (made in Round G0; additive; source-compatible:
`InstalledLibrary` compiles unchanged because the new members have defaults). In
`core/api/src/main/kotlin/io/github/spysyweeb/beamline/api/data/InstalledData.kt`:

```kotlin
// EXCERPT: core/api/src/main/kotlin/io/github/spysyweeb/beamline/api/data/InstalledData.kt
    val verified: Boolean,
    val bytesOnDisk: Long,
    /**
     * The manifest's pack_version (pack format 3: the build time as YYYYMMDDhhmm, UTC); null for a
     * pack without one. A higher number is a newer build of the same pack_id.
     */
    val packVersion: Long? = null,
)
```

```kotlin
// EXCERPT: core/api/src/main/kotlin/io/github/spysyweeb/beamline/api/data/InstalledData.kt
class PackArchive(val label: String, val open: () -> InputStream)

/**
 * A pack already on this device as a folder (the downloader's): manifest.json and the part files
 * side by side, on the same volume as the packs folder. [dir] is an absolute path.
 */
class PackFolder(val label: String, val dir: String)
```

```kotlin
// EXCERPT: core/api/src/main/kotlin/io/github/spysyweeb/beamline/api/data/InstalledData.kt
    /** Empty when it can be installed. Free space is never listed here: compare [neededBytes] with [freeBytes]. */
    val problems: List<String>,
```

```kotlin
// EXCERPT: core/api/src/main/kotlin/io/github/spysyweeb/beamline/api/data/InstalledData.kt
    /** Starts an install; false when one is running or changes are held. */
    fun startInstall(archive: PackArchive, includeAddresses: Boolean): Boolean

    /** True when [startInstallFromFolder] is implemented (track P). */
    val installsFromFolder: Boolean get() = false

    /**
     * Starts an install from [folder] that moves its part files into the pack (rename, no copy)
     * after checking each one's size and SHA-256 against the folder's manifest.json, then goes on as
     * [startInstall] does (merge, checks, rename into place, a new generation). The free-space check
     * counts only bytes that are not already in [folder]. On failure or cancel every moved file is
     * moved back into [folder]. Progress and result in [install]; install-record.json says
     * "source": "download". False when an install is running, changes are held, or
     * [installsFromFolder] is false.
     */
    fun startInstallFromFolder(folder: PackFolder, includeAddresses: Boolean): Boolean = false

    /** True when [startAddOptionalParts] and [removeOptionalParts] are implemented (track P). */
    val changesOptionalParts: Boolean get() = false

    /**
     * Adds the optional parts found in [folder] (today only "addresses") to the installed pack whose
     * pack_id and pack_version equal those of [folder]'s manifest.json. Each part file is checked
     * (size and SHA-256 against that manifest) and moved into the pack folder; then the install
     * record (skipped_parts) is replaced atomically and a new generation is published with the
     * pack's addressesFile and attribution updated. No other file of the pack is touched. Progress
     * and result in [install]. False when an install is running, changes are held, no such pack is
     * installed, or [changesOptionalParts] is false.
     */
    fun startAddOptionalParts(folder: PackFolder): Boolean = false

    /**
     * Removes the optional parts of the installed pack [packId]: the install record first, a new
     * generation (search closes the old address handle, rule 3 of section 2.2), then the files.
     * Returns null when removed, else why not (plain words); refused while changes are held.
     */
    suspend fun removeOptionalParts(packId: String): String? = "This version of Beamline can't remove house numbers."
```

(The excerpts show the changes. The whole file is the `// FILE:` block of `InstalledData.kt` in
`docs/architecture.md` 2.2, which carries them, the `problems` KDoc line "Empty when it can be
installed." included as replaced above; `tools/check_contracts.py` checks each excerpt here as one
unbroken run of the tree's file, so they cannot drift apart. Round G0 built them with the rest, and
`:core:api`'s `InstallerHandoffTest` checks that an installer with only Round 0's members compiles
and answers "not supported" through the defaults, without reaching the archive install.)

**P's side during the round** (added to P's deliverables and definition of done, 9.2):

1. Manifest v3 top-level `pack_version` (integer `YYYYMMDDhhmm` of `created_utc`), written by
   `packbuilder/pack/assemble.py`, read into `PackInfo.packVersion`.
2. `InstalledLibrary.installsFromFolder = true` and `startInstallFromFolder`: the same flow as the
   archive install, with "copy and hash into `.staging-*`" replaced by "move (rename) each part file
   from the folder into `.staging-*`, then hash it there (read only)"; skipping `addresses` when
   asked (an `addresses` file in the folder is left there); on any failure or cancel before the
   final rename, the moved files are renamed back into the folder; `install-record.json`
   `"source": "download"`. A crash between "moved" and "committed" may lose the moved parts (P's
   `cleanUp` deletes `.staging-*` as today): G's `recover` sees missing files and downloads them
   again (accepted: rare, costs bandwidth, never a broken pack). A crash after the rename into
   place and before G's job ended is G's case: `recover` finds the pack installed and ends the job
   without fetching anything (6.4).
3. `inspect` never lists free space among `problems` (only versions, names, bounds, held changes).
4. JVM tests: folder install of the real PA folder (files moved, none copied: the folder holds no
   part file afterwards and the installed files keep their last-modified times, which a rename
   keeps and a copy does not), a failure in the middle moves the files back, `packVersion` read,
   space counted without the moved parts.
5. `changesOptionalParts = true`, `startAddOptionalParts` and `removeOptionalParts`: the addresses
   file is moved into the existing pack folder (adding a file changes no file in place), then
   `install-record.json` is replaced by rename and a generation published; removal does the
   reverse (record and generation first, then the file is deleted, as a removal does with a
   folder). JVM tests: PA installed without addresses, then added from a folder holding only the
   manifest and `addresses.sqlite` (credit gains the address sources, nothing else in the pack
   folder changes), then removed (credit back, file gone), each refused while held; a folder whose
   manifest has another `pack_version` refused.

**Stand-in until P delivers** (G uses it in its tests and demo; `handoffFor` picks it while
`installsFromFolder` is false): `ArchiveStreamHandoff` feeds the frozen `startInstall` a tar built
on the fly by `VirtualTar` from the job folder; `Space` counts the copy. Everything else is the
same. When P's folder install lands, nothing in G changes.

### 6.7 How the picker and the States screen are built

G's internal design (not frozen; G may change it): `StatesPresenter.present(StatesInput):
StatesUi` is a pure function of the shapes' names, `CatalogState`, `InstalledSet`, `InstallStatus`,
`DownloadJob?`, `NetworkState`, the selection, the house-number choice, free space, `moves`, the
search text, the clock and the zone; it makes every text of section 1, the map chip's text, and
the per-state status (`INSTALLED`, `UPDATE_AVAILABLE`, `IN_PROGRESS`, `AVAILABLE`,
`NOT_AVAILABLE`, `NEEDS_NEWER_APP`, `LOADING`, `UNKNOWN`). A sketch of its types (not a
contract, not compiled): `StateRowUi(code, name,
status, line, builtLine, houseNumbersLine, houseNumbersAction, stateDescription, progress, packId)`,
`SheetUi(code, title, lines, houseNumbers, sourcesLine, connectionLine, primary, secondary,
creditLine, privacyLine)`, `JobUi(title, line, hint, progress, actions, notificationsLine)`,
`StatesUi(mode, title, intro, banner, job, onPhone, available, notAvailable, mapStatus, selected,
sheet, dialog, query, checkedLine, bottomBar, mapHint)`, events `Select, CloseSheet, Query,
HouseNumbers, Download(code, allowMetered), Pause, Continue, UseMobileData, Cancel,
ConfirmCancel, Dismiss, FreeUpSpace, CheckAgain, Remove(packId), ConfirmRemove,
AddHouseNumbers(packId), RemoveHouseNumbers(packId), ConfirmRemoveHouseNumbers, Details,
TurnOnNotifications, UseFile, NotNow, OpenMap, TapOutside`.

Status rules: a state is INSTALLED when an installed pack has that `state`; UPDATE_AVAILABLE when
the catalog has a newer build for that pack id (1.3); IN_PROGRESS while the job is for it;
AVAILABLE when the catalog has a usable entry; NEEDS_NEWER_APP when only an unusable entry with
that reason exists; NOT_AVAILABLE when the catalog is loaded and has none, or only an OLD_FORMAT
or BROKEN entry; LOADING while the first fetch runs with no cached list; UNKNOWN when no list is
known and none is being fetched (offline, failed). A catalog entry whose state is not in the
shapes (a later territory) is listed under "Available" without a map shape.

Accessibility (the list is the full path; research, Android and Compose guidance in appendix B):
rows at least 48 dp, one row per state with `mergeDescendants`, a unique description (the state
name), `stateDescription` from the row, section titles as `heading()`, the progress text in a
`liveRegion = Polite`, the sheet with `paneTitle`; the map card has one description, "Map of the
United States. The list below has every state.", and stays tappable; status never by colour alone
(outline style and the list line say it too); texts reflow at 200 % font size. The bottom bar
(Use a file instead, Not now) is a fixed `Row` outside the `LazyColumn`, placed right after the
title in traversal order (`traversalIndex`), so a TalkBack user reaches it without passing the
list.

### 6.8 The drawn US map and the tap

**Data:** `android/feature/states/src/main/assets/states/us_states.json`, format
`beamline-state-shapes/1`, **committed** (data/ is not in git; the APK build must not need it),
made by `packbuilder/catalog/build_state_shapes.py` (7.4) from **Natural Earth 5.1.2**
`ne_10m_admin_1_states_provinces` (public domain; credit optional, already in A's data credits;
the copy pinned by `packbuilder/map/fetch_sources.py`, read from
`data/map/work/overview/ne_overview.gpkg`, which `build_overview.py` writes; verified: 51 US rows,
50 states and DC, with `postal`, `name`, `fips` ("US42"), label `latitude`/`longitude`). Natural
Earth rather than Census cartographic files: the same source as the overview's state lines, and
its polygons include Great Lakes water, so a tap on Lake Michigan means Michigan (state-picker
research). Size budget 300 KB (the research measured 280 KB for NE at about 1 km tolerance as plain
GeoJSON; this format is smaller); the script fails above it.

**Projection:** Albers equal-area conic, as US wall maps: lower 48 with standard parallels 29.5°
and 45.5° N, origin 23° N 96° W; Alaska its own frame (55°/65° N, origin 50° N 154° W) scaled to
fit its inset box (0.285 of the lower 48's scale for Natural Earth 5.1.2, the Aleutians included);
Hawaii its own (8°/18° N, origin 3° N 157° W) scaled to fit its box (0.947); both placed as
insets at the lower left. Alaska's parts east of 180° are shifted by −360° first. Map units: the
lower 48 is 1,000 wide; the height follows (about 620).

**Asset:**

```json
{
  "format": "beamline-state-shapes/1",
  "source": "Natural Earth 5.1.2 ne_10m_admin_1_states_provinces (public domain)",
  "source_sha256": "b9e2e7b3d5c2c59593f6eafc19be2ba87fb04788a90c57ccd6cf32a587b6ecc3",
  "width": 1000, "height": 620, "scale": 10,
  "frames": {"lower48": {"lat1": 29.5, "lat2": 45.5, "lat0": 23, "lon0": -96, "k": "…", "dx": "…", "dy": "…"},
             "alaska": {"…": "…"}, "hawaii": {"…": "…"}},
  "inset_boxes": {"alaska": ["…"], "hawaii": ["…"]},
  "states": [
    {"code": "PA", "fips": 42, "name": "Pennsylvania", "inset": "lower48", "label": [8123, 2410],
     "box": [7610, 2105, 8640, 2711], "neighbours": ["DE", "MD", "NJ", "NY", "OH", "WV"],
     "rings": [[7610, 2105, 12, -3, 40, 7]]}
  ]
}
```

(The numbers of the PA entry are illustrative; "…" marks values the script computes.)
Coordinates are integers in tenths of a map unit; within each ring the first pair is absolute and
the rest are differences. `k`, `dx`, `dy` map projected units to map units; `inset_boxes` are the
insets' frames in map units (Alaska fitted into a 230 × 170 box at the lower left, Hawaii into a
140 × 80 box to its right, both 20 units from the bottom edge). Simplification: Douglas-Peucker per ring in map units with tolerance 0.25 (about 1.5 km in
the lower 48), every state keeping its largest part; parts smaller than 0.5 square map units are
dropped otherwise. `neighbours` come from the unsimplified outlines (touching within 0.5 map
units).

**Drawing** (Compose `Canvas`, no MapLibre): fills, then outlines, inset boxes, the selection
outline, labels. By status: AVAILABLE `surfaceVariant` fill, 1 dp outline; INSTALLED and
UPDATE_AVAILABLE primary colour at 55 % with a 1.5 dp outline and " ✓" after the code (Canvas text
uses the system font, so the mark draws; the style's glyph limit does not apply here);
IN_PROGRESS `primaryContainer` with a 2 dp primary outline and " ↓"; NOT_AVAILABLE background fill
with a dashed 1 dp outline; UNKNOWN as AVAILABLE without labels' marks; the selected state a 3 dp
`onSurface` outline. Postal codes at the label point when the state's box is at least 28 dp wide on
screen. Paths are built once per asset load and scaled with the transform. Zoom 1× (fit to the
card with 16 dp margins) to 8×; a list selection animates to the state's box at 60 % of the card.

**The tap** (`StateHitTest.resolve`, pure, JVM-tested): inverse-transform to map units; candidates
= states whose box grown by 16 dp contains the point; inside = the candidate whose rings contain
the point (even-odd). If inside: when the tapped state itself is small (box long side under 48 dp
at this zoom) and another state's edge lies within 2 dp of the tap (`nearDp / 8`) →
**Choose**([inside and every state whose edge is within 16 dp, small or not], nearest first, at
most 4), else **One**(inside) (the rule the cases of 10.2 need on the real shapes;
`StateShapesTest.theInsideRuleAndWhyItDiffersFrom68` pins each difference from the first wording). If outside every state: the nearest edge within 16 dp → One(it), unless the
nearest is small and a second state is within 16 dp → Choose. Else **None**: nothing is selected
and the hint "Tap a state, or pick it from the list." shows under the map for 3 seconds.
Double-tap and pinch zoom stay on; a zoomed-in tap rarely needs the chooser.

```kotlin
// FILE: feature/states/src/main/kotlin/io/github/spysyweeb/beamline/feature/states/map/StateShapes.kt
package io.github.spysyweeb.beamline.feature.states.map

/** Which drawing frame a state is in: Alaska and Hawaii are drawn as insets, as on US wall maps. */
enum class Inset { LOWER48, ALASKA, HAWAII }

/** One state in map units (the asset's integers divided by its `scale`; y grows downwards). */
class StateShape(
    /** USPS code, "PA". */
    val code: String,
    /** FIPS state code, 42. */
    val fips: Int,
    val name: String,
    val inset: Inset,
    val labelX: Float,
    val labelY: Float,
    /** minX, minY, maxX, maxY. */
    val box: FloatArray,
    /** Each ring as x0, y0, x1, y1, ...; closed implicitly; outer rings and holes alike (even-odd). */
    val rings: List<FloatArray>,
    /** USPS codes of the states it touches, from the unsimplified outlines. */
    val neighbours: List<String>,
)

/** One Albers equal-area conic frame of the asset (degrees; [k] map units per projected unit). */
class AlbersFrame(val lat1: Double, val lat2: Double, val lat0: Double, val lon0: Double, val k: Double, val dx: Double, val dy: Double)

/**
 * assets/states/us_states.json (format beamline-state-shapes/1), made by
 * packbuilder/catalog/build_state_shapes.py from Natural Earth 5.1.2 admin-1 (public domain).
 */
class StateShapes(
    val width: Float,
    val height: Float,
    val states: List<StateShape>,
    /** The inset frames' boxes (minX, minY, maxX, maxY), drawn as thin outlines. */
    val insetBoxes: Map<Inset, FloatArray>,
    val frames: Map<Inset, AlbersFrame>,
) {
    fun byCode(code: String): StateShape? = states.firstOrNull { it.code == code }

    /** A position in map units (x, y), with the frame chosen as the build script chooses it; null outside every frame. */
    fun project(lon: Double, lat: Double): FloatArray? = TODO("track G")

    companion object {
        const val FORMAT = "beamline-state-shapes/1"
        fun parse(json: String): StateShapes = TODO("track G")
    }
}

data class HitRules(
    /** A tap outside every state still picks one whose outline is this close. */
    val nearDp: Float = 16f,
    /** A state whose box's long side is shorter than this on screen counts as small. */
    val smallDp: Float = 48f,
    val chooserMax: Int = 4,
)

sealed interface TapResult {
    /** Outside every state: the UI shows the "Tap a state" hint. */
    data object None : TapResult
    data class One(val code: String) : TapResult
    /** "Which one?": nearest first. */
    data class Choose(val codes: List<String>) : TapResult
}

/**
 * Which state a tap means (section 6.8). [x], [y] in map units; [unitsPerDp] = map units per dp at
 * the current zoom. Pure; no MapLibre, no Android.
 */
object StateHitTest {
    fun resolve(shapes: StateShapes, x: Float, y: Float, unitsPerDp: Float, rules: HitRules = HitRules()): TapResult = TODO("track G")
}
```

Why a drawn map and not MapLibre (where I depart from the state-picker research, which proposed
layers on the composed overview style): the overview has no state polygons (verified by the
research and `us-overview-…stats.json`), its state labels start at zoom 3 while the lower 48 fits a
phone at about zoom 2, the style's glyphs lack ✓ and ↓, a second MapView would sit on top of the
main one, and composing the style would tie G to P's changing `StyleComposer`. The research's tap
rules (point in polygon, nearest edge, the chooser, the tap never downloading, the list as the
equal path) are kept as they were.

### 6.9 Contract: the feature's entry points (`:feature:states`, for I)

Android signatures, compiled with `:feature:states` since Round G0.

```kotlin
// FILE: feature/states/src/main/kotlin/io/github/spysyweeb/beamline/feature/states/GetPacks.kt
package io.github.spysyweeb.beamline.feature.states

import android.content.Context
import android.content.Intent
import io.github.spysyweeb.beamline.api.data.InstalledData
import io.github.spysyweeb.beamline.api.data.PackInfo
import io.github.spysyweeb.beamline.download.CatalogState
import io.github.spysyweeb.beamline.download.DownloadJob
import io.github.spysyweeb.beamline.settings.SettingsStore
import io.github.spysyweeb.beamline.units.UnitFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class GetPacksConfig(
    /** BuildConfig.CATALOG_URL; "" = downloads are off in this build. */
    val catalogUrl: String,
    /** BuildConfig.DEBUG: plain http to a private or loopback IPv4 address is allowed. */
    val allowCleartext: Boolean,
    /** :core:packs PackManifest.SUPPORTED_VERSION (the installer reads exactly one version today). */
    val minManifestVersion: Int,
    /** :core:packs PackManifest.SUPPORTED_VERSION. */
    val maxManifestVersion: Int,
    /** getExternalFilesDir(null)/downloads: on the volume of the packs folder. */
    val downloadsDir: File,
    /** filesDir/getpacks: catalog cache and ui.json. */
    val stateDir: File,
    /** The same function InstalledLibrary gets: File.usableSpace (section 2.6). */
    val freeBytes: (File) -> Long,
    /** Bytes of the merged routing tile set on the phone now: the files in InstalledLibrary's routing folder; 0 when none. */
    val routingSetBytes: () -> Long,
    /** Opens the app on the States screen (notification tap). */
    val openStates: () -> Intent,
    /** Opens the app on the map (tap on "ready"). */
    val openMap: () -> Intent,
)

/** The map's chip (section 1.5); I shows it at the top of the map and opens the States screen on a tap. */
data class MapChip(
    /** "Choose your state", "Downloading Pennsylvania · 43 %", "Download paused · Pennsylvania", … */
    val text: String,
    /** For TalkBack: the text plus "Opens States". */
    val description: String,
)

/** Track G's part of AppGraph: one per process. */
class GetPacks(
    context: Context,
    installed: InstalledData,
    settings: SettingsStore,
    units: StateFlow<UnitFormatter>,
    config: GetPacksConfig,
    scope: CoroutineScope,
) {
    val catalog: StateFlow<CatalogState> get() = TODO("track G")
    val job: StateFlow<DownloadJob?> get() = TODO("track G")

    /**
     * False until the first scan (InstalledSet.scanned). Then true while no pack is on the phone
     * (none installed, none among the issues) and either the user has not picked a state, a file
     * or "Not now", or a download started on the first-run screen is not finished (any phase;
     * FAILED until dismissed). I shows StatesScreen(FIRST_RUN) after the driver notice while true.
     */
    val showFirstRunPicker: StateFlow<Boolean> get() = TODO("track G")

    /** The map's chip; null when there is nothing to show (a state installed, no job and no unseen update). */
    val mapChip: StateFlow<MapChip?> get() = TODO("track G")

    /**
     * A pack to show: a first-run download finished (kept in ui.json until done, so it survives the
     * process and works from the launcher as well as from the "ready" notification), or the user
     * tapped "Show on map". I closes the States screen, fits the map to [PackInfo.bounds], shows
     * the snackbar, then calls [showOnMapDone]. Null when there is none.
     */
    val showOnMap: StateFlow<PackInfo?> get() = TODO("track G")

    /** I has moved the camera and shown the snackbar for [showOnMap]'s pack. */
    fun showOnMapDone(): Unit = TODO("track G")

    /** From the activity's onStart: recovers (once per process) and continues an unfinished job by starting the service. */
    fun onAppVisible(): Unit = TODO("track G")

    /**
     * Item 40 (the owner, 2026-11-24): I calls this when the app becomes visible and the user allows it
     * (Online features, "Check for map updates"). It fetches the list of states, never a pack, when the
     * last fetch is a day old or more; [mapChip] then names an installed state with newer map data until
     * the user has seen it on the States screen.
     */
    fun checkForUpdates(): Unit = TODO("track G")
}

/** Implemented by the Application (I) so DownloadService finds the process's GetPacks. */
interface GetPacksHost {
    val getPacks: GetPacks
}
```

```kotlin
// FILE: feature/states/src/main/kotlin/io/github/spysyweeb/beamline/feature/states/StatesScreen.kt
package io.github.spysyweeb.beamline.feature.states

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

enum class StatesMode { FIRST_RUN, MANAGE }

/** "Choose your state" (FIRST_RUN) or "States" (MANAGE). [onClose]: Not now, "Look at the map while you wait", back, or the first state installed. */
@Composable
fun StatesScreen(getPacks: GetPacks, mode: StatesMode, onClose: () -> Unit, modifier: Modifier = Modifier): Unit = TODO("track G")
```

```kotlin
// FILE: feature/states/src/main/kotlin/io/github/spysyweeb/beamline/feature/states/DownloadService.kt
package io.github.spysyweeb.beamline.feature.states

import android.content.Context
import androidx.lifecycle.LifecycleService

/** The foreground service (type dataSync) that runs DownloadEngine.run(): section 5. */
class DownloadService : LifecycleService() {
    companion object {
        /** Notification actions. Cancel is not one: it deletes files and is confirmed in the app. */
        const val ACTION_PAUSE = "io.github.spysyweeb.beamline.download.PAUSE"
        const val ACTION_USE_MOBILE_DATA = "io.github.spysyweeb.beamline.download.USE_MOBILE_DATA"

        /** Starts the service from the visible app; false when Android refuses (then GetPacks runs the engine itself). */
        fun start(context: Context): Boolean = TODO("track G")
    }
}
```

## 7. The PC side

All under `packbuilder/catalog/` (G's), Python 3.12 standard library only, run from the repository
root, never uploading anything.

### 7.1 `build_catalog.py`

```
python packbuilder/catalog/build_catalog.py --out data/catalog/site \
    --pack data/r3-packs/us-pa [--pack data/r3-packs/us-oh] \
    [--archive data/android/archives-z14/us-pa-2026-11-14.tar] [--manifest-versions 3] [--prune] [--check]
```

1. Each input is a pack folder or an archive (manifest first, as `read_pack.py` reads it). The
   manifest version must be one of `--manifest-versions` (default `3`, what this round's app reads;
   G's own tests and the demo of 10.3 pass `2,3`), so a catalog never offers a pack the app it is
   meant for refuses; every part's size and SHA-256 are verified (one read pass); `pack_version` is
   the manifest's or, for version 2, `created_utc` as `YYYYMMDDhhmm`.
2. The release folder `<out>/<pack_id>-<pack_version>/` gets `manifest.json` (the same bytes) and
   one transfer file per part (2.2 builder rule: `<file>.gz`, gzip -6 without name or time, or
   `<file>` as a hard link when on the same volume, else a copy) and `transfer.json`
   (`{"<file>": {"path", "encoding", "bytes", "sha256"}}`) so a re-run reuses finished files
   instead of compressing again. A release folder that `catalog.json` already names is never
   written again (2.2: a published release is not rebuilt). Folder and file names must match
   `[a-z0-9][a-z0-9._-]*`.
3. Refuses: a transfer file of 2,147,483,648 bytes or more ("too large for one release asset");
   two packs with the same `state`; a `pack_id` other than `us-` + the state in lower case; a
   string over its 2.3 limit; any path that breaks 2.3 rule 2.
4. `<out>/catalog/catalog.json`: the entries for the given packs replace those with the same `id`;
   other entries stay; `serial` as in 2.3; written to a temporary file, then renamed. Parts in
   ascending transfer size.
5. `--prune` deletes release folders no catalog entry names. `--check` re-reads every file the
   catalog names and compares size and SHA-256 (run before publishing). Prints one line per pack:
   state, version, download MB, unpacked MB.

### 7.2 `serve_packs.py` (development server)

```
python packbuilder/catalog/serve_packs.py --root data/catalog/site [--bind 127.0.0.1] [--port 8765]
    [--log-headers] [--log-clients] [--throttle-kbps N] [--cut-after BYTES] [--ignore-range] [--fail-every N]
```

- `ThreadingHTTPServer` with its own handler (Python's `SimpleHTTPRequestHandler` ignores `Range`:
  hosting research, tested): `GET` and `HEAD` only (else 405); the URL path maps to a file under
  `--root` only when every segment matches `[A-Za-z0-9][A-Za-z0-9._-]*` (else 404).
- Responses: `Content-Type` (`application/json` for `.json`, else `application/octet-stream`),
  `Content-Length`, `Accept-Ranges: bytes`, strong `ETag` `"<size hex>-<mtime_ns hex>"`,
  `Last-Modified`, `Cache-Control: no-cache`. One `bytes=N-` or `bytes=N-M` range → 206 with
  `Content-Range`; N at or past the end → 416 with `Content-Range: bytes */<size>`; several ranges →
  200 with the whole file. `If-Match` mismatch → 412; `If-None-Match` match → 304; `If-Range`
  honoured.
- One log line per request: time, method, path, `Range`, status, bytes sent, `User-Agent`; the
  client's address only with `--log-clients` (so a log pasted into a report or the committed
  `docs/experiments/18-getting-packs.md` carries no home-network address); `--log-headers` prints every
  request header (the owner can see nothing else is sent).
- Faults for tests: `--cut-after N` closes the connection after N body bytes, once per path;
  `--ignore-range` answers 200 always; `--throttle-kbps`; `--fail-every N` answers every Nth
  request 503.
- `--bind 127.0.0.1` by default (the emulator reaches it as `10.0.2.2`; a USB phone through `adb
  reverse tcp:8765 tcp:8765` as `127.0.0.1`). `--bind 0.0.0.0` for the phone over Wi-Fi prints:
  "Serving data/catalog/site to your home network on port 8765. Anyone on it can download these
  files." Windows asks once whether Python may accept connections on private networks.

### 7.3 `catalog_client.py` (reference client)

`python packbuilder/catalog/catalog_client.py list <catalog URL>` and `… get <catalog URL> <state
or id> --out <folder> [--skip-optional]`: the algorithm of 2.4 with the redirect and address rules
of 3.1 (public addresses only, except a loopback or private catalog host itself, which only then
allows plain http to that host), the rules of 2.3, progress on one line; the result is a pack
folder that `android/scripts/read_pack.py --verify` passes. It is the normative example for
`docs/catalog-format.md` (the snippet of 2.5 is its core); the tests use it against
`serve_packs.py`; it is the starting point of a comma 3X client.

### 7.4 `build_state_shapes.py`

`python packbuilder/catalog/build_state_shapes.py [--check]`: reads
`data/map/work/overview/ne_overview.gpkg` read-only (SQLite; GeoPackage geometry header plus WKB,
parsed in Python), the 51 rows with `adm0_a3 = 'USA'`; projects, simplifies and encodes as 6.8;
writes `android/feature/states/src/main/assets/states/us_states.json` (LF, compact). `--check`
rebuilds in memory and fails when the committed file differs or exceeds 300 KB. Fails with "run
packbuilder/map/overview/build_overview.py first" when the GeoPackage is missing.

### 7.5 Tests

`python -m unittest discover packbuilder/catalog/tests`: a tiny pack made in a temporary folder
(three parts, one compressible) → `build_catalog.py` → `serve_packs.py` on a free port in a thread
→ `catalog_client.py get` with `--cut-after` (resumes; the log shows `Range`), with
`--ignore-range` (starts over, still correct), with a corrupted file (fails as damaged), with a
redirect to http or to `127.0.0.1` (refused); the catalog equal byte for byte when built twice on
the same machine, and a second run that reuses `transfer.json` without compressing; a release
folder already named by the catalog left untouched; paths escaping the scope refused; a second
pack with the same state, and an `id` that does not match its state, refused; a version-2
manifest refused with the default `--manifest-versions`.

## 8. Configuration

### 8.1 Where the address comes from

One Gradle property, `beamline.catalogUrl`, read by `:app` (and `:demo:states`) in this order:
`android/local.properties` (git-ignored: `.gitignore` lists `android/local.properties`; verified),
then the Gradle property (`-Pbeamline.catalogUrl=…`, `~/.gradle/gradle.properties`, or
`android/gradle.properties` from publishing day on). **The PC's home-network address is written
only into `android/local.properties`**, never into a committed file. Values:

| Where the app runs | `beamline.catalogUrl` | Server |
|---|---|---|
| Emulator | `http://10.0.2.2:8765/catalog/catalog.json` | `serve_packs.py` (default bind) |
| Phone on USB | `http://127.0.0.1:8765/catalog/catalog.json` | `serve_packs.py` + `adb reverse tcp:8765 tcp:8765` |
| Phone on the home Wi-Fi | `http://<the PC's address on the home network>:8765/catalog/catalog.json` | `serve_packs.py --bind 0.0.0.0` |
| Public (after publishing) | `https://github.com/<owner>/<data repo>/releases/download/catalog/catalog.json` | GitHub Releases |

**Release builds since 0.1.23** (the owner, 2026-11-24; the packs repository is public since
2026-11-22): a release whose `beamline.catalogUrl` is not https (none, or the emulator's http
address) reads the public catalog
`https://github.com/SpysyWeeb/beamline-packs/releases/download/catalog/catalog.json` (`:app`
`publicCatalogUrl`); an https value replaces it. Debug builds read only the configured address.

A build with **no address** has downloads off: `CatalogState.Off`, the banner "Downloads are off in
this build. Use a file instead: a state file (.tar) copied to this phone." (says what file is
meant; next run, part 1), no network use at all. The PC address changes with DHCP; a
reserved address in the router saves rebuilding. For the three local rows the app does not ask
whether the network reaches the internet, only whether a Wi-Fi (or Ethernet) network is up, and
it sends the requests over that network (5.2), so a home network whose internet link is down, or a
phone that has switched its default to mobile data, still reaches the PC.

### 8.2 The `:app` build (Round G0, 9.3)

In `android/app/build.gradle.kts` (and the same in `android/demo/states/build.gradle.kts`): three
imports at the top of the script, before `plugins`,

```kotlin
import java.io.StringReader
import java.net.URI
import java.util.Properties
```

and after the existing properties:

```kotlin
// Pack downloads (docs/architecture-downloads.md, section 8). While the project is private
// the address lives only in android/local.properties (git-ignored) or a -P property.
val localProperties = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText.orNull
        ?.let { load(StringReader(it)) }
}
val catalogUrl: String = (localProperties.getProperty("beamline.catalogUrl")
    ?: providers.gradleProperty("beamline.catalogUrl").orNull ?: "").trim()
val catalogUri: URI? = catalogUrl.takeIf { it.isNotEmpty() }?.let { URI(it) }
val privateIpv4 = Regex("""^(10\.\d+|172\.(1[6-9]|2\d|3[01])|192\.168|127\.\d+)\.\d+\.\d+$""")
require(catalogUri == null || catalogUri.scheme == "https" ||
    (catalogUri.scheme == "http" && (catalogUri.host == "localhost" || privateIpv4.matches(catalogUri.host)))) {
    "beamline.catalogUrl must be https, or http to localhost or a private IPv4 address"
}
// Debug builds: cleartext only to the emulator host, loopback and the configured private address.
abstract class BeamlineNetworkConfig : DefaultTask() {
    @get:Input abstract val hosts: ListProperty<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @TaskAction fun write() {
        val f = outputDir.get().file("xml/beamline_network.xml").asFile
        f.parentFile.mkdirs()
        f.writeText(buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<network-security-config>\n")
            append("    <domain-config cleartextTrafficPermitted=\"true\">\n")
            hosts.get().forEach { append("        <domain includeSubdomains=\"false\">$it</domain>\n") }
            append("    </domain-config>\n</network-security-config>\n")
        })
    }
}
val debugNetworkConfig = tasks.register<BeamlineNetworkConfig>("beamlineDebugNetworkConfig") {
    hosts.set((listOf("10.0.2.2", "127.0.0.1", "localhost") +
        listOfNotNull(catalogUri?.takeIf { it.scheme == "http" }?.host)).distinct())
    outputDir.set(layout.buildDirectory.dir("generated/beamline/debugNetworkRes"))
}
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(debugNetworkConfig, BeamlineNetworkConfig::outputDir)
    }
}
```

In `buildTypes`: debug `buildConfigField("String", "CATALOG_URL", "\"$catalogUrl\"")`; release
`buildConfigField("String", "CATALOG_URL", "\"${if (catalogUri?.scheme == "https") catalogUrl else ""}\"")`
(an http address never reaches a release build). New file `android/app/src/debug/AndroidManifest.xml`
(with a comment saying what it is for):

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:networkSecurityConfig="@xml/beamline_network" />
</manifest>
```

Release builds have no network security config (platform default: no cleartext). **Change in
Round G0:** the design wrote `java.util.Properties()`, `java.io.StringReader(it)` and
`java.net.URI(it)` in place; under AGP 9.5.1 the build script's `java` extension accessor
(`JavaPluginExtension`) shadows the `java` package there ("Unresolved reference 'util' on receiver
of type 'JavaPluginExtension'"), so the three classes are imported at the top of the script
instead. Nothing else changed. Round G0's build ran the snippet with the emulator address of 8.1 in
`android/local.properties` and read the generated XML and the merged manifests once
(`docs/architecture.md` appendix B). Verified for the XML: Android's parser accepts any text in
`<domain>` (it lower-cases it and does not reject IP literals; AOSP `XmlConfigSource.java`, appendix
B); that an IP literal then matches a URL's host is inferred and is proved by the first phone run
(10.4).

### 8.3 On publishing day (owner's word)

1. Create the public data repository (question 12.2); never put a token in the app. Its first
   commit is a `LICENSE` with the ODbL 1.0 text and a `README.md` that says: the packs are
   OpenStreetMap-derived and published under ODbL 1.0, "© OpenStreetMap contributors"; the house-
   number parts also carry U.S. Census Bureau TIGER/Line, USDOT NAD and City of Scranton data; the
   overview and state shapes use public-domain Natural Earth data; and where the build recipe is
   (the app repository's `packbuilder/`).
2. `build_catalog.py --check`, then upload each release folder as a release with that tag (`gh
   release create us-pa-202611152241 data/catalog/site/us-pa-202611152241/* --notes-file <notes>`,
   where the notes repeat the licence and credit lines of step 1 for that pack, from its
   manifest's attributions) and the catalog as release `catalog` (`gh release upload catalog
   data/catalog/site/catalog/catalog.json --clobber`), pack releases immutable, the catalog release
   not.
3. Commit `beamline.catalogUrl=https://github.com/<owner>/<data repo>/releases/download/catalog/catalog.json`
   to `android/gradle.properties`; release builds then have downloads on. That file is frozen, so
   the orchestrator then runs `python tools/check_contracts.py --freeze` and
   `python tools/check_contracts.py` (`docs/architecture.md` 5.2).
4. First release-APK download over HTTPS on the phone (the first time R8-processed download code
   meets the network; 10.4).
5. Nothing in the app changes; the dev server stays for testing. Before any second host: signing
   (3.3).
6. When the app targets API 37: debug builds need `ACCESS_LOCAL_NETWORK` for the LAN server
   (research; not for GitHub).

## 9. Ownership for track G

### 9.1 What G owns

| Owns (create, edit, delete) | Needs from a device | Definition of done |
|---|---|---|
| `android/core/download/`, `android/feature/states/`, `android/demo/states/`, `packbuilder/catalog/`, `docs/catalog-format.md`, `android/scripts/check_network_rule.py`, report `docs/experiments/18-getting-packs.md`, `data/r3-g/`, `data/catalog/`, `data/gradle/g/` | emulator AVD `beamline_g`, port 5574, no pushed data; the dev server on port 8765 | 10.1-10.3 pass; `check_network_rule.py scan` and `privacy` pass on the tree; `build_state_shapes.py --check` passes; `docs/catalog-format.md` written; report 18 names every artifact G adds to the runtime classpath with its SPDX licence (today OkHttp 5.3.2 and its Okio 3.16.4, both Apache-2.0, both already resolved through Ferrostar), so A's `notices.py --check` passes when I puts `:feature:states` on `:app`'s classpath (`docs/architecture.md` 5.4 item 14) |

G consumes: `:core:api` (frozen, with 6.6), `:core:settings` (`SettingsStore`,
`Settings.includeAddresses`), `:core:units` (`UnitFormatter.duration`), `:ui:theme` (colours,
icons); from P the `InstalledData` implementation (G codes against the interface; its tests and
demo use fakes); from P's tools the pack folders and archives under `data/` (read-only); from A
nothing (I orders the notice before the picker).

### 9.2 What P adds (by this design)

P's row of `docs/architecture.md` 5.1 gained the items of 6.6 (`pack_version` in manifest v3 and
`assemble.py`, `PackInfo.packVersion`, `startInstallFromFolder` with its tests, `inspect` without
space problems, the optional-part install with its tests) and one assertion in its install test:
the merged set's `valhalla.json` (`RoutingSet.engineConfigFile`) has `mjolnir.tile_url` empty or
absent (rule N5). `docs/pack-format.md` gains `pack_version` and `"source": "download"`.

### 9.3 Round G0: the shared-file changes (done 2026-11-15)

Round G0 is the set-up step of this design, made by the orchestrator before the seven tracks start
(`docs/architecture.md` section 0; its appendix B records the build and the checks). What it changed:

1. `android/settings.gradle.kts`, appended:

   ```kotlin
   // Track G: getting state packs in the app (docs/architecture-downloads.md).
   include(":core:download")       // track G
   include(":feature:states")      // track G
   include(":demo:states")         // track G, not shipped
   ```

2. `android/gradle/libs.versions.toml`: under `[versions]`, after the Round 0 block,
   `okhttp = "5.3.2"  # com.squareup.okhttp3:okhttp; what the app already resolves (Ferrostar BOM 5.3.2 over MapLibre's 4.12.0), published 2025-11-18`
   (the comment was reworded on 2026-11-17, when Ferrostar was dropped; the version stays);
   under `[libraries]`, `okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }`.
   No other new entry (`androidx-lifecycle-service`, `kotlinx-serialization-json`,
   `kotlinx-coroutines-*`, Compose, JUnit are there). Verified on Maven Central when this design was
   written: 5.3.2 dated 2025-11-18; 5.5.1 (2026-10-22) is newer, but taking it would change the
   OkHttp that MapLibre and Ferrostar get, which is a separate decision. Licences (verified in the
   POMs on Maven Central): OkHttp 5.3.2 Apache-2.0, and the Okio it depends on, 3.16.4, Apache-2.0;
   neither is new to the APK, and A's notice list must keep both (9.1). `:core:download` is a JVM
   module, so it resolves OkHttp's JVM variant (`okhttp-jvm`); an Android consumer gets
   `okhttp-android` from the same coordinates.
3. `:core:api` and the contract check, in this order: (a) the whole-file `// FILE:` block of
   `InstalledData.kt` in `docs/architecture.md` 2.2 carries the changes the excerpts of 6.6 show
   (not as extra excerpt blocks there, 4.3), and the tree's file equals it; a new test in the frozen
   module, `core/api/src/test/kotlin/io/github/spysyweeb/beamline/api/InstallerHandoffTest.kt`,
   checks the defaults (6.6); (b) `tools/check_contracts.py` reads `docs/architecture.md` and every
   companion `docs/architecture-*.md` (this document since Round G0), reports a file quoted as a
   whole in two places, and `--write` wrote G's contract files into the tree; (c)
   `python tools/check_contracts.py --freeze` recorded the frozen files again
   (`android/settings.gradle.kts`, `android/gradle/libs.versions.toml`, `android/core/api` and the
   script itself changed); (d) `python tools/check_contracts.py` and `--exact` pass.
4. New modules (Round 0's style; G owns them afterwards):
   - `android/core/download/build.gradle.kts`: plugins `kotlin.jvm`, `kotlin.serialization`; Java
     and JVM target from the catalog; `api(project(":core:api"))`, `api(libs.kotlinx.coroutines.core)`,
     `implementation(libs.okhttp)`, `implementation(libs.kotlinx.serialization.json)`; tests JUnit 5
     (`junit-bom`, `junit-jupiter`, `junit-platform-launcher`) and `kotlinx-coroutines-test`;
     `tasks.test { useJUnitPlatform() }` with the system property `beamline.repoRoot` (as
     `:nav:session` and `:map:scene` have) for G's test that compares the rules copy with its source.
   - `android/feature/states/build.gradle.kts`: `id("com.android.library")`,
     `alias(libs.plugins.kotlin.compose)`; namespace `io.github.spysyweeb.beamline.feature.states`;
     compile and min SDK from the catalog; `buildFeatures.compose = true`;
     `api(project(":core:download"))`, **`api(project(":core:settings"))`, `api(project(":core:units"))`,
     `api(libs.androidx.lifecycle.service)`** (the design said `implementation` for these three; they
     are `api` because `SettingsStore`, `UnitFormatter` and `LifecycleService` appear in the public
     signatures of `GetPacks` and `DownloadService`, the rule of `docs/architecture.md` 1.1),
     `implementation(project(":ui:theme"))`, `implementation(libs.androidx.core.ktx)`,
     `implementation(libs.androidx.activity.compose)`, `implementation(libs.androidx.lifecycle.runtime.compose)`,
     Compose BOM, `ui`, `foundation`, `material3`, `ui-tooling-preview` (+ `debugImplementation`
     `ui-tooling`), `implementation(libs.kotlinx.coroutines.android)`,
     `implementation(libs.kotlinx.serialization.json)`; unit tests on JUnit 5 with
     `kotlinx-coroutines-test`, and the androidTest entries, as `:feature:search`'s.
   - `android/demo/states/build.gradle.kts`: `alias(libs.plugins.android.application)`, Compose;
     application id `io.github.spysyweeb.beamline.statesdemo`; namespace
     `io.github.spysyweeb.beamline.states.demo`; `implementation(project(":feature:states"))`,
     `implementation(project(":ui:theme"))`, the Compose and activity entries; the catalog URL, the
     `CATALOG_URL` build-config fields and the debug network config exactly as 8.2 (with the same
     three imports), and `src/debug/AndroidManifest.xml` as `:app`'s. Its main manifest is a
     placeholder without an activity (G writes it) and declares no permission itself: the network
     permissions reach it only from `:feature:states` (its manifest of 5.3, and `INTERNET` from
     OkHttp's Android AAR through `:core:download`, already in Round G0's build).
   - The contract files of 6.2, 6.3, 6.8 (`StateShapes.kt`) and 6.9 with their `TODO("track G")`
     bodies; an empty `AndroidManifest.xml` for the library (G fills in 5.3).
   - **The rule data file:** the design put `download_rules.json` straight into `:core:download`'s
     resources. Every other rule file of the round has its single source under `packbuilder/` and a
     copy in the module (`docs/architecture.md` 5.2, decision 6.33), so the source is
     `packbuilder/catalog/download_rules.json` (G's folder; the data block of 6.3 quotes it, with a
     `note` key like the other rule files), and `android/routing/scripts/sync_data.py` gained the
     pair, so `sync_data.py --only download_rules.json` makes the copy and `sync_data.py --check`
     compares it. `DownloadRules`' KDoc names the source.
5. `android/app/src/main/AndroidManifest.xml`: the first comment and the first two
   `uses-permission` elements (the removal of `INTERNET` and `ACCESS_NETWORK_STATE`) became

   ```xml
       <!-- Offline-first: maps, routing and search never use the network. The network is used only to
            fetch the list of states and the state packs, by one class (docs/architecture-downloads.md,
            section 4); :feature:states declares INTERNET and ACCESS_NETWORK_STATE for it. MapLibre's AAR also
            declares ACCESS_WIFI_STATE, which nothing needs: remove it from the merged manifest. -->
   ```

   keeping the `ACCESS_WIFI_STATE` element with `tools:node="remove"`. Plus 8.2 (build file and
   `src/debug/AndroidManifest.xml`). These are `:app` files: Round G0 applied them so G's
   permissions are not stripped from the app once I puts `:feature:states` on it; I owns them
   afterwards. Until I wires `OfflineMapHttp` (rule N5), `:app`'s merged manifest holds the
   `INTERNET` and `ACCESS_NETWORK_STATE` that MapLibre's AAR declares; nothing in the app requests
   anything over the network (styles point at local files, `MapLibre.setConnected(false)` stays).
6. `android/local.properties` (git-ignored, on the build PC only): `beamline.catalogUrl` set to the
   emulator form of 8.1, `http://10.0.2.2:8765/catalog/catalog.json`, so debug builds on an
   emulator have downloads on. No home-network address is written anywhere by Round G0.

### 9.4 What I wires after the round (items of `docs/architecture.md` 5.4)

1. `AppGraph`: `GetPacks(context, installed, settings, units, GetPacksConfig(BuildConfig.CATALOG_URL,
   BuildConfig.DEBUG, PackManifest.SUPPORTED_VERSION, PackManifest.SUPPORTED_VERSION,
   File(getExternalFilesDir(null) ?: filesDir, "downloads"), File(filesDir, "getpacks"), freeBytes,
   routingSetBytes, openStates, openMap), scope)`, with the same `freeBytes` given to
   `InstalledLibrary` (`File.usableSpace`, 2.6) and `routingSetBytes` = the total size of the
   files in the routing folder I gives `InstalledLibrary` (`getExternalFilesDir/routing`).
2. `BeamlineApplication` implements `GetPacksHost`; in `onCreate`, right after `MapLibre.getInstance`
   (right after it, not before: MapLibre 13.7.1's `HttpRequestImpl` reads the MapLibre instance in its static initialiser and throws `MapLibreConfigurationException` without it, seen at integration on 2026-11-16; no map view and no request exists yet then),
   calls `HttpRequestImpl.setOkHttpClient(OfflineMapHttp.client)` (rule N5; `OfflineMapHttp.kt`
   is I's, with `OfflineMapHttpTest` and `ComposedStyleOfflineTest` of 4.2 item 3; `:app` gains
   `implementation(libs.okhttp)`, the version it already resolves); keeps
   `MapLibre.setConnected(false)`; in debug builds sets the StrictMode policy of 4.2 item 5.
3. Start-up order: Splash → reveal → `FirstRunNotice` → `StatesScreen(FIRST_RUN)` while
   `showFirstRunPicker` → the map with the search pill at the top (`docs/architecture.md` S8).
   `MainActivity.onStart` calls `getPacks.onAppVisible()`.
4. The States screen is G's `StatesScreen(MANAGE)` (replaces `PackManagerScreen` and
   `PickedSources`); opened from Settings, from the States button under the Settings button, from
   `SearchOutput.OpenStates`, from the map chip (`getPacks.mapChip`, shown under the search pill
   while it is not null and only while the pill shows, 1.5 and `docs/architecture-saved-places.md`
   SP2), and by the notification intents:
   `openStates`/`openMap` are explicit intents to `MainActivity` with the action
   `io.github.spysyweeb.beamline.action.OPEN_STATES` / `…OPEN_MAP`, handled in every build (not a
   test extra).
5. Whenever the map is shown and `showOnMap` is not null (a cold start from the launcher, the
   `OPEN_MAP` intent, the first-run screen closing, "Show on map"): close the States screen,
   `MapController.moveCamera(CameraTarget(fit = bounds, padding = 32 dp))`, snackbar "<name> is
   ready.", then `getPacks.showOnMapDone()`.
6. `POST_NOTIFICATIONS`: G asks right after the first Download has started; Start asks only if it
   was never asked.
7. Checks in item 14: `check_network_rule.py manifest` (debug and release), `scan` and `privacy`;
   the JVM tests of 4.2 item 3; `build_state_shapes.py --check`; `notices.py --check` with
   `:feature:states` on the classpath.

## 10. Tests and acceptance

### 10.1 JVM tests of `:core:download` (G)

All against `TestPackServer` (test sources: `com.sun.net.httpserver.HttpServer` on port 0 with
Range, ETag, `If-Match`, `If-None-Match`, redirects, a request log, and faults: cut after N bytes,
ignore Range, change the file, 503, corrupt bytes) and the real `PackHttp`, with a virtual clock
and a fake `InstalledData` that records calls and reads the virtual tar.

| Test | Pass when |
|---|---|
| Catalog parsing | The fixture `catalog-v1.json` (PA's real sizes and hashes) gives the fields of 2.3; unknown keys ignored; `beamline-catalog/2` → NEWER_FORMAT; 2 MiB + 1 byte → TOO_LARGE; paths `../../x`, `/abs`, `http://h/x`, `a/../../b`, `%2e%2e/x`, `a//b`, `a?b` → OUTSIDE_SCOPE; an entry with `manifest_version` 4 or encoding `zstd` → unusable NEEDS_NEWER_APP, one with `manifest_version` 2 → OLD_FORMAT, the others still usable; a bad hash, negative size, duplicate state, `id` `us-oh` with `state` `PA`, a 65-character name → that entry BROKEN; a licence URL over 200 characters or not https → NOT_A_CATALOG |
| Catalog cache | A fetched list with a lower serial than the cached one replaces it (logged); a cache whose `checked_ms` is in the future is refreshed; a cache made for another URL is ignored; a `ClockProblem` → `Failed(CLOCK)` with the cache kept |
| Address rules | `parse` accepts https and, with cleartext allowed, `http://10.0.2.2:8765/catalog/catalog.json`, `http://192.168.200.20:8765/…`, `http://127.0.0.1:8765/…` (each `local`); refuses http without the flag, a private or loopback host without the flag, `http://example.org/…`, user info, query; scope of the GitHub-shaped and dev URLs as in 6.2; `redirectAllowed` as in 3.1 |
| Network rule (`NetworkRuleTest`) | 4.2 item 4 |
| Resume | The server cuts after 3 MB of a 10 MB file: the second request has `Range: bytes=3145728-` and `If-Match` with the first ETag; the final file's SHA-256 matches; total bytes received from the server = file size + at most one buffer |
| Range ignored | With "ignore Range" the resumed request gets 200; the file is rewritten from 0 and is correct |
| File changed | The server changes the file between attempts (new ETag, 412): the part restarts at 0 and is correct |
| 416 at the end | A transfer file already complete on disk: a 416 with `bytes */size` ends it; hash checked |
| Hash failure | Corrupt bytes once: one re-download from 0, then DONE; corrupt the second of three parts twice: PAUSED(DAMAGED), that part's files gone, the first part kept; `resume()` requests only the second and third parts |
| Server unreachable | The server stops answering (connection refused) after 30 % of a part: back-off steps as in 1.4 on the virtual clock, then PAUSED(SERVER_UNREACHABLE) after 8 attempts with every byte kept; a new engine's `onAppVisible` continues it and the next request starts at the bytes on disk. 503 on every request: the same |
| Bad response | A redirect to `http://`, a sixth hop, a body longer than `Content-Length`, `Content-Encoding: gzip`, a 403: each → PAUSED(BAD_RESPONSE), files kept, `detail` names the rule; not continued by `onAppVisible` |
| Clock | `PackHttp` throws `ClockProblem` (built exception chains holding `CertificateNotYetValidException` and `CertificateExpiredException`): PAUSED(CLOCK) at once, no retry loop |
| List mismatch | A manifest whose `osm_snapshot` differs from the catalog's: FAILED(LIST_MISMATCH) with only the catalog and the manifest in the request log |
| gzip part | Inflated output equals the raw part; the transfer file is deleted; peak space in the folder ≤ R + G; `pause()` while inflating stops within one buffer, the half part file is gone, the transfer file kept, `resume()` inflates it without a request |
| Out of space | `freeBytes` below `Space.exact`: PAUSED(NO_SPACE) after CHECKING with the request log showing only the catalog and the manifest; an injected ENOSPC during a write: PAUSED(NO_SPACE), files kept, Continue resumes |
| Process death | Run to 40 %, cancel the coroutine, new engine on the same folder, `recover()` → PAUSED(APP_ENDED), `resume()` → the next request starts at the bytes on disk; two deaths without `sync_every_bytes` gained → PAUSED(KEEPS_STOPPING), not continued by `onAppVisible`; a death after 100 MB more resets the count |
| Already installed | The fake installer already has the job's pack at its version with every chosen part: `recover()` and CHECKING end the job as DONE, folder deleted, no request |
| Network states | `NetworkState.NONE` → WAITING(NO_NETWORK), no request made; metered without consent → WAITING(NOT_UNMETERED); `allowMetered()` → requests start; connected again → continues; WAITING(NO_NETWORK) and WAITING(NOT_UNMETERED) each end in PAUSED(SYSTEM_STOPPED) after 360 virtual minutes |
| Hold and busy | `changesHeldBy` set at READY → WAITING(TRIP_IN_PROGRESS), no `start` call; cleared → INSTALLING; `startInstall` false while Running → INSTALLER_BUSY |
| Hand-off | With `installsFromFolder` the fake gets `startInstallFromFolder` with the job folder; without it, `startInstall` with a stream that Python's `tarfile` (fixture made once) and the test's own ustar reader read as manifest + parts in order |
| Add house numbers | ADD_OPTIONAL with `changesOptionalParts`: the request log shows only the catalog, the manifest and the addresses part; the fake gets `startAddOptionalParts` with a folder holding exactly those two files; without `changesOptionalParts`, or with another installed `pack_version`, `start` → NOT_SUPPORTED |
| Switch on 404 | A part answers 404 and the re-fetched list has a newer build: the new job has the same kind, `include_optional`, `allow_metered` and `first_run`; the old folder is gone |
| Unknown format | Manifest `manifest_version` above the maximum or `inspect` problems → FAILED(NEEDS_NEWER_APP) / FAILED(REJECTED) before any part request |
| Space formulas | A pack with exactly PA's three parts (no manifest bytes, nothing on disk, margin 200,000,000): `estimate` = 1,868,268,314 when moving and 2,902,107,188 when copying with no routing set on the phone; 2,502,697,754 when moving with a routing set of 634,429,440 bytes (6.3's worked example) |

### 10.2 JVM tests of `:feature:states` (G)

`StateShapes.parse` of the real asset (51 states, every code once, sizes); `StateHitTest` at the fit
zoom of a 393 dp card: central PA → One(PA); DC's label point → Choose containing DC, MD, VA; RI's
label point → Choose containing RI and CT; Lake Michigan's centre → One(MI); 1,000 km off the
Atlantic coast → None; 100 km off New Jersey → NJ in the result; Juneau and Honolulu → AK, HI
(projected with `StateShapes.project`); at 8× over DC → One(DC). `StatesPresenter`: every text of
section 1 for every status, phase and pause reason, the map chip's texts, sizes ("8.5 MB", "553
MB", "1.0 GB", "less than 1 MB"), the date format, `UnitFormatter`-made durations; the cases of
1.4 "Other transitions" that the presenter decides (another state's sheet during a job, Remove
disabled during a job, the sheet while LOADING, the None hint); `showFirstRunPicker` false before
the scan and false when the scan finds a pack in `issues`, true again after a first-run Cancel;
the installer's words only under Details.

### 10.3 G's device checks (`:demo:states` on `beamline_g`, dev server from `data/r3-g/site`)

The site of these checks is G's scratch site, `build_catalog.py --out data/r3-g/site
--manifest-versions 2,3` over the z14 archive (7.1 item 1), served with `serve_packs.py --root
data/r3-g/site`. It is kept apart on purpose from `data/catalog/site` (7.1, 7.2, 10.4), which offers
only the version-3 packs this round's app reads and is the one I and publishing day use.
`:demo:states` hosts `StatesScreen` with a fake `InstalledData` that verifies and moves files into
`files/demo-packs/`. Pass when observed: the first-run screen, the map tap on PA, the chooser near
DC, the sheet on Wi-Fi and (emulator cellular only) on mobile data; a PA download (the z14 archive
as input to `build_catalog.py`) finishes with the notification steps of 1.1; with `--cut-after
100000000` the server log shows a second request with `Range: bytes=100000000-` (or the offset
reached) and the job finishes; process death (`adb root`, then `adb shell kill -9` of the demo's
process, on the emulator image that allows root): reopening shows "Continuing the download…" and
the next request starts at the bytes on disk; `adb shell am force-stop` (the system's Force stop)
then reopening shows "Continuing the download…" as well;
`adb shell dumpsys deviceidle force-idle` during a download: the server log keeps receiving
requests for at least 2 minutes; the server stopped for 10 minutes mid-download: "Couldn't reach
the download server" with every byte kept, and reopening the demo after the server is back
continues it; a Pause from the notification leaves the "Download paused" notification; the debug
StrictMode policy logs no "Untagged socket" line during a download and `adb shell dumpsys netstats
detail` shows the demo's traffic under tag `0xb1a` (the factory of rule N1 works); screenshots of
every view, day and night, looked at; TalkBack reaches the bottom bar right after the title and
reads the list rows and the progress (on an emulator image without TalkBack: `ReadingOrderTest` of
`:feature:states`, which follows the traversal links a screen reader is given; TalkBack itself in
I's phone run, 10.4).

### 10.4 Integration acceptance (I, after the round; emulator, then the owner's phone)

Set-up: `build_catalog.py` over P's v3 PA and OH packs into `data/catalog/site`; `serve_packs.py`;
debug APK built with the matching `beamline.catalogUrl`; app data cleared on the emulator. The
owner's phone is never cleared (rule PV7 of `docs/architecture-saved-places.md`, item 7 for these
journeys); how the journeys run there is said after the table.

| # | Journey | Pass when (observable) |
|---|---|---|
| 1 | Fresh install → driver notice → "Choose your state" | The notice comes first; the server log shows no request before "I understand"; then exactly one `GET /catalog/catalog.json` with only the headers of N3 (`--log-headers`) |
| 2 | Tap Pennsylvania on the map → sheet → Download | The sheet shows 1.1 step 4's texts with PA's catalog sizes; the progress view starts before the notification-permission dialog appears; the progress view and the notification follow 1.1 steps 5 and 7 |
| 3 | Install finishes | The picker closes by itself; the map is fitted to PA's bounds; snackbar "Pennsylvania is ready."; States lists PA "On this phone" with "House numbers: included". Again with the app sent to the background before the end and reopened from the launcher: the same fit and snackbar |
| 4 | Network off (`adb shell svc wifi disable`, `svc data disable`; on the phone: airplane mode) | Search "sheetz" near Erie returns results; directions from Erie to Downs Dr plan and a simulated drive guides (`docs/architecture.md` section 8, rows 0-1 at least); no failure anywhere from the missing network |
| 5 | Interrupted download: `serve_packs.py --cut-after 100000000`, and separately Wi-Fi off for 60 s mid-download, the server stopped for 10 minutes mid-download, and the phone restarted mid-download | Each finishes (the stopped server after "Couldn't reach the download server" and Continue, or reopening the app); the server log shows `Range` requests starting at the bytes already on the phone, never a second full transfer of a finished part; after the restart the app opens on the first-run screen's progress view with "Continuing the download…" |
| 6 | Mobile data only (phone: Wi-Fi off, data on) | The sheet offers "Download on Wi-Fi only" and "Use mobile data"; "Download on Wi-Fi only" waits ("Waiting for Wi-Fi") and goes on by itself when Wi-Fi returns |
| 7 | Not enough space (emulator with a small data partition, or a filler file) | "Not enough space" with figures; nothing larger than the manifest requested (server log); with PA installed, the Ohio sheet's estimate includes PA's merged tile set |
| 8 | Update: rebuild PA with a later `pack_version`, `build_catalog.py`, reopen States, Check again | "Update available"; Update installs; PA works throughout; About shows the new data date |
| 9 | Remove Ohio; remove during a simulated trip | Removed; during the trip Remove is disabled with "You can't remove a state during a trip." |
| 10 | Network rule on the device (4.2 item 5) | For the app's UID, `dumpsys netstats detail` after rows 1-9 minus before: the bytes on tag `0x0` exceed those on tag `0xb1a` by at most 16 KB each way; on the emulator, the `-tcpdump` capture of rows 1-4 has no destination beyond the dev server, DNS and the idle baseline; no "Untagged socket" StrictMode line in logcat |
| 11 | No address in the build | "Downloads are off in this build. Use a file instead: a state file (.tar) copied to this phone."; no request reaches the server; the file route installs PA |
| 12 | House numbers later: install Ohio without house numbers, then Add house numbers, then Remove house numbers | The row says "House numbers: not included", then "included" after a download of only the manifest and the house-number part (server log), then "not included" again; a search for a known Ohio house number (taken from the address part) finds it only while included; the map, routing and search keep working throughout |

On the owner's phone, every session follows rule PV7 of `docs/architecture-saved-places.md`: it
starts cold with the test extra `--es private_data test` (also after the restart of row 5), before
any tap, screenshot or UI dump, and the app's data is never cleared and the app never uninstalled,
whatever the owner has saved. So row 1 (a fresh install, the driver notice, "Choose your state")
and the first-run screen's part of rows 2, 3 and 5 are proved on the emulator only. On the phone,
over the home Wi-Fi (`--bind 0.0.0.0`, the PC's address in `local.properties`), with the debug APK
installed over the app (`adb install -r`): Pennsylvania removed on the States screen and downloaded
again from its "Add a state" section, which gives rows 2-4 there (the notification-permission dialog
only if it was never asked; the banner "Pennsylvania is ready." and **Show on map** fitting the map
in place of the picker closing by itself); row 5 with the phone locked for 5 minutes during the
download, and after the restart the download continuing ("Continuing the download…") with a
`Range` request; row 6; row 12 with Ohio (removed first the same way if it is installed); row 10
over those rows; and once with the router's internet link unplugged (the download must still reach
the PC, 5.2).

## 11. Left out on purpose, and the room left

| Left out now | Room left |
|---|---|
| Checking for updates in the background, automatic updates | `pack_version` and the catalog serial make a periodic check trivial; it needs the owner's word (rule N4) |
| Several downloads at once, a queue | One job folder per job already; the engine's single-job rule is the only limit |
| Mirrors, a CDN, other hosts | A format-2 catalog with `mirrors` and a required signature (3.3); paths are relative, so the same files work under any scope |
| zstd (−9 % transfer) | `encoding` values; an older app lists such an entry as "needs a newer version" |
| A part larger than 2 GiB on GitHub | A later format can split a transfer file into numbered pieces |
| Suggesting the state from the phone's position | The asset carries its projection (`StateShapes.project`), so a coarse fix can be tested on the phone with nothing sent; asked only on a tap |
| "Add the neighbouring state?" after an install | `neighbours` are in the asset |
| Puerto Rico, US Virgin Islands, other territories | Catalog entries without a shape are listed; shapes can be added to the asset |
| A user-initiated data transfer job runner on API 34+ | The engine does not know who runs it; add when phones show stops |
| A Wi-Fi lock for screen-off speed | Measure first on the phone |
| Letting Android clear other apps' caches to make room (`StorageManager.allocateBytes`) | `freeBytes` is one function given by I; switching it to `getAllocatableBytes` together with an `allocateBytes` call before CHECKING changes nothing else |
| A "notify me" for states without a pack | It would have to send something about the user; the list shows what exists |
| Publishing the `.tar` archives for sideloading | `build_catalog.py` could copy them into the release |
| Running file installs in the download service | The service can host any long install later |
| Downloading over the release build before publishing | Only possible over HTTPS; done on publishing day (8.3) |

## 12. Questions only the owner can answer

These are questions 7-9 of `docs/architecture.md` 6.38. The owner answered 1 on 2026-11-16; 2 and 3 are
still open.

1. **Answered 2026-11-16: as built (only after a tap, no setting).** **Mobile data.** The design never uses mobile data without a tap and offers "Download on Wi-Fi
   only" first. Recommendation: keep it (no setting needed now).
2. **The public host.** Recommendation: a separate public repository only for packs (for example
   `beamline-packs`), GitHub Releases as in 8.3, created only when you say so; a paid mirror only
   if GitHub limits downloads.
3. **Update checks.** The list is fetched only when you open the first-run screen or States.
   Recommendation: keep it so for now; a daily check at app start would be the first background
   network use and should be your explicit choice. *The owner's choice (2026-11-24, docs/next-round.md
   item 40):* a daily check, under Online features ("Check for map updates", on with the main switch):
   when the app becomes visible and the last fetch of the list is a day old or more, the list (never
   a pack) is fetched; an installed state with a newer pack then gets the map's chip "Pennsylvania: new
   map data" (several: "New map data for 2 states"), which opens States and stops naming that pack
   version once States has been opened (`ui.json` `updates_seen`).

## Appendix A. Measurements (Pennsylvania, 2026-11-15)

Inputs: `data/map/packs-v2/pa-261114-z14.pmtiles`, `data/routing/packs/pa/routing-pa-261114.tar`,
`data/search/pa-search.sqlite` (their SHA-256 equal the parts of
`data/android/archives-z14/us-pa-2026-11-14.tar`, 1,033,850,880 bytes). Read-only; compressed
copies were written to `data/architecture/tmp/` and deleted. This PC, one thread, Python 3.12.10
(zlib 1.3.1); zstd 1.5.7 through the `libzstd.dll` that ships with Git for Windows (ctypes,
nothing installed); other work was running on the machine, so times are indicative. Ratio =
compressed / raw. Times in seconds: c = compress, d = decompress (to nothing), d+h = decompress and
SHA-256 of the output.

| Part | Raw bytes | SHA-256 | gzip -1 | gzip -6 | gzip -9 | zstd -3 | zstd -19 |
|---|---|---|---|---|---|---|---|
| map | 244,703,514 | 1.18 s | 244,604,326 (0.9996), c 15.3, d 1.04 | 244,558,495 (0.9994), c 14.2, d 1.19 | 244,558,334 (0.9994), c 13.4, d 0.95 | 244,679,078 (0.9999), c 2.0, d 0.42 | not run |
| routing | 634,429,440 | 2.92 s | 265,353,082 (0.4183), c 22.0, d 3.18 | **240,430,789 (0.3790)**, c 41.8, d 3.17, d+h 4.66 | 238,303,809 (0.3756), c 227.3, d 2.35 | 243,882,287 (0.3844), c 4.5, d 0.96 | 207,134,566 (0.3265), c 296.6, d 2.78 |
| search | 154,705,920 | 0.54 s | 68,692,723 (0.4440), c 4.1, d 0.78 | **63,346,936 (0.4095)**, c 6.5, d 0.63, d+h 0.95 | 62,931,742 (0.4068), c 21.9, d 0.55 | 61,239,580 (0.3958), c 1.0, d 0.19 | 47,531,067 (0.3072), c 50.8, d 0.25 |

Totals (map as is): raw 1,033,838,874; gzip -6 on routing and search **548,481,239** (53.1 %,
−485.4 MB); gzip -9 545,939,065 (−2.5 MB more, 249 s instead of 48 s to build); zstd -19
499,369,147 (−49.1 MB more than gzip -6). Hashing the three parts: 4.64 s (about 223 MB/s).
Unpacking routing and search with hashing: 5.6 s. Transfer at 10 Mbit/s: 827 s raw, 439 s gzip; at
50 Mbit/s: 165 s and 88 s (computed). The routing gzip -6 size agrees with the routing track's own
`data/routing/results/compression.json` (240,430,809; 20 bytes of header difference) and with the
hosting research (240,671,366 for the archive's copy).

## Appendix B. Sources

Repository (read 2026-11-15): `docs/architecture.md` (sections 1, 2.2, 2.10-2.12, 4, 5, 6, 7, 8),
`data/architecture/architecture-draft.md`, `docs/pack-format.md`, `android/core/packs/…/PackInstaller.kt`,
`PackManifest.kt`, `StyleComposer.kt`, `android/core/installed/…/InstalledLibrary.kt`,
`android/core/api/…/InstalledData.kt`, `android/core/settings/…`, `android/app/…` (`AndroidManifest.xml`,
`build.gradle.kts`, `AppGraph.kt`, `MainActivity.kt`, `MapViewModel.kt`, `BeamlineApplication.kt`,
`PackManagerScreen.kt`, `assets/style/day.json`), `android/routing/core/…/EngineConfig.kt` and
`ModelAndDataTest.kt`, `tools/check_contracts.py`, `tools/frozen-files.sha256`,
`android/settings.gradle.kts`, `android/gradle/libs.versions.toml`,
`.gitignore`, `tools/fetch_android_map_pack.py`, `android/scripts/phone/make_z14_archive.py`,
`packbuilder/map/overview/overview.yml`, `packbuilder/map/fetch_sources.py`,
`packbuilder/addresses/sources.json`, `docs/experiments/05-android-skeleton.md` (OkHttp versions,
network-capable libraries), `data/map/work/overview/ne_overview.gpkg` (read-only query).

Outside, checked by me for this design:

- WorkManager long-running workers, Android 16 quota note and the `dataSync` manifest snippet:
  https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running
- Foreground service timeouts (6 h in 24 h for `dataSync`, `onTimeout`, stop within seconds, reset
  when the app is foregrounded): https://developer.android.com/develop/background-work/services/fgs/timeout
- Power details (foreground-service state: network "No restrictions"; Android 16 quota for jobs
  alongside a foreground service; active bucket 20 min per 60 min):
  https://developer.android.com/topic/performance/power/power-details
- Doze restrictions (no exemption stated there): https://developer.android.com/training/monitoring-device-state/doze-standby
- AOSP `NetworkPolicyManager.isProcStateAllowedWhileIdleOrPowerSaveMode` (allowed at or below the
  foreground-service threshold or with `PROCESS_CAPABILITY_POWER_RESTRICTED_NETWORK`, granted to
  foreground-service states):
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/net/NetworkPolicyManager.java
- AOSP network security config parser (domain text lower-cased, IP literals not rejected; debug
  overrides only when debuggable):
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/security/net/config/XmlConfigSource.java
- StrictMode `detectUntaggedSockets` (API 26): https://developer.android.com/reference/android/os/StrictMode.VmPolicy.Builder
- StorageManager (`getAllocatableBytes` "typically larger than `File.getUsableSpace()`, since the
  system may be willing to delete cached files", which `allocateBytes` then deletes;
  `getUuidForPath` API 26; `ACTION_MANAGE_STORAGE` API 25; `EXTRA_REQUESTED_BYTES` API 26):
  https://developer.android.com/reference/android/os/storage/StorageManager
- OkHttp versions and dates on Maven Central: https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/;
  licences in the POMs: OkHttp 5.3.2 Apache-2.0, depending on Okio 3.16.4, Apache-2.0:
  https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/5.3.2/okhttp-5.3.2.pom,
  https://repo1.maven.org/maven2/com/squareup/okio/okio/3.16.4/okio-3.16.4.pom
- OkHttp's `Dns` interface (`lookup(hostname): List<InetAddress>`, the order OkHttp tries; the
  3.x javadoc, the interface is unchanged in 5.x as far as I know, inferred):
  https://www.javadoc.io/static/com.squareup.okhttp3/okhttp/3.6.0/okhttp3/Dns.html
- `ApplicationExitInfo.REASON_USER_REQUESTED`: "the user clicked the "Force stop" button of the
  application in the Settings, or removed the application away from Recents", and before Android
  14 also an app update: https://developer.android.com/reference/android/app/ApplicationExitInfo
- `TrafficStats.tagSocket` tags "with any statistics parameters active for the current thread";
  `getAndSetThreadStatsTag` API 26: https://developer.android.com/reference/android/net/TrafficStats
- `NetworkCapabilities`: INTERNET means configured to reach the internet, VALIDATED that "the
  system found actual connectivity to the general Internet the last time it checked":
  https://developer.android.com/reference/android/net/NetworkCapabilities
- `Network.bindSocket(Socket)` (API 21; "The socket must not be connected"), `getAllByName`:
  https://developer.android.com/reference/android/net/Network
- MapLibre Native Android 13.7.1: `HttpRequestImpl.setOkHttpClient(client: Call.Factory?)`:
  https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.module.http/-http-request-impl/index.html;
  `MapLibre.setConnected` ("Manually sets the connectivity state of the app"; nothing about
  blocking requests): https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android/-map-libre/set-connected.html
- Emulator `-tcpdump` ("capturing all network packets that are sent through the virtual Ethernet
  LAN of the emulator"): https://developer.android.com/studio/run/emulator-commandline
- GitHub release download, probed here on 2026-11-15 with a public asset of an unrelated
  open-source project and `Range: bytes=0-15`: `github.com` answered 302 with a `Location` on
  `release-assets.githubusercontent.com` carrying a signed query string (`sp`, `sv`, `se`, `sig`,
  `jwt` and others), which answered 206 with a strong `ETag`, `Accept-Ranges: bytes` and
  `Content-Range`, served by Azure Blob storage.
- Python's cryptographic services (no public-key signatures in the standard library):
  https://docs.python.org/3/library/crypto.html

From the three research reports (their URLs, not re-checked by me): GitHub release limits (each
file under 2 GiB, 1000 assets, no total or bandwidth limit; AUP section 9 "excessive bandwidth"):
https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases,
https://docs.github.com/en/site-policy/acceptable-use-policies/github-acceptable-use-policies;
release download redirects, Range, `If-Range` ignored and `If-Match` honoured by the storage
backend (their live probes); RFC 9110 13.1.5 https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.5;
foreground service types https://developer.android.com/develop/background-work/services/fgs/service-types;
user stop in Task Manager https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping;
notification permission https://developer.android.com/develop/ui/views/notifications/notification-permission;
user-initiated data transfer https://developer.android.com/develop/background-work/background-tasks/uidt;
metered networks https://developer.android.com/develop/connectivity/network-ops/reading-network-state;
network security config https://developer.android.com/privacy-and-security/security-config;
local network permission (API 37) https://developer.android.com/privacy-and-security/local-network-permission;
`java.security.Signature` algorithms https://developer.android.com/reference/java/security/Signature;
Natural Earth terms https://www.naturalearthdata.com/about/terms-of-use/;
ODbL https://opendatacommons.org/licenses/odbl/1-0/ and OSMF attribution guidelines
https://osmfoundation.org/wiki/Licence/Attribution_Guidelines; Android and Compose accessibility
https://developer.android.com/guide/topics/ui/accessibility/apps,
https://developer.android.com/develop/ui/compose/accessibility/semantics.

Where the researchers disagreed, what I followed: catalog signing (hosting: Ed25519 now; android:
optional ECDSA) — neither now, room left, required before mirrors (3.3); the mechanism (android:
WorkManager) — the app's own `dataSync` service after reading Google's WorkManager and power pages
(5.1); the picker's drawing (state-picker: MapLibre layers) — a drawn Canvas map (6.8); compression
(hosting: gzip on routing and search) — the same, confirmed by my own measurement; the dev server
(hosting: Caddy or nginx) — a standard-library server with real Range support (7.2), so nothing is
installed; the signed-redirect lifetime (5 min jwt against about 1 h) — irrelevant to the design,
since every attempt starts again at the stable URL.
