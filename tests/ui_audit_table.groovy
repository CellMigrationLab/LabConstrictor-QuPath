// ================================================================== UI audit, part 2: the control table, the independent expectation of every window, and the scan that compares them (set equality, both ways)
// A control is a javafx.scene.control.Control a person can act on, found by walking the scene graph of every showing window. The parts of a control's own skin (the arrow of a combo box, the editor of a spinner,
// the cells of a table, scroll bars) are not controls of their own; labels are display text. Each control has a SIGNATURE: its kind, and its text for buttons, check boxes, radio buttons, menus and titled panes.
// What must be there is computed from sources that are not the window: the fixed panel is written out below (docs/HOST_FEATURES.md), a form's controls come from the schema the Tools CLI reports for the tool
// (UaTable.paramSignatures), the results window from the outputs the schema declares. The window is then scanned and the two lists compared both ways: a control found that nobody expects is UNKNOWN,
// a control expected that is not found is MISSING; both fail ui_qupath_controls_all_known. Finding no control at all fails too (a minimum count).
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.control.*
import javafx.scene.layout.GridPane

class UaTable {
    /** The control table: id, JavaFX kind, expected effect (docs/UI_AUDIT.md prints it). The panel rows are proved by ui_audit_panel.groovy, the form rows by ui_audit_form.groovy. */
    static final List<Map> ROWS = [
        [id: "app_chooser", kind: "ComboBox", effect: "lists every registered app that the host lists; choosing one fills the tool chooser with exactly its tools"],
        [id: "tool_chooser", kind: "ComboBox", effect: "lists the tools of the app; choosing one builds its form and shows its description"],
        [id: "run", kind: "Button:Run", effect: "reads the form and starts the run (Run off, Cancel on); a missing required input is refused with its sentence and nothing starts"],
        [id: "cancel", kind: "Button:Cancel", effect: "disabled while nothing runs, and a click then changes nothing; during a run it asks the worker to stop"],
        [id: "details", kind: "Button:Details...", effect: "disabled until a run ended; then opens the read-only window with the report and the worker output"],
        [id: "rescan_apps", kind: "Button:Rescan apps", effect: "reads the registry again, keeps the chosen app, says 'Found N app(s)'"],
        [id: "restart_worker", kind: "Button:Restart worker", effect: "ends the kept worker and says 'worker stopped'"],
        [id: "keep_worker", kind: "CheckBox:Keep the worker running between runs (faster repeat runs)", effect: "ticked: the worker stays after a run; unticked: it is closed when the run ends"],
        [id: "copy_as_command", kind: "MenuButton:Copy as command", effect: "opens a menu with exactly the entries copy_terminal_command and copy_python_snippet"],
        [id: "copy_terminal_command", kind: "MenuItem:Terminal command", effect: "puts the terminal line of the current form on the clipboard and says so"],
        [id: "copy_python_snippet", kind: "MenuItem:Python snippet", effect: "puts the Python snippet of the current form on the clipboard and says so"],
        [id: "form_scroll", kind: "ScrollPane", effect: "holds the generated form (no effect of its own)"],
        [id: "progress", kind: "ProgressBar", effect: "idle at 0; indeterminate while a run is going"],
        [id: "group_section", kind: "TitledPane:<group>", effect: "a Collapsed group of parameters: expanding shows its controls"],
        [id: "advanced_settings", kind: "TitledPane:Advanced settings", effect: "the advanced parameters, folded at first: expanding shows them"],
        [id: "param_boolean", kind: "CheckBox", effect: "toggles the yes/no value the run sends"],
        [id: "param_choice", kind: "ComboBox", effect: "choosing an entry sets the declared choice at that index"],
        [id: "param_radio", kind: "RadioButton:<choice>", effect: "selecting it sets the (hidden) choice to its text; the combo box behind it is the param_choice of the row"],
        [id: "param_integer", kind: "Spinner", effect: "a typed or stepped whole number is what the run sends"],
        [id: "param_float", kind: "Spinner", effect: "a typed or stepped decimal number is what the run sends"],
        [id: "param_slider", kind: "Slider", effect: "moving it sets the spinner beside it"],
        [id: "param_text", kind: "TextField", effect: "the text is the value; also the path field of a file, table or folder, the file field of an image, and the pixel-size field"],
        [id: "param_choices_dropdown", kind: "ComboBox", effect: "a ChoicesFrom dropdown that replaces the text field when the source tool answers; choosing sets the text"],
        [id: "param_browse", kind: "Button:...", effect: "opens the native file or folder chooser and puts the choice into the field beside it (HUMAN: a native dialog cannot be driven under Xvfb; presence, enabled state and an action are checked)"],
        [id: "param_image_source", kind: "ComboBox", effect: "the image or labels source: (none), the open image, project images, File..."],
        [id: "param_image_area", kind: "ComboBox", effect: "Image area: enabled only for the image open in QuPath; for a file or a project image it stays 'whole image' and is disabled"],
        [id: "param_image_channel", kind: "ComboBox", effect: "PickChannel: the channels of an image that has more than one; the run sends the chosen index"],
        [id: "param_selection", kind: "CheckBox:use the selection", effect: "RegionOf: use the selected annotations; disables the source and file field while ticked"],
        [id: "param_set", kind: "CheckBox:set", effect: "the 'set' box of an optional parameter: ticked enables its control, unticked sends nothing"],
        [id: "param_use_calibration", kind: "Button:Use the image's calibration", effect: "drops a typed pixel size and takes the image's own calibration again"],
        [id: "result_scroll", kind: "ScrollPane", effect: "holds the results of a run"],
        [id: "result_table", kind: "TableView", effect: "the rows of a table or points result"],
        [id: "result_open_in_qupath", kind: "Button:Open in QuPath", effect: "opens the result image in the QuPath viewer"],
        [id: "details_text", kind: "TextArea", effect: "the read-only report of the last run"],
        [id: "alert_ok", kind: "Button:OK", effect: "closes the 'nothing found' dialog"]
    ]
    /** The kinds the table knows (the part before ':'): a signature of a found control must have one of these kinds. */
    static final Set<String> KINDS = ROWS.collect { ((String) it.kind).split(":")[0] } as Set<String>
    /** The fixed panel of the window, in the order of the window (docs/HOST_FEATURES.md). */
    static final List<String> PANEL = ["ComboBox", "ComboBox", "Button:Run", "ProgressBar", "CheckBox:Keep the worker running between runs (faster repeat runs)", "ScrollPane", "Button:Cancel",
                                       "Button:Rescan apps", "Button:Restart worker", "Button:Details...", "MenuButton:Copy as command"]
    static final List<String> COPY_MENU = ["Terminal command", "Python snippet"]

    /** The controls one parameter must have, from its schema entry (sorted). */
    static List<String> paramSignatures(Map p) {
        List<String> s = []
        boolean slider = p.widget == "slider" && !p.nullable && p.minimum != null && p.maximum != null
        switch (p.type) {
            case "boolean": s << "CheckBox"; break
            case "choice":
                s << "ComboBox"
                if (p.widget == "radio" && !p.nullable) p.choices.each { s << "RadioButton:" + it }
                break
            case "integer": s << "Spinner"; if (slider) s << "Slider"; break
            case "float":
                if (p.pixel_size_of) s.addAll(["TextField", "Button:Use the image's calibration"])
                else { s << "Spinner"; if (slider) s << "Slider" }
                break
            case ["image", "labels"]:
                s.addAll(["ComboBox", "ComboBox", "TextField", "Button:..."])      // the source chooser, the Image area chooser, the file field, browse
                if (p.pick_channel) s << "ComboBox"
                if (p.region_of) s << "CheckBox:use the selection"
                break
            case ["file", "table", "folder"]: s.addAll(["TextField", "Button:..."]); break
            default:
                s << "TextField"
                if (p.choices_from) s << "ComboBox"
        }
        if (p.nullable && !p.region_of && !p.pixel_size_of) s << "CheckBox:set"
        return s.sort(false)
    }

    /** The label of a parameter's row. */
    static String labelOf(Map p) { return (p.label as String) + (p.unit ? " (" + p.unit + ")" : "") }

    /** The folded sections a tool's form must have: Advanced settings, and one per Collapsed group. */
    static List<String> sections(Map tool) {
        List<Map> inputs = (List<Map>) tool.inputs
        List<String> out = []
        inputs.findAll { it.group_collapsed && !it.advanced && it.group }.collect { it.group as String }.unique().each { out << ("TitledPane:" + it) }
        if (inputs.any { it.advanced }) out << "TitledPane:Advanced settings"
        return out.sort(false)
    }

    /** The controls of a results window: the scroll pane, and for each declared output the control its section has. */
    static List<String> resultSignatures(Map tool) {
        List<String> s = ["ScrollPane"]
        ((List<Map>) (tool?.outputs ?: [])).each { Map o ->
            if (o.type in ["image", "labels"]) s << "Button:Open in QuPath"
            if (o.type in ["table", "points"]) s << "TableView"
        }
        return s.sort(false)
    }

    /** The control table as markdown rows (docs/UI_AUDIT.md). */
    static String markdown() { return ROWS.collect { "| `" + it.id + "` | `" + it.kind + "` | " + it.effect + " |" }.join("\n") }
}

/** Every node under `root`: the skin's children, plus the content of a titled pane and a scroll pane (their skin may not be built yet). */
List<Node> uaAllNodes(Node root) {
    List<Node> out = []
    Closure walk
    walk = { Node n ->
        out << n
        if (n instanceof Parent) ((Parent) n).childrenUnmodifiable.each { Node c -> walk(c) }
        if (n instanceof TitledPane && n.content != null && !out.contains(n.content)) walk(n.content)
        if (n instanceof ScrollPane && n.content != null && !out.contains(n.content)) walk(n.content)
    }
    walk(root)
    return out
}

/** True for the controls whose insides are part of their skin: nothing below them is a control of its own. */
boolean uaIsLeafControl(Node n) {
    return n instanceof ComboBox || n instanceof Spinner || n instanceof MenuButton || n instanceof Slider || n instanceof TableView || n instanceof TextInputControl ||
            n instanceof ButtonBase || n instanceof ProgressBar || n instanceof ScrollBar
}

/** The controls under `root`: Control nodes that are not inside another leaf control, and not labels or scroll bars. */
List<Node> uaControlsOf(Node root) {
    return uaAllNodes(root).findAll { Node n ->
        if (!(n instanceof Control) || n instanceof ScrollBar || (n instanceof Labeled && !(n instanceof ButtonBase) && !(n instanceof TitledPane))) return false
        for (Node p = n.parent; p != null; p = p.parent) if (uaIsLeafControl(p)) return false
        return true
    }
}

/** The signature of a control: its kind, and its text where the text tells it apart. */
String uaSignature(Node n) {
    String kind = n instanceof Spinner ? "Spinner" : n instanceof Slider ? "Slider" : n instanceof ComboBox ? "ComboBox" : n instanceof TextArea ? "TextArea" : n instanceof TextInputControl ? "TextField" :
            n instanceof RadioButton ? "RadioButton" : n instanceof CheckBox ? "CheckBox" : n instanceof MenuButton ? "MenuButton" : n instanceof Button ? "Button" : n instanceof TitledPane ? "TitledPane" :
            n instanceof ScrollPane ? "ScrollPane" : n instanceof TableView ? "TableView" : n instanceof ProgressBar ? "ProgressBar" : n.class.simpleName
    return kind in ["Button", "CheckBox", "RadioButton", "MenuButton", "TitledPane"] && !(((Labeled) n).text ?: "").isEmpty() ? kind + ":" + ((Labeled) n).text : kind      // a yes/no parameter's box has no text of its own
}

/** Both-way comparison of two signature lists as multisets: "unknown" (found, not expected) and "missing" (expected, not found), each as readable text. */
Map uaCompare(List<String> found, List<String> expected, String where) {
    List<String> unknown = new ArrayList<String>(found), missing = new ArrayList<String>(expected)
    expected.each { String e -> unknown.remove(e) }
    found.each { String f -> missing.remove(f) }
    return [unknown: unknown.collect { it + " (" + where + ")" }, missing: missing.collect { it + " (" + where + ")" }]
}

/** The titles of the showing windows other than QuPath's own main window. */
List<String> uaStageTitles() {
    return (List<String>) UiAudit.fx {
        Stage main = QuPathGUI.getInstance()?.stage
        new ArrayList<javafx.stage.Window>(javafx.stage.Window.getWindows()).findAll { it instanceof Stage && it.showing && !it.is(main) }.collect { (it as Stage).title ?: "(untitled)" }
    }
}

/** The controls of the form, per parameter label, as found: for each two-column row of a grid of the form, the signatures of the controls of the second column. */
Map<String, List<String>> uaFoundRows(Node formBox) {
    Map<String, List<String>> rows = [:]
    uaAllNodes(formBox).findAll { it instanceof GridPane }.each { Node g ->
        GridPane grid = (GridPane) g
        grid.children.findAll { it instanceof Label && GridPane.getColumnIndex(it) == 0 && (GridPane.getColumnSpan(it) ?: 1) == 1 }.each { Node label ->
            Integer row = GridPane.getRowIndex(label)
            Node cell = grid.children.find { GridPane.getColumnIndex(it) == 1 && GridPane.getRowIndex(it) == row }
            List<String> sigs = cell == null ? [] : uaControlsOf(cell).collect { uaSignature(it) }
            rows.computeIfAbsent(((Label) label).text, { new ArrayList<String>() }).addAll(sigs)
        }
    }
    rows.each { k, v -> v.sort() }
    return rows
}

/** Compares the main window with what the plan says it must hold; `tool` is the plan's tool of the form shown (or null before any form). Adds to UiAudit.unknown / missing and returns the number of controls found. */
int uaScanMain(Object dlg, Map tool, String where) {
    return (int) UiAudit.fx {
        Node root = dlg.stage.scene.root
        Node scroll = uaAllNodes(root).find { it instanceof ScrollPane && it.content != null && it.content.is(dlg.formBox) }
        List<Node> panelNodes = uaControlsOf(root).findAll { n -> scroll == null || !uaAllNodes(scroll).contains(n) || n.is(scroll) }
        Map cmp = uaCompare(panelNodes.collect { uaSignature(it) }, UaTable.PANEL, where + ", panel")
        UiAudit.unknown.addAll((List<String>) cmp.unknown); UiAudit.missing.addAll((List<String>) cmp.missing)
        int found = panelNodes.size()
        MenuButton menu = (MenuButton) panelNodes.find { it instanceof MenuButton }
        if (menu != null) {
            List<String> items = menu.items.collect { it.text }
            Map menuCmp = uaCompare(items, UaTable.COPY_MENU, where + ", Copy as command menu")
            UiAudit.unknown.addAll((List<String>) menuCmp.unknown); UiAudit.missing.addAll((List<String>) menuCmp.missing)
            found += items.size()
        }
        if (tool != null) {
            Map<String, List<String>> foundRows = uaFoundRows(dlg.formBox)
            Map<String, List<String>> wantRows = [:]
            ((List<Map>) tool.inputs).each { Map p -> wantRows.computeIfAbsent(UaTable.labelOf(p), { new ArrayList<String>() }).addAll(UaTable.paramSignatures(p)) }
            wantRows.each { k, v -> v.sort() }
            (foundRows.keySet() + wantRows.keySet()).unique().each { String label ->
                Map c = uaCompare(foundRows[label] ?: [], wantRows[label] ?: [], where + ", row '" + label + "'")
                if (!wantRows.containsKey(label)) c.unknown << ("row '" + label + "' is in the form but not in the schema (" + where + ")")
                if (!foundRows.containsKey(label)) c.missing << ("row '" + label + "' is in the schema but not in the form (" + where + ")")
                UiAudit.unknown.addAll((List<String>) c.unknown); UiAudit.missing.addAll((List<String>) c.missing)
                found += (foundRows[label] ?: []).size()
            }
            List<String> panes = uaAllNodes(dlg.formBox).findAll { it instanceof TitledPane }.collect { uaSignature(it) }.sort()
            Map paneCmp = uaCompare(panes, UaTable.sections(tool), where + ", sections")
            UiAudit.unknown.addAll((List<String>) paneCmp.unknown); UiAudit.missing.addAll((List<String>) paneCmp.missing)
            found += panes.size()
        }
        found
    }
}

/** Scans the open windows: the main one against the plan, the others against what the checkpoint expects. `expect` = [windows: titles that must be showing (besides the main), tool: plan tool of the form,
 *  resultTool: plan tool whose results window is open, details: true when the Details window is open, alert: true when an OK dialog is expected]. Writes the side file of the launch. */
void uaScanControls(Object dlg, String where, Map expect = [:]) {
    int found = uaScanMain(dlg, (Map) expect.tool, where)
    List<String> expectedTitles = ["LabConstrictor tools"] + (List<String>) (expect.windows ?: [])
    List<String> actual = uaStageTitles()
    Map windows = uaCompare(actual, expectedTitles, where + ", windows")
    UiAudit.windowProblems.addAll((List<String>) windows.unknown.collect { "unexpected window " + it }); UiAudit.windowProblems.addAll((List<String>) windows.missing.collect { "missing window " + it })
    UiAudit.fx {
        Stage main = QuPathGUI.getInstance()?.stage
        new ArrayList<javafx.stage.Window>(javafx.stage.Window.getWindows()).findAll { it instanceof Stage && it.showing && !it.is(main) && !it.is(dlg.stage) && it.scene != null }.each { javafx.stage.Window w ->
            String title = (w as Stage).title ?: "(untitled)"
            List<String> want = title == "LabConstrictor: last run" ? ["TextArea"] : title.contains(": ") ? UaTable.resultSignatures((Map) expect.resultTool) : (expect.alert ? ["Button:OK"] : [])
            List<Node> nodes = uaControlsOf(w.scene.root)
            Map c = uaCompare(nodes.collect { uaSignature(it) }.sort(), want, where + ", window '" + title + "'")
            UiAudit.unknown.addAll((List<String>) c.unknown); UiAudit.missing.addAll((List<String>) c.missing)
            found += nodes.size()
        }
        null
    }
    UiAudit.controlCount = Math.max(UiAudit.controlCount, found)
    uaWriteControlsFile()
}

/** The side file of this launch: what the scans found, read by `tests/ui_audit_matrix.py finish` for ui_qupath_controls_all_known. */
void uaWriteControlsFile() {
    String key = UiAudit.phase == "tool" ? "tool:" + UiAudit.toolId : UiAudit.phase
    File dir = new File(UiAudit.shots, "qupath/" + UiAudit.app)
    dir.mkdirs()
    Map doc = [phase: key, count: UiAudit.controlCount, unknown: new ArrayList<String>(new LinkedHashSet<String>(UiAudit.unknown)), missing: new ArrayList<String>(new LinkedHashSet<String>(UiAudit.missing)),
               windows: new ArrayList<String>(new LinkedHashSet<String>(UiAudit.windowProblems))]
    File target = new File(dir, "controls_" + UiAudit.sanitize(key) + ".json")
    File temp = File.createTempFile("controls_", ".tmp", dir)
    temp.setText(LcJson.pretty(doc), "UTF-8")
    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
}

/** Geometry of the main window: the stage is inside the screen, every visible control of the panel is inside the scene, every visible control of the form is not clipped horizontally by the scroll pane
 *  and has a size. Returns problems (empty when fine). */
List<String> uaGeometryProblems(Object dlg) {
    return (List<String>) UiAudit.fx {
        List<String> bad = []
        javafx.stage.Stage st = dlg.stage
        javafx.geometry.Rectangle2D screen = javafx.stage.Screen.getPrimary().visualBounds
        if (st.x < screen.minX - 1 || st.y < screen.minY - 1 || st.x + st.width > screen.maxX + 1 || st.y + st.height > screen.maxY + 1) bad << ("window " + [st.x, st.y, st.width, st.height] + " is outside the screen " + [screen.minX, screen.minY, screen.maxX, screen.maxY])
        Node root = st.scene.root
        ScrollPane scroll = (ScrollPane) uaAllNodes(root).find { it instanceof ScrollPane && it.content != null && it.content.is(dlg.formBox) }
        uaControlsOf(root).each { Node n ->
            for (Node p = n; p != null; p = p.parent) if (!p.visible || !p.managed) return
            javafx.geometry.Bounds b = n.localToScene(n.boundsInLocal)
            boolean inForm = scroll != null && n != scroll && uaAllNodes(scroll).contains(n)
            String name = uaSignature(n)
            if (b.width <= 0 || b.height <= 0) bad << (name + " has no size")
            else if (!inForm && (b.minX < -0.5 || b.maxX > st.scene.width + 0.5 || b.minY < -0.5 || b.maxY > st.scene.height + 0.5)) bad << (name + " is outside the window " + [b.minX, b.minY, b.maxX, b.maxY])
            else if (inForm && b.maxX > scroll.localToScene(scroll.boundsInLocal).maxX + 0.5) bad << (name + " is clipped on the right by the form's scroll pane")
        }
        bad
    }
}
