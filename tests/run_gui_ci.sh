#!/bin/bash
# Runs every GUI test body (tests/gui_test_*body.groovy) one after the other with tests/run_gui_test.sh, keeps one log per body, and
# fails when any body fails. Used by .github/workflows/qupath-gui.yml; works locally too:
#   QUPATH=/path/to/QuPath LC_HOME=<registry> tests/run_gui_ci.sh [body.groovy ...]     (logs and screenshots in $LOGS, default gui-logs/)
# A body FAILS when QuPath exits non-zero, a line "FAIL ..." is printed, the body never printed its RESULTS/SUMMARY line (a crash or a
# hang past the timeout), or it printed no PASS at all. A body is SKIPPED, with the reason printed, only when an app it needs is not
# registered in $LC_HOME and is listed in needs_optional_apps below; a missing app for any other body is a failure.
set -uo pipefail
cd "$(dirname "$0")/.."
LOGS="${LOGS:-$PWD/gui-logs}"; mkdir -p "$LOGS"
LC_HOME="${LC_HOME:?set LC_HOME to the folder whose apps/ holds the registered apps}"; export LC_HOME
TIMEOUT="${BODY_TIMEOUT:-900}"

# body -> apps it needs (registered in $LC_HOME/apps/<name>.json)
declare -A needs=(
  [gui_test_body.groovy]="CellTracksColab NucleiSky"
  [gui_test_adversarial_body.groovy]="adv"
  [gui_test_playground_body.groovy]="LabConstrictorPlayground"
  [gui_test_frame_body.groovy]="frame"
  [gui_test_calibration_body.groovy]="frame"
  [gui_test_frame_body.groovy]="frame"
  [gui_test_calibration_body.groovy]="frame"
)
for b in area buttons channel copy fallbacks interactions region shapes widgets; do needs[gui_test_${b}_body.groovy]="interactions"; done
for b in parity_form parity_host parity_lifecycle parity_messages parity_pixel parity_results parity_support parity_quote; do needs[gui_test_${b}_body.groovy]="interactions"; done
needs[gui_test_parity_cancel_body.groovy]="LabConstrictorPlayground interactions"
# bodies that write their own registry and results (they empty LC_HOME first, so each gets a scratch LC_HOME named as the body asserts; the registered apps stay in LC_SHARED_HOME)
declare -A scratch=([gui_test_parity_trust_body.groovy]=lhome_trust [gui_test_parity_form_body.groovy]=lhome_form [gui_test_parity_host_body.groovy]=lhome_host
  [gui_test_parity_lifecycle_body.groovy]=lhome_life [gui_test_parity_pixel_body.groovy]=lhome_pixel [gui_test_parity_support_body.groovy]=lhome_support)
SHARED_HOME="$LC_HOME"; export LC_SHARED_HOME="$SHARED_HOME"
# bodies that are skipped (not failed) while their apps are not registered: the bridge of these two apps is not on their main branch yet
optional="gui_test_body.groovy"
skip_reason="CellTracksColab and NucleiSky have no labconstrictor_tools bridge (celltracks_lc_tools / nucleisky_lc_tools) on their main branch yet, so they cannot be installed from a release or main; register them in LC_HOME to run this body"

if [ $# -gt 0 ]; then bodies=("$@"); else bodies=($(cd tests && ls gui_test_*body.groovy)); fi
fail=0; rows=()
for body in "${bodies[@]}"; do
  body=$(basename "$body"); name=${body%.groovy}; log="$LOGS/$name.log"
  missing=""
  for app in ${needs[$body]:-}; do [ -f "$SHARED_HOME/apps/$app.json" ] || missing="$missing $app"; done
  if [ -n "$missing" ]; then
    if [[ " $optional " == *" $body "* ]]; then
      echo "SKIP $body: app(s) not registered:$missing. $skip_reason" | tee "$log"; rows+=("| $body | SKIPPED | $skip_reason |"); continue
    fi
    echo "FAIL $body: app(s) not registered in $SHARED_HOME/apps:$missing" | tee "$log"; rows+=("| $body | FAILED | app(s) not registered:$missing |"); fail=1; continue
  fi
  echo "=== $body"
  export LC_HOME="$SHARED_HOME"
  if [ -n "${scratch[$body]:-}" ]; then export LC_HOME="$LOGS/scratch/${scratch[$body]}"; mkdir -p "$LC_HOME"; fi
  BODY="$body" OUT="$LOGS/shots/$name" timeout --kill-after=30 "$TIMEOUT" tests/run_gui_test.sh > "$log" 2>&1; code=$?
  pass=$(grep -c '^PASS ' "$log"); nfail=$(grep -c '^FAIL ' "$log")
  grep -E '^(PASS|FAIL|RESULTS|SUMMARY)' "$log" | cut -c1-300
  why=""
  [ "$code" -ne 0 ] && why="exit code $code"
  [ "$nfail" -ne 0 ] && why="$why; $nfail failed check(s)"
  grep -qE '^(RESULTS|SUMMARY) ' "$log" || why="$why; no RESULTS/SUMMARY line (crash or timeout)"
  [ "$pass" -eq 0 ] && why="$why; no check passed"
  if [ -n "$why" ]; then echo "FAILED $body:${why#;}"; rows+=("| $body | FAILED | $pass passed, $nfail failed:${why#;} |"); fail=1
  else echo "OK $body ($pass checks)"; rows+=("| $body | passed | $pass checks |"); fi
done
{ echo "| body | result | detail |"; echo "|---|---|---|"; printf '%s\n' "${rows[@]}"; } > "$LOGS/summary.md"
cat "$LOGS/summary.md"; [ -z "${GITHUB_STEP_SUMMARY:-}" ] || cat "$LOGS/summary.md" >> "$GITHUB_STEP_SUMMARY"
exit $fail
