#!/bin/bash
# The UI audit of the QuPath extension (docs/UI_AUDIT.md), one QuPath launch per phase and per tool, each with its own watchdog:
#   QUPATH=<QuPath folder> LC_HOME=<registry> LC_APP=<app> LC_APP_TESTS=<its lc_tests> LC_SHOTS=<out> tests/run_ui_audit.sh        (or: tests/run_gui_ci.sh gui_test_ui_audit_body.groovy)
# Optional: LC_EXPECT_QUPATH=listed|not_listed (default listed)   LC_APPS=a,b (first the multi-app checks, then every app of the list is audited in turn; LC_APP_TESTS_MAP="a=/lc_tests/a,b=/lc_tests/b" names
#           the lc_tests of each, LC_APP_TESTS is the one of LC_APP)   LC_UI_ONLY=tool,ids (only these tools)   LC_UI_TOOL_TIMEOUT=300 (seconds per tool and per run)   LC_UI_LAUNCH_TIMEOUT=900 (watchdog per QuPath launch)
#           LC_UI_CLICK=robot|fire   LC_UI_RUN_VIA=click|call (call: dlg.run() instead of a click on Run, a diagnostic: ui_qupath_panel_run then FAILS, see docs/UI_AUDIT.md)   LC_UI_SEED=<seed> (negative controls)
#           LC_UI_ORDERED=GuessTheCondition (apps whose cases must replay in order)   PYTHON=python3 (any 3.9+; only the standard library)
# Phases: panel (choosers, Rescan, Cancel, Copy as command), panel_run (Run, Details, Restart worker, Keep the worker), one `tool` launch per tool (form opens, form controls, run, screens). A launch that is killed
# or crashes leaves FAIL rows for its checks and the audit goes on with the next launch. Every launch works on a COPY of the app's lc_tests (a tool that writes into the fixtures cannot spoil the next launch).
# tests/ui_audit_matrix.py keeps the matrix and decides the exit status: 1 on any FAIL or NOT_RUN. Every CHECK/NOTE line of every launch is printed in full (never piped through tail: an early FAIL line must not be dropped).
set -uo pipefail
cd "$(dirname "$0")/.."
: "${QUPATH:?set QUPATH}"; : "${LC_HOME:?set LC_HOME}"; : "${LC_APP:?set LC_APP}"
EXPECT="${LC_EXPECT_QUPATH:-listed}"
case "$EXPECT" in listed|not_listed) ;; *) echo "FAIL LC_EXPECT_QUPATH must be listed or not_listed, not '$EXPECT'"; exit 2;; esac
SHOTS="$(mkdir -p "${LC_SHOTS:-screens}" && cd "${LC_SHOTS:-screens}" && pwd)"; export LC_SHOTS="$SHOTS"
LOGS="${LOGS:-$SHOTS/logs}"; mkdir -p "$LOGS"
PY="${PYTHON:-python3}"; HELPER="$PY -I tests/ui_audit_matrix.py"
LAUNCH_TIMEOUT="${LC_UI_LAUNCH_TIMEOUT:-900}"; export LC_UI_TOOL_TIMEOUT="${LC_UI_TOOL_TIMEOUT:-300}"
export LC_HOME; status=0

# tests_of <app>: the lc_tests folder of an app (LC_APP_TESTS_MAP, else LC_APP_TESTS for LC_APP), empty when there is none
tests_of() {
  local entry
  if [ -n "${LC_APP_TESTS_MAP:-}" ]; then
    for entry in ${LC_APP_TESTS_MAP//,/ }; do [ "${entry%%=*}" = "$1" ] && { echo "${entry#*=}"; return; }; done
  fi
  [ "$1" = "$LC_APP" ] && echo "${LC_APP_TESTS:-}"
  return 0
}

# launch <log name> <phase> <tool id or ""> <app> <lc_tests of the app or "">: one QuPath start on a copy of the lc_tests; its CHECK and NOTE lines are printed; a kill or a crash becomes FAIL rows
launch() {
  local name="$1" phase="$2" tool="$3" app="$4" tests="$5" log started code ended key work=""
  log="$LOGS/$name.log"; started=$(date +%s)
  echo "=== launch $name (phase $phase${tool:+, tool $tool})"
  if [ -n "$tests" ]; then
    work="$(mktemp -d "${TMPDIR:-/tmp}/lc_ui_tests.XXXXXX")"
    cp -rL "$tests"/. "$work"/ || { echo "FAIL ui_qupath_driver_crashed cannot copy $tests"; rm -rf "$work"; return; }
  fi
  LC_APP="$app" LC_APP_TESTS="${work:-}" LC_APP_TESTS_PRISTINE="$tests" LC_UI_PHASE="$phase" LC_UI_TOOL="$tool" BODY=gui_test_ui_audit_body.groovy OUT="$LOGS/shots/$name" \
    timeout --kill-after=20 "$LAUNCH_TIMEOUT" tests/run_gui_test.sh > "$log" 2>&1; code=$?
  [ -z "$work" ] || rm -rf "$work"
  grep -E '^(CHECK|NOTE) ' "$log" | cut -c1-700
  ended=$(grep -c '^UI_AUDIT_LAUNCH_END' "$log")
  key="$phase"; [ -z "$tool" ] || key="tool:$tool"
  if [ "$code" -eq 124 ] || [ "$code" -eq 137 ]; then $HELPER killed --app "$app" --shots "$SHOTS" --phase "$key" --seconds "$(( $(date +%s) - started ))" --reason "timeout of $LAUNCH_TIMEOUT s"
  elif [ "$ended" -eq 0 ]; then $HELPER killed --app "$app" --shots "$SHOTS" --phase "$key" --seconds "$(( $(date +%s) - started ))" --reason "QuPath exited with code $code before the driver ended (log: $log)"
  fi
  pkill -f "lc_gui_test.groovy" 2>/dev/null || true
}

# audit_app <app> <expect> <lc_tests>: plan, launches, finish; sets status on a FAIL or NOT_RUN
audit_app() {
  local app="$1" expect="$2" tests="$3" tool
  if [ "$expect" = listed ] && [ -z "$tests" ]; then echo "FAIL ui_qupath_plan no lc_tests folder for '$app' (LC_APP_TESTS, or LC_APP_TESTS_MAP for LC_APPS)"; status=1; return; fi
  $HELPER plan --app "$app" --shots "$SHOTS" --home "$LC_HOME" --tests "$tests" --expect "$expect" --ordered "${LC_UI_ORDERED:-GuessTheCondition}" || { echo "FAIL ui_qupath_plan could not be made for $app"; status=1; return; }
  if [ "$expect" = not_listed ]; then
    launch "${app}_not_listed" not_listed "" "$app" ""
  else
    launch "${app}_panel" panel "" "$app" "$tests"
    launch "${app}_panel_run" panel_run "" "$app" "$tests"
    for tool in $($HELPER tools --app "$app" --shots "$SHOTS"); do
      if [ -n "${LC_UI_ONLY:-}" ] && [[ ",$LC_UI_ONLY," != *",$tool,"* ]]; then continue; fi
      launch "${app}_tool_$tool" tool "$tool" "$app" "$tests"
    done
  fi
  $HELPER finish --app "$app" --shots "$SHOTS" --expect "$expect" || status=1
  echo "matrix: $SHOTS/matrix_ui_qupath_$app.json"
}

apps=("$LC_APP")
if [ -n "${LC_APPS:-}" ]; then      # the two-app situation: every app listed with all its tools, switching shows the right tools; then each app is audited in turn
  PLANS="$SHOTS/multi_plans"; mkdir -p "$PLANS"
  IFS=',' read -ra apps <<< "$LC_APPS"
  for a in "${apps[@]}"; do $HELPER plan --app "$a" --shots "$PLANS" --home "$LC_HOME" --tests "" || { echo "FAIL ui_qupath_multi_plan $a"; status=1; }; done
  $HELPER multi --app multi --shots "$SHOTS" --apps "$LC_APPS"
  LC_UI_PLANS="$PLANS" LC_APPS="$LC_APPS" launch multi multi "" multi ""
  $HELPER finish --app multi --shots "$SHOTS" --expect multi || status=1
fi
for a in "${apps[@]}"; do
  [ "$a" = "$LC_APP" ] || [ -n "${LC_APPS:-}" ] || continue
  audit_app "$a" "$([ "$a" = "$LC_APP" ] && echo "$EXPECT" || echo listed)" "$(tests_of "$a")"
done
echo "exit status $status"
exit $status
