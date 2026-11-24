"""Checks of the network rule (docs/architecture-downloads.md 4.2 items 1 and 2). Standard library only.

    python android/scripts/check_network_rule.py manifest <merged AndroidManifest.xml> --variant debug|release [--host app|demo]
    python android/scripts/check_network_rule.py scan [--root android]
    python android/scripts/check_network_rule.py privacy

manifest  The merged manifest's permissions are exactly the app's list (ACCESS_COARSE_LOCATION,
          ACCESS_FINE_LOCATION, FOREGROUND_SERVICE, FOREGROUND_SERVICE_LOCATION,
          FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS, INTERNET, ACCESS_NETWORK_STATE, WAKE_LOCK)
          plus permissions defined under the app's own id (androidx core's
          <id>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION); never ACCESS_WIFI_STATE,
          RECEIVE_BOOT_COMPLETED, ACCESS_BACKGROUND_LOCATION or REQUEST_IGNORE_BATTERY_OPTIMIZATIONS; no
          android:usesCleartextTraffic; a networkSecurityConfig only in the debug variant; exactly two
          services with a foreground type, both not exported (GuidanceService location,
          DownloadService dataSync). --host demo checks :demo:states instead: the downloader's
          permissions only and the one DownloadService. For :app the merged manifest is
          app/build/intermediates/merged_manifest/<variant>/process<Variant>MainManifest/AndroidManifest.xml
          under the build root (I confirms the path for AGP 9.5).
scan      A lint over android/**/src/main/**/*.{kt,java} (build folders excluded): no network API outside
          the four files allowed to use one (PackHttp.kt, TaggingSocketFactory.kt, OfflineMapHttp.kt, and
          since batch O1 the app's OsmHttp.kt: one class per service, docs/architecture-downloads.md 4.1 N1).
          It catches a slip in our own sources; it cannot see dependencies.
privacy   Lists the git-tracked files and the untracked ones git does not ignore (git ls-files,
          read-only) and fails on any private IPv4 literal (RFC 1918: 10/8, 172.16/12, 192.168/16) other
          than 10.0.2.2 and the documentation examples 192.168.200.20 and 10.200.0.5, so the home
          network's address never reaches a commit. A network written with its prefix length
          (10.0.0.0/8) is not an address and passes; loopback and link-local literals are not home
          addresses and pass.
Exit code 0 when clean, 1 on a finding, 2 on a usage error.
"""
from __future__ import annotations

import argparse
import ipaddress
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

APP_PERMISSIONS = {
    "android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_LOCATION",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC", "android.permission.POST_NOTIFICATIONS",
    "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.WAKE_LOCK",
}
DEMO_PERMISSIONS = {
    "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
    "android.permission.POST_NOTIFICATIONS", "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.WAKE_LOCK",
}
NEVER = {
    "android.permission.ACCESS_WIFI_STATE", "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.ACCESS_BACKGROUND_LOCATION", "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
}
APP_SERVICES = {"GuidanceService": "location", "DownloadService": "dataSync"}
DEMO_SERVICES = {"DownloadService": "dataSync"}

PATTERNS = [
    r"\bokhttp3\.",
    r"\bjava\.net\.(URL|URLConnection|HttpURLConnection|Socket|ServerSocket|DatagramSocket|InetAddress|Inet4Address|Inet6Address)\b",
    r"\bimport\s+java\.net\.\*",
    r"\bimport\s+javax\.net\.\*",
    r"\.toURL\(\)",
    r"\.openStream\(\)",
    r"\bjava\.nio\.channels\.(SocketChannel|DatagramChannel|ServerSocketChannel)\b",
    r"\bjava\.net\.http\.",
    r"\bjavax\.net\.",
    r"\bandroid\.net\.http\.",
    r"\borg\.chromium\.net\.",
    r"\bio\.ktor\.",
    r"\bDownloadManager\b",
    r"\bWebView\b",
    r"\bFerrostarCore\b",
    r"\bHttpClientProvider\b",
    r"\bRuntime\.getRuntime\(\)\.exec\b",
    r"\bProcessBuilder\b",
    r"setConnected\(\s*true",
]
# The four files allowed a network API, and which of the patterns each may use (N1: one class per service).
ALLOWED = {
    "core/download/src/main/kotlin/io/github/spysyweeb/beamline/download/net/PackHttp.kt":
        [r"\bokhttp3\.", r"\bjavax\.net\.", r"\bjava\.net\.(URL|URLConnection|HttpURLConnection|Socket|ServerSocket|DatagramSocket|InetAddress|Inet4Address|Inet6Address)\b"],
    "feature/states/src/main/kotlin/io/github/spysyweeb/beamline/feature/states/TaggingSocketFactory.kt":
        [r"\bjava\.net\.(URL|URLConnection|HttpURLConnection|Socket|ServerSocket|DatagramSocket|InetAddress|Inet4Address|Inet6Address)\b", r"\bjavax\.net\."],
    "app/src/main/kotlin/io/github/spysyweeb/beamline/app/OfflineMapHttp.kt": [r"\bokhttp3\."],
    # Batch O1 (docs/online-design.md section 6): OSM notes, its switch checked before every request;
    # batch O2: also the sign-in's token and revoke requests, the user's name and the requests of an edit.
    "app/src/main/kotlin/io/github/spysyweeb/beamline/app/online/OsmHttp.kt":
        [r"\bokhttp3\.", r"\bjavax\.net\.", r"\bjava\.net\.(URL|URLConnection|HttpURLConnection|Socket|ServerSocket|DatagramSocket|InetAddress|Inet4Address|Inet6Address)\b"],
}
# PackHttp and OsmHttp may name InetAddress (their resolvers); TaggingSocketFactory only Socket (and InetAddress in the refused overloads).
ALLOWED_NAMES = {
    "app/src/main/kotlin/io/github/spysyweeb/beamline/app/online/OsmHttp.kt": {"InetAddress"},
    "core/download/src/main/kotlin/io/github/spysyweeb/beamline/download/net/PackHttp.kt": {"InetAddress"},
    "feature/states/src/main/kotlin/io/github/spysyweeb/beamline/feature/states/TaggingSocketFactory.kt": {"Socket", "InetAddress"},
}

# Batch O2 (N2): the only web addresses OsmHttp.kt may name: OSM's test server, the API and the sign-in host of
# openstreetmap.org. Any other https:// or http:// literal in that file is a finding.
OSM_FILE = "app/src/main/kotlin/io/github/spysyweeb/beamline/app/online/OsmHttp.kt"
OSM_HOSTS = {"master.apis.dev.openstreetmap.org", "api.openstreetmap.org", "www.openstreetmap.org"}
URL_LITERAL = re.compile(r'"(https?)://([^/"$:]+)')

ALLOWED_IPS = {"10.0.2.2", "127.0.0.1", "192.168.200.20", "10.200.0.5"}
RFC1918 = [ipaddress.ip_network(n) for n in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")]
IPV4 = re.compile(r"(?<![0-9.])((?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?:\.(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3})(?![0-9])(/\d{1,2})?(?!\.\d)")


def check_manifest(path: Path, variant: str, host: str) -> list[str]:
    problems = []
    root = ET.parse(path).getroot()
    package = root.get("package") or ""
    defined = {p.get(ANDROID_NS + "name") for p in root.findall("permission")}
    used = {p.get(ANDROID_NS + "name") for p in root.findall("uses-permission")} | \
           {p.get(ANDROID_NS + "name") for p in root.findall("uses-permission-sdk-23")}
    allowed = APP_PERMISSIONS if host == "app" else DEMO_PERMISSIONS
    own = {p for p in used if p in defined or (package and p.startswith(package + "."))}
    for p in sorted(used - allowed - own):
        problems.append(f"permission not on the list: {p}")
    if host == "app":
        for p in sorted(APP_PERMISSIONS - used):
            problems.append(f"permission missing: {p}")
    for p in sorted(used & NEVER):
        problems.append(f"permission never allowed: {p}")
    app = root.find("application")
    if app is not None:
        if app.get(ANDROID_NS + "usesCleartextTraffic") is not None:
            problems.append("android:usesCleartextTraffic is set")
        nsc = app.get(ANDROID_NS + "networkSecurityConfig")
        if variant == "release" and nsc is not None:
            problems.append("a networkSecurityConfig in the release variant")
        if variant == "debug" and nsc is None:
            problems.append("no networkSecurityConfig in the debug variant")
        want = APP_SERVICES if host == "app" else DEMO_SERVICES
        typed = {}
        for s in app.findall("service"):
            t = s.get(ANDROID_NS + "foregroundServiceType")
            if t is None:
                continue
            name = (s.get(ANDROID_NS + "name") or "").rsplit(".", 1)[-1]
            typed[name] = (t, s.get(ANDROID_NS + "exported"))
        if set(typed) != set(want):
            problems.append(f"services with a foreground type: {sorted(typed)}, want {sorted(want)}")
        for name, t in want.items():
            if name in typed:
                if typed[name][0] != t:
                    problems.append(f"{name} has foregroundServiceType {typed[name][0]}, want {t}")
                if typed[name][1] != "false":
                    problems.append(f"{name} must be android:exported=\"false\"")
    else:
        problems.append("no <application> element")
    return problems


def scan(root: Path) -> list[str]:
    hits = []
    compiled = [re.compile(p) for p in PATTERNS]
    for f in sorted(root.rglob("*")):
        if f.suffix not in (".kt", ".java") or not f.is_file():
            continue
        rel = f.relative_to(root).as_posix()
        parts = rel.split("/")
        if "build" in parts or "src" not in parts or parts[parts.index("src") + 1] != "main":
            continue
        allowed = [re.compile(p) for p in ALLOWED.get(rel, [])]
        names = ALLOWED_NAMES.get(rel, set())
        for n, line in enumerate(f.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            for c in compiled:
                for m in c.finditer(line):
                    if any(a.pattern == c.pattern for a in allowed):
                        sub = m.group(1) if c.groups else None
                        if sub is None or sub in names:
                            continue
                    hits.append(f"{rel}:{n}: {m.group(0)}")
            if rel == OSM_FILE:
                for m in URL_LITERAL.finditer(line):
                    if m.group(1) != "https" or m.group(2) not in OSM_HOSTS:
                        hits.append(f"{rel}:{n}: {m.group(0)}\" (not a fixed OSM host, N2)")
    return hits


def privacy() -> list[str]:
    try:
        out = subprocess.run(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=REPO,
                             capture_output=True, check=True).stdout
    except (OSError, subprocess.CalledProcessError) as e:
        raise SystemExit(f"git ls-files failed: {type(e).__name__}")
    hits = []
    for name in out.decode("utf-8", "replace").split("\0"):
        if not name:
            continue
        p = REPO / name
        try:
            data = p.read_bytes()
        except OSError:
            continue
        if b"\0" in data[:8192]:
            continue  # binary
        text = data.decode("utf-8", "replace")
        for n, line in enumerate(text.splitlines(), 1):
            for m in IPV4.finditer(line):
                ip, prefix = m.group(1), m.group(2)
                if prefix or ip in ALLOWED_IPS:
                    continue
                a = ipaddress.ip_address(ip)
                if any(a in net for net in RFC1918):
                    hits.append(f"{name}:{n}: {ip}")
    return hits


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    m = sub.add_parser("manifest")
    m.add_argument("path", type=Path)
    m.add_argument("--variant", choices=["debug", "release"], required=True)
    m.add_argument("--host", choices=["app", "demo"], default="app")
    s = sub.add_parser("scan")
    s.add_argument("--root", type=Path, default=REPO / "android")
    sub.add_parser("privacy")
    a = ap.parse_args(argv)
    if a.cmd == "manifest":
        problems = check_manifest(a.path, a.variant, a.host)
        for p in problems:
            print(p)
        print(f"manifest ({a.host}, {a.variant}): {len(problems)} problem(s)")
        return 1 if problems else 0
    if a.cmd == "scan":
        hits = scan(a.root)
        for h in hits:
            print(h)
        print(f"scan: {len(hits)} finding(s)")
        return 1 if hits else 0
    hits = privacy()
    for h in hits:
        print(h)
    print(f"privacy: {len(hits)} private IPv4 literal(s) in tracked files")
    return 1 if hits else 0


if __name__ == "__main__":
    sys.exit(main())
