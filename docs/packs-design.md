# Publishing packs, and county packs for road trips: design (items 23 and 6)

Status: **agreed with the owner, 2026-11-20 (answers in section 8). Nothing here is built.** Written by the lead from
docs/next-round.md items 6 and 23, docs/pack-format.md, docs/catalog-format.md,
docs/architecture-downloads.md and the pack builders' READMEs. The owner's answers so far are built
in (section 1); the remaining questions are at the end.

## 1. What this is, and what is already decided

**Item 23.** A separate repository whose whole job is to build the state packs from fresh
OpenStreetMap data and publish them, so the app can download and update them by itself.

**Item 6.** Later, the same repository also builds **county packs**, a nationwide **main-roads set**
and a small nationwide **place index**, so a road trip needs only the counties along the route.

The owner's answers (2026-11-20):

| Question | Answer |
|---|---|
| Public or private repository | **Private for now, public later.** First answer: public. Then (2026-11-20): "Maybe we can leave both repos private until we're done building our items." Both repositories (the app's and the packs') stay private until the items are built, then both become public, after a licence is chosen and the history checked. |
| How often | **Weekly**. The app offers each new build; the user chooses when to download it. |
| Which states first | **Pennsylvania and Ohio**, the two built today. More states once the setup runs smoothly. |

**While the packs repository is private** (until both go public):
- the weekly build runs on GitHub's runner for private repositories (2 cores, 7 GB of memory, about
  14 GB of free disk; 2,000 free minutes a month, of which weekly PA and OH builds use an estimated
  250 to 700); if the build needs more memory, this PC runs it as a self-hosted runner until the
  repository is public (step P1a measures it);
- **only Pennsylvania is built and published** (owner, 2026-11-20); Ohio is added when the
  repository goes public. Pennsylvania's routing is then built from its own extract rather than
  together with Ohio (P1a measures what that changes near the state lines);
- release files of a private repository need a login to download, so **the app's own downloads
  (step P2) wait until it is public**; until then the lead fetches each new build and copies it to
  the phone, and the owner installs it with "Use a file instead", as today.

## 2. What exists already

Most of the pieces are built. They run on this PC today.

| Piece | Today | Made by |
|---|---|---|
| Map part | Planetiler 0.10.2 with the Protomaps profile (Java), one PMTiles file per state | `packbuilder/map/build_pack.py` |
| Routing part | Valhalla 3.9.1 from the `pyvalhalla` wheel (runs on Windows and Linux); one graph for PA and OH, cut into per-state parts | `packbuilder/routing` |
| Search part | the SQLite index (batch F: beaches, waterfalls, peaks) | `packbuilder/search/build.py` |
| House numbers | OSM addresses with the public-domain National Address Database and Census TIGER files | `packbuilder/addresses/run.py` |
| The pack | format 3: a `.tar` with a manifest; refuses parts of different OSM dates | `packbuilder/pack/assemble.py` |
| The catalog | one folder per pack build plus `catalog.json`, **laid out like GitHub Releases on purpose**: the same catalog works from the PC's test server and from GitHub | `packbuilder/catalog/build_catalog.py` |
| In the app | the downloader (resume, checks every file's SHA-256), the States screen with **"Update available · data from … · size"** and **Update** | batch G of round 3 |

So the new work is mostly **running these on GitHub's machines every week and publishing the
result**, plus switching downloads on in the app.

## 3. The packs repository

- **Name:** `beamline-packs` (question 1), public, under the owner's GitHub account.
- **What it holds:**
  - the pack builders (`packbuilder/`), copied over from the app repository;
  - one workflow file, the weekly build;
  - a README that says what the packs are, their licence and where they come from.
- **Where the packs go:**
  - every pack build becomes **one GitHub Release** (tag `us-pa-202611230400`, for example), holding
    the pack's files;
  - the release tagged `catalog` holds `catalog.json`, which the app reads.
  - This is the layout `docs/catalog-format.md` already describes. A published release is never
    rebuilt; a newer build is a new release.
- **How long builds are kept:** the newest two builds of each state (question 2). The catalog names
  only the newest, and the one before stays a week longer for phones still downloading it.
- **Licences, written in each release and in the README:**
  - map data © OpenStreetMap contributors, under the ODbL, which allows sharing the packs openly
    with that credit;
  - house numbers from public-domain US sources (National Address Database, Census TIGER);
  - the tools' own licences, already listed by the builders.

## 4. The weekly build

A GitHub Actions workflow on a schedule, once a week (question 3 picks the day and hour), plus a
"run now" button for the owner.

1. **Fetch the inputs.**
   - The state extracts from Geofabrik, refreshed daily: one file per state, checked against its
     MD5.
   - The slower-changing inputs: the National Address Database, the Census files, water polygons,
     time zones. These are fetched only when their publisher has a newer file, and kept between runs
     in a release of their own, so the weekly build does not download several gigabytes each time.
2. **Build every part, one state per job:**
   - map, search and house numbers per state;
   - routing for Pennsylvania and Ohio together, then cut per state, as today.
   - Each job runs on GitHub's standard Linux machine (4 cores, 16 GB of memory, about 14 GB of
     free disk; more disk after removing preinstalled tools), within GitHub's 6-hour limit per job.
3. **Check before publishing.** Nothing is published unless all of these pass:
   - the pack reads cleanly (`read_pack.py --verify`);
   - the search conformance tests pass;
   - a fixed set of test routes plans;
   - the pack is not much smaller than last week's, which catches a broken input.
4. **Publish:**
   1. one new release per state;
   2. then the catalog is rebuilt and replaced in one step, so a phone never sees a catalog that
      names files not yet uploaded;
   3. then builds older than the newest two are deleted.
5. **Report:** a short summary on the workflow page (sizes, how long each step took, what changed
   from last week). If anything fails, the owner gets GitHub's failure e-mail, and the published
   packs stay as they were.

**GitHub's limits to keep in mind:**
- **2 GB per file.** Each part of a pack (map, routing, search, house numbers) is already its own
  file in the catalog, so a state fits as long as each part is under 2 GB; Pennsylvania's and Ohio's
  parts are well under. For the largest states (California, Texas) a part may need to be split into
  pieces, which the format would gain when those states come (step P3).
- **Scheduled runs pause after 60 days without activity** in a public repository. The weekly catalog
  update keeps it active.
- **No promised bandwidth.** Downloads of release files are free and have been reliable for projects
  of this kind, but GitHub does not guarantee them. Fine for the owner's own use; to be looked at
  again before publishing the app.

**First build time,** estimated from the PC: about 30 to 60 minutes per state. Measured on the first
real run.

## 5. In the app

- **Downloads switched on in the release build.** The catalog address becomes the packs
  repository's `catalog` release. Today a release build has downloads off, which is why packs come
  from a file.
- **The network rule (N2) gains GitHub's hosts.** GitHub answers a release download with a redirect
  to its file servers. The catalog's redirect rules (catalog-format section 2.4) already expect
  this. The rule names the exact hosts, so nothing else can be reached.
- **"Offline only" is unchanged.** State downloads were always the one thing it allows, and only
  when the user starts them.
- **How a phone learns of an update.** By the network rule (N4) the app never checks in the
  background. When the user opens the States screen, the catalog is read and a state with a newer
  build shows **"Update available · data from … · size"** with **Update**, as built in round 3.
  Question 4 asks whether "Allow online features" should also let the app check once when it opens,
  and show a small notice.
- **A signed catalog (a requirement of step P2; the audit of 2026-11-21).** Today the app trusts
  the catalog because it comes over HTTPS from the one address built into the app, and trusts each
  file because its SHA-256 is in the catalog. That leaves the GitHub account as the single point:
  whoever can publish a release there can hand every phone a pack. Before downloads are switched on
  in a release build, the weekly build signs `catalog.json` (an Ed25519 signature in a second file,
  `catalog.json.sig`; the private key a repository secret, never in the repository), the app carries
  the public key, and the catalog reader refuses a catalog whose signature is missing or wrong, the
  same way it refuses a wrong checksum today. The file checksums in the catalog then cover the
  packs. A key change goes out with an app update that knows both keys for a while.
- **Sizes.** A whole new Pennsylvania pack is about 1.3 GB each time. Smaller weekly updates are a
  later improvement: the parts are separate files with their own checksums, so an update could skip
  a part that did not change, and later still only the changes could be sent.

## 6. County packs for road trips (item 6, later)

This is the second stage, built on the same repository and the same weekly run. Item 6 in
docs/next-round.md has the full reasoning; in short:

- **Counties as the download unit for trips.** Pennsylvania's 1.3 GB over 67 counties averages about
  20 MB a county. "Download this route" fetches the counties a trip passes through, and their
  neighbours, instead of whole states.
- **A nationwide main-roads set** (motorways and major highways only), so the long parts of a trip
  can be planned anywhere.
- **A nationwide place index** (cities, towns, ZIP codes, counties: a few megabytes), so the app can
  say which county a typed address is in and offer it.
- **What the builder adds:**
  - county parts cut from each state's build (map tiles clipped to the county, its search entries
    and house numbers);
  - the routing squares that cover it (they do not follow county lines, so neighbours share some);
  - the main-roads set and the place index, built once for the country.
  - About 3,100 counties: organised by state (one release per state build holding all its
    counties), they stay within GitHub's limits.
- **The hard part is on the phone.**
  - **Drawing:** dozens of small county files would slow the map, so the app joins the installed
    counties into one map store.
  - **Search:** looks in one joined index, not many small ones.
  - **Routing:** shared routing squares are kept once.
  - **Whole states:** a whole state becomes "all its counties" or stays a separate pack (question
    5).
  - This needs its own detailed design when its turn comes, after the weekly state builds run.
- **Depends on:** the data build for more states (put off on 2026-11-16) and the main-roads set.

## 7. Order of work

| Step | Contents | Where |
|---|---|---|
| P1 | The packs repository, the builders running on GitHub's Linux machines for PA and OH, the weekly workflow with its checks, publishing to Releases and the catalog | the new repository |
| P2 | Downloads on in the app's release build, pointed at the new catalog; the network rule's GitHub hosts; update offers from the States screen | the app |
| P3 | More states (neighbours first); for the largest states, big parts split into pieces | the new repository |
| P4 | County packs, the main-roads set and the place index (item 6), with their own design first | both |

P1 and P2 together are about one batch's size each. Making the repository public, and creating it
under the owner's GitHub account, are the owner's steps; the lead walks through them. The first run
is watched together.

## 8. The owner's answers (2026-11-20)

1. **Name:** `beamline-packs`, public, under the owner's GitHub account.
2. **Old builds:** the newest two builds of each state stay published.
3. **When:** weekly, early Monday morning (about 4 AM US Eastern).
4. **Update notice:** yes, with "Allow online features" on, the app reads the catalog once when it
   opens and shows a small notice such as "A newer map of Pennsylvania is available"; never in the
   background, never while guiding. With "Offline only", updates show only on the States screen.
   This check belongs to the online switch (an online feature that makes an existing feature
   better), so it is gated like the others and named in what the app sends: nothing but the catalog
   request.
5. **Counties as the only kind of pack, or next to whole-state packs:** left for the county design
   (step P4).
