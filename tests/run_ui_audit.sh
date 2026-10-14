#!/bin/bash
# The UI audit of the QuPath extension (docs/UI_AUDIT.md), one QuPath launch per phase with its own watchdog:
#   QUPATH=<QuPath folder> LC_HOME=<registry> LC_APP=<app> LC_APP_TESTS=<its lc_tests> LC_SHOTS=<out> tests/run_ui_audit.sh        (or: tests/run_gui_ci.sh gui_test_ui_audit_body.groovy)
# Optional: LC_EXPECT_QUPATH=listed|not_listed (default listed)   LC_APPS=a,b (first the multi-app checks)   LC_UI_ONLY=tool,ids (only these tools)   LC_UI_TOOL_TIMEOUT=300 (seconds per tool and per run)
#           LC_UI_LAUNCH_TIMEOUT=900 (watchdog per QuPath launch)   LC_UI_CLICK=robot|fire   LC_UI_SEED=<seed>   LC_UI_ORDERED=GuessTheCondition (apps whose cases must replay in order)   PYTHON=python3
# Phases: panel (choosers, Rescan, Cancel, Copy as command), panel_run (Run, Details, Restart worker, Keep the worker), one `tool` launch per tool (form opens, form controls, run, screens). A launch that is killed
# or crashes leaves FAIL rows for its checks and the audit goes on with the next launch. tests/ui_audit_matrix.py keeps the matrix and decides the exit status: 1 on any FAIL or NOT_RUN. Every CHECK/NOTE line of
# every launch is printed in full (never piped through tail: an early FAIL line must not be dropped).
set -uo pipefail
cd "$(dirname "$0")/.."
: "${QUPATH:?set QUPATH}"; : "${LC_HOME:?set LC_HOME}"; : "${LC_APP:?set LC_APP}"
EXPECT="${LC_EXPECT_QUPATH:-listed}"
case "$EXPECT" in listed|not_listed) ;; *) echo "FAIL LC_EXPECT_QUPATH must be listed or not_listed, not '$EXPECT'"; exit 2;; esac
[ "$EXPECT" = not_listed ] || : "${LC_APP_TESTS:?set LC_APP_TESTS to the lc_tests folder of the app}"
SHOTS="$(mkdir -p "${LC_SHOTS:-screens}" && cd "${LC_SHOTS:-screens}" && pwd)"; export LC_SHOTS="$SHOTS"
LOGS="${LOGS:-$SHOTS/logs}"; mkdir -p "$LOGS"
PY="${PYTHON:-python3}"; HELPER="$PY -I tests/ui_audit_matrix.py"
LAUNCH_TIMEOUT="${LC_UI_LAUNCH_TIMEOUT:-900}"; export LC_UI_TOOL_TIMEOUT="${LC_UI_TOOL_TIMEOUT:-300}"
export LC_HOME; status=0

# launch <log name> <phase> <tool id or ""> <app (for the matrix)>: one QuPath start; its CHECK and NOTE lines are printed; a kill or a crash becomes FAIL rows
launch() {
  local name="$1" phase="$2" tool="$3" app="$4" log started code ended key
  log="$LOGS/$name.log"; started=$(date +%s)
  echo "=== launch $name (phase $phase${tool:+, tool $tool})"
  LC_UI_PHASE="$phase" LC_UI_TOOL="$tool" BODY=gui_test_ui_audit_body.groovy OUT="$LOGS/shots/$name" \
    timeout --kill-after=20 "$LAUNCH_TIMEOUT" tests/run_gui_test.sh > "$log" 2>&1; code=$?
  grep -E '^(CHECK|NOTE) ' "$log" | cut -c1-700
  ended=$(grep -c '^UI_AUDIT_LAUNCH_END' "$log")
  key="$phase"; [ -z "$tool" ] || key="tool:$tool"
  if [ "$code" -eq 124 ] || [ "$code" -eq 137 ]; then $HELPER killed --app "$app" --shots "$SHOTS" --phase "$key" --seconds "$(( $(date +%s) - started ))" --reason "timeout of $LAUNCH_TIMEOUT s"
  elif [ "$ended" -eq 0 ]; then $HELPER killed --app "$app" --shots "$SHOTS" --phase "$key" --seconds "$(( $(date +%s) - started ))" --reason "QuPath exited with code $code before the driver ended (log: $log)"
  fi
  pkill -f "lc_gui_test.groovy" 2>/dev/null || true
}

if [ -n "${LC_APPS:-}" ]; then      # the two-app situation: every app listed with all its tools, switching shows the right tools
  PLANS="$SHOTS/multi_plans"; mkdir -p "$PLANS"
  IFS=',' read -ra multi <<< "$LC_APPS"
  for a in "${multi[@]}"; do $HELPER plan --app "$a" --shots "$PLANS" --home "$LC_HOME" --tests "" || { echo "FAIL ui_qupath_multi_plan $a"; status=1; }; done
  $HELPER multi --app multi --shots "$SHOTS" --apps "$LC_APPS"
  LC_UI_PLANS="$PLANS" LC_APP="$LC_APPS" launch multi multi "" multi
  $HELPER finish --app multi --shots "$SHOTS" --expect multi || status=1
fi

$HELPER plan --app "$LC_APP" --shots "$SHOTS" --home "$LC_HOME" --tests "${LC_APP_TESTS:-}" --expect "$EXPECT" --ordered "${LC_UI_ORDERED:-GuessTheCondition}" || { echo "FAIL ui_qupath_plan could not be made for $LC_APP"; exit 1; }
if [ "$EXPECT" = not_listed ]; then
  launch not_listed not_listed "" "$LC_APP"
else
  launch panel panel "" "$LC_APP"
  launch panel_run panel_run "" "$LC_APP"
  for tool in $($HELPER tools --app "$LC_APP" --shots "$SHOTS"); do
    if [ -n "${LC_UI_ONLY:-}" ] && [[ ",$LC_UI_ONLY," != *",$tool,"* ]]; then continue; fi
    launch "tool_$tool" tool "$tool" "$LC_APP"
  done
fi
$HELPER finish --app "$LC_APP" --shots "$SHOTS" --expect "$EXPECT" || status=1
echo "matrix: $SHOTS/matrix_qupath_$LC_APP.json (exit status $status)"
exit $status
