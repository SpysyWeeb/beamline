# Online as a choice: design (items 7 and 21)

Status: **agreed with the owner, 2026-11-20 (answers in section 8). Batch O1 is built (batch H,
2026-11-20, version 0.1.10; docs/experiments/29-batch-h.md); batch O2 too (2026-11-20, version
0.1.11; docs/experiments/30-batch-o2.md); O3 too (2026-11-20, version 0.1.12;
docs/experiments/31-batch-o3.md); O4 is not.**
Written by the lead from docs/next-round.md items 6, 7 and 21 and the owner's answers.
0.1.41 (docs/next-round.md item 53 a, c, d, e, g): 4.2's source rule changed by the owner; 4.5 gains changes checked
tag by tag, Undo, Were you there? and the confirmation of a place's hours; 4.8 asks about a place where the user is.

## 1. What this is

Beamline stays offline-first. Maps, routing, search, saved places and guidance keep working with no
connection, as they do today. On top of that the app may offer **online features that the user
chooses to switch on**. Each one says what it sends and to whom before it first sends anything.

The online features this design covers:

| Feature | What it does | Service | Account |
|---|---|---|---|
| **Report a map problem** (item 21) | Leaves a public OSM note at a point: "closed", "wrong hours", "no left turn here" | openstreetmap.org | none needed |
| **Fix a place's details** (item 21) | Changes a place's hours, phone, website or name in OSM | openstreetmap.org | the user's OSM login |
| **Add a missing place** (item 21) | Adds a new point to OSM with its type, name and details | openstreetmap.org | the user's OSM login |
| **Find places online** (item 7) | Finds an address or place whose state is not on the phone (the gap of item 6) | an address-lookup service (section 5) | none |
| **Check for app updates** (item 41, 0.1.26) | Once a day when the app opens, fetches the app's `update.json` from its GitHub releases; downloads a newer APK on Wi-Fi (or on a tap) and installs it when not guiding | the app's releases on GitHub (`PackHttp`) | none |
| **Check for map updates** (item 40, 0.1.24) | Once a day when the app opens, fetches the list of states (a few kilobytes, never a pack) and puts a chip on the map when an installed state has newer map data | the packs' catalog on GitHub (`PackHttp`) | none |

Not in this design: online routing through areas that are not installed, traffic and closures.
They fit the same switch and the same network rule later; each gets its own design when its turn
comes.

## 2. The switch

As the owner set it (2026-11-16):

- **On first run**, after the notice, one screen with two equal choices. Neither is pre-selected.
  - **Offline only**: "Beamline makes no internet requests except the state downloads you start."
  - **Allow online features**: "You can report map problems and fix places on OpenStreetMap.
    Beamline sends only what each feature needs, when you use it, and tells you what that is
    first." (Batch O2, the owner's answers after batch H: no mention of finding places online until
    batch O4 brings it; it comes back into this text then.)
- **In Settings**, a section headed "Internet" (0.1.40, P55 / F88: the heading no longer repeats the main switch's name): the main switch, and under it one switch per
  feature ("Contribute to OpenStreetMap", later "Find places online"), and the OSM account line
  ("Sign in to OpenStreetMap" / "Signed in as … · Sign out"). Turning the main switch on (on first
  run or in Settings) turns on every online feature: those that only work online and those that
  work better online (the owner, 2026-11-20). Each can then be turned off on its own.
- **Updating from an earlier version** shows the first-run choice once.
- **"Offline only" is a promise.** With it, the app makes no request beyond the pack downloads the
  user starts, and a test proves it (section 7).
- **While guiding** none of the online actions are offered: no report, no edit, no add. The buttons come back when
  the trip ends. At the end of a trip, arrived and parked at the destination, the arrival card may ask one question
  about the place (0.1.41, 4.8).

## 3. What leaves the phone, and to whom

| Feature | Sent | To | Never sent |
|---|---|---|---|
| Report a problem | the note's text, the point the user chose (not the phone's position), the app name | openstreetmap.org | anything else |
| Fix details | the place's OSM id, its changed tags, a short comment ("Updated opening hours"), the app name, under the user's OSM name; for an edit made at the place (0.1.41, 4.5), the changeset tag `source=survey`, which tells everyone the user was there at about that time | openstreetmap.org | the phone's position |
| Confirm the hours (4.8) | the place's OSM id and one tag, `check_date:opening_hours` with the day; the comment "Confirmed the opening hours of <its name on the map>"; the app name, under the user's OSM name (`source=survey` when made at the place, 4.2) | openstreetmap.org | the phone's position; a saved place's own name |
| Add a place | the new point (where the user put it), its tags, a comment, the app name, under the user's OSM name; for an edit made at the place (0.1.41, 4.5), the changeset tag `source=survey`, which tells everyone the user was there at about that time | openstreetmap.org | the phone's position |
| Find places online | the typed text, and the state or a rough area (rounded to about 10 km) so the answer is local | the lookup service | the exact position, saved places |
| All of them | | | saved places, Home and Work, recents, drive logs, the GPS track, any device identifier |

Before the first send of each kind, the app shows that line in plain words, with "Send" and "Not
now". **What a user adds to OSM is public**: everyone can see the change, the OSM username, the
point and the time. The app says so the first time, in those words. A note posted without a login
is public too, without a name.

## 4. Contributing to OpenStreetMap (item 21)

### 4.1 Report a map problem

- **Where:**
  - a long press on the map gives "Report a problem here" (built in O1 as a button on the dropped
    pin's sheet that the long press opens);
  - a place sheet has "Report a problem" at its details height.
- **How:**
  1. A short form opens with quick choices ("Closed", "Wrong hours", "Wrong location", "Road
     missing", "Turn not allowed") and a text box.
  2. The note is placed at the pressed point, or at the place's point.
  3. "Send" posts it as an OSM note. Local mappers see it on the map and in their editors.
  4. Every note ends with a line "via Beamline" (batch O1's decision, as StreetComplete and Organic
     Maps do): a note without a name has no other sign of where it came from, and it tells mappers
     the user looked at an offline map that may be weeks old. No version, nothing about the phone.
     The form says so before Send.
- **Account:** OSM accepts notes without one today.
  - **Signed in:** the form shows a box "Post with my OSM name", checked to start with, so mappers
    can ask the user about it.
  - **Not signed in, or the box unchecked:** the note goes without a name.
  - If OSM stops accepting notes without an account, the app asks the user to sign in.
- **Offline:** the note waits in an **outbox** (section 4.5) and goes out by itself when the
  connection is back and the app is open.
- **A closed business** is an edit, not a note, when the user is signed in: "Closed permanently"
  in section 4.2. Not signed in, the app offers to sign in, or to send it as a note instead.

### 4.2 Fix a place's details

- **Where:** "Edit on OpenStreetMap" at the details height of a place sheet (batch G). It is offered
  only for places that come from OSM: the search packs keep each place's OSM type and id
  (`poi.osm`, for example `n25479855`), so no new pack build is needed.
  *0.1.41 (4.8):* at the details height, while the user stands at the place, "Hours still right?" confirms the hours
  in one tap ("Yes") or opens this form at its hours ("Edit hours"); the arrival card can open it at the hours, the
  phone or the website.
- **The form:**
  - **Name.**
  - **Opening hours:** a simple week editor that writes valid OSM hours ("Mo-Fr 08:00-18:00;
    Sa 08:00-13:00"), checked with the hours parser the app already uses.
  - **Phone** and **website**.
  - **"Closed permanently"**: follows OSM's documented way of marking a closed place. The record
    stays on the map as a closed place, so mappers see what was there; its hours, phone and website
    are removed. The summary says so before the upload. *Built in O2* (OSM wiki Key:disused: and
    Lifecycle_prefix): each main key the place has (`amenity`, `shop`, `craft`, `office`, `leisure`,
    `tourism`, `healthcare`, `club`) moves under the `disused:` prefix with its value
    (`shop=bakery` becomes `disused:shop=bakery`); `opening_hours`, `phone`, `contact:phone`,
    `website` and `contact:website` are removed; `name` and every other tag stay ("the name is still
    relevant", Key:disused:). Offered only for a place with one of those keys, and only after the
    place was read from OSM.
  - Anything the form cannot express (holiday rules, seasonal hours) is shown read-only, with a line
    saying it can be changed on openstreetmap.org.
  - **"Paste hours"** (0.1.19, docs/next-round.md item 34): the hours as the business writes them
    ("Mon-Fri 9:30am-6pm, Sat 9-5:30, Sun closed", a sign's lines, "Weekdays 8 to 5", "Open 24
    hours") are read into the editor (`HoursText`), which then shows the week for the user to check.
    An AM or PM that was not written is worked out ("9-6pm" opens at 9 AM); where neither time says,
    the form adds "Check AM and PM". A text it does not wholly understand changes nothing; that
    includes days written after their times, a day named twice and days with no times. The
    clipboard is read only on the tap of "Paste from clipboard".
  - **The phone number's form** (0.1.21, docs/next-round.md item 38): OpenStreetMap asks for the
    international form with the country code ("+1-814-555-0123"; the wiki's Key:phone). A number
    typed any usual American way ("(814) 555-0123", "8145550123", "1 814 555 0123") is written in
    that form (`Checks.phoneText`), and the form says so under the field ("Saved as
    +1-814-555-0123"). A number that starts with + and another country's code, or that cannot be a
    North American number, stays as typed. While the user does not touch the phone field, what
    the place already had is not rewritten; once they change it, every number in the field is
    written in that form.
  - **A second set of hours** (0.1.19, item 33): "+ Office, drive-through or kitchen hours" adds the office's, the
    drive-through's or the kitchen's hours beside the place's own (`opening_hours:office`,
    `opening_hours:drive_through`, `opening_hours:kitchen`, as OSM's wiki documents them). One such
    set a place; the one OSM already has is shown and keeps its kind; emptied, its tag is removed.
    The place sheet still shows the place's own hours only (the packs carry no second set).
- **Before upload:**
  1. The app fetches the place's current version from OSM, so it edits what is there now, not what
     the pack had.
  2. If someone changed the place since the pack was built, the form shows the current values.
  3. Then a short summary: "Hours: Mo-Fr 08:00-18:00 → Mo-Fr 08:00-17:00". "Upload" sends it.
- **The upload:** one changeset per edit, with a comment and `created_by=Beamline <version>`. An edit made at the place
  also carries `source=survey`; any other edit carries no source (the owner, 2026-11-28, changing the decision of
  2026-11-21; 4.5 "Were you there?"). If OSM refuses because the place changed in the meantime, the app reads it again
  and checks the edit tag by tag (4.5).
- **After:** a link to the change on openstreetmap.org, where it can be seen or reverted.

### 4.3 Add a missing place

- **Where:** a long press on the map gives "Add a place here".
- **The form:** a type from the app's categories ("Restaurant", "Gas station", "Waterfall", ...),
  a name, and optional hours, phone and website.
- **Before upload:** if the search pack already has a place of that name within about 50 m, the app
  says so and offers to edit that one instead. The summary is shown, then "Upload".
- **Only a point.** No buildings, roads or areas.
- *Built in O3* (docs/experiments/31-batch-o3.md): the dropped pin's sheet has "Add a missing place"
  beside "Report a problem here" (the owner's words, docs/next-round.md item 25). Signed out: sign in, or
  a note "Missing place: ...". The types are the search's categories a driver can put a point on
  (`PlaceTypes.ADDABLE`, chosen in the "View all" grid; not the umbrellas, not categories defined by a
  name or brand, not areas or stations). When the form opens it checks the spot: the pack at once and
  OSM's map of a box about 120 m across as soon as it arrives. A business within about 30 m or in the
  same building gives "Already on the map here: <name> (<type>)" with "My place replaced it" (the
  existing object becomes the new business: its own tags go, address and building stay, as OSM usually
  does it), "Both are here" and "It's the same place" (the edit form); an address alone gets the new
  tags. The duplicate check (same name within 50 m, same type and a similar name within 50 m, an
  unnamed type within 25 m) offers "Edit that one instead". The summary, then one changeset ("Added
  <type> <name>", `created_by`) with one node at the pressed point and its link; the
  outbox runs the duplicate check again before an item that waited goes. The edit form has "This is now
  a different business" with the same tag swap, and is the friendlier form of item 24 for both.

### 4.4 Login

- **Users sign in and out of their own OSM accounts themselves**: in Settings, or when they start
  something that needs an account (an edit, a new place). Notes never require it.
- OSM's standard sign-in (OAuth 2.0 with PKCE) in a browser tab. The app never sees the password.
- **Permissions asked:**
  - reading the user's name, to show "Signed in as …";
  - writing map edits;
  - writing notes.
- The access token is kept encrypted on the phone (Android Keystore) and never logged.
  "Sign out" deletes it.
- OSM requires every app to be registered once, by whoever publishes it. This is separate from
  users signing in. The owner registers Beamline under the owner's own OSM account when batch O2
  needs it, with the lead walking through the steps. The registration gives the app an id; no
  secret is kept in the app.
- *Built in O2:* the owner registered Beamline on openstreetmap.org and on the test server (public
  clients, redirect `io.github.spysyweeb.beamline://oauth`, scopes `read_prefs write_api
  write_notes`); the build carries the test server's client id in debug and test builds and the
  live one only in the plain release. The sign-in page opens in the browser; the redirect is
  accepted only for the sign-in the app started (the PKCE state, once, within 15 minutes). The token
  and the name are sealed with a key of the Android Keystore in `no_backup/osm-account.bin`;
  sign-out deletes the file and asks OSM to revoke the token when the switch is on.

### 4.5 The outbox and showing your own edits

- **The outbox:** notes, edits and new places wait there until sent. Each one can be opened,
  changed or deleted until it goes. Nothing in it is sent without the user's "Send" or "Upload"
  for that item.
- **Sending later:** a confirmed item that could not go out goes by itself when the connection is
  back and the app is open. There is no background service.
- **Changed meanwhile, tag by tag** (0.1.41, item 53 a): each tag an edit changes keeps the value it had when the
  edit was made. When the edit goes, the place is read again and the edit goes onto its newest version, unless one
  of the edit's own tags now holds a value that is neither that one nor the edit's (a tag the edit adds or removes
  counts), a place mapped as a point was moved more than 50 m since the form read it, or the place was deleted: only
  then does it wait for the user ("Changed on OpenStreetMap meanwhile: open it to look again"; "Deleted from
  OpenStreetMap meanwhile"). The tags the edit does not touch keep what others gave them; when OSM already has every
  value, nothing is uploaded; a check date never conflicts and a later one stays. This holds for every edit:
  details, Closed permanently, a different business (made again on the place as it is then), a speed limit and a
  confirmation of hours. A new place has nothing to rebase. A closing or a different business goes only while the
  place is still the same kind of place (its type tags as the form read them), and a closing also removes hours,
  phone and website added meanwhile; `phone` and `contact:phone` (and the two website keys) count as one detail; an
  hours change or removal also removes the hours' old check date.
- **Undo** (item 53 d): for 12 hours after an edit went, My reports offers "Undo" on it. The question lists what
  goes back; its "Undo" sends the reverse through the outbox as a new changeset ("Undid my change to <name>", no
  source). A tag someone changed since stays as they made it; when that is every tag, nothing is uploaded and the
  row says so. Before it goes, an edit (an undo too) can be deleted. A closing or a different business is undone
  only while the place is still as the edit left it; otherwise nothing is uploaded.
- **Were you there?** (item 53 e): while "Contribute to OpenStreetMap" is on, the app keeps where the phone was in
  the last 10 minutes, from the positions it already reads for the map and the trip; in memory only: never written
  to a file or a backup, never sent, never logged, gone when the app ends or the switch goes off; each position leaves
  it at 10 minutes, also with the app in the background. At Upload, an edit
  or a new place with none of them within about 80 m of its place asks "Are you sure you checked this on-site?"
  (Yes / Cancel; Yes sends it without `source`); one with such a position is "made at the place" and its changeset
  carries `source=survey`. That yes or no is kept with the outbox item, never a position, so an edit sent later
  keeps it. Before the first such edit the app says what the tag tells: that the user was there at about that time.
  An edit opened from a question at the place counts as made there; an item looked at again keeps it only when it
  goes with the values it had.
- **"Still right?"** (item 53 c): confirming a place's hours is an edit that writes only
  `check_date:opening_hours=<today>` (wiki Key:check_date) and goes only while OSM still has the hours the user saw;
  its changeset says "Confirmed the opening hours of <name>". It is one tap with no summary, because the question
  shows the hours it confirms; the first-time notices and the on-site question come as for any edit, and Undo works
  as for any edit. Asked again once the user's own check is a year old. Where and when it is asked: 4.8.
- **Seeing your edits:** OSM data reaches Beamline only with the next state pack, so the app keeps
  the user's sent edits locally and shows them on the place sheets, marked "Your edit, waiting for
  the next map update". A pack that already contains the change replaces the local copy.
  *Built in O3* (docs/next-round.md item 26): every sent addition, edit, replacement and closing shows
  as if real until an installed pack covering it has OSM data of a later day: on the map an own layer
  draws it with the map's own icon and label style and hides the old label by the tiles' feature id;
  search finds the new name and type and not the old entry; the sheet shows the user's values with one
  tag "Your edit · waiting for the map update"; a closed place is hidden on the map and shows "Closed
  permanently" in search and on its sheet; an added place can be routed to.
- **Privacy:** the outbox and the local edits live with the saved places (`no_backup`, never logged,
  never in a backup), under the same rules PV1 to PV8. Since 0.1.41 `no_backup/arrival-asked.json` keeps the OSM
  objects of the places where the user answered the arrival question, with the day, for 90 days (4.8), under the
  same rules, and is deleted when Contribute or online features go off.

### 4.6 Testing without touching the real map

OSM runs a separate test server for editors. Every development and test build of Beamline talks to
that server. Only the owner's own release build talks to openstreetmap.org, and only after the
owner turns the feature on. The emulator tests post notes and edits to the test server and check
the answers.

### 4.7 The speed limit of a road (0.1.19, docs/next-round.md item 32)

The one fix of road data the app makes itself. Every other problem with a road stays a note (4.1): a
wrong one-way or a closed road is not a thing to change from a phone.

- **What it changes:** the `maxspeed` tag of one way that exists ("45 mph", OSM's form in the United
  States). Nothing is created, moved or deleted. One way is one stretch of a road, often a few
  blocks; the summary says how long the stretch is.
- **Which road:** OSM's own map of the spot is read (a box 250 m each way), and the road is the way of
  a class that carries a posted limit (motorway to residential and their links; not service roads,
  tracks or paths) whose line passes within 30 m of the point. When the app knows which road the
  driver was on, a way of that name comes before a nearer one of another name (a side street at a
  corner). No such road: the screen says so and nothing is sent.
- **Standing still:** the report form of a point on the map (a long press, "Report a problem here")
  has "Fix the speed limit of this road". The screen shows the road and its limit on OSM now, the
  posted limits as large buttons (15 to 70 mph), then the summary ("Speed limit: 35 mph → 45 mph")
  and Upload. It needs the account, as every edit does.
- **While driving:** with "Contribute to OpenStreetMap" on, the speed sign and the speed beside it
  are one button. A tap opens the limits as large buttons; one more tap keeps the choice in My
  reports with the position and the road's name ("Picked while driving: open it to check the road
  and send it"). **Nothing is read from or sent to OSM while driving.** Opened later, it is the same
  screen with the limit already chosen; the road is read then, and only the summary's Upload sends.
- **What leaves the phone:** when the fix is opened, the box around the point goes to OSM's map
  call (for a pick made while driving that is where the phone was, which the user chose to report);
  on Upload, the way's new tag in one changeset with the comment "Set the speed limit of <road> to
  45 mph" and `created_by`. The changeset is public and shows which road the account edited and
  when. `source=survey` as 4.2 says: a limit picked while driving was picked at the road, so it counts as made there.
- **After:** the edit goes out and is checked tag by tag exactly as a place's edit is (4.5), and can be undone for 12
  hours. It is not one of "your edits" on the map: a road's limit is no place, so nothing is
  drawn or found for it, and the app's own speed sign changes with the next state pack.

### 4.8 At a place: "Hours still right?" and one question on arrival (0.1.41, docs/next-round.md items 53c and 53g)

Beamline asks about a place only where the user can see it, and only what the map lacks or what the user has not
checked for a year. Everything that changes OSM goes through the outbox as any edit (4.5) and through 53e's
questions before an upload (4.2).

- **AP1 Two places, nothing else.** The place window's details height (the user standing at the place) and the
  arrival card (arrived and parked at the destination). No notification, sound or dialog of its own. Both need
  online features and "Contribute to OpenStreetMap" on (`osmAllowed`) and an OSM account signed in. Neither shows
  while a trip is guided (GUIDING, REROUTING); the window's line also not in ARRIVED (the card has the question).
- **AP2 At the place.** 53e's memory (`RecentPositions.near`: a fix of accuracy 50 m or better within 80 m of the
  place's own point in the map pack, else the window's): for the window within the last 2 minutes ("standing near it
  now"), for the arrival within the last 10 minutes (53e's own window: the car is there; a garage may have lost the
  GPS). Both are inside 53e's test for "made at the place", so a tap there never meets "Are you sure you checked
  this on-site?" (except in the one second a last fix can age out). No location permission, approximate location
  only, a simulated drive (its positions never reach the memory): never at the place.
- **AP3 Arrived and parked.** The trip is ARRIVED, the guidance is not simulated, and `Vehicle.motion` has been
  STOPPED for 15 s with no MOVING since. Once parked, MOVING for 3 s in a row hides the question (a short GPS jump
  does not); stopped 15 s again, the same question comes back. UNKNOWN (GPS lost) changes nothing.
- **AP4 One question, in the owner's order.** Only for a place the app can edit (its OSM object a node or a way), of
  a kind a visitor reads hours at (a business by `BusinessTags.isBusinessType`, not one of `NOT_ASKED`; a pack type
  that is a raw tag is asked only for shop, craft, office and healthcare; fishing spots, disc golf courses and truck
  scales are not asked), not closed by the user, with no edit of the user's for it waiting or in review:
  1. no hours on the map, and the user did not remove them within the year: "This place has no hours on the map.
     Add them?";
  2. hours the app can read, and `HoursCheck.due` (no own check for a year): "Hours still right?" with the week;
  3. no phone number or no website (each not changed by the user within the year; an ATM or a charger is asked
     about hours only): "This place has no phone number on the map. Add it?", "This place has no website on the map.
     Add it?" or "This place has no phone number or website on the map. Add them?".
  Hours present but unreadable skip 1 and 2. The question chosen stays for the whole arrival.
- **AP5 The user's own year.** The pipeline's `HoursCheck.due`: the newest of the user's own confirmations and hours
  edits in the outbox (waiting or sent; not an undo, not undone; this OSM object or the same pack object), and the
  hours of a place the user added, asked again 365 days after (`HoursCheck.AGAIN_AFTER_DAYS`). "Remove from this
  list" in My reports forgets it.
- **AP6 At most once in 90 days.** A place where the user answered the arrival question (Yes, the edit button or Not
  now) is not asked again on arrival for 90 days; a question shown and not answered is not asked again until the app
  is closed. The window's line is not limited.
- **AP7 The answers.** "Yes" (to "Hours still right?" only) confirms the hours (AP8). "Edit hours", "Add hours", "Add
  phone", "Add website" and "Add them" open the edit form at that field (AP9). "Not now" closes the question. Any
  answer ends the question for this arrival; Done ends the trip as before.
- **AP8 One tap, no summary.** The question shows the hours it confirms, and its one change on OSM is
  `check_date:opening_hours=<today>`; no value the user sees changes. So the question is the summary and "Yes" is
  the Upload (N4). Kept as for any edit: the public-edit and survey notices before the first one, the outbox, the
  send only while OSM still has the hours the user saw (53a), the result line, and Undo in My reports for 12 hours
  (53d). All of it is the pipeline's `AppFlows.confirmHours(HoursTarget)`.
- **AP9 The form at the field.** The edit form opens with that field in view: the hours editor's heading, or the
  phone or website field with the cursor in it and the keyboard up. The rest of the form is 4.2's.
- **AP10 The window's line.** At the details height, under the week: "Hours still right?" with "Yes" and "Edit hours"
  while AP1 and AP2 hold, the hours can be read, `HoursCheck.due` is true and no edit of the place is in review;
  after "Yes", "Hours confirmed" with a check for the rest of that day.
- **AP11 What leaves the phone.** Only what the user's tap uploads (section 3, the confirmation's row). The
  questions read nothing from OSM and send nothing: they use the packs, the outbox and 53e's memory.
- **AP12 Privacy.** Positions stay in 53e's memory. `no_backup/arrival-asked.json` keeps only the OSM object ids and
  days of answered questions, at most 90 days old, deleted when Contribute or online features go off, under PV1-PV8
  (never logged, never in a backup, never sent). A public changeset comment names the place by its name on the map,
  never by a saved place's label.

*Code:* `app/online/PlaceQuestions.kt` (`PlaceQuestions`, `ArrivalAsker`), `AskedStore.kt`; the pipeline's
`HoursCheck` (the year rule, the confirmation item).

## 5. Finding places online (item 7)

**Later.** The owner: "The basics first. Anything that improves existing features can come in a
later batch." Online search improves the existing search, so it comes after the OSM features, and
its service is chosen then. The outline below is kept for that batch.

- **When:** offline search comes first. When it finds nothing, or the place lies in a state that is
  not installed, the list offers "Search online". The app never searches online while typing, only
  on that tap.
- **The service:** a free community service has fair-use rules that a published app must not lean
  on. OpenStreetMap's own Nominatim, for example, allows at most one request a second, no
  search-as-you-type, and an app name in every request. The choices, as in item 7:
  - **A free service within its rules** while Beamline is a personal build. One search per tap
    keeps far inside those rules. The lead's recommendation for when this batch comes.
  - **A lookup service on the owner's NAS.** Possible later. A nationwide index needs a lot of disk
    and memory, so it is weighed again before any publishing.
  - **A paid service:** a cost per search.
- **Results** show beside the offline ones, marked "Online". Picking one gives a place the app can
  route to as far as its installed data reaches. A trip into a state that is not installed still
  needs that state, or the road-trip work of item 6.

## 6. The network rule, rewritten

Today rules N1 to N5 (docs/architecture.md, architecture-downloads.md section 4) allow one network
class, `PackHttp`, for packs only. The new rule keeps the same shape, with one class per service
and the switch as a condition:

- **N1 one class per service.**
  - `PackHttp` for packs, as now;
  - `OsmHttp` for notes, edits and the login;
  - `LookupHttp` for online search.
  No other code opens a connection.
- **N2 where.** Each class has its fixed hosts: `PackHttp` the catalog; `OsmHttp` the OSM API and
  login hosts (in test builds, the OSM test server); `LookupHttp` the one lookup host chosen.
- **N3 what.**
  - **`OsmHttp`:** only the requests the OSM API defines for notes, changesets, single places and
    the user's name.
  - **`LookupHttp`:** only the search request.
  - **All classes:** the app's name in the User-Agent; no cookies, no identifiers, no position
    beyond section 3.
- **N4 when.**
  - **Only after the user's action:** a tap on Send, Upload, Search online or Sign in, or a
    confirmed outbox item going out while the app is open.
  - **Gated by the switches:** `OsmHttp` and `LookupHttp` refuse every request while their switch
    is off.
  - **One exception, by the user's own switch (item 40, the owner, 2026-11-24):** with "Check for
    map updates" on, `PackHttp` fetches the list of states when the app becomes visible and the last
    fetch is a day old or more; never a pack, never while the app is closed. The same for "Check for
    app updates" (item 41): the app's `update.json` once a day, its APK on an unmetered network only.
- **N5 everything else offline**, unchanged: maps, routing and search never need the network.
- **Proof:**
  - the source scan learns the new classes;
  - JVM tests cover each switch;
  - on the emulator, traffic accounting shows **zero** non-pack requests with "Offline only", and
    only the expected hosts with the features on.

## 7. Order of work

| Batch | Contents | Needs first |
|---|---|---|
| O1 | The switch (first run, Settings, the update notice), the network rule rewrite, the outbox, **Report a problem** (notes; signed-in notes come with O2) | nothing more: the owner's answers are in |
| O2 | OSM sign-in and sign-out, **Fix a place's details** (week-hours editor, "Closed permanently", conflict check), notes under the user's name, showing your own edits | the app registered under the owner's OSM account |
| O3 | **Add a missing place** | O2 |
| O4 (later) | **Find places online** | the service, chosen when this batch comes |

Each batch is about the size of the recent ones: one builder, one reviewer, roughly 6 to 14 weekly
points. All of it is tested against OSM's test server before the owner's build ever posts to the
real map.

## 8. The owner's answers (2026-11-20)

1. **Accounts.** Users sign in and out of their own OSM accounts themselves, in Settings or when a
   feature needs an account. The one-time registration of Beamline as an OSM app is made under the
   owner's own OSM account, when batch O2 needs it.
2. **Notes and the user's name.** Yes: signed in, the note form has a box "Post with my OSM name",
   checked to start with.
3. **What "Allow online features" switches on.** Every online feature: those that need the
   network and those that work better with it.
4. **Sending later.** Yes: a confirmed item goes by itself when the connection is back and the app
   is open.
5. **Order.** The basics first: the OSM features (O1 to O3). Online search, which improves an
   existing feature, comes in a later batch, and its service is chosen then.
6. **Closed businesses.** An edit ("Closed permanently", section 4.2) when signed in; not signed
   in, the app offers to sign in or to send a note instead.
