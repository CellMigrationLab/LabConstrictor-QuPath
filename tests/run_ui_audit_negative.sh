#!/bin/bash
# NEGATIVE CONTROLS of the UI audit: each seeded failure must turn the audit red, with real output (docs/UI_AUDIT.md). Exit 0 only when every one was caught.
#   QUPATH=<QuPath folder> LC_HOME=<registry with the test apps frame and runs, see docs/UI_AUDIT.md> [PYTHON=python3] [LC_RELEASE_DIR=...] tests/run_ui_audit_negative.sh [out dir]
# Seeds: extra_control, removed_control (the window), blank_result (the results), a tool that fails, a worker that dies, a hung tool (ends by the watchdog), a launch killed by the outer watchdog, an app that
# must not be listed but is. Run is activated by calling run() (LC_UI_RUN_VIA=call, see docs/UI_AUDIT.md "Bugs found"), the failures seeded here are about everything else.
set -uo pipefail
cd "$(dirname "$0")/.."
: "${QUPATH:?set QUPATH}"; : "${LC_HOME:?set LC_HOME}"
OUT="${1:-${TMPDIR:-/tmp}/lc_ui_negative}"; mkdir -p "$OUT"; PY="${PYTHON:-python3}"; missed=0
export LC_UI_RUN_VIA=call
FRAME="$PWD/tests/ui_audit_apps/frame/lc_tests"; SEEDED="$PWD/tests/ui_audit_apps/runs_seeded/lc_tests"
# scenario <label> <app> <lc_tests> <extra env as VAR=value ...> -- : runs the audit into $OUT/<label>
scenario() {
  local label="$1" app="$2" tests="$3"; shift 3
  echo "=== negative control: $label"
  env "$@" LC_APP="$app" LC_APP_TESTS="$tests" LC_SHOTS="$OUT/$label" tests/run_ui_audit.sh > "$OUT/$label.out" 2>&1
  echo "(audit exit status $?, red expected; output $OUT/$label.out)"
}
# caught <label> <app> <id> <status> <text>
caught() { $PY -I tests/ui_audit_matrix.py expect --shots "$OUT/$1" --app "$2" --id "$3" --status "$4" --contains "$5" --label "$1" || missed=1; }

scenario extra_control frame "$FRAME" LC_UI_SEED=extra_control LC_UI_ONLY=none
caught extra_control frame ui_qupath_controls_all_known FAIL "Seeded extra control"
scenario removed_control frame "$FRAME" LC_UI_SEED=removed_control LC_UI_ONLY=none
caught removed_control frame ui_qupath_controls_all_known FAIL "Button:Rescan apps"
caught removed_control frame ui_qupath_panel_rescan_apps FAIL ""
scenario blank_result frame "$FRAME" LC_UI_SEED=blank_result LC_UI_ONLY=probe
caught blank_result frame ui_qupath_run_probe FAIL ""
scenario tool_failures runs "$SEEDED" LC_UI_ONLY=quick_values,fails,dies,never_returns LC_UI_TOOL_TIMEOUT=20
caught tool_failures runs ui_qupath_run_quick_values PASS ""      # the control: the same audit can be green
caught tool_failures runs ui_qupath_run_fails FAIL "COMPLETE"
caught tool_failures runs ui_qupath_run_dies FAIL ""
caught tool_failures runs ui_qupath_run_never_returns FAIL "timeout"
scenario outer_watchdog frame "$FRAME" LC_UI_ONLY=probe LC_UI_LAUNCH_TIMEOUT=25
caught outer_watchdog frame ui_qupath_form_probe_opens FAIL "watchdog"
scenario wrongly_listed frame "$FRAME" LC_EXPECT_QUPATH=not_listed
caught wrongly_listed frame ui_qupath_not_listed_frame FAIL "is in the app chooser"
echo "negative controls: $([ "$missed" -eq 0 ] && echo "all caught" || echo "SOME MISSED")"
exit $missed
