#!/bin/bash
# OS smoke of a built extension jar on Windows, macOS or Linux with a real display (a CI runner's desktop session; no Xvfb):
#   tests/smoke_os.sh <QuPath app folder> <launcher> <jar> <LC_HOME of the registered apps>
# <QuPath app folder> holds the launcher's .cfg files and jars (Windows: <QuPath>/app, macOS: <name>.app/Contents/app). The jar is put on
# its class path (the same way tests/run_gui_test.sh does), QuPath is started and runs, one after the other:
#   1. tests/smoke_jar.groovy: the menu entry exists, the dialog opens and lists the registered app "interactions";
#   2. the test bodies that reach OS-specific code: the Copy-as-command quoting of every case of tests/quote_vectors.json, the trust checks
#      (interpreter inside the prefix, the Windows note), the results folders and the run-record clean-up (file locking while pruning).
# Prints one line per step: "RESULT <step>: pass | fail (reason) | no gui (QuPath never ran the script)". Exit 1 when a step is not "pass".
set -uo pipefail
APPDIR="${1:?QuPath app folder}"; LAUNCHER="${2:?launcher}"; JAR="${3:?jar}"; SHARED="${4:?LC_HOME}"
HERE="$(cd "$(dirname "$0")" && pwd)"; REPO="$(dirname "$HERE")"
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }
LOGS="${LOGS:-$REPO/os-logs}"; mkdir -p "$LOGS"
RES="$REPO/src/main/resources/org/cellmigrationlab/labconstrictor/qupath"
cp "$JAR" "$APPDIR/"; JARNAME="$(basename "$JAR")"
for cfg in "$APPDIR"/*.cfg; do
  line="$(grep -m1 '^app.classpath=' "$cfg" || true)"; [ -n "$line" ] || continue
  sep='/'; case "$line" in *'\'*) sep='\';; esac
  NEWLINE="app.classpath=\$APPDIR$sep$JARNAME" awk '{ print } !d && /^app.classpath=/ { print ENVIRON["NEWLINE"] ($0 ~ /\r$/ ? "\r" : ""); d = 1 }' "$cfg" > "$cfg.new" && mv "$cfg.new" "$cfg"
  echo "jar added to the class path of $(basename "$cfg")"
done
rc=0; summary="$LOGS/summary.md"; echo "| step | result |" > "$summary"; echo "|---|---|" >> "$summary"
# run_qupath <name> <groovy file> <LC_HOME> [extra -D...]: QuPath with a time limit; the output goes to $LOGS/<name>.log
run_qupath() {
  local name="$1" script="$2" home="$3" log="$LOGS/$1.log"; shift 3
  LC_HOME="$home" "$LAUNCHER" -q "-Dlc.qupath.test.script=$(native "$script")" "-Dlc.test.out=$(native "$LOGS/shots_$name")" "-Dlc.test.fixtures=$(native "$REPO/tests/fixtures")" "$@" > "$log" 2>&1 &
  local pid=$! i
  for ((i = 0; i < ${STEP_TIMEOUT:-180}; i++)); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
  if kill -0 "$pid" 2>/dev/null; then echo "TIMEOUT after ${STEP_TIMEOUT:-180}s" >> "$log"; kill -9 "$pid" 2>/dev/null; command -v taskkill >/dev/null 2>&1 && taskkill //F //T //PID "$pid" >/dev/null 2>&1; fi
  wait "$pid" 2>/dev/null; echo $? > "$LOGS/$name.code"
}
verdict() {   # <name> <what marks the end: regex of the final line>
  local name="$1" log="$LOGS/$1.log" r
  if ! grep -qE '^(SMOKE start|PASS |FAIL |RESULTS |SUMMARY )' "$log"; then r="no gui (QuPath never ran the script; see $name.log)"
  elif grep -q '^FAIL ' "$log"; then r="fail ($(grep -c '^FAIL ' "$log") failed check(s): $(grep -m1 '^FAIL ' "$log" | cut -c1-160))"
  elif ! grep -qE "$2" "$log"; then r="fail (no final line: crash or timeout; code $(cat "$LOGS/$name.code"))"
  elif [ "$(grep -c '^PASS ' "$log")" -eq 0 ]; then r="fail (no check passed)"
  else r="pass ($(grep -c '^PASS ' "$log") checks)"; fi
  echo "RESULT $name: $r"; echo "| $name | $r |" >> "$summary"
  case "$r" in pass*) ;; *) rc=1; tail -n 40 "$log";; esac
}
# 1. the jar itself: menu entry and dialog (the apps of $SHARED are registered)
LC_SMOKE_APP=interactions run_qupath smoke_jar "$HERE/smoke_jar.groovy" "$SHARED"
verdict smoke_jar '^SMOKE OK'
if grep -q 'no gui' "$summary"; then echo "QuPath did not run the script: the other steps are skipped"; echo "| (other steps) | skipped: QuPath did not run the script |" >> "$summary"; [ -z "${GITHUB_STEP_SUMMARY:-}" ] || cat "$summary" >> "$GITHUB_STEP_SUMMARY"; exit 1; fi
# 2. OS-specific code paths: each body is appended to the joined script (what tests/run_gui_test.sh does)
declare -A scratch=([gui_test_parity_trust_body.groovy]=lhome_trust [gui_test_parity_lifecycle_body.groovy]=lhome_life [gui_test_retention_body.groovy]=lhome_retention)
for body in gui_test_parity_quote_body.groovy gui_test_parity_trust_body.groovy gui_test_parity_lifecycle_body.groovy gui_test_retention_body.groovy; do
  name="${body%.groovy}"; joined="$LOGS/$name.joined.groovy"
  { while IFS= read -r part || [ -n "$part" ]; do part="${part%$'\r'}"; [ -z "$part" ] || { cat "$RES/$part"; echo; }; done < "$RES/parts.txt"; cat "$HERE/$body"; } > "$joined"
  home="$SHARED"
  if [ -n "${scratch[$body]:-}" ]; then home="$LOGS/scratch/${scratch[$body]}"; mkdir -p "$home"; fi
  LC_SHARED_HOME="$(native "$SHARED")" run_qupath "$name" "$joined" "$(native "$home")" -Dlc.noshow=true
  verdict "$name" '^(RESULTS|SUMMARY) '
done
[ -z "${GITHUB_STEP_SUMMARY:-}" ] || cat "$summary" >> "$GITHUB_STEP_SUMMARY"
cat "$summary"; exit $rc
