#!/usr/bin/env python3
"""The bookkeeping of the QuPath UI audit (docs/UI_AUDIT.md), done OUTSIDE QuPath so that it survives a killed QuPath.

The audit runs one QuPath launch per phase (see tests/run_ui_audit.sh). This script, which needs only the Python standard library, is called between the launches:

    multi     the expected ids and an empty matrix of the multi-app check (LC_APPS)
    plan      read the app's schema from the Tools CLI (an independent source: the app's own interpreter, `labconstrictor_tools list --json`) and its lc_tests, write
              <shots>/plan_qupath_<app>.json (tools, the case chosen per tool, the cases to replay first) and <shots>/expected_qupath_<app>.json (every id the audit must produce)
    init      write an empty <shots>/matrix_qupath_<app>.json (the Groovy launches append to it)
    killed    a QuPath launch was killed by the watchdog: every expected id of that phase that has no row yet gets a FAIL row
    finish    the controls check from the side files of the launches, NOT_RUN rows for every expected id without a row, the summary; exit status 1 on any FAIL or NOT_RUN

Matrix rows: {id, host, app, tool, control, status PASS|FAIL|NOT_RUN|HUMAN, detail, seconds, screenshot}. A line `CHECK <id> PASS|FAIL <detail>` (one line, at most 500 characters)
or `NOTE <id> not run: <reason>` is printed for every row this script adds.
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

HOST = "qupath"
DETAIL_CHARS = 500
PANEL_CONTROLS = ["app_chooser", "tool_chooser", "rescan_apps", "restart_worker", "copy_as_command", "copy_terminal_command", "copy_python_snippet", "cancel", "details", "run", "keep_worker"]
STRESS_PREFIX = "stress_"          # tools that stress memory or hang on purpose: never run through the form (the Playground declares them in no_case.json)
MIN_CONTROLS = 12                  # the panel alone has 11 controls: fewer found means the scan found (almost) nothing
Row = dict[str, Any]


def now() -> str:
    """UTC time stamp, second resolution."""
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def sanitize(text: str) -> str:
    """Lower case letters, digits and underscores only (the <tool> of a check id)."""
    out = "".join(c if c.isalnum() and c.isascii() else "_" for c in text.lower())
    return "_".join(part for part in out.split("_") if part) or "x"


def one_line(text: str) -> str:
    """The text on one line, at most DETAIL_CHARS characters."""
    flat = " ".join(str(text).split())
    return flat if len(flat) <= DETAIL_CHARS else flat[: DETAIL_CHARS - 3] + "..."


def read_json(path: Path) -> Any:
    """The JSON file, or a SystemExit that names the file and the problem."""
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise SystemExit("cannot read %s: %s" % (path, error))


def write_json_atomic(path: Path, data: Any) -> None:
    """Writes the JSON to a temporary file in the same folder and moves it over `path` (atomic)."""
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, temp = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=str(path.parent))
    with os.fdopen(handle, "w", encoding="utf-8") as out:
        json.dump(data, out, indent=2)
    os.replace(temp, path)


def matrix_path(shots: Path, app: str) -> Path:
    """Where the matrix of an app is."""
    return shots / ("matrix_qupath_%s.json" % app)


def load_matrix(shots: Path, app: str) -> dict[str, Any]:
    """The matrix of the app (it must exist: `init` made it)."""
    return read_json(matrix_path(shots, app))


def add_row(shots: Path, app: str, row_id: str, status: str, detail: str, tool: str = "", control: str = "", seconds: float = 0.0) -> None:
    """Appends one row to the matrix and prints its CHECK or NOTE line."""
    matrix = load_matrix(shots, app)
    text = one_line(detail)
    matrix["rows"].append({"id": row_id, "host": HOST, "app": app, "tool": tool, "control": control, "status": status, "detail": text, "seconds": seconds, "screenshot": None})
    write_json_atomic(matrix_path(shots, app), matrix)
    if status in ("PASS", "FAIL"):
        print("CHECK %s %s %s" % (row_id, status, text))
    else:
        print("NOTE %s not run: %s [%s]" % (row_id, text, status))


def tool_json(home: Path, app: str) -> dict[str, Any]:
    """The app's schema as the Tools CLI reports it: the interpreter named in the registry entry on disk runs `labconstrictor_tools list --json`."""
    entry = read_json(home / "apps" / ("%s.json" % app))
    python = entry.get("python")
    if not python:
        raise SystemExit("the registry entry of %s names no python interpreter" % app)
    env = dict(os.environ, LC_HOME=str(home))
    done = subprocess.run([python, "-m", "labconstrictor_tools", "list", "--json"], capture_output=True, text=True, env=env, timeout=300, check=False)
    if done.returncode != 0:
        raise SystemExit("`labconstrictor_tools list --json` failed (exit %d): %s" % (done.returncode, done.stderr.strip()[-400:]))
    apps = json.loads(done.stdout)["apps"]
    if app not in apps:
        raise SystemExit("the Tools CLI does not list the app '%s' (it lists %s)" % (app, sorted(apps)))
    return {"python": python, "schema": apps[app]}


def load_cases(tests: Path) -> tuple[list[dict[str, Any]], dict[str, str]]:
    """The cases (a list, or {"cases": [...]}) and the optional no_case.json of the app's lc_tests."""
    cases = read_json(tests / "cases.json")
    if isinstance(cases, dict):
        cases = cases.get("cases", [])
    if not isinstance(cases, list):
        raise SystemExit("%s is not a list of cases" % (tests / "cases.json"))
    no_case = read_json(tests / "no_case.json") if (tests / "no_case.json").is_file() else {}
    if not isinstance(no_case, dict):
        raise SystemExit("%s is not an object {tool id: reason}" % (tests / "no_case.json"))
    return cases, no_case


def runnable(case: dict[str, Any]) -> bool:
    """True for a case the audit may run through the form: it expects COMPLETE (the default), is not a cancel case, is not skipped and is not a stress tool."""
    expect = case.get("expect", {})
    return (not case.get("skip") and case.get("cancel_after_s") is None and expect.get("status", "COMPLETE") == "COMPLETE"
            and not str(case.get("tool", "")).startswith(STRESS_PREFIX))


def make_plan(app: str, home: Path, tests: Path, ordered: list[str]) -> dict[str, Any]:
    """The plan of the audit of one app: the tools (from the CLI), the case chosen per tool and, for an app whose cases depend on each other in order, the earlier cases to replay."""
    info = tool_json(home, app)
    cases, no_case = load_cases(tests) if str(tests) not in ("", ".") else ([], {})      # no lc_tests given (an app that is only listed in the multi-app check): the tools alone
    tools = info["schema"]["tools"]
    chosen: dict[str, int] = {}
    replay: dict[str, list[int]] = {}
    for tool in tools:
        for index, case in enumerate(cases):
            if case.get("tool") == tool["id"] and runnable(case):
                chosen[tool["id"]] = index
                replay[tool["id"]] = [i for i, c in enumerate(cases[:index]) if runnable(c) and i != index] if app in ordered else []
                break
    return {"app": app, "python": info["python"], "tools": tools, "cases": cases, "no_case": no_case, "chosen": chosen, "replay": replay, "ordered": app in ordered}


def expected_ids(plan: dict[str, Any]) -> dict[str, str]:
    """Every id the audit must produce for the plan, with the phase (launch) that produces it."""
    ids: dict[str, str] = {"ui_qupath_controls_all_known": "finish"}
    for control in PANEL_CONTROLS:
        ids["ui_qupath_panel_%s" % control] = "panel" if control not in ("run", "details", "restart_worker", "keep_worker") else "panel_run"
    for tool in plan["tools"]:
        key = sanitize(tool["id"])
        phase = "tool:%s" % tool["id"]
        for suffix in ("form_%s_opens", "form_%s_controls", "run_%s", "screens_%s"):
            ids["ui_qupath_" + suffix % key] = phase
    return ids


def command_plan(args: argparse.Namespace) -> int:
    """`plan`: writes plan_qupath_<app>.json and expected_qupath_<app>.json."""
    shots = Path(args.shots)
    if args.expect == "not_listed":
        ids = {"ui_qupath_not_listed_%s" % sanitize(args.app): "not_listed"}
        plan: dict[str, Any] = {"app": args.app, "tools": [], "cases": [], "no_case": {}, "chosen": {}, "replay": {}, "ordered": False}
    else:
        plan = make_plan(args.app, Path(args.home), Path(args.tests), [a for a in args.ordered.split(",") if a])
        ids = expected_ids(plan)
    write_json_atomic(shots / ("plan_qupath_%s.json" % args.app), plan)
    write_json_atomic(shots / ("expected_qupath_%s.json" % args.app), {"host": HOST, "app": args.app, "expected": [{"id": i, "phase": p} for i, p in ids.items()]})
    write_json_atomic(matrix_path(shots, args.app), {"host": HOST, "app": args.app, "started": now(), "finished": None, "notes": [], "rows": []})
    print("plan %s: %d tool(s), %d expected id(s); tools: %s" % (args.app, len(plan["tools"]), len(ids), " ".join(t["id"] for t in plan["tools"])))
    return 0


def command_multi(args: argparse.Namespace) -> int:
    """`multi`: the expected ids and an empty matrix of the multi-app check of the apps in --apps."""
    shots = Path(args.shots)
    names = [a for a in args.apps.split(",") if a]
    ids = {"ui_qupath_multi_lists_%s" % sanitize(a): "multi" for a in names}
    ids.update({"ui_qupath_multi_switch_%s_%s" % (sanitize(a), sanitize(b)): "multi" for a in names for b in names if a != b})
    write_json_atomic(shots / "expected_qupath_multi.json", {"host": HOST, "app": "multi", "expected": [{"id": i, "phase": p} for i, p in ids.items()]})
    write_json_atomic(matrix_path(shots, "multi"), {"host": HOST, "app": "multi", "started": now(), "finished": None, "notes": [], "rows": []})
    print("multi: %d expected id(s) for %s" % (len(ids), ", ".join(names)))
    return 0


def command_tools(args: argparse.Namespace) -> int:
    """`tools`: prints the tool ids of the plan, one per line (the launches of the orchestrator)."""
    for tool in read_json(Path(args.shots) / ("plan_qupath_%s.json" % args.app))["tools"]:
        print(tool["id"])
    return 0


def command_killed(args: argparse.Namespace) -> int:
    """`killed`: the launch of `phase` was killed after `seconds`: its ids without a row become FAIL rows."""
    shots = Path(args.shots)
    expected = read_json(shots / ("expected_qupath_%s.json" % args.app))["expected"]
    have = {r["id"] for r in load_matrix(shots, args.app)["rows"]}
    for item in expected:
        if item["phase"] == args.phase and item["id"] not in have:
            add_row(shots, args.app, item["id"], "FAIL", "the watchdog killed QuPath after %s s during phase '%s' (%s): this check had not finished" % (args.seconds, args.phase, args.reason))
    return 0


def controls_problems(shots: Path, app: str, plan: dict[str, Any]) -> list[str]:
    """What the side files of the launches say against ui_qupath_controls_all_known: unknown controls, expected controls not found, wrong windows, too few controls, a launch with no file."""
    problems: list[str] = []
    wanted = ["panel", "panel_run"] + ["tool:%s" % t["id"] for t in plan["tools"]]
    total = 0
    side = shots / "qupath" / app
    for phase in wanted:
        path = side / ("controls_%s.json" % sanitize(phase))
        if not path.is_file():
            problems.append("no control scan from the launch '%s'" % phase)
            continue
        data = read_json(path)
        total = max(total, int(data.get("count", 0)))
        for key in ("unknown", "missing", "windows"):
            if data.get(key):
                problems.append("%s %s: %s" % (phase, key, "; ".join(data[key])))
    if total < MIN_CONTROLS:
        problems.append("the largest scan found %d control(s), at least %d are expected (zero controls found counts as failure)" % (total, MIN_CONTROLS))
    return problems


def command_finish(args: argparse.Namespace) -> int:
    """`finish`: the controls row, NOT_RUN rows, the summary. Exit status 1 when anything is FAIL or NOT_RUN."""
    shots = Path(args.shots)
    plan = read_json(shots / ("plan_qupath_%s.json" % args.app)) if args.expect != "multi" else {}
    if args.expect == "listed":
        problems = controls_problems(shots, args.app, plan)
        if problems:
            add_row(shots, args.app, "ui_qupath_controls_all_known", "FAIL", "%d problem(s): %s" % (len(problems), " | ".join(problems)), control="all")
        else:
            add_row(shots, args.app, "ui_qupath_controls_all_known", "PASS", "every control found is expected and every expected control was found, in every window of every launch", control="all")
    matrix = load_matrix(shots, args.app)
    have = {r["id"] for r in matrix["rows"]}
    for item in read_json(shots / ("expected_qupath_%s.json" % args.app))["expected"]:
        if item["id"] not in have:
            add_row(shots, args.app, item["id"], "NOT_RUN", "expected, but no row was written (phase '%s' ended early or never ran)" % item["phase"])
    matrix = load_matrix(shots, args.app)
    matrix["finished"] = now()
    write_json_atomic(matrix_path(shots, args.app), matrix)
    counts = {s: sum(1 for r in matrix["rows"] if r["status"] == s) for s in ("PASS", "FAIL", "NOT_RUN", "HUMAN")}
    print("SUMMARY qupath %s pass=%d fail=%d not_run=%d human=%d (matrix: %s)" % (args.app, counts["PASS"], counts["FAIL"], counts["NOT_RUN"], counts["HUMAN"], matrix_path(shots, args.app)))
    return 1 if counts["FAIL"] or counts["NOT_RUN"] else 0


def command_add(args: argparse.Namespace) -> int:
    """`add`: one row, for the orchestrator (a launch that could not start, a seeded run)."""
    add_row(Path(args.shots), args.app, args.id, args.status, args.detail)
    return 0


def main(argv: list[str]) -> int:
    """Command line entry."""
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    for name, fn in (("plan", command_plan), ("multi", command_multi), ("tools", command_tools), ("killed", command_killed), ("finish", command_finish), ("add", command_add)):
        p = sub.add_parser(name)
        p.add_argument("--app", required=True)
        p.add_argument("--shots", required=True)
        p.set_defaults(fn=fn)
        if name == "plan":
            p.add_argument("--home", required=True)
            p.add_argument("--tests", default="")
            p.add_argument("--expect", choices=["listed", "not_listed"], default="listed")
            p.add_argument("--ordered", default="GuessTheCondition")
        if name == "multi":
            p.add_argument("--apps", required=True)
        if name == "killed":
            p.add_argument("--phase", required=True)
            p.add_argument("--seconds", required=True)
            p.add_argument("--reason", default="timeout")
        if name == "finish":
            p.add_argument("--expect", choices=["listed", "not_listed", "multi"], default="listed")
        if name == "add":
            p.add_argument("--id", required=True)
            p.add_argument("--status", choices=["PASS", "FAIL", "NOT_RUN", "HUMAN"], required=True)
            p.add_argument("--detail", required=True)
    args = parser.parse_args(argv)
    return int(args.fn(args))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
