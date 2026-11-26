# Next round: what the owner found while trying the app

Notes from the owner's own use of the early build (version 0.1.0, first installed on the phone on
2026-11-16). Each item says what was seen, what is wanted, and what it touches. Nothing here is
built yet. Items are added as they are reported and removed when a round delivers them.

## The next run, as agreed with the owner on 2026-11-16

**In the run:** the small fixes (items 2, 5, 8 and 10 and the list "Found by the integration"),
the four features (items 1, 3, 4 and 9), dropping Ferrostar, and two written designs for the
owner to read before anything of them is built: road trips without whole states (item 6) and online
as a choice (item 7). At the end: the scripted test on the owner's phone, and a sheet of the draft
artwork and texts to approve. **Not in the run:** the vehicle link, the light sensor, the export of
saved places, the data build for all states, publishing.

**How:** lean. One builder for each group of work, one reviewer each, one fix pass, then the
emulator tests of the joined app.

**The owner's answers:**
- Ferrostar is dropped: Beamline's own follower does all of it. (The app gets smaller, about 90
  licence entries go, and with them the one allowed GPL exception; the test drive is run again.)
- The uncertainty bubble and the steadier position apply while browsing only; guidance keeps the
  position on the route.
- "End trip" stays one tap, and a bar "Trip ended" offers Undo for about 10 seconds.
- Search results on the map: after Enter the map fits the matches near the driver as pins, with a
  short list at the bottom that can be pulled up; a pin or a row opens that place's sheet.
- When the app opens and has no position to show, the map opens where it was left last time; the
  whole state only on the very first open.
- US spellings everywhere in the app's texts.

**Settled by the lead** (small; say so if they should be different): a release build with
downloads off says what file "Use a file instead" means; short road names such as "Peach St" do
not break across two lines in the banner; the results view shows at most the 20 nearest matches.

**The owner's answers after part 1 (2026-11-18, report 20):**
- The guide view's tilt (pitch 60) is fine for now.
- The driven road's shields go back above the route line while guiding.
- A far house with the typed number ranking above a nearer street: the owner tests it on the phone
  first and says.
- The owner asked for the cheapest features first, with usage looked at between them (the lead
  proposed small batches of related features rather than one at a time).

**The owner's answers after batch B (2026-11-18, report 22; for batch C):**
- Search results on the map: up to 20, none farther than 25 miles; when nothing is that close,
  the nearest few anyway.
- Arriving with the destination on the driver's side: preferred unless it adds more than about
  one minute; then the quicker side.
- Hours, phone and website also for a place tapped on the map, a saved place and a recent (looked
  up in the pack by name and position) when the data has them.
- "Closing soon" stays the last hour before closing.
- Call and Website are offered again once the trip has arrived.

**The owner's answers after batch C1 (2026-11-18):**
- A place with an address is tied to the street its address names when that street is within
  reach (the app picks it, for example from the engine's nearby roads); otherwise the nearest
  street of residential class or above, as built.
- A pin the driver drops stays where it is put: the route goes to the road at the pin (alleys still
  avoided along the way); no tie.
- The lane-ends warning keeps its words ("Right lane turns right: keep left") and shows the lane
  row already built with the lanes that continue highlighted.
- Fewer false lane warnings is right: where a lane becomes turn-only with no lane added before it,
  the warning needs about 300 m of road with that lane count, as built.

## From the early look, 2026-11-16

1. **Search results on the map.** Typing a name such as "walmart" and pressing Enter should show
   all matches near the driver on the map, as pins, together with the list. Today search only gives
   a list, and one place is shown at a time after a tap.
   *Touches:* the search screen (a "show on map" result state after Enter), the map scene (a set of
   result pins, one of them selected), the camera (fit the results), the place sheet (opened from a
   result pin). Rule S of the search contract and the map scene contract gain a result set.

2. **Picking a search result moves the map to it.** After searching (for example a storage
   business by name) and tapping the result, the map should go to that place and show its pin with the
   sheet. Today the camera stays where it was; when it was following the puck it stays on the puck,
   so the place is not on screen.
   *Touches:* camera rule K1 of `docs/architecture.md` 3.7, which says the browsing camera is never
   moved by the app except for my-location: it must also move for a picked place (leave the follow
   mode, centre on the place above the sheet at a street-level zoom, capped outside the installed
   states). Small: the map screen in `:app` and the camera director's browse rules, with a test.

3. **An uncertainty bubble around the position, and a steadier centre.** When the position is
   inaccurate (indoors, between buildings), the map should draw a larger soft circle around the
   puck, about as wide as the spread of the unsteady fixes, and put its centre where the phone most
   likely is, worked out from more than the last fix: the recent fixes weighted by their reported
   accuracy, and the phone's motion sensors (the accelerometer tells standing still from moving).
   Today the puck sits on the last fix as reported, with no sign of how uncertain it is, so indoors
   it jumps with every fix (report 11: a fix every 20 s at about 100 m indoors).
   *Touches:* the position model in `:core:location` (a filtered position and an uncertainty radius
   beside the raw fix; stationary detection from the accelerometer; a new sensor listener in `:app`),
   the map scene and layers (the circle under the puck, by day and night, hidden when the fix is
   good), and the rules file `position.json` (thresholds). To settle first: which filter (a
   weighted average while standing still and a Kalman-style filter while moving is the usual
   split), whether Android's own fused provider is used where the phone has one, and that guidance
   keeps using the route-snapped position, not this one.

4. **"Day" and "Night" promise more than the app does.** Settings offers Automatic, Day and Night
   for the map, and Automatic only follows the phone's own light or dark setting: nothing changes
   with sunrise and sunset or with the light around the phone. Either the names change (Light,
   Dark, Same as phone) or the app really switches by day and night.
   *Touches, if it switches:* a pure sunrise and sunset calculation from the position and the date
   (no network; the last known position, else the middle of the installed state), a rule for when to
   switch (civil twilight, with a margin so it does not flicker), the Settings choices, and the
   existing style reload. A light-sensor mode (tunnels, garages) would be a further step: a sensor
   listener in `:app` and a rule with delays in both directions.
   *Decided by the owner on 2026-11-16:* build the real day and night switch (Automatic follows
   sunrise and sunset; "Same as phone" becomes its own choice; Day and Night stay). The light
   sensor is left for another day.

5. **Open where the driver is.** On opening, the app shows the whole installed state from far
   above. It should open at the driver's position: on the puck, at street level, following it,
   as after a tap on the my-location button.
   *Touches:* the start-up camera in `:app` and camera rule K1 (a cold start with location allowed
   begins in the follow mode from the last known position, and moves when the first fix arrives);
   what to show when location is not allowed or no position is known yet (the place the map was
   left at last time, else the state as today), so the last camera is remembered in the app state.
   The one-off "<state> is ready." view after a download keeps fitting the new state.

6. **Road trips across states without downloading whole states.** A trip through several states
   should work (the route, detours, stops along the way) without installing each state in full.
   The owner's idea: a button on the route sheet that downloads what the route needs before
   guidance starts.
   *What it needs, as far as can be said before a design:*
   - **Planning across states that are not installed.** The app can only plan where it has road
     data. A small nationwide set with the main roads only (motorways and major highways, the top
     levels of the routing data) would let it plan the long legs anywhere in the lower 48.
   - **A corridor download.** For the chosen route, the detailed data within some miles of it
     (wider around the stops and the ends): map tiles, the local routing tiles, and the places a
     driver looks for on the road (fuel, food, rest areas, lodging, towns). Detours and re-routes
     then work inside the corridor; outside it the app falls back to the main-roads set.
   - **Hosting that can hand out parts of a state.** Today a state is a few large files. A corridor
     needs single tiles: the map format already allows fetching ranges of one file, routing tiles
     would be published one by one or with an index, and search would need a per-area form.
   - **In the app:** the route sheet says which states the trip passes that are not installed and
     offers "Download this route" with its size beside "Download the states"; trip data is kept
     apart from installed states and cleaned up after the trip (or kept, the owner's choice); the
     downloader, the installed-data view, the zoom cap outside covered areas and the network rule
     all gain this case.
   *Depends on:* the data build for all lower-48 states (put off on 2026-11-16) and a host for it.
   *Size:* a round of its own, starting with a design. Questions for that design: corridor width,
   what happens when the driver leaves the corridor, how long trip data is kept, and how search
   behaves on a corridor.
   *The owner's suggestion (2026-11-16): split states into counties.* A county becomes the unit
   that is published and downloaded, and "Download this route" fetches the counties the route
   passes through, with their neighbours. To weigh in the design:
   - For it: a county is a plain file like a state is today, so any simple host works and nothing
     has to be fetched in pieces; the downloader and installer already handle packs; a county is a
     name people know; house-number sources are already organised by county. Pennsylvania's
     1.3 GB over 67 counties is about 20 MB a county on average, so a long trip is a few hundred
     megabytes instead of several gigabytes of whole states.
   - To solve: the map is drawn from one source per installed pack, and dozens of small sources
     would slow drawing badly, so counties have to be joined into one map store on the phone;
     search would have to look in many small files at once, or join them too; installing a whole
     state would be 67 installs unless installs are batched; routing data comes in fixed squares
     that do not follow county lines, so neighbouring counties share squares (the same already
     happens at state borders), and the main-road levels are large squares best shipped once for
     the whole country, which is the nationwide main-roads set named above.
   - Likely shape: the nationwide main-roads set, county packs as the download unit, joined on
     the phone into one map, one routing set and one search index.
   *Finding the destination (raised by the owner, 2026-11-16).* Search only knows the installed
   packs, so an address in a state that is not installed cannot be found today, and a road trip
   could not even be started. The design must answer this first. Ways to weigh:
   - **A small nationwide place index** in the app or downloaded once (cities and towns, ZIP codes,
     counties, a few megabytes): the app can then tell where any typed address lies. For "123 Main
     St, Columbus, OH 43215" it reads the city, state and ZIP, finds the county, and says: "This
     address is in Franklin County, Ohio, which is not on this phone. Download it (about 25 MB) to
     find it." After that small download the exact address is found and the trip can be planned,
     and "Download this route" fetches the rest. A city or ZIP alone can be routed to at once.
   - **A place handed over by another app:** a shared location or a map link with coordinates
     opens in Beamline as a destination without any search data.
   - **A point on the map:** the bundled overview map is enough to drop a pin on a town, not on a
     street address.
   - **Looking the address up online** would work everywhere but sends what the driver types to
     an outside service, against the rule that nothing leaves the phone; only as an explicit
     choice of the owner, and never the default.

7. **Online as a choice (direction set by the owner, 2026-11-16).** Beamline stays an
   offline-first app: everything it does today keeps working with no connection. From here on it
   may also offer online conveniences that the user chooses to switch on. This replaces the rule
   that the network is used for the list of states and the packs only.
   *What it opens up:* finding an address or a place anywhere without its data on the phone (the
   gap of item 6), planning a route through areas that are not installed, and later things that
   cannot exist offline at all (closures, traffic).
   *What the design must settle before anything is built:*
   - **The switch.** The owner wants the choice made when the app is first opened (2026-11-16):
     a step of the first run, after the notice, with two equal choices ("Offline only" and "Allow
     online features"), each saying in a line or two what it means and what is sent; changeable at
     any time in Settings, where the single features can also be switched one by one; shown once
     to someone who updates from a version without it. Neither choice is pre-selected. "Offline"
     is a real promise: with it the app makes no request beyond the pack downloads the user
     starts.
   - **Who answers.** Every online feature needs a service. Free community services for address
     lookup and routing have fair-use limits that a published app may not lean on; the choices are
     those services within their rules while the app is a personal build, a service the project
     runs itself (the owner's NAS was offered as a host), or a paid one. Each has a cost.
   - **What leaves the phone, and to whom.** A typed address, a position, a route request: each
     named in plain words in the app before it is first sent, never anything else, never in the
     background, and the saved places never.
   - **The network rule rewritten, not dropped.** Still one place in the code per kind of request,
     still provable by scan and by measuring the app's traffic, now with the switch as a condition.
   *Fits with item 6:* the offline answers there (the nationwide place index, county downloads)
   stay the base; online lookup becomes the shortcut for those who switch it on.

8. **The credit line takes too much of the driving screen.** While guiding, three lines of source
   credits sit above the trip bar all the time (seen on the owner's phone, 2026-11-16). Wanted:
   show credits only where and when they are needed.
   *What the sources require:* only OpenStreetMap asks for a credit on the map itself. Its
   attribution guidelines (osmfoundation.org/wiki/Licence/Attribution_Guidelines, read 2026-11-16)
   allow the credit to fade or collapse after five seconds, after the user touches the map, or on
   dismissal, as long as the licence information can still be found by someone who looks for it,
   and allow a splash screen or pop-up instead of a corner of the map. Natural Earth, the US Census
   Bureau and TIGER/Line are public domain and the City of Scranton data is under a public-domain
   dedication: none of them needs a line on the map. The National Address Database's terms are
   still to be confirmed (it stays out of published packs until then).
   *Proposed:* on the map one short line, "© OpenStreetMap", small, in a corner; it fades after
   five seconds or at the first touch, and a tap on it opens the full list. The full list of
   sources (as today's line, per installed pack) moves to About, under "Map data". While guiding,
   the short line shows on the route preview and for the first seconds of the drive, then the
   space belongs to the map. The credit rule of the contract (5.4 item 4: "keeps the credit line
   visible above the trip bar") changes accordingly.

9. **No alleys unless there is no other way, and arrive with the destination on the right.** On a
   real drive between two businesses the route ended in the alley behind the destination (owner,
   2026-11-16). Wanted: alleys and similar back ways only when nothing else reaches the place, and
   an arrival on the destination's own side of the street, so that in the US it is on the right.
   *Why it happens:* a place's point is tied to the nearest road, and behind a building the nearest
   road is often the alley or a car-park lane; and nothing in the request asks for a side.
   *Touches* (the routing request and presets, in Python and Kotlin alike, with the recorded
   reference routes made again):
   - where a destination or a stop is tied to the road network: prefer streets of residential class
     and above, and fall back to service ways only when none is within reach; later, tie an address
     to the street its address names;
   - the cost of driving through alleys and service ways along the route, raised so they are used
     only when there is no ordinary street;
   - the side of arrival: ask the engine for the destination's own side where the street is not a
     minor one, with a tolerance so that it never forces a long loop or a U-turn for a few metres;
   - the arrival text already says the side ("on the right"); check it against the new behaviour.
   To verify in the round: the engine's exact settings for alleys and for the side of arrival, on
   real cases (a shop with an alley behind it, a place on a divided road, a one-way street).

10. **Check the tilt of the driving view on the phone.** A screenshot from the owner's phone,
    taken while stopped just before a turn, looked close to top-down. On the emulator the final
    build tilts as designed (the camera reports 50 degrees and the road narrows towards the top of
    the screen). To do: read the camera's pitch from the app's log during a real or simulated
    drive on the phone, and look at it while moving; if it is right but reads as flat when zoomed
    in close, revisit the close-up zoom and the tilt together.

## Questions left by the integration (report 19), for the owner

- "End trip" is a single tap, close to the Stops button: keep it, ask "End trip?" first, or move it?
- Spellings: the texts use British forms in places ("Kilometres and metres", "Neighbourhood"); switch to US spellings throughout?
- A release build has downloads switched off and says "Downloads are off in this build. Use a file instead." Add a hint about what file is meant?
- Keep short road names such as "Peach St" from breaking across two lines in the banner?

## Found by the integration, for the next round

- Search: an exact street address can rank below a place or an estimated address with a similar name.
- Map: road names drawn along the route line in the tilted view; the camera's heading catches up slowly after a turn.
- After removing a state while choosing a route, the map is blank until a state is installed again.
- A saved dropped pin's open sheet still offers "Save".
- The app carries code libraries for phone types that do not exist any more (about 10 MB to drop).
- Measured on the emulator, the driving view draws at about 18 frames a second; to be measured on the phone.

11. **Use what the state packs already hold** (owner, 2026-11-17). Three things are on the phone
    and not shown yet; all three go into the next run:
    - **Speed-limit sign while guiding.** Each route answer of the engine carries the limit per
      stretch (`annotation.maxspeed`) and the sign style (`speedLimitSign`: US, MUTCD). On the exit 24
      route it is known for 85 % of the distance (mostly interstate; local streets not measured).
      A US-style sign in a corner of the guidance screen while the limit is known, nothing where it
      is not; red when the speed is clearly over it (a margin, and only with a good fix). Touches
      the session's progress (the limit of the current stretch), the drive presenter and the
      overlay, units (mph and km/h).
      **With the current speed beside it** (owner, 2026-11-17): the speed from the GPS fixes in a
      round badge next to the sign, shown during the whole of guidance (also where no limit is
      known), never while browsing (owner, 2026-11-17); the number turns **yellow at 5 mph over**
      the limit and **red at 10 mph over** (8 and 16 km/h in metric; owner, 2026-11-17), and the
      sign stays as it is; with a weak or lost fix the number is replaced by a dash rather than a guess. GPS
      speed often reads a few mph below the car's speedometer, which is built to read high; the
      report says so, and the comma 3X link (Later) could give the car's own speed.
    - **Opening hours, phone and website on the place sheet.** The search packs keep them from
      OpenStreetMap (Pennsylvania: hours on 19 % of places, 67 % of fast food, 38 % of restaurants,
      14 % of gas stations; phone 26 %, website 28 %). "Open until 9 PM", "Closed now", "Opens at
      7 AM" from the OSM opening_hours text with an existing open-source parser (licence to check),
      at the phone's clock and time zone; the full week behind a tap; "Call" and "Website" buttons,
      not offered while guiding.
      **Status in colour, on the place sheet and on each search result row** (owner, 2026-11-17):
      green "Open now", yellow "Closing soon" (within the last hour before closing; the lead's
      choice), red "Closed now"; the words always beside the colour; nothing shown when the place
      has no hours in the data. No separate filter in this run.
    - **3D buildings in the tilted driving view.** The map tiles carry building heights
      (`height`, `min_height`). Extruded buildings at the closest zooms of the guide view, in the
      day and night colours; kept only if the frame rate on the owner's phone stays acceptable
      (measured on the emulator first, then the phone).
    Not chosen (2026-11-17): spoken directions (guidance stays silent; the spoken sentences are in
    every route already, for a later round), reviews (no open source; dropped).

12. **A lane that ends in a turn: warn the driver to change lanes** (owner, 2026-11-18; goes into
    batch C). Northbound on Peach Street at Craig Street, Erie, two lanes go north, and at the
    intersection the right one becomes a right-turn-only lane; a driver going straight in it has to
    move left. Wanted: while guiding, a notice ahead of such a spot to get into the lane that
    continues. The owner allows this intersection to be named in the project as a test case.
    *What the data holds* (checked by the lead with the offline engine on the PC): the route answer's
    intersection there carries lanes `left` (not valid), `straight` (valid), `right` (not valid); and
    `trace_attributes` along the route gives the lane counts of the road pieces: 2 lanes for a long
    stretch, 3 for the last ~100 m (a left-turn lane added), 1 after the intersection. So a through
    lane that becomes turn-only can be told apart from an added turn lane: fewer lanes valid for the
    route at the intersection than lanes on the road before the turn lanes began.
    *Lead's design (to be confirmed by the build):* on a step that goes straight through such an
    intersection, show the lane row with the valid lane(s) highlighted and one line such as "Right
    lane turns right: keep left for Peach St", early enough to change lanes (about 15 to 20 s at the
    current speed, with a minimum distance) and gone after the intersection; never for an added turn
    lane only. GPS cannot tell which lane the car is in, so it shows to every driver approaching. It
    works only where OpenStreetMap has turn lanes tagged. One more engine call per route plan
    (`trace_attributes` for the lane counts), or the equivalent from data already in the answer.

13. **Route types: drop Shortest; give Simpler more room** (owner, 2026-11-18; for a later batch,
    not batch C). Offer only Fastest and Simpler. Simpler may drive further or longer than today
    (its pool may take a route up to 5 % slower than Fastest) to make a drive with fewer turns.
    The owner's example: Erie to Butler, where they saw both types leave I-79 at exit 113 for PA 258
    and expected Simpler to use exit 99 and US 422. *Lead's check the same day* (offline engine on
    the PC, tree with batch C1): from downtown Erie, Millcreek, Harborcreek and east Erie to three
    points in Butler, both Fastest and Simpler already take exit 99 and US 422 then PA 8. The owner
    then named the destination: a road in Center Township just north of Butler. There, from
    downtown Erie, both types take exit 113, PA 258 and PA 8 (97.6 min, 12 maneuvers); a route
    forced along exit 99 and US 422 took about 5 min longer (the forcing point was rough); and
    Simpler with a wider window (+10 % to +25 %) picks I-79, I-80 (exit 29) and PA 8 instead
    (104.3 min, 9 maneuvers), because the engine's candidate pool never offers the US 422 route.
    So a wider window alone is not enough: the pool needs candidates that keep to major roads.
    *To settle in the build:* how much more time Simpler may take (for example up to 15 % or a
    fixed number of minutes), how turns are counted (maneuvers, or only turns off the current road),
    and what happens to a stored "Shortest" choice (it becomes Fastest).
    *The owner's answers (2026-11-18 evening):* Simpler may be up to 10 % longer than Fastest and
    never more than 10 minutes longer; turns are still counted as every guidance instruction (as
    today). A stored "Shortest" becomes Fastest (the lead's default).

14. **Named beaches and similar places are not searchable** (owner, 2026-11-18). "Beach 10" finds
    nothing, though the Pennsylvania data has `Beach 10 - Budny` and the other Presque Isle beaches
    (`natural=beach`, numbered 1 to 11). *Why* (the lead's check): the search build keeps named
    objects only under amenity, shop, tourism, leisure, office, craft, healthcare, historic,
    aeroway and emergency (`packbuilder/search/osm_extract.py` POI_KEYS), so `natural=beach` is
    dropped. Beaches 1 to 4 are tagged `leisure=park` and are missing too, for a reason still to
    find. The query itself is fine: only a leading number is read as a house number. *Fix:* keep
    named beaches (and other named natural places a driver goes to, such as waterfalls and peaks),
    find why the four parks are missing, rebuild the search part of the Pennsylvania and Ohio packs
    and the conformance reference; the owner's phone then needs the new Pennsylvania pack.

15. **The results list cannot be lowered to show the whole map** (owner, 2026-11-18). The list of
    batch B has two heights (about 30 % and 60 % of the screen); a drag down from the lower one does
    nothing, and only the handle drags (a drag on the rows scrolls them). *Fix:* a third, lowest
    height with only the handle and the result count, reached by dragging down, so the pins and
    the map are free; the camera fits the pins to the space left; a drag on the rows moves the list
    when the rows are scrolled to the top.
    *Also the route choice sheet* (owner, 2026-11-18, on 0.1.4): with From, To, Add stop, three
    route cards and Start it covers about 60 % of the screen, so the routes cannot be compared on
    the map. It must be draggable down to a low height (the chosen route's time and Start still
    showing), with the camera fitting the routes to the space left, and back up. The same rule
    for every bottom sheet (results list, route choice, place sheet): a lowest height that leaves
    the map free.

16. **Arriving on the right must not cost a loop around the block** (owner, 2026-11-18, on 0.1.4).
    To a business on a residential street in Erie, approached along a main road, the route went past
    the street, around the whole block (three left turns) and back, only to arrive with the place
    on the right; the plain route was one turn onto the street with the place on the left. The side
    rule of the C1 routing fix (every street, at most 60 s more, no extra U-turn) let it through.
    *The owner's answer:* both limits. On quiet residential streets (residential class and below)
    arrive on whichever side is quicker, with no side request. On busier streets (tertiary and
    above) prefer the right side when it costs at most about a minute and at most two more turns than
    the plain route, going around one block at most (refined the same evening: the owner wants a
    shop on a busy secondary street in Erie, which the lead found needs one block and two extra
    turns from the west, to keep arriving on the right).

17. **"Find me parking"** (owner, 2026-11-18; for a later batch). Many downtown places have only
    street parking. A button, on the place sheet and near arrival, that routes to the closest
    parking lot or garage to the destination, paid or not (most downtown garages charge). The
    owner's example: a bar on East 10th Street in Erie with a garage across the street. *What the
    data holds* (the lead's check): the search pack already has named parking places
    (`amenity=parking`); near that bar there are ten within about 650 m, the nearest a garage 72 m
    away. Unnamed lots are not in the pack today (a search place needs a name), and many lots are
    unnamed, so the build must decide whether to add unnamed public lots (`access` not private,
    with `parking`, `fee` and `capacity` where tagged). *To settle:* where the button shows (sheet,
    route choice, arrival card), whether it replaces the destination or adds the lot as a stop
    before it, how far is "close" (for example 400 m walking), and what the sheet says about fees
    (only what the data says; no prices).

**The owner's answers after batch D (2026-11-19):**
- Simpler keeps picking the fewest instructions within 10 % / 10 min (north of Butler it stays on
  I-79, I-80 and PA 8).
- Unclassified roads count as quiet streets (no side request).
- The rise in left arrivals on quiet streets (68 to 133 in the Erie sample) is accepted.
- Item 18 is built now, alone, as batch E.

18. **A route to a business must end in its parking lot, not on the main road** (owner, 2026-11-19,
    on 0.1.5). A route to a restaurant on Peach Street in Millcreek ends on Peach Street instead of
    guiding into the restaurant's lot. *The lead's check* (offline engine on the PC): around that
    restaurant lie a driveway 20 m away, a parking aisle 64 m away, Peach Street (primary) 48 m and a
    residential side street 45 m. Without a tie (0.1.3) the route ended on the driveway; the C1 tie
    (nearest residential-or-higher street) ends it on the side street; the address-street tie
    (0.1.4/0.1.5) ends it on Peach Street. So both ties keep routes out of every service way,
    alleys and parking lots alike, which fixed the alley case but broke this one. Valhalla's
    location filter knows only road classes, not the service kind, so the app has to choose.
    *Proposed fix:* for a place that is a business or other point of interest (not a plain house
    address), when a driveway or parking aisle (Valhalla edge use `driveway` / `parking_aisle`) lies
    within about 80 m of the place, end the route there (the place as the display point), reached
    the ordinary way; never an alley (use `alley`); otherwise the address street, then the nearest
    street, as today. The address probe (one `trace_attributes` call) can return the edge use too.
    Python and Kotlin alike; cases: this restaurant (it must end in its lot), the alley case of C1
    (must still avoid the alley), a house address (ends on its street), a plaza with a shared lot.
    *Expected result, agreed with the owner:* the route enters the lot from the side street behind
    the restaurant and ends in the lot beside the building. The lead's check with the destination
    allowed onto the lot: from the north, Peach Street, Douglas Parkway, Commons Drive, into the lot
    (221 s); from the south, US 19, Downs Drive, Commons Drive, into the lot (232 s); both end 20 m
    from the building, the same way the owner's reference map goes.

**The owner's answers after batch E (2026-11-19, report 25, on 0.1.6):**
- A lot route may stay up to 90 s slower than the street ending (13 of 450 sample routes are 1-2
  min longer than 0.1.5, mostly malls and plazas): kept.
- Downtown places with a driveway at least 10 m nearer than their street end in it (13 of 38): kept.
- Car washes (one car wash's route drives through its drive-through lane to the aisle beside the
  building, 3 of 450): the owner asked to see both endings side by side. The lead's picture (a car
  wash on West 12th Street): 0.1.6 drives up the wash lane to the aisle beside the building
  (4.8 min); with car washes left out of the lot rule the route ends on West 12th Street at the
  lane's entrance (4.3 min, as 0.1.5). The owner does not want places picked out one by one
  ("We need a way to make sure this is done smart"): no car-wash exception; the general rule of
  item 19 (drive-through lanes left out of every route) settles it.
- Item 19 is built later.

**The owner's answers after batch F (2026-11-20, report 27, on the emulator results):**
- Waterfall and cave icons: yes, draw them; the lead's drafts go to the owner for approval before
  anything ships (until then waterfalls show the viewpoint icon and caves the attraction icon).
  Approved on 2026-11-20 after four rounds of drafts: the waterfall (two rock walls, two streams,
  foam where they land, a pool) and the cave (a rock mound with a dark entrance), both in the
  standard 19 x 19 frame and the green group; the approved SVGs wait in the lead's scratch
  (data/r3-run2/icons-approved/) and go into assets/icons/beamline with the next batch.
- Peaks: keep all of them (1,589 in Pennsylvania).
- "Beach 1" after Enter keeps the nearest-first order (Beach no.1 third); no change.
- The stepped drag of the sheets and the tap on the handle at the highest height: the owner tests
  them on the phone first.
- Still open from batch E2: the place itself, untied, as the last end for a plain place whose
  address street is out of reach; Simpler following the side of the street Fastest decided.

19. **Every route type ends at the same point** (owner, 2026-11-19, on 0.1.6). On the route choice
    sheet for the restaurant of item 18, Fastest ends in the lot but Simpler ends on Peach Street,
    in the middle of the road. *The lead's check* (offline engine, 5 origins): the lot rule's tie
    check runs per route type. Simpler's fewest-instruction way into the lot (around the back, via
    Rotunda Drive) was 94-95 s slower than its own street ending (via Interchange Road onto Peach
    Street), just over the 90 s limit, so Simpler fell back to the street ending, while Fastest's
    way in was about 76 s slower and kept the lot. Simpler lost the lot from 3 of the 5 origins
    (and so showed as quicker than Fastest). *The owner's rule:* "The destination point is
    non-negotiable. The route should end at the same point no matter what route choice." The end
    point of each stop is decided once per plan and every route type routes to that same point;
    route types differ only in the way there.
    *The plan, agreed with the owner (2026-11-19, after the lead's pictures):* one rule for every
    destination, from the map data, with no list of special places.
    1. The possible ends, best first, by kind of stop: a business (point of interest): its own lot
       (batch E: a driveway or parking aisle within 80 m, at least 10 m nearer than every street),
       then the street its address names, then the nearest street. A house address: the street its
       address names, then the nearest street. A dropped pin: exactly where it was dropped.
    2. Each end is tried in that order with the Fastest route and the first that passes is kept.
       An end passes when the way to it never uses an alley or a drive-through lane, adds no U-turn
       and is at most 90 s slower than the next end on the list; a street end keeps the arrival
       side rule of batch D (busier streets only, at most 60 s and two instructions more). New:
       drive-through lanes (Valhalla use `drive_through`) are left out of the route itself, not
       only as the end point (the app's engine allows `exclude_polygons`: max 100 vertices, 10 km),
       so a lot reachable only through a drive-through lane fails and the next end is used.
    3. The end point is fixed for the whole trip: every route type (Fastest, Simpler) routes to it
       and a reroute keeps it. The checks (lot, the stop tie's check, the arrival side) run once
       per plan instead of once per route type.
    *The lead's checks (offline engine):* the restaurant: the lot passes from all 5 origins
    (Fastest about 76 s more than the street end); Simpler then ends at the same point, 18-61 s
    slower than Fastest with 1-5 fewer instructions (from one origin: Fastest 9.8 min, 9
    instructions; Simpler 10.1 min, 8, coming down Commons Drive from the north; 0.1.6's Simpler
    8.6 min, 5, ending on Peach Street). The car wash on West 12th Street: with its wash lane left
    out, the engine finds no route to the aisle beside it (all 3 origins), so it ends on West 12th
    Street at the entrance (4.3 min instead of 4.8). In the 450 Erie plans only that car wash's 3
    routes use a drive-through lane in their last 300 m. Probes in data/r3-run2/e/ (scratch):
    simpler_case.py, dt_exclude.py, carwash_pair.py, viz_data.py, viz_draw.py.
    *Cases for the batch:* the restaurant stand-in (both route types end at the same lot point, from
    the north and the south); a car wash or fast-food place whose lot is reached only through its
    lane (ends on the street) and one whose lot has an ordinary entrance (ends in the lot, the route
    stays out of the lane); Family Dollar (own driveway, no alley); a house address and a downtown
    place with no lot (unchanged); a reroute keeps the end point; Python and Kotlin in step.

20. **Drag a place sheet up for all its details** (owner, 2026-11-20, on 0.1.8). "We'll need to add
    the ability to drag up on location pages so the user can see things like business hours for
    all days of the week, and business phone number and website spelled out." Today (batch B, rule
    S10; batch F, rule D10) the place sheet has two heights; the week's hours open only with a tap
    on the status line, and the phone and website exist only as the Call and Website buttons.
    *Proposed:* a third, highest height of the place sheet (drag up, a tap on the handle, or the
    screen reader's "Raise the sheet"), with the same shared sheet rules (D10): the whole week of
    hours always shown (Monday to Sunday, today in bold, "Holiday hours may differ" where it
    applies, nothing when the place has no readable hours), the phone number written out in US
    form ("(814) 555-0123"; more numbers each on a line) and the website written out without
    "https://" ("example.com/menu"); a tap on the number opens the dialer and a tap on the address
    opens the browser, as the Call and Website buttons do, and a long press copies it; while
    driving the text shows but the taps do nothing (batch B's rule: no Call or Website while
    guiding). The same details for a business tapped on the map and for a saved or recent place
    made from a search result: today those show no hours because they carry no search id (report
    22, "Not done"); look the place up in the search pack by its name and position. No new data:
    the packs already hold opening_hours, phone and website.

**The owner's answers after batch G (2026-11-20, on 0.1.9):**
- Batch E2's last end: yes, add the place itself, untied, as the last end of every list, so the
  nearest street has to pass the check against it (at most 90 s slower, no extra U-turn); this
  brings back 0.1.6's result for a plain place whose address street is out of reach. To build.
- Batch E2's side rule: kept (the side is decided once with Fastest and Simpler follows it).
- Item 21 (below): all three kinds of contribution, planned inside the online design of item 7,
  which the lead writes now for the owner to read before anything is built.

**The owner's answers after batch H (2026-11-20, on 0.1.10):**
- The place sheet's details height stays at 70 % of the screen.
- No copying of a number or website while guiding (taps and long press stay off while driving).
- The sheets stay as they are (95 % opaque); not made fully solid.
- Brand and operator stay off the details height.
- Next batch: online batch O2 (sign-in, fixing a place's details). Before it starts the owner
  registers Beamline as an OAuth 2 app under the owner's OSM account, and makes a throwaway account
  and an app registration on OSM's test server for the builder's tests (the lead gives the steps).
- Follow-up for the next app batch: the first-run text of "Allow online features" mentions finding
  places online, which comes only with O4; reword it until then.

21. **Contribute to OpenStreetMap from the app** (owner, 2026-11-20). "Another feature that I think
    would be nice, is the ability to contribute to OSM from within our app." The owner chose all
    three kinds: report a map problem (an OSM note at a chosen point, no account needed), fix a
    place's details (hours, phone, website, name, with the user's own OSM login, the change shown
    before upload) and add a missing place (a new point with its type, name and details). Not
    roads, lanes or turn rules. Online, off until switched on, never while guiding. Planned in the
    online design (item 7): docs/online-design.md. The search packs already keep each place's OSM
    type and id (`poi.osm`, for example `n25479855`), so an edit can name its object without a new
    pack build.

22. **Help the driver see a turn coming** (owner, 2026-11-20). "What all we can do to help users
    with knowing their turn is coming up. Things like context clues like having traffic light /
    stop sign icons on the routes intersection, or twisting / zooming into the map to keep the
    route in focus (like Apple maps)." The details are settled with the owner when the item's turn
    comes. The lead's proposal, most useful first:
    1. **Traffic lights on the route and in the words.** Small traffic-light icons at the lights on
       the route ahead, and banners such as "Turn right at the light" and "Turn left at the 2nd
       light". *The lead's check:* the routing data already marks lights (Valhalla trace_attributes
       `end_node.traffic_signal`: 11 lights on a short downtown Erie route), and the app already
       makes one trace_attributes call per route for the lane-ends warning (batch C1), so the
       lights come with no extra call.
    2. **A camera that leans into the turn** (like Apple Maps): approaching a turn the map zooms in,
       tilts a little and turns toward the road the driver is turning onto; after the turn it eases
       back to the driving view. Earlier and farther out at highway speed, closer in town. The
       whole sequence stays in frame at roundabouts, ramps and back-to-back turns. It builds on the
       speed-aware camera and the two-turn banner that exist.
    3. **A countdown on the banner:** a bar that fills as the turn comes closer.
    4. **Stop signs (later).** The routing data does not hand them out (trace_attributes shows no
       stop or yield signs), so they would come from OSM (`highway=stop`) in the pack build; OSM's
       stop signs are less complete, so a missing icon must not suggest there is no sign.
    5. **Landmarks (later):** "Turn left after the Sheetz", from a well-known business at the
       corner in the search data; needs rules for what counts as visible and known.
    6. **An optional vibration (later)** at "get ready" and "turn now", off by default; guidance
       stays silent by the owner's choice (2026-11-17).
    7. **Spoken directions (very low priority;** owner, 2026-11-21, after the audit raised it): if
       ever, the phone's own offline voice saying the banner's words, with a mute button.
    Icons only for the route ahead, so the map stays calm. Proposed first batch: 1, 2 and 3.
    *The owner's choice (2026-11-21):* 1 and 2; no countdown bar. *Built (2026-11-21, 0.1.14):*
    traffic-light icons at the lights of the next 3 km of the route and "At the light, turn left"
    when a plain turn is at one (rule B10; the icon was approved by the owner on 2026-11-21, on the emulator's day style); the
    camera leans into a turn and eases back (K3a). With them, from the lead's walkthrough of 0.1.13
    (the driving screen showed many place names and small road names, and no name for the road being
    driven): while guiding the general place names are hidden and only fuel, charging, parking, rest
    areas, services and toll booths show; road names are 1.3 times larger and upright; a label
    names the road being driven (B12, D11); a ramp without a sign says the road it leads toward and
    the turn into a store's lot says "into the parking lot" (B11); lane descriptions say "straight".

23. **A repository that builds and publishes the packs** (owner, 2026-11-20). "Can we make another
    repo who's whole job is to push out state packs? Maybe it could be run daily on github?" The
    owner's answers: a repository that is **private until the items are built, then public** (with
    the app's repository; the first answer was public),
    built **weekly**, **Pennsylvania and Ohio** first. It also becomes the home of the county packs
    of item 6 ("We also wanted to make individual counties a thing, right?"). Design for the owner:
    docs/packs-design.md (with the questions left).

24. **A friendlier OSM edit form** (owner, 2026-11-20, on 0.1.11, after a real edit). "I was able to
    delete the ':' and couldn't figure out how to type it in. Maybe we can make the edit page more
    user friendly as a whole?" *The lead's look* (O2's form): the time boxes are free text with a
    number keypad that has no ':' key; times are 24-hour while the app shows "8 AM-6 PM"; seven
    separate day blocks make the page long; the switches and the X have no words. An OSM checker
    also warned that the owner's hours named no public holidays (a warning, not an error).
    *Plan, built with O3:* times picked with the platform's time picker in the phone's 12/24-hour
    form; day groups with day chips instead of seven blocks, split days, "Open 24 hours", "(next
    day)" after midnight; a plain-words preview; a public holidays line ("Not sure" by default,
    "Closed", "Same as usual", custom); words on every control, "Changed" marks with Undo, a discard
    question, phone and website checked as typed, the exact OSM text in a fold-out.

25. **A new business in an old place** (owner, 2026-11-20, on 0.1.11). A downtown State Street
    address in Erie still has the previous business in OSM; the owner wanted to say a new business
    is there and could only leave a note. In OSM the existing object is usually changed (new name
    and type, the old business's own tags removed, the address and building kept; the history
    keeps the old one), or a second point is added when several businesses share a building.
    *Plan, built with O3:* "This is now a different business" in the edit form, with the summary
    listing every tag that goes and comes. The owner's follow-up: "My instant reaction was to tap and
    hold at a location, then 'Add a missing place', but maybe we could make it so that old info could
    be replaced by the user's input somehow?" So "Add a missing place" checks the spot itself and,
    when a business is already there, asks "My place replaced it" (the user's input takes over the
    old entry, address and building kept), "Both are here" or "It's the same place".

26. **Your changes on your map at once** (owner, 2026-11-20). "To save the user from having to wait
    for their input to make it to the new map pack, are we able to put that info they input onto
    their map right after, so they can see it like it's really there?" *Plan, built with O3:* every
    sent addition, edit, replacement and closing is drawn on the user's own map by a local layer in
    the map's own icon and label style, with the old label at that spot hidden; search finds the new
    name and not the old; the place sheet shows the user's values with one small tag ("Your edit ·
    waiting for the map update"); a closed place shows as closed; routing to an added place works.
    Stored with the saved places, nothing more sent; it steps aside when a pack has the change.

27. **"View all" place types** (owner, 2026-11-20). "I see there's an option to select business types
    like Gas / Rest stops / Food / Coffee etc. Can we add a button at the end of that list that just
    says 'View all' and shows a list of all the types with their icons? It could probably just be a
    grid list that the user can scroll." *Plan, built with O3:* a "View all" chip at the end of the
    row opens a scrolling grid of every type with its icon; a tap searches like a chip; the same grid
    is the type picker of Add a missing place.

28. **A route to a large store ends at its front** (owner, 2026-11-21, after the lead's walkthrough
    of 0.1.13). A route to a Walmart Supercenter in Erie ended on the service road behind the
    building: "Arrived, on the right", 121 m from the entrance. The owner: "the goal is for it to
    route to the front of the Walmart, right? Not just the parking lanes further away from it?"
    *Cause:* a place of the search pack that is an area uses its centroid, and for a large store
    that is the middle of the roof, 60 m or more from every lane; item 18's rule takes the lane
    nearest to it, which is as often the one behind the building as the one along its front.
    *Built (2026-11-21):* the search pack carries a front door for a large building (its centroid
    15 m or more inside its outline): a mapped main entrance, else another mapped customer
    entrance, else for a shop the point of its outline on the side its customer parking is on
    (parking areas and parking aisles within 60 m; `packbuilder/search/doors.py`, the pack's
    `poi_door` table). The trip planner looks for the lot around that door, and there a plain
    service road counts too, because the lane along a store's front is often mapped as one
    (`presets.json` `every_request.lot.door_uses`), and the way to it may be slower than the
    street end by 90 s plus the walk it saves (`tie_check.door_walk_m_per_s`), because the lot of
    a large store is large. A small building, and a pack built before 2026-11-21, behave as before. Measured in `docs/experiments/33-front-doors.md`: 2,098 doors in
    Pennsylvania; the route ends within 30 m of the door in 92 % of the plans to a shop (38 %
    before). *Not covered:* a business mapped as a point inside a large building (a pharmacy in a
    supermarket, a shop in a mall) keeps its own point; a campus (a school, a hospital, a park) is
    not a building and keeps its centroid.

29. **The cleanup list after the walkthrough of 0.1.13** (owner, 2026-11-21: "Agree with all").
    *Built in 0.1.15:* category and brand results nearest first; the same places in the list and on
    the map after Enter; chips and "View all" types straight to the map; Back from a type's results
    to the grid; the search words stay in the map's bar with an X; the States button off the map;
    "No map for this area. Add a state." outside the installed states; a dropped pin has the pin
    icon; "Frame rate" and "Guidance sound" out of Settings; no list of states to add while
    downloads are off; monuments and statues are not "already on the map here"; hours in plain
    words in the upload summaries; "Canceled"; the same note is not added twice; catalog signing
    written into `docs/packs-design.md` as a requirement of step P2. *Not built, with the reason:*
    an intersection search ("State St and 12th") and a street's distance to its nearest point both
    need the streets' lines in the search pack (an entry has one point today): a pack change, for a
    later batch; the nearest address or street for a dropped pin needs a reverse lookup the packs do
    not have yet (the same pack change, or a call to the routing engine); "None in Edinboro.
    Nearest:" and the leftovers of earlier reviews are still open. (The three that needed the
    streets' lines are item 31.)

30. **Store entrances tagged as driveways** (owner, 2026-11-21, on a route to a supermarket that went
    round the back of the building instead of turning in at the signal). Cause, found with the
    engine on the PC: ten metres of the entrance lane were tagged `service=driveway` in
    OpenStreetMap; Valhalla marks every driveway "destination only" and will not drive through one
    while any other way exists, so the signalised left turn was closed to every route. The owner
    fixed that piece in OpenStreetMap the same day. Pennsylvania has 214,435 open driveways; 7,676
    join two other roads. The owner chose **lot entrances only**: a driveway open to cars whose two
    ends are on other drivable ways (not one and the same way), at least one of them a lane of a lot
    (`highway=service` with no `service` tag, or `service=parking_aisle`), goes into the routing
    graph's input as a plain service road (`packbuilder/routing/driveways.py`; 4,112 ways in the
    2026-11-14 extract; 47 s). Driveways that join two streets (an alley counts as a street) stay
    closed: many are real private driveways. The map and the search data keep OpenStreetMap's tags.
    Accepted (review, 2026-11-21): a plain `highway=service` is also how some long private drives
    are tagged, so a driveway stub onto one is opened too; a route still pays the service-road
    penalty there and such a drive rarely joins two public roads. Gates and bridges are not looked
    at here (Valhalla reads barriers itself).
    *Measured on a trial graph (300 plans to 150 large buildings, with their doors):* 21 plans
    changed; 17 faster (by up to 68 s), 2 the same within 3 s, 1 slower by 32 s to the same end, 1
    slower by 40 s because it now ends in the lot 21 m from the door instead of on the street 67 m
    away. *What it does not do:* on the owner's own route the engine still turns in at the other
    entrance. Through the signal is 18 s faster by the clock (193 s against 211 s) but costs the
    engine a little more (389 against 374: a left turn at the lights, one more named road), so it is
    a near tie the engine decides the other way; inside the lot the route now goes round the other
    side of the building. A change to how near ties are decided is a routing question for a later
    batch, not part of this rule.

31. **The streets' lines in the search pack** (the three items of the cleanup list that needed
    them; owner, 2026-11-21: "All three"). The search pack's new table `street_line` holds each
    street entry's line (`packbuilder/search/lines.py`: its ways simplified to about 4 m, as steps
    of 1e-5 degree in varints; 305,507 streets, 2.6 million points, 9.8 MB of blobs; the
    Pennsylvania file grows from 155 MB to 168 MB). With it: **(a)** a street in a result list is as
    far away as the nearest point of its line, not as its middle (State Street from Perry Square:
    0.01 km, was the distance to the street's middle), and that nearest point is the result's
    point, so its row, its sheet, its pin and a route to it agree (seen on the emulator: with only
    the distance changed the row said one thing and the sheet another); **(b)** "state st and 12th", "peach & w 26th", "state at 12th", "us 19 and w 26th st"
    answer with the place where the two streets meet, before every other result ("State Street &
    East 12th Street"; kind street, type `intersection`); two streets typed by their own names win
    over crossings found through a road's other name; "bed bath and beyond" stays a shop; without a
    position there is usually no crossing to find (the candidates are the best-known streets of the
    state); **(c)** a dropped pin says which street it is on ("Near North Park Row, Erie") when a
    street line is within 60 m, else its coordinates as before. Rules in `ranking.json` (`crossing`,
    `near_street`). The Kotlin engine reads the lines with the same formulas as the Python reference
    (`StreetLines.kt`); the conformance reference covers the crossing queries and the street at 136
    points. A pack built before 2026-11-22 has no lines and answers as before. **(d)** (0.1.18; owner,
    2026-11-21: "go") a dropped pin also says the house number: of the address points of its street
    (the house-number file, read through the street's key as a typed house number is), the one
    nearest to the pin when it is within 40 m: "Near 10 North Park Row, Erie". No pack change (the
    lead had first said the address file lacked an index by position; going through the street
    needs none: 0.2 to 3 ms on the PC, 20 ms for the state's largest street key). *Not built:* a
    number for a pin with no street within 60 m or on a street with no address point near it (an
    estimate from the address ranges, or an index of all address points by position, a pack change).

32. **The speed limit of a road** (owner, 2026-11-21: "Build your three recommendations", after two
    real fixes that only a computer could make). The app could change places but only write notes
    about roads. Built in 0.1.19 (`docs/online-design.md` 4.7): the `maxspeed` tag of the road at a
    point, found in OSM's own map of the spot; from the report form of a point on the map, and
    while driving by a tap on the speed sign, which only keeps the picked limit in My reports
    until the driver has stopped (nothing is read or sent while driving). One tag of one existing
    way, the summary before Upload, the conflict check of every edit. *Not built:* any other road
    tag (they stay notes); a limit in km/h; telling the two carriageways of a divided road apart
    (the nearest way is taken; both usually carry the same limit and each is its own way).

33. **A second set of hours** (the same request; seen on a storage place whose gate and office keep
    different hours). Built in 0.1.19 (4.2): office, drive-through or kitchen hours beside the
    place's own, in the edit form and for a new place.

34. **Hours pasted as text** (the same request: typing a week of hours on a phone is the slow part).
    Built in 0.1.19 (4.2): "Paste hours" reads what the business publishes into the editor; a guessed
    AM or PM is flagged; a text it does not wholly understand changes nothing.

35. **The other way into a lot** (owner, 2026-11-21: "Why does Wegmans still go through the back
    road?", then "Go with your fix"). With the entrance driveway opened (item 30) the engine could
    turn in at the signal, and still did not: through the signal is 193 s and costs it 389, by the
    other entrance 211 s and 374. Both turns are at traffic lights (the lead first said the other
    one had none; OpenStreetMap has a signal there too). What differs is how the entrance is drawn:
    the turn onto a short piece of a side street, then several short lane pieces and a merge, each
    join with its own small cost, against one turn onto one road. The engine reports a cost per
    instruction, not per junction, so that is the likely cause, not a proven one; none of it is a
    setting. *Built in 0.1.20* (`docs/architecture.md` 2.11 rule 6): the planner closes the way in
    the engine chose and asks again; the other way in is taken when it is at least 10 s faster by
    the clock, costs at most 1.1 times as much and has no more U-turns. The owner's route then
    turns in at the signal (193 s); from the south it keeps the south entrance (the other way would
    be 9 s slower); after a missed turn the next plan decides again. *Measured:* 16 of 300 plans to
    large buildings change, saving 22 s in the middle. *Not done:* the lanes inside the lot are
    still the engine's (it prefers the lane round the lot to the parking aisles).

36. **Traffic lights the route passes beside** (owner, 2026-11-21, after a drive with 0.1.20: "a
    couple intersection I passed didn't have the traffic light icon"). The engine reports a light
    only when the signal is on the route's very line. The map often has it elsewhere at the same
    junction: on the cross street's side, on the other carriageway of a divided road, or a few
    metres past the point where the route turns off. *Built in 0.1.21 and the pack after it*
    (`docs/architecture.md` rule B10): the search pack carries every traffic light (`signal`
    table, 21,287 in Pennsylvania, about 0.5 MB); the app adds those within 20 m of a junction of the
    route, or within 3 m of its line, on plain road (no bridge, tunnel or motorway). *Measured*
    on 300 routes (3,857 km): the engine has a light at 3,262 junctions, the rule adds 141. A rule
    of "within 18 m of the route's line" was measured first and dropped: it added 221, a third of
    them lights of roads the route only runs beside. By a strict check (the light is on a road of
    the route or on a road that meets one at the junction) 20 of the 141 fail; the ten of those
    looked at were all lights of the same large junction, mapped on its other carriageway.
    *Limit:* a signal of a crossing for people on a side street, mapped as a traffic light within
    20 m of a junction without one, is taken for that junction's light.
    *Needs the new pack:* with an older pack the app shows the lights it showed before.
37. **The lane to take at a fork** (owner, 2026-11-21: "This turn should prompt the user to stay
    in the right lane"). A road gains a second lane just before a light and forks: the left lane
    to one road, the right lane to the other, as a sign over the road says. The engine counts the
    route's branch as straight ahead, so it gives no instruction; the lane rule (B9) stayed silent
    on purpose, because it never warns of a lane the road only gains. The lanes were in
    OpenStreetMap and in the engine's OSRM answer. *Built in 0.1.21* (rule B9,
    `LaneWarnings.forkOf`): where no lane ends but another plain road, as important as the
    route's, leaves the junction within 50 degrees of straight ahead, the banner shows the lanes
    and a line such as "Use the right lane for Mill Rd". *Measured:* 13 such junctions on 300
    routes; 1,107 junctions on them have lanes of which some are not for the route. *Limits:* two
    roads of one class that cross at less than 50 degrees count as a fork too; a fork whose lanes
    OpenStreetMap does not have gets no word (an instruction there is the engine's to give).
38. **Phone numbers in OpenStreetMap's form** (owner, 2026-11-21: "I have to manually format the
    phone numbers when I make OSM edit"). *Built in 0.1.21* (`docs/online-design.md` 4.2): a
    number typed any usual American way is written as "+1-814-555-0123", the form says so under
    the field, and a number the user did not touch is left as it was.

39. **The arrow a little behind the car, and lights only for the next 3 km** (owner, 2026-11-24, on
    the phone: "the location on screen is a little behind where I'm at in the real world"; in the
    emulator: "There's still some intersection that don't have the traffic light icon"). The arrow
    glided to each fix over the second until the next one, and a fix is already a moment old, so it
    trailed the car by about 1.5-2 s (20-40 m at 25-45 mph). The two "missing" lights (two junctions
    on East 38th St, Erie) were found by rule 36's pack lights; they
    were more than 3 km ahead of the car, and the map drew only the next 3 km. *Built in 0.1.22:* rule
    G8 (`guidance.json` `lead`): the arrow is drawn ahead of the fix by speed x (the fix's age + the
    glide), never past a turn the car slows for, never from a slow or poor fix; arrivals and the
    turn-by-turn step still go by the fix itself. Lights are drawn from the car to the route's end
    (zoomed out they still hide). *To tune on a drive:* `lead.extra_s` (0.5 since 0.1.25, after the owner's drive: "It still feels a little behind") if the arrow still
    trails, or is ahead, at a stop line.

40. **Tell the user when a state has newer map data** (owner, 2026-11-24: "Does the app let the
    user know a new pack is available (if they have online features toggled on)?" ... "Do it"). Until
    then the list of states was fetched only on the States screen, and release builds had no catalog
    address at all (fixed in 0.1.23: they read the public packs catalog). *Built in 0.1.24:* Settings,
    Online features, "Check for map updates" (on with the main switch; an older online.json takes the
    main switch's value): when the app becomes visible and the list is a day old or more, the list
    (never a pack) is fetched; an installed state with a newer pack shows the map's chip "Pennsylvania:
    new map data", which opens States; opening States marks that pack version seen (`ui.json`
    `updates_seen`). Nothing downloads by itself.
41. **App updates from GitHub, like openpilot** (owner, 2026-11-24: "Next we'll work on having app
    updates run like openpilot, pulling from GitHub when available"). The repository is public since
    2026-11-24, so the phone can read its releases without a login. Design with the owner's
    answers: `docs/app-updates-design.md`. *Built 2026-11-24 (0.1.26):* `tools/release.py`, `AppUpdate`,
    `AppUpdater`, `AppUpdates` (switch, daily check, Wi-Fi download, certificate check, install, chips);
    the install is tried on the phone with the first two releases (design section 8).

42. **Turn the map, and a compass** (owner, 2026-11-24: "allow users to rotate the map, with a compass
    in the bottom right corner above the location button ... when they click on it, it will point
    north, if they click it again, it points in the direction the phone compass is pointing (with
    some smoothing so it's not so jittery)"). *Built in 0.1.27:* rule K1a. *0.1.28* (owner, after the first
    self-update: "Maybe we should combine the compass and location button ... I think we should also add
    an install button next to the check for updates button"): my-location holds the compass (K1a), and
    Settings shows Install beside "Check for an update now" when an update is downloaded.

43. **Everyday searches that found nothing nearby** (a tester, 2026-11-24: "antique stores" found two
    shops near Philadelphia and none of Erie's). The search knew 104 hand-written categories; a search
    outside them fell back to names. *Built 2026-11-24:* `packbuilder/search/extend_categories.py` adds
    a category for every one-tag iD preset (shop, amenity, craft, office, healthcare, leisure, tourism)
    with at least 15 places in Pennsylvania that no category matched (178), with everyday forms
    (plural, with "store"/"shop"), iD's aliases and terms (minus words too general: old, used, new,
    keyboard, drum) and a few everyday words iD lacks (barber shop, nail salon, bike shop, antique
    mall). `tests/everyday_searches.json`: 20 everyday searches from Erie that must find nearby places;
    all pass on the rebuilt pack ("antique stores": eight antique shops within 7 km). Data only: the
    app reads categories from the pack, so the new search pack is the whole change. Left for later:
    plural folding in name matching, the same list from other towns.

44. **Search this area** (the owner, 2026-11-24: "When searching for parks, it searches for parks near
    the user, but when they drag the map to a different area, a button should pop up below the
    search bar that says "Search this area" that updates the pins and list with searches in that
    window"). *Built for 0.1.30:* rule S11b. With it, K1a: my-location on a map turned by hand and not
    following turns it north up where it is first; the next tap follows.

45. **Lane strip** (the owner, 2026-11-25: "When approaching an intersection that needs more
    instruction, I think we should have a small window drop down from the top ... showing what lane to
    be in"). Concepts A-C, three Sonnet design critiques (driver safety, visual design, honesty), then
    a revised design on the owner's design canvas; the owner chose "the progress bar, as drawn" over
    the car's arrow moving in a lane. *Built for 0.1.30:* rule D9b. Left for later: lanes trimmed to
    the relevant ones ("+3") on wide highways, per-lane destinations (OSM destination:street:lanes,
    e.g. Parade St at the Pine Ave fork), a swipe to hide it, the car's real lane from a comma 3X.

46. **Map style button** (the owner, 2026-11-25: "the map style section in settings could have it's own
    button and UI on the map screen"). Concept on the design canvas's "Map style" page, approved.
    *Built for 0.1.34:* a round button under Settings (not while guiding) opens a sheet of four picture
    tiles (Automatic, Same as phone, Day, Night); a tap writes the setting at once, so the map changes
    behind the sheet; Settings keeps the same section (`app/ui/MapStyleSheet.kt`).

47. **Draggable sheets for every window, and screen transitions** (the owner, 2026-11-25: "Lets make
    sure all windows have sliders / drag (if they need to be / are capable)"). Concepts on the design
    canvas ("Sheets", "Sheets, revised"), two rounds of three and two Sonnet critiques. One big release.
    *Owner's decisions:* full sheets stay usable while moving, no passenger button; the steps preview
    in the full directions window; Directions shows the drive time (the fastest route's, plain
    "Directions" when none); a map tap closes the place window; no vibration on snap.
    *Behaviour:* three heights (peek, half, full) from content (equal heights collapse into one stop);
    the handle row and header (48 dp or more) drag, a tap on them steps up one height; content drags
    the sheet only at its scroll top; a fling picks the target; it always settles on a height. Place
    window: opens at peek; closes by a deliberate pull below peek, a map tap, X or Back. Directions and
    the stops editor: never close by a drag or a map tap (peek is the floor); X or Back closes them with
    an Undo message that survives rotation. Back steps down one height. The map refits after the drag
    settles (eased), never while a finger is down, and keeps the pin or route visible. Start (and
    every primary action) stays pinned at the sheet's bottom, the same size at every height, above the
    gesture area; lists scroll above it with a faded edge. TalkBack: the handle announces its height,
    with Expand and Collapse actions; stops have Move up and Move down; removing a stop shows Undo.
    Landscape: a side panel about 40 % wide, no heights. The keyboard keeps the sheet above it. Large
    text: peek grows, never clips, titles never wrap at peek.
    *Content:* place peek = category icon, name, "Open · closes 5 PM · 1.0 mi" (a darker green, 4.5:1),
    full-width "Directions · 6 min", Save as an icon at the title's right; half = icon rows (pin,
    clock, phone, globe; one outline icon set, 24 px, 2 px stroke) and Call / Website with icons; full
    = the week's hours ("Show all hours"), a quiet footer (Report a problem, Edit on OpenStreetMap).
    Directions peek = "11:08 PM" large, "Erie Zoo · 8 min · 2.9 mi", a one-line "2 routes" chip;
    half = route cards with a big time, "Fastest" and "+1 min", the chosen card filled; full =
    From / To rows with a dot-line-pin connector and swap, "Add a stop" as a row, option chips filled
    with a check when on, and the steps preview (real maneuver icons, lane icons where they matter,
    48 dp rows, "Show all N steps"; a tap zooms the map to the step and lowers the sheet to half).
    Stops editor (the trip bar's list button) and the arrival card (Done primary, Save as a link;
    drags up to the place's details) follow the same rules; the pickers (time, speed, place type)
    become small sheets; Search, Settings, States, Saved places, Report / Add / Edit place and About
    slide in and out; the search bar grows into the search screen and back.
    *Style:* handle 40 x 4 px, 8 px from the top, mid-grey at 3:1 or more in both themes; 24 px top
    corners on every sheet; 16 px side padding, 16 px between groups; title 22 semibold, key figure 28
    bold, body 16, secondary 14 muted, section labels 13 medium in sentence case; primary 52 px filled
    pill, secondary grey-filled with an icon, tertiary text links, no heavy outlined buttons; one blue
    for primary and selection, green only for open, red only for closed and destructive.
    *Started 2026-11-25 (not released):* every sheet built on `SheetFrame` (results, place,
    directions) slides between heights instead of jumping (`animateContentSize`, no bounce;
    ui/theme Sheets.kt, frozen: hash re-recorded), and the camera takes new insets only once they
    have settled for 180 ms (`AppRoot` INSETS_SETTLE_MS), so the map refits after a slide. Then: the handle
    40 x 4 dp in each sheet's muted text colour; the place window opens at peek with its status line,
    its main button and Save (SheetHeightsScreensTest updated, not yet run on the emulator). Left:
    the directions window's three heights opening at half (about ten drive screen tests assume it
    opens at full: update them with it), and everything else in this item. *2026-11-26:* the
    directions window has three heights (peek: arrival, destination, "2 routes", Start; middle: the
    cards and Start, where it opens under the map; full in the side panel), the steps preview
    (`RouteChoice.steps` from the banners, `RouteSheetUi.steps`, `DriveEvent.ShowStep` looks at the
    step); all 18 drive screen tests pass on the emulator (the lane counter now counts lanes only,
    the strip's progress bar is not one). Left: the drive time on Directions, the stops editor,
    arrival card and pickers on the sheet frame, the screen transitions, the style rulebook pass.

48. **Every state's packs, built on GitHub's machines only** (the owner, 2026-11-25/26: "I'm just
    trying to think of a way we can have github machines handle all the workflow"). Today
    `weekly.py` builds the states one after another in one job (5 h 45 min cap); 50 states would not
    fit. *Plan:* one workflow with a matrix, one job per state in parallel (`max-parallel` about 8:
    20 jobs at once per account, and gentle on Geofabrik), `fail-fast: false`; a state that fails
    keeps its previous pack; then one publish job that gathers the built packs, checks them and
    writes `catalog.json` (and `comma.json`) once. `weekly.py` gains a build-one-state mode and a
    separate publish step. Each job keeps the disk freeing and swap (about 60-70 GB, 16 GB RAM).
    A state too big for one job splits by part (map, routing, search, addresses, each its own job,
    files passed through the store), never a self-hosted runner. *First:* a one-off California
    trial on a GitHub machine measuring time, peak memory, disk and file sizes (estimated 2-3 h,
    about 10 GB for the search build; Pennsylvania: about 30 min, 3 GB). *Watch:* release files
    are at most 2 GB each (a big state's routing file may need parts); Geofabrik outages (per-address
    logging is live, openstreetmap.fr region files as a fallback if they repeat). *Order:*
    Pennsylvania and Ohio first (both in the config), then New York, New Jersey, Maryland,
    Delaware, West Virginia, then the rest. Changes the packs repo only (exported from `packs-repo/`).
    *Also queued:* the next app release carries silent updates (3ef1e0d, the owner's approval
    2026-11-26); a map-data check on the first start after a self-update; the Parade St lane-tag fix
    in OpenStreetMap (shown to the owner first); lane strip extras ("+3" trimming, per-lane
    destinations, swipe to hide); search: plural folding in names, the everyday list from other
    towns, more than 20 pins or thinning when zoomed out.

## Later (not the next round)

- **Linking Beamline to the real vehicle through the comma 3X** (owner, 2026-11-16). The driving
  arrow's headlight beam and brake lenses are guesses today: the beam shows with the night map, and
  the lenses light when the position stops moving. With the car's own signals they would show what
  the car is doing: brake lights when the brake is pressed, the beam when the headlights are on.
  The same link could give more: the car's own speed and standstill in place of GPS guesses (a
  steadier arrow, guidance through tunnels), turn signals, reverse gear. To find out when the 3X
  work starts: which of these signals openpilot exposes for the owner's car, and how the phone or
  the 3X build of the app would read them. Keep the arrow's inputs (beam on, brakes on, speed) as
  plain values in the map scene, as they are now, so that a vehicle source can feed them later
  without a redesign.
