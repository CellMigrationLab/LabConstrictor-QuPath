#!/bin/bash
# Starts QuPath on a virtual screen (Xvfb) with a built extension jar and runs tests/smoke_jar.groovy: the menu entry exists and the dialog opens.
#   QUPATH=/path/to/QuPath tests/smoke_jar.sh target/labconstrictor-qupath-<version>.jar
# The QuPath folder is copied first (the jar is put on its class path like tests/run_gui_test.sh does), so the original stays untouched.
# Needs xvfb and QuPath's libraries (see .github/workflows/release.yml). Uses an empty LC_HOME: no app is registered, the dialog says so.
set -euo pipefail
JAR="$(cd "$(dirname "${1:?path of the jar to test}")" && pwd)/$(basename "$1")"
QUPATH="${QUPATH:?set QUPATH}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
cp -a "$QUPATH" "$WORK/QuPath"
cp "$JAR" "$WORK/QuPath/lib/app/"
CFG="$WORK/QuPath/lib/app/QuPath.cfg"
grep -q '^app.mainclass=qupath.QuPath$' "$CFG" || { echo "FAIL QuPath.cfg has no app.mainclass line to extend" >&2; exit 1; }
sed -i "s|^app.mainclass=qupath.QuPath\$|&\napp.classpath=\$APPDIR/$(basename "$JAR")|" "$CFG"
mkdir -p "$WORK/home"
export LC_HOME="$WORK/home" HOME="$WORK/home" QT_QPA_PLATFORM=
timeout --kill-after=20 "${SMOKE_TIMEOUT:-300}" xvfb-run -a -s "-screen 0 1600x1000x24" "$WORK/QuPath/bin/QuPath" -q -Dlc.qupath.test.script="$HERE/smoke_jar.groovy" | tee "$WORK/out.txt"
grep -q '^SMOKE OK' "$WORK/out.txt"
