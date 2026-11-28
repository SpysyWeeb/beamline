# :feature:states

Getting state packs, the Android part (`docs/architecture-downloads.md` sections 1, 5 and 6.7-6.9):
`GetPacks` (the in-process controller I creates once in `AppGraph`), `StatesScreen` (the first-run
"Choose your state" and the States screen), the drawn US map and its tap rules, `DownloadService`
(foreground service, type `dataSync`) with its notifications, `AndroidNetworkWatcher`,
`TaggingSocketFactory`, the file route "Use a file instead" and the library manifest of 5.3.
Android library, Compose. Owner during the feature round: track G. Report:
`docs/experiments/18-getting-packs.md`.

## What I wires (9.4)

| Piece | How |
|---|---|
| `GetPacks(context, installed, settings, units, GetPacksConfig(...), scope)` | once per process in `AppGraph` (9.4 item 1); `GetPacksConfig` takes `BuildConfig.CATALOG_URL`, `BuildConfig.DEBUG`, `PackManifest.SUPPORTED_VERSION` twice, `getExternalFilesDir(null)/downloads`, `filesDir/getpacks`, the same `freeBytes` as `InstalledLibrary` (`File.usableSpace`), the routing folder's size, and the two intents `OPEN_STATES` / `OPEN_MAP` to `MainActivity` |
| `GetPacksHost` | implemented by the `Application`, so `DownloadService` finds the process's `GetPacks` |
| `getPacks.onAppVisible()` | from `MainActivity.onStart`: recovers once per process and continues a job that ended with the process |
| `StatesScreen(getPacks, StatesMode.FIRST_RUN, onClose)` | after the driver notice while `getPacks.showFirstRunPicker` is true; it calls `onClose` itself when the picker is no longer needed (a state installed, Not now, "Look at the map while you wait") |
| `StatesScreen(getPacks, StatesMode.MANAGE, onClose)` | the States screen (Settings, the map chip, search's `OpenStates`, the notifications) |
| `getPacks.mapChip` | under the search pill while not null and only while the pill shows (1.5, SP2) |
| `getPacks.showOnMap` / `showOnMapDone()` | when not null and the map shows: fit to its bounds, snackbar "<name> is ready.", then `showOnMapDone()` |

The screen draws with the caller's `MaterialTheme` and keeps clear of the system bars and the
keyboard itself (`imePadding`); the activity sets the bars' icon colour (dark by day, light by night,
as `DemoActivity` does with `WindowCompat.getInsetsController(...).isAppearanceLightStatusBars`). The
States screen has a top bar as Settings has (a Back arrow that calls `onClose`, then the title); the
first-run screen keeps its title alone. Everything else but the bottom bar is one scrolling list (in
portrait the map card is one of its entries; the search field stays at the top once it gets there);
in landscape the top bar and the map are the left pane. Each visit of the screen starts fresh (no old
search text, selection or banner). Its bottom bar (Use a file instead, Not now) is fixed outside the
list, hidden while the keyboard is up, and is a traversal group with `traversalIndex = -1`, so a
screen reader reaches it right after the top bar (`ReadingOrderTest`). The notification permission
(Android 13+) is asked once, right after the first Download has started; the progress view says so
when it is refused.

Threading: every action that touches files (the engine's start, pause, resume, cancel, dismiss;
`onAppVisible`'s recover and resume; each read and write of `ui.json`) runs on
`GetPacksCore.actions`, one task at a time on `Dispatchers.IO`, in the order asked for. The screen's
state is made on `Dispatchers.IO`. Nothing in this module reads or writes a file on the main thread
(StrictMode on `:demo:states`, report 18). `InstalledData`'s suspend functions (`remove`, `inspect`)
must be main-safe themselves.

## Files

| File | What |
|---|---|
| `GetPacks.kt`, `StatesScreen.kt`, `DownloadService.kt`, `map/StateShapes.kt` | the contract files (6.8, 6.9); bodies delegate to the files below |
| `GetPacksCore.kt` | the catalog source, the engine, `ui.json`, the first-run rule, the chip, show-on-map, the screens' actions, the in-process watcher (a download held by a trip or a busy installer is installed when that ends) |
| `model/StatesPresenter.kt`, `model/Texts.kt` | pure: every text of section 1, the per-state status, the chip, the notification texts |
| `model/ListEntries.kt` | pure: what the screen's one scrolling list holds, in order, and a selected row's index in it |
| `model/FirstRun.kt` | pure: `showFirstRunPicker` and the changes of the remembered choice |
| `model/JobSwitch.kt` | pure: when the engine switched the job to a newer build after a 404 ("A newer Pennsylvania was published. Downloading that one.") |
| `model/Continuing.kt` | pure: how long "Continuing the download…" shows (until bytes move again, then 3 s) |
| `StatesScreenImpl.kt`, `map/StatesMap.kt` | Compose: the screens (`StatesBody`: top bar, the one list with the map card, bottom bar, without GetPacks), sheets (`SheetView`), dialogs, the chooser, the drawn map (Canvas, pinch and double-tap zoom about the finger, one-finger pan only when zoomed in; `StatesMapView` holds zoom and centre so "Whole country" sits under the map, not over it; a state picked on the map is outlined, not zoomed) |
| `map/MapBounds.kt` | pure: the map's centre kept so the card shows at most its 16 dp margin past the country; zoom about a point |
| `map/ShapesCode.kt` | parse of `assets/states/us_states.json`, the projection, `StateHitTest` |
| `DownloadServiceBody.kt` | the service: foreground at once (id 4210), `engine.run()` on IO, the notification at most once per second, the partial wake lock while bytes move, the end notification, `onTimeout` |
| `AndroidNetworkWatcher.kt`, `TaggingSocketFactory.kt` | 5.2 and N1: network state by kind of catalog host; every socket tagged `0x0B1A` and, for a local host, bound to the Wi-Fi network |
| `UiStore.kt` | `getpacks/ui.json`, read and written only on `GetPacksCore.actions` |
| `src/main/AndroidManifest.xml` | the permissions and the service of 5.3 |
| `assets/states/us_states.json` | written by `packbuilder/catalog/build_state_shapes.py` (never by hand; `--check` compares) |

## Tests

```
sh tools/round/gradle.sh g :feature:states:testDebugUnitTest
sh tools/round/gradle.sh g :feature:states:connectedDebugAndroidTest     # emulator beamline_g up (sh tools/round/emulator.sh g start)
```

JVM tests of 10.2 (55): `StateShapesTest` (the real asset, the taps at a 393 dp card, the inside
rule and each of its differences from 6.8), `StatesPresenterTest` (the texts of section 1 by
status, phase and reason; sheets, the window of a state on this phone; the search, its X "Clear
text" and "No state matches"; destructive actions; the chip; notifications; the file route), `ListEntriesTest` (the
list's order), `MapBoundsTest` (the clamp and the zoom about a point), `ScreenStateTest` (a search
that hides the selection clears it), `FirstRunTest` (`showFirstRunPicker`), `JobSwitchTest`,
`ContinuingTest`, `UiStoreTest` (no file work on the caller's thread), `ListIsStaleTest`.

Device tests (`src/androidTest`, 24; inputs shared in `StatesTestInputs.kt`): `ReadingOrderTest`
turns on a do-nothing accessibility service from the test APK (so Compose computes the traversal
links it gives a screen reader), shows `StatesBody` with the presenter's texts and follows
`AccessibilityNodeInfo.traversalBefore` from the title (on the States screen from Back) through
UiAutomation: the bar comes right after the top bar, then the intro, the progress (when a job runs),
the map and the rows; a control case shows that a bar without `isTraversalGroup` is read last; every
tappable node has a name (also the search field with words typed in), and the download sheet's
house-number box is one stop with its name. The orders are logged under the tag
`BeamlineReadingOrder`. The test restores the two accessibility settings it changes. TalkBack itself
is not on the round's emulator image. `StatesScreenLayoutTest` (the one list in a short window, a
drag on the map scrolls it, the search going to the top, landscape with the keyboard up, Back, "No
state matches", the installed state's window, Remove apart), `StatesMapTapTest` (taps zoomed in, a
1x drag does not move the map, double tap about the finger, a map tap does not zoom, the clamp, back
to the whole country), `StatesVisitTest` (a second visit starts fresh, through a real `GetPacks`
with downloads off).
The other device checks of 10.3 run in `:demo:states`.

## Decisions taken here (details in the report)

- Tap rule: inside a state the chooser opens only when the tapped state is small and another
  state's outline is within `nearDp / 8` (2 dp); it then lists every state within 16 dp, small or
  not. 6.8's wording fails three of 10.2's cases (central PA, Lake Michigan's middle, VA at DC's
  label); `StateShapesTest.theInsideRuleAndWhyItDiffersFrom68` pins each. Cost: 1 dp inside Virginia
  next to DC gives Virginia. Alaska and Hawaii are drawn at fitted scales (0.285 and 0.947) so they
  fit the inset boxes. Amendment asked in `data/architecture/requests/g.md`.
- Free space and needs are written in GB as the sheet examples do; download sizes by the size rule.
- "It will be installed when the other install has finished." (INSTALLER_BUSY) is a draft: section 1
  has no text for it.
- A file install started on the first-run screen keeps the picker open while it runs and opens the
  map on the state when done; a refused file install shows "<name> can't be installed" with Details.
  A file the installer cannot read at all shows the same under the file's name, with the draft
  "This file is not a Beamline state pack." under Details.
- A failed job's row says "Download failed" (1.4's title; section 1 has no row line for it) and its
  progress view has no bar. Update and Add house numbers are off only while a job is unfinished
  (a DONE or FAILED job stays the engine's current one until the next start).
