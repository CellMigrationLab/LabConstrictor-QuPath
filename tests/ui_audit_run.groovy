// ================================================================== UI audit, part 6: run every tool with the case the plan chose, assert the results, take the screenshots
// File inputs of a case become QuPath inputs the way a person would use them: the fixture image is opened as an image of a QuPath project (the first one is the open image, the others project images), and the
// form's image chooser is set to "Current image: <name>" / "Project: <name>". Results are asserted against the files the tool wrote and against the open image's hierarchy (uaPlacementProblems).
import javafx.scene.Node
import javafx.scene.control.*
import javafx.stage.Stage

/** A path of a case input: relative to the folder of cases.json when a file or folder of that name exists there (the rule of labconstrictor-tools test), else as given. */
String uaResolvePath(String value) {
    File f = new File(UiAudit.testsDir, value)
    return f.exists() ? f.getCanonicalPath() : value
}

/** The image chooser entry for a case file: "Current image: <name>" for the open one, "Project: <name>" for the other project images; null when the file is not a project image (then the file field is used). */
String uaImageChoice(String path) {
    String name = UiAudit.caseImages[path]
    return name == null ? null : (path == UiAudit.firstImage ? "Current image: " : "Project: ") + name
}

/** Puts the inputs of a case into the form the way a person would: a typed number, a text, a choice, an image of the project (or a file path). Unknown parameters are an error. */
void uaApplyInputs(Object dlg, Map tool, Map inputs) {
    inputs.each { String name, Object value ->
        Map p = (Map) ((List) tool.inputs).find { it.name == name }
        if (p == null) throw new IllegalStateException("the case sets '" + name + "', which tool '" + tool.id + "' does not declare")
        List typedFile = []      // [chooser, field, path] of an image given as a file: typed after this block, with the focus moving on
        UiAudit.fx {
            if (value == null) return null      // unset: the form default of an optional parameter is already "unset"
            if (dlg.checks[name] != null) dlg.checks[name].selected = true
            if (p.type in ["image", "labels"]) {
                def box = dlg.imageBoxes[name]
                String path = uaResolvePath(value.toString()), choice = uaImageChoice(path)
                if (choice != null) {
                    if (!box.items.contains(choice)) throw new IllegalStateException("'" + choice + "' is not offered for '" + name + "' (it offers " + box.items + ")")
                    box.value = choice
                } else {
                    typedFile.addAll([box, dlg.fileFields[name], path])
                }
            } else if (p.type in ["file", "table", "folder"]) {
                dlg.setters[name](uaResolvePath(value.toString()))
            } else if (p.pixel_size_of) {
                dlg.controls[name].text = LcLink.plain(value as double)      // typed: the value is the person's
            } else if (p.type == "integer" || p.type == "float") {
                LcDialog.setNumber(dlg.controls[name], p.type == "integer" ? (Object) (value as long) : (Object) (value as double))
            } else if (p.type == "boolean") {
                dlg.setters[name](value as boolean)
            } else {
                dlg.setters[name](value.toString())
            }
            null
        }
        if (typedFile) uaTypeFile((ComboBox) typedFile[0], (TextField) typedFile[1], (String) typedFile[2])
    }
    Thread.sleep(600)
}

/** Opens the case's fixtures as project images (when it has image inputs that are files), builds the form of `tool` fresh and fills it with the case's inputs. */
void uaPrepareCase(Object dlg, String app, Map tool, Map inputs) {
    uaPick(dlg, app, uaChoiceOf(dlg, tool))
    List<String> paths = ((List<Map>) tool.inputs).findAll { it.type in ["image", "labels"] && inputs[it.name] != null }.collect { uaResolvePath(inputs[it.name].toString()) }.unique().findAll { new File(it).isFile() }
    UiAudit.caseImages = [:]
    UiAudit.firstImage = null
    if (!paths.isEmpty()) {
        UiAudit.caseImages = uaOpenFixtures(paths)
        UiAudit.firstImage = paths[0]
        uaAssert(uaWaitFor(30) { UiAudit.fx { QuPathGUI.getInstance()?.imageData != null } }, "QuPath did not open '" + paths[0] + "' in 30 s")
        Thread.sleep(500)
    }
    UiAudit.fx { dlg.onTool(); null }      // the form is built after the image is open: it lists "Current image" and the project images
    Thread.sleep(500)
    uaApplyInputs(dlg, tool, inputs)
}

/** The outcome map of the last run, read from the Details text the dialog keeps (the JSON before "worker output:"). */
Map uaOutcome(Object dlg) {
    String report = (String) UiAudit.fx { dlg.lastReport }
    return (Map) LcJson.parseText(report.split("\n\nworker output:\n", 2)[0])
}

/** Text of every label of a window's scene. */
List<String> uaLabels(Stage st) { return (List<String>) UiAudit.fx { uaAllNodes(st.scene.root).findAll { it instanceof Label }.collect { it.text ?: "" } } }

/** Problems with the results of a run: each declared output (from the CLI schema) came back in order, its file exists and is not empty, it is shown, and the counts the case states hold. `unverified` collects what the GUI cannot check. */
List<String> uaResultProblems(Object dlg, Map tool, Map outcome, Map expect, List<String> unverified) {
    List<String> bad = []
    List<Map> results = (List<Map>) (((Map) outcome.outputs)?.results ?: [])
    List declared = ((List<Map>) (tool.outputs ?: [])).collect { [it.name, it.type] }
    List got = results.collect { [it.name, it.type] }
    if (declared != got) bad << ("results " + got + " differ from the declared outputs " + declared)
    Stage st = (Stage) UiAudit.fx { dlg.lastResultStage }
    if (st == null || !UiAudit.fx { st.showing }) return bad + ["no results window is showing"]
    List<String> labels = uaLabels(st)
    List<Node> nodes = (List<Node>) UiAudit.fx { uaAllNodes(st.scene.root) }
    String message = (String) UiAudit.fx { dlg.messageLabel.text }
    results.each { Map r ->
        String name = (r.name ?: r.type) as String
        Map want = (Map) (((Map) (expect.results ?: [:]))[r.name] ?: [:])
        try {
            if (r.path != null && !(r.type == "file" && !new File(r.path as String).exists()) && (!new File(r.path as String).isFile() || new File(r.path as String).length() == 0L)) bad << (name + ": its file '" + r.path + "' is missing or empty")
            switch (r.type) {
                case "values":
                    r.values.each { k, v -> if (!labels.contains(k.toString()) || !labels.contains(LcDialog.show(v))) bad << (name + ": '" + k + " = " + LcDialog.show(v) + "' is not shown") }
                    Map expectedValues = (Map) (want.equals ?: want)
                    expectedValues.each { k, v ->
                        Object actual = ((Map) r.values)[k]
                        boolean ok = v instanceof Map ? (actual instanceof Number && Math.abs((actual as double) - (v.approx as double)) <= ((v.tol ?: 1e-6) as double)) : (actual instanceof Number && v instanceof Number ? Math.abs((actual as double) - (v as double)) <= 1e-6 * Math.max(1d, Math.abs(v as double)) : actual == v)
                        if (!ok) bad << (name + ": " + k + " is " + actual + ", expected " + v)
                    }
                    break
                case "message":
                    String text = (r.text as String).replaceAll(/\*\*(.+?)\*\*/, '$1').replaceAll(/(?m)^\s*[-*] /, "• ")
                    if (text.trim().isEmpty()) bad << (name + ": the message is empty")
                    if (!message.contains(text)) bad << (name + ": the message '" + text.take(60) + "' is not shown on the form")
                    if (want.contains != null && !text.contains(want.contains as String)) bad << (name + ": the message lacks '" + want.contains + "'")
                    break
                case ["image", "labels"]:
                    if (!labels.contains(r.path as String)) bad << (name + ": its file is not shown")
                    if (!nodes.any { it instanceof Button && it.text == "Open in QuPath" }) bad << (name + ": no 'Open in QuPath' button")
                    uaImageExpectations(name, r, want, bad, unverified)
                    break
                case ["table", "points"]:
                    if (!nodes.any { it instanceof TableView }) bad << (name + ": no table is shown")
                    List<String> lines = new File(r.path as String).readLines("UTF-8").findAll { it.trim() }
                    int rows = lines.size() - 1
                    if (rows < 1) bad << (name + ": the file has no data row")
                    if (want.rows != null && rows != (want.rows as int)) bad << (name + ": " + rows + " rows in the file, expected " + want.rows)
                    if (want.columns != null && !(lines[0].split(",") as List).containsAll(want.columns)) bad << (name + ": columns " + lines[0] + " lack " + want.columns)
                    unverified.addAll(want.keySet().findAll { !(it in ["rows", "columns"]) }.collect { name + "." + it })
                    break
                case "shapes":
                    if (!labels.any { it.endsWith("(outlines)") }) bad << (name + ": no outlines section")
                    if (want.features != null && (r.n as int) != (want.features as int)) bad << (name + ": " + r.n + " outlines, expected " + want.features)
                    unverified.addAll(want.keySet().findAll { it != "features" }.collect { name + "." + it })
                    break
                case "affine":
                    if (!labels.any { it.contains(" relative to ") }) bad << (name + ": no alignment section")
                    break
                case "file":
                    if (!labels.contains(r.path as String)) bad << (name + ": its path is not shown")
                    break
                default: bad << (name + ": result type '" + r.type + "' is not one the audit knows")
            }
        } catch (Throwable t) { bad << (name + ": " + UiAudit.describe(t)) }
    }
    return bad
}

/** Shape and dtype of an image result, from QuPath's own reader; the other expectation keys cannot be read in QuPath and are listed as unverified. */
void uaImageExpectations(String name, Map r, Map want, List<String> bad, List<String> unverified) {
    def server = qupath.lib.images.servers.ImageServers.buildServer(r.path as String)
    try {
        if (want.shape != null) {
            List<Integer> got = server.nChannels > 1 ? [server.nChannels, server.height, server.width] : [server.height, server.width]
            List<Integer> wanted = want.shape.collect { it as int }
            if (wanted.size() == 3 && wanted[0] == 1 && got.size() == 2) wanted = wanted.drop(1)
            if (wanted.size() > 3) unverified << (name + ".shape(" + wanted.size() + "D)")
            else if (wanted != got) bad << (name + ": shape " + got + ", expected " + wanted)
        }
        if (want.dtype != null && server.pixelType.toString().toLowerCase(Locale.ROOT) != want.dtype) bad << (name + ": dtype " + server.pixelType + ", expected " + want.dtype)
    } finally { server.close() }
    unverified.addAll(want.keySet().findAll { !(it in ["shape", "dtype"]) }.collect { name + "." + it })
}

/** One run record folder (a new one for this tool) with a readable run.json that names the tool; returns its name. */
String uaRunRecord(Set<String> before, String toolId) {
    File root = LcRuns.runsDir()
    String suffix = "_" + LcDialog.slug(toolId)
    String found = null
    uaWaitFor(15) {
        found = (root.list() ?: new String[0]).find { String n -> !before.contains(n) && n.endsWith(suffix) && new File(root, n + "/run.json").isFile() }
        found != null
    }
    uaAssert(found != null, "no new run record folder ending in '" + suffix + "' with a run.json in " + root)
    Object record = LcJson.parse(new File(root, found + "/run.json"))
    uaAssert(record instanceof Map && !((Map) record).isEmpty() && record.toString().contains(toolId), "run.json of " + found + " is not a JSON object that names '" + toolId + "'")
    return found
}

/** Activates Run: a real mouse click (default), or `dlg.run()` called directly when LC_UI_RUN_VIA=call. The call mode exists because the button's action `{ run() }` of QuPath 0.7.0's extension script recurses into
 *  Closure.run() and overflows the stack (a click starts nothing, see docs/UI_AUDIT.md "Bugs found"); it is NEVER chosen silently, and ui_qupath_panel_run fails in it, because a button that was not clicked is not shown to work. */
void uaPressRunButton(Object dlg) {
    if (UiAudit.runVia == "call") UiAudit.fx { dlg.run(); null } else uaClick(dlg.runButton)
}

/** Presses Run (see uaPressRunButton) and waits for the end: returns [seconds, evidence of the buttons going busy]. A refusal (nothing started) throws with the status line. */
Map uaPressRun(Object dlg, Map testCase, int timeout) {
    List<Boolean> runFlips = Collections.synchronizedList(new ArrayList<Boolean>()), cancelFlips = Collections.synchronizedList(new ArrayList<Boolean>())
    def runListener = { o, a, b -> runFlips << b } as javafx.beans.value.ChangeListener
    def cancelListener = { o, a, b -> cancelFlips << b } as javafx.beans.value.ChangeListener
    UiAudit.fx {
        dlg.runButton.disableProperty().addListener(runListener)
        dlg.cancelButton.disableProperty().addListener(cancelListener)
        dlg.lastReport = ""
        null
    }
    long t0 = System.nanoTime()
    try {
        uaPressRunButton(dlg)
        boolean ended = uaWaitFor(timeout) {
            String report = (String) UiAudit.fx { dlg.lastReport }
            boolean idle = (boolean) UiAudit.fx { !dlg.running }
            report != null && !report.isEmpty() ? true : (idle && (System.nanoTime() - t0) / 1e9d > 5d)
        }
        if (!ended) throw new IllegalStateException("timeout: the run did not end in " + timeout + " s (status '" + UiAudit.fx { dlg.status.text } + "')")
        if (((String) UiAudit.fx { dlg.lastReport }).isEmpty()) throw new IllegalStateException("Run was refused or did nothing, nothing started (status '" + UiAudit.fx { dlg.status.text } + "')")
    } finally {
        UiAudit.fx { dlg.runButton.disableProperty().removeListener(runListener); dlg.cancelButton.disableProperty().removeListener(cancelListener); null }
    }
    return [seconds: (System.nanoTime() - t0) / 1e9d, runWentBusy: runFlips.contains(true), cancelWentOn: cancelFlips.contains(false)]
}

/** Opens the result image in QuPath with the 'Open in QuPath' button of the results window (a real click) and waits until the viewer shows it, with the size the result file has. Returns a problem or null. */
String uaOpenResult(Object dlg, Map outcome) {
    Stage st = (Stage) UiAudit.fx { dlg.lastResultStage }
    Map image = ((List<Map>) (((Map) outcome.outputs)?.results ?: [])).find { it.type in ["image", "labels"] }
    if (image == null || st == null) return null
    Button open = (Button) UiAudit.fx { uaAllNodes(st.scene.root).find { it instanceof Button && it.text == "Open in QuPath" } }
    if (open == null) return "no 'Open in QuPath' button in the results window"
    UiAudit.fx { st.x = 720; st.y = 20; st.toFront(); null }
    Thread.sleep(300)
    uaClick(open)
    String name = new File(image.path as String).name
    // with a project open QuPath first asks whether to import the file into it (its own dialog, modal): the person says Import, a real click on that button
    if (uaWaitFor(10) { uaStageTitles().contains("Import images to project") }) {
        Stage imp = (Stage) UiAudit.fx { javafx.stage.Window.getWindows().find { it instanceof Stage && it.showing && it.title == "Import images to project" } }
        Button ok = (Button) UiAudit.fx { uaAllNodes(imp.scene.root).find { it instanceof Button && it.text == "Import" } }
        if (ok == null) return "QuPath's 'Import images to project' dialog has no Import button"
        uaClick(ok)
    }
    if (uaWaitFor(15) { uaStageTitles().contains("Save changes") }) {      // the open image has changes (the result's annotations): QuPath asks whether to save them first; the person says No
        Stage sv = (Stage) UiAudit.fx { javafx.stage.Window.getWindows().find { it instanceof Stage && it.showing && it.title == "Save changes" } }
        Button no = (Button) UiAudit.fx { uaAllNodes(sv.scene.root).find { it instanceof Button && it.text == "No" } }
        if (no == null) return "QuPath's 'Save changes' dialog has no No button"
        uaClick(no)
    }
    boolean shown = uaWaitFor(30) { (boolean) UiAudit.fx { String p = QuPathGUI.getInstance()?.imageData?.server?.path; p != null && p.contains(name) } }
    if (!shown) return "'Open in QuPath' did not open " + name + " in the viewer in 30 s (status '" + UiAudit.fx { dlg.status.text } + "', viewer shows '" + UiAudit.fx { QuPathGUI.getInstance()?.imageData?.server?.path } + "', windows " + uaStageTitles() + ")"
    Map info = uaImageIoInfo(image.path as String)
    def server = UiAudit.fx { QuPathGUI.getInstance().imageData.server }
    return (server.width == info.width && server.height == info.height) ? null : "the image opened has " + server.width + " x " + server.height + ", the file has " + info.width + " x " + info.height
}

/** True when the results of this tool are placed on the open image: the tool declares a points or shapes output and the image it applies to is the one open in QuPath. */
boolean uaPlacedOnOpenImage(Map tool, Map inputs) {
    boolean hasPlaced = ((List<Map>) tool.outputs).any { it.type in ["points", "shapes"] }
    if (!hasPlaced || UiAudit.firstImage == null) return false
    Map firstImageParam = (Map) ((List<Map>) tool.inputs).find { it.type in ["image", "labels"] && inputs[it.name] != null && uaResolvePath(inputs[it.name].toString()) == UiAudit.firstImage }
    return ((List<Map>) tool.outputs).findAll { it.type in ["points", "shapes"] }.every { Map o -> (o.apply_to ?: o.display?.apply_to ?: ((List<Map>) tool.inputs).find { it.type in ["image", "labels"] }?.name) == firstImageParam?.name }
}

/** ui_qupath_run_<tool> and ui_qupath_screens_<tool> for one tool: replays the prerequisite cases of a stateful app, runs the chosen case. Returns nothing; every row goes to the matrix. */
void uaRunTool(Object dlg, String app, Map tool, UaMatrix matrix) {
    String toolId = tool.id as String, id = UiAudit.sanitize(toolId)
    File dir = new File(UiAudit.shots, "qupath/" + app)
    File formPng = new File(dir, LcDialog.slug(toolId) + "_form.png"), afterPng = new File(dir, LcDialog.slug(toolId) + "_after_run.png"), resultPng = new File(dir, LcDialog.slug(toolId) + "_result.png")
    Map noCase = (Map) UiAudit.plan.no_case
    Integer index = ((Map) UiAudit.plan.chosen)[toolId] as Integer
    if (index == null) {
        String reason = noCase[toolId] != null ? "declared in no_case.json: " + noCase[toolId] : (toolId.startsWith("stress_") ? "a stress tool: it is never run through the form (a person runs it)" : null)
        if (reason != null) {
            matrix.add("ui_qupath_run_" + id, toolId, "run", "HUMAN", "no case of '" + toolId + "' is run (" + reason + "); the form checks were made")
            try { uaPrepareCase(dlg, app, tool, [:]); uaSnapshot((Stage) dlg.stage, formPng) } catch (Throwable t) { println "CHECK ui_qupath_screens_" + id + "_form FAIL " + UiAudit.describe(t) }
            matrix.add("ui_qupath_screens_" + id, toolId, "screens", "HUMAN", "'" + toolId + "' is not run, so only the form picture " + formPng.name + " exists, for a person to look at")
        } else {
            matrix.add("ui_qupath_run_" + id, toolId, "run", "NOT_RUN", "'" + toolId + "' has no case that expects COMPLETE in cases.json (failing, cancel, skipped and stress cases are not run through the form) and is not declared in no_case.json")
            matrix.add("ui_qupath_screens_" + id, toolId, "screens", "NOT_RUN", "'" + toolId + "' was not run, so there is no after-run picture")
        }
        return
    }
    List<Map> cases = (List<Map>) UiAudit.plan.cases
    Map testCase = cases[index]
    Set<String> recordsBefore = (LcRuns.runsDir().list() ?: new String[0]) as Set<String>
    Closure cleanup = { UiAudit.fx { dlg.cancelButton.fire(); null }; Thread.sleep(2000); UiAudit.fx { dlg.stopWorker(true); null }; uaCloseExtraWindows(dlg) }
    uaCheck("ui_qupath_run_" + id, toolId, "run", UiAudit.toolTimeout + 120, {
        ((List<Integer>) ((Map) UiAudit.plan.replay)[toolId] ?: []).each { Number before ->      // a stateful app: the cases that come first in cases.json run first, through the same form
            Map pre = cases[before as int]
            Map preTool = UiAudit.planTool(pre.tool as String)
            uaPrepareCase(dlg, app, preTool, (Map) (pre.inputs ?: [:]))
            uaPressRun(dlg, pre, UiAudit.toolTimeout)
            Map preOutcome = uaOutcome(dlg)
            Map preExpect = (Map) (pre.expect ?: [:])
            if ((preExpect.status ?: "COMPLETE") == "COMPLETE") uaAssert(preOutcome.responseType == "COMPLETION", "prerequisite case " + before + " (" + pre.tool + ") ended " + preOutcome.responseType + ": " + preOutcome.error)
            else uaAssert(preOutcome.responseType != "COMPLETION" && (preExpect.message_contains == null || (preOutcome.error ?: "").contains(preExpect.message_contains as String)), "prerequisite case " + before + " (" + pre.tool + ") expects " + preExpect.status + " " + (preExpect.message_contains ?: "") + " but ended " + preOutcome.responseType + ": " + preOutcome.error)
            uaCloseExtraWindows(dlg)
        }
        Map inputs = (Map) (testCase.inputs ?: [:])
        uaPrepareCase(dlg, app, tool, inputs)
        Thread.sleep(300)
        try { uaSnapshot((Stage) dlg.stage, formPng) } catch (Throwable t) { println "CHECK ui_qupath_screens_" + id + "_form FAIL " + UiAudit.describe(t) }
        uaScanControls(dlg, "form of " + toolId + " with the case", [tool: tool])
        Map expect = (Map) (testCase.expect ?: [:])
        Map ran = uaPressRun(dlg, testCase, UiAudit.toolTimeout)
        Map outcome = uaOutcome(dlg)
        uaSeedBeforeAssert(dlg)
        try { uaSnapshot((Stage) dlg.stage, afterPng) } catch (Throwable t) { println "CHECK ui_qupath_screens_" + id + "_after_run FAIL " + UiAudit.describe(t) }
        uaScanControls(dlg, "after the run of " + toolId, [tool: tool, resultTool: tool, windows: [app + ": " + tool.label]])
        List<String> bad = [], unverified = []
        if (outcome.responseType != "COMPLETION") bad << ("the run ended " + outcome.responseType + ", the case expects COMPLETE (" + (outcome.error ?: "") + ")")
        else {
            bad.addAll(uaResultProblems(dlg, tool, outcome, expect, unverified))
            Stage rs = (Stage) UiAudit.fx { dlg.lastResultStage }
            if (rs != null) try { uaSnapshot(rs, resultPng) } catch (Throwable t) { println "CHECK ui_qupath_screens_" + id + "_result FAIL " + UiAudit.describe(t) }
            if (uaPlacedOnOpenImage(tool, inputs)) bad.addAll(uaPlacementProblems(dlg, app, outcome, UiAudit.firstImage))
            String open = uaOpenResult(dlg, outcome)
            if (open != null) bad << open
            if (outcome.recordProblem) bad << ("run record: " + outcome.recordProblem)
        }
        String record = null
        try { record = uaRunRecord(recordsBefore, toolId) } catch (Throwable t) { bad << UiAudit.describe(t) }
        if (expect.max_seconds != null && (ran.seconds as double) > (expect.max_seconds as double)) bad << ("the run took " + String.format("%.1f", ran.seconds) + " s, the case allows " + expect.max_seconds + " s")
        unverified.addAll(expect.keySet().findAll { it == "progress_events_min" }.collect { "expect." + it })
        if (unverified) matrix.add("ui_qupath_run_" + id + "_unverified", toolId, "run", "HUMAN", "expectation(s) the GUI cannot check: " + unverified.unique().join(", "))
        uaAssert(bad.isEmpty(), bad.join("; "))
        uaCloseExtraWindows(dlg)
        "COMPLETE in " + String.format("%.1f", ran.seconds) + " s, " + ((Map) outcome.outputs)?.results?.size() + " result(s) shown and checked against their files" + (uaPlacedOnOpenImage(tool, inputs) ? " and the image's hierarchy" : "") + ", record " + record
    }, cleanup, afterPng.path)
    uaCloseExtraWindows(dlg)
    uaScreens(id, toolId, formPng, afterPng, matrix)
}

/** ui_qupath_screens_<tool>: EVIDENCE FOR A PERSON, not proof that the picture is right: both files exist, were written in this launch, have a plausible size and are not one flat colour. */
void uaScreens(String id, String toolId, File formPng, File afterPng, UaMatrix matrix) {
    uaCheck("ui_qupath_screens_" + id, toolId, "screens", UiAudit.controlTimeout, {
        List<String> bad = uaScreenshotProblems(formPng, UiAudit.startedMillis) + uaScreenshotProblems(afterPng, UiAudit.startedMillis)
        uaAssert(bad.isEmpty(), bad.join("; "))
        formPng.name + " and " + afterPng.name + " exist, are fresh and of plausible size (look at them: this does not show the picture is right)"
    }, null, formPng.path)
}

/** The panel Run check, after a real run: the buttons went busy and back, as shown by listeners on their disable state; and the refusal of an empty required input. */
void uaPanelRun(Object dlg, String refusal, Map evidence) {
    uaCheck("ui_qupath_panel_run", "", "run", UiAudit.controlTimeout, {
        uaAssert(UiAudit.runVia == "click", "LC_UI_RUN_VIA=" + UiAudit.runVia + ": the Run button was not clicked in this launch, so it is not shown to work")
        uaAssert(evidence != null, "no tool was run, so Run was not shown to start a run")
        uaAssert(evidence.responseType == "COMPLETION", "the run the case expects to COMPLETE ended " + evidence.responseType)
        uaAssert(evidence.runWentBusy == true, "Run did not go disabled while the run was going")
        uaAssert(evidence.cancelWentOn == true, "Cancel did not become enabled while the run was going")
        uaAssert(UiAudit.fx { !dlg.runButton.disable && dlg.cancelButton.disable } == true, "after the run Run is not enabled and Cancel disabled")
        "a real click started a run (Run off, Cancel on, then back); " + refusal
    })
}
