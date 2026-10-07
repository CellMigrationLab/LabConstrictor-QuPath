#!/bin/bash
# Runs the QuPath GUI test on a virtual screen. Needs: QuPath (QUPATH=...), the jar built by build.sh, apps registered in LC_HOME.
set -euo pipefail
cd "$(dirname "$0")/.."
QUPATH="${QUPATH:?set QUPATH}"
cp target/labconstrictor-qupath-0.1.0.jar "$QUPATH/lib/app/"
# QuPath's launcher lists its jars one by one; a user installs the extension by dropping the jar on the QuPath window instead.
python3 - "$QUPATH/lib/app/QuPath.cfg" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
if "labconstrictor-qupath" not in s:
    open(p, "w").write(s.replace("app.mainclass=qupath.QuPath\n", "app.mainclass=qupath.QuPath\napp.classpath=$APPDIR/labconstrictor-qupath-0.1.0.jar\n", 1))
PY
cat src/main/resources/org/cellmigrationlab/labconstrictor/qupath/LabConstrictorTools.groovy tests/gui_test_body.groovy > /tmp/lc_gui_test.groovy
export QT_QPA_PLATFORM=
xvfb-run -a -s "-screen 0 1600x1000x24" "$QUPATH/bin/QuPath" -q -Dlc.noshow=true -Dlc.qupath.test.script=/tmp/lc_gui_test.groovy -Dlc.test.out="${OUT:-/tmp/qp_shots}"
