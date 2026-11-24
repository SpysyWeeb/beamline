# :routing:packs

Plain Kotlin/JVM. Owner in the feature round: track P (compiled by N's `:routing:demo`: keep the
public API source-compatible). `RoutingPackManager` keeps the one merged Valhalla tile set of all
installed states (`routing.tar`, `valhalla.json`, `packs/<state>.json`) with the rules of
`packbuilder/routing/packs.py`: one graph build, tiles on a state line stored once, per-tile
SHA-256, Valhalla version checked in every tile header, an atomic swap with a commit marker and
`recover()` after a crash. `TileTar` reads and writes the tile tar with its `index.bin`.

Used by `:core:installed` (`InstalledLibrary`), which merges a pack's staged `routing.tar` with
`verifyTarHash = false` (the part was hashed when it was staged; the tiles are hashed here) and
deletes it afterwards.

Round 3 (after the review, additive API only): every refusal is a `PackException` with a `kind`
(`GRAPH_BUILD`, `ENGINE_VERSION`, `NO_SPACE`, `DAMAGED`, `OTHER`), so the installer words it for
the user without showing the engineer's message; an installed `packs/<state>.json` that cannot be
read no longer stops `installed()` (the state is left out and named by `unreadable()`), `uninstall`
removes such a state (the set is rewritten from the readable states), and any install rewrites the
set without it; `hasPendingChange()` says whether `recover()` has work, without doing it.

Tests: `sh tools/round/gradle.sh p :routing:packs:test --rerun -Pbeamline.routingPacksWork=<abs repo>/data/r3-p/routing-packs-test`
(the real-pack tests read `data/routing/packs` and write about 1.8 GB of test devices into the
work folder, deleted afterwards; without the packs they are skipped). `PackRulesTest` (13) runs on
small synthetic packs.
