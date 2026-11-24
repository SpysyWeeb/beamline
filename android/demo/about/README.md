# :demo:about

Host app for track A's device checks of `:feature:about` (not shipped; application id
`io.github.spysyweeb.beamline.aboutdemo`, so it installs beside the real app). It plays the
start-up order of docs/architecture.md 5.4 item 3 over a stand-in map: system splash,
`SplashReveal` until "map ready", the first-run notice until accepted (kept in the demo's own
preferences), then a menu: About (made-up but realistic content), Licences (the module's real
notices file), the driver notice, and the icon's layers under circle, squircle and rounded masks
with the themed (monochrome) preview. Uses `:feature:about`'s launcher icon and launch theme.

```
sh tools/round/gradle.sh <key> :demo:about:assembleDebug
sh tools/round/adb.sh <key> install -r data/gradle/<key>/build/demo/about/outputs/apk/debug/about-debug.apk
sh tools/round/adb.sh <key> shell am start -n io.github.spysyweeb.beamline.aboutdemo/io.github.spysyweeb.beamline.about.demo.DemoActivity [extras]
```

Extras: `--es screen about|licences|notice|icons` (open that screen, no reveal), `--ez night true|false`,
`--ez reset_notice true` (forget the accepted notice), `--ei map_ready_ms N` (default 900),
`--ei hold_ms N` (keep the system splash N ms). A start from the launcher icon is the real cold
start.
