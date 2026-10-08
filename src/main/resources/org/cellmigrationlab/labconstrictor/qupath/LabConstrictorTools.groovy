/*
 * LabConstrictor tools for QuPath (prototype).
 *
 * Runs the tools of every installed LabConstrictor app (NucleiSky, CellTracksColab, ...) from QuPath. The form is generated from the
 * tool's declared schema (see https://github.com/CellMigrationLab/LabConstrictor-Tools, docs/PROTOCOL.md); the tool runs in the app's
 * own Python environment through the LabConstrictor worker (JSON lines on stdin/stdout), so QuPath never imports the app's packages.
 *
 * Use it as the extension jar (Extensions > LabConstrictor tools...) or paste it in the Script Editor and run it.
 *
 * Sections, in file order: logging (LcLog), JSON (LcJson), registry and trust checks (LcRegistry), the worker (LcWorker), the dialog (LcDialog:
 * form, copy as command, request, running, results) and the entry point.
 */
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.stage.DirectoryChooser
import javafx.stage.FileChooser
import javafx.stage.Stage
import qupath.lib.gui.QuPathGUI
import qupath.lib.images.servers.ImageServers
import qupath.lib.images.servers.TransformedServerBuilder
import qupath.lib.images.writers.ImageWriterTools
import qupath.lib.objects.PathObjects
import qupath.lib.regions.ImagePlane
import qupath.lib.regions.RegionRequest
import qupath.lib.roi.ROIs
import javafx.animation.PauseTransition
import javafx.embed.swing.SwingFXUtils
import javafx.scene.image.ImageView
import javafx.util.Duration
import java.awt.image.BufferedImage

import java.nio.file.Files
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------------------------------------------- constants
/** Every limit, timeout and size of the script in one place. Values are the ones that used to be written inline; the first four mirror the Python reference. */
class LcConst {
    // limits (what is shown, kept or accepted)
    static final int MAX_SHAPES = 50000                  // outlines added to the image; the message says how many were left out (same limit as the Python reference, shapes.MAX_LABELS_HINT)
    static final int MAX_REGION_OBJECTS = 65535          // annotations in a RegionOf selection: labels of the region image (16 bit)
    static final long MAX_REGION_PIXELS = 100_000_000L   // pixels of the image a RegionOf selection is painted on (the label image is held in memory)
    static final int RESULTS_KEPT = 20                   // results folders kept under <LC_HOME>/results (same as the command line, cli.RESULTS_KEPT)
    static final int TABLE_ROWS_SHOWN = 2000             // rows of a result table put in the results window
    static final int LOG_KEPT = 200                      // messages LcLog.recent keeps
    static final int STDERR_TRIM_ABOVE = 20000           // the worker's error text is cut back ...
    static final int STDERR_KEEP = 10000                 // ... to this many characters at the end once it is longer than the line above
    static final long INT_LIMIT = 1_000_000_000L         // an integer parameter without declared limits accepts +-this
    static final double FLOAT_LIMIT = 1e12d              // a float parameter without declared limits accepts +-this
    // timeouts and delays
    static final int EXIT_WAIT_SECONDS = 2               // how long a crash text waits for the worker's exit code
    static final int POLL_SECONDS = 1                    // a run checks whether the worker is still alive this often
    static final int CANCEL_GRACE_SECONDS = 3            // a tool that ignores Cancel is killed after this
    static final int CLOSE_WAIT_SECONDS = 10             // a worker that ignores its closed input is killed after this
    static final int CHOICES_DEBOUNCE_MS = 400           // wait for typing to stop before asking for dropdown choices again
    static final int CHOICES_BUSY_RETRY_MS = 1500        // ask again this long after, when the worker was busy with a run
    static final int CHOICES_AFTER_RUN_MS = 200          // ask again this long after a run finished
    // text excerpts (characters)
    static final int LOG_LINE_CHARS = 200                // a worker line that is not a protocol message, as logged
    static final int STDERR_LINE_CHARS = 500             // the same line, as kept for Details and crash texts
    static final int CRASH_TAIL_CHARS = 400              // the worker's last words in a crash text
    static final int STATUS_REASON_CHARS = 200           // a reason shown in the status line
    // pixels (layout)
    static final int FORM_HGAP = 8                       // gap between the label and the control of a form row
    static final int FORM_VGAP = 6                       // gap between form rows
    static final int TEXT_WIDTH = 560                    // widest description, status and message text
    static final int FORM_SCROLL_HEIGHT = 420            // preferred size of the scrolling form
    static final int FORM_SCROLL_WIDTH = 600
    static final int TOOLTIP_WIDTH = 400
    static final int SPINNER_WIDTH = 110                 // number box beside a slider
    static final int DETAILS_WIDTH = 720                 // the Details window
    static final int DETAILS_HEIGHT = 480
    static final int RESULTS_WIDTH = 640                 // a results window
    static final int RESULTS_HEIGHT = 520
    static final int PREVIEW_WIDTH = 560                 // longest side of an image preview
    static final int PREVIEW_MIN_WIDTH = 240             // a small result is enlarged to at least this
    static final int TABLE_HEIGHT = 260
    static final double LABEL_HUE_STEP = 0.61803398875d  // golden ratio: neighbouring label numbers get well separated colours in the preview
}

// ---------------------------------------------------------------------------------------------------- logging
/** Every handler that does not rethrow says so here (QuPath's log, with the stack trace); `once` is for fallbacks that are the intended
 *  behaviour, so a form that refreshes often does not flood the log. `recent` keeps the last messages (the tests read it). */
class LcLog {
    static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("labconstrictor")
    static final List<String> recent = Collections.synchronizedList(new ArrayList<String>())
    private static final Set<String> seen = Collections.synchronizedSet(new HashSet<String>())

    /** Remembers the message in `recent`, capped at the newest 200. */
    private static void keep(String text) { recent << text; while (recent.size() > LcConst.LOG_KEPT) recent.remove(0) }

    static void warn(String text, Throwable t = null) {
        keep(t != null ? text + " [" + t.class.simpleName + ": " + t.message + "]" : text)
        if (t != null) LOG.warn("LabConstrictor: " + text, t) else LOG.warn("LabConstrictor: " + text)
    }

    /** An intended fallback: logged the first time it happens for `key`. */
    static boolean once(String key, String text) {
        boolean first = seen.add(key)
        if (first) { keep(text); LOG.info("LabConstrictor: " + text) }
        return first
    }

    /** The stack trace of `t` as text (for the Details window). */
    static String trace(Throwable t) { def w = new StringWriter(); t.printStackTrace(new PrintWriter(w)); return w.toString() }

    /** Best-effort removal of a temporary folder: a folder that stays behind is reported, not ignored. */
    static void deleteTemp(File dir) {
        if (dir != null && dir.exists() && !dir.deleteDir()) warn("could not delete the temporary folder " + dir)
    }
}

// ---------------------------------------------------------------------------------------------------- JSON (QuPath ships Gson, not groovy-json)
/** One Gson instance each for compact and pretty output; numbers come back as Double (see LcDialog.show). */
class LcJson {
    static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create()
    static final Gson PRETTY = new GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting().create()
    static Object parseText(String text) { GSON.fromJson(text, Object) }          // Map / List / Double / String / Boolean
    static Object parse(File file) { parseText(file.getText("UTF-8")) }
    static String toJson(Object o) { GSON.toJson(o) }
    static String pretty(Object o) { PRETTY.toJson(o) }
}

// ---------------------------------------------------------------------------------------------------- registry and trust checks
/** Finds the registered LabConstrictor apps and refuses entries that fail the trust checks in load(). */
class LcRegistry {
    /** Variables removed from the worker's environment so the app's own Python is not mixed with QuPath's or the user's. */
    static final List<String> SCRUBBED_ENV = ["PYTHONHOME", "VIRTUAL_ENV", "CONDA_PREFIX", "QT_PLUGIN_PATH", "PYTHONPATH"]

    /** Folders searched for app registry files, in priority order: $LC_HOME/apps (default ~/.labconstrictor/apps), $LC_APPS_PATH, then the machine-wide folder. */
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
                    // lexical check (path text, links are not followed): a venv's interpreter is a link to a Python outside the prefix
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
                } catch (Exception e) {     // broad on purpose: any broken registry entry (I/O, JSON, checks above) is one listed problem, never a failed start
                    LcLog.warn("registry entry '" + name + "' skipped", e)
                    problems << (name + ": " + e.message)
                }
            }
        }
        return [apps: apps, problems: problems]
    }
}

// ---------------------------------------------------------------------------------------------------- the worker
/** One Python worker process of an app: JSON lines on stdin/stdout, one reader thread per stream, one result queue per running task. */
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
                        def msg = null
                        try {
                            msg = LcJson.parseText(line)
                        } catch (com.google.gson.JsonParseException e) {     // not JSON: a stray print of the tool or a library
                            msg = null
                            LcLog.warn("worker output that is not a protocol message: " + line.take(LcConst.LOG_LINE_CHARS), e)
                        }
                        if (msg instanceof Map) {
                            def queue = tasks.get(msg.task)
                            if (queue != null) queue.put(msg)
                        } else {
                            stderrTail.append("[not a protocol message] ").append(line.take(LcConst.STDERR_LINE_CHARS)).append("\n")    // shown in Details and in a crash text
                        }
                    }
                }
            } catch (IOException e) {      // the pipe closes when the worker ends or is killed; the CRASH below tells the person if a run was waiting
                if (!closed) LcLog.warn("lost the worker's output stream", e)
            }
            tasks.values().each { it.put([responseType: "CRASH", error: crashText()]) }
        }
        Thread.start("lc-stderr") {
            try {
                proc.errorStream.withReader("UTF-8") { r -> char[] buf = new char[2048]; int n; while ((n = r.read(buf)) > 0) { stderrTail.append(buf, 0, n); if (stderrTail.length() > LcConst.STDERR_TRIM_ABOVE) stderrTail.delete(0, stderrTail.length() - LcConst.STDERR_KEEP) } }
            } catch (IOException e) {      // same as above, for the error stream
                if (!closed) LcLog.warn("lost the worker's error stream", e)
            }
        }
    }

    /** The worker's last error output (trimmed to the end when it grows too long, see LcConst.STDERR_*). */
    String stderrText() { stderrTail.toString() }

    /** What to tell the person when the worker ends without answering: the likely cause from the exit code (wording close to the Fiji host's), then the worker's own last words. */
    String crashText() {
        Integer code = null
        try { if (proc.waitFor(LcConst.EXIT_WAIT_SECONDS, TimeUnit.SECONDS)) code = proc.exitValue() } catch (InterruptedException e) { Thread.currentThread().interrupt(); LcLog.warn("interrupted while waiting for the worker's exit code", e) }
        def hint = code in [-9, 137] ? "The worker was killed (out of memory? the OS ends big image jobs this way)."
                 : code in [-11, 139, -1073741819] ? "The worker crashed natively (segmentation fault in a compiled library)."
                 : code == 3 ? "The app's tool module failed to import (see the output below)."
                 : "The worker process stopped unexpectedly."
        def tail = stderrText().takeRight(LcConst.CRASH_TAIL_CHARS).trim()
        return hint + (code != null ? " (exit code " + code + ")" : "") + (tail ? " " + tail : "")
    }

    /** Sends one EXECUTE and blocks until its terminal message; `onUpdate` gets (message, current, maximum). */
    Map run(String toolId, Map inputs, Closure onUpdate) {
        def id = "lcq-" + (++counter) + "-" + System.nanoTime()
        def queue = new LinkedBlockingQueue<Map>()
        tasks.put(id, queue)
        try {
            send([task: id, requestType: "EXECUTE", script: "lc:" + toolId, inputs: inputs])
            while (true) {
                def msg = queue.poll(LcConst.POLL_SECONDS, TimeUnit.SECONDS)
                if (msg == null) {
                    if (!proc.isAlive()) return [responseType: "CRASH", error: crashText()]
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
    volatile String currentTask = null      // id of the run in progress (cancel targets it)

    /** Writes one JSON line to the worker; synchronized so lines from the run and cancel threads never interleave. */
    private synchronized void send(Map message) {
        if (message.requestType == "EXECUTE") currentTask = message.task
        def out = proc.outputStream
        out.write((LcJson.toJson(message) + "\n").getBytes("UTF-8"))
        out.flush()
    }

    /** Asks the running tool to stop; a tool that ignores the request is killed after `graceSeconds`. */
    void cancel(int graceSeconds = LcConst.CANCEL_GRACE_SECONDS) {
        def id = currentTask
        if (id == null) return
        try { send([task: id, requestType: "CANCEL"]) } catch (IOException e) { LcLog.warn("could not send the cancel request (the worker is killed after " + graceSeconds + " s)", e) }
        Thread.start("lc-cancel") {
            Thread.sleep(graceSeconds * 1000L)
            if (currentTask == id) kill()
        }
    }

    /** Ends the worker and its child processes at once. */
    void kill() {
        closed = true
        try { proc.descendants().each { it.destroyForcibly() } } catch (SecurityException | UnsupportedOperationException e) { LcLog.warn("could not stop the worker's child processes", e) }
        proc.destroyForcibly()
    }

    /** Closes the worker's input so it ends by itself; killed if it is still there after LcConst.CLOSE_WAIT_SECONDS. */
    void close() {
        closed = true
        try { proc.outputStream.close() } catch (IOException e) { LcLog.warn("could not close the worker's input (it is killed if it does not stop)", e) }
        if (!proc.waitFor(LcConst.CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)) kill()
    }
}

// ---------------------------------------------------------------------------------------------------- the dialog (the form, the request, running, results, copy as command)
/** The tools dialog. State (maps of controls, getters, setters) is rebuilt for every tool; the tests read some of it. */
class LcDialog {
    // ---- helpers and state
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
    Map<String, ComboBox<String>> channelBoxes = [:]   // PickChannel: the channel chooser of an image parameter
    Map<String, CheckBox> checks = [:]            // the 'set' box of each optional parameter
    Map<String, ComboBox<String>> choiceBoxes = [:]   // ChoicesFrom: the dropdown beside the text field
    Map<String, Integer> choiceSeq = [:]          // newest question per parameter: older answers are dropped
    PauseTransition choiceTimer = new PauseTransition(Duration.millis(LcConst.CHOICES_DEBOUNCE_MS))
    Label messageLabel = new Label()              // message results of the last run
    Map<String, Stage> resultWindows = [:]        // app/tool -> its last results window (Replace reuses it)
    Map<String, String> imageChoiceAtRun = [:]    // image parameter -> what was chosen when the run started
    Map<String, Node> wrappers = [:]             // the node placed in the form for each parameter (enabled_when disables this one)
    String lastCopied                              // the text the last Copy as command put on the clipboard (for tests)
    Map<String, CheckBox> selectionBoxes = [:]       // RegionOf: the 'use the selection' box of a region parameter
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

    // ---- the form: window and top-level wiring
    void show() {
        stage = new Stage()
        stage.title = "LabConstrictor tools"
        if (qupath?.stage) stage.initOwner(qupath.stage)
        configureWidgets()
        def root = new VBox(8, appBox, toolBox, description, formScroll(), runButton, progress, status, messageLabel, keepWorker, buttonRow())
        root.padding = new Insets(10)
        if (apps.isEmpty()) {
            status.text = "No LabConstrictor app is registered on this machine." + (problems ? " Skipped: " + problems.join("; ") : "")
        }
        stage.scene = new Scene(root)
        stage.onHidden = { stopWorker(false) }   // also when closed from code
        stage.show()
        if (!apps.isEmpty()) appBox.value = apps.keySet().first()
    }

    /** Sizes, listeners and actions of the widgets that are fields of the dialog. */
    void configureWidgets() {
        keepWorker.selected = true
        description.wrapText = true
        description.maxWidth = LcConst.TEXT_WIDTH
        status.wrapText = true
        status.maxWidth = LcConst.TEXT_WIDTH
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
        messageLabel.wrapText = true
        messageLabel.maxWidth = LcConst.TEXT_WIDTH
        messageLabel.style = "-fx-border-color: #5a9fd4; -fx-border-width: 0 0 0 3; -fx-padding: 4 8 4 8"
        messageLabel.visible = false
        messageLabel.managed = false
        choiceTimer.onFinished = { resolveChoices() }
    }

    /** The scrolling area that holds the generated form. */
    ScrollPane formScroll() {
        def scroll = new ScrollPane(formBox)
        scroll.fitToWidth = true
        scroll.prefViewportHeight = LcConst.FORM_SCROLL_HEIGHT
        scroll.prefViewportWidth = LcConst.FORM_SCROLL_WIDTH
        VBox.setVgrow(scroll, Priority.ALWAYS)
        return scroll
    }

    /** Cancel, Rescan apps, Restart worker, Details and the Copy as command menu. */
    HBox buttonRow() {
        def rescanButton = new Button("Rescan apps")
        rescanButton.onAction = { rescan() }
        def restartButton = new Button("Restart worker")
        restartButton.onAction = { stopWorker(true); status.text = "worker stopped" }
        return new HBox(6, cancelButton, rescanButton, restartButton, detailsButton, copyMenu())
    }

    /** The Copy as command menu button (terminal line or Python snippet). */
    MenuButton copyMenu() {
        def copyMenu = new MenuButton("Copy as command")
        copyMenu.tooltip = new Tooltip("Copy what repeats this run outside QuPath: a terminal line or a Python snippet")
        def terminalItem = new MenuItem("Terminal command"), pythonItem = new MenuItem("Python snippet")
        terminalItem.onAction = { copyAsCommand("terminal") }
        pythonItem.onAction = { copyAsCommand("python") }
        copyMenu.items.addAll(terminalItem, pythonItem)
        return copyMenu
    }

    /** Reads the registry again and keeps the chosen app when it is still there. */
    void rescan() {
        def registry = LcRegistry.load()
        apps = registry.apps
        problems = registry.problems
        def current = appBox.value
        appBox.items.setAll(apps.keySet())
        if (current in apps) appBox.value = current else if (!apps.isEmpty()) appBox.value = apps.keySet().first()
        status.text = "Found " + apps.size() + " app(s)" + (problems ? "; skipped: " + problems.join("; ") : "")
    }

    /** A new app was chosen: list its tools. */
    void onApp() {
        def app = apps[appBox.value]
        toolBox.items.setAll(app ? app.schema.tools.collect { it.label } : [])
        if (!toolBox.items.isEmpty()) toolBox.value = toolBox.items.first()
    }

    /** A new tool was chosen: forget the old form's controls and build the new form. */
    void onTool() {
        def app = apps[appBox.value]
        currentTool = app?.schema?.tools?.find { it.label == toolBox.value }
        formBox.children.clear()
        getters.clear(); setters.clear(); controls.clear(); wrappers.clear(); imageBoxes.clear(); imageOf.clear(); selectionBoxes.clear()
        checks.clear(); choiceBoxes.clear(); choiceSeq.clear(); channelBoxes.clear()
        description.text = currentTool?.description ?: ""
        if (currentTool == null) return
        buildForm(currentTool.inputs)
        hookChoices()
        scheduleChoices(0)
    }

    // ---- dynamic choices (ChoicesFrom), clear after run
    /** Asks for the choices again (after a pause in the typing) whenever a parameter the choices depend on changes. */
    void hookChoices() {
        currentTool.inputs.findAll { it.choices_from }.each { p ->
            p.choices_from.depends.each { dep ->
                def control = controls[dep]
                if (control instanceof TextField) control.textProperty().addListener({ o, a, b -> scheduleChoices(LcConst.CHOICES_DEBOUNCE_MS) } as javafx.beans.value.ChangeListener)
                else if (control instanceof ComboBox) control.valueProperty().addListener({ o, a, b -> scheduleChoices(LcConst.CHOICES_DEBOUNCE_MS) } as javafx.beans.value.ChangeListener)
            }
        }
    }

    /** (Re)starts the timer that resolves the choices; a burst of changes becomes one question. */
    void scheduleChoices(int delayMs) {
        if (choiceBoxes.isEmpty()) return
        choiceTimer.duration = Duration.millis(Math.max(delayMs, 1))
        choiceTimer.playFromStart()
    }

    /** Starts one worker-side question per ChoicesFrom parameter; each gets a number so a late answer for an older question is dropped. */
    void resolveChoices() {
        if (currentTool == null || choiceBoxes.isEmpty()) return
        if (running) { scheduleChoices(LcConst.CHOICES_BUSY_RETRY_MS); return }              // the worker is busy with a run: ask again afterwards
        def app = apps[appBox.value]
        def appName = appBox.value
        currentTool.inputs.findAll { it.choices_from && choiceBoxes[it.name] }.each { p ->
            def request = choiceRequest(p)
            def number = (choiceSeq[p.name] = (choiceSeq[p.name] ?: 0) + 1)
            if (request == null) { applyChoices(p.name, number, null, "waiting for a value of " + p.choices_from.depends.join(", ")); return }
            Thread.start("lc-choices") { fetchChoices(p, request, number, app, appName) }
        }
    }

    /** The values the source tool needs (the parameters this one depends on), or null while one of them is not ready. */
    Map choiceRequest(Map p) {
        def request = new LinkedHashMap()
        for (dep in p.choices_from.depends) {
            def v = null
            try { v = getters[dep]() } catch (Exception e) { LcLog.warn("choices of '" + p.name + "': could not read '" + dep + "' (text field kept)", e) }   // a getter that fails leaves the question not ready
            def depParam = currentTool.inputs.find { it.name == dep }
            if (v == null || v.toString().isEmpty() || (depParam.type == "folder" && !new File(v.toString()).isDirectory())) return null
            request[dep] = v
        }
        return request
    }

    /** Runs the source tool on the worker thread and hands its answer (or why there is none) to applyChoices on the FX thread. */
    void fetchChoices(Map p, Map request, int number, Map app, String appName) {
        List options = null
        String why = null
        def tmp = Files.createTempDirectory("lcqchoices_").toFile()
        try {
            request["_job_dir"] = tmp.absolutePath
            ensureWorker(app, appName)
            def outcome = worker.run(p.choices_from.tool as String, request, null)
            if (outcome.responseType == "COMPLETION") {
                def found = (outcome.outputs?.results ?: []).find { it.type == "values" && it.values?.get(p.choices_from.field ?: "choices") instanceof List }
                options = found?.values?.get(p.choices_from.field ?: "choices")?.collect { it.toString() }
                if (options == null) why = "the source tool '" + p.choices_from.tool + "' did not return a list"
            } else {
                why = "the source tool '" + p.choices_from.tool + "' did not complete (" + outcome.responseType + ": " + outcome.error + ")"
            }
        } catch (Exception e) {
            // broad on purpose (isolation boundary): whatever the source tool or its worker does, the form is never blocked; the text field remains
            LcLog.warn("choices of '" + p.name + "' could not be loaded", e)
            why = e.class.simpleName + ": " + e.message
        } finally { LcLog.deleteTemp(tmp) }
        def failure = why
        Platform.runLater { applyChoices(p.name, number, options, failure) }
    }

    /** `why` says why there are no options (the text field is used instead); the reason is logged once and, for a failure, shown in the status line. */
    void applyChoices(String name, int number, List options, String why = null) {
        if (choiceSeq[name] != number) return                         // a late answer for another question or form
        def combo = choiceBoxes[name], field = controls[name]
        if (combo == null) return
        if (!options) {
            combo.visible = false; combo.managed = false; field.visible = true; field.managed = true
            boolean first = LcLog.once("choices:" + currentTool?.id + ":" + name + ":" + (why == null ? "empty" : why.startsWith("waiting") ? "waiting" : "failed"), "'" + name + "' stays a text field" + (why ? ": " + why : " (the source tool listed nothing)"))
            // shown the first time only: the refresh after every run must not replace the result of that run in the status line
            if (first && why && !why.startsWith("waiting") && !running) status.text = "The choices for '" + name + "' could not be loaded (type the value): " + why.take(LcConst.STATUS_REASON_CHARS)
            return
        }
        def current = (field as TextField).text ?: ""
        def param = currentTool.inputs.find { it.name == name } ?: [:]
        // a blank entry means "no answer" (unset for an optional parameter, or when the field is empty); a value the field already
        // holds (its default, or what was typed) stays selectable even when the source tool does not list it: never silently dropped
        def entries = (param.nullable || !current ? [""] : []) + (current && !options.contains(current) ? [current] : []) + options
        combo.items.setAll(entries)
        combo.value = entries.contains(current) ? current : entries.first()
        field.visible = false; field.managed = false
        combo.visible = true; combo.managed = true
    }

    /** ClearAfterRun parameters go back to their default (or unset) after a successful run. */
    void clearAfterRun() {
        currentTool.inputs.findAll { it.clear_after_run }.each { p ->
            checks[p.name]?.selected = false
            choiceBoxes[p.name]?.value = ""
            def control = controls[p.name]
            if (control instanceof TextField) control.text = p.default != null ? p.default.toString() : ""
        }
    }

    // ---- the form: images available in QuPath
    /** The images a form can use: the open image first, then every image of the project (label -> closure that returns its ImageServer). */
    Map<String, Closure> imageSources() {
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

    // ---- the form: building it for the chosen tool
    /** Rows for the normal parameters, one collapsed section per Collapsed group, one for Advanced; then the links between parameters. */
    void buildForm(List inputs) {
        def sources = imageSources()
        def shown = inputs.findAll { !it.advanced }, advanced = inputs.findAll { it.advanced }
        def folded = shown.findAll { it.group_collapsed }.groupBy { it.group }          // accordion sections (Collapsed)
        def normal = shown.findAll { !it.group_collapsed }
        def grid = newGrid()
        addRows(grid, normal, sources)
        formBox.children.add(grid)
        folded.each { group, params ->
            def foldedGrid = newGrid()
            addRows(foldedGrid, params.collect { it + [group: null] }, sources)
            def section = new TitledPane(group as String, foldedGrid)
            section.expanded = false
            formBox.children.add(section)
        }
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

    /** A two-column grid (label, control) with the form's spacing. */
    GridPane newGrid() {
        def grid = new GridPane()
        grid.hgap = LcConst.FORM_HGAP
        grid.vgap = LcConst.FORM_VGAP
        grid.padding = new Insets(4)
        def c0 = new ColumnConstraints(), c1 = new ColumnConstraints()
        c1.hgrow = Priority.ALWAYS
        grid.columnConstraints.addAll(c0, c1)
        return grid
    }

    /** One row per parameter (group headings between groups); the description becomes the label's tooltip. */
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
                tip.maxWidth = LcConst.TOOLTIP_WIDTH
                Tooltip.install(label, tip)
            }
            grid.add(label, 0, row)
            grid.add(node, 1, row++)
        }
    }

    // ---- the form: one control per parameter type, each registering its own node and its getter/setter
    /** The control of one parameter, wrapped for the form; registers controls, getters, setters, checks and wrappers under the parameter's name. */
    Node makeControl(Map p, Map sources) {
        def name = p.name
        Map built
        switch (p.type) {
            case "boolean": built = booleanControl(p); break
            case "choice": built = choiceControl(p); break
            case "integer": built = integerControl(p); break
            case "float": built = floatControl(p); break
            case "image":
            case "labels": built = imageControl(p, sources); break
            case "file":
            case "table":
            case "folder": built = fileControl(p); break
            default: built = stringControl(p)   // string
        }
        Node node = built.node
        Closure get = built.get, set = built.set
        if (p.nullable && !p.region_of) {      // optional without a default: unticked = the tool receives None (a region has its own 'use the selection' box)
            def check = new CheckBox("set")
            checks[name] = check
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

    /** The control builders below each return [node, get, set] and register the control under the parameter's name. */
    Map booleanControl(Map p) {
        def box = new CheckBox()
        box.selected = p.default == true
        controls[p.name] = box
        return [node: box, get: { box.selected }, set: { box.selected = it as boolean }]
    }

    Map choiceControl(Map p) {
        def box = new ComboBox<String>()
        box.items.setAll(p.choices.collect { it.toString() })
        box.maxWidth = Double.MAX_VALUE
        if (p.default != null) box.value = p.default.toString() else if (!box.items.isEmpty()) box.value = box.items.first()
        controls[p.name] = box
        Node node = box
        if (p.widget == "radio" && !p.nullable) node = radioButtonsFor(box)     // Widget("radio")
        return [node: node,
                get: { def i = box.items.indexOf(box.value); i >= 0 ? p.choices[i] : null },
                set: { box.value = it.toString() }]
    }

    /** Widget("radio"): radio buttons that drive the (hidden) combo box, so every other rule keeps working. */
    Node radioButtonsFor(ComboBox<String> box) {
        def group = new ToggleGroup()
        def buttons = box.items.collect { item ->
            def rb = new RadioButton(item)
            rb.toggleGroup = group
            rb.selected = item == box.value
            rb.onAction = { box.value = item }
            rb
        }
        box.valueProperty().addListener({ o, a, b -> buttons.each { it.selected = it.text == b } } as javafx.beans.value.ChangeListener)
        box.visible = false; box.managed = false
        return new HBox(12, *buttons, box)
    }

    /** A spinner (optionally with a slider); with no declared limits it accepts +-LcConst.INT_LIMIT. */
    Map integerControl(Map p) {
        def lo = p.minimum != null ? (p.minimum as double).longValue() : -LcConst.INT_LIMIT
        def hi = p.maximum != null ? (p.maximum as double).longValue() : LcConst.INT_LIMIT
        def initial = p.default != null ? (p.default as double).longValue() : Math.max(lo, Math.min(hi, 0L))
        def spinner = new Spinner<Integer>(lo as int, hi as int, initial as int)
        spinner.editable = true
        commitOnFocusLost(spinner, p.label as String)
        controls[p.name] = spinner
        Node node = spinner
        if (wantsSlider(p)) node = sliderBeside(spinner, lo, hi, initial) { double b -> spinner.valueFactory.value = Math.round(b) as int }
        return [node: node, get: { spinner.value }, set: { spinner.valueFactory.value = (it as double).intValue() }]
    }

    /** A spinner (optionally with a slider); with no declared limits it accepts +-LcConst.FLOAT_LIMIT. The step is 1% of a range of 100 or less, else 1. */
    Map floatControl(Map p) {
        def lo = p.minimum != null ? p.minimum as double : -LcConst.FLOAT_LIMIT
        def hi = p.maximum != null ? p.maximum as double : LcConst.FLOAT_LIMIT
        def initial = p.default != null ? p.default as double : Math.max(lo, Math.min(hi, 0d))
        def step = (hi - lo) <= 100 && p.maximum != null ? (hi - lo) / 100 : 1d
        def spinner = new Spinner<Double>(lo, hi, initial, step)
        spinner.editable = true
        commitOnFocusLost(spinner, p.label as String)
        controls[p.name] = spinner
        Node node = spinner
        if (wantsSlider(p)) node = sliderBeside(spinner, lo, hi, initial) { double b -> spinner.valueFactory.value = b }
        return [node: node, get: { spinner.value }, set: { spinner.valueFactory.value = it as double }]
    }

    /** A slider is only offered when asked for, the parameter is not optional and both limits are known. */
    static boolean wantsSlider(Map p) { p.widget == "slider" && !p.nullable && p.minimum != null && p.maximum != null }

    /** Widget("slider"): a slider beside the typed box, kept in step; `toSpinner` puts a slider position into the spinner. */
    static Node sliderBeside(Spinner spinner, def lo, def hi, def initial, Closure toSpinner) {
        def slider = new Slider(lo, hi, initial)
        slider.maxWidth = Double.MAX_VALUE
        HBox.setHgrow(slider, Priority.ALWAYS)
        boolean syncing = false
        slider.valueProperty().addListener({ o, a, b ->
            if (syncing) return
            syncing = true
            try { toSpinner(b as double) } finally { syncing = false }
        } as javafx.beans.value.ChangeListener)
        spinner.valueProperty().addListener({ o, a, b ->
            if (syncing || b == null) return
            syncing = true
            try { slider.value = (b as double) } finally { syncing = false }
        } as javafx.beans.value.ChangeListener)
        spinner.prefWidth = LcConst.SPINNER_WIDTH
        return new HBox(8, slider, spinner)
    }

    /** An image or label parameter: a QuPath image or a file, optionally one channel of it, optionally the selected annotations (RegionOf). */
    Map imageControl(Map p, Map sources) {
        def name = p.name
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
        def channelBox = p.pick_channel ? channelChooser(p, box, fileField, sourceMap) : null
        def selectionBox = p.region_of ? regionSelectionBox(name, box, fileField) : null
        Node node = new HBox(6, box, fileField, browse)
        if (selectionBox != null) node = new VBox(2, node, selectionBox)
        if (channelBox != null) node = new VBox(2, node, new HBox(6, new Label("Channel"), channelBox))
        HBox.setHgrow(box, Priority.SOMETIMES)
        controls[name] = box
        return [node: node, set: { },
                get: {
                    if (selectionBox != null && selectionBox.selected) return [selection: true]
                    def v = box.value
                    if (v == NO_IMAGE) return null
                    def channel = channelBox != null && channelBox.visible ? channelBox.items.indexOf(channelBox.value) : -1
                    if (v == FILE_CHOICE) return fileField.text?.trim() ? [file: fileField.text.trim(), channel: channel] : null
                    return [source: sourceMap[v], channel: channel]
                }]
    }

    /** PickChannel: the channels (names) of the chosen image; the tool gets only the chosen one. */
    ComboBox<String> channelChooser(Map p, ComboBox<String> box, TextField fileField, Map sourceMap) {
        def name = p.name
        def channelBox = new ComboBox<String>()
        channelBox.maxWidth = Double.MAX_VALUE
        channelBoxes[name] = channelBox
        def refresh = {
            def names = []
            try {
                def server = box.value == FILE_CHOICE ? (fileField.text?.trim() ? ImageServers.buildServer(fileField.text.trim()) : null) : sourceMap[box.value]?.call()
                if (server != null) names = server.metadata.channels.collect { it.name as String }
            } catch (Exception e) {      // broad on purpose (a file chooser entry can fail in any reader): the channel list must never break the form
                LcLog.warn("channels of '" + name + "' could not be read", e)
                status?.text = "Channels of '" + p.label + "' could not be read: " + e.message
            }
            if (names.size() <= 1) LcLog.once("channels:" + currentTool?.id + ":" + name, "no channel chooser for '" + name + "' (the image has " + names.size() + " channel(s))")
            channelBox.items.setAll(names.size() > 1 ? names : [])
            channelBox.value = names.size() > 1 ? names.first() : null
            channelBox.visible = channelBox.managed = names.size() > 1
        }
        box.valueProperty().addListener({ o, a, b -> refresh() } as javafx.beans.value.ChangeListener)
        fileField.focusedProperty().addListener({ o, a, focused -> if (!focused) refresh() } as javafx.beans.value.ChangeListener)
        Platform.runLater { refresh() }
        return channelBox
    }

    /** RegionOf: the selected annotations can be the value. */
    CheckBox regionSelectionBox(String name, ComboBox<String> box, TextField fileField) {
        def selectionBox = new CheckBox("use the selection")
        selectionBox.tooltip = new Tooltip("Send the selected annotations as the region (several are labels 1, 2, 3...)")
        selectionBox.selectedProperty().addListener({ o, a, on -> box.disable = on; fileField.disable = on } as javafx.beans.value.ChangeListener)
        selectionBoxes[name] = selectionBox
        return selectionBox
    }

    /** A text field with a Browse button (folder chooser for a folder); empty text means unset. */
    Map fileControl(Map p) {
        def field = new TextField()
        field.maxWidth = Double.MAX_VALUE
        HBox.setHgrow(field, Priority.ALWAYS)
        def browse = new Button("...")
        browse.onAction = {
            def f = p.type == "folder" ? new DirectoryChooser().showDialog(stage) : new FileChooser().showOpenDialog(stage)
            if (f != null) field.text = f.absolutePath
        }
        controls[p.name] = field
        return [node: new HBox(6, field, browse), get: { field.text?.trim() ? field.text.trim() : null }, set: { field.text = it as String }]
    }

    /** A text field; with ChoicesFrom a hidden dropdown is placed under it and takes over when the source tool lists options. */
    Map stringControl(Map p) {
        def name = p.name
        def field = new TextField(p.default != null ? p.default.toString() : "")
        field.maxWidth = Double.MAX_VALUE
        controls[name] = field
        Node node = field
        if (p.choices_from) {            // a dropdown where the source tool can answer, the text field where it cannot
            def combo = new ComboBox<String>()
            combo.maxWidth = Double.MAX_VALUE
            combo.visible = false; combo.managed = false
            combo.valueProperty().addListener({ o, a, b ->
                if (b == null) return
                field.text = b
                checks[name]?.selected = b != ""        // picking an option also sets an optional parameter
            } as javafx.beans.value.ChangeListener)
            choiceBoxes[name] = combo
            node = new VBox(2, field, combo)
        }
        return [node: node, get: { field.text }, set: { field.text = it as String }]
    }

    /** A spinner only takes typed text on Enter; this also takes it when the box loses focus. */
    void commitOnFocusLost(Spinner spinner, String label = null) {
        spinner.focusedProperty().addListener({ o, a, focused -> if (!focused) commitSpinner(spinner, label) } as javafx.beans.value.ChangeListener)
    }

    /** Takes what was typed into the box; text that is not a number keeps the old value, and the person is told. */
    void commitSpinner(Spinner spinner, String label = null) {
        try {
            spinner.increment(0)
        } catch (RuntimeException e) {      // JavaFX reports unparseable text as NumberFormatException or as a RuntimeException around a ParseException
            if (!(e instanceof NumberFormatException) && !(e.cause instanceof java.text.ParseException)) throw e
            LcLog.warn("'" + (label ?: "number") + "': '" + spinner.editor?.text + "' is not a number (kept " + spinner.value + ")", e)
            status?.text = "Check '" + (label ?: "number") + "': '" + spinner.editor?.text + "' is not a number; " + spinner.value + " is used."
        }
    }

    /** EnabledWhen: the parameter's row is disabled unless the driving parameter has the required value (or is set, when no value is named). */
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

    /** PixelSizeOf: refill the pixel size whenever another image is chosen. */
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
        } catch (Exception e) {     // broad on purpose: a listener on the image dropdown must never break the form; the field keeps its value
            LcLog.warn("pixel size of '" + box.value + "' could not be read", e)
            status?.text = "Pixel size of '" + box.value + "' could not be read; '" + p.label + "' keeps its value: " + e.message
        }
    }

    // ---- copy as command (the text format of labconstrictor_tools.command, the reference). Quoting only through two helpers: shellQuote (terminal words)
    // and pythonLiteral (Python snippet); no copied text is assembled with bare quotes around a value.
    /** The placeholder path written for a required file-like parameter that has no path. */
    static final Map<String, String> FILE_PLACEHOLDERS = [image: "image.tif", labels: "labels.tif", table: "table.csv", file: "file", folder: "folder"]

    /** Quotes one word for a POSIX shell or for Windows; text made only of safe characters stays as it is. */
    static String shellQuote(String text, boolean windows) {
        if (windows) return (text && text ==~ /[A-Za-z0-9_.:\/\\=+,-]+/) ? text : '"' + text.replace('"', '\\"') + '"'
        return (text && text ==~ /[A-Za-z0-9_@%+=:,.\/-]+/) ? text : "'" + text.replace("'", "'\"'\"'") + "'"
    }

    /** The values a command line needs, in the tool's order: the file behind each image, else a placeholder; unset parameters are left out. */
    Map commandValues() {
        def values = [:], missing = [], notes = []
        for (p in currentTool.inputs) {
            def v = null
            try { v = getters[p.name]() } catch (Exception e) {
                LcLog.warn("command line: could not read '" + p.name + "' (left out)", e)
                notes << ("# " + p.name + ": the value could not be read (" + e.message + "); it is left out")
            }
            if (p.type in ["image", "labels"]) v = commandImagePath(p, v, notes)
            if (v != null) values[p.name] = v
            if (p.type in FILE_PLACEHOLDERS.keySet() && p.required && values[p.name] == null) { values[p.name] = FILE_PLACEHOLDERS[p.type]; missing << p.name }
        }
        return [values: values, missing: missing, notes: notes]
    }

    /** The file a command line names for an image value: the file typed, or the file behind a QuPath image; null (with a note) when there is none. */
    String commandImagePath(Map p, def v, List notes) {
        String path = null
        if (v instanceof Map && v.selection) {
            path = "region.tif"
            notes << ("# " + p.name + ": the selection cannot be copied; save it as a label image and put its path here")
        } else if (v instanceof Map) {
            if (v.file) path = v.file as String
            else if (v.source != null) {
                try {
                    def uri = v.source.call().getURIs()?.find { it.scheme == "file" }
                    if (uri != null) path = new File(uri).path
                } catch (Exception e) {      // broad on purpose: the image getter is QuPath's; the command still gets a placeholder
                    LcLog.warn("command line: no file for the image of '" + p.name + "'", e)
                    notes << ("# " + p.name + ": the image file could not be determined (" + e.message + ")")
                }
            }
            if (p.pick_channel && v.channel != null && v.channel >= 0) notes << ("# " + p.name + ": QuPath sent only channel " + (v.channel + 1) + "; the command sends the whole file")
        }
        return path
    }

    /** The clipboard text: notes (placeholders, values left out) as comment lines, then the Python snippet or the terminal command. */
    String commandText(String kind) {
        def built = commandValues()
        def head = (built.missing ? ["# replace the file for: " + built.missing.join(", ")] : []) + built.notes
        def note = head ? head.join("\n") + "\n" : ""
        def given = currentTool.inputs.findAll { built.values.containsKey(it.name) }
        return note + (kind == "python" ? pythonSnippet(given, built.values) : terminalCommand(given, built.values))
    }

    /** One Python literal: True/False, a number as it is, anything else as a single-quoted string (backslash and quote escaped). */
    static String pythonLiteral(Object v) {
        if (v instanceof Boolean) return v ? "True" : "False"
        if (v instanceof Number) return v.toString()
        return "'" + v.toString().replace("\\", "\\\\").replace("'", "\\'") + "'"
    }

    /** Python that runs the tool once through the client; every name and value is written through pythonLiteral. */
    String pythonSnippet(List given, Map values) {
        def body = given ? "{\n" + given.collect { "    " + pythonLiteral(it.name) + ": " + pythonLiteral(values[it.name]) + "," }.join("\n") + "\n}" : "{}"
        return "from labconstrictor_tools import client\n\ntask = client.run_once(" + pythonLiteral(appBox.value) + ", " + pythonLiteral(currentTool.id) + ", " + body + ")\n" +
               'print(task.status, task.outputs if task.status == "COMPLETE" else task.error)'
    }

    /** `python -m labconstrictor_tools run <app> <tool> name=value ...`, every word quoted for this machine's shell. */
    String terminalCommand(List given, Map values) {
        def app = apps[appBox.value]
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win")
        def parts = [app.entry.python as String, "-m", "labconstrictor_tools", "run", appBox.value as String, currentTool.id as String].collect { shellQuote(it, windows) }
        given.each { p ->
            def v = values[p.name]
            parts << shellQuote(p.name + "=" + (v instanceof Boolean ? (v ? "true" : "false") : v.toString()), windows)
        }
        return parts.join(" ")
    }

    /** Puts the command text on the clipboard and returns it (null when no tool is chosen). */
    String copyAsCommand(String kind) {
        if (currentTool == null) return null
        def text = commandText(kind)
        def content = new javafx.scene.input.ClipboardContent()
        content.putString(text)
        javafx.scene.input.Clipboard.systemClipboard.setContent(content)
        lastCopied = text
        status.text = "copied the " + (kind == "python" ? "Python snippet" : "terminal command") + " to the clipboard"
        return text
    }

    // ---- the request: what the form asks for
    /** The values of the form in the tool's order, or null (with the reason in the status line) when one cannot be read or a required one is missing. */
    Map readFormValues() {
        def values = new LinkedHashMap()
        for (p in currentTool.inputs) {
            def v
            try { v = getters[p.name]() } catch (Exception e) { LcLog.warn("could not read '" + p.name + "'", e); status.text = "Check '" + p.label + "': " + e.message; return null }
            if (v == null) {
                if (p.required && !p.nullable && p.type in ["image", "labels", "table", "file", "folder", "string"]) { status.text = "'" + p.label + "' is required."; return null }
                continue
            }
            values[p.name] = v
        }
        return values
    }

    // ---- running
    /** Run pressed: read the form, then start the run in the background. */
    void run() {
        if (running || currentTool == null) return
        def values = readFormValues()
        if (values == null) return
        imageChoiceAtRun = imageBoxes.collectEntries { k, b -> [(k): b.value as String] }
        showRunning()
        runInBackground(values)
    }

    /** The form goes busy: Run off, Cancel on, an indeterminate progress bar. */
    void showRunning() {
        messageLabel.visible = false; messageLabel.managed = false
        running = true
        runButton.disable = true
        cancelButton.disable = false
        detailsButton.disable = true
        progress.progress = ProgressBar.INDETERMINATE_PROGRESS
        status.text = "starting..."
    }

    /** The form is free again after a run. */
    void showIdle() {
        running = false
        runButton.disable = false
        cancelButton.disable = true
        detailsButton.disable = false
        progress.progress = 0
    }

    /** The worker is started (or replaced) when there is none for this app, or it has died. */
    void ensureWorker(Map app, String appName) {
        if (worker == null || workerApp != appName || worker.closed || !worker.proc.isAlive()) {
            stopWorker(false)
            worker = new LcWorker(app.entry)
            workerApp = appName
        }
    }

    /** Copies what the thread needs (the form may change meanwhile), runs on a thread, shows the outcome on the FX thread. */
    void runInBackground(Map values) {
        def toolId = currentTool.id as String
        def app = apps[appBox.value]
        def appName = appBox.value
        def toolLabel = currentTool.label
        def toolInputs = currentTool.inputs
        Thread.start("lc-run") {
            def t0 = System.nanoTime()
            Map outcome = runOnWorker(app, appName, toolId, toolInputs, values)
            def seconds = (System.nanoTime() - t0) / 1e9
            Platform.runLater { finish(appName, toolLabel, outcome, seconds) }
        }
    }

    /** One run on the worker thread; every failure becomes the outcome the person reads. */
    Map runOnWorker(Map app, String appName, String toolId, List toolInputs, Map values) {
        Map outcome
        def tmp = Files.createTempDirectory("lcqupath_").toFile()
        try {
            def inputs = prepareInputs(values, toolInputs, tmp, appName, toolId)
            ensureWorker(app, appName)
            outcome = worker.run(toolId, inputs) { message, current, maximum ->
                Platform.runLater {
                    status.text = message
                    progress.progress = (current != null && maximum) ? (current as double) / (maximum as double) : ProgressBar.INDETERMINATE_PROGRESS
                }
            }
        } catch (Exception e) {
            // broad on purpose (isolation boundary of the run thread): any failure becomes the outcome the person reads (status line and Details, with the stack trace)
            LcLog.warn("run of '" + toolId + "' failed on the host", e)
            outcome = [responseType: "FAILURE", error: e.class.simpleName + ": " + e.message, code: "host_error", trace: LcLog.trace(e)]
        } finally {
            LcLog.deleteTemp(tmp)
        }
        return outcome
    }

    // ---- the request: what the tool receives
    /** RegionOf: the selected annotations of the open image as a 16-bit label image the size of that image (labels 1..N, 0 outside).
     *  Whatever makes that impossible is said to the person, never guessed around. */
    File selectionMask(Map p, File tmp, String appName) {
        def label = p.label as String
        def chosen = imageChoiceAtRun[p.region_of]
        def data = qupath?.imageData
        if (data == null || chosen == null || !chosen.startsWith("Current image"))
            throw new IllegalStateException("'" + label + "': the selection belongs to the image open in QuPath: choose 'Current image' for the image, or untick the selection")
        def selected = data.hierarchy.selectionModel.selectedObjects.findAll { it.isAnnotation() && it.ROI != null && !it.ROI.isPoint() }
        if (!selected) throw new IllegalStateException("'" + label + "': no annotation is selected: select one or more annotations, or untick the selection")
        if (selected.size() > LcConst.MAX_REGION_OBJECTS) throw new IllegalStateException("'" + label + "': " + selected.size() + " annotations are selected; at most " + LcConst.MAX_REGION_OBJECTS + " are supported")
        def server = data.server
        int w = server.width, h = server.height
        if ((long) w * h > LcConst.MAX_REGION_PIXELS)
            throw new IllegalStateException("'" + label + "': the image has " + (long) w * h + " pixels; a selection region is supported up to " + LcConst.MAX_REGION_PIXELS + " (use a smaller image or a file)")
        def mask = paintLabelMask(selected, w, h)
        if (mask == null) throw new IllegalStateException("'" + label + "': the selected annotations cover no pixel of the image")
        def out = new File(tmp, p.name + ".tif")
        ImageWriterTools.writeImage(mask, out.absolutePath)
        return out
    }

    /** The objects painted as a 16-bit label image (object i has label i + 1), or null when none covers a pixel. */
    static BufferedImage paintLabelMask(Collection selected, int w, int h) {
        def painted = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)       // each object painted in the colour of its number (no anti-aliasing: pixel centres decide)
        def g = painted.createGraphics()
        try {
            selected.eachWithIndex { obj, i -> g.setColor(new java.awt.Color(i + 1)); g.fill(obj.ROI.shape) }
        } finally { g.dispose() }
        def mask = new BufferedImage(w, h, BufferedImage.TYPE_USHORT_GRAY)
        def raster = mask.raster
        boolean any = false
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int objectNumber = painted.getRGB(x, y) & 0xFFFFFF
            if (objectNumber != 0) { raster.setSample(x, y, 0, objectNumber); any = true }
        }
        return any ? mask : null
    }

    /** The request inputs: every form value prepared, plus the host-owned results folder as `_job_dir`. */
    Map prepareInputs(Map values, List params, File tmp, String appName, String toolId) {
        def inputs = new LinkedHashMap()
        values.each { name, v -> inputs[name] = prepareInput(params.find { it.name == name }, name as String, v, tmp, appName) }
        inputs["_job_dir"] = newJobDir(appName, toolId).absolutePath
        return inputs
    }

    /** What the tool receives for one value: images are exported (or the file's path is passed), files are checked, the rest passes unchanged. */
    Object prepareInput(Map p, String name, Object v, File tmp, String appName) {
        if (p.type in ["image", "labels"]) return prepareImageInput(p, name, v, tmp, appName)
        if (p.type in ["file", "table", "folder"]) {
            def f = new File(v as String)
            if (!f.exists()) throw new FileNotFoundException("file not found: " + f)
            return f.absolutePath
        }
        return v
    }

    /** An image value becomes a file path: the selection mask, the file as typed, or an exported TIFF (one channel when PickChannel chose one). */
    String prepareImageInput(Map p, String name, Map v, File tmp, String appName) {
        if (v.selection) return selectionMask(p, tmp, appName).absolutePath          // RegionOf: the selected annotations
        boolean oneChannel = p.pick_channel && v.channel != null && v.channel >= 0
        if (v.file && !oneChannel) return existingFile(v.file as String).absolutePath
        def server = v.file ? ImageServers.buildServer(existingFile(v.file as String).absolutePath) : v.source.call()
        if (oneChannel && server.nChannels() > 1)       // PickChannel: only the chosen channel is exported
            server = new TransformedServerBuilder(server).extractChannels(v.channel as int).build()
        def out = new File(tmp, name + ".tif")
        ImageWriterTools.writeImageRegion(server, RegionRequest.createInstance(server), out.absolutePath)
        return out.absolutePath
    }

    /** The file, or FileNotFoundException with its path. */
    static File existingFile(String path) {
        def f = new File(path)
        if (!f.isFile()) throw new FileNotFoundException("file not found: " + f)
        return f
    }

    /** Results go to a new folder under <LC_HOME>/results, where the command line also puts its runs; this folder and the ones before it (by name) are kept, LcConst.RESULTS_KEPT in all. */
    static File newJobDir(String appName, String toolId) {
        def root = new File(LcRegistry.searchPath().first().parentFile, "results")
        def stamp = new java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss").format(new Date())
        def job = new File(root, stamp + "_" + System.nanoTime().toString().takeRight(6) + "_" + appName + "_" + toolId)
        job.mkdirs()
        (root.listFiles({ File f -> f.isDirectory() } as FileFilter) ?: []).sort { it.name }.reverse().drop(LcConst.RESULTS_KEPT).each { it.deleteDir() }
        return job
    }

    /** Cancel pressed: ask the worker to stop the run. */
    void cancel() {
        status.text = "cancelling..."
        worker?.cancel()
    }

    // ---- results
    /** Back on the FX thread after a run: the form is idle again and the outcome is shown according to its type. */
    void finish(String appName, String toolLabel, Map outcome, double seconds) {
        showIdle()
        def type = outcome.responseType
        lastReport = LcJson.pretty(outcome) + "\n\nworker output:\n" + (worker?.stderrText() ?: "")
        if (type == "COMPLETION") {
            finishCompleted(appName, toolLabel, outcome, seconds)
        } else if (type == "CANCELATION") {
            status.text = "cancelled"
        } else if (type == "CRASH") {
            status.text = "the worker stopped: " + outcome.error
            stopWorker(true)
        } else {
            finishFailed(toolLabel, outcome)
        }
        if (!keepWorker.selected) stopWorker(false)
    }

    /** A completed run: status line with the values, results window, then the post-run updates. */
    void finishCompleted(String appName, String toolLabel, Map outcome, double seconds) {
        def results = outcome.outputs?.results ?: []
        def summary = results.findAll { it.type == "values" }.collect { r -> r.values.collect { k, v -> k + "=" + show(v) }.join(", ") }.join("; ")
        status.text = "done in " + String.format("%.1f", seconds) + "s  " + summary
        showResults(appName, toolLabel, results)
        clearAfterRun()
        scheduleChoices(LcConst.CHOICES_AFTER_RUN_MS)          // a run may change what a source tool answers (e.g. a game was prepared)
    }

    /** A "no match" outcome is an answer (shown in a small dialog); any other failure goes to the status line. */
    void finishFailed(String toolLabel, Map outcome) {
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

    // ---- worker lifetime and the Details window
    /** Releases the worker: killed at once when `force`, else closed on a background thread. */
    void stopWorker(boolean force) {
        def w = worker
        worker = null
        if (w == null) return
        if (force) w.kill() else Thread.start { w.close() }
    }

    /** The last run's outcome and the worker's output in a read-only window. */
    void showDetails() {
        def area = new TextArea(lastReport)
        area.editable = false
        def s = new Stage()
        s.title = "LabConstrictor: last run"
        s.scene = new Scene(new BorderPane(area), LcConst.DETAILS_WIDTH, LcConst.DETAILS_HEIGHT)
        s.initOwner(stage)
        s.show()
    }

    // ---- results window: one section per output type
    /** Builds one results window from all outputs of a run. */
    void showResults(String appName, String toolLabel, List results) {
        def box = new VBox(10)
        box.padding = new Insets(10)
        for (r in results) addResult(box, appName, r)
        showResultWindow(box, appName, toolLabel)
    }

    /** One output of the tool becomes its part of the results window (a "message" goes to the form instead). */
    void addResult(VBox box, String appName, Map r) {
        switch (r.type) {
            case "values": box.children.addAll(valuesSection(r)); break
            case "message":
                def text = (r.text as String).replaceAll(/\*\*(.+?)\*\*/, '$1')
                messageLabel.text = (messageLabel.text && messageLabel.visible ? messageLabel.text + "\n\n" : "") + text
                messageLabel.visible = true; messageLabel.managed = true
                break
            case "points":
                box.children.addAll(boldLabel((r.name ?: "points") + " (points)"), new Label(placePoints(appName, r)), tableView(r.path as String))
                break
            case "shapes":
                box.children.addAll(boldLabel((r.name ?: "shapes") + " (outlines)"), wrapped(placeShapes(appName, r)))
                break
            case ["image", "labels"]: box.children.addAll(imageSection(r)); break
            case "table":
                box.children.addAll(boldLabel(r.name ?: "table"), tableView(r.path as String))
                break
            case "affine": box.children.addAll(affineSection(r)); break
            default:
                box.children.addAll(boldLabel((r.name ?: r.type) + " (" + r.type + ")"), new Label((r.path ?: r.toString()) as String))
        }
    }

    /** A name-value grid for a "values" output. */
    List<Node> valuesSection(Map r) {
        def grid = newGrid()
        int row = 0
        r.values.each { k, v -> grid.add(new Label(k.toString()), 0, row); def l = new Label(show(v)); l.wrapText = true; grid.add(l, 1, row++) }
        return [boldLabel(r.name ?: "values"), grid]
    }

    /** Preview, path and an Open in QuPath button for an image or labels output. */
    List<Node> imageSection(Map r) {
        def nodes = []
        def open = new Button("Open in QuPath")
        def path = r.path as String
        try { nodes.add(previewNode(path, r.type == "labels")) } catch (Exception e) { LcLog.warn("no preview for " + path, e); nodes.add(new Label("(no preview: " + e.message + ")")) }
        open.onAction = {
            try { qupath.openImage(qupath.viewer, path, false, false) } catch (Exception e) { LcLog.warn("cannot open " + path, e); status.text = "cannot open: " + e.message }
        }
        def l = new Label(path); l.wrapText = true
        nodes.addAll([boldLabel((r.name ?: r.type) + " (" + r.type + ", axes " + (r.axes ?: "?") + ")"), l, open])
        return nodes
    }

    /** An alignment matrix (matrix_yx) printed with 5 decimals. */
    static List<Node> affineSection(Map r) {
        def m = r.matrix_yx
        def txt = (m instanceof List) ? m.collect { row -> row.collect { String.format("%.5f", it as double) }.join("   ") }.join("\n") : m.toString()
        def l = new Label(txt); l.style = "-fx-font-family: monospace"
        return [boldLabel((r.name ?: "alignment") + ": " + (r.apply_to ?: "") + " relative to " + (r.relative_to ?: "")), l]
    }

    /** Replace(): the next run's results take the place of the last ones in the same window. */
    void showResultWindow(VBox box, String appName, String toolLabel) {
        def key = appName + "/" + toolLabel
        def replacing = currentTool?.outputs?.any { it.replace } && resultWindows[key]?.showing
        def stage2 = replacing ? resultWindows[key] : new Stage()
        stage2.title = appName + ": " + toolLabel
        if (!replacing) stage2.initOwner(stage)
        def sc = new ScrollPane(box)
        sc.fitToWidth = true
        if (replacing) stage2.scene.root = sc else stage2.scene = new Scene(sc, LcConst.RESULTS_WIDTH, LcConst.RESULTS_HEIGHT)
        stage2.show()
        resultWindows[key] = stage2
        lastResultStage = stage2
    }
    Stage lastResultStage       // the window of the last results

    // ---- placing results on the image (points, outlines)
    /** The image open in QuPath when an output's results were found in it (the image parameter was "Current image" for this run); otherwise null. */
    def openImageFor(Map r) {
        def data = qupath?.imageData
        def target = r.apply_to ?: currentTool?.inputs?.find { it.type in ["image", "labels"] }?.name
        def chosen = target ? imageChoiceAtRun[target] : null
        return (data == null || chosen == null || !chosen.startsWith("Current image")) ? null : data
    }

    /** Points go on the image they were found in when that was the image open in QuPath; otherwise only the table is shown. */
    String placePoints(String appName, Map r) {
        def data = openImageFor(r)
        if (data == null)
        {
            LcLog.once("points-not-placed:" + appName + ":" + r.name, "points '" + r.name + "' not placed: they were not found in the image open in QuPath (table only)")
            return "Not placed on an image (the points were not found in the image open in QuPath)."
        }
        def lines = new File(r.path as String).readLines("UTF-8")
        def header = splitCsv(lines[0])
        def yi = header.indexOf("y"), xi = header.indexOf("x")
        def ys = [], xs = []
        lines.drop(1).each { line -> def cells = splitCsv(line); ys << (cells[yi] as double) + 0.5d; xs << (cells[xi] as double) + 0.5d }   // pixel centres
        def name = appName + ":" + r.name
        def hierarchy = data.hierarchy
        def previous = hierarchy.annotationObjects.findAll { it.name == name }
        if (previous && currentTool.outputs.any { it.name == r.name && it.replace }) hierarchy.removeObjects(previous, true)   // Replace()
        def obj = PathObjects.createAnnotationObject(ROIs.createPointsROI(xs as double[], ys as double[], ImagePlane.getDefaultPlane()))
        obj.name = name
        hierarchy.addObject(obj)
        return xs.size() + " point(s) added to the open image as the annotation '" + name + "'."
    }

    /** A label that wraps long text. */
    static Label wrapped(String text) {
        def l = new Label(text)
        l.wrapText = true
        return l
    }

    /** Outlines (GeoJSON Polygon / MultiPolygon, [x, y] with pixel centres at integers) become annotations named "<app>:<output> <label>"
     *  on the image they were found in (when that was the image open in QuPath); holes and parts are kept; numeric properties become
     *  measurements. Replace() removes the previous annotations of this output. */
    String placeShapes(String appName, Map r) {
        def data = openImageFor(r)
        if (data == null)
        {
            LcLog.once("shapes-not-placed:" + appName + ":" + r.name, "outlines '" + r.name + "' not placed: they were not found in the image open in QuPath")
            return "Not placed on an image (the outlines were not found in the image open in QuPath)."
        }
        def collection = new Gson().fromJson(new File(r.path as String).getText("UTF-8"), Map)
        def prefix = appName + ":" + r.name
        def built = shapeAnnotations(collection, prefix)
        def objects = built.objects, holes = built.holes, total = collection.features.size()
        def hierarchy = data.hierarchy
        def replacing = currentTool.outputs.any { it.name == r.name && it.replace }
        if (replacing) {
            def previous = hierarchy.annotationObjects.findAll { it.name?.startsWith(prefix + " ") }
            if (previous) hierarchy.removeObjects(previous, true)       // Replace()
        }
        hierarchy.addObjects(objects)
        def text = objects.size() + " outline(s) added to the open image as annotations named '" + prefix + " <label>'."
        if (total > LcConst.MAX_SHAPES) text += " Showing the first " + LcConst.MAX_SHAPES + " of " + total + "."
        if (holes) text += " " + holes + " outline(s) have holes (kept)."
        return text
    }

    /** One annotation per GeoJSON feature (at most LcConst.MAX_SHAPES), and the number of polygon parts that have holes. */
    Map shapeAnnotations(Map collection, String prefix) {
        def factory = new org.locationtech.jts.geom.GeometryFactory()
        def ring = { List points -> factory.createLinearRing(points.collect { new org.locationtech.jts.geom.Coordinate((it[0] as double) + 0.5d, (it[1] as double) + 0.5d) } as org.locationtech.jts.geom.Coordinate[]) }   // pixel centres
        def objects = [], holes = 0
        for (feature in collection.features) {
            if (objects.size() >= LcConst.MAX_SHAPES) break
            def geometry = feature.geometry
            def parts = geometry.type == "Polygon" ? [geometry.coordinates] : geometry.coordinates
            def polygons = parts.collect { part ->
                if (part.size() > 1) holes++
                factory.createPolygon(ring(part[0] as List), part.drop(1).collect { ring(it as List) } as org.locationtech.jts.geom.LinearRing[])
            }
            def shape = polygons.size() == 1 ? polygons[0] : factory.createMultiPolygon(polygons as org.locationtech.jts.geom.Polygon[])
            def obj = PathObjects.createAnnotationObject(qupath.lib.roi.GeometryTools.geometryToROI(shape, ImagePlane.getDefaultPlane()))
            def label = feature.get("properties")?.get("label")   // .get(): on a map, Groovy's feature.properties and feature["properties"] give the bean properties, not the JSON key
            if (label instanceof Number && (label as double) == Math.floor(label as double)) label = (label as double).longValue()      // Gson reads every number as a double: 1.0 -> 1
            obj.name = prefix + " " + (label != null ? label : objects.size() + 1)
            feature.get("properties")?.each { k, v -> if (v instanceof Number) obj.measurementList.put(k.toString(), v as double) }   // numeric properties become measurements
            objects << obj
        }
        return [objects: objects, holes: holes]
    }

    // ---- previews and tables of results
    /** A small preview of a result image (first plane, first channel, scaled to the window); labels get a colour per label. */
    static Node previewNode(String path, boolean labels) {
        def server = ImageServers.buildServer(path)
        double downsample = Math.max(1d, Math.max(server.width, server.height) / (double) LcConst.PREVIEW_WIDTH)
        def img = server.readRegion(RegionRequest.createInstance(server.path, downsample, 0, 0, server.width, server.height))
        def raster = img.raster
        int w = raster.width, h = raster.height
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) { double v = raster.getSampleDouble(x, y, 0); if (v < lo) lo = v; if (v > hi) hi = v }
        def out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            double v = raster.getSampleDouble(x, y, 0)
            if (labels) {
                out.setRGB(x, y, v == 0 ? 0 : java.awt.Color.HSBtoRGB((float) ((v * LcConst.LABEL_HUE_STEP) % 1d), 0.7f, 0.95f))
            } else {
                int g = hi > lo ? (int) Math.round(255d * (v - lo) / (hi - lo)) : 0
                out.setRGB(x, y, (g << 16) | (g << 8) | g)
            }
        }
        def view = new ImageView(SwingFXUtils.toFXImage(out, null))
        view.preserveRatio = true
        view.smooth = false                                  // pixels stay square when a small result is enlarged
        view.fitWidth = Math.min((double) LcConst.PREVIEW_WIDTH, Math.max(w, (double) LcConst.PREVIEW_MIN_WIDTH))
        return view
    }

    /** A bold heading label. */
    static Label boldLabel(String text) { def l = new Label(text); l.style = "-fx-font-weight: bold"; return l }

    /** The first LcConst.TABLE_ROWS_SHOWN rows of a CSV file as a table, with the row count and the path. */
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
        lines.drop(1).take(LcConst.TABLE_ROWS_SHOWN).each { table.items.add(splitCsv(it)) }
        table.prefHeight = LcConst.TABLE_HEIGHT
        def rows = Math.max(0, lines.size() - 1)
        return new VBox(4, table, new Label(rows + " row(s)" + (rows > LcConst.TABLE_ROWS_SHOWN ? " (first " + LcConst.TABLE_ROWS_SHOWN + " shown; the full table is in " + csvPath + ")" : "") + "  " + csvPath))
    }

    /** One CSV line into cells: commas separate, double quotes group, a doubled quote is one quote (no multi-line cells). */
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
// Opens the dialog unless a test or another script sets lcNoShow / -Dlc.noshow (then it only defines the classes).
def lcShow = {
    def registry = LcRegistry.load()
    def dialog = new LcDialog(QuPathGUI.getInstance(), registry)
    dialog.show()
    return dialog
}
if (!binding.hasVariable("lcNoShow") && !System.getProperty("lc.noshow")) {
    if (Platform.isFxApplicationThread()) lcShow() else Platform.runLater { lcShow() }
}
