#!/bin/bash
# Registers the small apps of the OS smoke (tests/smoke_os.sh) in a scratch LC_HOME on Linux, macOS or Windows (Git Bash):
#   tests/ci_setup_os.sh <work dir> [tools git ref, default main]      PYTHON=<python 3.12> (default: python)
#   interactions  the example app of labconstrictor-tools     runs  tests/runs_app (delayed, failing and number-reporting tools)
# Like tests/ci_setup_apps.sh, with the interpreter path of the OS: <env>/Scripts/python.exe on Windows, <env>/bin/python elsewhere.
set -euo pipefail
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }
WORK="$(mkdir -p "${1:?work dir}" && cd "$1" && pwd)"; REF="${2:-main}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
WORKN="$(native "$WORK")"; REPON="$(native "$REPO")"
export LC_HOME="$WORKN/home"; mkdir -p "$WORK/home"
"${PYTHON:-python}" -m venv --copies "$WORK/tools_env"
if [ -x "$WORK/tools_env/Scripts/python.exe" ]; then PY="$WORK/tools_env/Scripts/python.exe"; else PY="$WORK/tools_env/bin/python"; fi
echo "interpreter of the app environment: $PY"
"$PY" -m pip install -q --upgrade pip
"$PY" -m pip install -q "git+https://github.com/CellMigrationLab/LabConstrictor-Tools@$REF" numpy pandas tifffile scipy scikit-image
"$PY" -m labconstrictor_tools register --name interactions --prefix "$WORKN/tools_env" --module labconstrictor_tools.examples.interactions
"$PY" -m labconstrictor_tools register --name runs --prefix "$WORKN/tools_env" --module runs_lc_tools --pythonpath "$REPON/tests/runs_app"
"$PY" -m labconstrictor_tools list
