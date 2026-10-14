#!/bin/bash
# Runs the QuPath GUI test on a virtual screen. Needs: QuPath (QUPATH=...), the jar built by build.sh, apps registered in LC_HOME.
set -euo pipefail
cd "$(dirname "$0")/.."
QUPATH="${QUPATH:?set QUPATH}"
# LC_EXTENSION_JAR / LC_EXTENSION_SCRIPT: test the RELEASED jar and all-in-one script (tests/run_ui_audit.sh sets them from LC_RELEASE_DIR); the default is the jar built by build.sh and the parts of this checkout
cp -n "$QUPATH/lib/app/QuPath.cfg" "$QUPATH/lib/app/QuPath.cfg.before_lc_tests" 2>/dev/null || true      # to restore the installation: move it back and delete lib/app/labconstrictor-qupath-0.1.0.jar
cp "${LC_EXTENSION_JAR:-target/labconstrictor-qupath-0.1.0.jar}" "$QUPATH/lib/app/labconstrictor-qupath-0.1.0.jar"
# QuPath's launcher lists its jars one by one; a user installs the extension by dropping the jar on the QuPath window instead.
python3 - "$QUPATH/lib/app/QuPath.cfg" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
if "labconstrictor-qupath" not in s:
    open(p, "w").write(s.replace("app.mainclass=qupath.QuPath\n", "app.mainclass=qupath.QuPath\napp.classpath=$APPDIR/labconstrictor-qupath-0.1.0.jar\n", 1))
PY
# the script is joined from the files listed in parts.txt (the same order the extension uses), then the test body is appended
RES=src/main/resources/org/cellmigrationlab/labconstrictor/qupath
BODY_FILE="${BODY:-gui_test_body.groovy}"; PARTS_FILE="tests/${BODY_FILE%.groovy}.parts"      # a body made of several files: tests/<body>.parts lists them, in order, to be joined in front of the body
{ if [ -n "${LC_EXTENSION_SCRIPT:-}" ]; then cat "$LC_EXTENSION_SCRIPT"; else while IFS= read -r part || [ -n "$part" ]; do [ -z "$part" ] || { cat "$RES/$part"; echo; }; done < "$RES/parts.txt"; fi
  if [ -f "$PARTS_FILE" ]; then while IFS= read -r shared || [ -n "$shared" ]; do [ -z "$shared" ] || { cat "tests/$shared"; echo; }; done < "$PARTS_FILE"; fi
  cat "tests/$BODY_FILE"; } > "${TMPDIR:-/tmp}/lc_gui_test.groovy"
export QT_QPA_PLATFORM=
xvfb-run -a -s "-screen 0 1600x1000x24" "$QUPATH/bin/QuPath" -q -Dlc.noshow=true -Dlc.qupath.test.script="${TMPDIR:-/tmp}/lc_gui_test.groovy" -Dlc.test.out="${OUT:-/tmp/qp_shots}" -Dlc.test.fixtures="$PWD/tests/fixtures"
