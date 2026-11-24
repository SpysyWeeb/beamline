#!/usr/bin/env bash
# Build SQLite's spellfix1 extension in WSL (Ubuntu 24.04) and run the typo
# comparison with Linux python3, whose sqlite3 module can load extensions.
#   wsl -d Ubuntu-24.04 -u <wsl-user> -- bash /mnt/c/<repo>/packbuilder/search/wsl_spellfix.sh /mnt/c/<repo>
# Works only under ~/beamline/search-fuzzy in WSL. Needs gcc (present on the
# distro) and nothing else: the SQLite headers come from the official
# amalgamation zip of the same version as the system libsqlite3.
set -euo pipefail
REPO="$1"
WORK="$HOME/beamline/search-fuzzy"
mkdir -p "$WORK/src" "$WORK/db"
cd "$WORK/src"
VER=$(python3 -c 'import sqlite3; print(sqlite3.sqlite_version)')
case "$VER" in
  3.45.1) ZIP=sqlite-amalgamation-3450100.zip; YEAR=2024;
          ZIP_SHA256=""; TAG=version-3.45.1 ;;
  *) echo "unexpected system SQLite $VER"; exit 1 ;;
esac
[ -f "$ZIP" ] || curl -sSfL -o "$ZIP" "https://www.sqlite.org/$YEAR/$ZIP"
[ -f spellfix.c ] || curl -sSfL -o spellfix.c "https://raw.githubusercontent.com/sqlite/sqlite/$TAG/ext/misc/spellfix.c"
sha256sum "$ZIP" spellfix.c
rm -rf amal && mkdir amal && cd amal && python3 -c "import zipfile,sys; zipfile.ZipFile('../$ZIP').extractall('.')" && cd ..
INC=$(dirname "$(find amal -name sqlite3ext.h | head -1)")
gcc -O2 -fPIC -shared -I"$INC" spellfix.c -o spellfix.so
ls -la spellfix.so
cd "$WORK"
python3 "$REPO/packbuilder/search/fuzzy_eval.py" run --dir "$REPO/data/search/fuzzy" --work "$WORK/db" \
  --spellfix "$WORK/src/spellfix" --out "$WORK/results-linux.json"
cp "$WORK/results-linux.json" "$REPO/data/search/fuzzy/results-linux.json"
