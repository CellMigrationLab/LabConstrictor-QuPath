#!/bin/bash
# Installs LabConstrictor-Tools and the apps the GUI tests use into virtual environments under $1 and registers them in $1/home/apps
# (the LC_HOME of the tests):  tests/ci_setup_apps.sh <work dir> [tools git ref, default main]
#   interactions  the example app of labconstrictor-tools (labconstrictor_tools.examples.interactions)
#   adv           tests/adversarial_app (odd input for the hints)
#   LabConstrictorPlayground  the Playground app, from its main branch, with CPU PyTorch (its device test runs a real model on the CPU)
# Not installed: CellTracksColab and NucleiSky (their bridge is not on their main branch yet, see tests/run_gui_ci.sh).
set -euo pipefail
WORK="$(mkdir -p "${1:?work dir}" && cd "$1" && pwd)"; REF="${2:-main}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="git+https://github.com/CellMigrationLab/LabConstrictor-Tools@$REF"
export LC_HOME="$WORK/home"; mkdir -p "$LC_HOME"

# --copies: the interpreter of an environment is a file of its own. A link would point at the runner's shared Python (/opt/hostedtoolcache), which is
# writable by everybody, and the registry (Tools and hosts) refuses an interpreter that is. The environments are made unwritable for group and others below.
"${PYTHON:-python3}" -m venv --copies "$WORK/tools_env"
"$WORK/tools_env/bin/pip" install -q --upgrade pip
"$WORK/tools_env/bin/pip" install -q "$TOOLS" numpy pandas tifffile scipy scikit-image
"$WORK/tools_env/bin/python" -m labconstrictor_tools register --name interactions --prefix "$WORK/tools_env" --module labconstrictor_tools.examples.interactions
"$WORK/tools_env/bin/python" -m labconstrictor_tools register --name adv --prefix "$WORK/tools_env" --module adv_lc_tools --pythonpath "$REPO/tests/adversarial_app"

"${PYTHON:-python3}" -m venv --copies "$WORK/playground_env"
"$WORK/playground_env/bin/pip" install -q --upgrade pip
"$WORK/playground_env/bin/pip" install -q torch --index-url https://download.pytorch.org/whl/cpu
"$WORK/playground_env/bin/pip" install -q "$TOOLS" "git+https://github.com/CellMigrationLab/labconstrictor-playground@main" numpy pandas scipy matplotlib tifffile
"$WORK/playground_env/bin/python" -m labconstrictor_tools register --name LabConstrictorPlayground --prefix "$WORK/playground_env" --module labconstrictor_playground_lc_tools
chmod -R go-w "$WORK/tools_env" "$WORK/playground_env" "$LC_HOME"
"$WORK/playground_env/bin/python" -m labconstrictor_tools list
