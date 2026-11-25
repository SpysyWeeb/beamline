# Beamline: saved places, Home and Work (companion contract, tracks S and M)

Date: 2026-11-15. Status: a companion contract of `docs/architecture.md` for the feature round,
adopted in Round SP0 (`docs/architecture.md` section 0 and appendix B), after Round G0 and its
companion `docs/architecture-downloads.md`. Every module, contract file and data file named here
exists in the tree, compiles, and equals the code shown here (checked with
`python tools/check_contracts.py`, which reads this document together with the other two).
`docs/architecture.md` holds what crosses tracks: the search bar's place in short, the frozen
`:core:api` files and every contract file this design changed (`Model.kt`, `Saved.kt`,
`MapScene.kt`, `Scene.kt`, `SearchUi.kt`, `SearchScreens.kt`, `BeamlineNavigationSession.kt`) and
the two rule files as whole files, the persistence table, the privacy and logging rules in short,
ownership and definitions of done, shared files, the integration checklist, decisions 6.54-6.69, the
owner's questions and the end-to-end test. This document holds the journeys and exact texts, the
file format, the privacy rules in full, the new contract files of S (`SavedPlaces.kt`,
`SavedUi.kt`), the rules as data, the artwork and the tests; section 6.4 says where the main
document carries each part. A `// FILE:` block (and a whole data file) lives in one document only:
the `// EXCERPT:` blocks here show the parts of those files that this design changed and are checked
against the tree like the rest. This document is frozen like the main one: changes go through the
orchestrator (`docs/architecture.md` 5.2), and a difference between the documents is a defect for
the orchestrator to fix. Package root `P` = `io.github.spysyweeb.beamline`; Kotlin paths are under
`android/`, other paths are from the repository root.

The owner's request (2026-11-15): "The app will have a search bar at the top, right? We should also
have a way for the user to add important location, like home, work, or others if they like." What
the lead engineer told the owner, and which this design delivers: the search bar sits at the top of
the map while browsing, and during a drive the turn banner has the top while search stays reachable
for adding a stop; Home and Work are fixed slots, plus any number of the user's own named places;
any place that was searched for or tapped on the map can be saved; Home and Work sit at the top of
the search screen and one tap goes straight to route choice; a saved place can be a stop or the
starting point; places can be renamed, moved and deleted; they are stored only on the phone and
never written to the app's logs; by default they show as small markers on the map, and a switch
turns the markers off.

How to read: section 0 indexes the decisions (numbered 6.54-6.69 in `docs/architecture.md`); 1 is
the search bar; 2 the journeys, word for word; 3 the file; 4 privacy and safety; 5 the code
contracts; 6 ownership, Round SP0 and where `docs/architecture.md` carries this design; 7 the rules
as data; 8 the artwork; 9 Android Auto and the Python client; 10 tests and acceptance; 11 what is
left out; 12 the owner's questions. Rule ids: **SP1-SP57** (behaviour and data; the numbers have
gaps) and **PV1-PV8** (privacy); they do not collide with the ids of `docs/architecture.md` (G, B,
O, A, C, P, M, K, S, D, R) or of `docs/architecture-downloads.md` (N1-N5). A section number given
with a document's name ("`docs/architecture.md` 2.2") is that document's; one without a name is this
document's. "Verified" = read in the named file or source; *(estimate)* = not measured.

## 0. Decisions

The decisions of this design are 6.54-6.69 in `docs/architecture.md` section 6, where each has its
question, decision and reason. Where their details are here:

| # | Question | Rules | Sections |
|---|---|---|---|
| 6.54 | Where is the search bar? | SP1-SP3 | 1 |
| 6.55 | Model | PV2 item 1 | 5.1, 5.2 |
| 6.56 | Store | SP51 | 3.3, 5.3 |
| 6.57 | File | SP50, SP52, SP53 | 3.1, 3.2 |
| 6.58 | Identity | SP54 | 3.4 |
| 6.59 | One tap | SP12, SP13 | 2.4 |
| 6.60 | Replacing Home | SP28 | 2.2, 2.6 |
| 6.61 | Names | SP26, SP55 | 2.7, 3.4 |
| 6.62 | Search | SP20-SP22 | 2.7 |
| 6.63 | Recents | SP30, SP31 | 2.9 |
| 6.64 | Markers | SP40-SP46 | 2.8, 7.2, 8 |
| 6.65 | Backup and transfer | PV4 | 4 |
| 6.66 | Logging | PV2, PV3 | 4 |
| 6.67 | Export and import | | 3.6, 11 |
| 6.68 | Tests on the owner's phone | PV7 | 4 |
| 6.69 | While driving | SP4 | 2 |

## 1. The search bar

What `docs/architecture.md` fixed before Round SP0, quoted: `:feature:search` holds the "search bar
and screen" (1, module map); `SearchBar(onClick: () -> Unit, modifier: Modifier = Modifier)` is "The
search pill on the map" (2.6, owned by S); in `:app`, "Flows: search bar → `SearchScreen` (GO_TO;
ADD_STOP from `DriveEvent.SearchForStop`; SET_START from `DriveEvent.ChooseStart`)" (5.4 item 6). It
did not say where the pill sits or when it shows. The rules below fill that (in short, rule S8
there); the contract of `SearchBar` does not change.

- **SP1 When.** The pill shows while the trip phase is IDLE or RESUME_OFFERED and no search screen,
  saved places screen or map pick (SP16) is open. A place sheet does not hide it. It is hidden in
  PLANNING, CHOOSING, GUIDING, REROUTING and ARRIVED. On the first run it appears with the map, after
  the driver notice and "Choose your state" (`docs/architecture.md` 5.4 item 3,
  `docs/architecture-downloads.md` 1.1). With nothing installed (the user chose "Not now") it still
  shows (search then has "Add a state", rule S7, and saved names still match, SP21).
- **SP2 Where and what (browsing).** At the top of the map, directly under the status bar
  (`statusBarsPadding()`), 8 dp from the start edge and 8 dp below the status bar. One row: the
  pill (height 48 dp, fully rounded, width = the row minus the button, at most 480 dp), 8 dp gap,
  then the existing round Settings button (46 dp, today's `RoundButton` in
  `app/src/main/kotlin/io/github/spysyweeb/beamline/app/map/MapScreen.kt`). The States round button
  stays under the Settings button, as in today's top-right column (it opens G's States screen,
  `docs/architecture-downloads.md` 1.2). Today's pack-problem notice and G's map chip
  (`GetPacks.mapChip`: "Choose your state", or a download's progress, `docs/architecture-downloads.md`
  1.5) go under the pill. The chip shows only while the pill shows (SP1): whenever the pill is
  hidden, the chip is too, so it never covers the route preview or the turn banner (the download's
  notification carries the job meanwhile). The pill:
  background `panel(night)` as today's round buttons, shadow 3 dp, a magnifier glyph (24 dp, drawn by
  S with the search screen) 16 dp from its start, then the hint **"Search places or addresses"** in
  the panel text colour. Its accessibility label is the same text, "Search places or addresses"
  (SP5). Tapping it opens the search screen in GO_TO (unchanged).
- **SP3 Route choice and guidance.** During route choice (PLANNING, CHOOSING) the top of the map is
  free for the route preview; the route sheet's start row ("Your position", `DriveEvent.ChooseStart`
  → SET_START) and its or the stops editor's "Add stop" (`DriveEvent.SearchForStop` → ADD_STOP)
  open search. Closing the route sheet ends planning and the pill returns. During GUIDING,
  REROUTING and ARRIVED the turn banner has the top; the stops editor's "Add stop" opens search in
  ADD_STOP, over the guidance screen, while guidance continues, without raising the keyboard (SP4).
  During a map pick (SP16) the pill's place is taken by the pick prompt.

The search screen covers the whole map (as `docs/architecture.md` has it); it shows no pill of its own, only
its text field.

## 2. Journeys, with the words on screen

Every text below is exact. Texts of the search screen, the place sheet and the saved-places screens
are made by S's controllers (`SearchFeature`, `SavedPlacesFeature`) and put into their UI states, so
the JVM tests compare them; the composables add only the fixed words "Cancel", "OK", "Name" (field
label) and "Back" (accessibility). Distances come from `UnitFormatter` (`docs/architecture.md` 2.9).
Three rules hold for every screen below:

- **SP4 While driving.** In GUIDING, REROUTING and ARRIVED nothing about saved places can be
  created or changed, and using one needs no typing. (1) A place sheet offers no SAVE or EDIT: in
  GUIDING and REROUTING [ADD_STOP, DIRECTIONS], in ARRIVED [DIRECTIONS]
  (`SearchFeature.placeSheet(..., driving = true)`); a tapped saved marker's sheet included (SP44).
  (2) The saved places screen opened from ADD_STOP or SET_START search is **pick-only** in every
  phase (`SavedListUi.pickOnly`): its rows only, with no "Edit", no "Set home" / "Set work" rows, no
  "Add a place" and no switch. (3) When Settings is opened in those phases, its "Saved places" row
  reads **"Not while guiding"** and opens nothing: the words with which installs and removals are
  refused during a trip (`docs/architecture.md` 2.2 rule 4). (4) Search opened in ADD_STOP in those phases does not raise the
  keyboard (`SearchUi.focusField` false): the Home and Work rows and "Saved places" are at the top
  and one tap adds the stop; the keyboard comes up only when the user taps the field (ADD_STOP
  search is otherwise as `docs/architecture.md` has it). SAVE mode and the map pick start only from GO_TO search,
  a sheet's "Save" or "Edit", or a full list, so with (1)-(3) neither can start while driving.
- **SP5 Accessibility.** (1) The pill's accessibility label is its visible text (SP2). (2) Every
  row of the search screen and of the saved places screen is one focus stop that reads its texts in
  order ("Home, Erie Maritime Museum, 100 East Front Street, Erie, 2.6 mi"); a row's "Edit" is a
  separate focus stop labelled **"Edit {name}"** ("Edit Home", "Edit Mom's", `SavedRowUi.editLabel`);
  every tap target is at least 48 by 48 dp. (3) The save dialog's chips are radio buttons with a
  selected state; the switch is labelled "Show on the map" with its on or off state. (4) The problem
  lines (2.3), the pick bar's problem and the notices of SP51-SP53 are polite live regions, read out
  when they appear. (5) The map markers are not in the accessibility tree, and nothing needs them:
  going to, adding as a stop or start, renaming, changing and removing a saved place all work from
  search and the list, and a place can be set from search or "Use my position" (SP23); only
  choosing a point on the map needs sight. (6) Texts use `sp` and grow with the font size (SP6).
- **SP6 Long texts.** In every saved row the title (a name of up to 40 characters) is one line,
  cut with an ellipsis; the description wraps to at most 2 lines, then an ellipsis; the distance,
  the note and "Edit" never shrink and sit at the end of the row. At a font scale above 1.3 the row
  stacks: title, then description, then distance and note on a third line, with "Edit" at the end
  of the title line. Sheet and dialog titles wrap to 2 lines.

### 2.1 The search screen with saved places

Top to bottom in START (empty text), by mode:

| Row | GO_TO | ADD_STOP, SET_START | SAVE (2.3) |
|---|---|---|---|
| Header line above the field | none | none | "Set home", "Set work", "Save place", "New location for Home" / "New location for Work" / "New location for {name}" |
| Notice (SP52, SP53) | when there is one, first, with "OK" | none | none |
| Home | set: icon `saved_home`, "Home", the place's description, distance; empty: icon `saved_home`, "Set home" | only when set | none |
| Work | set: icon `saved_work`, "Work", description, distance; empty: "Set work" | only when set | none |
| "Saved places" | always; subtitle "None yet", "1 place", "{n} places" (all saved places, Home and Work counted) | only when at least one other place exists | none |
| "Use my position" | none | none | first row, only with a fresh, precise fix (SP23) |
| "Choose on the map" | none | none | next row, no icon |
| Category shortcuts | as `docs/architecture.md` (S6) | as there | none |
| Recents | as `docs/architecture.md`, minus saved places (SP30) | same | same |

In SET_START the row of the saved place that is the trip's destination carries the note **"Already
your destination"** and a tap on it does nothing (`open(..., destination)`).

- **SP10** The description of a saved place is `SavedPlace.description` (5.1): its own place name
  unless that equals its name, then its subtitle, joined with ", " ("Erie Maritime Museum, 100 East
  Front Street, Erie"; for a saved point just its coordinates, "42.08418, -80.17321", SP16). The
  distance text shows only when the ranking position is a GPS fix
  (`NearSource.GPS`); with the map centre or no position it is left out. A saved place no routing
  tile covers (`InstalledSet.routing?.covers(point) != true`) gets the note "Outside the installed
  maps".
- **SP11** In a store that is read-only (a newer file, SP53) the search screen shows no Home, Work
  or "Saved places" rows (the notice of SP53 stands in their place until "OK"), and saved names do
  not match.

### 2.2 a. Setting Home (and Work) the first time

**From the search screen.** Tap the search pill, then **"Set home"**. The screen stays open and
switches to SAVE mode for Home: header **"Set home"**, the field empty with the hint "Search places
or addresses", rows **"Use my position"** (with a fresh, precise fix), **"Choose on the map"** and
the recents. Then one of:

1. **Search:** type ("erie maritime museum"), tap a result. Home is saved at once (no extra step),
   and the screen returns to GO_TO START, where the first row now reads **"Home"** with the
   description and distance. The result rows already carry the engine's "Exact address" / "Street
   only" note, so the user sees how precise an address is before tapping.
2. **Where the user is:** tap **"Use my position"** (SP23). Home is saved at once at the current
   fix, and the screen returns to GO_TO START with the row **"Home"** and the point's coordinates.
   For a user at home this is the quickest way: no installed state, no typing, no map.
3. **A point on the map:** tap **"Choose on the map"**. The search screen gives way to the map pick
   (SP16): prompt at the top **"Tap the map at your home"**, a bar at the bottom **"No point chosen
   yet"** with **"Cancel"** and **"Set home"** (off); the my-location button stays above the bar.
   Each tap on the map (or long press) moves the chosen point there: the PICKED pin is drawn at it,
   and the bar shows **"Point on the map"** (or the name of a POI label that was tapped) and the
   coordinates **"42.13646, -80.08718"** (five decimals). Below zoom 16 the bar says **"Zoom in to
   choose the exact spot."** and "Set home" stays off (SP24). **"Set home"** saves it; the bar
   closes and the search screen is back at GO_TO START with the row **"Home"** (SP25). Back or
   **"Cancel"** ends the pick without saving and returns there too.

"Set work" works the same with "Set work" and "Tap the map at your work".

**From a place sheet** (any search result, recent, tapped POI, dropped pin or map point): the sheet
has a **"Save"** button (SP19). It opens the save dialog:

```
Save place
Erie Maritime Museum
100 East Front Street, Erie
[ Home ]  [ Work ]  [ Other ]          (chips with the three sprite icons; "Other" selected)
Name: [ Erie Maritime Museum      ]     (only with "Other")
                         Cancel   Save
```

Choosing **"Home"** hides the name field. If a Home exists, the line **"This replaces your home:
{description of the old home}."** appears and the button reads **"Replace"** (that is the
confirmation, SP28). After the save the sheet shows **"Saved as Home"** and an **"Edit"** button in
place of "Save".

- **SP15 SAVE mode.** `SearchFeature` enters SAVE mode for a target (`SaveTarget.Home`, `Work`,
  `NewPlace`, `Move(id)`) either itself, from an empty Home or Work row in GO_TO (it returns to GO_TO
  START after the save), or through `openToSave(target, context)`, called by the app from the saved
  places screen or the Edit menu (after the save, or on Close, `onEvent` returns
  `SearchOutput.Closed`). A pick in
  SAVE mode is never added to the recents. For Home, Work and Move the pick is written by
  `SearchFeature` itself; for NewPlace it returns `SearchOutput.SaveNew(place)` and the app opens the
  save dialog. "Use my position" is a pick like a result (SP23). "Choose on the map" returns
  `SearchOutput.PickOnMap(target)` and ends SAVE mode as a save would (SP25). Shortcuts are hidden
  in SAVE mode and saved places are not matched there (SP21); typing searches as in GO_TO.
- **SP16 Map pick.** Started by `SavedPlacesFeature.startMapPick(target)`; it can start in every
  phase but GUIDING, REROUTING and ARRIVED (SP4). While it runs, the app hides the search pill, the
  place sheet and the drive overlay's sheets (it does not close them, SP25), keeps the
  my-location button and the credit (line or mark) above the bar, forwards every `MapGesture.Tap` and
  `LongPress` with the camera's zoom to `onMapTap(point, poi, zoom)`, and passes the chosen place to
  `MapSceneMapper.scene(picked = ...)`. A tap on a POI label takes the POI's name and point
  (`Place(name, point, MAP_POI)`); any other tap, including on a saved marker, takes the point
  (`Place("Point on the map", point, MAP_PIN, subtitle = "lat, lon" with five decimals)`). A point
  (any MAP_PIN place: a map point, a dropped pin, "Your position") is saved with its coordinates as
  its place name and no subtitle (3.1), so its description is just "42.13646, -80.08718". Prompts:
  **"Tap the map at your home"**, **"Tap the map at your work"**, **"Tap the map at the place"**
  (NewPlace), **"Tap the map at the new location"** (Move). The confirm button reads **"Set
  home"**, **"Set work"**, **"Save"** (NewPlace: it opens the save dialog over the map, and Cancel
  there returns to the pick) or **"Use this point"** (Move). The app does not move the camera at the
  start of a pick (rule K1 of `docs/architecture.md`); the my-location button centres it on the user as always.
- **SP23 Use my position.** In SAVE mode START, when the search context is a GPS fix
  (`NearSource.GPS`, at most `near.fix_max_age_s` old, rule S1) with an accuracy of at most
  `saved.position_max_accuracy_m` (30 m; `SearchContext.accuracyM`), the first row is **"Use my
  position"** (no icon, no subtitle, as "Choose on the map"). A tap picks `Place("Your position", fix,
  MAP_PIN, subtitle = "lat, lon")` for the target exactly as a result tap does: Home, Work and Move
  are written at once, NewPlace returns `SaveNew` (the dialog then shows "Your position" and the
  coordinates, with an empty name, SP17). Without such a fix (none, an old one, approximate location
  only, or the map centre) the row is not shown. A point outside the installed maps is saved with
  the note "Outside the installed maps": the user may add the state later.
- **SP24 Pick precision.** A tap on a POI label is exact at any zoom. Any other tap counts only at
  a camera zoom of at least `saved.pick_min_zoom` (16, about 1 m per dp in Pennsylvania): below it
  the PICKED pin is still drawn, the confirm button stays off and the bar shows **"Zoom in to choose
  the exact spot."** A point outside every installed state's map box (`PackInfo.bounds`), where the
  map shows only the overview or a plain background, cannot be confirmed: **"Add this state to choose
  a point here."** ("Use my position" and search still work there.) So a map pick never stores a
  Home that is a kilometre off.
- **SP25 Where a pick returns.** A pick hides, and does not close, what it was started from: the
  GO_TO search screen (which `SearchFeature` has already put back at START, SP15), the saved places
  screen, or the place sheet whose "Edit" led to it, and in PLANNING or CHOOSING the route sheet.
  When the pick ends, by its confirm button (for NewPlace: the save dialog's "Save"), by
  "Cancel" or by Back, `SavedPlacesFeature` returns `SavedOutput.PickEnded(place or null)` and the app
  shows again exactly what it hid; the search screen and the list show the new or moved place in
  their rows, a sheet is rebuilt at the new point (SP19). Search opened by `openToSave` for the pick
  (from the list or a sheet's Edit) is closed when the pick starts, so after the pick the user is
  back on that list or sheet.

### 2.3 b. Saving any other place, and the default name

From the place sheet: **"Save"** → the save dialog with "Other" selected. From the saved places
screen: **"Add a place"** → search in SAVE mode with header **"Save place"**, "Use my position" and
"Choose on the map" → a pick → the same dialog. **"Save"** stores it; the sheet (or the list) shows
it.

- **SP17 Default name** (the name field's text when the dialog opens, all text selected): the
  place's own name, trimmed and cut to `name_max_chars` (40) characters as SP26 counts them, for a
  search result, a recent or a tapped POI ("Sheetz", "100 East Front Street", "Millcreek Mall").
  For a point (source MAP_PIN: "Dropped pin", "Point on the map", "Your position") the field starts
  empty and **"Save"** is off until a name is typed. If the default name is taken or reserved, the
  field keeps it and shows the problem (below); the user edits it.
- Problems under the field (Save off while one shows): **"Type a name."**; **"Use at least one
  letter or number."** (the name key is empty, SP26); **"Up to 40 characters."**; **"You already
  have a place called {name}."**; **"Choose Home above to save your home."** (the name's key is
  "home"); **"Choose Work above to save your work."**; **"This place is already saved as
  {name}."** (SP55 rule 2); **"You can save 100 places besides Home and Work. Remove one to save
  another."** (only with "Other" chosen, SP56). After a failed write: **"Could not save. The phone
  may be out of space."** (SP51). The Rename dialog (2.6) has no chips, so there the reserved names
  read **"Use Make home to make this your home."** and **"Use Make work to make this your
  work."**

### 2.4 c. Going to a saved place

- **SP12 One tap.** In GO_TO search, a tap on the Home or Work row, on a saved result row, or on a
  row of the saved places screen opened from GO_TO search, returns `PlanTrip(place)` when a routing
  tile covers the place. The app then does exactly what "Directions" does today (`docs/architecture.md` 5.4 item 6
  and section 4: asks for location first when there is no start point and no permission),
  and sends `NavIntent.PlanTo(savedPlace.toPlace())` (SP18 for the phase rules). No place sheet is
  shown: the route sheet appears with the stop row "Home" and its description.
- **No position fix:** unchanged behaviour of `docs/architecture.md` (rule P1): the session waits up to 15 s, then the
  route sheet shows "No GPS position yet. Choose a start point or try again."; its start row opens
  search in SET_START, where the other saved places are one tap away too (the destination's own row
  reads "Already your destination", 2.1). Location refused or approximate only: as `docs/architecture.md` has it (D4).
- **SP13 Outside every installed state** (or nothing installed): the tap returns `ShowPlace(place)`
  instead; the app opens the place sheet: title "Home", the description, note **"Outside the
  installed maps"**, buttons **"Add a state"** and **"Edit"** ("Edit" not while driving, SP4). The
  same in ADD_STOP and SET_START
  mode: an uncovered saved place is never added as a stop or start; its sheet opens (during
  guidance "Add a state" leads to G's States screen, where a download may start but is installed only
  when the trip has ended, and Remove is off: `docs/architecture-downloads.md` 1.2 and 1.4).
- **SP14 ADD_STOP and SET_START.** A tap on Home, Work, a saved result or a saved-list row returns
  `Picked(place, mode)` (the list returns `SavedOutput.Picked`) and the app sends
  `NavIntent.AddStop(place)` or `NavIntent.SetOrigin(Origin.FromPlace(place))`, as for any pick.
  In SET_START the saved place that is the trip's destination (the last stop's `savedId`, else
  `SavedPlaces.find` of it) is not offered as the start: its row carries "Already your
  destination" and a tap does nothing. The app passes the destination to `SearchFeature.open` and
  `SavedPlacesFeature.openList`.
- **SP18 When a trip already exists.** GO_TO search is only reachable in IDLE and RESUME_OFFERED
  (SP1), so one-tap planning never meets a running trip there. A trip can meet a saved place in two
  other ways: (1) search opened for a stop (ADD_STOP): one tap adds it as a stop (SP14); (2) the
  place sheet of a tapped saved marker, or of a row of the saved places screen opened from Settings
  (not while driving, SP4): the sheet offers **"Add stop"** first and **"Directions"** second (rule
  S7, `tripActive`). For every place, saved or not, the app handles `PlanTrip` and the sheet's
  "Directions" by phase:
  - IDLE → `PlanTo`.
  - RESUME_OFFERED → a dialog **"Drop your trip to {destination} and go to {name}?"**
    ({destination}: `ResumeUi.destinationName`) with **"Cancel"** and **"Go"**; Go sends
    `DiscardResume`, then `PlanTo`. The offer follows a crash or a kill, often minutes into a drive,
    and `DiscardResume` deletes `trip.json` (`docs/architecture.md` R1), so it is never dropped without a word.
  - PLANNING, CHOOSING → `PlanTo` (it replaces the plan, which is not yet a drive).
  - GUIDING, REROUTING → a dialog **"End this trip and go to {name}?"** with **"Cancel"** and
    **"Go"**; Go sends `End`, then `PlanTo`.
  - ARRIVED → `Done`, then `PlanTo`, with no dialog: the trip has already ended (`docs/architecture.md` A3).

  This filled a gap of `docs/architecture.md` (what "Directions" does while a trip exists; now its
  rule S7) and applies to every place sheet.

### 2.5 d. A saved place as a stop or as the start

- **As a stop:** route sheet or stops editor → "Add stop" → search in ADD_STOP: the Home and Work
  rows (when set) and "Saved places" (when other places exist) are at the top; one tap adds the
  stop (SP14). The stop row reads "Home" / "Mom's" with the description; arrival cards and the
  notification say "Home" (D's existing texts use `Place.name`). Also from a marker's sheet:
  "Add stop".
- **As the start:** route sheet → start row ("Your position") → search in SET_START: the same rows,
  except that the destination's own row reads "Already your destination" (SP14); one tap sets
  `Origin.FromPlace(home)`; the start row then reads "Home" (`RouteSheetUi.fromText` is the origin's
  name, unchanged). The drive feature needs no new contract for either (5.7).

### 2.6 e. Seeing and managing the list

The **saved places screen** opens from search's "Saved places" row (a row tap there picks for the
search mode, SP27) and from **Settings → "Saved places"** (a new row I adds; a row tap there shows
the place's sheet on the map; "Not while guiding" while driving, SP4). Opened from GO_TO search or
from Settings it is the full screen below; opened from ADD_STOP or SET_START search it is
pick-only: the rows of saved places and nothing else (SP4). Top to bottom:

```
←  Saved places
Show on the map                                   [on]
(house)  Home       Erie Maritime Museum, 100 Eas…   2.6 mi   Edit
(case)   Set work
(star)   Mom's      20 East 5th Street, Erie         1.9 mi   Edit
(star)   Sheetz     5800 Peach Street, Erie          4.0 mi   Edit
Add a place
```

- Rows: Home (or "Set home"), Work (or "Set work"), then the other places by name (by the name key
  of SP26, then id). Distance and "Outside the installed maps" as SP10; layout as SP6. Under the
  rows, when there is no other place: **"Places you save from search or the map show here."**; at
  the limit, the "Add a place" row is replaced by **"You can save 100 places besides Home and Work.
  Remove one to add another."** The notices of SP51-SP53 show above the rows.
- **"Set home"** / **"Set work"** → search in SAVE mode (header "Set home"); after the pick the list
  is back with the row filled. **"Add a place"** → search with header "Save place" → pick → save
  dialog over the list.
- **"Edit"** (a text button at the end of a row, "Edit {name}" to a screen reader, SP5; also on the
  place sheet of a saved place) opens a menu titled with the name:
  - Home: **"Change location"**, **"Remove home"**. Work: **"Change location"**, **"Remove work"**.
  - Other: **"Rename"**, **"Change location"**, **"Make home"**, **"Make work"**, **"Remove"**.
- **Rename:** dialog **"Rename"**, the name field with the current name, the problems of 2.3 (with
  the Rename texts for "home" and "work"), **"Cancel"** / **"Save"**. Id, kind and point stay.
- **Change location (pick a new point):** search in SAVE mode with header **"New location for
  {name}"** ("New location for Home"), "Use my position" and "Choose on the map". The pick replaces
  the point, place name, subtitle and category; id, kind and name stay. Whether the pick came from
  search or from the map ("Use this point"), the user is back where "Edit" was tapped, the list or
  the sheet, which shows the new description (SP25). (The stops editor of `docs/architecture.md` moves stops up and
  down; "Change location" is a different word on purpose.)
- **Remove:** **"Remove {name}?"** with **"Cancel"** / **"Remove"**; Home: **"Remove your home?"**
  / **"Remove"**; Work: **"Remove your work?"** / **"Remove"** (the word the other screens use
  for taking things away, as for stops and states).
- **Make home** (Make work alike): **"Make {name} your home?"**, text **"It will be called Home."**,
  preceded when a Home exists by **"Your current home ({description}) will be removed."**; buttons
  **"Cancel"** / **"Make home"**.
- **"Show on the map"** switch: SP45.
- **SP28 Replacing Home and Work.** The save dialog's "Replace" and "Make home" remove the old Home
  entry, after the line or dialog that names it ("Set home" and "Choose on the map" for Home are
  offered only while the slot is empty). A trip that used the old Home keeps its own copy until it
  ends; recents that are the old Home are removed with it (SP30). Work alike.
- **SP27 Row taps.** Opened from search in mode m: GO_TO → `PlanTrip` / `ShowPlace` (SP12, SP13);
  ADD_STOP, SET_START → `Picked(place, m)` (SP14). Opened from Settings → `ShowPlace`. After a pick the
  app closes the list and the search screen.

### 2.7 f. Saved places in search results

- **SP20 Matching.** Let q = `nameKey`(typed text) and n = `nameKey`(saved name) (SP26); tokens =
  split on spaces. No saved place matches while q has fewer than `search_min_chars` (1) code points.
  A saved place matches with **rank 0** when n = q, **rank 1** when n starts with q ("mom" →
  "Mom's", key "moms"; "h" → "Home"; "до" → "Дом"), **rank 2** when every token of q is a prefix of
  a different token of n, in any order (taking the tokens of q one by one, each takes the first
  token of n not yet taken that it is a prefix of; the match fails when one finds none) ("ho mo" →
  "Mom's House"). Matching uses the name only: not the place name, not the subtitle (the engine
  finds those, SP22). Order: rank, then kind (HOME, WORK, OTHER), then n (by Unicode code point),
  then id; at most `search_max_rows` (3).
- **SP26 Name key.** Saved names are compared by their own key, not by the search engine's `fold`,
  which keeps only a-z and 0-9 and so turns "Дом", "家" and "🏠" all into "" (verified,
  `SearchNormalizer.fold` and `normalize.py`'s `_NON_ALNUM`). `nameKey(text)`: (1) NFKD; (2) drop
  combining marks as `fold` does (canonical combining class not 0), the variation selectors
  U+FE00-U+FE0F and format characters (general category Cf, such as the zero-width joiner); (3)
  lower case (Kotlin `lowercase()`, Python `str.lower()`); (4) the apostrophes U+0027, U+2019 and
  U+0060 removed, "&" → " and "; (5) every run of code points that are not letters, marks, numbers or symbols (general
  categories L, M, N, S) → one space; trimmed. So "Mom's" and "moms" share the key "moms", "Café"
  and "cafe" share "cafe", while "Дом" ("дом"), "家", "🏠" and "🏡" each keep their own key and
  "***" has the empty key. It is used for name uniqueness, the reserved names (keys "home" and
  "work"), matching and the order of the list; it is written in Kotlin (`savedNameKey`, S) and in
  `packbuilder/search/saved_places.py` alike, and `SearchNormalizer.fold` does not change. The
  length limit `name_max_chars` counts what the eye sees as one character (extended grapheme
  clusters, `java.text.BreakIterator.getCharacterInstance()`: ICU on Android, and on the JDK 25 the
  JVM tests run on, `android/gradle/gradle-daemon-jvm.properties`), so a family emoji of 7 code
  points counts 1. The place-name rules (SP22, SP30, SP55 rule 2) keep `fold`, because they compare
  with the engine's names.
- **SP21 Placement.** The matching saved places are listed in `SearchUi.savedResults` above every
  engine result, in every mode but SAVE and in every phase where results show: while SEARCHING (they
  need no engine call and show at once), RESULTS, and NO_SEARCH_DATA (above the "Add a state"
  message). In SAVE mode no saved place is matched: a saved row there would have no clear meaning,
  and the engine row of a saved place still carries its "Saved" note (SP22). When the engine finds
  nothing but a saved place matches, the phase is RESULTS. Row: key `"saved:<id>"`,
  icon `saved_home` / `saved_work` / `saved_place`, title = name, subtitle = description, distance
  (SP10), note "Outside the installed maps" when uncovered.
- **SP22 Engine rows that are saved places.** An engine result that is the same place as a saved
  place (`SavedPlaces.find`, SP55 rule 2) is dropped when that saved place is already in
  `savedResults`; otherwise its note gets " · Saved" appended ("Exact address · Saved"; alone:
  "Saved"). Typing "home" therefore lists Home first, then "Home Depot" and the rest; typing "erie
  maritime" lists the museum with the note "Saved" when it is the saved Home.

### 2.8 g. Markers on the map

- **SP40 Icons.** Home: sprite `saved_home` (a house), Work: `saved_work` (a briefcase), other
  places: `saved_place` (a star); all three are new (section 8). They come from the style's sprite
  sheet (light by day, dark by night), like the POI icons.
- **SP41 Zooms.** Home and Work from map zoom `home_work_min_zoom` (10), others from
  `other_min_zoom` (12), drawn at `icon_scale` (1.25) times the sprite size (19 dp, so about 24 dp),
  upright (`icon-pitch-alignment` and `icon-rotation-alignment` viewport), also in the tilted guide
  view.
- **SP42 Layer.** One GeoJSON source, drawn by `MapLibreController` above all style labels and
  below the pins (destination, stop, picked) and the puck or arrow layers; above or below the route
  line layers as SP43 says (while guiding that is below the route lines, which sit under the style's
  labels, so the markers are then under the labels too: the line and the labels of the road ahead
  win over a marker; M's choice how: two symbol layers on the one source, one above and one
  below the route lines, of which only the one `MapScene.savedBelowRoute` names is visible, so no
  layer moves at run time). `icon-allow-overlap` and `icon-ignore-placement` true (they are few and
  must not vanish behind labels); `symbol-sort-key` others 0, Work 1, Home 2 (MapLibre draws a
  higher key above a lower one, so a star never hides the house; where markers overlap, a tap goes
  to the one on top); filter `[">=", ["zoom"], ["get", "min_zoom"]]` (the same zoom-in-filter form
  the style's `pois_driving` layer already uses on the device).
- **SP43 The route line.** While browsing and choosing a route the markers are drawn over the route
  line, so Home on a route preview stays visible. In GUIDING and REROUTING they are drawn below the
  route line and its maneuver layers (`MapScene.savedBelowRoute` true, set by the mapper from the
  trip phase): at speed the line and the turn must never be covered, and a marker beside the road
  still shows. A saved place that is a stop of the current trip (any stop whose `place.savedId` is
  its id) is left out: its numbered stop or destination pin shows it.
- **SP44 Tap.** A tap on a marker is reported as `MapGesture.Tap(point, pinId = "saved:<id>", poi =
  null)`. Pins win over saved markers, saved markers win over POI labels. The app opens the place
  sheet of `store.byId(id).toPlace()` (title "Home", description, distance, "Directions" or "Add
  stop" + "Directions" while a trip exists, "Edit" except while driving, SP4). During a map pick the
  tap is a pick (SP16).
- **SP45 Hiding.** The switch **"Show on the map"** on the saved places screen sets `show_on_map` in
  the file (SP50). Off: the app passes no saved places to the scene, so no marker is drawn; search,
  slots and the list are unchanged. Default on (question 12.4, answered by the owner on 2026-11-16: on
  by default).
- **SP46 Day and night.** By day a deep blue square (#1F5BC4) with a white glyph and a 1 px white
  edge; by night a brighter blue (#3A80F0), same white glyph and edge (colour pairs in 8). The style
  reload on a day/night switch brings them back with the other app layers (`onStyleLoaded`). Over the
  route line in route choice (blue B, #4A8CF7 by day, #3A80F0 by night) the white edge and the square
  shape keep the marker apart; while guiding the line is on top (SP43); the puck always wins (drawn
  above).

### 2.9 h. Recents and saved places

- **SP30** A recent that is the same place as a saved place (SP55 rule 2) is not listed among the
  recents. When a saved place is removed (Remove, "Replace" or "Make home" / "Make work" taking the
  slot), `SavedPlacesFeature` also removes every recent that is the same place as it
  (`SavedPlaces.same`, `RecentPlaces.remove`), so removing Home also forgets its address in
  `recents.json`. A recent hidden by a place that only changes location shows again. A trip in
  `trip.json` keeps its copy until it ends (`docs/architecture.md` R1). `RecentPlaces` does not change.
- **SP31** Picking a saved place (any mode) and any pick in SAVE mode never adds a recent.

### 2.10 The place sheet with saved places

- **SP19** `PlaceSheetUi` gains `savedId` and `savedText`. For a place that is saved
  (`SavedPlaces.find(place)` not null): action **EDIT** ("Edit"), `savedText` **"Saved as {name}"**
  ("Saved as Home") unless the sheet's title already is that name (a sheet made from a saved place);
  icon `saved_home` / `saved_work` / `saved_place` for a sheet made from a saved place. For a place
  that is not saved: action **SAVE** ("Save"). SAVE or EDIT is always the last action: [DIRECTIONS,
  SAVE|EDIT], with a trip [ADD_STOP, DIRECTIONS, SAVE|EDIT], outside the maps [OPEN_STATES,
  SAVE|EDIT]. While driving (GUIDING, REROUTING, ARRIVED, SP4) and with a read-only store (SP53)
  there is neither. When the saved places change while a sheet is open, the app rebuilds it: a
  sheet made from a saved place is rebuilt from `store.byId(savedId)?.toPlace()` and closed when
  that is null; any other sheet is rebuilt from its place (its "Save"/"Edit" follows). A sheet
  whose own SAVE was just confirmed (`SavedOutput.Saved` for the place it showed, at its point) is
  rebuilt from that place with `savedId` set to the new saved place's id, so a point (a dropped pin,
  a map point, "Your position"), which `same` never matches, shows "Saved as {name}" and EDIT
  instead of SAVE again (next run, part 1).

## 3. The data

### 3.1 Where and what

- **SP50 File.** `saved-places.json` in the app's no-backup folder:
  `Context.getNoBackupFilesDir()` = `/data/data/<application id>/no_backup/saved-places.json`
  (internal, app-private, credential-encrypted storage: not readable by other apps or before the
  phone's first unlock; removed when the app is uninstalled or its data is cleared). UTF-8, no BOM,
  written compact (`JsonObject.toString()`), keys in the order below. About 250 bytes per place
  *(estimate)*, so about 25 KB at the limit.

```json
{
  "format": "beamline-saved-places/1",
  "show_on_map": true,
  "places": [
    {"id": "s0a1b2c3d4e5f6071", "kind": "home", "name": "Home", "lat": 42.13646, "lon": -80.08718,
     "place_name": "Erie Maritime Museum", "subtitle": "100 East Front Street, Erie", "category": "museum"},
    {"id": "s8899aabbccddeeff", "kind": "work", "name": "Work", "lat": 42.08418, "lon": -80.17321,
     "place_name": "42.08418, -80.17321"},
    {"id": "s1234567890abcdef", "kind": "other", "name": "Quokka Shop", "lat": 42.06875, "lon": -80.09813,
     "place_name": "Millcreek Mall", "subtitle": "5800 Peach Street, Erie", "category": "mall"}
  ]
}
```

(The places are made up, at public spots; section 10 uses the same.)

| Key | Type | Rule |
|---|---|---|
| `format` | string | `"beamline-saved-places/1"`; required |
| `show_on_map` | boolean | the marker switch (SP45); missing = true |
| `notice` | string | optional: `"broken"` while the user has not yet confirmed the notice of SP52; written only by the store, never exported |
| `places` | array | in store order: Home, Work, then the others by name key (SP26), then id; at most one `home` and one `work` |
| `places[].id` | string | `s` + 16 lower-case hex digits; unique in the file |
| `places[].kind` | string | `home`, `work` or `other` |
| `places[].name` | string | non-empty after trimming. Written as `Home` for home and `Work` for work (read as such whatever it says); for other, when saved or renamed: at most `name_max_chars` (40) characters as SP26 counts them, no control characters, a non-empty name key, unique by name key and not keyed `home` or `work` (SP55) |
| `places[].lat`, `lon` | number | WGS84 degrees, -90..90 and -180..180, rounded to 6 decimals on save (the point in memory is the rounded one, so a reload equals memory) |
| `places[].place_name` | string | non-empty: the place's own name when saved or moved; for a point (a MAP_PIN place: a map point, a dropped pin, "Your position") its coordinates with five decimals, "42.08418, -80.17321" |
| `places[].subtitle` | string | optional (none for a point) |
| `places[].category` | string | optional: the search category id (`Place.categoryId`) when saved |

Readers ignore unknown keys. A later change that only adds optional keys keeps `/1` only if an older
app dropping them on a rewrite loses nothing that matters; otherwise the format becomes `/2`. Which
rules a reader checks is SP52's business: the name rules of this table bind new saves and renames,
not what is read.

### 3.2 Reading

- **SP52 Broken file.** The file is read once, when the store is created. Missing: empty list,
  `show_on_map` true, no problem. Reading checks **structure only**, the rules no later version
  could loosen: a JSON object; a `format` starting with `beamline-saved-places/`; `places` an array
  of objects; each with an id of the right form, unique; a known `kind`, at most one `home` and one
  `work`; `lat` and `lon` numbers in range; `name` and `place_name` non-empty strings; `subtitle`
  and `category` strings when present. The name rules of SP55 (length, name key, uniqueness,
  reserved words) and the limit of SP56 bind only new saves, renames and changes, never what is
  read: a 60-character name, two names with the same key or an other place named "Home" are read
  and kept as they are, so a tighter `search_app.json` or a changed name key later can never
  discard a list. A file that breaks the structure (truncated JSON, two homes, a bad or duplicate
  id, a latitude of 91, an empty name, an unknown kind, no format) is **broken as a whole**: no
  entry is silently dropped. The store renames it to `saved-places.broken-<n>.json` in the same
  folder (n = 1 + the highest existing, so nothing is overwritten), starts empty and writable,
  reports `loadProblem = BROKEN` and at once writes the new, empty file with `"notice": "broken"`
  (3.1). While that key is there, `SavedPlaces.notice` is BROKEN and the search START screen (GO_TO)
  and the saved places screen show **"Your saved places could not be read, so the list starts
  empty. The old file is kept on this phone."** with **"OK"**; "OK" (`dismissNotice()`) removes the
  key. So the notice survives restarts until the user has seen it. The switch is back at its
  default then (the old value was in the unreadable file). Kept files are never deleted by the app
  (a later import can read them). If the rename fails, the store is read-only as in SP53, with the
  BROKEN notice in this process only, so the file is never overwritten.
- **SP53 Newer file.** `format` = `beamline-saved-places/N` with N > 1 (written by a newer app, then
  the app was downgraded): the file is left untouched, the store is empty and read-only (every
  change returns `Refused(READ_ONLY)`), `loadProblem` and `notice` = NEWER; the list shows only
  **"These saved places were made by a newer Beamline. Update the app to see and change them."**,
  the search START screen shows the same notice in place of the Home and Work rows ("OK" hides it
  until the next start), and there are no slots, no Save or Edit, no markers (SP11, SP19).
- A leftover `saved-places.json.tmp` (a write that never reached its rename) is deleted at start; the
  main file is the truth.
- Over the limit (a lower `max_other` in a later rules file): all entries are read and kept; new
  other places are refused until the count is below the limit.
- The store's problems and exceptions never carry the file's text: the JSON parser's message
  (kotlinx-serialization puts a piece of the input into it) is not kept, logged or rethrown (PV2).

### 3.3 Writing

- **SP51 Atomic, off the main thread.** Every change is validated and applied in memory at once
  (the `places` flow updates on the calling thread), then the whole file is written on the store's
  `writer` executor (the app passes `Dispatchers.IO.asExecutor()`): write
  `saved-places.json.tmp` in the same folder, `fd.sync()`, close, rename over the file (on failure
  delete and rename again), exactly as `SettingsStore` does. Writes are serialized by a lock and
  always write the newest state; a queued write that finds a newer state already written does
  nothing. A failed write sets `writeProblem = WRITE_FAILED` ("Could not save. The phone may be out
  of space." on the list, the save dialog and the pick bar) until a later write succeeds; memory
  keeps the change. A process killed within milliseconds of a change can lose that change *(the
  same as any store of `docs/architecture.md`)*; the emulator journey waits 2 s before force-stopping.

### 3.4 Identity, duplicates, limit

- **SP54 Identity.** A saved place is its `id`: random (`SecureRandom`, 64 bits, re-drawn if it
  exists in the file), kept by rename and move, new for a new place (also for a new Home that
  replaces the old). It never stores or depends on a search id or a pack id: `point`, `place_name`,
  `subtitle` and `category` are copies taken when it was saved or moved, enough to show it, search
  it by name, draw it and route to it. A pack being rebuilt, updated, removed or added changes
  nothing in the file.
- **SP55 Duplicates and names.** (1) **Names**, checked when an other place is saved or renamed:
  control characters are removed and the name trimmed; it must be non-empty (NAME_EMPTY), have a
  non-empty name key (NAME_NO_LETTERS: "***" is refused, "🏠" and "家" are fine), be at most
  `name_max_chars` characters as SP26 counts them (NAME_TOO_LONG), not have the key `home` or `work`
  (NAME_RESERVED), and not share its key with another other place (NAME_TAKEN; a rename may keep
  its own name in another case). (2) **Same place.** `SavedPlaces.same(saved, place)` is true when
  `place.savedId` is the saved place's id; otherwise, only for a place with a name of its own
  (source SEARCH, RECENT, MAP_POI or RESUMED, not MAP_PIN), when it lies within `same_place_m` (50 m)
  of the saved point and `fold(place.name)` is non-empty and equals the fold of the saved place's
  place name. A point (MAP_PIN: a map point, a dropped pin, "Your position") is never the same place
  as a saved one except by its saved id: neighbouring houses saved by points are different places,
  and a dropped pin near Home does not say "Saved as Home". `SavedPlaces.find(place)` = the saved
  place with `place.savedId`, else among those `same` holds for, the nearest; on equal distance HOME
  before WORK before OTHER, then by name key, then id. Saving or moving an **other** place to a
  place that `find` returns, other than itself, is refused with ALREADY_SAVED. Home and Work are
  never refused for this: Home and Work may be the same address, and a place saved as "Mom's" may
  also become Work through "Set work". `save` and `move` take a place from search, a recent, a POI
  or a point, never a `SAVED` one (SAVE mode offers none, SP21; turning a saved place into Home is
  "Make home").
- **SP56 Limit.** At most `max_other` (100) other places, plus Home and Work. The 101st is refused
  with LIMIT; Home and Work can still be set.
- **SP57 A state removed.** When the pack that covers a saved place is removed, the saved place
  stays as it is: its rows get "Outside the installed maps", picking it opens its sheet with "Add a
  state" (SP13), its marker is still drawn (over the overview map). When the state is added again,
  everything works again with no re-linking. A trip stop that came from it follows the rules of
  `docs/architecture.md` (PACKS_CHANGED, OUTSIDE_INSTALLED_MAPS).

### 3.5 What else holds a saved place

`trip.json` (N's `TripStore`) holds the trip's stops, so a saved place used as a stop or destination;
`recents.json` (S's `RecentPlaces`) holds places picked in search, which may be the same address as
Home. Both therefore move to the no-backup folder too (PV4). `trip.json` writes `Place.source` by
name, now including `SAVED`, and `savedId` when present; a resumed stop whose saved place was
deleted meanwhile still works from its copy.

### 3.6 Export and import (format ready, screens later)

Not in this round (question 12.2, answered by the owner on 2026-11-16: saved places are lost on a new
phone for now; export comes in a later round). The export file will be
this file as the store's codec writes it (`SavedPlacesFile.encode`, S), without `notice`, so the
format above is the exchange format; import will merge by id (and SP55) and ignore `show_on_map`.
The smallest useful first step, if the owner wants it now: a **"Save a copy"** row on the full saved
places screen that hands this file to the system's "create document" picker
(`ACTION_CREATE_DOCUMENT`, the user chooses where), with no import yet; about a day of S and I work
*(estimate)*, and no change to the format. `packbuilder/search/saved_places.py` (S, standard
library only) reads and validates the format now (section 9), so the Python side is ready too.

## 4. Privacy and safety

Driving safety is rule SP4 (section 2): nothing about saved places is created, changed or typed
while driving. The privacy rules follow; each names how it is checked.

- **PV1 Only on the phone.** The store writes only `no_backup/saved-places.json` (and its `.tmp`
  and `.broken-<n>` files beside it); nothing about a saved place is written to `files/`, the cache,
  external storage or a shared folder, and nothing is sent anywhere. The app has no account and no
  cloud. Since Round G0 the network code is `PackHttp` (`docs/architecture-downloads.md` rule
  N1), which sends fixed headers, no body, no query string and no position (rule N3), and since batch
  O1 `OsmHttp`, which sends a note's text and the point the user chose, only after the user's Send
  and only with the online switch on; the saved-places code uses no network API, which G's source
  scan (`check_network_rule.py scan`, its 4.2 item 2) enforces. **The OSM outbox** (batch O1,
  docs/online-design.md 4.5: `no_backup/osm-outbox.json`, the notes the user wrote, their points,
  and after sending each note's number and link; since batch O2 also the user's edits of places, and
  once sent their changeset and the local copy the place sheet shows until a pack has it; since batch
  O3 the places the user adds and the new businesses that replace old ones, which the search, the
  place sheet and the map's own-changes layer show from this file until an installed pack has them,
  and nothing more is sent) and **the
  OSM account** (batch O2, `no_backup/osm-account.bin`: the access token and the user's OSM name,
  sealed with AES-GCM under an Android Keystore key, deleted by "Sign out"; the open sign-in's PKCE
  verifier likewise in `no_backup/osm-signin.bin` until its redirect) live beside the saved places
  under these same rules PV1-PV8: never logged (its package `app/online/` has no log call and builds no exception
  message from a value, `check_private_logs.py scan`), never in a backup, `OutboxItem` prints no text
  or point. *Checked by:* the emulator file listing (10.3 step 13), G's scan, the private-log scan.
- **PV2 Never in a log.** No log line, exception message, `toString()` or crash text contains a
  saved place's name, place name, subtitle or coordinates. A saved place travels as a `Place` once
  it is used (a sheet, `PlanTo`, a stop, `trip.json`), and many types print a point (`GeoPoint`,
  `MapPin`, `MapPoi`, the puck), so the rule rests on how logging is written, not on one class.
  Concretely:
  1. **Redacted `toString()`** in the frozen model: `SavedPlace` prints only id and kind
     (`SavedPlace(id=s0a1..., kind=HOME)`), `Place` only its source and saved id
     (`Place(source=SAVED, savedId=s0a1...)`), `SavedMarker` only saved id and kind (5.1, 5.2). So a
     stray template, an assertion message or a crash text that prints one of these, or a `TripStop`,
     a `NavIntent` or a `SearchOutput` holding one, prints no name and no point. Other types
     (`GeoPoint`, `MapPin`, `MapPoi`, the UI states, which hold names as texts) keep their normal
     `toString()` and are kept out of logs by item 3.
  2. `:core:search`, `:feature:search` and `:map:scene` contain no logging call at all in their main
     sources (`android.util.Log`, `Log.`, `println`, `print(`, `System.out`, `System.err`,
     `printStackTrace`, `Timber`). (Verified: none of the three has one today; S's demo app, which
     logs queries, is a separate module that never holds a store.) In the code that holds saved
     places, `:core:search`'s `search/saved/` package, `:feature:search`, `:map:scene` and
     `:map:layers`, no exception message is built from a value: a `require`, `check`, `error`,
     `requireNotNull`, `checkNotNull` or `throw` whose message contains `$` is refused, so a crash
     text there is a fixed sentence. (Verified: none of these has one today; the engine code of
     `:core:search`, whose messages name rule files and database paths, is outside the rule.)
  3. In every other main source of a shipped module, a log call (`Log.v/d/i/w/e/wtf`, `println`,
     `print(`, `System.out`, `System.err`) is either (a) a tag and one plain string literal with no
     `$`, or (b) marked on its line with `// log-ok: <what the values are>`, and then may print only
     numbers, durations, counts, sizes, enum names, pack ids, file names and catalog-relative paths
     (a pack folder and a file name, rule N3 of `docs/architecture-downloads.md`, whose log lines in
     `:core:download` and `:feature:states` are marked the same way). The scan lists every marked
     line and the reviewer checks each (today about 18 lines, all in `:app`, which I owns). An
     exception is never passed to a log call and its message is never logged (no third argument, no
     `.message`, `.localizedMessage`, `stackTraceToString()`, `printStackTrace()`): a message can
     carry whatever its thrower put in it, so an exception is logged as `e.javaClass.simpleName`
     (marked). Today `PackMap.kt` lines 158 and 329, `AndroidPositionSource.kt` line 66 and
     `MapViewModel.kt` line 105 break this (verified) and change in I's rewrite. In addition, a log
     line that contains, as a whole word, one of `lat`, `lon`, `latitude`, `longitude`, `point`,
     `GeoPoint`, `Place`, `place`, `stops`, `query`, `saved` (regex
     `\b(lat|lon|latitude|longitude|point|GeoPoint|Place|place|stops|query|saved)\b`) must carry
     `// private-ok: <reason>` instead and sit inside the location-log guard of PV7 item 6.
     Today two lines in `:app` log coordinates (`MapViewModel.kt` line 85, the puck: verified;
     `PackMap.kt` line 244, "camera idle ... at %.5f %.5f": verified). The puck line is the phone
     kit's location measurement (`android/scripts/phone/location_check.py` reads it and keeps it
     under `data/phone/private/`); it stays, marked, behind that guard. The camera line loses its
     coordinates in I's rewrite (zoom only): the camera centres on saved places (a tapped marker, a
     destination), so it would log them.
  4. The store maps parser failures to `LoadProblem` without their messages (3.2).
  5. Valhalla's own logging is off on the device (`"logging.type": ""` in
     `android/routing/core/src/main/resources/io/github/spysyweeb/beamline/routing/device_config.json`:
     verified), so a route request to Home is not logged by the engine.
  *Checked by:* PV3.
- **PV3 How PV2 is proved.** Three checks, because a source scan sees only what a line says and the
  emulator journey only the paths it runs:
  1. `android/scripts/check_private_logs.py` (new, S; standard library): `scan` fails on any breach
     of PV2 items 2 and 3 over `android/**/src/main/**/*.kt` of the modules `:app` depends on (build
     folders, demos and test sources excluded) and prints every marked line; `logcat <files...>`
     fails when any canary of the emulator journey appears, case-insensitively, in the captured log:
     the words "quokka", "maritime", "millcreek", "art museum", "airport", and each canary point's
     latitude and longitude written with 3, 4 and 5 decimals ("42.136", "42.1365", "42.13646",
     "-80.087", ...), a number counting only when no digit or dot comes before it (so "142.136 ms"
     is not a hit; regex `(?<![0-9.])42\.136`); `manifest <merged AndroidManifest.xml> <apk>`
     checks PV4. I runs `scan` and `manifest` in checklist item 14 and `logcat` at the end of the
     emulator journey.
  2. **Failure paths on the JVM** (10.1): `SavedPlacesLeakTest` (`:core:search`) and
     `SavedLeakTest` (`:feature:search`) run every failure path with the canary place "Quokka Shop"
     at (42.06875, -80.09813): a broken file, a newer file, a failed write, every refused save,
     rename and change of location, an unknown id, a cancelled pick. They capture `System.out` and
     `System.err`, add a handler to the root `java.util.logging` logger and install a default
     uncaught-exception handler, and assert that no captured text, no exception message (causes
     included) and no `toString()` of a `SaveResult`, `LoadProblem`, `SearchOutput`, `SavedOutput`
     or `Place` contains "quokka", "millcreek", "42.068" or "80.098".
  3. `SavedPlaceTest` (`:core:api`) checks item 1 for `SavedPlace`, `Place` and `SavedMarker`.
- **PV4 Backup and device transfer: excluded.** Today the manifest sets `android:allowBackup="false"`
  and nothing else (verified, `app/src/main/AndroidManifest.xml`). For an app that targets API 31 or
  higher (Beamline targets 36), Android's documentation says: "On devices from some device
  manufacturers, specifying `android:allowBackup="false"` does disable backups to Google Drive, but
  doesn't disable D2D transfers for the app", and "If there are no rules for a particular backup
  mode, such as if the `<device-transfer>` section is missing, that mode is fully enabled for all
  content except for `no-backup` and `cache` directories" (verified, developer.android.com
  "Behavior changes: Android 12 / Backup and restore" and "Back up user data with Auto Backup",
  2026-11-15). So:
  1. `saved-places.json`, `recents.json` and `trip.json` live in `no_backup/` (I passes those paths
     to `SavedPlaces`, `RecentPlaces` and `TripStore`). Android leaves that folder out of cloud
     backup and device-to-device transfer whatever the manifest says ("Files in directories
     returned by `getCacheDir()`, `getCodeCacheDir()`, or `getNoBackupFilesDir()` are always
     excluded even if you try to include them": verified, same page); the same holds for `adb
     backup` and Android 16's cross-platform transfer, which apply the same full-backup rules
     (inferred, not tried).
  2. `android:allowBackup="false"` stays (cloud backup off on every version; on Android 11 and lower
     it also turns device transfer off, so no `fullBackupContent` file is needed).
  3. New attribute on `<application>` in `app/src/main/AndroidManifest.xml`:
     `android:dataExtractionRules="@xml/data_extraction_rules"`, and the new file
     `app/src/main/res/xml/data_extraction_rules.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Beamline keeps everything on this phone (docs/architecture-saved-places.md, rule PV4; the
     lead's decision for integration, 2026-11-16: nothing of the app leaves the phone).
     saved-places.json, recents.json and trip.json live in no_backup/, which Android never backs up
     or transfers. allowBackup="false" turns cloud backup off; these rules say the same on Android 12
     and later, and exclude every domain from a device-to-device transfer as well, the
     device-protected ones included (nothing of the app uses that storage today; a library that
     did would not take anything along). -->
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="root" path="." />
        <exclude domain="file" path="." />
        <exclude domain="database" path="." />
        <exclude domain="sharedpref" path="." />
        <exclude domain="external" path="." />
        <exclude domain="device_root" path="." />
        <exclude domain="device_file" path="." />
        <exclude domain="device_database" path="." />
        <exclude domain="device_sharedpref" path="." />
    </cloud-backup>
    <device-transfer>
        <exclude domain="root" path="." />
        <exclude domain="file" path="." />
        <exclude domain="database" path="." />
        <exclude domain="sharedpref" path="." />
        <exclude domain="external" path="." />
        <exclude domain="device_root" path="." />
        <exclude domain="device_file" path="." />
        <exclude domain="device_database" path="." />
        <exclude domain="device_sharedpref" path="." />
    </device-transfer>
</data-extraction-rules>
```

  Since integration (the lead's decision of 2026-11-16) a device transfer carries nothing of the app
  either (the four device-protected domains are listed too since the integration review: the app
  writes nothing there today, and nothing a library might put there later goes along): settings, packs in `Android/data/<app id>/files/` and G's download jobs are excluded with
  every other domain (before, the design left them to the transfer; none of them holds a place of
  the user). The owner has decided nothing leaves the phone except by an export, so saved places
  do not move to a new phone until export and import exist (question 12.2). *Checked by:*
  `check_private_logs.py manifest`: the merged release manifest has `allowBackup="false"` and that
  `dataExtractionRules`, and the XML in the APK (`aapt2 dump xmltree`) has exactly the rules above;
  on the emulator, `run-as` shows the three files only in `no_backup/` (10.3 step 13).
- **PV5 Export.** Nothing about saved places leaves the app until the user exports it through the
  system file picker (a later round, 3.6). No share intent, no clipboard copy of coordinates, no
  content provider is added now.
- **PV6 On screen.** Names and descriptions show only inside the app's screens and the guidance
  notification, which already shows the destination's name ("Arrived at Home"); the notification
  never shows a saved place's description or coordinates (D's texts use `Place.name` only: verified
  in `docs/architecture.md` D6).
- **PV7 Tests on the owner's real phone, once real saved places exist.** Only I uses the phone
  (`docs/architecture.md` 5.2); these rules bind I, the phone kit (`android/scripts/phone/`) and
  anyone running it:
  1. **A separate test data set.** In test-extras builds, `--es private_data test` on a **cold
     start** (`am force-stop` first) makes the app use `no_backup/test/` for `saved-places.json`,
     `recents.json` and `trip.json` for the life of that process, and log the one line `private
     data: test set` (tag `Beamline`). Without the extra, or in a process that already runs, the
     owner's own files are used (the activity logs `private data: extra ignored, app already
     running`). **Where the choice is made:** `AppGraph` is built lazily on first use
     (`BeamlineApplication.graph`, verified), and a service (guidance today, a car screen later) may
     build it first. So the folder is a constructor argument of `AppGraph`, held by
     `BeamlineApplication` as a value that `MainActivity.onCreate` sets from the extra **before** its
     first access to `graph`; the application refuses the setting once the graph exists, and that
     refusal is what logs "extra ignored". No other code reads the extra. **Every** phone session,
     the phone kit's measurements included (their Erie views could show a real Home marker), first
     force-stops, starts with the extra and waits for the `test set` line; without it the script
     stops before any tap, screenshot or UI dump. Release builds without test extras ignore the
     extra. The phone kit's `Phone.start` (`android/scripts/phone/phone.py`, outside the tracks
     today, `docs/architecture.md` appendix A R7) gains the extra and the check; I makes that change
     when it adopts the kit for integration.
  *Dated note (2026-11-16):* the owner allowed **one** clearing of the app's data on the phone, at the
  start of the phone step that follows integration, because nothing personal is stored there yet:
  allowed once by the owner on 2026-11-16, before any saved place existed; never again. Item 2 holds
  for every other session.
  2. **Never:** `pm clear`, `adb uninstall` (it deletes the app's data), `adb install` without `-r`,
     `adb backup`, `bmgr`, `run-as` (it works only on debuggable builds and would read or change the
     private files), `adb pull` of anything under `/data/data/<app id>/`, or creating, renaming,
     changing or removing a saved place outside the test set. **On an install error**
     (`INSTALL_FAILED_UPDATE_INCOMPATIBLE` after a signing-key change, `INSTALL_FAILED_VERSION_DOWNGRADE`
     or any other), the script and the engineer stop and ask the owner; nobody uninstalls to get
     past it (today's release builds are signed with the build PC's debug key, verified in
     `app/build.gradle.kts`, so a new build PC causes exactly this error).
  3. **Logs:** only `logcat --pid` of the app's process, saved under `data/phone/private/`, never
     printed (as `phone.py` already does). The emulator's canary check (PV3) is what proves the code
     logs no saved place; the phone run does not grep for the owner's real names (nobody knows
     them, and that is the point).
  4. **Screenshots and UI dumps** (`device_ui.py texts`, `uiautomator dump`) only within the test set;
     the location runs' map screenshots stay under `data/phone/private/` as today.
  5. The test set may be left on the phone (made-up places only); nothing else is cleaned up.
  6. **Position log only when measuring.** The puck line (PV2 item 3) logs only in a process
     started cold with `--ez location_log true` (test-extras builds; read and fixed at process
     start like `private_data`). The phone kit's location runs pass it; every other run, a
     test-extras build used for everyday driving included, logs no position, so a day at home never
     puts the real Home in the log.
  7. **The download journeys** (`docs/architecture-downloads.md` 10.4, and the downloads part of
     `docs/architecture.md` section 8) follow items 1-6 on the phone: every session starts cold with
     `--es private_data test`, also after the phone restart of journey 5 and before any tap,
     screenshot or UI dump. Item 2 holds for them without exception, whatever the owner has saved:
     the phone's data is never cleared and the app never uninstalled, so "app data cleared" and a
     fresh install (journey 1, and the first-run screen's part of journeys 2, 3 and 5) are proved
     on the emulator only; on the phone a state is removed and downloaded again through the States
     screen (that document's 10.4 says how).
- **PV8 The keyboard.** The search field, the save dialog's name field and the rename field ask the
  keyboard not to learn what is typed (Android's `EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING`,
  the keyboard's "incognito" request) and have autocorrect and suggestions off, so a typed home
  address or "Mom's" does not go into the keyboard's dictionary or its sync. In Compose, S sets the
  flag through `InterceptPlatformTextInput` on the `EditorInfo` *(not tried)*; whether a keyboard
  honours it is the keyboard's business. *Checked by:* S on the emulator: with each of the three fields focused, the editor the keyboard is bound to
  (`dumpsys input_method`) has `IME_FLAG_NO_PERSONALIZED_LEARNING` and `TYPE_TEXT_FLAG_NO_SUGGESTIONS`
  set and `TYPE_TEXT_FLAG_AUTO_CORRECT` clear (10.4). Gboard's incognito mark itself: I on the phone,
  step 18 of 10.3 (the round's emulator image, `system-images;android-36;default`, has no Gboard).

## 5. Contracts

Markings: **[FROZEN]** = a change to a frozen file of `:core:api` (made by the orchestrator in Round
SP0); **[SKELETON]** = a new or changed compiled contract file in a track's module, written in Round
SP0 with `TODO("track X")` bodies and checked by `tools/check_contracts.py`; **[TRACK]** = inside one
track's own folder, the track's own design within the rules here. Changes to frozen contracts are
additive: every existing call site compiled unchanged in Round SP0's build (`docs/architecture.md`
appendix B). The files this design changed that `docs/architecture.md` already quoted, and the new
`Saved.kt` of the frozen kernel, are quoted whole there; the excerpts below show what changed and are
checked against the same files.

### 5.1 Model [FROZEN]

**Extend `Place` or add a type?** Both, minimally. A saved place needs a kind (Home, Work, other),
a stable id across rename and move, and both the user's name and the place's own name: `Place` has
none of these, and adding them would load every search result and stop with fields they never use.
So the stored entity is a new type, `SavedPlace`. Everywhere a place is *used* (sheets, `PlanTo`,
stops, origin, `trip.json`, arrival texts), it stays a `Place`, so the session, the drive feature
and the trip store need no new type: `PlaceSource.SAVED` says where it came from and `Place.savedId`
links back for "Edit", for the sheet's saved state, for SP43 and for SP30. `SavedPlace` lives in
`:core:api`, not in `:core:search`, because `:map:scene` (M) needs it to draw markers and may not
depend on `:core:search` (`docs/architecture.md` 1.1), and a later car screen needs it too.

Changes to `core/api/src/main/kotlin/io/github/spysyweeb/beamline/api/model/Model.kt` (quoted whole
in `docs/architecture.md` 2.1). `Place` gains `savedId` and a redacted `toString()` (PV2 item 1):
data-class equality and `copy` are unchanged, and no code relied on a place's `toString()` (searched
in Round SP0: no main or test source of the tree prints or compares one), so nothing else changed.

```kotlin
// EXCERPT: core/api/src/main/kotlin/io/github/spysyweeb/beamline/api/model/Model.kt
/** Where a place came from. */
enum class PlaceSource { SEARCH, RECENT, MAP_POI, MAP_PIN, RESUMED, SAVED }

/**
 * A place the user can go to: a search result, a recent, a POI tapped on the map, a dropped pin, a
 * saved place ([source] SAVED, [savedId] set). [searchId] and [packId] identify a search entry of
 * one pack build (they may not survive a pack rebuild); [name], [subtitle] and [point] are copies
 * and always enough to route and display. [toString] shows only [source] and [savedId], never the
 * name, the subtitle, the point or the search id (privacy rule PV2, docs/architecture-saved-places.md).
 */
data class Place(
    val name: String,
    val point: GeoPoint,
    val source: PlaceSource,
    val subtitle: String? = null,
    val categoryId: String? = null,
    val searchId: Long? = null,
    val packId: String? = null,
    /** The SavedPlace.id this place was made from (SavedPlace.toPlace), or the one it was just saved as
     *  from its own open sheet (SP19, a dropped pin included); null for every other place. */
    val savedId: String? = null,
) {
    override fun toString(): String = "Place(source=$source, savedId=$savedId)"
}
```

The new file `core/api/src/main/kotlin/io/github/spysyweeb/beamline/api/model/Saved.kt` (quoted whole
in `docs/architecture.md` 2.1), after its package line:

```kotlin
// EXCERPT: core/api/src/main/kotlin/io/github/spysyweeb/beamline/api/model/Saved.kt
/** Which slot a saved place fills: at most one HOME and one WORK. */
enum class SavedKind { HOME, WORK, OTHER }

/**
 * A place the user saved (saved-places.json, format beamline-saved-places/1, written by
 * :core:search SavedPlaces; docs/architecture-saved-places.md). Self-contained: [point],
 * [placeName], [subtitle] and [categoryId] are copies taken when it was saved or moved, so it
 * survives any pack being rebuilt, replaced or removed; it holds no search id and no pack id.
 * [toString] shows only [id] and [kind], never the name or the point (privacy rule PV2).
 */
data class SavedPlace(
    /** "s" and 16 lower-case hex digits; random; kept by rename and move. */
    val id: String,
    val kind: SavedKind,
    /** What lists, sheets, stops and cards show: "Home", "Work", or the user's own name ("Mom's"). */
    val name: String,
    val point: GeoPoint,
    /** The place's own name when saved or moved ("Erie Maritime Museum"); for a point, its coordinates ("42.08418, -80.17321"). */
    val placeName: String,
    /** The place's own subtitle then ("100 East Front Street, Erie"); null when it had none. */
    val subtitle: String? = null,
    /** The search category of the place then ("museum"); null for addresses, streets and points. */
    val categoryId: String? = null,
) {
    /** The second line: [placeName] unless it equals [name] (ignoring case), then [subtitle], joined with ", "; null when empty. */
    val description: String?
        get() = listOfNotNull(placeName.takeUnless { it.equals(name, ignoreCase = true) }, subtitle)
            .joinToString(", ")
            .ifEmpty { null }

    /** The place to plan to, add as a stop, start from or show in a sheet. */
    fun toPlace(): Place =
        Place(name = name, point = point, source = PlaceSource.SAVED, subtitle = description, categoryId = categoryId, savedId = id)

    override fun toString(): String = "SavedPlace(id=$id, kind=$kind)"
}
```

Round SP0 added a test to the frozen module,
`core/api/src/test/kotlin/io/github/spysyweeb/beamline/api/SavedPlaceTest.kt`: `toString()` of a
saved place named "Quokka Shop" at (42.06875, -80.09813) placed "Millcreek Mall", of its
`toPlace()`, of a search `Place` with the same name, subtitle and search id, of `TripStop`s,
`NavIntent`s, a `TripState` and a `MapScene` holding them and of its `SavedMarker` contains none of
"Quokka", "Millcreek", "Peach", "42.06", "80.09" or the search id; `description` for name = place
name (also in another case), for name ≠ place name, with and without subtitle, and for a point (just
the coordinates); `toPlace()` gives source SAVED, the saved id, the name and the description;
`savedId` defaults to null and takes part in equality; `SAVED` is the last `PlaceSource`.

### 5.2 Map scene [FROZEN] and the mapper [SKELETON]

Changes to `core/api/src/main/kotlin/io/github/spysyweeb/beamline/api/map/MapScene.kt` (quoted whole
in `docs/architecture.md` 2.5; it imports `io.github.spysyweeb.beamline.api.model.SavedKind`):

```kotlin
// EXCERPT: core/api/src/main/kotlin/io/github/spysyweeb/beamline/api/map/MapScene.kt
/** Everything the app draws on top of the map style. Built by :map:scene MapSceneMapper. */
data class MapScene(
    val theme: MapTheme,
    val route: RouteLines? = null,
    val pins: List<MapPin> = emptyList(),
    val marker: VehicleMarker? = null,
    /** The user's saved places (none when hidden): above the labels, below the pins and the vehicle; above the route lines unless [savedBelowRoute]. */
    val saved: List<SavedMarker> = emptyList(),
    /** True while guiding (GUIDING, REROUTING): [saved] is drawn below the route lines, so they never hide the line or the turn. */
    val savedBelowRoute: Boolean = false,
    /**
     * The traffic lights on the guided route ahead of the vehicle (Guidance.lightsAhead; docs/next-round.md
     * item 22): a small traffic-light image on the route line at each, above the line, below the pins
     * and the vehicle. Only for the route being driven, so the map stays calm.
     */
    val lights: List<GeoPoint> = emptyList(),
)

/**
 * One saved place on the map: the style sprite image saved_home, saved_work or saved_place by
 * [kind], [scale] times its sprite size, upright, shown from map zoom [minZoom] on. A tap on it is
 * reported as MapGesture.Tap with pinId = "saved:" + [savedId]. [toString] shows only [savedId] and
 * [kind], never the point (privacy rule PV2).
 */
data class SavedMarker(val savedId: String, val point: GeoPoint, val kind: SavedKind, val minZoom: Double, val scale: Double) {
    override fun toString(): String = "SavedMarker(savedId=$savedId, kind=$kind)"
}
```

and in `MapGesture`, the doc of `Tap` only:

```kotlin
// EXCERPT: core/api/src/main/kotlin/io/github/spysyweeb/beamline/api/map/MapScene.kt
    /** [pinId]: the MapPin.id, or "saved:" + SavedMarker.savedId, under the tap; [poi]: a POI label under it. */
    data class Tap(val point: GeoPoint, val pinId: String?, val poi: MapPoi?) : MapGesture
```

Changes to M's contract file `map/scene/src/main/kotlin/io/github/spysyweeb/beamline/map/scene/Scene.kt`
(quoted whole in `docs/architecture.md` 2.5; it imports `io.github.spysyweeb.beamline.api.model.SavedPlace`;
`CameraRules` and `CameraDirector` unchanged):

```kotlin
// EXCERPT: map/scene/src/main/kotlin/io/github/spysyweeb/beamline/map/scene/Scene.kt
/**
 * Trip and puck to what the map draws. One per map surface: it remembers the previous vehicle
 * position, so the arrow's glideMs is the time between the last two positions (rule K3).
 * [savedRules]: camera.json "saved_places" (docs/architecture-saved-places.md, rules SP40-SP43).
 */
class MapSceneMapper(savedRules: SavedMarkerRules = SavedMarkerRules.bundled()) {
    /**
     * [picked]: the place whose sheet is open, or the point being chosen on the map (a PICKED pin).
     * [saved]: the saved places to draw (the app passes none when they are hidden); one that is a
     * stop of [trip] (a stop's place.savedId) is left out; MapScene.savedBelowRoute is true in
     * GUIDING and REROUTING (SP43). The arrow replaces the puck while a trip is guided.
     * [results]: the search results on the map (rule S11), RESULT pins "result:<index>"; the one at
     * the point of [picked] (the selected result, whose sheet is open) is left out: the PICKED pin
     * stands there instead.
     */
    fun scene(trip: TripState, puck: PuckState?, theme: MapTheme, picked: Place?, saved: List<SavedPlace> = emptyList(), results: List<GeoPoint> = emptyList()): MapScene =
        TODO("track M")
}

/** camera.json "saved_places" (the same file as CameraRules, copied by sync_data.py). Field = JSON key. */
data class SavedMarkerRules(
    val homeWorkMinZoom: Double,            // saved_places.home_work_min_zoom
    val otherMinZoom: Double,               // saved_places.other_min_zoom
    val iconScale: Double,                  // saved_places.icon_scale
) {
    companion object {
        fun parse(text: String): SavedMarkerRules = TODO("track M")
        fun bundled(): SavedMarkerRules = TODO("track M")
    }
}
```

`MapController` and `MapLibreController` do not change: `show(scene)` draws `scene.saved` above or
below the route lines as `scene.savedBelowRoute` says (SP42, SP43), the gesture flow reports
saved-marker taps (SP44). What M builds inside them is [TRACK]. Until M has written
`SavedMarkerRules.bundled()`, `MapSceneMapper()` (its default argument) stops with the stub's
`TODO`, like every other stub of the round.

### 5.3 The store [SKELETON, `:core:search`]

```kotlin
// FILE: core/search/src/main/kotlin/io/github/spysyweeb/beamline/search/saved/SavedPlaces.kt
package io.github.spysyweeb.beamline.search.saved

import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.api.model.SavedPlace
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.Executor

/** search_app.json "saved" (packbuilder/search/search_app.json; the copy in this module's rules folder). Field = JSON key. */
data class SavedRules(
    val maxOther: Int,                      // saved.max_other
    val nameMaxChars: Int,                  // saved.name_max_chars
    val samePlaceM: Double,                 // saved.same_place_m
    val searchMinChars: Int,                // saved.search_min_chars
    val searchMaxRows: Int,                 // saved.search_max_rows
    val pickMinZoom: Double,                // saved.pick_min_zoom
    val positionMaxAccuracyM: Double,       // saved.position_max_accuracy_m
) {
    companion object {
        /** From the whole text of search_app.json. */
        fun parse(searchAppJson: String): SavedRules = TODO("track S")
        /** From the copy copy_search_rules.py puts in the resources (search/rules/search_app.json). */
        fun bundled(): SavedRules = TODO("track S")
    }
}

/** Why saved-places.json was not used when the store opened (rules SP52, SP53). */
enum class LoadProblem {
    /** Its structure is damaged: renamed to saved-places.broken-<n>.json; the store starts empty. */
    BROKEN,
    /** Written by a newer app: left in place; the store is empty and read-only. */
    NEWER,
}

enum class SaveProblem { NAME_EMPTY, NAME_NO_LETTERS, NAME_TOO_LONG, NAME_RESERVED, NAME_TAKEN, ALREADY_SAVED, LIMIT, NOT_FOUND, READ_ONLY, WRITE_FAILED }

sealed interface SaveResult {
    /** [place]: the saved place after the change; null after a delete or a show-on-map change. */
    data class Done(val place: SavedPlace?) : SaveResult
    data class Refused(val problem: SaveProblem) : SaveResult
}

/**
 * The user's saved places in [file] (saved-places.json, format beamline-saved-places/1; rules
 * SP50-SP56 of docs/architecture-saved-places.md). Reads the file once, here. A change is
 * validated and applied in memory on the calling thread, then written atomically (temporary file,
 * fsync, rename) on [writer], newest state wins. Thread-safe; Android-free; never logs (PV2).
 * [newId] makes ids (tests pass a sequence).
 */
class SavedPlaces(file: File, rules: SavedRules, writer: Executor, newId: () -> String = ::randomSavedId) {
    /** Home, then Work, then the OTHER places by name key (SP26), then id. */
    val places: StateFlow<List<SavedPlace>> get() = TODO("track S")
    val showOnMap: StateFlow<Boolean> get() = TODO("track S")
    /** What happened when this store opened; null when the file was fine or missing. */
    val loadProblem: LoadProblem? get() = TODO("track S")
    /** The problem the user has not yet confirmed with "OK": BROKEN until dismissNotice (kept in the file), NEWER until dismissNotice in this process. */
    val notice: StateFlow<LoadProblem?> get() = TODO("track S")
    /** WRITE_FAILED after a failed write, until a later write succeeds; null otherwise. */
    val writeProblem: StateFlow<SaveProblem?> get() = TODO("track S")
    val readOnly: Boolean get() = TODO("track S")

    fun byId(id: String): SavedPlace? = TODO("track S")
    fun home(): SavedPlace? = TODO("track S")
    fun work(): SavedPlace? = TODO("track S")
    /** Whether [place] is [saved] (SP55 rule 2): the same savedId, or a place with a name of its own (not MAP_PIN) within same_place_m whose non-empty folded name equals the fold of saved.placeName. */
    fun same(saved: SavedPlace, place: Place): Boolean = TODO("track S")
    /** The saved place that is [place] (SP55 rule 2): its savedId, else the nearest one [same] holds for, ties by kind, name key, id; null when none. */
    fun find(place: Place): SavedPlace? = TODO("track S")
    /** Rule SP20: saved places whose name matches [typed], best first, at most search_max_rows. */
    fun matching(typed: String): List<SavedPlace> = TODO("track S")
    /** Null when [name] can name an OTHER place (SP55 rule 1); [exceptId]: the place being renamed. */
    fun checkName(name: String, exceptId: String? = null): SaveProblem? = TODO("track S")
    /** Drops the notice (SP52): rewrites the file without "notice" (BROKEN), or hides it for this process (NEWER). */
    fun dismissNotice(): Unit = TODO("track S")

    /**
     * HOME or WORK: [place] fills that slot, replacing (deleting) the place there; [name] is ignored.
     * OTHER: a new place named [name] (required), refused by SP55 or SP56. A MAP_PIN place is kept
     * with its coordinates as placeName and no subtitle (3.1). A [place] with source SAVED is a
     * programming error (IllegalArgumentException with a fixed message).
     */
    fun save(place: Place, kind: SavedKind, name: String? = null): SaveResult = TODO("track S")
    /** OTHER places only. */
    fun rename(id: String, name: String): SaveResult = TODO("track S")
    /** New point, placeName, subtitle and categoryId from [to], as [save] takes them; id, kind and name stay (SP55 rule 2 for OTHER; [to] being this same place is fine). */
    fun move(id: String, to: Place): SaveResult = TODO("track S")
    /** An OTHER place becomes HOME or WORK: the place in that slot is deleted, the name becomes "Home" or "Work". */
    fun makeKind(id: String, kind: SavedKind): SaveResult = TODO("track S")
    fun delete(id: String): SaveResult = TODO("track S")
    fun setShowOnMap(show: Boolean): SaveResult = TODO("track S")
}

/** "s" and 16 lower-case hex digits from SecureRandom. */
fun randomSavedId(): String = TODO("track S")

/** The name key of rule SP26: uniqueness, reserved names, matching and order of saved names. */
fun savedNameKey(text: String): String = TODO("track S")
```

S's own [TRACK] pieces behind it: `search/saved/SavedPlacesFile.kt` (`encode` and `decode` of 3.1,
the export codec of 3.6), the matcher of SP20, the grapheme count of SP26, and a top-level
`foldText(text: String?): String` in `search/Fold.kt` holding the body of today's
`SearchNormalizer.fold`, which then calls it (one folding for the engine's names and the place-name
rules SP22, SP30 and SP55 rule 2).

### 5.4 Search, the place sheet and the saved places screens [SKELETON, `:feature:search`]

`SearchUi.kt` changed throughout and is quoted whole in `docs/architecture.md` 2.6 (unchanged parts
kept as they are; new: `SAVE`, `SaveTarget`, `SearchContext.accuracyM`, `SavedRowUi`, eight
`SearchUi` fields, five events, five outputs, two place actions, two sheet fields, the `saved`
constructor parameter, `openToSave`, the `driving` and `destination` parameters of `open` and the
`driving` parameter of `placeSheet`; every new function parameter and data-class field has a
default, and the one new constructor parameter, `saved`, is passed by I in `AppGraph`, the only
caller). Its new parts, in the file's order:

```kotlin
// EXCERPT: feature/search/src/main/kotlin/io/github/spysyweeb/beamline/feature/search/SearchUi.kt
/** What a pick is for: a new trip, a stop of the current trip, the trip's start point, or a saved place (SaveTarget). */
enum class SearchMode { GO_TO, ADD_STOP, SET_START, SAVE }

/** What a pick in SAVE mode is for (docs/architecture-saved-places.md, rule SP15). */
sealed interface SaveTarget {
    data object Home : SaveTarget
    data object Work : SaveTarget
    /** A new place with a name: the pick returns SearchOutput.SaveNew and the app opens the save dialog. */
    data object NewPlace : SaveTarget
    /** A new point for the saved place [savedId]; its id, kind and name stay. */
    data class Move(val savedId: String) : SaveTarget
}

enum class NearSource { GPS, MAP_CENTER, NONE }

/**
 * Where results are ranked from: a fresh fix (60 s) when there is one, else the map centre.
 * [accuracyM]: the fix's horizontal accuracy (GPS only; null when unknown): "Use my position" needs
 * it at most saved.position_max_accuracy_m (SP23).
 */
data class SearchContext(
    val near: GeoPoint?,
    val source: NearSource,
    val accuracyM: Double? = null,
    /** S1b: the user's position when [near] is the map centre (results ranked there, distances from the user). */
    val distanceFrom: GeoPoint? = null,
)

data class ShortcutUi(val id: String, val label: String, val iconName: String)

/**
 * Batch O3 (docs/next-round.md item 27): one place type of the "View all" grid and of the type picker
 * of "Add a missing place" (one component, [PlaceTypeGrid]): the category [code], its [label], its
 * sprite [iconName] (the same as the map and the result rows) and the [query] a tap in the search types.
 */
data class PlaceTypeUi(val code: String, val label: String, val iconName: String, val query: String)

/** A plain heading of the grid ("Food and drink") and its types. */
data class PlaceTypeGroupUi(val heading: String, val types: List<PlaceTypeUi>)

/**
 * Batch O3 (docs/next-round.md item 26): one of the user's own sent changes (an added place, an edit, a
 * replacement or a closing), shown in search and on the place sheet as if the map had it, until an
 * installed pack does (the app makes these from its outbox and passes them to [SearchFeature.ownPlaces]).
 * [name]: the name now; [oldName]: the pack's name at that spot it replaces (its pack row is not shown);
 * [categoryCode] / [categoryLabel]: its type; [terms]: words besides its name that find it ("food");
 * [overrides]: "opening_hours", "phone", "website" set by the user (a null value: removed; a key not
 * there comes from the pack); [closed]: closed permanently; [osm]: its OSM object. [toString] prints
 * none of it (PV2).
 */
data class OwnPlaceUi(
    val key: String,
    val name: String,
    val point: GeoPoint,
    val categoryCode: String?,
    val categoryLabel: String?,
    val terms: List<String> = emptyList(),
    val oldName: String? = null,
    val overrides: Map<String, String?> = emptyMap(),
    val closed: Boolean = false,
    val osm: String? = null,
) {
    override fun toString(): String = "OwnPlaceUi"
}

/** [iconName]: a sprite name of the style bundle (assets/poi_icons.json, or saved_home / saved_work / saved_place). [note]: "Exact address", "Street only", "Saved", ... */
data class ResultRowUi(
    val key: String, val iconName: String, val title: String, val subtitle: String?, val distanceText: String?, val note: String?,
    /** Open, closing soon or closed now (rule S10); null when the place has no hours or they cannot be read. */
    val hours: HoursUi? = null,
)

/**
 * A place's opening hours now (rule S10, batch B): [state] gives the colour (green, yellow, red),
 * [label] the words always beside it ("Open now", "Closing soon", "Closed now"), [next] the next
 * change ("until 9 PM", "closes at 9 PM", "opens at 7 AM", "opens Mon at 7 AM", "open 24 hours";
 * null when there is none in the week ahead). [week]: Monday to Sunday (the place sheet's tap);
 * [holidayNote]: "Holiday hours may differ" when holiday rules were left out.
 */
data class HoursUi(
    val state: OpenState,
    val label: String,
    val next: String?,
    val week: List<HoursDayUi> = emptyList(),
    val holidayNote: String? = null,
)

/** One day of the week view: "Mon", "8 AM–5 PM" or "Closed" or "Open 24 hours"; [today] is drawn bold. */
data class HoursDayUi(val day: String, val text: String, val today: Boolean)

/**
 * The results of a search shown on the map (rule S11): [rows] and [places] in the same order,
 * nearest first, at most search_app.json on_map.max_results (20); [selected]: the index whose
 * sheet is open (its pin stands out), null while the list shows.
 */
data class ResultsUi(val query: String, val rows: List<ResultRowUi>, val places: List<Place>, val selected: Int? = null)

/** The results view (rule S11). */
sealed interface ResultsEvent {
    /** A row or a result pin: that place's sheet opens (the app moves the map to it, K1). */
    data class Select(val index: Int) : ResultsEvent
    /** The sheet of a result closed: the list shows again. */
    data object Deselect : ResultsEvent
    /** The list's close button or Back: the results and their pins are gone. */
    data object Close : ResultsEvent
}

/**
 * A Home, Work or other saved-place row of the search screen and the saved places screen.
 * [savedId] null: the "Set home" / "Set work" row. [distanceText] only with a GPS position (SP10).
 */
data class SavedRowUi(
    val savedId: String?,
    val kind: SavedKind,
    val iconName: String,
    val title: String,
    val subtitle: String?,
    val distanceText: String?,
    /** "Outside the installed maps", or "Already your destination" (SET_START, SP14; a tap does nothing). */
    val note: String?,
    /** The row's "Edit" and its accessibility label "Edit Mom's" (SP5); null: no Edit (search rows, a pick-only list). */
    val editLabel: String? = null,
)
```

The new fields at the end of `SearchUi`:

```kotlin
// EXCERPT: feature/search/src/main/kotlin/io/github/spysyweeb/beamline/feature/search/SearchUi.kt
    /** SAVE mode: the line above the field ("Set home", "Set work", "Save place", "New location for Mom's"); null otherwise. */
    val title: String? = null,
    /** START: the Home and Work rows (table of 2.1). */
    val slots: List<SavedRowUi> = emptyList(),
    /** START: the subtitle of the "Saved places" row ("None yet", "1 place", "4 places"); null: no row. */
    val savedPlacesText: String? = null,
    /** START in SAVE mode: the "Choose on the map" row. */
    val chooseOnMap: Boolean = false,
    /** Saved places whose name matches the typed text (SP20, SP21), shown above [results]; keys "saved:<id>"; always empty in SAVE mode. */
    val savedResults: List<ResultRowUi> = emptyList(),
    /** GO_TO START: the notice of SP52 or SP53, shown first with "OK"; null otherwise. */
    val savedNotice: String? = null,
    /** START in SAVE mode: the "Use my position" row (a fresh fix within saved.position_max_accuracy_m, SP23). */
    val useMyPosition: Boolean = false,
    /** Raise the keyboard when the screen opens; false in ADD_STOP while driving (SP4). */
    val focusField: Boolean = true,
    /** Batch O3 ("View all", item 27): the grid of every place type shows over the start rows; null: not shown. */
    val allTypes: List<PlaceTypeGroupUi>? = null,
)
```

The new events (before `Close`):

```kotlin
// EXCERPT: feature/search/src/main/kotlin/io/github/spysyweeb/beamline/feature/search/SearchUi.kt
    /** The Home or Work row: set, a pick (SP12-SP14); empty, SAVE mode for that slot (SP15). */
    data class SlotTapped(val kind: SavedKind) : SearchEvent
    /** The "Saved places" row. */
    data object OpenSavedPlaces : SearchEvent
    /** The "Choose on the map" row (SAVE mode). */
    data object ChooseOnMap : SearchEvent
    /** The "Use my position" row (SAVE mode, SP23). */
    data object UseMyPosition : SearchEvent
    /** "OK" on the notice of SP52 or SP53. */
    data object DismissSavedNotice : SearchEvent
```

The outputs:

```kotlin
// EXCERPT: feature/search/src/main/kotlin/io/github/spysyweeb/beamline/feature/search/SearchUi.kt
sealed interface SearchOutput {
    /** Never with mode SAVE: SAVE picks are saved here (Home, Work, Move) or returned as SaveNew. */
    data class Picked(val place: Place, val mode: SearchMode) : SearchOutput
    /** The app opens the States screen. */
    data object OpenStates : SearchOutput
    data object Closed : SearchOutput
    /** GO_TO: a saved place inside the installed maps was tapped; plan the trip now, no sheet (SP12, SP18). */
    data class PlanTrip(val place: Place) : SearchOutput
    /** Show this place's sheet on the map (a saved place outside the installed maps, SP13). */
    data class ShowPlace(val place: Place) : SearchOutput
    /** Open the saved places screen; a row tap there picks for [mode] (SP27). */
    data class OpenSavedPlaces(val mode: SearchMode) : SearchOutput
    /** SAVE mode for SaveTarget.NewPlace: [place] was picked; the app opens the save dialog. */
    data class SaveNew(val place: Place) : SearchOutput
    /** SAVE mode: "Choose on the map" for [target] (SP16); SAVE mode has ended (back at GO_TO START, or done when opened by openToSave, SP25). */
    data class PickOnMap(val target: SaveTarget) : SearchOutput
    /** Enter found several matches (rule S11): the search screen is done; SearchFeature.results holds them and the app fits [places]. */
    data class ShowResults(val places: List<Place>) : SearchOutput
}
```

The place actions and the sheet:

```kotlin
// EXCERPT: feature/search/src/main/kotlin/io/github/spysyweeb/beamline/feature/search/SearchUi.kt
/**
 * Where results are ranked from: a fresh fix (60 s) when there is one, else the map centre.
 * [accuracyM]: the fix's horizontal accuracy (GPS only; null when unknown): "Use my position" needs
 * it at most saved.position_max_accuracy_m (SP23).
 */
data class SearchContext(
    val near: GeoPoint?,
    val source: NearSource,
    val accuracyM: Double? = null,
    /** S1b: the user's position when [near] is the map centre (results ranked there, distances from the user). */
    val distanceFrom: GeoPoint? = null,
)

data class ShortcutUi(val id: String, val label: String, val iconName: String)

/**
 * Batch O3 (docs/next-round.md item 27): one place type of the "View all" grid and of the type picker
 * of "Add a missing place" (one component, [PlaceTypeGrid]): the category [code], its [label], its
 * sprite [iconName] (the same as the map and the result rows) and the [query] a tap in the search types.
 */
data class PlaceTypeUi(val code: String, val label: String, val iconName: String, val query: String)

/** A plain heading of the grid ("Food and drink") and its types. */
data class PlaceTypeGroupUi(val heading: String, val types: List<PlaceTypeUi>)

/**
 * Batch O3 (docs/next-round.md item 26): one of the user's own sent changes (an added place, an edit, a
 * replacement or a closing), shown in search and on the place sheet as if the map had it, until an
 * installed pack does (the app makes these from its outbox and passes them to [SearchFeature.ownPlaces]).
 * [name]: the name now; [oldName]: the pack's name at that spot it replaces (its pack row is not shown);
 * [categoryCode] / [categoryLabel]: its type; [terms]: words besides its name that find it ("food");
 * [overrides]: "opening_hours", "phone", "website" set by the user (a null value: removed; a key not
 * there comes from the pack); [closed]: closed permanently; [osm]: its OSM object. [toString] prints
 * none of it (PV2).
 */
data class OwnPlaceUi(
    val key: String,
    val name: String,
    val point: GeoPoint,
    val categoryCode: String?,
    val categoryLabel: String?,
    val terms: List<String> = emptyList(),
    val oldName: String? = null,
    val overrides: Map<String, String?> = emptyMap(),
    val closed: Boolean = false,
    val osm: String? = null,
) {
    override fun toString(): String = "OwnPlaceUi"
}

/** [iconName]: a sprite name of the style bundle (assets/poi_icons.json, or saved_home / saved_work / saved_place). [note]: "Exact address", "Street only", "Saved", ... */
data class ResultRowUi(
    val key: String, val iconName: String, val title: String, val subtitle: String?, val distanceText: String?, val note: String?,
    /** Open, closing soon or closed now (rule S10); null when the place has no hours or they cannot be read. */
    val hours: HoursUi? = null,
)

/**
 * A place's opening hours now (rule S10, batch B): [state] gives the colour (green, yellow, red),
 * [label] the words always beside it ("Open now", "Closing soon", "Closed now"), [next] the next
 * change ("until 9 PM", "closes at 9 PM", "opens at 7 AM", "opens Mon at 7 AM", "open 24 hours";
 * null when there is none in the week ahead). [week]: Monday to Sunday (the place sheet's tap);
 * [holidayNote]: "Holiday hours may differ" when holiday rules were left out.
 */
data class HoursUi(
    val state: OpenState,
    val label: String,
    val next: String?,
    val week: List<HoursDayUi> = emptyList(),
    val holidayNote: String? = null,
)

/** One day of the week view: "Mon", "8 AM–5 PM" or "Closed" or "Open 24 hours"; [today] is drawn bold. */
data class HoursDayUi(val day: String, val text: String, val today: Boolean)

/**
 * The results of a search shown on the map (rule S11): [rows] and [places] in the same order,
 * nearest first, at most search_app.json on_map.max_results (20); [selected]: the index whose
 * sheet is open (its pin stands out), null while the list shows.
 */
data class ResultsUi(val query: String, val rows: List<ResultRowUi>, val places: List<Place>, val selected: Int? = null)

/** The results view (rule S11). */
sealed interface ResultsEvent {
    /** A row or a result pin: that place's sheet opens (the app moves the map to it, K1). */
    data class Select(val index: Int) : ResultsEvent
    /** The sheet of a result closed: the list shows again. */
    data object Deselect : ResultsEvent
    /** The list's close button or Back: the results and their pins are gone. */
    data object Close : ResultsEvent
}

/**
 * A Home, Work or other saved-place row of the search screen and the saved places screen.
 * [savedId] null: the "Set home" / "Set work" row. [distanceText] only with a GPS position (SP10).
 */
data class SavedRowUi(
    val savedId: String?,
    val kind: SavedKind,
    val iconName: String,
    val title: String,
    val subtitle: String?,
    val distanceText: String?,
    /** "Outside the installed maps", or "Already your destination" (SET_START, SP14; a tap does nothing). */
    val note: String?,
    /** The row's "Edit" and its accessibility label "Edit Mom's" (SP5); null: no Edit (search rows, a pick-only list). */
    val editLabel: String? = null,
)

enum class SearchPhase { START, SEARCHING, RESULTS, NO_RESULTS, NO_SEARCH_DATA }

data class SearchUi(
    val mode: SearchMode = SearchMode.GO_TO,
    val query: String = "",
    val phase: SearchPhase = SearchPhase.START,
    val shortcuts: List<ShortcutUi> = emptyList(),
    val recents: List<ResultRowUi> = emptyList(),
    val results: List<ResultRowUi> = emptyList(),
    /** "Near your position", "Near the map center". */
    val nearText: String? = null,
    val message: String? = null,
    /** SAVE mode: the line above the field ("Set home", "Set work", "Save place", "New location for Mom's"); null otherwise. */
    val title: String? = null,
    /** START: the Home and Work rows (table of 2.1). */
    val slots: List<SavedRowUi> = emptyList(),
    /** START: the subtitle of the "Saved places" row ("None yet", "1 place", "4 places"); null: no row. */
    val savedPlacesText: String? = null,
    /** START in SAVE mode: the "Choose on the map" row. */
    val chooseOnMap: Boolean = false,
    /** Saved places whose name matches the typed text (SP20, SP21), shown above [results]; keys "saved:<id>"; always empty in SAVE mode. */
    val savedResults: List<ResultRowUi> = emptyList(),
    /** GO_TO START: the notice of SP52 or SP53, shown first with "OK"; null otherwise. */
    val savedNotice: String? = null,
    /** START in SAVE mode: the "Use my position" row (a fresh fix within saved.position_max_accuracy_m, SP23). */
    val useMyPosition: Boolean = false,
    /** Raise the keyboard when the screen opens; false in ADD_STOP while driving (SP4). */
    val focusField: Boolean = true,
    /** Batch O3 ("View all", item 27): the grid of every place type shows over the start rows; null: not shown. */
    val allTypes: List<PlaceTypeGroupUi>? = null,
)

sealed interface SearchEvent {
    data class QueryChanged(val text: String) : SearchEvent
    data class ShortcutTapped(val id: String) : SearchEvent
    /** A result, a recent or a saved result ("saved:<id>"). */
    data class RowTapped(val key: String) : SearchEvent
    data class RemoveRecent(val key: String) : SearchEvent
    data object ClearRecents : SearchEvent
    /** "Add a state" on the NO_SEARCH_DATA screen. */
    data object OpenStates : SearchEvent
    /** The Home or Work row: set, a pick (SP12-SP14); empty, SAVE mode for that slot (SP15). */
    data class SlotTapped(val kind: SavedKind) : SearchEvent
    /** The "Saved places" row. */
    data object OpenSavedPlaces : SearchEvent
    /** The "Choose on the map" row (SAVE mode). */
    data object ChooseOnMap : SearchEvent
    /** The "Use my position" row (SAVE mode, SP23). */
    data object UseMyPosition : SearchEvent
    /** "OK" on the notice of SP52 or SP53. */
    data object DismissSavedNotice : SearchEvent
    data object Close : SearchEvent
    /** The keyboard's search action (Enter): in GO_TO while not driving, the matches go on the map (rule S11). */
    data object Submit : SearchEvent
    /** Batch O3: the "View all" chip at the end of the shortcuts: the grid of every place type opens. */
    data object ViewAll : SearchEvent
    /** Batch O3: a type of the grid: its search runs as a chip's does, and the grid closes. */
    data class TypeTapped(val type: PlaceTypeUi) : SearchEvent
    /** Batch O3: the grid's Back. */
    data object CloseViewAll : SearchEvent
}

sealed interface SearchOutput {
    /** Never with mode SAVE: SAVE picks are saved here (Home, Work, Move) or returned as SaveNew. */
    data class Picked(val place: Place, val mode: SearchMode) : SearchOutput
    /** The app opens the States screen. */
    data object OpenStates : SearchOutput
    data object Closed : SearchOutput
    /** GO_TO: a saved place inside the installed maps was tapped; plan the trip now, no sheet (SP12, SP18). */
    data class PlanTrip(val place: Place) : SearchOutput
    /** Show this place's sheet on the map (a saved place outside the installed maps, SP13). */
    data class ShowPlace(val place: Place) : SearchOutput
    /** Open the saved places screen; a row tap there picks for [mode] (SP27). */
    data class OpenSavedPlaces(val mode: SearchMode) : SearchOutput
    /** SAVE mode for SaveTarget.NewPlace: [place] was picked; the app opens the save dialog. */
    data class SaveNew(val place: Place) : SearchOutput
    /** SAVE mode: "Choose on the map" for [target] (SP16); SAVE mode has ended (back at GO_TO START, or done when opened by openToSave, SP25). */
    data class PickOnMap(val target: SaveTarget) : SearchOutput
    /** Enter found several matches (rule S11): the search screen is done; SearchFeature.results holds them and the app fits [places]. */
    data class ShowResults(val places: List<Place>) : SearchOutput
}

/**
 * CALL and WEBSITE (rule S10) last, never while driving: the app opens the dialer or the browser.
 * REPORT is never in [PlaceSheetUi.actions]: the sheet offers it by [PlaceSheetUi.canReport]
 * (docs/online-design.md 4.1, batch O1); EDIT_OSM neither, by [PlaceSheetUi.canEditOsm] (4.2, batch O2);
 * ADD_PLACE neither, by [PlaceSheetUi.canAddPlace] (4.3, batch O3).
 */
enum class PlaceAction { DIRECTIONS, ADD_STOP, OPEN_STATES, SAVE, EDIT, CALL, WEBSITE, REPORT, EDIT_OSM, ADD_PLACE }

data class PlaceSheetUi(
    val place: Place,
    val iconName: String,
    val title: String,
    val subtitle: String?,
    val distanceText: String?,
    /** "Outside the installed maps" when no routing tile covers it. */
    val note: String?,
    /** In display order (SP19); OPEN_STATES then SAVE or EDIT when the place cannot be routed to. */
    val actions: List<PlaceAction>,
    /** "Saved as Home", "Saved as Mom's"; null when not saved or when the title already is the saved name. */
    val savedText: String? = null,
    /** The saved place this is (EDIT acts on it); null when not saved. */
    val savedId: String? = null,
    /** Opening hours now and the week (rule S10); null when OSM has none or the text cannot be read. */
    val hours: HoursUi? = null,
    /** The number the CALL action dials (the first of OSM's `phone`); null: no CALL. */
    val phone: String? = null,
    /** The address the WEBSITE action opens (http or https, the first of OSM's `website`); null: no WEBSITE. */
    val website: String? = null,
    /** The details height (batch G, docs/next-round.md item 20): every number of OSM's `phone`, each on its own line. */
    val phones: List<ContactUi> = emptyList(),
    /** Every web address of OSM's `website` that a browser can open, each on its own line. */
    val websites: List<ContactUi> = emptyList(),
    /** The postal address from the pack ("4662 West Ridge Road, Erie, PA 16506"); null when it has none. */
    val address: String? = null,
    /** What the place is, in words ("Convenience store"); null when the pack does not say. */
    val category: String? = null,
    /** False while guiding (GUIDING, REROUTING): the numbers and web addresses show, but a tap or a long press does nothing (S10). */
    val canOpenOutside: Boolean = true,
    /**
     * "Report a problem" (docs/online-design.md 4.1): the app sets it while "Contribute to
     * OpenStreetMap" is on and no trip is being driven. A dropped pin shows "Report a problem here"
     * beside its buttons; any other place at the details height. Either sends Act(REPORT).
     */
    val canReport: Boolean = false,
    /** Batch O2 (docs/online-design.md 4.2): the OSM object of the place from its pack ("n25479855"), or null. */
    val osm: String? = null,
    /** The pack's own values of the place (hours, phone, website as OSM had them), or null. */
    val poi: PoiDetails? = null,
    /**
     * "Edit on OpenStreetMap" at the details height: the app sets it for a place with [osm] while
     * "Contribute to OpenStreetMap" is on and no trip is being driven. It sends Act(EDIT_OSM).
     */
    val canEditOsm: Boolean = false,
    /**
     * Batch O3 (docs/next-round.md item 26): the place is one of the user's own sent changes not yet in an
     * installed pack; its values above are the user's, and the sheet shows this one small tag ("Your
     * edit · waiting for the map update"); null otherwise.
     */
    val ownTag: String? = null,
    /**
     * Batch O3 (docs/online-design.md 4.3): "Add a missing place" beside "Report a problem here" on a
     * dropped pin; the app sets it with [canReport]. It sends Act(ADD_PLACE).
     */
    val canAddPlace: Boolean = false,
)
```

The controller:

```kotlin
// EXCERPT: feature/search/src/main/kotlin/io/github/spysyweeb/beamline/feature/search/SearchUi.kt
/**
 * UI-free search controller (rules S1-S7; saved places SP10-SP31). Queries run on
 * [searchDispatcher]; [ui] is read on the main thread. [saved] is read for the rows and written for
 * SAVE-mode picks of Home, Work and Move.
 */
class SearchFeature(
    installed: StateFlow<InstalledSet>,
    recents: RecentPlaces,
    saved: SavedPlaces,
    units: StateFlow<UnitFormatter>,
    scope: CoroutineScope,
    searchDispatcher: CoroutineDispatcher,
) {
    val ui: StateFlow<SearchUi> get() = TODO("track S")
    /**
     * GO_TO, ADD_STOP or SET_START; SAVE goes through [openToSave]. [driving]: phase GUIDING,
     * REROUTING or ARRIVED (no keyboard at once, SP4). [destination]: in SET_START, the trip's last
     * stop, whose saved row reads "Already your destination" (SP14).
     */
    fun open(mode: SearchMode, context: SearchContext, driving: Boolean = false, destination: Place? = null): Unit = TODO("track S")
    /** SAVE mode for [target] (from the saved places screen or a sheet's Edit); after the save, on Close or on "Choose on the map", onEvent returns Closed or PickOnMap and this search is done. */
    fun openToSave(target: SaveTarget, context: SearchContext): Unit = TODO("track S")
    /** Picked places are added to the recents here, except saved places and SAVE-mode picks (SP31). */
    fun onEvent(event: SearchEvent): SearchOutput? = TODO("track S")
    /**
     * For a search pick, a recent, a tapped POI, a dropped pin or a saved place; [tripActive] (phase
     * PLANNING, CHOOSING, GUIDING or REROUTING) offers ADD_STOP first; [driving] (GUIDING, REROUTING
     * or ARRIVED) leaves out SAVE and EDIT (SP4, SP19); [guiding] (GUIDING or REROUTING) leaves out
     * CALL and WEBSITE (S10: offered again once the trip has arrived).
     */
    fun placeSheet(place: Place, context: SearchContext, tripActive: Boolean, driving: Boolean = false, guiding: Boolean = driving): PlaceSheetUi = TODO("track S")
    fun close(): Unit = TODO("track S")

    /** The results on the map after Enter (rule S11); null when none are shown. */
    val results: StateFlow<ResultsUi?> get() = TODO("track S")
    /** Outputs that come with an answer after [onEvent] returned: ShowResults, or Picked for a single match (S11). */
    val later: SharedFlow<SearchOutput> get() = TODO("track S")
    /** A row or pin selected (ShowPlace of that result), the sheet closed or the results closed (S11). */
    fun onResultsEvent(event: ResultsEvent): SearchOutput? = TODO("track S")
    /** S11: ends the results when a state one of them came from is no longer in [set] (removed while they show); true when they ended. */
    fun closeResultsGoneFrom(set: InstalledSet): Boolean = TODO("track S")
    /** Counts up when hours, phone or website of a place arrived from its pack: a place sheet made before is made again (S10). */
    val detailsLoaded: StateFlow<Int> get() = TODO("track S")
    /** Recomputes the statuses of the results rows at the clock (the app calls it each minute, S10). */
    fun refreshHours(): Unit = TODO("track S")
    /** The phone's local date and time the hours are evaluated at (S10); the test extras may set another. */
    var hoursClock: () -> LocalDateTime
        get() = TODO("track S")
        set(value) = TODO("track S")
}
```

New contract file for the saved places screen, the dialogs and the map pick:

```kotlin
// FILE: feature/search/src/main/kotlin/io/github/spysyweeb/beamline/feature/search/SavedUi.kt
package io.github.spysyweeb.beamline.feature.search

import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.map.MapPoi
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.api.model.SavedPlace
import io.github.spysyweeb.beamline.search.RecentPlaces
import io.github.spysyweeb.beamline.search.saved.SavedPlaces
import io.github.spysyweeb.beamline.units.UnitFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** The saved places screen (docs/architecture-saved-places.md 2.6). Every text is made by SavedPlacesFeature. */
data class SavedListUi(
    /** Home (or "Set home"), Work (or "Set work"), then the other places by name; empty when read-only; pick-only: saved places only. */
    val rows: List<SavedRowUi>,
    /** The "Show on the map" switch. */
    val showOnMap: Boolean,
    /** The "Add a place" row (false at the limit, when read-only and when pick-only). */
    val canAdd: Boolean,
    /** "Places you save from search or the map show here." or the limit line; null otherwise. */
    val hint: String?,
    /** The notice of SP52 or SP53 (with "OK"), or the write failure of SP51; null otherwise. */
    val notice: String?,
    /** Opened from ADD_STOP or SET_START search: rows only, no Edit, no Set rows, no "Add a place", no switch (SP4). */
    val pickOnly: Boolean = false,
)

/** MOVE is "Change location" on screen; DELETE is "Remove" (2.6). */
enum class EditAction { RENAME, MOVE, MAKE_HOME, MAKE_WORK, DELETE }

/** "Rename", "Change location", "Make home", "Make work", "Remove" ("Remove home", "Remove work" for DELETE on Home, Work). */
data class MenuItemUi(val action: EditAction, val label: String)

/** A chip of the save dialog: "Home", "Work", "Other" (radio buttons to a screen reader, SP5). */
data class KindChoiceUi(val kind: SavedKind, val label: String, val selected: Boolean)

/** The one dialog on top. */
sealed interface SavedDialogUi {
    /** The Edit menu of one saved place; [title] is its name. */
    data class Menu(val title: String, val items: List<MenuItemUi>) : SavedDialogUi

    /** "Save place" (SP17, SP28). */
    data class Save(
        val placeTitle: String,
        val placeSubtitle: String?,
        val kinds: List<KindChoiceUi>,
        /** The name field's text; null when Home or Work is chosen (no field). */
        val name: String?,
        /** "This replaces your home: Erie Maritime Museum, 100 East Front Street, Erie." */
        val replaceText: String?,
        /** Why "Save" is off, or why the last save failed; null when fine. */
        val problem: String?,
        /** "Save" or "Replace". */
        val confirmText: String,
        val canConfirm: Boolean,
    ) : SavedDialogUi

    /** "Rename". */
    data class Rename(val name: String, val problem: String?, val canConfirm: Boolean) : SavedDialogUi

    /** "Remove Mom's?", "Remove your home?", "Make Mom's your home?" */
    data class Confirm(val title: String, val text: String?, val confirmText: String) : SavedDialogUi
}

/** Choosing a point on the map (SP16, SP24). */
data class MapPickUi(
    /** "Tap the map at your home", "… at your work", "… at the place", "… at the new location" */
    val prompt: String,
    /** The point chosen so far (the app draws it as the PICKED pin); null before the first tap. */
    val place: Place?,
    /** "Point on the map" or the tapped POI's name; "No point chosen yet" before the first tap. */
    val title: String,
    /** "42.13646, -80.08718"; null before the first tap. */
    val subtitle: String?,
    /** "Outside the installed maps" (saving is still allowed). */
    val note: String?,
    /** "Set home", "Set work", "Save", "Use this point". */
    val confirmText: String,
    /** False before the first tap, below saved.pick_min_zoom and outside every state's map (SP24). */
    val canConfirm: Boolean,
    /** "Zoom in to choose the exact spot.", "Add this state to choose a point here.", or why the last confirm failed; null when fine. */
    val problem: String?,
)

data class SavedUi(
    /** Non-null while the saved places screen is open. */
    val list: SavedListUi? = null,
    /** Shown above everything (the list, the map, a place sheet, the pick bar). */
    val dialog: SavedDialogUi? = null,
    /** Non-null while a point is being chosen on the map. */
    val mapPick: MapPickUi? = null,
)

sealed interface SavedEvent {
    /** A row with a saved place (SP27). */
    data class RowTapped(val savedId: String) : SavedEvent
    /** "Set home" or "Set work" on the list. */
    data class SlotTapped(val kind: SavedKind) : SavedEvent
    data class EditTapped(val savedId: String) : SavedEvent
    data object AddTapped : SavedEvent
    data class ShowOnMapChanged(val show: Boolean) : SavedEvent
    data class MenuChosen(val action: EditAction) : SavedEvent
    data class KindChosen(val kind: SavedKind) : SavedEvent
    data class NameChanged(val text: String) : SavedEvent
    /** The dialog's confirm button. */
    data object DialogConfirm : SavedEvent
    /** The dialog's "Cancel", a tap outside it, or Back while it is open. */
    data object DialogDismiss : SavedEvent
    data object PickConfirm : SavedEvent
    /** "Cancel" on the pick bar, or Back while picking. */
    data object PickCancel : SavedEvent
    /** "OK" on the notice of SP52 or SP53. */
    data object NoticeDismissed : SavedEvent
    /** Back on the list. */
    data object Back : SavedEvent
}

sealed interface SavedOutput {
    /** List opened from GO_TO search: plan the trip now (inside the installed maps). */
    data class PlanTrip(val place: Place) : SavedOutput
    /** Show the place's sheet on the map (outside the installed maps, or the list was opened from Settings). */
    data class ShowPlace(val place: Place) : SavedOutput
    /** List opened from ADD_STOP or SET_START search: [place] picked for [mode]. */
    data class Picked(val place: Place, val mode: SearchMode) : SavedOutput
    /** Open search in SAVE mode for [target] ("Set home", "Set work", "Add a place", "Change location"). */
    data class SearchToSave(val target: SaveTarget) : SavedOutput
    /** A save from the save dialog is done; the screens below rebuild themselves (SP19). */
    data class Saved(val place: SavedPlace) : SavedOutput
    /** The map pick ended: saved ([place]) or cancelled (null); the app shows again what the pick hid (SP25). */
    data class PickEnded(val place: SavedPlace?) : SavedOutput
    data object Closed : SavedOutput
}

/**
 * UI-free controller of the saved places screen, the Edit menu, the save, rename and confirm
 * dialogs and the map pick (rules SP4, SP15-SP19, SP23-SP25, SP27, SP30, SP45). Changes go to
 * [store] on the calling thread (its file write runs on the store's own writer, SP51); a removed
 * saved place's recents are removed from [recents] (SP30). [ui] is read on the main thread.
 */
class SavedPlacesFeature(
    store: SavedPlaces,
    recents: RecentPlaces,
    installed: StateFlow<InstalledSet>,
    units: StateFlow<UnitFormatter>,
    scope: CoroutineScope,
) {
    val ui: StateFlow<SavedUi> get() = TODO("track S")
    /**
     * Opens the list. [pickFor]: the search mode a row tap picks for (opened from search; ADD_STOP
     * and SET_START make it pick-only, SP4); null: opened from Settings, a row tap shows the place.
     * [destination]: in SET_START, the trip's last stop (SP14).
     */
    fun openList(pickFor: SearchMode?, context: SearchContext, destination: Place? = null): Unit = TODO("track S")
    /** The save dialog for [place] (place sheet "Save", SearchOutput.SaveNew). */
    fun openSave(place: Place): Unit = TODO("track S")
    /** The Edit menu of a saved place (place sheet "Edit"). */
    fun openEdit(savedId: String): Unit = TODO("track S")
    /** Starts choosing a point for [target] (SearchOutput.PickOnMap). */
    fun startMapPick(target: SaveTarget): Unit = TODO("track S")
    /** A tap or long press on the map while picking; [poi]: the POI label under it; [zoom]: the camera zoom at the tap (SP24). */
    fun onMapTap(point: GeoPoint, poi: MapPoi?, zoom: Double): Unit = TODO("track S")
    fun onEvent(event: SavedEvent): SavedOutput? = TODO("track S")
}
```

Three composables added to `feature/search/src/main/kotlin/io/github/spysyweeb/beamline/feature/search/SearchScreens.kt`
(the existing three unchanged; the file is quoted whole in `docs/architecture.md` 2.6):

```kotlin
// EXCERPT: feature/search/src/main/kotlin/io/github/spysyweeb/beamline/feature/search/SearchScreens.kt
/** The results on the map after Enter (rule S11): the list at the bottom, pulled up for more. */
@Composable
fun ResultsSheet(ui: ResultsUi, onEvent: (ResultsEvent) -> Unit, modifier: Modifier = Modifier): Unit = TODO("track S")

/** The saved places screen (2.6). */
@Composable
fun SavedPlacesScreen(ui: SavedListUi, onEvent: (SavedEvent) -> Unit, modifier: Modifier = Modifier): Unit = TODO("track S")

/** The Edit menu or a dialog of 2.2-2.6, over whatever is on screen. */
@Composable
fun SavedDialog(ui: SavedDialogUi, onEvent: (SavedEvent) -> Unit, modifier: Modifier = Modifier): Unit = TODO("track S")

/** The pick prompt at the top (in the search pill's place) and the bar at the bottom, over the map; the app keeps its my-location button above the bar. */
@Composable
fun MapPickBar(ui: MapPickUi, onEvent: (SavedEvent) -> Unit, modifier: Modifier = Modifier): Unit = TODO("track S")
```

### 5.5 What the app does with the outputs (I)

| Output or event | I does |
|---|---|
| Opening search | `search.open(mode, context, driving = phase in GUIDING, REROUTING, ARRIVED, destination = last stop in SET_START)`; `SearchContext.accuracyM` from the fix (SP23) |
| `SearchOutput.PlanTrip(p)`, `SavedOutput.PlanTrip(p)`, sheet "Directions" | close search and the list; SP18 by phase, with its two dialogs (permission first, as Directions today) |
| `ShowPlace(p)` (both) | close search and the list (and Settings); open `PlaceSheet(search.placeSheet(p, context, tripActive, driving))` |
| `SearchOutput.Picked(p, ADD_STOP / SET_START)`, `SavedOutput.Picked` | close search and the list; `AddStop(p)` / `SetOrigin(FromPlace(p))` (as today) |
| `SearchOutput.OpenSavedPlaces(m)` | `saved.openList(pickFor = m, context, destination)`; show `SavedPlacesScreen` above search |
| `SavedOutput.SearchToSave(t)` | `search.openToSave(t, context)`; show search above the list or the sheet |
| `SearchOutput.Closed` | pop one screen (back to the list or the sheet when it opened search, else to the map) |
| `SearchOutput.SaveNew(p)` | pop search (to the list); `saved.openSave(p)` |
| `SearchOutput.PickOnMap(t)` | close search when `openToSave` opened it, else hide it (it is at GO_TO START); hide the list and the sheet; `saved.startMapPick(t)` (SP25) |
| `SavedOutput.PickEnded(sp)` | show again what the pick hid: the search screen, the list or the sheet (rebuilt, SP19) |
| `SavedOutput.Saved(sp)` | nothing more: the list and an open sheet rebuild from the store (SP19) |
| `SavedOutput.Closed` | pop the list |
| `SearchEvent.DismissSavedNotice`, `SavedEvent.NoticeDismissed` | handled inside S's controllers (`store.dismissNotice()`) |
| Sheet "Save" / "Edit" | `saved.openSave(sheet.place)` / `saved.openEdit(sheet.savedId!!)` |
| `MapGesture.Tap` with `pinId` "saved:<id>" | while picking: `onMapTap`; else the sheet of `store.byId(id)?.toPlace()` |
| `Tap` / `LongPress` while `ui.mapPick != null` | `saved.onMapTap(point, poi, cameraZoom)`; no sheet, no dropped pin |
| `store.places` or `showOnMap` changes | rebuild an open sheet (SP19); new scene with `saved = if (showOnMap) places else emptyList()` |
| Back | dialog → `DialogDismiss`; pick → `PickCancel`; list → `Back`; search → `Close` |

The saved places screen and the search screen cover the map; `SavedDialog` draws over everything
when `ui.dialog != null`; `MapPickBar` shows when `ui.mapPick != null`, with the pill and the place
sheet hidden; the my-location button and the credit (line or mark) stay visible above the bar.

### 5.6 Settings [no change]

The marker switch lives in `saved-places.json` (SP45), so `:core:settings` (frozen) does not
change. I adds a row **"Saved places"** to the Settings screen (`:app`), in a new group
**"Places"** above "About", that opens the list with `pickFor = null`; in GUIDING, REROUTING and
ARRIVED the row reads "Not while guiding" and opens nothing (SP4).

### 5.7 Drive [no change]

`:feature:drive` needs nothing new: stop rows, the start row (`fromText`), cards, arrival texts and
the notification use `Place.name` and `Place.subtitle`, which `SavedPlace.toPlace()` fills ("Home",
the description). Adding a saved stop and choosing a saved start go through search
(`DriveEvent.SearchForStop`, `ChooseStart`) as for any place. D adds one JVM test (6.1).

## 6. Ownership

### 6.1 Work per track

No track edits another track's files. Folders are those of `docs/architecture.md` 5.1 unless named.

| Track | Work this design adds | Where |
|---|---|---|
| **S** | `SavedPlaces` (SP50-SP56: structure-only reading, the persisted notice), `SavedRules`, `SavedPlacesFile` codec, `savedNameKey` and the grapheme count (SP26), `foldText`, the matcher (SP20); `SearchFeature` additions (SP4, SP10-SP15, SP18-SP23, SP30, SP31, the sheet of SP19); `SavedPlacesFeature` (2.2-2.6, SP4, SP16, SP23-SP25, SP27, SP30, SP45); composables: slot rows, notice, "Saved places", "Use my position" and "Choose on the map" rows, saved result rows, `SavedPlacesScreen` (full and pick-only), `SavedDialog`, `MapPickBar`, the pill's hint text (SP2), sheet "Save"/"Edit"/"Saved as"; accessibility and layout (SP5, SP6); the keyboard flag (PV8); `search_app.json` "saved" read (its copy in `:core:search` was added in Round SP0, 6.3); `packbuilder/search/saved_places.py` and its test; `android/scripts/check_private_logs.py` (PV3); JVM tests 10.1 (leak tests included), 10.2; emulator screenshots 10.4, from `:feature:search`'s test APK, which gets the app's style sprites through `getByName("androidTest").assets.directories.add("../../app/src/main/assets")` in S's build file (`docs/architecture.md` 5.1 S); until P's first wave announces the three saved icons in the sprites, a stand-in image under each name in test code only, and the screenshots with a saved icon after the announcement | `android/core/search/` (new `search/saved/`, `search/Fold.kt`), `android/feature/search/`, `packbuilder/search/` (`saved_places.py`, `tests/test_saved_places.py`), `android/scripts/copy_search_rules.py`, new `android/scripts/check_private_logs.py` |
| **M** | `SavedMarkerRules.parse` / `bundled`; `MapSceneMapper` saved markers (kinds, min zooms, scale, SP43) and `savedBelowRoute`; `MapLibreController`: the saved source and layers above and below the route lines (SP41-SP43, SP46), sort order Home on top, hit test (SP44), re-added on style load; `:map:demo` shows made-up saved markers (until P's first wave announces the three icons in the sprites, with a stand-in image added under each name in demo code only); JVM tests 10.1; screenshots 10.4, the markers' after P's announcement | `android/map/` |
| **P** | Rebuild the sprite sheets with the three new icons (`build_sprites.py` reads `icons.json`; no code change expected), refresh the contact sheet for the owner (`build_sheet.py`, then `render_art.py page` for the PNG), sync the style bundle into the app (`node generate_style.mjs`, `sync_assets.py`, `check` in sync); each step run on its own, not through `build_art.py`, whose `markers` step writes M's `data/art/markers/` (`docs/architecture.md` 5.1 P). First wave, announced to S and M | `packbuilder/map/` outputs, `android/app/src/main/assets/style/` via `sync_assets.py`; `data/art/` except M's `markers/` and `build/markers/`, and the git-ignored `assets/sheet/` (`docs/architecture.md` 5.2, Data) |
| **N** | `TripStore` writes and reads `PlaceSource.SAVED` and `savedId` (one JVM round-trip test); any log call in `:nav:session` main sources follows PV2 item 3 (a plain literal, or marked and free of stops and points) | `android/nav/session/` |
| **D** | One JVM test: `present` with a stop made from a saved Home shows the row title "Home" and its description, and `FromPlace(home)` gives `fromText` "Home" | `android/feature/drive/` |
| **A** | None (the new icons are drawn for the project under MIT, covered by `assets/LICENSES.md`'s existing row for `icons/beamline/*.svg`; no new dependency, so no notice entry) | |
| **G** | None beyond what rule N3 of `docs/architecture-downloads.md` already asks: its log lines name only catalog-relative paths, pack ids, enum names and numbers, and carry the `// log-ok:` mark of PV2 item 3 when they print a value | |
| **I** | `AppGraph`: `SavedPlaces(File(dir, "saved-places.json"), SavedRules.bundled(), Dispatchers.IO.asExecutor())`, `RecentPlaces` and `TripStore` in the same `dir` (`noBackupFilesDir`, or its `test/` folder, PV4, PV7), `SavedPlacesFeature` (with the `RecentPlaces`), the `saved` argument of `SearchFeature`, `MapSceneMapper()` with `saved`; the map screen layout of SP1-SP3; the wiring of 5.5 (search opened with `driving` and `destination`, `accuracyM` in the context, the pick hiding and showing screens, the camera zoom to `onMapTap`, the my-location button kept during a pick); the SP18 phase rule and its two dialogs; the Settings row with "Not while guiding" (5.6); manifest attribute and `res/xml/data_extraction_rules.xml` (PV4); the test extras `private_data` and `location_log`, chosen before the first `graph` access (PV7), and the camera extras of 10.3 (`--ef lat`, `--ef lon`, `--ef zoom`: one camera move, also to a running app); the rewrite of `:app`'s log lines: no coordinates except the marked puck line, values only in marked lines, exceptions by class name (PV2); checklist and E2E additions (6.4, 10.3) | `android/app/` |

**Order.** (1) Round SP0 (6.3, done) before anything. (2) P's sprite rebuild in the first wave; S and M
code against the icon names from the start and need the sprites only for their screenshots. (3) S
and M in parallel: M needs only the `:core:api` types, not S's code. N and D any time. (4) I after
the round. The drafts are used in the round's emulator screenshots and on the owner's phone for
review, where the owner judges them; nothing with unapproved artwork is merged into the main line or
released (the project's standing rule: artwork is approved before it ships). A change the owner asks
for replaces the SVGs only, under the same names, so no code waits on the approval.

### 6.2 Definitions of done (in `docs/architecture.md` 5.1 since Round SP0)

- **S:** the JVM tests of 10.1 (the leak tests of PV3 included) and the checks of 10.2 pass;
  `copy_search_rules.py --check` covers `search_app.json`; `python -m pytest
  packbuilder/search/tests/test_saved_places.py` passes; `check_private_logs.py scan` passes on S's
  modules; emulator screenshots of 10.4, day and night, looked at; the keyboard check of PV8 done (the editor flags on the emulator; Gboard's mark is I's, 10.3 step 18).
- **M:** the mapper and rules tests of 10.1 pass; the camera.json copy equals its source; the
  screenshots of 10.4 looked at.
- **P:** `saved_home`, `saved_work`, `saved_place` are in `light`/`dark` sprites (1x and 2x) of the
  synced bundle; `sync_assets.py check` passes; the contact sheet shows them for the owner.
- **N, D:** the one test each.
- **I:** 10.3 on the emulator passes, with `check_private_logs.py logcat` and `manifest`; on the
  phone, the steps of 10.3 marked "phone" in the test set only (PV7).

### 6.3 Round SP0: what the orchestrator changed before the round (done 2026-11-16)

Round SP0 is the set-up step of this design, made by the orchestrator after Round G0 and before the
seven tracks start (`docs/architecture.md` section 0; its appendix B records the build and the
checks). What it changed:

1. `:core:api` [FROZEN]: `Model.kt` (5.1), the new `api/model/Saved.kt`, `MapScene.kt` (5.2), and the
   new test `SavedPlaceTest.kt` (5.1, four tests).
2. `:map:scene` [SKELETON]: `Scene.kt` (5.2).
3. `:core:search` [SKELETON]: the new `search/saved/SavedPlaces.kt` (5.3).
4. `:feature:search` [SKELETON]: `SearchUi.kt` (5.4, whole file), the new `SavedUi.kt`, the three
   composables in `SearchScreens.kt`.
5. `:nav:session` [SKELETON], doc only: `TripStore`'s comment "trip.json in the app's files folder"
   became "trip.json in the app's no-backup folder (getNoBackupFilesDir)".
6. Data: `packbuilder/search/search_app.json` and `packbuilder/routing/camera.json` as in section 7;
   `python android/routing/scripts/sync_data.py --only camera.json`; `android/scripts/copy_search_rules.py`
   gained the row of 7.1 and made the copy `search_app.json` (and its line in `SOURCES.json`) in
   `:core:search`'s rules folder; `RulesAndVersionsTest.ruleCopiesMatchPackbuilder` (S's), which
   lists the copies it expects, gained `"search_app.json"` in that list (one line), so it checks the
   new copy against its source.
7. Art (section 8): the three SVGs in `assets/icons/beamline/`, their `icons.json` entries and the
   `saved` colour pairs (with a line in the file's `_doc`); `assets/README.md`'s `icons/beamline/*.svg`
   row gained "home, work, saved place", and its template-colour paragraph says that these three are
   filled. The owner's approval on P's contact sheet is still to come (question 12.3; it may come
   during the round).
8. This document (from the reviewed design), `docs/architecture.md` (6.4) and
   `docs/architecture-downloads.md` where the two designs meet (below); the docstring of
   `tools/check_contracts.py`, which already read every `docs/architecture-*.md` since Round G0, now
   names this document (no change in behaviour; its trials were run again on a scratch copy of the
   tree, `docs/architecture.md` appendix B); then `python tools/check_contracts.py --freeze`,
   `python tools/check_contracts.py` and `--exact`, and the full build of `docs/architecture.md` 5.3
   (the stubs compile; `SavedPlaceTest` passes).

Where the design was changed in Round SP0 (each recorded where its text changed):

- The KDoc and the data files' notes name this document where the design named the path of its draft.
- `Saved.kt` is quoted whole in `docs/architecture.md` 2.1 with the rest of the frozen `:core:api`,
  not here (5.1 shows it as an excerpt). `SavedPlaces.kt` and `SavedUi.kt` are quoted here; every
  other file this design changed is quoted whole in `docs/architecture.md` (6.4).
- The copy of `search_app.json`: the design gave the new row of `copy_search_rules.py` to S during
  the round; Round SP0 added it, so `SavedRules.bundled()` has its copy from the start (as Round G0
  added `download_rules.json`'s pair to `sync_data.py`), with the one line of S's
  `RulesAndVersionsTest` that names the expected copies. The copy is in `:core:search`, where
  `:feature:search` reads it too; `docs/architecture.md` 2.6 had said `:feature:search`.
- `SavedPlaceTest` checks a few more holders than the design listed (`NavIntent`, `TripState`,
  `MapScene`) and that `SAVED` is the last `PlaceSource`.
- Where this design and `docs/architecture-downloads.md` meet, the two now say the same (the stricter
  privacy rule kept where they differed): the search pill comes after the driver notice and "Choose
  your state" (SP1); G's map chip sits under the pill and the States round button stays (SP2, and
  that document's 1.5 and 9.4); the "Not while guiding" of SP4 is the installer's word for a held
  change, while G's States screen says "You can't remove a state during a trip." (SP4, SP13); a
  log line may name a catalog-relative path, and G's value-printing log lines carry the `// log-ok:`
  mark (PV2 item 3, rule N3); the download journeys on the owner's phone follow PV7 (item 7 here,
  and that document's 10.4); the camera extras that 10.3 uses are now in I's test-extras list
  (`docs/architecture.md` 5.4 item 12); and the end-to-end variant "remove while guiding" of
  `docs/architecture.md` section 8 now expects G's words, as that document's 10.4 row 9 does.

### 6.4 Where `docs/architecture.md` carries this design

Round SP0 made these changes to the main contract (its appendix B records the step); the details
stay here. Each `// FILE:` block there that this design changed was replaced in full; no excerpt of
this document is pasted beside it.

| `docs/architecture.md` | What it says about saved places |
|---|---|
| Header, 0 | The goal (a search bar at the top; Home, Work and named places, one tap, markers on the map, only on the phone); Round SP0 done. |
| 1, 1.2 | The rows of `:core:api`, `:core:search`, `:feature:search`, `:map:scene` and `:map:layers`; `saved-places.json` among the JSON stores on `Dispatchers.IO`. |
| 2.1 | `Model.kt` (`PlaceSource.SAVED`, `Place.savedId`, the redacted `toString()`) and `Saved.kt`, whole. |
| 2.4 | `BeamlineNavigationSession.kt`, whole (the folder of `trip.json`). |
| 2.5 | `MapScene.kt` and `Scene.kt`, whole. |
| 2.6 | `SearchUi.kt`, `SearchScreens.kt` and `search_app.json`, whole; the copy in `:core:search`. |
| 2.11, 2.12 | The format `beamline-saved-places/1`; the persisted files: `saved-places.json`, `recents.json` and `trip.json` in the no-backup folder, and the test set's folder (PV7). |
| 2.14 | The list of this document's contract files. |
| 3.7, 3.8 | `camera.json`, whole; the marker rules SP40-SP46 in short; rule S7 amended (SP4, SP18, SP19), S8 (the search bar, SP1-SP3), S9 (saved places in search). |
| 4 | Backup and transfer (PV4, `dataExtractionRules`), the privacy rules PV1-PV8 in short, logging. |
| 5.1 | The work and definitions of done of S, M, P, N, D and I (6.1, 6.2). |
| 5.2, 5.3 | The search rules copy; logging for every track (PV2); the owner's phone (PV7); Round SP0's build key. |
| 5.4 | Items 2, 3, 4, 5, 6, 7, 8, 10, 12 and 14 (5.5, 5.6, PV3, PV4, PV7). |
| 6 | Decisions 6.54-6.69 (section 0); questions 10-13 of 6.38 (section 12). |
| 7, 8 | Android Auto and the comma 3X port (section 9); the saved-places journey of 10.3 in the end-to-end test; PV7 for every phone run. |

## 7. Rules as data

Both rule files exist in `docs/architecture.md`; each section is read only by its owner's module.

### 7.1 `packbuilder/search/search_app.json` (S; copied by `copy_search_rules.py`)

The whole file is quoted in `docs/architecture.md` 2.6 (with its `note`, which names this
document). Its new section, as the copy in `:core:search` holds it (the copy equals the source:
`copy_search_rules.py --check` and `RulesAndVersionsTest`):

```json
// EXCERPT: core/search/src/main/resources/io/github/spysyweeb/beamline/search/rules/search_app.json
  "saved": {"max_other": 100, "name_max_chars": 40, "same_place_m": 50, "search_min_chars": 1, "search_max_rows": 3,
            "pick_min_zoom": 16, "position_max_accuracy_m": 30},
```

The format stays `beamline-search-app/1`: no reader of it exists yet (Round 0 stubs). Round SP0
added to S's `copy_search_rules.py` the row `("packbuilder/search/search_app.json", "search_app.json",
"search screen and saved places: near, typing, merge, recents, saved, shortcuts")`, so the copy sits
in `android/core/search/src/main/resources/io/github/spysyweeb/beamline/search/rules/` beside the
other rule copies, where `SavedRules.bundled()` (JVM) and `:feature:search` (which depends on
`:core:search`) both read it, and `RulesAndVersionsTest` checks it against its source. `same_place_m`
equals the recents' 50 m on purpose: SP30 compares recents with saved places. `pick_min_zoom` is a
map zoom but lives here, not in M's `camera.json`, because `SavedPlacesFeature` (S) applies it and
a module reads only its own rules (`docs/architecture.md` 1.1); 16 is about 0.9 m per dp at Erie's
latitude, so a finger's error of about 30 dp is about 25 m, less than a house lot *(estimate)*. A
name length lowered later never touches saved names (SP52).

### 7.2 `packbuilder/routing/camera.json` (M; copied by `sync_data.py --only camera.json`)

The whole file is quoted in `docs/architecture.md` 3.7. Its new section, as the copy in `:map:scene`
holds it (`sync_data.py --check`):

```json
// EXCERPT: map/scene/src/main/resources/io/github/spysyweeb/beamline/map/scene/camera.json
  "saved_places": {"home_work_min_zoom": 10, "other_min_zoom": 12, "icon_scale": 1.25},
```

The marker zooms live in M's own rule file because `:map:scene` builds the markers and
`:map:layers` may not read another module's rules (`docs/architecture.md` 1.1): the mapper puts
`minZoom` and `scale` into each `SavedMarker`. Format stays `/1` (no reader yet). No new packbuilder
file and no new copy script are needed.

## 8. Artwork

**What exists** (verified, `assets/icons/`): 7 project icons in `beamline/` (atm, bank, hospital,
parking, pharmacy, truck_stop, welcome_center) and 89 upstream icons; none is a house, a briefcase or
a star (the welcome center icon is a house with an "i"-like mark, and reusing it would confuse two
meanings). The screen icons of `BeamlineIcons` (`ui/theme/.../Icons.kt`: my-location, packs,
settings, back, add) have none either. So three icons are drawn **for the project**, in the set's
frame, MIT like the other `beamline/` icons; no outside icon set is used.

**Style.** The set's frame (`rect x=0.5 y=0.5 width=18 height=18 rx=4`, `0 0 19 19` viewBox, 1 px
outline), but **filled**: frame fill `#000000` (→ the colour pair's first colour), outline and glyph
`#ffffff` (→ the second), so they read as "yours" against the pale-framed POI icons. New colour group
`saved` in `assets/icons/icons.json`: `"light": "saved": ["#1F5BC4", "#FFFFFF"]` (route casing B by
day) and `"dark": "saved": ["#3A80F0", "#FFFFFF"]` (route fill B by night); white on #1F5BC4 is 6.3:1, on #3A80F0
3.8:1 (computed here with the WCAG relative-luminance formula; 3:1 is the floor for icons). No `label_colors` entry is needed: no map kind uses
the group (verified, `generate_style.mjs` reads label colours only for `poi_icons.json` map kinds).
No lettering.

Entries in `icons.json` `icons`:

```json
  "saved_home": {"source": "beamline", "color": "saved", "_note": "the user's Home (app layer and search rows, not the style's POIs)"},
  "saved_work": {"source": "beamline", "color": "saved", "_note": "the user's Work"},
  "saved_place": {"source": "beamline", "color": "saved", "_note": "the user's other saved places"}
```

Drafts (added in Round SP0; the owner judges them on P's contact sheet,
`assets/sheet/index.html`, and on the phone):

```xml
<!-- assets/icons/beamline/saved_home.svg -->
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 19 19">
  <!-- Drawn for Beamline in the frame of the Protomaps/tangrams set (18 x 18 rounded square, 1 px outline), filled for the user's own places (docs/architecture-saved-places.md 8; a draft for the owner's approval). MIT License, see assets/LICENSES.md. Template colours: #000 = the 'saved' colour, #fff = its partner (white). -->
  <rect x="0.5" y="0.5" width="18" height="18" rx="4" ry="4" fill="#000000" stroke="#ffffff" stroke-miterlimit="10"/>
  <path d="M9.5,3.75L15,8.75h-1.5v6h-2.75v-3.5h-2.5v3.5H5.5v-6H4Z" fill="#ffffff"/>
</svg>

<!-- assets/icons/beamline/saved_work.svg -->
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 19 19">
  <!-- (same header comment) -->
  <rect x="0.5" y="0.5" width="18" height="18" rx="4" ry="4" fill="#000000" stroke="#ffffff" stroke-miterlimit="10"/>
  <path d="M7.25,6.5V5.25a1,1 0 0 1 1-1h2.5a1,1 0 0 1 1,1V6.5" fill="none" stroke="#ffffff" stroke-width="1.25"/>
  <rect x="4" y="6.5" width="11" height="8.25" rx="1.25" fill="#ffffff"/>
  <path d="M4,10.25h11" stroke="#000000"/>
</svg>

<!-- assets/icons/beamline/saved_place.svg -->
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 19 19">
  <!-- (same header comment) -->
  <rect x="0.5" y="0.5" width="18" height="18" rx="4" ry="4" fill="#000000" stroke="#ffffff" stroke-miterlimit="10"/>
  <path d="M9.5,4L10.881,7.849L14.969,7.973L11.735,10.476L12.88,14.402L9.5,12.1L6.12,14.402L7.265,10.476L4.031,7.973L8.119,7.849Z" fill="#ffffff"/>
</svg>
```

(House: walls 5.5-13.5, eaves 4-15, door 2.5 × 3.5, centred on 9.5. Briefcase: body 11 × 8.25 with a
seam, handle centred. Star: five points, outer radius 5.75, inner 2.35, centred at 9.5, 9.75.)

The same three sprite images serve the map markers (M), the search and list rows and the save
dialog's chips (S, cut from the sprite sheet as the category icons of `docs/architecture.md` are). No other new
artwork: "Edit", "Save", "Use my position", "Choose on the map" and the menu are words, not icons;
the search pill's magnifier is already S's (`docs/architecture.md` 5.1).

**For the owner's approval:** the three icons and the `saved` colours, by day and night, on the
contact sheet and in M's and S's screenshots (10.4).

## 9. Android Auto and the Python client later

Reused unchanged by a later **Android Auto** screen (same process, same `AppGraph`):
`SavedPlaces` (JVM: list, `matching` for the car's search template, `find`), `SavedPlace`,
`SavedKind` and `toPlace()` (`:core:api`), `NavIntent.PlanTo` / `AddStop` with the same places, the
rules in `search_app.json`, and `MapScene.saved` with `SavedMarkerRules`, which a car-surface
`MapController` draws like the phone's. A car destination list is Home, Work, then the others, in
`places` order. The car never edits saved places (11).

Reused unchanged by a **Python client on a comma 3X**: the file format of 3.1 (plain, versioned
JSON); `packbuilder/search/saved_places.py` (S, standard library only: `load(path) -> list[dict]`
raising `SavedPlacesError("broken" | "newer")` with the structure checks of SP52-SP53, `name_key`
equal to SP26 (`unicodedata` gives the normal form, the combining class and the general category),
and `matching(places, typed, rules)` equal to SP20); the rules from `search_app.json` and
`camera.json`. Saved places reach a 3X only through the user's own export (3.6), never by sync.

## 10. Tests and acceptance

### 10.1 JVM tests

**`:core:api`** (Round SP0): `SavedPlaceTest` (5.1).

**`:core:search`, `SavedPlacesTest`** (S; temporary folders, a direct executor `Executor { it.run()
}` unless the test is about the writer; made-up places only):

1. No file: empty, `showOnMap` true, `loadProblem` null, `readOnly` false.
2. Save Home: the file has the exact format, key order and values of 3.1; a new store on the same
   file gives an equal list; no `.tmp` left. The written text of a fixed list is byte-identical on
   every write.
3. **Atomic write:** with an old valid file and a garbage `saved-places.json.tmp` beside it, a new
   store reads the old file and deletes the tmp. With the folder made unwritable (a directory in
   place of the tmp file), a change still updates `places`, `writeProblem` becomes WRITE_FAILED, the
   old file is unchanged; after the folder is fixed, the next change writes and clears the problem.
   With a real single-thread executor and 200 quick changes, the file at the end equals the final
   list.
4. **Broken file:** a truncated JSON holding "Quokka Shop" at (42.06875, -80.09813) → BROKEN,
   `saved-places.broken-1.json` has the original bytes, the list is empty and writable, nothing
   thrown, the new file holds `"notice": "broken"` and `notice` is BROKEN; a new store on it still
   has `notice` BROKEN; after `dismissNotice()` the key is gone and a new store has none; a second
   broken start gives `broken-2`; `loadProblem.toString()` and every exception message seen contain
   neither "Quokka" nor "42.068". Each of: two homes, a duplicate id, id "x12", latitude 91, empty
   name, kind "garage", `format` missing → BROKEN. **Read as they are, not BROKEN:** an other place
   with a 60-character name, two other places whose names have the same key ("Mom's", "moms"), an
   other place named "Home", 120 other places, a home entry named "House" (read as "Home"); each
   survives a reload and a rewrite after an unrelated change.
5. **Newer file:** `beamline-saved-places/2` → NEWER, `notice` NEWER, the file's bytes unchanged
   after every write attempt, each returning `Refused(READ_ONLY)`; `dismissNotice()` clears `notice`
   and leaves the file as it is.
6. **Same place (SP55 rule 2):** saving as OTHER a search place within 50 m with the same folded
   name as a saved place's place name → ALREADY_SAVED; 60 m away → saved; same point, different
   name → saved; a map point (MAP_PIN) 20 m from a Home that was itself saved from a map point →
   saved, and `find` of it is null; Home and Work at the same point → both saved, and `find` of a
   search place there returns Home (the tie rule); two others with one place name at one point (only
   possible in a file read as it is, SP52) → the first by name key, then id;
   `find` by `savedId`, by the rule, none; `same` true and false for each of these.
7. **Names (SP26):** "" and "  " → NAME_EMPTY; "***" → NAME_NO_LETTERS; 41 characters →
   NAME_TOO_LONG, 40 fine; a family emoji of 7 code points counts 1 (so 40 of them are fine); "🏠"
   and then "🏡" → both saved (different keys); "Дом" saved, then typing "до" matches it; "家" saved;
   "Café" then "cafe" → NAME_TAKEN; "HOME", "work ", "Home!" → NAME_RESERVED; "moms" when "Mom's"
   exists → NAME_TAKEN; a name with a newline is stored without it; rename to the same name in other
   case → fine. `savedNameKey` gives the keys of SP26 for each of these.
8. **Rename:** id, kind, point kept; Home and Work refused (`IllegalArgumentException`).
9. **Change location (`move`):** id, kind, name kept; point, place name, subtitle, category from the
   new place; a MAP_PIN place gives its coordinates as place name and no subtitle; moving an OTHER
   onto another saved place → ALREADY_SAVED; onto a search place that is itself → fine; `save` of a
   place with source SAVED → `IllegalArgumentException` whose message holds no name.
10. **Replace Home:** `save(p2, HOME)` with a Home at p1 → exactly one HOME, at p2, with a new id;
    the old id `byId` null.
11. **Make home:** `makeKind(otherId, HOME)` with a Home → the old Home gone, the place is HOME named
    "Home" with its id, place name and point unchanged.
12. **Limit:** 100 OTHER, the 101st → LIMIT; Home still saved.
13. **Order:** Home, Work, then "Äpfel" (key "apfel"), "apple", "Mom's": by name key, then id.
14. **Matching (SP20):** places Home, Work, "Mom's", "Mom's House", "Momo Sushi", "Gym": "" → none;
    "h" → [Home (rank 1), "Mom's House" (rank 2, "h" starts "house")]; "home" → [Home];
    "mom" → ["Momo Sushi", "Mom's", "Mom's House"] (all rank 1, by key: "momo sushi" < "moms" <
    "moms house"); "moms" → ["Mom's" (0), "Mom's House" (1)]; "ho mo" → ["Mom's House"] (2);
    "xyz" → none; never more than 3; with "🏠" saved, typing "🏠" → ["🏠"].
15. **SavedRules:** `parse` of the copy; the copy equals `packbuilder/search/search_app.json`
    (`RulesAndVersionsTest`).
16. **Cross-language:** the committed fixture
    `android/core/search/src/test/resources/saved/saved-places-v1.json` (the three made-up places of
    3.1) decodes and re-encodes byte-identically; `packbuilder/search/tests/test_saved_places.py`
    loads the same fixture (path from the repo root), finds the same order and the same `matching`
    answers as item 14 on a second fixture, and the same `name_key` as `savedNameKey` for every name
    of item 7 (a shared list in a third fixture).
17. **Leaks (`SavedPlacesLeakTest`, PV3 item 2):** the failure paths of items 3-9 and an unknown id,
    run with "Quokka Shop" at (42.06875, -80.09813) placed "Millcreek Mall", with `System.out`,
    `System.err`, a root `java.util.logging` handler and a default uncaught-exception handler
    captured: none of "quokka", "millcreek", "42.068", "80.098" in anything captured, in any
    exception message (causes included) or in the `toString()` of any `SaveResult`, `LoadProblem` or
    `Place` the store returns.

**`:feature:search`** (S; the engine fixtures of `docs/architecture.md` 5.1):

1. GO_TO START with nothing saved: slots "Set home", "Set work" (no subtitle, no distance),
   `savedPlacesText` "None yet"; with Home set and a GPS context: "Home", the description, a
   distance; with a map-centre context: no distance; with a BROKEN or NEWER notice: `savedNotice`
   with its text, gone after `DismissSavedNotice`; ADD_STOP: only set slots, no "Saved places" row
   without other places; SAVE: none, `chooseOnMap` true, header texts of 2.1 for each target,
   `useMyPosition` true with a GPS context of accuracy 10 m, false at 100 m, without accuracy and
   with a map-centre context.
2. Taps: Home in GO_TO covered → `PlanTrip(place)` with source SAVED and the saved id; uncovered (no
   routing, and a point outside `level2Tiles`) → `ShowPlace`; ADD_STOP → `Picked(…, ADD_STOP)`,
   uncovered → `ShowPlace`; SET_START → `Picked(…, SET_START)`, except the row of the `destination`
   given to `open`, which reads "Already your destination" and returns nothing; a saved result row
   tap behaves the same; no recent added in any of them.
3. Empty Home in GO_TO → SAVE mode "Set home"; a result tap → the store has Home; the screen is back
   in GO_TO START with the slot set; no recent added. "Use my position" → Home at the fix, its place
   name the coordinates. `openToSave(Move(id))` → a pick moves it and `onEvent` returns `Closed`.
   `openToSave(NewPlace)` → a pick returns `SaveNew`. "Choose on the map" returns
   `PickOnMap(target)` and the screen is at GO_TO START (or done, after `openToSave`).
4. Typing: "home" → `savedResults` [Home] at once while SEARCHING, then above the engine rows; the
   engine row that is the saved Home is dropped; typing the Home's place name lists the engine row
   with note "Saved" (or "Exact address · Saved"); NO_SEARCH_DATA still lists matching saved rows;
   engine none + saved match → RESULTS; in SAVE mode `savedResults` stays empty and the engine row
   keeps its "Saved" note.
5. Recents: a recent within 50 m with the Home's place name is not listed; after Home is removed
   through `SavedPlacesFeature` the recent is gone from `RecentPlaces` too; after Home only changes
   location it is listed again.
6. Place sheet: unsaved → [DIRECTIONS, SAVE]; with a trip → [ADD_STOP, DIRECTIONS, SAVE]; outside →
   [OPEN_STATES, SAVE]; a search result that is the saved Home → EDIT, `savedText` "Saved as Home",
   `savedId`; a sheet from `home.toPlace()` → title "Home", `savedText` null, icon `saved_home`; a
   read-only store → no SAVE or EDIT; a dropped pin 20 m from Home → SAVE, no "Saved as". **Per
   phase (SP4):** `driving = true` with `tripActive` (GUIDING, REROUTING) → [ADD_STOP, DIRECTIONS],
   without (ARRIVED) → [DIRECTIONS], for an unsaved place and for the saved Home alike;
   `open(ADD_STOP, …, driving = true)` → `focusField` false, slots at the top; `driving = false` →
   `focusField` true.
7. `SavedPlacesFeature`: list rows and texts of 2.6 (with and without Home, Work, others, at the
   limit, read-only notice, broken notice with "OK", write-failure notice, `editLabel` "Edit
   {name}"); `openList(ADD_STOP)` and `openList(SET_START)` → `pickOnly`, no Set rows, no
   `editLabel`, `canAdd` false; menus per kind ("Change location", "Remove"); the save dialog
   (default names of SP17 for each source, every problem text, "Replace" with the replace line,
   chips); rename with its own "home" and "work" texts; each confirm text; removing a place removes
   its recents (SP30); row taps for each `pickFor`; the map pick (prompt texts per target, "No point
   chosen yet", a plain tap at zoom 17 → "Point on the map" and five-decimal coordinates, at zoom 14
   → "Zoom in to choose the exact spot." and no confirm, a POI tap at zoom 12 → its name and
   confirm on, a tap outside every pack box → "Add this state to choose a point here.", outside the
   routing tiles → note, confirm for each target → `PickEnded(place)`, cancel → `PickEnded(null)`);
   the switch writes `show_on_map`.
8. **Leaks (`SavedLeakTest`, PV3 item 2):** the failure paths of items 1-7 (a broken and a newer
   store, a failed write, every refused save and rename, a refused change of location, a cancelled
   pick, an unknown id) with the canary place, with the same captures as `SavedPlacesLeakTest`:
   none of "quokka", "millcreek", "42.068", "80.098" in anything captured, in any exception message
   or in the `toString()` of any `SearchOutput`, `SavedOutput` or `Place` returned.

**`:map:scene`** (M): no saved places → `scene.saved` empty; three places → three markers with the
kinds, `minZoom` 10, 10, 12 and `scale` 1.25 from the bundled rules; a trip whose destination is the
saved Home (`savedId`) → Home left out, Work kept; `savedBelowRoute` false in IDLE, PLANNING and
CHOOSING, true in GUIDING and REROUTING; `SavedMarkerRules.parse` of a test text; the camera.json
copy equals its source (the existing check covers the new section).

**`:nav:session`** (N): `TripStore` saves and loads a stop with source SAVED and a saved id.

**`:feature:drive`** (D): 5.7's test.

### 10.2 Static checks

`check_private_logs.py scan` (PV2 items 2 and 3) and `manifest` (PV4), run by S on its modules during
the round and by I on the whole app (checklist item 14). Each check is shown to fail once on a
scratch copy: a `Log.i` in `search/saved/`, a `require(ok) { "bad $name" }` in `search/saved/`, an
unmarked `Log.i("x", "install: $s")` in `:app`, a `Log.e("x", "failed", e)` and a
`Log.w("x", "${e.message}")` in `:app` even when marked, a marked `Log.i("x", "at ${p.lat}")` outside
the location-log guard, a manifest without the rules attribute; and `logcat` on a text holding
"142.136 ms" passes while one holding "at 42.1365" fails.

### 10.3 Emulator journey (I, `beamline_i`, after the round)

Set-up: test-extras build; the `us-pa` pack installed; Settings: Day map, miles; location granted
(`pm grant` fine and coarse); the emulator at the Erie Zoo, a public spot that is never saved:
`adb emu geo fix -80.07578 42.0971`; `adb logcat -c` (the emulator's own log, never the phone's).
Canary places (made up, at public spots, all in the PA pack: verified in
`data/search/pa-search.sqlite`): Home = Erie Maritime Museum (42.13646, -80.08718); Work = a map
point at Erie International Airport (42.08418, -80.17321); "Quokka Shop" = Millcreek Mall (42.06875,
-80.09813); the new location of step 12 = Erie Art Museum (42.13108, -80.08557).

| # | Action | Pass (observable) |
|---|---|---|
| 1 | Cold start; screenshot; tap "Search places or addresses" | the pill at the top with the Settings button beside it (screenshot); START shows "Set home", "Set work", "Saved places" / "None yet", then the shortcuts |
| 2 | "Set home"; type "erie maritime museum"; tap the museum | header "Set home" while searching; back at START: "Home", "Erie Maritime Museum, 100 East Front Street, Erie", a distance |
| 3 | "Set work" → "Choose on the map"; move the camera with `--ef lat 42.08418 --ef lon -80.17321 --ef zoom 17`; tap the screen centre; "Set work" | the SAVE screen lists "Choose on the map"; prompt "Tap the map at your work"; the my-location button is on screen above the bar; the bar shows "Point on the map" (or the airport's label) and "42.084…, -80.173…"; a PICKED pin; then the search START again with the row "Work" and "42.084…, -80.173…" (or the airport) as its description (SP25) |
| 4 | Search "millcreek mall"; tap it; "Save"; replace the name with "Quokka Shop"; "Save" | dialog "Save place" with "Other" selected and "Millcreek Mall" in the field; the sheet then reads "Saved as Quokka Shop" with "Edit" |
| 5 | Wait 2 s; capture the log (`logcat -d --pid`); `am force-stop`; start; open search | "Home", "Work", "Saved places" / "3 places"; the recents do not list "Millcreek Mall" (SP30); typing "quo" lists "Quokka Shop" first; typing "home" lists "Home" first |
| 6 | Clear the text; tap "Home" | no place sheet; the route sheet with the stop row "Home" and at least one card (PLANNING then CHOOSING) |
| 7 | On the route sheet: "Add stop" → "Saved places" → "Quokka Shop" | the search screen shows "Home" and "Work" rows, no "Set" rows; back on the route sheet the stops read "Quokka Shop", "Home"; new cards |
| 8 | Close the route sheet; with `--ef lat/lon --ef zoom 13`, the camera on the museum, then on the airport, then on the mall, a screenshot each; then (42.11, -80.13) at z11, screenshot; the mall at z12, screenshot | z13: the house at the museum, the briefcase at the airport, the star at the mall, each in the middle of its screenshot; z11: house and briefcase, no star (a 411 dp wide view at z11 spans about ±5.8 km, enough for both; at z13 it spans only ±1.5 km, so one view cannot hold two of them); z12: the star |
| 9 | Search "quo", tap "Quokka Shop" (route sheet), "Start" via "Simulate drive"; after 10 s open the stops editor → "Add stop", screenshot, Close; 20 s, screenshot, "End trip" | search opened for the stop shows "Home" and "Work" at the top with the keyboard down (SP4); the star is not drawn under the destination pin (SP43); in the tilted view a marker in view stands upright and never covers the route line |
| 10 | Tap the house marker | the sheet "Home" with "Directions" and "Edit" |
| 11 | Settings → "Saved places" → switch "Show on the map" off; back; screenshot; switch on | no markers while off; markers back when on |
| 12 | List: "Edit" on "Quokka Shop" → "Rename" → "Quokka Mall" → "Save"; "Edit" → "Change location" → search "erie art museum" → tap; "Edit" → "Make home" → "Make home"; "Edit" on Work → "Remove work" → "Remove" | the rename shows; the change shows "Erie Art Museum, 20 East 5th Street, Erie" on the list; the make-home dialog reads "Make Quokka Mall your home?", "Your current home (Erie Maritime Museum, 100 East Front Street, Erie) will be removed.", "It will be called Home."; at the end the rows are "Home" (Erie Art Museum…), "Set work", the hint line, "Add a place" |
| 13 | `run-as <app id> ls files no_backup`; `run-as <app id> cat no_backup/saved-places.json` into `data/r3-i/` and `python packbuilder/search/saved_places.py --check` on it | `saved-places.json`, `recents.json` (and `trip.json` if a trip was saved) only under `no_backup/`; the file passes and holds one place, the Home at (42.13108, -80.08557) |
| 14 | Settings → Night; screenshot at z13 | the house in the night colours, readable |
| 15 | Capture the log again; `check_private_logs.py logcat` over both captures; `check_private_logs.py manifest` on the merged manifest and the APK | no canary string; manifest and rules as PV4 |
| 16 | Variant: replace `no_backup/saved-places.json` with `{` (run-as, emulator only), cold start, open search; cold start again, open search; "OK"; cold start, open search | the notice of SP52 above "Set home" after the first and the second start; `saved-places.broken-1.json` exists with the `{`; after "OK" and a restart no notice |
| 17 | `uiautomator dump` of the map, the search START and the list (emulator); then TalkBack over steps 1, 2 and 12 where the emulator image has TalkBack, else on the phone in the test set | the pill's content description is "Search places or addresses"; each "Edit" is "Edit {name}"; the switch is "Show on the map" with its state; TalkBack reaches every row, "Edit" and dialog button and reads the problem lines |
| 18 | Phone only, in the test set (PV7), Gboard the phone's keyboard: tap the search field (step 1), the save dialog's name field (step 4) and the rename field (step 12); a screenshot each with the keyboard up, under `data/phone/private/` | Gboard shows its incognito mark on each of the three fields (PV8) |

**On the phone** (I, after the emulator): steps 1-8, 10, 11 and 18 only (and 17 when the emulator had no TalkBack), in the test data set (PV7, `--es private_data test`), screenshots under
`data/phone/private/`; no step 13, 15 or 16 (no `run-as`, no log greps of the owner's places).

Pass for the reviewer: the screenshots of steps 1, 8, 9, 11 and 14 looked at; the texts of steps 1-7
and 12 as listed (read with `device_ui.py texts` on the emulator); the outputs of steps 13, 15, 16
and 17.

### 10.4 Screenshots per track (emulator, day and night, made-up places)

S (from `:demo:search` or the app module's preview host S uses): the search START with empty and set
slots and with the BROKEN notice, the SAVE START with "Use my position", typed "home" with saved and
engine rows, the saved places screen with all row types (full and pick-only), a 40-character name
at font scale 1.0 and 1.5 (SP6), the save dialog (Other, Home with replace line), the Edit menu for
Home and for an other place, a confirm, the map pick bar before a tap, after a tap at zoom 17 and
after one at zoom 14; the three text fields focused, with the editor flags of PV8 (the incognito mark itself: 10.3 step 18, on the phone). M
(`:map:demo`): the three markers at z11 and z13 by day and night, a marker over the route line in
route choice and under it in the tilted guide view, Home and a star at one point (the house on top),
a marker tap reported with its pin id.

## 11. Left out on purpose

- **Export and import screens** (format ready, 3.6; a plain "Save a copy" only if the owner asks for
  it now, question 12.2), and moving saved places to a new phone (PV4).
- **Sharing** a place with other apps, receiving `geo:` links, and any sync or cloud copy.
- **Automations:** "arriving home" actions, departure reminders, commute or "usual time" guesses,
  starting guidance on its own. They need history the app does not keep.
- **Lists, folders, tags, notes, photos, custom icons or colours per place**, and manual reordering
  of the list (it is sorted by name).
- **Labels on the map markers** (the icon says Home or Work; the name shows in the sheet).
- **Saved routes or trips**, several homes, and a "Directions from Home" shortcut beyond SET_START.
- **Editing from a car screen**, launcher shortcuts ("Go home"), widgets and voice ("take me home").
- **Undo** for remove and replace (confirmation dialogs instead).
- **Reverse lookup** of an address for a map point (the round has no reverse geocoder; the point
  shows its coordinates).
- **Hiding the app's screen from the system's recent-apps thumbnail** (`FLAG_SECURE`): it would also
  block the owner's own screenshots.

## 12. Questions only the owner can answer

These are questions 10-13 of `docs/architecture.md` 6.38. The owner answered 1, 2 and 4 on 2026-11-16;
3 is still open (the icons stay drafts).

1. **Answered 2026-11-16: as built.** **Replacing Home or Work:** the old one is removed, after a confirmation that names it
   (recommended), or kept as an ordinary saved place under its own place name?
2. **Answered 2026-11-16: lost on a new phone for now; export comes in a later round.** **New phone, or
   a reinstall:** saved places stay on this phone and do not move with Android's
   phone-to-phone transfer. Until export exists they are lost on a new phone and on any uninstall,
   and an uninstall is forced when an update cannot install over the old app: today's builds are
   signed with the build PC's own debug key, so a build from another PC cannot update the installed
   app. Choose: (a) accept for now, with export and import in the next round, and the orchestrator signs
   every build with one kept key so updates never need an uninstall; or (b) also add a plain "Save a
   copy" row this round (3.6, about a day of work). Recommended: (a), because the kept key removes
   the forced uninstall and re-entering Home and Work takes a minute; (b) if you already have many
   named places.
3. **The look of the markers:** the three icons and the blue colours of section 8, on the contact
   sheet and the phone screenshots. They are filled squares where the POI icons are pale, so they
   read as "yours", and their blue is the route's blue (casing by day, fill by night): while
   guiding they sit under the route line, in route choice over it with a white edge. Approve, or
   change (recommended: approve; the other choice is an amber square, which no POI uses either)?
4. **Answered 2026-11-16: on by default.** **Markers on the map by default:** Home, Work and your places show as small markers unless you
   turn "Show on the map" off (Settings → Saved places). With them on, any screenshot or shared view
   of the map near home shows where Home is. Keep them on by default (recommended: yes, they make
   the places easy to tap), start with them off, or show Work and your places but never mark Home?
