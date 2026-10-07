/*
 * LabConstrictor tools for QuPath (prototype).
 *
 * Runs the tools of every installed LabConstrictor app (NucleiSky, CellTracksColab, ...) from QuPath. The form is generated from the
 * tool's declared schema (see https://github.com/CellMigrationLab/LabConstrictor-Tools, docs/PROTOCOL.md); the tool runs in the app's
 * own Python environment through the LabConstrictor worker (JSON lines on stdin/stdout), so QuPath never imports the app's packages.
 *
 * Use it as the extension jar (Extensions > LabConstrictor tools...) or paste it in the Script Editor and run it.
 */
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import javafx.application.Platform
import javafx.beans.binding.Bindings
import javafx.geometry.Insets
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.stage.DirectoryChooser
import javafx.stage.FileChooser
import javafx.stage.Stage
import qupath.lib.gui.QuPathGUI
import qupath.lib.images.writers.ImageWriterTools
import qupath.lib.regions.RegionRequest

import java.nio.file.Files
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------------------------------------------- JSON (QuPath ships Gson, not groovy-json)
class LcJson {
    static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create()
    static final Gson PRETTY = new GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting().create()
    static Object parseText(String text) { GSON.fromJson(text, Object) }          // Map / List / Double / String / Boolean
    static Object parse(File file) { parseText(file.getText("UTF-8")) }
    static String toJson(Object o) { GSON.toJson(o) }
    static String pretty(Object o) { PRETTY.toJson(o) }
}

// ---------------------------------------------------------------------------------------------------- registry
class LcRegistry {
    static final List<String> SCRUBBED_ENV = ["PYTHONHOME", "VIRTUAL_ENV", "CONDA_PREFIX", "QT_PLUGIN_PATH", "PYTHONPATH"]

    static List<File> searchPath() {
        def env = System.getenv()
        def home = env.LC_HOME ? new File(env.LC_HOME) : new File(System.getProperty("user.home"), ".labconstrictor")
        def dirs = [new File(home, "apps")]
        if (env.LC_APPS_PATH) env.LC_APPS_PATH.split(File.pathSeparator).findAll { it }.each { dirs << new File(it) }
        def os = System.getProperty("os.name").toLowerCase()
        if (os.contains("win")) dirs << new File(env.ProgramData ?: "C:\\ProgramData", "LabConstrictor\\apps")
        else if (os.contains("mac")) dirs << new File("/Library/Application Support/LabConstrictor/apps")
        else dirs << new File("/etc/labconstrictor/apps")
        return dirs
    }

    /** @return [apps: LinkedHashMap name -> [entry: Map, schema: Map], problems: List of "name: reason"] */
    static Map load() {
        def apps = new LinkedHashMap(), problems = []
        for (dir in searchPath()) {
            if (!dir.isDirectory()) continue
            def files = (dir.listFiles({ File f -> f.name.endsWith(".json") && !f.name.endsWith(".schema.json") } as FileFilter) ?: []).sort { it.name }
            for (file in files) {
                def name = file.name.replaceAll(/\.json$/, "")
                if (apps.containsKey(name)) continue // the first folder in the search order wins
                try {
                    def entry = LcJson.parse(file)
                    if (!new File(entry.python as String).isFile()) throw new IllegalStateException("interpreter not found: " + entry.python)
                    // lexical check, like the Python side: a venv's interpreter is a link to a Python outside the prefix
                    def prefix = new File(entry.prefix as String).toPath().toAbsolutePath().normalize()
                    if (!new File(entry.python as String).toPath().toAbsolutePath().normalize().startsWith(prefix))
                        throw new IllegalStateException("interpreter is not inside the app's prefix")
                    if (file.toPath().getFileSystem().supportedFileAttributeViews().contains("posix")) {
                        def perms = Files.getPosixFilePermissions(file.toPath())
                        if (perms.any { it.name() in ["GROUP_WRITE", "OTHERS_WRITE"] })
                            throw new IllegalStateException("registry file is writable by others")
                    }
                    def schema = LcJson.parse(new File(entry.schema_path as String))
                    if ((schema.protocol as double) != 1d) throw new IllegalStateException("unsupported protocol " + schema.protocol)
                    apps[name] = [entry: entry, schema: schema]
                } catch (Exception e) {
                    problems << (name + ": " + e.message)
                }
            }
        }
        return [apps: apps, problems: problems]
    }
}

// ---------------------------------------------------------------------------------------------------- worker
class LcWorker {
    Process proc
    final Map<String, BlockingQueue<Map>> tasks = new java.util.concurrent.ConcurrentHashMap<>()
    final StringBuffer stderrTail = new StringBuffer()
    volatile boolean closed = false
    private int counter = 0

    LcWorker(Map entry) {
        def command = [entry.python as String, "-m", "labconstrictor_tools", "serve", "--module", entry.module as String]
        def pb = new ProcessBuilder(command)
        LcRegistry.SCRUBBED_ENV.each { pb.environment().remove(it) }
        def path = ((entry.pythonpath ?: []) + [entry.runtime_path ?: ""]).findAll { it }.join(File.pathSeparator)
        pb.environment().putAll([PYTHONPATH: path, PYTHONNOUSERSITE: "1", PYTHONSAFEPATH: "1", PYTHONIOENCODING: "utf-8", PYTHONUNBUFFERED: "1"])
        proc = pb.start()
        Thread.start("lc-stdout") {
            try {
                proc.inputStream.withReader("UTF-8") { reader ->
                    String line
                    while ((line = reader.readLine()) != null) {
                        try {
                            def msg = LcJson.parseText(line)
                            def queue = tasks.get(msg.task)
                            if (queue != null) queue.put(msg)
                        } catch (Exception ignored) { /* a line that is not a protocol message is ignored */ }
                    }
                }
            } catch (Exception ignored) { }
            tasks.values().each { it.put([responseType: "CRASH", error: "the worker stopped (" + stderrText().takeRight(400).trim() + ")"]) }
        }
        Thread.start("lc-stderr") {
            try {
                proc.errorStream.withReader("UTF-8") { r -> char[] buf = new char[2048]; int n; while ((n = r.read(buf)) > 0) { stderrTail.append(buf, 0, n); if (stderrTail.length() > 20000) stderrTail.delete(0, stderrTail.length() - 10000) } }
            } catch (Exception ignored) { }
        }
    }

    String stderrText() { stderrTail.toString() }

    /** Sends one EXECUTE and blocks until its terminal message; `onUpdate` gets (message, current, maximum). */
    Map run(String toolId, Map inputs, Closure onUpdate) {
        def id = "lcq-" + (++counter) + "-" + System.nanoTime()
        def queue = new LinkedBlockingQueue<Map>()
        tasks.put(id, queue)
        try {
            send([task: id, requestType: "EXECUTE", script: "lc:" + toolId, inputs: inputs])
            while (true) {
                def msg = queue.poll(1, TimeUnit.SECONDS)
                if (msg == null) {
                    if (!proc.isAlive()) return [responseType: "CRASH", error: "the worker stopped (" + stderrText().takeRight(400).trim() + ")"]
                    continue
                }
                def type = msg.responseType
                if (type == "UPDATE") onUpdate?.call(msg.message, msg.current, msg.maximum)
                else if (type in ["COMPLETION", "FAILURE", "CANCELATION", "CRASH"]) return msg
            }
        } finally {
            tasks.remove(id)
            currentTask = null
        }
    }
    volatile String currentTask = null

    private synchronized void send(Map message) {
        if (message.requestType == "EXECUTE") currentTask = message.task
        def out = proc.outputStream
        out.write((LcJson.toJson(message) + "\n").getBytes("UTF-8"))
        out.flush()
    }

    /** Asks the running tool to stop; a tool that ignores the request is killed after `graceSeconds`. */
    void cancel(int graceSeconds = 3) {
        def id = currentTask
        if (id == null) return
        try { send([task: id, requestType: "CANCEL"]) } catch (Exception ignored) { }
        Thread.start("lc-cancel") {
            Thread.sleep(graceSeconds * 1000L)
            if (currentTask == id) kill()
        }
    }

    void kill() {
        closed = true
        try { proc.descendants().each { it.destroyForcibly() } } catch (Exception ignored) { }
        proc.destroyForcibly()
    }

    void close() {
        closed = true
        try { proc.outputStream.close() } catch (Exception ignored) { }
        if (!proc.waitFor(10, TimeUnit.SECONDS)) kill()
    }
}

// ---------------------------------------------------------------------------------------------------- the dialog
class LcDialog {
    /** Gson reads every JSON number as a Double: show whole numbers without ".0". */
    static String show(Object v) {
        if (v instanceof Double && Double.isFinite(v) && v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf(v.longValue())
        if (v instanceof List) return "[" + v.collect { show(it) }.join(", ") + "]"
        return v == null ? "(not a finite number)" : v.toString()
    }

    static final String FILE_CHOICE = "File..."
    static final String NO_IMAGE = "(none)"

    QuPathGUI qupath
    Map apps
    List problems
    Stage stage
    ComboBox<String> appBox = new ComboBox<>(), toolBox = new ComboBox<>()
    VBox formBox = new VBox(6)
    Label description = new Label(), status = new Label("idle")
    ProgressBar progress = new ProgressBar(0)
    Button runButton = new Button("Run"), cancelButton = new Button("Cancel"), detailsButton = new Button("Details...")
    CheckBox keepWorker = new CheckBox("Keep the worker running between runs (faster repeat runs)")
    Map<String, Closure> getters = [:]           // parameter name -> () -> value or null (omitted)
    Map<String, Closure> setters = [:]
    Map<String, Control> controls = [:]
    Map<String, Node> wrappers = [:]             // the node placed in the form for each parameter (enabled_when disables this one)
    Map<String, ComboBox<String>> imageBoxes = [:]
    Map<String, String> imageOf = [:]            // pixel-size parameter -> image parameter
    LcWorker worker
    String workerApp
    volatile boolean running = false
    String lastReport = ""
    Map currentTool

    LcDialog(QuPathGUI qupath, Map registry) {
        this.qupath = qupath
        this.apps = registry.apps
        this.problems = registry.problems
    }

    // ---- window
    void show() {
        stage = new Stage()
        stage.title = "LabConstrictor tools"
        if (qupath?.stage) stage.initOwner(qupath.stage)
        keepWorker.selected = true
        description.wrapText = true
        description.maxWidth = 560
        status.wrapText = true
        status.maxWidth = 560
        progress.maxWidth = Double.MAX_VALUE
        appBox.items.addAll(apps.keySet())
        appBox.maxWidth = Double.MAX_VALUE
        toolBox.maxWidth = Double.MAX_VALUE
        appBox.valueProperty().addListener({ o, a, b -> onApp() } as javafx.beans.value.ChangeListener)
        toolBox.valueProperty().addListener({ o, a, b -> onTool() } as javafx.beans.value.ChangeListener)
        runButton.defaultButton = true
        runButton.maxWidth = Double.MAX_VALUE
        runButton.onAction = { run() }
        cancelButton.disable = true
        cancelButton.onAction = { cancel() }
        detailsButton.disable = true
        detailsButton.onAction = { showDetails() }
        def rescan = new Button("Rescan apps")
        rescan.onAction = { rescan() }
        def restart = new Button("Restart worker")
        restart.onAction = { stopWorker(true); status.text = "worker stopped" }
        def buttons = new HBox(6, cancelButton, rescan, restart, detailsButton)
        def scroll = new ScrollPane(formBox)
        scroll.fitToWidth = true
        scroll.prefViewportHeight = 420
        scroll.prefViewportWidth = 600
        VBox.setVgrow(scroll, Priority.ALWAYS)
        def root = new VBox(8, appBox, toolBox, description, scroll, runButton, progress, status, keepWorker, buttons)
        root.padding = new Insets(10)
        if (apps.isEmpty()) {
            status.text = "No LabConstrictor app is registered on this machine." + (problems ? " Skipped: " + problems.join("; ") : "")
        }
        stage.scene = new Scene(root)
        stage.onHidden = { stopWorker(false) }   // also when closed from code
        stage.show()
        if (!apps.isEmpty()) appBox.value = apps.keySet().first()
    }

    void rescan() {
        def registry = LcRegistry.load()
        apps = registry.apps
        problems = registry.problems
        def current = appBox.value
        appBox.items.setAll(apps.keySet())
        if (current in apps) appBox.value = current else if (!apps.isEmpty()) appBox.value = apps.keySet().first()
        status.text = "Found " + apps.size() + " app(s)" + (problems ? "; skipped: " + problems.join("; ") : "")
    }

    void onApp() {
        def app = apps[appBox.value]
        toolBox.items.setAll(app ? app.schema.tools.collect { it.label } : [])
        if (!toolBox.items.isEmpty()) toolBox.value = toolBox.items.first()
    }

    void onTool() {
        def app = apps[appBox.value]
        currentTool = app?.schema?.tools?.find { it.label == toolBox.value }
        formBox.children.clear()
        getters.clear(); setters.clear(); controls.clear(); wrappers.clear(); imageBoxes.clear(); imageOf.clear()
        description.text = currentTool?.description ?: ""
        if (currentTool == null) return
        buildForm(currentTool.inputs)
    }

    // ---- images available in QuPath
    Map<String, Closure> imageSources() {   // label -> closure returning the ImageServer
        def sources = new LinkedHashMap<String, Closure>()
        def data = qupath?.imageData
        if (data != null) {
            def entry = qupath.getProjectImageEntry(data)
            def name = entry?.imageName ?: data.server.metadata.name ?: new File(data.server.path.replaceFirst(/^[^:]*:\s*/, "")).name
            sources["Current image: " + name] = { qupath.imageData.server }
        }
        def project = qupath?.project
        project?.imageList?.each { entry ->
            def label = "Project: " + entry.imageName
            if (!sources.containsKey(label)) sources[label] = { entry.serverBuilder.build() }
        }
        return sources
    }

    // ---- form
    void buildForm(List inputs) {
        def sources = imageSources()
        def normal = inputs.findAll { !it.advanced }, advanced = inputs.findAll { it.advanced }
        def grid = newGrid()
        addRows(grid, normal, sources)
        formBox.children.add(grid)
        if (advanced) {
            def advGrid = newGrid()
            addRows(advGrid, advanced, sources)
            def pane = new TitledPane("Advanced settings", advGrid)
            pane.expanded = false
            formBox.children.add(pane)
        }
        inputs.each { p -> imageOf[p.name] = p.pixel_size_of }
        inputs.findAll { it.pixel_size_of }.each { p -> hookPixelSize(p) }
        inputs.findAll { it.enabled_when }.each { p -> hookEnabled(p) }
        inputs.findAll { it.pixel_size_of }.each { p -> fillPixelSize(p) }
    }

    GridPane newGrid() {
        def grid = new GridPane()
        grid.hgap = 8
        grid.vgap = 6
        grid.padding = new Insets(4)
        def c0 = new ColumnConstraints(), c1 = new ColumnConstraints()
        c1.hgrow = Priority.ALWAYS
        grid.columnConstraints.addAll(c0, c1)
        return grid
    }

    void addRows(GridPane grid, List params, Map sources) {
        int row = 0
        String lastGroup = null
        for (p in params) {
            if (p.group && p.group != lastGroup) {
                def heading = new Label(p.group)
                heading.style = "-fx-font-weight: bold"
                grid.add(heading, 0, row++, 2, 1)
                lastGroup = p.group
            }
            def label = new Label(p.label + (p.unit ? " (" + p.unit + ")" : ""))
            def node = makeControl(p, sources)
            if (p.description) {
                def tip = new Tooltip(p.description)
                tip.wrapText = true
                tip.maxWidth = 400
                Tooltip.install(label, tip)
            }
            grid.add(label, 0, row)
            grid.add(node, 1, row++)
        }
    }

    Node makeControl(Map p, Map sources) {
        def name = p.name
        Node node
        Closure get, set
        switch (p.type) {
            case "boolean":
                def box = new CheckBox()
                box.selected = p.default == true
                get = { box.selected }; set = { box.selected = it as boolean }
                node = box; controls[name] = box
                break
            case "choice":
                def box = new ComboBox<String>()
                box.items.setAll(p.choices.collect { it.toString() })
                box.maxWidth = Double.MAX_VALUE
                if (p.default != null) box.value = p.default.toString() else if (!box.items.isEmpty()) box.value = box.items.first()
                get = {
                    def i = box.items.indexOf(box.value)
                    i >= 0 ? p.choices[i] : null
                }
                set = { box.value = it.toString() }
                node = box; controls[name] = box
                break
            case "integer":
                def lo = p.minimum != null ? (p.minimum as double).longValue() : -1000000000L
                def hi = p.maximum != null ? (p.maximum as double).longValue() : 1000000000L
                def initial = p.default != null ? (p.default as double).longValue() : Math.max(lo, Math.min(hi, 0L))
                def spinner = new Spinner<Integer>(lo as int, hi as int, initial as int)
                spinner.editable = true
                commitOnFocusLost(spinner)
                get = { spinner.value }; set = { spinner.valueFactory.value = (it as double).intValue() }
                node = spinner; controls[name] = spinner
                break
            case "float":
                def lo = p.minimum != null ? p.minimum as double : -1e12d
                def hi = p.maximum != null ? p.maximum as double : 1e12d
                def initial = p.default != null ? p.default as double : Math.max(lo, Math.min(hi, 0d))
                def step = (hi - lo) <= 100 && p.maximum != null ? (hi - lo) / 100 : 1d
                def spinner = new Spinner<Double>(lo, hi, initial, step)
                spinner.editable = true
                commitOnFocusLost(spinner)
                get = { spinner.value }; set = { spinner.valueFactory.value = it as double }
                node = spinner; controls[name] = spinner
                break
            case "image":
            case "labels":
                def box = new ComboBox<String>()
                def items = []
                if (!p.required) items << NO_IMAGE
                items.addAll(sources.keySet())
                items << FILE_CHOICE
                box.items.setAll(items)
                box.maxWidth = Double.MAX_VALUE
                def fileField = new TextField()
                fileField.promptText = "or a file"
                fileField.maxWidth = Double.MAX_VALUE
                HBox.setHgrow(fileField, Priority.ALWAYS)
                def browse = new Button("...")
                browse.onAction = {
                    def f = new FileChooser().showOpenDialog(stage)
                    if (f != null) { fileField.text = f.absolutePath; box.value = FILE_CHOICE }
                }
                // sources first, but a tool that takes optional labels starts on "(none)"
                box.value = p.required ? (sources ? sources.keySet().first() : FILE_CHOICE) : NO_IMAGE
                imageBoxes[name] = box
                def sourceMap = sources
                get = {
                    def v = box.value
                    if (v == NO_IMAGE) return null
                    if (v == FILE_CHOICE) return fileField.text?.trim() ? [file: fileField.text.trim()] : null
                    return [source: sourceMap[v]]
                }
                set = { }
                node = new HBox(6, box, fileField, browse)
                HBox.setHgrow(box, Priority.SOMETIMES)
                controls[name] = box
                break
            case "file":
            case "table":
            case "folder":
                def field = new TextField()
                field.maxWidth = Double.MAX_VALUE
                HBox.setHgrow(field, Priority.ALWAYS)
                def browse = new Button("...")
                browse.onAction = {
                    def f = p.type == "folder" ? new DirectoryChooser().showDialog(stage) : new FileChooser().showOpenDialog(stage)
                    if (f != null) field.text = f.absolutePath
                }
                get = { field.text?.trim() ? field.text.trim() : null }; set = { field.text = it as String }
                node = new HBox(6, field, browse); controls[name] = field
                break
            default: // string
                def field = new TextField(p.default != null ? p.default.toString() : "")
                field.maxWidth = Double.MAX_VALUE
                get = { field.text }; set = { field.text = it as String }
                node = field; controls[name] = field
        }
        if (p.nullable) {                      // optional without a default: unticked = the tool receives None
            def check = new CheckBox("set")
            def control = node
            control.disable = true
            check.selectedProperty().addListener({ o, a, on -> control.disable = !on } as javafx.beans.value.ChangeListener)
            def inner = get
            getters[name] = { check.selected ? inner() : null }
            setters[name] = set
            node = new VBox(2, node, check)
        } else {
            getters[name] = get
            setters[name] = set
        }
        wrappers[name] = node
        return node
    }

    static void commitOnFocusLost(Spinner spinner) {
        spinner.focusedProperty().addListener({ o, a, focused ->
            if (!focused) {
                try { spinner.increment(0) } catch (Exception ignored) { /* keep the old value */ }
            }
        } as javafx.beans.value.ChangeListener)
    }

    void hookEnabled(Map p) {
        def cond = p.enabled_when
        def driver = controls[cond.param]
        def control = controls[p.name]
        if (driver == null || control == null) return
        def update = {
            def v = getters[cond.param]()
            boolean on = cond.containsKey("equals") ? (cond.equals instanceof List ? cond.equals.any { it.toString() == v?.toString() } : cond.equals.toString() == v?.toString()) : (v != null && v != false)
            wrappers[p.name].disable = !on
        }
        if (driver instanceof ComboBox) driver.valueProperty().addListener({ o, a, b -> update() } as javafx.beans.value.ChangeListener)
        else if (driver instanceof CheckBox) driver.selectedProperty().addListener({ o, a, b -> update() } as javafx.beans.value.ChangeListener)
        update()
    }

    void hookPixelSize(Map p) {
        def box = imageBoxes[p.pixel_size_of]
        if (box != null) box.valueProperty().addListener({ o, a, b -> fillPixelSize(p) } as javafx.beans.value.ChangeListener)
    }

    /** The pixel size of a chosen QuPath image goes into its pixel-size parameter (micrometres per pixel). */
    void fillPixelSize(Map p) {
        def box = imageBoxes[p.pixel_size_of]
        if (box == null) return
        def source = imageSources()[box.value]
        if (source == null) return
        try {
            def cal = source().pixelCalibration
            if (cal.hasPixelSizeMicrons()) setters[p.name](cal.pixelWidthMicrons)
        } catch (Exception ignored) { /* leave what is there */ }
    }

    // ---- running
    void run() {
        if (running || currentTool == null) return
        def toolId = currentTool.id as String
        def app = apps[appBox.value]
        def values = new LinkedHashMap()
        for (p in currentTool.inputs) {
            def v
            try { v = getters[p.name]() } catch (Exception e) { status.text = "Check '" + p.label + "': " + e.message; return }
            if (v == null) {
                if (p.required && !p.nullable && p.type in ["image", "labels", "table", "file", "folder", "string"]) { status.text = "'" + p.label + "' is required."; return }
                continue
            }
            values[p.name] = v
        }
        running = true
        runButton.disable = true
        cancelButton.disable = false
        detailsButton.disable = true
        progress.progress = ProgressBar.INDETERMINATE_PROGRESS
        status.text = "starting..."
        def appName = appBox.value
        def toolLabel = currentTool.label
        Thread.start("lc-run") {
            def t0 = System.nanoTime()
            Map outcome
            def tmp = Files.createTempDirectory("lcqupath_").toFile()
            try {
                def inputs = prepareInputs(values, currentTool.inputs, tmp, appName, toolId)
                if (worker == null || workerApp != appName || worker.closed || !worker.proc.isAlive()) {
                    stopWorker(false)
                    worker = new LcWorker(app.entry)
                    workerApp = appName
                }
                outcome = worker.run(toolId, inputs) { message, current, maximum ->
                    Platform.runLater {
                        status.text = message
                        progress.progress = (current != null && maximum) ? (current as double) / (maximum as double) : ProgressBar.INDETERMINATE_PROGRESS
                    }
                }
            } catch (Exception e) {
                outcome = [responseType: "FAILURE", error: e.class.simpleName + ": " + e.message, code: "host_error"]
            } finally {
                tmp.deleteDir()
            }
            def seconds = (System.nanoTime() - t0) / 1e9
            Platform.runLater { finish(appName, toolLabel, outcome, seconds) }
        }
    }

    Map prepareInputs(Map values, List params, File tmp, String appName, String toolId) {
        def inputs = new LinkedHashMap()
        values.each { name, v ->
            def p = params.find { it.name == name }
            if (p.type in ["image", "labels"]) {
                if (v.file) {
                    def f = new File(v.file as String)
                    if (!f.isFile()) throw new FileNotFoundException("file not found: " + f)
                    inputs[name] = f.absolutePath
                } else {
                    def server = v.source.call()
                    def out = new File(tmp, name + ".tif")
                    ImageWriterTools.writeImageRegion(server, RegionRequest.createInstance(server), out.absolutePath)
                    inputs[name] = out.absolutePath
                }
            } else if (p.type in ["file", "table", "folder"]) {
                def f = new File(v as String)
                if (!f.exists()) throw new FileNotFoundException("file not found: " + f)
                inputs[name] = f.absolutePath
            } else {
                inputs[name] = v
            }
        }
        // results go to a host-owned folder next to the ones the command line makes (the newest 20 are kept)
        def root = new File(LcRegistry.searchPath().first().parentFile, "results")
        def stamp = new java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss").format(new Date())
        def job = new File(root, stamp + "_" + System.nanoTime().toString().takeRight(6) + "_" + appName + "_" + toolId)
        job.mkdirs()
        (root.listFiles({ File f -> f.isDirectory() } as FileFilter) ?: []).sort { it.name }.reverse().drop(20).each { it.deleteDir() }
        inputs["_job_dir"] = job.absolutePath
        return inputs
    }

    void cancel() {
        status.text = "cancelling..."
        worker?.cancel()
    }

    void finish(String appName, String toolLabel, Map outcome, double seconds) {
        running = false
        runButton.disable = false
        cancelButton.disable = true
        detailsButton.disable = false
        progress.progress = 0
        def type = outcome.responseType
        lastReport = LcJson.pretty(outcome) + "\n\nworker output:\n" + (worker?.stderrText() ?: "")
        if (type == "COMPLETION") {
            def results = outcome.outputs?.results ?: []
            def summary = results.findAll { it.type == "values" }.collect { r -> r.values.collect { k, v -> k + "=" + show(v) }.join(", ") }.join("; ")
            status.text = "done in " + String.format("%.1f", seconds) + "s  " + summary
            showResults(appName, toolLabel, results)
        } else if (type == "CANCELATION") {
            status.text = "cancelled"
        } else if (type == "CRASH") {
            status.text = "the worker stopped: " + outcome.error
            stopWorker(true)
        } else {
            def code = outcome.code
            def error = outcome.error as String
            if (code in ["no_match", "no_result"]) {
                status.text = "no result: " + error.replaceFirst(/^\[[^\]]*\]\s*/, "")
                def a = new Alert(Alert.AlertType.INFORMATION, error.replaceFirst(/^\[[^\]]*\]\s*/, ""), ButtonType.OK)
                a.headerText = toolLabel + ": nothing found"
                a.initOwner(stage)
                a.show()
            } else {
                status.text = "failed: " + error
            }
        }
        if (!keepWorker.selected) stopWorker(false)
    }

    void stopWorker(boolean force) {
        def w = worker
        worker = null
        if (w == null) return
        if (force) w.kill() else Thread.start { w.close() }
    }

    void showDetails() {
        def area = new TextArea(lastReport)
        area.editable = false
        def s = new Stage()
        s.title = "LabConstrictor: last run"
        s.scene = new Scene(new BorderPane(area), 720, 480)
        s.initOwner(stage)
        s.show()
    }

    // ---- results
    void showResults(String appName, String toolLabel, List results) {
        def box = new VBox(10)
        box.padding = new Insets(10)
        for (r in results) {
            switch (r.type) {
                case "values":
                    def grid = newGrid()
                    int row = 0
                    r.values.each { k, v -> grid.add(new Label(k.toString()), 0, row); def l = new Label(show(v)); l.wrapText = true; grid.add(l, 1, row++) }
                    box.children.addAll(boldLabel(r.name ?: "values"), grid)
                    break
                case ["image", "labels"]:
                    def open = new Button("Open in QuPath")
                    def path = r.path as String
                    open.onAction = {
                        try { qupath.openImage(qupath.viewer, path, false, false) } catch (Exception e) { status.text = "cannot open: " + e.message }
                    }
                    def l = new Label(path); l.wrapText = true
                    box.children.addAll(boldLabel((r.name ?: r.type) + " (" + r.type + ", axes " + (r.axes ?: "?") + ")"), l, open)
                    break
                case "table":
                    box.children.addAll(boldLabel(r.name ?: "table"), tableView(r.path as String))
                    break
                case "affine":
                    def m = r.matrix_yx
                    def txt = (m instanceof List) ? m.collect { row -> row.collect { String.format("%.5f", it as double) }.join("   ") }.join("\n") : m.toString()
                    def l = new Label(txt); l.style = "-fx-font-family: monospace"
                    box.children.addAll(boldLabel((r.name ?: "alignment") + ": " + (r.apply_to ?: "") + " relative to " + (r.relative_to ?: "")), l)
                    break
                default:
                    box.children.addAll(boldLabel((r.name ?: r.type) + " (" + r.type + ")"), new Label((r.path ?: r.toString()) as String))
            }
        }
        def stage2 = new Stage()
        stage2.title = appName + ": " + toolLabel
        stage2.initOwner(stage)
        def sc = new ScrollPane(box)
        sc.fitToWidth = true
        stage2.scene = new Scene(sc, 640, 520)
        stage2.show()
        lastResultStage = stage2
    }
    Stage lastResultStage

    static Label boldLabel(String text) { def l = new Label(text); l.style = "-fx-font-weight: bold"; return l }

    static Node tableView(String csvPath) {
        def lines = new File(csvPath).readLines("UTF-8")
        if (lines.isEmpty()) return new Label("(empty table)")
        def header = splitCsv(lines[0])
        def table = new TableView<List<String>>()
        header.eachWithIndex { String h, int i ->
            def col = new TableColumn<List<String>, String>(h)
            col.cellValueFactory = { cd -> new javafx.beans.property.SimpleStringProperty(i < cd.value.size() ? cd.value[i] : "") } as javafx.util.Callback
            table.columns.add(col)
        }
        lines.drop(1).take(2000).each { table.items.add(splitCsv(it)) }
        table.prefHeight = 260
        def rows = Math.max(0, lines.size() - 1)
        return new VBox(4, table, new Label(rows + " row(s)" + (rows > 2000 ? " (first 2000 shown; the full table is in " + csvPath + ")" : "") + "  " + csvPath))
    }

    static List<String> splitCsv(String line) {
        def out = [], cur = new StringBuilder()
        boolean quoted = false
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i)
            if (quoted) {
                if (c == ('"' as char)) { if (i + 1 < line.length() && line.charAt(i + 1) == ('"' as char)) { cur.append('"'); i++ } else quoted = false } else cur.append(c)
            } else if (c == ('"' as char)) quoted = true
            else if (c == (',' as char)) { out << cur.toString(); cur.setLength(0) }
            else cur.append(c)
        }
        out << cur.toString()
        return out
    }
}

// ---------------------------------------------------------------------------------------------------- entry point
def lcShow = {
    def registry = LcRegistry.load()
    def dialog = new LcDialog(QuPathGUI.getInstance(), registry)
    dialog.show()
    return dialog
}
if (!binding.hasVariable("lcNoShow") && !System.getProperty("lc.noshow")) {
    if (Platform.isFxApplicationThread()) lcShow() else Platform.runLater { lcShow() }
}
