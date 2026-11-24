#!/usr/bin/env bash
# Run the search tests with Linux python3 + its system SQLite (no venv, stdlib
# only), as a stand-in for the comma 3X (plain Python on Linux).
#   wsl -d Ubuntu-24.04 -u <wsl-user> -- bash /mnt/c/<repo>/packbuilder/search/wsl_linux_check.sh /mnt/c/<repo>
# The database is copied to ~/beamline/search-linux first, so it is read from
# the Linux file system and not through the Windows mount. The addresses
# database is attached from the Windows mount (read-only); while the addresses
# track's database and normaliser versions differ, the tests run with
# --allow-addr-version-mismatch (they print the warning).
set -euo pipefail
REPO="$1"
WORK="$HOME/beamline/search-linux"
mkdir -p "$WORK"
cp -u "$REPO/data/search/pa-search.sqlite" "$WORK/pa-search.sqlite"
python3 -c 'import sqlite3, sys, platform; print("python", sys.version.split()[0], "| sqlite", sqlite3.sqlite_version, "|", platform.platform()); c = sqlite3.connect(":memory:"); print([r[0] for r in c.execute("pragma compile_options") if any(k in r[0] for k in ("FTS5", "RTREE", "MATH", "LOAD"))])'
cd "$WORK"
T="$REPO/packbuilder/search/tests"
python3 "$T/run_tests.py" --db "$WORK/pa-search.sqlite" --allow-addr-version-mismatch --json "$WORK/test-results-linux.json" | tail -16
python3 "$T/run_tests.py" --db "$WORK/pa-search.sqlite" --allow-addr-version-mismatch --cases cases_holdout.json --json "$WORK/test-results-holdout-linux.json" | tail -4
python3 "$T/run_tests.py" --db "$WORK/pa-search.sqlite" --allow-addr-version-mismatch --cases cases_review.json --json "$WORK/test-results-review-linux.json" | tail -11
python3 "$T/check_inputs.py" --db "$WORK/pa-search.sqlite" | tail -3
cp "$WORK/test-results-linux.json" "$REPO/data/search/test-results-linux.json"
cp "$WORK/test-results-holdout-linux.json" "$REPO/data/search/test-results-holdout-linux.json"
cp "$WORK/test-results-review-linux.json" "$REPO/data/search/test-results-review-linux.json"
python3 "$REPO/packbuilder/search/cli.py" --db "$WORK/pa-search.sqlite" --no-addresses "walmart erie pa" --limit 3
