// ---------------------------------------------------------------------------------------------------- run identity (SPEC_WRONG_SCIENCE section 4)
/** The image one input of a run was taken from, as an OBJECT and not as a label: the `ImageData` open in QuPath when Run was pressed (null for a project image or a file, which have no
 *  open image to draw on), and the shape the image had then. The text of the chooser ("Current image: slide A") is only the name shown in sentences, never identity (4.2). */
class LcSource {
    final String param                 // the parameter name of the image input
    final String choice                // what the chooser said when Run was pressed
    final String name                  // the image name without the chooser's prefix (the name used in sentences)
    final qupath.lib.images.ImageData imageData
    final List<Integer> shape          // [width, height, z-planes, time points, channels] of the image when Run was pressed

    LcSource(String param, String choice, String name, qupath.lib.images.ImageData imageData, List<Integer> shape) {
        this.param = param
        this.choice = choice
        this.name = name
        this.imageData = imageData
        this.shape = shape
    }
}

/** One run, frozen when Run is pressed on the FX thread, before any worker call (4.1): an id, the app and tool (the schema maps), the image each input came from (LcSource), the selected
 *  regions (their ROIs, which are immutable, copied by value) and the plane the viewer showed. Export, the request, the run record and the placement of the results read this and nothing
 *  live: opening another image, selecting other annotations or switching the tool while the run goes cannot change what is exported, drawn or recorded (RUN-01..07). */
class LcRun {
    static final String TARGET_UNAVAILABLE = "run {run_id} on '{source}': '{source}' is no longer open; results are in {path}"            // conformance messages.target_unavailable
    static final String TARGET_CHANGED_SIZE = "run {run_id} on '{source}': '{source}' changed size while the tool ran; results are in {path}"   // messages.target_changed_size
    static final String CODE_TARGET_UNAVAILABLE = "target_unavailable"       // conformance host_internal_codes: also the code of "changed size" (RUN-07)
    static final String CURRENT_PREFIX = "Current image: "
    static final String META_RUN_ID = "labconstrictor.run_id"                // metadata of every annotation drawn by a run
    static final String META_APP = "labconstrictor.app"
    static final String META_TOOL = "labconstrictor.tool"
    /** Test hook (tests only): called with a stage name ("export") on the run thread, so a test can change the viewer or the selection at that point of a real run. */
    static volatile Closure<Object> barrier = null

    final String runId = String.valueOf(UUID.randomUUID())
    final String startedUtc = LcRetention.nowUtc()
    final String appName
    final Map app
    final Map tool
    final Map<String, LcSource> sources = new LinkedHashMap<String, LcSource>()
    final List<Object> selectedRois = new ArrayList<Object>()      // the ROIs of the annotations selected on the open image when Run was pressed (points do not count)
    final Map<String, Integer> plane
    volatile String resultsPath = ""                                // the results folder of the run (set when it is made); the sentences of 4.4 name it
    volatile boolean active = false                                 // true from Run until the results are shown: prepareInputs reads this snapshot then

    LcRun(String appName, Map app, Map tool, Map<String, Integer> plane) {
        this.appName = appName
        this.app = app
        this.tool = tool
        this.plane = plane
    }

    String getToolId() { return tool.id as String }

    String getToolLabel() { return tool.label as String }

    /** [width, height, z-planes, time points, channels] of an image server. */
    static List<Integer> shapeOf(qupath.lib.images.servers.ImageServer server) {
        return [server.width as int, server.height as int, server.nZSlices() as int, server.nTimepoints() as int, server.nChannels() as int]
    }

    /** The name shown for an image in sentences: the chooser text without "Current image: ". */
    static String nameOf(String choice) { return choice.startsWith(CURRENT_PREFIX) ? choice.substring(CURRENT_PREFIX.length()) : choice }

    /** Freezes the run `dialog` is about to start. Call on the FX thread. `values` are the form values (the image values that use the open image get a source that is bound to the image open NOW);
     *  what each image chooser shows is `dialog.imageChoiceAtRun`; a "Current image" label that the form cannot offer any more means the open image changed since the chooser was filled: the
     *  run is refused, never started on another image than the one named. */
    static LcRun take(LcDialog dialog, Map values) {
        QuPathGUI qupath = dialog.qupath
        Map tool = dialog.currentTool
        Map<String, String> choices = dialog.imageChoiceAtRun
        Collection<String> openLabels = dialog.imageSources().keySet()
        LcRun run = new LcRun(dialog.appBox.value as String, dialog.apps[dialog.appBox.value] as Map, tool, dialog.planeAtRun)
        for (Map p : (tool.inputs as List<Map>).findAll { it.type in ["image", "labels"] }) {
            String name = p.name as String
            Object value = values[name]
            String choice = choices[name]
            if (!(value instanceof Map) || value.selection || choice == null || !choice.startsWith(CURRENT_PREFIX)) continue      // a project image, a file, a region or nothing: no open image to bind
            qupath.lib.images.ImageData data = qupath?.imageData
            if (data == null || !openLabels.contains(choice))
                throw new LcRefusal("'" + p.label + "': '" + nameOf(choice) + "' is no longer the image open in QuPath" + (data == null ? " (no image is open)" : " ('" + openName(qupath, data) + "' is)") + ": choose the image again")
            qupath.lib.images.servers.ImageServer server = data.server
            run.sources[name] = new LcSource(name, choice, nameOf(choice), data, shapeOf(server))
            (value as Map).source = { server }          // the export reads this image, whatever is open later
        }
        qupath.lib.images.ImageData open = run.sources.values().isEmpty() ? null : run.sources.values().first().imageData
        if (open != null) {
            run.selectedRois.addAll(open.hierarchy.selectionModel.selectedObjects.findAll { it.isAnnotation() && it.ROI != null && !it.ROI.isPoint() }*.ROI)
        }
        run.active = true
        return run
    }

    /** The name the chooser would give the image `data` ("Current image: <name>" without the prefix): the project entry's name, else the server's name, else the file name. */
    static String openName(QuPathGUI qupath, qupath.lib.images.ImageData data) {
        qupath.lib.projects.ProjectImageEntry entry = qupath.getProjectImageEntry(data)
        return entry?.imageName ?: data.server.metadata.name ?: new File(data.server.path.replaceFirst(/^[^:]*:\s*/, "")).name
    }

    /** The image the open-image input `param` was bound to, or null (a project image, a file, or no such input). */
    qupath.lib.images.ImageData dataFor(String param) { return sources[param]?.imageData }

    /** Why the results for `param` must not be drawn on its image now (4.4), as the conformance sentence, or null when the very same image is still open with the shape it was exported
     *  with. Gone (no viewer shows that ImageData) is `target_unavailable` "is no longer open"; still shown but another shape is "changed size while the tool ran": the two sentences are never swapped. */
    String targetProblem(QuPathGUI qupath, String param) {
        LcSource source = sources[param]
        if (source == null) return null
        boolean open = qupath != null && qupath.allViewers.any { it.imageData != null && it.imageData.is(source.imageData) }
        if (!open) return sentence(TARGET_UNAVAILABLE, source)
        return shapeOf(source.imageData.server) == source.shape ? null : sentence(TARGET_CHANGED_SIZE, source)
    }

    private String sentence(String template, LcSource source) {
        return template.replace("{run_id}", runId).replace("{source}", source.name).replace("{path}", resultsPath)
    }

    /** Puts the run's identity on an annotation it draws (4.5): the run id, and the app and tool of THIS run, not of whatever the form shows later. */
    void tag(qupath.lib.objects.PathObject annotation) {
        annotation.metadata.put(META_RUN_ID, runId)
        annotation.metadata.put(META_APP, appName)
        annotation.metadata.put(META_TOOL, toolId)
    }

    /** The tool the results of the dialog's last run belong to: the one the run was started for (frozen with it), not whatever the form shows now (4.5). */
    static Map toolOf(LcDialog dialog) { return dialog.runSnapshot != null ? dialog.runSnapshot.tool : dialog.currentTool }

    /** True while the form shows the tool the run was started for (the form can be switched to another tool or app while a run goes on). */
    static boolean formShowsToolOf(LcDialog dialog) {
        LcRun run = dialog.runSnapshot
        return run != null && dialog.currentTool != null && run.toolId == dialog.currentTool.id && run.appName == dialog.appBox.value
    }

    /** The image the open-image input `param` was frozen to when Run was pressed; with no run in progress (a direct call) the image open now. */
    static qupath.lib.images.ImageData dataFor(LcDialog dialog, String param) {
        LcRun run = dialog.runSnapshot
        return (run != null && run.active) ? run.dataFor(param) : dialog.qupath?.imageData
    }

    /** The ROIs of the selected annotations (points do not count) the run uses: those frozen when Run was pressed; with no run in progress the live selection of `data`. */
    static List roisOf(LcDialog dialog, qupath.lib.images.ImageData data) {
        LcRun run = dialog.runSnapshot
        if (run != null && run.active) return run.selectedRois
        return data.hierarchy.selectionModel.selectedObjects.findAll { it.isAnnotation() && it.ROI != null && !it.ROI.isPoint() }*.ROI
    }

    /** The image open in QuPath changed: the "Current image: ..." choice of every image chooser names the image that is open now, so that a label never names another image than the one that
     *  would be exported (4.2). A choice the person made on another entry (a project image, a file) and every other field stay as they are; with no image open a "Current image" choice
     *  keeps its old label (a run is then refused, never moved to another entry). */
    static void refreshChoosers(LcDialog dialog) {
        if (dialog.currentTool == null || dialog.imageBoxes.isEmpty()) return
        Map<String, Closure> fresh = dialog.imageSources()
        dialog.formSources.clear()
        dialog.formSources.putAll(fresh)
        String current = fresh.keySet().find { LcDialog.isCurrentImage(it) }
        for (Map p : (dialog.currentTool.inputs as List<Map>).findAll { dialog.imageBoxes[it.name] != null }) {
            ComboBox<String> box = dialog.imageBoxes[p.name as String]
            String old = box.value
            List<String> items = (p.required ? [] : [LcDialog.NO_IMAGE]) + fresh.keySet().toList() + [LcDialog.FILE_CHOICE]
            String start = p.required ? (fresh ? fresh.keySet().first() : LcDialog.FILE_CHOICE) : LcDialog.NO_IMAGE
            box.items.setAll(items)
            box.value = LcDialog.isCurrentImage(old) ? (current ?: old) : (items.contains(old) ? old : start)
        }
    }

    /** Called at a named point of the run thread; a no-op unless a test set the barrier. */
    static void reach(String stage) {
        Closure<Object> hook = barrier
        if (hook != null) hook.call(stage)
    }
}
