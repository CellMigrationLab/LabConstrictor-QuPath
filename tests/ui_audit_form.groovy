// ================================================================== UI audit, part 4: the form of every tool: one widget per declared parameter (_opens), every form control activated and read back (_controls)
import javafx.scene.Node
import javafx.scene.control.*

/** The value the form would send for parameter `name` now (the getter the run uses). */
Object uaGet(Object dlg, String name) { return UiAudit.fx { dlg.getters[name]() } }

/** The nodes of the row of parameter `name`, all kinds. */
List<Node> uaRowNodes(Object dlg, String name) { return (List<Node>) UiAudit.fx { uaAllNodes((Node) dlg.wrappers[name]) } }

/** The widget classes one parameter must have, as a list of problems (empty when the row is as declared). */
List<String> uaRowProblems(Object dlg, Map p) {
    String name = p.name
    List<String> bad = []
    Object c = dlg.controls[name]
    List<Node> nodes = uaRowNodes(dlg, name)
    if (c == null || dlg.wrappers[name] == null) return ["'" + name + "': no control in the form"]
    Closure expectKind = { Class kind, Object actual, String what -> if (!kind.isInstance(actual)) bad << (name + ": " + what + " is " + actual?.class?.simpleName + ", expected " + kind.simpleName) }
    boolean slider = p.widget == "slider" && !p.nullable && p.minimum != null && p.maximum != null
    switch (p.type) {
        case "boolean": expectKind(CheckBox, c, "the control"); break
        case "choice":
            expectKind(ComboBox, c, "the control")
            if (c instanceof ComboBox && c.items.size() != p.choices.size()) bad << (name + ": " + c.items.size() + " entries, " + p.choices.size() + " declared")
            int radios = nodes.count { it instanceof RadioButton }
            if (p.widget == "radio" && !p.nullable && radios != p.choices.size()) bad << (name + ": " + radios + " radio buttons, " + p.choices.size() + " choices")
            break
        case "integer": expectKind(Spinner, c, "the control"); break
        case "float":
            if (p.pixel_size_of) {
                expectKind(TextField, c, "the pixel-size field")
                if (!nodes.any { it instanceof Button && it.text == "Use the image's calibration" }) bad << (name + ": no 'Use the image's calibration' button")
            } else expectKind(Spinner, c, "the control")
            break
        case ["image", "labels"]:
            expectKind(ComboBox, c, "the source chooser")
            if (c instanceof ComboBox && !c.items.contains(LcDialog.FILE_CHOICE)) bad << (name + ": the chooser has no '" + LcDialog.FILE_CHOICE + "' entry")
            if (dlg.areaBoxes[name] == null) bad << (name + ": no Image area chooser")
            if (dlg.fileFields[name] == null) bad << (name + ": no file field")
            if (!nodes.any { it instanceof Button && it.text == "..." }) bad << (name + ": no browse button")
            if ((dlg.channelBoxes[name] != null) != (p.pick_channel == true)) bad << (name + ": channel chooser " + (dlg.channelBoxes[name] != null) + ", pick_channel " + p.pick_channel)
            if ((dlg.selectionBoxes[name] != null) != (p.region_of != null)) bad << (name + ": selection box " + (dlg.selectionBoxes[name] != null) + ", region_of " + p.region_of)
            break
        case ["file", "table", "folder"]:
            expectKind(TextField, c, "the path field")
            if (!nodes.any { it instanceof Button && it.text == "..." }) bad << (name + ": no browse button")
            break
        default:
            expectKind(TextField, c, "the text field")
            if ((dlg.choiceBoxes[name] != null) != (p.choices_from != null)) bad << (name + ": dropdown " + (dlg.choiceBoxes[name] != null) + ", choices_from " + p.choices_from)
    }
    if (p.type in ["integer", "float"] && !p.pixel_size_of && nodes.any { it instanceof Slider } != slider) bad << (name + ": slider " + nodes.any { it instanceof Slider } + ", expected " + slider)
    if ((dlg.checks[name] != null) != (p.nullable == true && !p.region_of && !p.pixel_size_of)) bad << (name + ": 'set' box " + (dlg.checks[name] != null) + ", nullable " + p.nullable)
    String label = p.label + (p.unit ? " (" + p.unit + ")" : "")
    if (!UiAudit.fx { uaAllNodes(dlg.formBox).any { it instanceof Label && it.text == label } }) bad << (name + ": no label '" + label + "'")
    return bad
}

/** ui_qupath_form_<tool>_opens: choose the tool, then one widget of the declared kind per parameter, and no widget for an undeclared one. */
void uaFormOpens(Object dlg, String app, String toolChoice, Map tool) {
    String id = UiAudit.sanitize(tool.id as String)
    uaCheck("ui_qupath_form_" + id + "_opens", tool.id as String, "form", UiAudit.toolTimeout, {
        uaPick(dlg, app, toolChoice)
        UiAudit.fx { dlg.onTool(); null }
        Thread.sleep(500)
        uaAssert(UiAudit.fx { dlg.currentTool?.id } == tool.id, "choosing '" + toolChoice + "' built the form of " + UiAudit.fx { dlg.currentTool?.id })
        String unsupported = LcDialog.unsupportedParameter(tool)
        uaAssert(unsupported == null, "the form cannot be built: " + unsupported + " (status: " + UiAudit.fx { dlg.status.text } + ")")
        List<Map> inputs = (List<Map>) tool.inputs
        List<String> bad = []
        inputs.each { Map p -> try { bad.addAll(uaRowProblems(dlg, p)) } catch (Throwable t) { bad << (p.name + ": " + UiAudit.describe(t)) } }
        Set<String> declared = inputs.collect { it.name as String } as Set<String>
        Set<String> built = (Set<String>) UiAudit.fx { new LinkedHashSet<String>(dlg.controls.keySet()) }
        if (built != declared) bad << ("the form has " + built + ", the schema declares " + declared)
        bad.addAll(uaGeometryProblems(dlg))
        uaScanControls(dlg, "form of " + tool.id, [tool: tool])
        uaAssert(bad.isEmpty(), bad.join("; "))
        inputs.size() + " parameter(s), one widget of the declared kind each: " + inputs.collect { it.name + ":" + it.type }.join(", ")
    })
}

/** Presses a button of the row (by its text) and returns it. */
Button uaRowButton(Object dlg, String name, String text) {
    return (Button) uaRowNodes(dlg, name).find { it instanceof Button && it.text == text }
}

/** Activates the controls of one parameter and reads the state back; every problem is added to `bad`, what was done to `done`, what could not be done to `notes`. */
void uaActivateParameter(Object dlg, Map p, List<String> bad, List<String> done, List<String> notes) {
    String name = p.name
    CheckBox set = (CheckBox) dlg.checks[name]
    if (set != null) {      // optional: the 'set' box enables the control; unset sends nothing
        uaAssert(UiAudit.fx { ((Node) dlg.controls[name]).disable } == true, "optional '" + name + "' is enabled while unset")
        UiAudit.fx { set.fire(); null }
        uaAssert(UiAudit.fx { !((Node) dlg.controls[name]).disable }, "ticking 'set' did not enable '" + name + "'")
        done << (name + ".set")
    }
    Object c = dlg.controls[name]
    switch (p.type) {
        case "boolean":
            boolean before = UiAudit.fx { c.selected }
            UiAudit.fx { c.fire(); null }
            uaAssert(uaGet(dlg, name) == !before, "'" + name + "' did not toggle")
            UiAudit.fx { c.fire(); null }
            uaAssert(uaGet(dlg, name) == before, "'" + name + "' did not toggle back")
            break
        case "choice":
            List<Node> radios = uaRowNodes(dlg, name).findAll { it instanceof RadioButton }
            p.choices.eachWithIndex { Object choice, int i ->
                if (radios) UiAudit.fx { radios.find { it.text == choice.toString() }.fire(); null }
                else UiAudit.fx { c.value = choice.toString(); null }
                uaAssert(UiAudit.fx { c.value } == choice.toString() && uaGet(dlg, name) == choice, "choosing '" + choice + "' for '" + name + "' reads back " + uaGet(dlg, name))
                if (radios) uaAssert(UiAudit.fx { radios.find { it.text == choice.toString() }.selected }, "radio '" + choice + "' is not selected")
            }
            break
        case ["integer", "float"]:
            if (p.pixel_size_of) { uaActivatePixelSize(dlg, p, done); break }
            uaActivateNumber(dlg, p)
            break
        case ["image", "labels"]: uaActivateImage(dlg, p, notes); break
        case ["file", "table", "folder"]:
            String path = new File(UiAudit.testsDir, "fixtures").absolutePath
            UiAudit.fx { dlg.setters[name](path); null }
            uaAssert(uaGet(dlg, name) == path, "the path typed for '" + name + "' reads back " + uaGet(dlg, name))
            Button browse = uaRowButton(dlg, name, "...")
            uaAssert(browse != null && !UiAudit.fx { browse.disable } && UiAudit.fx { browse.onAction } != null, "the browse button of '" + name + "' is missing, disabled or has no action")
            notes << name
            break
        default:
            if (dlg.choiceBoxes[name] != null && UiAudit.fx { dlg.choiceBoxes[name].visible }) {
                ComboBox box = (ComboBox) dlg.choiceBoxes[name]
                String pick = (String) UiAudit.fx { box.items.find { it } }
                if (pick != null) { UiAudit.fx { box.value = pick; null }; uaAssert(uaGet(dlg, name) == pick, "choosing '" + pick + "' for '" + name + "' reads back " + uaGet(dlg, name)) }
            }
            UiAudit.fx { c.text = "audit text"; null }
            uaAssert(UiAudit.fx { dlg.choiceBoxes[name]?.visible } == true || uaGet(dlg, name) == "audit text", "the text typed for '" + name + "' reads back " + uaGet(dlg, name))
    }
    done << name
    if (set != null) {
        UiAudit.fx { set.fire(); null }
        uaAssert(UiAudit.fx { ((Node) dlg.controls[name]).disable } && uaGet(dlg, name) == null, "unticking 'set' for '" + name + "' did not leave it unset (" + uaGet(dlg, name) + ")")
    }
}

/** A number: typed text, a set value, and the slider beside it (moving it moves the number). */
void uaActivateNumber(Object dlg, Map p) {
    String name = p.name
    Spinner spinner = (Spinner) dlg.controls[name]
    boolean whole = p.type == "integer"
    double lo = p.minimum != null ? (p.minimum as double) : 0d, hi = p.maximum != null ? (p.maximum as double) : (p.default != null ? Math.max(2d * (p.default as double), 2d) : 10d)
    double target = whole ? Math.floor(lo + (hi - lo) / 2d) : lo + (hi - lo) / 2d
    UiAudit.fx { LcDialog.setNumber(spinner, whole ? (Object) (long) target : (Object) target); null }
    uaAssert(Math.abs((uaGet(dlg, name) as double) - target) < 1e-9, "'" + name + "' set to " + target + " reads back " + uaGet(dlg, name))
    String typed = whole ? String.valueOf((long) Math.max(lo, Math.min(hi, lo + 1d))) : String.valueOf(lo + (hi - lo) / 4d)
    UiAudit.fx { spinner.editor.text = typed; dlg.commitSpinner(spinner); null }
    uaAssert(Math.abs((uaGet(dlg, name) as double) - (typed as double)) < 1e-9, "'" + name + "' typed " + typed + " reads back " + uaGet(dlg, name))
    Slider slider = (Slider) uaRowNodes(dlg, name).find { it instanceof Slider }
    if (slider != null) {
        double position = lo + (hi - lo) * 0.75d
        UiAudit.fx { slider.value = position; null }
        double read = uaGet(dlg, name) as double
        uaAssert(Math.abs(read - (whole ? Math.rint(position) : position)) < 1e-6, "the slider at " + position + " gives '" + name + "' = " + read)
    }
}

/** A linked pixel size: typing makes the value the person's, 'Use the image's calibration' drops it, text that is not a number is kept and named. */
void uaActivatePixelSize(Object dlg, Map p, List<String> done) {
    String name = p.name
    TextField field = (TextField) dlg.controls[name]
    LcLink link = (LcLink) dlg.links[name]
    Button useImage = uaRowButton(dlg, name, "Use the image's calibration")
    String of = p.pixel_size_of
    File fixture = new File(UiAudit.testsDir, "fixtures/asym.tif").isFile() ? new File(UiAudit.testsDir, "fixtures/asym.tif") : new File(UiAudit.testsDir, "fixtures/blobs.tif")
    if (fixture.isFile() && dlg.imageBoxes[of] != null) {      // what the field shows for a file must be what the file's own TIFF tags say (read by javax.imageio, not by the host)
        UiAudit.fx { def box = dlg.imageBoxes[of]; box.value = null; box.value = LcDialog.FILE_CHOICE; dlg.fileFields[of].text = fixture.absolutePath; box.value = null; box.value = LcDialog.FILE_CHOICE; dlg.fileFields[of].text = fixture.absolutePath; null }
        Thread.sleep(1200)
        Double oracle = (Double) uaImageIoInfo(fixture.absolutePath).pixelSizeUm
        String shown = ((String) UiAudit.fx { field.text }).trim()
        if (oracle == null) uaAssert(shown.isEmpty(), "'" + fixture.name + "' has no calibration tags but the field shows '" + shown + "'")
        else uaAssert(!shown.isEmpty() && Math.abs((shown as double) - oracle) <= 1e-3 * oracle, "'" + fixture.name + "' says " + oracle + " um/px in its TIFF tags, the field shows '" + shown + "'")
        done << (name + ".calibration_oracle")
    }
    UiAudit.fx { field.text = "0.25"; null }
    uaAssert(UiAudit.fx { link.state } == LcLink.USER && uaGet(dlg, name) == 0.25d, "typing 0.25 gives state " + link.state + ", value " + uaGet(dlg, name))
    UiAudit.fx { useImage.fire(); null }
    uaAssert(UiAudit.fx { link.state } != LcLink.USER, "'Use the image's calibration' left the typed value (state " + link.state + ")")
    UiAudit.fx { field.text = "abc"; null }
    uaAssert(UiAudit.fx { link.invalid } == "abc", "text that is not a number is not kept as typed (invalid=" + link.invalid + ")")
    UiAudit.fx { useImage.fire(); null }
    uaAssert(UiAudit.fx { link.state } != LcLink.USER && UiAudit.fx { link.invalid } == null, "'Use the image's calibration' did not clear the invalid text")
    done << (name + ".use_calibration")
}

/** An image or labels parameter: every source entry, the area chooser, a file typed in, the channel chooser of a multi-channel file, the 'use the selection' box. */
void uaActivateImage(Object dlg, Map p, List<String> notes) {
    String name = p.name
    ComboBox box = (ComboBox) dlg.controls[name]
    TextField file = (TextField) dlg.fileFields[name]
    ComboBox area = (ComboBox) dlg.areaBoxes[name]
    List<String> entries = (List<String>) UiAudit.fx { new ArrayList<String>(box.items) }
    entries.findAll { it != LcDialog.FILE_CHOICE }.each { String entry ->
        UiAudit.fx { box.value = entry; null }
        Object value = uaGet(dlg, name)
        if (entry == LcDialog.NO_IMAGE) uaAssert(value == null, "'" + entry + "' reads back " + value)
        else uaAssert(value instanceof Map && value.source != null, "'" + entry + "' reads back " + value)
        boolean current = LcDialog.isCurrentImage(entry)
        uaAssert(UiAudit.fx { area.disable } == !current, "the Image area chooser is " + (UiAudit.fx { area.disable } ? "disabled" : "enabled") + " for '" + entry + "'")
        if (current) LcArea.CHOICES.each { String choice -> UiAudit.fx { area.value = choice; null }; uaAssert(UiAudit.fx { area.value } == choice, "the area '" + choice + "' was not taken") }
    }
    String fixture = new File(UiAudit.testsDir, "fixtures/blobs.tif").absolutePath
    File multi = new File(UiAudit.testsDir, "fixtures/three_channels.tif")
    UiAudit.fx { box.value = null; box.value = LcDialog.FILE_CHOICE; file.text = (p.pick_channel && multi.isFile() ? multi.absolutePath : fixture); box.value = null; box.value = LcDialog.FILE_CHOICE; null }
    Map read = (Map) uaGet(dlg, name)
    uaAssert(read != null && read.file == (p.pick_channel && multi.isFile() ? multi.absolutePath : fixture), "the file typed for '" + name + "' reads back " + read)
    uaAssert(UiAudit.fx { area.disable } == true, "the Image area chooser is enabled for a file")
    if (p.pick_channel) {
        ComboBox channel = (ComboBox) dlg.channelBoxes[name]
        if (multi.isFile()) {
            uaAssert(uaWaitFor(10) { UiAudit.fx { channel.visible && channel.items.size() == 3 } }, "the channel chooser does not show the 3 channels of " + multi.name + " (it shows " + UiAudit.fx { channel.items } + ")")
            UiAudit.fx { channel.value = channel.items[1]; null }
            uaAssert(((Map) uaGet(dlg, name)).channel == 1, "choosing the second channel sends " + ((Map) uaGet(dlg, name)).channel)
        } else notes << (name + ".channel(no multi-channel fixture " + multi.name + ")")
    }
    CheckBox selection = (CheckBox) dlg.selectionBoxes[name]
    if (selection != null) {
        UiAudit.fx { selection.fire(); null }
        uaAssert(UiAudit.fx { box.disable && file.disable } && ((Map) uaGet(dlg, name)).selection == true, "'use the selection' did not take over '" + name + "'")
        UiAudit.fx { selection.fire(); null }
        uaAssert(UiAudit.fx { !box.disable && !file.disable }, "unticking 'use the selection' did not give '" + name + "' back")
    }
    notes << name
}

/** EnabledWhen: the row of the dependent parameter is disabled exactly when the rule is not met, for each value the driver can take. */
void uaCheckEnabledWhen(Object dlg, Map tool) {
    ((List<Map>) tool.inputs).findAll { it.enabled_when }.each { Map p ->
        Map rule = (Map) p.enabled_when
        Map driver = ((List<Map>) tool.inputs).find { it.name == rule.param }
        List values = driver.type == "boolean" ? [true, false] : driver.type == "choice" ? (List) driver.choices : []
        values.each { Object v ->
            UiAudit.fx { dlg.setters[driver.name](v instanceof Boolean ? v : v.toString()); null }
            boolean want = LcDialog.ruleSatisfied(rule, uaGet(dlg, driver.name as String))
            uaAssert(UiAudit.fx { ((Node) dlg.wrappers[p.name]).disable } == !want, "'" + p.name + "' is " + (UiAudit.fx { ((Node) dlg.wrappers[p.name]).disable } ? "disabled" : "enabled") + " with '" + driver.name + "' = " + v)
        }
    }
}

/** ui_qupath_form_<tool>_controls: every control of the form activated and read back; the folded sections expanded and folded again. */
void uaFormControls(Object dlg, String app, String toolChoice, Map tool, UaMatrix matrix) {
    String id = UiAudit.sanitize(tool.id as String)
    List<String> notes = []
    boolean ok = uaCheck("ui_qupath_form_" + id + "_controls", tool.id as String, "form", UiAudit.toolTimeout, {
        uaPick(dlg, app, toolChoice)
        UiAudit.fx { dlg.onTool(); null }      // a fresh form
        Thread.sleep(300)
        List<String> bad = [], done = []
        List<TitledPane> panes = (List<TitledPane>) UiAudit.fx { uaAllNodes(dlg.formBox).findAll { it instanceof TitledPane } }
        panes.each { TitledPane pane ->
            try {
                UiAudit.fx { pane.expanded = true; null }
                uaAssert(UiAudit.fx { pane.expanded && pane.content != null && pane.content.visible } == true, "section '" + pane.text + "' does not open")
                done << ("section:" + pane.text)
            } catch (Throwable t) { bad << UiAudit.describe(t) }
        }
        ((List<Map>) tool.inputs).each { Map p ->
            try { uaActivateParameter(dlg, p, bad, done, notes) } catch (Throwable t) { bad << (p.name + ": " + UiAudit.describe(t)) }
        }
        try { uaCheckEnabledWhen(dlg, tool) } catch (Throwable t) { bad << UiAudit.describe(t) }
        panes.each { TitledPane pane -> UiAudit.fx { pane.expanded = false; null } }
        uaScanControls(dlg, "controls of " + tool.id, [tool: tool])
        uaAssert(bad.isEmpty(), bad.join("; "))
        done.size() + " control(s) activated and read back: " + done.join(", ")
    })
    List<String> browse = notes.findAll { !it.contains(".channel(") && ((List<Map>) tool.inputs).any { p -> p.name == it && p.type in ["file", "table", "folder", "image", "labels"] } }
    if (browse) matrix.add("ui_qupath_form_" + id + "_browse", tool.id as String, "param_browse", "HUMAN", "the native file chooser of " + browse + " cannot be driven under Xvfb: the browse buttons were checked for presence, enabled state and an action, and the path was typed into the field")
    notes.findAll { it.contains(".channel(") }.each { matrix.add("ui_qupath_form_" + id + "_channel", tool.id as String, "param_image_channel", "NOT_RUN", it) }
}
