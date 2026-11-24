"""Command line search over a built pack.

    python packbuilder/search/cli.py "walmart erie"
    python packbuilder/search/cli.py "gas" --near 42.129,-80.085
    python packbuilder/search/cli.py "mcdonalds on peach st" --at erie --json
    python packbuilder/search/cli.py --interactive --at pittsburgh

--at takes a named test point (see tests/points.json) instead of coordinates.
"""
from __future__ import annotations

import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
import query  # noqa: E402


def load_points() -> dict:
    with open(os.path.join(HERE, "tests", "points.json"), encoding="utf-8") as f:
        return json.load(f)["points"]


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("text", nargs="?")
    ap.add_argument("--db", default=os.path.join(REPO, "data", "search", "pa-search.sqlite"))
    ap.add_argument("--addresses", default=os.path.join(REPO, "data", "addresses", "pa-addresses.sqlite"))
    ap.add_argument("--near", help="lat,lon of the user")
    ap.add_argument("--at", help="named point from tests/points.json")
    ap.add_argument("--limit", type=int, default=10)
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--interactive", action="store_true")
    ap.add_argument("--no-addresses", action="store_true", help="do not attach the addresses database")
    ap.add_argument("--allow-addr-version-mismatch", action="store_true",
                    help="attach the addresses database even if its normalisation version differs")
    args = ap.parse_args()
    lat = lon = None
    if args.near:
        try:
            lat, lon = (float(x) for x in args.near.split(","))
        except ValueError:
            ap.error("--near takes lat,lon (two numbers)")
    elif args.at:
        pt = load_points()[args.at]
        lat, lon = pt["lat"], pt["lon"]
    try:
        eng = query.SearchEngine(args.db, addresses_path=None if args.no_addresses else args.addresses,
                                 allow_addr_version_mismatch=args.allow_addr_version_mismatch)
    except RuntimeError as e:
        print(f"WARNING: house numbers disabled, addresses database not attached: {e}", file=sys.stderr)
        eng = query.SearchEngine(args.db)
    if eng.addr_warning:
        print("WARNING:", eng.addr_warning, file=sys.stderr)

    def show(text):
        res = eng.search(text, lat, lon, limit=args.limit)
        if args.json:
            print(json.dumps(res, indent=1))
            return
        print(f"{text!r}  ({res['ms']} ms)  parse: " +
              "; ".join(f"{p['text']}={p['as']}" + (f"({p['match']})" if p.get('match') else "") for p in res["parse"]))
        for i, r in enumerate(res["results"], 1):
            dist = f"{r['distance_km']:.1f} km" if r["distance_km"] is not None else ""
            print(f"{i:2d}. {r['title']:<38.38} {r['category'] or '':<20.20} {dist:>9}  {r['subtitle'][:45]:<45} "
                  f"[{r['how']}/{r['tier']} {r['score']:.3f}]")

    if args.interactive:
        while True:
            try:
                text = input("> ")
            except EOFError:
                break
            if text.strip():
                show(text)
    elif args.text:
        show(args.text)


if __name__ == "__main__":
    main()
