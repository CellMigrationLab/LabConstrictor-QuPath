// ================================================================== UI audit driver, host qupath (appended to the tool script by tests/run_gui_test.sh after the parts listed in gui_test_ui_audit_body.parts)
// One QuPath launch runs ONE phase of the audit of ONE app (tests/run_ui_audit.sh starts them and keeps the watchdog; docs/UI_AUDIT.md says how). Environment:
//   LC_HOME           the registry that holds the installed app            LC_APP        the registry name of the app (LC_APPS for the multi-app phase)
//   LC_APP_TESTS      the app's lc_tests folder (cases.json, fixtures/, no_case.json)
//   LC_SHOTS          output folder (default ./screens): matrix_ui_qupath_<app>.json, expected_qupath_<app>.json, plan_qupath_<app>.json, qupath/<app>/*.png
//   LC_EXPECT_QUPATH  listed | not_listed (not_listed: only the check that the app is absent from the chooser, plus a NOTE)
//   LC_UI_PHASE       panel | panel_run | tool | multi | not_listed      LC_UI_TOOL  the tool id of phase tool
//   LC_UI_TOOL_TIMEOUT seconds per tool and per run (default 300)        LC_UI_CLICK robot (default) | fire         LC_UI_SEED  test-only seeded failure (ui_audit_seed.groovy)
// Output: one `CHECK <id> PASS|FAIL <detail>` line per assertion, `NOTE <id> not run: <reason>` for anything not run; exit status 1 when this launch recorded a FAIL.

/** The opening of the window and what every phase does first. */
Object uaStart(UaMatrix matrix) {
    Object dlg = uaOpenDialog()
    uaSeedAfterOpen(dlg)
    return dlg
}

/** The first tool of the plan that has a chosen case (the tool the panel checks use), or null. */
Map uaPanelTool() {
    Map chosen = (Map) UiAudit.plan.chosen, replay = (Map) UiAudit.plan.replay
    return (Map) ((List<Map>) UiAudit.plan.tools).find { chosen.containsKey(it.id) && ((List) (replay[it.id] ?: [])).isEmpty() }      // a tool that needs no earlier case (an ordered app: not play_round)
}

void uaPhasePanel(UaMatrix matrix, String app) {
    Object dlg = uaStart(matrix)
    Map tool = uaPanelTool() ?: ((List<Map>) UiAudit.plan.tools)[0]
    Map testCase = ((Map) UiAudit.plan.chosen)[tool.id] == null ? null : ((List<Map>) UiAudit.plan.cases)[(((Map) UiAudit.plan.chosen)[tool.id] as int)]
    uaPanelChoosers(dlg, app)
    uaPanelRescan(dlg, app)
    uaPanelCancelIdle(dlg)
    try { uaPanelCopy(dlg, app, tool, testCase) } catch (Throwable t) { matrix.add("ui_qupath_panel_copy_as_command", "", "copy_as_command", "FAIL", "the form for the copy checks could not be prepared: " + UiAudit.describe(t)) }
    uaScanControls(dlg, "panel", [tool: UiAudit.planTool((String) UiAudit.fx { dlg.currentTool?.id })])
}

void uaPhasePanelRun(UaMatrix matrix, String app) {
    Object dlg = uaStart(matrix)
    Map tool = uaPanelTool()
    List<String> ids = ["ui_qupath_panel_run", "ui_qupath_panel_details", "ui_qupath_panel_restart_worker", "ui_qupath_panel_keep_worker"]
    if (tool == null) {
        ids.each { matrix.add(it, "", it.replace("ui_qupath_panel_", ""), "NOT_RUN", "no tool of the app has a case that expects COMPLETE, so no run could be made to show this control working") }
        uaScanControls(dlg, "panel_run", [tool: null])
        return
    }
    Map testCase = ((List<Map>) UiAudit.plan.cases)[(((Map) UiAudit.plan.chosen)[tool.id] as int)]
    String refusal = ""
    uaCheck("ui_qupath_panel_run_refusal", "", "run", UiAudit.controlTimeout, { refusal = uaRunRefusal(dlg, app); refusal })
    Map evidence = null
    try {
        uaPrepareCase(dlg, app, tool, (Map) (testCase.inputs ?: [:]))
        evidence = uaPressRun(dlg, testCase, UiAudit.toolTimeout)
        evidence.responseType = uaOutcome(dlg).responseType
    } catch (Throwable t) { matrix.add("ui_qupath_panel_run_prepare", "", "run", "FAIL", "the run for the panel checks failed: " + UiAudit.describe(t)) }
    uaPanelRun(dlg, refusal, evidence)
    uaCloseExtraWindows(dlg)
    uaPanelDetails(dlg)
    uaPanelRestart(dlg)
    uaPanelKeepWorker(dlg, app, tool, testCase)
    uaScanControls(dlg, "panel_run", [tool: UiAudit.planTool((String) UiAudit.fx { dlg.currentTool?.id }), resultTool: tool, windows: [app + ": " + tool.label]])      // the results window of the last run is still open here
}

void uaPhaseTool(UaMatrix matrix, String app) {
    Object dlg = uaStart(matrix)
    Map tool = UiAudit.planTool(UiAudit.toolId)
    if (tool == null) throw new IllegalStateException("tool '" + UiAudit.toolId + "' is not in the plan of '" + app + "'")
    String choice = uaChoiceOf(dlg, tool)
    uaFormOpens(dlg, app, choice, tool)
    uaFormControls(dlg, app, choice, tool, matrix)
    uaRunTool(dlg, app, tool, matrix)
}

void uaPhaseNotListed(UaMatrix matrix, String app) {
    Object dlg = uaStart(matrix)
    uaCheck("ui_qupath_not_listed_" + UiAudit.sanitize(app), "", "app_chooser", UiAudit.controlTimeout, {
        List<String> offered = (List<String>) UiAudit.fx { new ArrayList<String>(dlg.appBox.items) }
        uaAssert(!offered.contains(app), "'" + app + "' is in the app chooser (it offers " + offered + ")")
        String sentence = System.getenv("LC_EXPECT_SENTENCE") ?: ""
        if (new File(UiAudit.home, "apps/" + app + ".json").isFile() && !sentence.isEmpty()) {
            File log = new File(UiAudit.home, "logs/" + LcConst.LOG_NAME)
            uaAssert(log.isFile() && log.getText("UTF-8").split("\n").any { it.endsWith(" qupath: " + sentence) }, "the Tools log does not hold: " + sentence)
        }
        "'" + app + "' is absent from the app chooser (it offers " + offered + ")"
    })
    matrix.note("ui_qupath_forms_" + UiAudit.sanitize(app), "the app must not be listed in QuPath, so no form, run or screenshot check applies")
}

void uaPhaseMulti(UaMatrix matrix, List<String> names) {
    Object dlg = uaStart(matrix)
    Map<String, List<Map>> schemas = [:]
    names.each { String n -> schemas[n] = (List<Map>) ((Map) LcJson.parse(new File(System.getenv("LC_UI_PLANS") ?: UiAudit.shots.path, "plan_qupath_" + n + ".json"))).tools }
    uaMultiChecks(dlg, names, schemas)
}

// the test script runs on the JavaFX thread: the work is done on a thread of its own, which asks the JavaFX thread for each GUI step
Thread.start("lc-gui-test") {
    int exitCode = 1
    try {
        UiAudit.home = new File(UiAudit.env("LC_HOME"))
        UiAudit.shots = new File(UiAudit.env("LC_SHOTS", "screens")).getAbsoluteFile()
        UiAudit.phase = UiAudit.env("LC_UI_PHASE")
        UiAudit.seed = System.getenv("LC_UI_SEED") ?: ""
        UiAudit.clickMode = UiAudit.env("LC_UI_CLICK", "robot")
        UiAudit.toolTimeout = Integer.parseInt(UiAudit.env("LC_UI_TOOL_TIMEOUT", "300"))
        UiAudit.runVia = UiAudit.env("LC_UI_RUN_VIA", "click")
        if (!(UiAudit.runVia in ["click", "call"])) throw new IllegalStateException("LC_UI_RUN_VIA must be click or call")
        if (!(UiAudit.clickMode in ["robot", "fire"])) throw new IllegalStateException("LC_UI_CLICK must be robot or fire")
        List<String> names = UiAudit.phase == "multi" ? UiAudit.env("LC_APPS").split(",").collect { it.trim() }.findAll { it } : [UiAudit.env("LC_APP")]
        UiAudit.app = UiAudit.phase == "multi" ? "multi" : names[0]
        UiAudit.toolId = System.getenv("LC_UI_TOOL") ?: ""
        if (UiAudit.phase != "multi" && UiAudit.phase != "not_listed") {
            UiAudit.testsDir = new File(UiAudit.env("LC_APP_TESTS")).getAbsoluteFile()
            UiAudit.plan = (Map) LcJson.parse(new File(UiAudit.shots, "plan_qupath_" + UiAudit.app + ".json"))
        }
        File matrixDir = UiAudit.shots
        if (UiAudit.phase == "multi" && !new File(matrixDir, "matrix_ui_qupath_multi.json").isFile()) new UaMatrix("multi", matrixDir).write()
        UiAudit.current = new UaMatrix(UiAudit.app, matrixDir)
        try {
            switch (UiAudit.phase) {
                case "panel": uaPhasePanel(UiAudit.current, UiAudit.app); break
                case "panel_run": uaPhasePanelRun(UiAudit.current, UiAudit.app); break
                case "tool": uaPhaseTool(UiAudit.current, UiAudit.app); break
                case "multi": uaPhaseMulti(UiAudit.current, names); break
                case "not_listed": uaPhaseNotListed(UiAudit.current, UiAudit.app); break
                default: throw new IllegalStateException("LC_UI_PHASE is panel, panel_run, tool, multi or not_listed, not '" + UiAudit.phase + "'")
            }
        } catch (Throwable t) {
            t.printStackTrace()
            UiAudit.current.add("ui_qupath_driver_crashed", UiAudit.toolId, UiAudit.phase, "FAIL", "phase " + UiAudit.phase + " ended early: " + UiAudit.describe(t))
        }
        if (UiAudit.phase in ["panel", "panel_run", "tool"]) uaWriteControlsFile()
        exitCode = UiAudit.current.failed == 0 ? 0 : 1
    } catch (Throwable t) {
        t.printStackTrace()
        println "CHECK ui_qupath_driver_crashed FAIL " + UaMatrix.oneLine(UiAudit.describe(t))
    } finally {
        println "UI_AUDIT_LAUNCH_END phase=" + UiAudit.phase + " exit=" + exitCode
        Thread.sleep(500)
        System.exit(exitCode)
    }
}
