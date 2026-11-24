# 31. Next run, batch O3: add a missing place, the friendlier editor, a new business in an old place, your changes on your map, View all

Status: **done, 2026-11-21** (key m). Batch O3 of docs/online-design.md (section 4.3 with 2, 3, 4.4-4.6, 6
and the owner's answers), docs/next-round.md items 24-27 and the O2 review's carry-overs. Version
**0.1.12 (versionCode 15)**. No new packs. Nothing is committed: the lead commits. Scratch, shots and logs
are in `data/r3-run2/o3/` (not in git); the working record is `data/r3-run2/PROGRESS.md`, section "Batch
O3". The batch was built by two engineers in turn (the first was stopped by the usage limit after shot 33;
the second rebuilt, fixed what the device showed and finished). Everything on the emulator went to OSM's
test server only, with the owner's throwaway test account ("the test account" below).

## Add a missing place (4.3)

- **Where.** A long press drops a pin as before; its sheet has **Add a missing place** beside **Report a
  problem here** (shot 28). Hidden when "Contribute to OpenStreetMap" is off (shot 58: Offline only, the
  pin has only Directions / Save) and while guiding (`PlaceSheetUi.canAddPlace`, set with `canReport`).
- **Signed out** (shot 55): "Sign in to add a place" with **Sign in to OpenStreetMap**, **Cancel** and
  **Send a note**; the note form opens at the pressed point with "Missing place: " chosen (shot 56).
- **The types** (`app/online/PlaceTypes.kt`, `PlaceTypes.ADDABLE`): the search's categories a driver can
  put one point on, each with OSM tags that satisfy its own match rule in packbuilder/search/categories.json,
  so the next pack files the new place under the same category (`PlaceTypesTest` checks every one against
  categories.json). Left out: the umbrellas (Food, Lodging); categories the search defines by a name,
  brand or detail (Truck stop, Welcome center, the state store, Urgent care, ER, Driver license center, Oil
  change, Inspection, Diesel, State game lands, State park); places OSM maps as areas (rest area, service
  plaza, parking, park, golf, beach, cemetery, school, college, hospital, mall, campground, RV park,
  airport); stations (the public transport scheme needs more than a point). The picker is the View all
  grid (shot 11: "What it is", On the road / For the car ..., icons).
- **The form** (shots 29, 13, 14): what it is (Change), the name, the hours editor below, phone and
  website checked as typed ("A phone number has 10 digits, like 814-555-0123, or starts with + and the
  country code.").
- **The spot check** (`app/online/Nearby.kt`, `SpotCheck`): the installed pack's places within 60 m at once
  (`SearchHub.poisNear`, the R*Tree), then OSM's `GET /api/0.6/map.json` of a box about 120 m across (no
  account needed) as soon as it arrives; offline the form says "No connection: checked with your map only.
  Beamline checks OpenStreetMap again before it goes." Rules, and why:
  - *Duplicates*: the same name (folded) within 50 m, or the same type and a similar name (one holds the
    other, or two letters apart) within 50 m; an unnamed type (restroom, viewpoint) the same type within
    25 m. 50 m is a downtown block front: a second "Sheetz" there is almost surely the same; a branch of a
    chain across town is not caught. Objects the user already answered about are left out.
  - *Already on the map here*: a business (a main key, or a `disused:` one) within 30 m or inside the same
    OSM building outline (point in polygon on the map answer's building ways). Several: the user picks
    which one the answer is about. None but an address-only node within 20 m or in the building: the new
    business's tags go onto that node.
- **Upload.** A summary first (shot 30: "A new pizza named "Beamline Test Pizza" at the point you pressed
  (not your position)", the hours, every tag, and "This will be uploaded to OpenStreetMap under your OSM
  name as one change with the comment "Added pizza Beamline Test Pizza". It will be public"), then one
  changeset (`comment`, `created_by=Beamline <version>`, `source=survey`) with one node at the pressed point,
  then the link (shot 31: ".../node/4365850255", Open it on OpenStreetMap).
- **Offline or failing**: it waits in the outbox like an edit (shots 38-40: summary, "Your new place waits
  in My reports and goes by itself when you're online and Beamline is open", My reports "Waiting to be
  sent") and went by itself when airplane mode was turned off with My reports open (shot 41: "Added · node
  4365850269"). Before an item that waited goes, the duplicate check runs again (one map request); a new
  duplicate makes it wait for the user (JVM: `OsmAddTest.aDuplicateAddedMeanwhileMakesItWaitForTheUser`).
- **The duplicate warning** "Is it already on the map?" with **Edit that one instead** (the edit form for
  it) and **It's a different place: add mine**: from the pack (shot 36: "SHE Vintage (Clothing store),
  about 22 m from here (on your map)") and from OSM (shot 37: the test server's Beamline Test Pizza, "about
  5 m from here (on OpenStreetMap)", which no pack has).

## A new business in an old place (item 25)

- "Already on the map here" (shot 35, four businesses near 419 State St; with one: "Already on the map
  here: Beamline Test Pizza (Pizza)") with **My place replaced it** (that object takes the new name, type
  and details; its own business tags go; address and building stay), **Both are here** (a new point) and
  **It's the same place** (the edit form). One must be picked before Next.
- The edit form has **This is now a different business** (shots 19, 22): the same swap.
- The swap (`BusinessTags` in PlaceTypes.kt). Goes: the main type keys and their `disused:`/`was:`/
  `abandoned:` forms, the names (`name`, `alt_/short_/official_/loc_/int_/old_name`, `name:*`), `brand*`,
  `operator*`, `network`, `cuisine*`, `opening_hours*`, `phone*`, `website`, `url`, `email`, `fax`,
  `contact:*`, social keys, `description`, `wikidata`/`wikipedia`, `takeaway`, `delivery`, `drive_through`,
  `drive_in`, `seating`, `payment:*`, `diet:*`, `fuel:*`, `service:*`, `healthcare:*`, `check_date*`,
  `survey:date`, `start_date`/`opening_date` and a few business details. Stays: everything else (`addr:*`,
  `building*`, `level`, `entrance`, `wheelchair`, `note`, `fixme`, `source` ...). Why: the OSM wiki's
  practice for a changed business ("Key:disused", "Lifecycle prefix", "Good practice: one feature, one
  OSM element") is to keep the object, which carries the place's history and address, and replace what
  belongs to the business; the keys that describe the shop rather than the place are the ones a new tenant
  does not inherit. The swap is made again at send time on the object as OSM has it then.
- Proved on the test server by the first engineer: the O2 test object n4365850188 (O2's closed "Primo
  Tailoring" copy) replaced by "Beamline Test Cafe" (shot 15, the summary: "Replaces Primo Tailoring: name,
  type, hours, phone, website change. Goes: disused:shop=clothes. Comes: amenity=cafe, name=..., opening_
  hours=Mo-Fr 07:00-15:00; PH off, phone=..., website=... Stays: note", comment "Replaced Primo Tailoring
  with coffee Beamline Test Cafe"); the node is v3 with exactly those tags (o3/net/replace-n4365850188.json).
- JVM: `PlaceTypesTest.aNewBusinessInAnOldPlaceSwapsTheBusinessTagsAndKeepsThePlace`,
  `anAddressAloneGetsTheBusinessTagsAndLosesNothing`, `alreadyHereAndAnAddressAlone`,
  `theMapAnswerAndTheBuildingOutline`; `OsmAddTest.aReplacementIsMadeAgainFromThePlaceAsItIsAtSendTime`.

## The friendlier editor (item 24)

One form serves Edit on OpenStreetMap and Add a missing place (`app/online/FormFields.kt`, `FormViews.kt`,
`HoursDraft.kt`).
- **Times are picked, never typed**: each time is a button ("8:00 AM") that opens the Material 3 time
  picker, dial with its keyboard mode ("Type the time"), in the phone's 12/24-hour setting (shot 12: "Pick
  a time 09:00 AM/PM"). There is no text field for a time, so the old ':' problem cannot happen; OSM's
  24-hour text is written behind the scenes.
- **Day groups** (shots 19, 20, 13): a row of day chips per group, one or more time ranges ("+ Add a
  second time", "Remove this time"), "Open 24 hours", "Remove these days", "+ Add more days", and "Closed:
  Sat, Sun" for days in no group. Existing hours open grouped the same way (Mo-Th 15:00-22:00 is one group
  of four days). A closing after midnight shows "2:00 AM (next day)" (shot 20).
- **The preview** at the top, "How Beamline will show the hours", is the place sheet's own week view
  (`HoursPreview` = feature/search's HoursTexts + HoursWeek), so it is exactly what the sheet shows
  (shot 22: "Mon 8 AM-9 PM, 10 PM-2 AM ... Sun Open 24 hours").
- **Public holidays**: Not sure (leave out) (default) / Closed (`PH off`) / Same as usual / Other hours
  (shots 21, 14). "Same as usual" writes no PH rule (the OSM wiki: none is needed when holidays are as
  usual), so on reopening it reads "Not sure". Existing PH rules are kept and shown.
- **Words on every control**, large targets, screen-reader labels ("Saturday, not in these days"); each
  changed field shows "Changed" with its own **Undo** (shot 22); **Discard your changes?** on leaving
  (shot 23); the fold-out **As OpenStreetMap stores it** with the exact text, read-only (shots 21, 13).
- The writer checks its text by reading it back with the hours parser; anything the editor cannot express
  stays read-only and is kept (`HoursDraftTest.whatTheEditorCannotExpressStaysReadOnly`).

## Your changes on your map at once (item 26)

`app/online/OwnChanges.kt` (the rules), `app/map/OwnLayer.kt` (the map), feature/search `OwnPlaceUi`.
- **Which**: every SENT addition, edit, replacement and closing of the outbox (O2's edits included); edits
  of one object taken together, oldest first.
- **The map**: a GeoJSON source and a copy of the style's own `pois` layer (font, size, colours, halo, day
  and night) are put into the style JSON before it loads; each point carries the category's sprite as its
  icon and the type's main OSM value (`fast_food`) as its kind, which the style colours labels by. The old
  label of a renamed, replaced or closed place is hidden by a filter on the map's POI layers on the tiles'
  feature id: the Protomaps tiles carry `(type << 44) | OSM id` (checked with o3/tile_ids.py on the PA
  pack: Subway n7570266250 = 17599756310666), so the hiding is exact, with no guess by name or area
  (`within` would also hide a neighbour of the same name). Shots 49 (day) and 50 (night): "Subway Downtown"
  (renamed) with the deli icon and the orange food label, the tiles' "Subway" gone; "Beamline Test Cafe"
  (replaced) and "Beamline Test Pizza" (added) drawn the same way as Tsaocaa beside them; shot 45 also shows
  "Beamline Test Ice Cream" in Perry Square.
- **Search** finds the new name and type and not the pack's old entry (shot 27, 42/47: "Subway Downtown ·
  Sandwiches and hoagies" first, no "Subway, 425 State Street"; "Beamline Test" finds the pizza, the ice
  cream and the cafe).
- **The sheet** shows the user's values as the place's, with one small tag **Your edit · waiting for the
  map update** (shots 25, 44, 46: the new phone "(814) 456-2701").
- **A closed place** is hidden on the map (as the next pack will be: the pack builder matches `amenity=`,
  not `disused:amenity=`), shown in search with "Closed permanently" and on its sheet (shots 51-53).
- **Routing** to an added place works (shot 45: Directions to Beamline Test Pizza, "< 1 min 0.1 mi via
  State St").
- **Stepping aside**: when an installed pack covering the point has an OSM snapshot of a later day (UTC)
  than the day the change went, that pack has the change (or the place changed since): the overlay steps
  aside for it (`PlaceTypesTest.theOverlayStepsAsideForAPackMadeAfterIt`). Same day: still shown.
- Kept with the other own edits in the outbox file (no_backup, PV1-PV8, never logged); nothing more is sent.
- Fixed in the continuation (seen on the device, shots 25, 43, 48): a renamed place kept the generic
  building icon, because an edit's stored `base` holds only the changed keys and a sheet opened from an own
  search row recorded the generic icon. Now each edit stores the type the form saw (`EditData.typeCode`,
  outbox JSON `"type"`), a recorded generic icon is ignored, and the label's kind is the type's main OSM
  value (a "deli" kind had given a grey label).

## "View all" place types (item 27)

The search screen's chip row ends with **View all** (shot 03); it opens "All place types" (shots 01 day,
04 night, 05 landscape with 7 columns): every category of the installed pack's `category` table, with the
map's and the result rows' icon, under plain headings per group (On the road, For the car, Food and drink,
...). A tap runs that category's search as a chip does (shot 02: "car wash", ten car washes). The same
component (`TypeGrid.kt`, `PlaceTypeGrid`) is the type picker of Add a missing place and of "This is now a
different business", with the types that cannot be added left out.

## The O2 review's carry-overs

- A redirect whose state does not match is ignored and no longer ends the pending sign-in
  (`SignIn.answers`; `OsmAddTest.aRedirectOfAnotherSignInIsIgnoredAndLeavesTheOpenOneOpen`).
- The summaries say "This will be uploaded ..." before the upload (shots 15, 30, 38).
- After sign-out (shot 54) the test build asks the test server for the user's details with the old token:
  logcat "the revoked token is answered 401". JVM: `OsmAddTest.theRevokeRequest`.
- `WeekHoursTest.overnightAndSundayIntoMonday`.
- New `Log` lines carry `@SuppressLint("LogNotTimber")` as the project's rule asks; lintRelease has no
  warning on a line new in O3 (55 warnings in all, O2: 60).

## The network rule

`OsmHttp` gains the map request (the box of the pressed point grown by about 60 m) and the node creation
(architecture-downloads.md 4.1 updated). "Offline only": with a polled baseline (`dumpsys netstats --poll`)
a session of search, own places, sheets and a long press: uid 10153 0 bytes on every tag (o3/net/
offline3-result.txt, PASS); the section 8 drive under the same accounting: 0 bytes on every tag
(o3/net/offline4-result.txt, PASS). A first try without
the poll counted 33 KB on the OSM tag that belonged to the sign-out, revoke check and test-object deletes
made just before the baseline (they were not yet in netstats' history); a poll before and after removes it.

## Test objects on the test server

Made in O3: n4365850255 (Beamline Test Pizza, 5th and State) and n4365850269 (Beamline Test Ice Cream,
Perry Square), both deleted again at the end (the API answers 410). O2's n4365850187 (the Subway copy, now
"Subway Downtown", closed) and n4365850188 (now "Beamline Test Cafe") stay; both carry the note "Beamline
development test object on the OSM test server, please ignore".

## On the emulator (beamline_m, PA pack, tx build; `o3/shots/`)

01-05 View all (day, from the grid, chips at night, grid at night, landscape); 06 Offline only pin; 08-09
map and pin sheet; 10/35 Already on the map here; 11 type picker; 12 time picker 12-hour; 13 hours and PH
Closed with the fold-out; 14 phone check; 15-16 replacement summary and upload; 17 replaced place on the
map; 19-23 editor (top, split day and next day, Open 24 hours with holidays and fold-out, preview with
Changed / Undo, discard question); 24-27 rename summary, map, search; 28-31 pin with both actions, add form,
summary, link; 32-33 added place day / night (first build: Subway still with the generic icon); 36-37
duplicate warnings; 38-41 offline addition sent later; 42-48 icon fix steps; 49-50 own layer day / night;
51-53 closed place; 54-56 signed out; 57-58 Offline only. Shots 08-10, 16-18, 24, 32 were taken and looked
at by the first engineer and were not reopened in the continuation.

## Checks

- Whole JVM suite `BEAMLINE_TESTS_STRICT=1 sh tools/round/gradle.sh m test --continue
  -Pbeamline.routingPacksWork=<abs>/data/gradle/m/routing-packs-test`: BUILD SUCCESSFUL; result XMLs 841,
  0 failures, 0 errors, 1 skipped (O2: 810). New: HoursDraftTest 8, PlaceTypesTest 9, OsmAddTest 8,
  feature/search OwnPlacesTest 5, WeekHoursTest +1.
- `check_contracts` all the same (44 frozen unchanged); `check_private_logs scan` 0 breaches, selftest ok;
  `check_network_rule scan` 0, `privacy` 0; `sync_data.py --check` exit 0; no changed or new file holds
  the test account's name or password (script check).
- `sh tools/round/gradle.sh m :app:assembleDebug :app:assembleRelease :app:lintRelease`: pass, lint 0
  errors, 55 warnings, none on a line new in O3 (o3/final-build.log).
- Phone APK `data/android/apks/batchO3/beamline-release.apk`: 85,472,457 B, SHA-256
  065ee8e5a3fcfe4358bb81fd18f94a8f9b09d4858e176e3696053847b392b731 (BuildConfig: server live, the live
  client id, TEST_EXTRAS false).
- Section 8 clean drive on the final tx build in Offline only: banners 222.0 / 251.5 s, ARRIVED 289.7 s
  (o3/drive2.log); again with polled netstats before and after: ARRIVED 288.9 s, 0 bytes (o3/drive3.log).

## Contract changes

docs/architecture.md: feature/search's quoted API (`PlaceTypeUi`, `PlaceTypeGroupUi`, `OwnPlaceUi`,
`SearchUi.allTypes`, `SearchEvent.ViewAll / TypeTapped / CloseViewAll`, `PlaceAction.ADD_PLACE`,
`PlaceSheetUi.ownTag` and `canAddPlace`, `SearchFeature.ownPlaces / poisNear / placeTypes`), decision 6.91;
docs/architecture-downloads.md 4.1 (OsmHttp: adding a place, the map request's box, the add form's reading);
docs/architecture-saved-places.md (the outbox file holds additions and replacements shown until a pack has
them); docs/online-design.md (status, "Built in O3" under 4.3).

## Not verified

- Nothing on the owner's phone or on openstreetmap.org; the plain release's live sign-in is not exercised.
- TalkBack was not run (labels are set in code, read in the UI dumps).
- On the device, not shown: "It's the same place" opening the edit form, an address-only node getting the
  tags, a new duplicate at send time making an item wait, the overlay stepping aside after a newer pack
  (JVM tests cover each).
- Judgment calls to look at: a monument counts as "Already on the map here" (the Korean War Memorial in
  Perry Square: `historic` is a main key); the summary shows the hours as OSM text ("Mo-Su 09:00-17:00"),
  not in plain words; "Changed" is styled like a link next to Undo.
