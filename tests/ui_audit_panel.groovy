// ================================================================== UI audit, part 4: the panel controls (choosers, Run, Cancel, Details, Rescan apps, Restart worker, Keep the worker, Copy as command) and the multi-app checks
// Expected values come from the registry files on disk, the Tools CLI schema (the plan), the case files and the files a run writes; buttons are activated with real mouse clicks (uaClick).
import javafx.scene.Node
import javafx.scene.control.*
import javafx.stage.Stage

/** The apps of the registry on disk that QuPath must list: an entry whose `hosts` is absent or names qupath (the discovery rule, read from the files, not from the window). */
Set<String> uaListedDiskApps() {
    File[] files = new File(UiAudit.home, "apps").listFiles({ File f -> f.name.endsWith(".json") && !f.name.endsWith(".schema.json") } as FileFilter)
    return (files ?: new File[0]).findAll { File f -> Object hosts = ((Map) LcJson.parse(f)).hosts; hosts == null || (hosts instanceof List && hosts.contains("qupath")) }.collect { it.name.replaceAll(/\.json$/, "") } as Set<String>
}

/** True when the chooser entry `shown` is the plan tool: the label, or `label (id)` when two tools share a label. */
boolean uaIsTool(String shown, Map tool) { return shown == tool.label || shown == tool.label + " (" + tool.id + ")" }

/** The chooser entry of a plan tool. */
String uaChoiceOf(Object dlg, Map tool) {
    String shown = (String) UiAudit.fx { dlg.toolBox.items.find { uaIsTool(it, tool) } }
    uaAssert(shown != null, "the tool chooser offers no entry for '" + tool.id + "' (it offers " + UiAudit.fx { new ArrayList(dlg.toolBox.items) } + ")")
    return shown
}

/** The button of the window with this text (the buttons of the button row are not fields of the dialog). */
Button uaButton(Object dlg, String text) {
    Button b = (Button) UiAudit.fx { uaAllNodes(dlg.stage.scene.root).find { it instanceof Button && it.text == text } }
    uaAssert(b != null, "no button '" + text + "' in the window")
    return b
}

/** Picks an app and a tool of the dialog the way a person does (the tool chooser value triggers the form). */
void uaPick(Object dlg, String app, String toolChoice) {
    UiAudit.fx { dlg.appBox.value = app; null }
    if (toolChoice != null) UiAudit.fx { dlg.toolBox.value = toolChoice; null }
    Thread.sleep(500)
}

/** app_chooser and tool_chooser: the app is offered and every offered app is on disk; choosing an app offers exactly the tools of its CLI schema; choosing a tool builds its form and shows its description. */
void uaPanelChoosers(Object dlg, String app) {
    int t = UiAudit.controlTimeout
    List<Map> tools = (List<Map>) UiAudit.plan.tools
    uaCheck("ui_qupath_panel_app_chooser", "", "app_chooser", t, {
        List<String> offered = (List<String>) UiAudit.fx { new ArrayList<String>(dlg.appBox.items) }
        Set<String> disk = uaListedDiskApps()
        uaAssert(offered as Set == disk, "the chooser offers " + offered + ", the registry files on disk say QuPath lists " + disk)
        uaAssert(offered.contains(app), "'" + app + "' is not offered (offers " + offered + ")")
        uaPick(dlg, app, null)
        List<String> shown = (List<String>) UiAudit.fx { new ArrayList<String>(dlg.toolBox.items) }
        uaAssert(shown.size() == tools.size() && tools.every { Map tool -> shown.any { uaIsTool(it, tool) } }, "'" + app + "' shows tools " + shown + ", the Tools CLI lists " + tools.collect { it.id })
        "offers " + offered + " (the registry files list the same); '" + app + "' shows its " + tools.size() + " CLI tool(s)"
    })
    uaCheck("ui_qupath_panel_tool_chooser", "", "tool_chooser", t, {
        uaPick(dlg, app, null)
        tools.each { Map tool ->
            String shown = uaChoiceOf(dlg, tool)
            UiAudit.fx { dlg.toolBox.value = shown; null }
            Thread.sleep(300)
            uaAssert(UiAudit.fx { dlg.currentTool?.id } == tool.id, "choosing '" + shown + "' built the form of " + UiAudit.fx { dlg.currentTool?.id })
            uaAssert(UiAudit.fx { dlg.description.text } == (tool.description ?: ""), "the description of '" + shown + "' is not the schema's")
        }
        "each of " + tools.size() + " tool(s) builds its form and shows the description of the schema"
    })
}

/** Rescan apps (real click): the chosen app stays, the status says how many apps the registry files list. */
void uaPanelRescan(Object dlg, String app) {
    uaCheck("ui_qupath_panel_rescan_apps", "", "rescan_apps", UiAudit.controlTimeout, {
        uaPick(dlg, app, null)
        int n = uaListedDiskApps().size()
        uaClick(uaButton(dlg, "Rescan apps"))
        uaAssert(uaWaitFor(10) { ((String) UiAudit.fx { dlg.status.text }).startsWith("Found ") }, "no 'Found N app(s)' after Rescan apps; status '" + UiAudit.fx { dlg.status.text } + "'")
        String status = (String) UiAudit.fx { dlg.status.text }
        uaAssert(status.startsWith("Found " + n + " app(s)"), "status is '" + status + "', the registry files list " + n + " app(s)")
        uaAssert(UiAudit.fx { dlg.appBox.value } == app, "the chosen app changed to " + UiAudit.fx { dlg.appBox.value })
        "clicked; status '" + status.take(80) + "', app kept"
    })
}

/** Cancel with nothing running (real click on a disabled button): disabled, and the click changes nothing. */
void uaPanelCancelIdle(Object dlg) {
    uaCheck("ui_qupath_panel_cancel", "", "cancel", UiAudit.controlTimeout, {
        String before = (String) UiAudit.fx { dlg.status.text }
        uaAssert(UiAudit.fx { dlg.cancelButton.disable } == true, "Cancel is enabled while nothing runs")
        uaClick(dlg.cancelButton)
        Thread.sleep(300)
        uaAssert(UiAudit.fx { dlg.status.text } == before && UiAudit.fx { !dlg.running }, "clicking Cancel with nothing running changed the status to '" + UiAudit.fx { dlg.status.text } + "'")
        "disabled; a click left the status at '" + before.take(60) + "' and started nothing"
    })
}

/** Copy as command: a real click opens the menu, its entries are the documented two, and each puts text on the clipboard that parses back to the app's interpreter, the app, the tool and the values of the case. */
void uaPanelCopy(Object dlg, String app, Map tool, Map testCase) {
    int t = UiAudit.controlTimeout
    Map inputs = (Map) (testCase?.inputs ?: [:])
    uaPrepareCase(dlg, app, tool, inputs)
    Map<String, Object> expected = uaExpectedValues(tool, inputs)
    MenuButton menu = (MenuButton) UiAudit.fx { uaAllNodes(dlg.stage.scene.root).find { it instanceof MenuButton } }
    uaCheck("ui_qupath_panel_copy_as_command", "", "copy_as_command", t, {
        uaAssert(menu != null && menu.text == "Copy as command", "no 'Copy as command' menu in the window")
        uaClick(menu)
        uaAssert(uaWaitFor(5) { UiAudit.fx { menu.showing } }, "a click on 'Copy as command' did not open its menu")
        List<String> entries = (List<String>) UiAudit.fx { menu.items.collect { it.text } }
        UiAudit.fx { menu.hide(); null }
        uaAssert(entries == UaTable.COPY_MENU, "the menu holds " + entries + ", the documented entries are " + UaTable.COPY_MENU)
        "a click opened the menu: entries " + entries
    })
    [[id: "copy_terminal_command", text: "Terminal command", check: { String text -> uaTerminalProblems(text, app, tool, expected) }],
     [id: "copy_python_snippet", text: "Python snippet", check: { String text -> uaPythonProblems(text, app, tool, expected) }]].each { Map entry ->
        uaCheck("ui_qupath_panel_" + entry.id, "", entry.id as String, t, {
            uaAssert(menu != null, "no menu")
            MenuItem item = (MenuItem) UiAudit.fx { menu.items.find { it.text == entry.text } }
            uaAssert(item != null, "no entry '" + entry.text + "'")
            UiAudit.fx { dlg.lastCopied = null; javafx.scene.input.Clipboard.systemClipboard.setContent(new javafx.scene.input.ClipboardContent()); item.fire(); null }
            String clip = (String) UiAudit.fx { javafx.scene.input.Clipboard.systemClipboard.getString() }
            String status = (String) UiAudit.fx { dlg.status.text }
            uaAssert(clip != null && !clip.isEmpty(), "the clipboard is empty after '" + entry.text + "'")
            uaAssert(status.startsWith("copied the "), "status is '" + status + "', expected 'copied the ...'")
            List<String> bad = ((Closure<List<String>>) entry.check)(clip)
            uaAssert(bad.isEmpty(), bad.join("; "))
            "the clipboard text (" + clip.length() + " characters) parses back to the interpreter of the registry entry, '" + app + "', '" + tool.id + "' and " + expected.size() + " predicted value(s)"
        })
    }
}

/** Details after a run (real click): opens the report window; its text holds the run's report and the worker output. */
void uaPanelDetails(Object dlg) {
    uaCheck("ui_qupath_panel_details", "", "details", UiAudit.controlTimeout, {
        uaAssert(UiAudit.fx { !dlg.detailsButton.disable }, "Details is still disabled after a run")
        uaClick(dlg.detailsButton)
        uaAssert(uaWaitFor(10) { UiAudit.fx { javafx.stage.Window.getWindows().any { it instanceof Stage && it.showing && it.title == "LabConstrictor: last run" } } }, "no 'LabConstrictor: last run' window after a click on Details")
        Stage st = (Stage) UiAudit.fx { javafx.stage.Window.getWindows().find { it instanceof Stage && it.showing && it.title == "LabConstrictor: last run" } }
        String text = (String) UiAudit.fx { uaAllNodes(st.scene.root).find { it instanceof TextArea }?.text }
        uaAssert(text != null && text.contains("\"responseType\"") && text.contains("worker output"), "the window does not show the report: " + text?.take(80))
        uaScanControls(dlg, "details window", [windows: ["LabConstrictor: last run"], tool: dlg.currentTool])
        UiAudit.fx { st.hide(); null }
        "window shows the report and the worker output (" + text.length() + " characters)"
    })
}

/** Restart worker (real click): after a run the kept worker exists; the click ends it and the status says so. */
void uaPanelRestart(Object dlg) {
    uaCheck("ui_qupath_panel_restart_worker", "", "restart_worker", UiAudit.controlTimeout, {
        uaAssert(uaWaitFor(5) { UiAudit.fx { dlg.worker != null } }, "no worker is kept after a run (keep-worker is on), so there is nothing to restart")
        uaClick(uaButton(dlg, "Restart worker"))
        uaAssert(uaWaitFor(10) { UiAudit.fx { dlg.status.text } == "worker stopped" }, "status is '" + UiAudit.fx { dlg.status.text } + "', expected 'worker stopped'")
        uaAssert(uaWaitFor(15) { UiAudit.fx { dlg.worker == null } }, "the worker is still there 15 s after Restart worker")
        "a click ended the kept worker; status 'worker stopped'"
    })
}

/** Keep the worker running (real clicks on the check box): ticked, the worker is there after a run; unticked, it is gone after the next one. Runs the case twice. */
void uaPanelKeepWorker(Object dlg, String app, Map tool, Map testCase) {
    uaCheck("ui_qupath_panel_keep_worker", "", "keep_worker", UiAudit.toolTimeout * 2 + 60, {
        CheckBox box = dlg.keepWorker
        uaAssert(UiAudit.fx { box.selected } == true, "the box is not ticked at the start (the documented default is on)")
        uaClick(box)
        uaAssert(UiAudit.fx { !box.selected }, "a click did not untick the box")
        uaResetFixtures()
        uaPrepareCase(dlg, app, tool, (Map) (testCase.inputs ?: [:]))
        uaPressRun(dlg, testCase, UiAudit.toolTimeout)
        uaAssert(uaWaitFor(20) { UiAudit.fx { dlg.worker == null } }, "unticked, but a worker is still kept after the run")
        uaCloseExtraWindows(dlg)      // the results window of this run; the one of the last run stays for the scan of the phase
        uaClick(box)
        uaAssert(UiAudit.fx { box.selected }, "a click did not tick the box again")
        uaResetFixtures()
        uaPressRun(dlg, testCase, UiAudit.toolTimeout)
        uaAssert(uaWaitFor(10) { UiAudit.fx { dlg.worker != null } }, "ticked, but no worker is kept after the run")
        "unticked: no worker after a run; ticked: a worker is kept"
    })
}

/** Run, refused part: a tool whose schema requires an input, left empty, gives the sentence and starts nothing (a real click on Run). Returns what it did. */
String uaRunRefusal(Object dlg, String app) {
    for (Map tool in (List<Map>) UiAudit.plan.tools) {
        Map missing = (Map) ((List) tool.inputs).find { Map p -> p.required && !p.nullable && p.type in ["image", "labels", "table", "file", "folder"] && p.default == null }
        if (missing == null) continue
        uaPick(dlg, app, uaChoiceOf(dlg, tool))
        UiAudit.fx { dlg.onTool(); null }
        UiAudit.fx {
            def box = dlg.imageBoxes[missing.name]
            if (box != null) { uaFileChoice(box); dlg.fileFields[missing.name].text = "" } else dlg.setters[missing.name]("")
            dlg.status.text = "idle"
            null
        }
        uaPressRunButton(dlg)
        Thread.sleep(500)
        uaAssert(UiAudit.fx { !dlg.running } == true, "Run started with the required '" + missing.label + "' empty")
        String text = (String) UiAudit.fx { dlg.status.text }
        uaAssert(text.startsWith("'" + missing.label + "' is required"), "status is '" + text + "', expected \"'" + missing.label + "' is required...\"")
        return "an empty '" + missing.label + "' of '" + tool.id + "' is refused with '" + text + "'"
    }
    return "no tool of the app has a required input to leave empty (the refusal was not tried)"
}

/** The two-app situation: every app of LC_APPS is listed with all its tools (from the Tools CLI), and switching shows the right tools. `schemas`: app -> plan tools. */
void uaMultiChecks(Object dlg, List<String> names, Map<String, List<Map>> schemas) {
    int t = UiAudit.controlTimeout
    Set<String> disk = uaListedDiskApps()
    names.each { String app ->
        uaCheck("ui_qupath_multi_lists_" + UiAudit.sanitize(app), "", "app_chooser", t, {
            List<String> offered = (List<String>) UiAudit.fx { new ArrayList<String>(dlg.appBox.items) }
            uaAssert(disk.contains(app), "the registry files do not say QuPath lists '" + app + "'")
            uaAssert(offered.contains(app), "'" + app + "' is not in the app chooser (it offers " + offered + ")")
            uaPick(dlg, app, null)
            List<String> shown = (List<String>) UiAudit.fx { new ArrayList<String>(dlg.toolBox.items) }
            List<Map> expected = schemas[app]
            uaAssert(shown.size() == expected.size() && expected.every { Map tool -> shown.any { uaIsTool(it, tool) } }, "the tool chooser of '" + app + "' offers " + shown + ", the Tools CLI lists " + expected.collect { it.id })
            "'" + app + "' listed with its " + shown.size() + " tool(s): " + shown
        })
    }
    names.each { String a ->
        names.findAll { it != a }.each { String b ->
            uaCheck("ui_qupath_multi_switch_" + UiAudit.sanitize(a) + "_" + UiAudit.sanitize(b), "", "app_chooser", t, {
                uaPick(dlg, a, null)
                uaPick(dlg, b, null)
                List<String> shown = (List<String>) UiAudit.fx { new ArrayList<String>(dlg.toolBox.items) }
                List<Map> expected = schemas[b]
                uaAssert(shown.size() == expected.size() && expected.every { Map tool -> shown.any { uaIsTool(it, tool) } }, "after switching " + a + " -> " + b + " the tool chooser offers " + shown + ", the CLI lists " + expected.collect { it.id })
                String current = (String) UiAudit.fx { dlg.currentTool?.id }
                Map planTool = expected.find { it.id == current }
                uaAssert(planTool != null, "the form shown is of tool '" + current + "', which is not a tool of '" + b + "'")
                uaAssert(UiAudit.fx { new LinkedHashSet<String>(dlg.controls.keySet()) } == (planTool.inputs.collect { it.name } as Set), "the form of '" + current + "' after the switch has the controls " + UiAudit.fx { dlg.controls.keySet() })
                a + " -> " + b + ": tools " + shown + ", form of '" + current + "'"
            })
        }
    }
}
