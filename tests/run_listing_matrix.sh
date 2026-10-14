#!/bin/bash
# The per-host discovery rule, end to end in a real QuPath: registers one app per row of the app matrix (the rows of LabConstrictor-Tools tests/release_sandbox/README.md) in a scratch
# registry with the `hosts` the matrix gives it, then runs the sandbox driver tests/gui_test_listing_body.groovy once per app, as the release sandbox does, and fails when one run fails.
#   tests/run_listing_matrix.sh <work dir of tests/ci_setup_apps.sh>      (QUPATH=... and the jar built by build.sh are needed; logs in $LOGS, default gui-logs/)
# The expectation is written here (the matrix, not the Tools reference); the sentence the driver must find in the Tools log is the one of the Tools reference function app_hosts.listed_for.
set -uo pipefail
cd "$(dirname "$0")/.."
WORK="$(cd "${1:?work dir of tests/ci_setup_apps.sh}" && pwd)"
PY="$WORK/tools_env/bin/python"
LOGS="${LOGS:-$PWD/gui-logs}"
HOME_DIR="$WORK/matrix_home"; rm -rf "$HOME_DIR"; mkdir -p "$HOME_DIR"
ALL=notebook,napari,fiji,qupath
# app | hosts it declares ("-" = no declaration) | what QuPath must do
matrix=("Playground|$ALL|listed" "NucleiSky|$ALL|listed" "GuessTheCondition|$ALL|listed" "VLab4Mic|notebook,napari,fiji|not_listed"
        "CellTracksColab|notebook|not_listed" "OldApp|-|listed")
for row in "${matrix[@]}"; do
  IFS='|' read -r app hosts _ <<< "$row"
  args=(--name "$app" --prefix "$WORK/tools_env" --module labconstrictor_tools.examples.interactions)
  [ "$hosts" = "-" ] || args+=(--hosts "$hosts")
  LC_HOME="$HOME_DIR" "$PY" -m labconstrictor_tools register "${args[@]}" || { echo "FAIL could not register $app"; exit 1; }
done
chmod -R go-w "$HOME_DIR"
fail=0
for row in "${matrix[@]}"; do
  IFS='|' read -r app _ expect <<< "$row"
  sentence=$("$PY" -I - "$HOME_DIR/apps/$app.json" <<'PYEOF'
import json, sys
from labconstrictor_tools import app_hosts
print(app_hosts.listed_for(json.load(open(sys.argv[1])), "qupath")[1])
PYEOF
  ) || { echo "FAIL the reference function failed for $app"; exit 1; }
  if [ "$expect" = not_listed ] && [ -z "$sentence" ]; then echo "FAIL the reference function gives no skip sentence for $app"; fail=1; continue; fi
  echo "=== $app: QuPath must $expect ($sentence)"
  LC_HOME="$HOME_DIR" LC_EXPECT_APP="$app" LC_EXPECT_LISTED="$expect" LC_EXPECT_SENTENCE="$sentence" LOGS="$LOGS/listing_$app" tests/run_gui_ci.sh gui_test_listing_body.groovy || fail=1
done
exit $fail
