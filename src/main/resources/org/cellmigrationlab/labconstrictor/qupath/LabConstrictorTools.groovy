/*
 * LabConstrictor tools for QuPath (prototype).
 *
 * Runs the tools of every installed LabConstrictor app (NucleiSky, CellTracksColab, ...) from QuPath. The form is generated from the
 * tool's declared schema (see https://github.com/CellMigrationLab/LabConstrictor-Tools, docs/PROTOCOL.md); the tool runs in the app's
 * own Python environment through the LabConstrictor worker (JSON lines on stdin/stdout), so QuPath never imports the app's packages.
 *
 * Use it as the extension jar (Extensions > LabConstrictor tools...). The script is several files, joined in the order of parts.txt (this file first; the others hold the
 * compatibility check LcCompat, retention and run records LcRetention, run identity LcRunIdentity, and any file added after them): this file alone does not run, because the classes it
 * uses are in the others. `./build.sh` also writes target/LabConstrictorTools-all.groovy, the whole script in one file, to paste in the Script Editor and run.
 *
 * Sections, in file order: logging (LcLog), JSON (LcJson), image area (LcArea), calibration (LcUnits, LcCal, LcTiff, LcLink), registry and trust checks (LcRegistry), the worker (LcWorker), the dialog (LcDialog:
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

import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    static final long MAX_EXPORT_PIXELS = 100_000_000L   // pixels (width x height of one plane) the host exports to a tool from an image; above this the run is refused (override: system property below)
    static final String MAX_EXPORT_PROPERTY = "lc.qupath.max_export_pixels"   // system property that replaces MAX_EXPORT_PIXELS (the tests use it to trigger the guard on a small image)
    static final long MAX_EXPORT_BYTES = 4L * 1024 * 1024 * 1024   // bytes (pixels x channels x pixel type) the host exports to a tool from an image; above this the run is refused, as Napari does (_export.MAX_EXPORT_BYTES)
    static final String MAX_EXPORT_BYTES_PROPERTY = "lc.qupath.max_export_bytes"   // system property that replaces MAX_EXPORT_BYTES (the tests use it to trigger the guard on a small image)
    static final double FULL_RESOLUTION = 1.0d           // downsample of every export and of every offset for now (the resolution choice is a later step)
    static final int TABLE_ROWS_SHOWN = 100_000          // rows of a result table put in the results window (same as Napari, _results.MAX_TABLE_ROWS); a TableView draws only the rows in view
    static final int LOG_KEPT = 200                      // messages LcLog.recent keeps
    static final String LOG_NAME = "labconstrictor.log"   // the shared log file under <LC_HOME>/logs (labconstrictor_tools.log.LOG_NAME)
    static final long LOG_FILE_BYTES = 1_000_000L        // size of the shared log file before it rotates (log.LOG_FILE_BYTES)
    static final int LOG_FILE_BACKUPS = 5                // rotated files kept next to the current one (log.LOG_FILE_BACKUPS)
    static final int LOG_LEVEL_WIDTH = 7                 // level names are padded to this width, as the Python format does
    static final int RUN_STDERR_CHARS = 4000             // the worker's last output kept in a run record (runs.STDERR_TAIL_CHARS)
    static final int STDERR_TRIM_ABOVE = 20000           // the worker's error text is cut back ...
    static final int STDERR_KEEP = 10000                 // ... to this many characters at the end once it is longer than the line above
    static final int SIGNIFICANT_DIGITS = 6              // digits of a pixel size in the anisotropy note (the "%.6g" of the Napari note)
    static final double SQUARE_PIXEL_TOLERANCE = 1e-3d   // Y and X pixel sizes closer than this (relative) count as square: no warning (same as Napari)
    static final long FORM_INTEGER_LIMIT = 9_007_199_254_740_991L   // an integer parameter accepts +-(2^53 - 1) at most, declared limits or not: the largest whole number a double (JSON in a page, a slider) holds exactly (Tools notebook.MAX_FORM_INTEGER)
    static final double FLOAT_LIMIT = 1e15d              // a float parameter without declared limits accepts +-this (the Napari rule; keeps every realistic calibration or area)
    static final int MODE_OTHERS_WRITE = 02              // unix mode bit: writable by others (registry trust check)
    static final int MODE_STICKY = 01000                 // unix mode bit, in a directory: only the owner of a file may replace it
    static final List<Double> SUPPORTED_PROTOCOLS = [1d] // schema protocol versions this script understands
    static final List<String> PARAMETER_TYPES = ["string", "integer", "float", "boolean", "choice", "image", "labels", "table", "file", "folder"]   // the parameter types of docs/PROTOCOL.md
    static final String REFUSAL_CODE = "host_refusal"    // internal code of an LcRefusal outcome (never shown: the status line shows the sentence alone)
    static final String CODE_SELECTION_MIXED_PLANES = "selection_mixed_planes"      // host-side refusal codes of SPEC_WRONG_SCIENCE 1.4 (conformance.json: host_internal_codes); they go to the log and the outcome's `reason`, the person reads the sentence
    static final String CODE_SELECTION_PLANE_MISMATCH = "selection_plane_mismatch"
    static final String FILE_INPUT_NOTE = "file input: results are in the file's pixel frame"      // conformance.json messages.file_input_note (SPEC 1.3)
    static final String SIZE_MISMATCH_TEMPLATE = "'{name}' is {w} x {h} but '{source}' was exported as {w2} x {h2}; shown at the pixel origin, not aligned"      // messages.size_mismatch (SPEC 1.1.7)
    static final List<Long> NATIVE_CRASH_CODES = [-11L, 139L, 3221225477L, -1073741819L]   // segmentation fault (Linux, macOS) and the Windows access violation, unsigned (Python) and signed (Java)
    static final int EXIT_TOOL_IMPORT_FAILED = 3         // the worker's exit code when the app's tool module cannot be imported ...
    static final String IMPORT_FAILED_MARKER = "cannot import tool module"      // ... and what the worker prints last on stderr then (log.IMPORT_FAILED_MARKER): code 3 alone is not proof, the Windows C runtime also ends a process with 3
    static final List<Integer> NON_PRINTABLE_TYPES = [Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
                                                      Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR].collect { it as int }   // Unicode categories Python's str.isprintable() rejects
    static final int RESULTS_DIR_ATTEMPTS = 20           // tries to find an unused results folder name before giving up (cli.RESULTS_DIR_ATTEMPTS)
    static final int RESULTS_DIR_RANDOM_BYTES = 3        // random part of a results folder name: six hex digits, two runs in one second never collide (cli.RESULTS_DIR_RANDOM_BYTES)
    static final int WORKER_IDLE_SECONDS = 600           // a kept worker that is idle this long is closed (Napari: _workers.IDLE_TIMEOUT_S)
    // timeouts and delays
    static final int EXIT_WAIT_SECONDS = 2               // how long a crash text waits for the worker's exit code
    static final int STREAM_DRAIN_MS = 2000              // how long a crash text waits for the worker's error output to be read to its end
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

// ---------------------------------------------------------------------------------------------------- refusals
/** A run the host refuses before starting any tool (no selection, no image, an area outside the image, ...). The message is the whole sentence the person
 *  reads, the way the Napari and Fiji hosts show it: the status line shows it alone, with no class name and no "failed:". */
class LcRefusal extends IllegalStateException {
    final String code           // a host-side code of conformance.json (host_internal_codes) when the refusal has one, else null
    LcRefusal(String sentence, String code = null) { super(sentence); this.code = code }
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
        shared("WARNING", text, t)
    }

    /** An intended fallback: logged the first time it happens for `key`. */
    static boolean once(String key, String text) {
        boolean first = seen.add(key)
        if (first) { keep(text); LOG.info("LabConstrictor: " + text); shared("INFO", text, null) }
        return first
    }

    private static volatile boolean sharedFailureSaid = false

    /** The shared log file every front-end writes: <LC_HOME>/logs/labconstrictor.log, in the line format of labconstrictor_tools.log
     *  (`2026-01-31 12:00:00.123 WARNING pid=1234 qupath: text`, a stack trace on the lines after it), rotated at LcConst.LOG_FILE_BYTES with LcConst.LOG_FILE_BACKUPS
     *  numbered backups (`.log.1` the newest), so `labconstrictor-tools logs` and `support-bundle` see QuPath runs. Never throws: a log that cannot be written is said once on stderr. */
    static synchronized void shared(String level, String text, Throwable t) {
        try {
            def file = new File(LcRegistry.home(), "logs/" + LcConst.LOG_NAME)
            file.parentFile.mkdirs()
            rotate(file)
            def stamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date())
            def line = stamp + " " + level.padRight(LcConst.LOG_LEVEL_WIDTH) + " pid=" + ProcessHandle.current().pid() + " qupath: " + text.replace("\r", "") + "\n"
            file.append(line + (t != null ? trace(t) : ""), "UTF-8")
        } catch (IOException | SecurityException e) {      // the log itself is broken (disk full, read-only home): there is nowhere else to write
            if (!sharedFailureSaid) {
                sharedFailureSaid = true
                System.err.println("LabConstrictor: cannot write the log file: " + e)      // groovylint-disable-line SystemErrPrint
            }
        }
    }

    /** At LcConst.LOG_FILE_BYTES or more the log becomes `.log.1`, `.log.1` becomes `.log.2`, and so on; the oldest of LcConst.LOG_FILE_BACKUPS is replaced. */
    static void rotate(File file) {
        if (file.length() < LcConst.LOG_FILE_BYTES) return
        for (int i = LcConst.LOG_FILE_BACKUPS - 1; i >= 1; i--) {
            def older = new File(file.parentFile, file.name + "." + i)
            if (older.exists()) Files.move(older.toPath(), new File(file.parentFile, file.name + "." + (i + 1)).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        Files.move(file.toPath(), new File(file.parentFile, file.name + ".1").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    /** The stack trace of `t` as text (for the Details window). */
    static String trace(Throwable t) { def w = new StringWriter(); t.printStackTrace(new PrintWriter(w)); return w.toString() }

    /** Best-effort removal of a temporary folder: a folder that stays behind is reported, not ignored. */
    static void deleteTemp(File dir) {
        if (dir != null && dir.exists() && !dir.deleteDir()) warn("could not delete the temporary folder " + dir)
    }
}

// ---------------------------------------------------------------------------------------------------- JSON (QuPath ships Gson, not groovy-json)
/** One Gson instance each for compact and pretty output; whole numbers come back as Long, the others as Double (see LcDialog.show). */
class LcJson {
    // whole JSON numbers come back as Long and the others as Double, so a choice of integers (Literal[1, 2, 3]) goes back as 2, not 2.0 (which the worker refuses)
    static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().setObjectToNumberStrategy(com.google.gson.ToNumberPolicy.LONG_OR_DOUBLE).create()
    static final Gson PRETTY = new GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting().setObjectToNumberStrategy(com.google.gson.ToNumberPolicy.LONG_OR_DOUBLE).create()
    static Object parseText(String text) { GSON.fromJson(text, Object) }          // Map / List / Long / Double / String / Boolean
    static Object parse(File file) { parseText(file.getText("UTF-8")) }
    static String toJson(Object o) { GSON.toJson(o) }
    static String pretty(Object o) { PRETTY.toJson(o) }
}

// ---------------------------------------------------------------------------------------------------- image area
/** The part of an image a tool receives ("Image area") and what maps its results back: an area is a Map
 *  [kind, x0, y0, width, height, downsample, z, t] in full-resolution slide pixels; `downsample` is always 1.0 for now and is the one field the
 *  later resolution step changes. This Map is the QuPath form of the frame of SPEC_WRONG_SCIENCE 1.1.2 (`axes_offset` = x0, y0 and the plane, `plane` = z, t,
 *  `downsample`, `shape` = width x height; the transform is the identity: a QuPath image has none). A result at (x, y) of the exported image (pixel centres at
 *  integers) lies at the continuous full-resolution coordinate c = x0 + (x + 0.5) * downsample (1.1.3), on the plane (z, t) that was exported (1.1.9). */
class LcArea {
    static final String WHOLE = "Whole image"
    static final String SELECTION = "Selected annotation(s)"
    static final String VIEWPORT = "Current viewport"
    static final List<String> CHOICES = [WHOLE, SELECTION, VIEWPORT]

    /** The export budget in pixels: LcConst.MAX_EXPORT_PIXELS, or the system property when it holds a positive whole number (anything else is logged once and ignored). */
    static long budget() {
        def text = System.getProperty(LcConst.MAX_EXPORT_PROPERTY)
        if (!text) return LcConst.MAX_EXPORT_PIXELS
        try {
            long value = Long.parseLong(text.trim())
            if (value > 0) return value
        } catch (NumberFormatException e) {
            LcLog.once("budget-text:" + text, "system property " + LcConst.MAX_EXPORT_PROPERTY + " = '" + text + "' is not a whole number (" + e.message + ")")
        }
        LcLog.once("budget:" + text, "system property " + LcConst.MAX_EXPORT_PROPERTY + " = '" + text + "' is ignored: the default of " + LcConst.MAX_EXPORT_PIXELS + " pixels is used")
        return LcConst.MAX_EXPORT_PIXELS
    }

    /** The whole image of `server` on plane (z, t): the plane the viewer shows at run start for the image open in QuPath (SPEC 1.2.7: whole-image export uses the
     *  same plane as the viewport and the selection), plane 0 / 0 for a project image or a file (nothing shows it). */
    static Map whole(def server, int z = 0, int t = 0) {
        return [kind: WHOLE, x0: 0, y0: 0, width: server.width, height: server.height, downsample: LcConst.FULL_RESOLUTION, z: z, t: t]
    }

    /** SPEC 1.2.7: every selected annotation must lie on one ImagePlane, and that plane must be the one exported (the viewer's, `z` and `t`). Mixed planes
     *  are refused with selection_mixed_planes, one other plane with selection_plane_mismatch; the host never switches the plane behind the person's back.
     *  `rois` are the ROIs of the annotations that define the crop or the mask. */
    static void checkSelectionPlane(String label, Collection rois, int z, int t) {
        def planes = rois.collect { [it.z as int, it.t as int] }.unique()
        def name = { List zt -> "z=" + zt[0] + " t=" + zt[1] }
        if (planes.size() > 1)
            throw new LcRefusal("'" + label + "': the selected annotations lie on different image planes (" + planes.collect(name).join(", ") + "); select annotations of one plane only", LcConst.CODE_SELECTION_MIXED_PLANES)
        if (planes && planes[0] != [z, t])
            throw new LcRefusal("'" + label + "': the selected annotation(s) lie on the plane " + name(planes[0]) + " but the plane that is exported is " + name([z, t]) +
                    ", the one shown in the viewer; show that plane in the viewer, or select annotations on the plane shown", LcConst.CODE_SELECTION_PLANE_MISMATCH)
    }

    /** The box [minX, minY, maxX, maxY] widened to whole pixels and clamped to the image of `server` (anything with width and height); null when nothing of it is inside. */
    static Map clamped(String kind, List<Double> box, def server, int z, int t) {
        int x0 = Math.max(0, (int) Math.floor(box[0])), y0 = Math.max(0, (int) Math.floor(box[1]))
        int x1 = Math.min(server.width as int, (int) Math.ceil(box[2])), y1 = Math.min(server.height as int, (int) Math.ceil(box[3]))
        return (x1 <= x0 || y1 <= y0) ? null : [kind: kind, x0: x0, y0: y0, width: x1 - x0, height: y1 - y0, downsample: LcConst.FULL_RESOLUTION, z: z, t: t]
    }

    /** The export budget in bytes: LcConst.MAX_EXPORT_BYTES, or the system property when it holds a positive whole number (anything else is logged once and ignored). */
    static long byteBudget() {
        def text = System.getProperty(LcConst.MAX_EXPORT_BYTES_PROPERTY)
        if (!text) return LcConst.MAX_EXPORT_BYTES
        try {
            long value = Long.parseLong(text.trim())
            if (value > 0) return value
        } catch (NumberFormatException e) {
            LcLog.once("bytes-text:" + text, "system property " + LcConst.MAX_EXPORT_BYTES_PROPERTY + " = '" + text + "' is not a whole number (" + e.message + ")")
        }
        LcLog.once("bytes:" + text, "system property " + LcConst.MAX_EXPORT_BYTES_PROPERTY + " = '" + text + "' is ignored: the default of " + LcConst.MAX_EXPORT_BYTES + " bytes is used")
        return LcConst.MAX_EXPORT_BYTES
    }

    static long pixels(Map area) { return (long) area.width * (long) area.height }

    /** The region of the server the export reads. */
    static RegionRequest request(def server, Map area) {
        return RegionRequest.createInstance(server.path, area.downsample as double, area.x0 as int, area.y0 as int, area.width as int, area.height as int, area.z as int, area.t as int)
    }

    /** A result coordinate of the exported image on the slide; null area (the whole image) leaves it as it is. */
    static double slideX(Map area, double x) { return area == null ? x : (area.x0 as double) + x * (area.downsample as double) }
    static double slideY(Map area, double y) { return area == null ? y : (area.y0 as double) + y * (area.downsample as double) }

    /** The plane results are placed on: that of the area, else the default one. */
    static ImagePlane plane(Map area) { return area == null ? ImagePlane.getDefaultPlane() : ImagePlane.getPlane(area.z as int, area.t as int) }

    /** Same part of the slide (kind aside). */
    static boolean same(Map a, Map b) {
        return ["x0", "y0", "width", "height", "z", "t"].every { a[it] == b[it] }
    }

    static String describe(Map area) {
        return area.kind + " (x " + area.x0 + ", y " + area.y0 + ", " + area.width + " x " + area.height + " px)"
    }
}

// ---------------------------------------------------------------------------------------------------- calibration (SPEC_WRONG_SCIENCE section 2)
/** The unit catalog of conformance.json (`wrong_science.calibration`), loaded once; this script keeps no list of units of its own. The file is the one of the installed
 *  labconstrictor-tools: `python -m labconstrictor_tools.conformance --path`, run with the interpreter of a registered app (or the file named by the environment variable
 *  LC_CONFORMANCE, which the tests may set). Matching rule (2.1.4): symbols are exact text (case-sensitive, nothing trimmed), words are matched case-insensitively after
 *  trimming; text in neither list is an unknown unit and gives an unknown calibration. A catalog that cannot be loaded makes every calibration unknown (said once): the
 *  number alone is never evidence. */
class LcUnits {
    static final String CONFORMANCE_ENV = "LC_CONFORMANCE"
    static final String NOT_SQUARE_TEXT = "pixels are not square"                          // conformance messages.pixels_not_square_warning
    static final String NOT_A_LENGTH_TEXT = "unit '{unit}' is not a length"                // messages.unit_not_length_warning
    static final String DAMAGED_TEXT = "unit text '{unit}' is damaged; enter the pixel size"      // messages.unit_damaged_warning
    static final String CALIBRATION_REQUIRED = "'{param}' needs a value: '{of}' has no pixel size. Enter it in um."      // messages.calibration_required
    static final String NOT_CALIBRATED_TOOLTIP = "not calibrated: '{of}' has no pixel size"        // messages.calibration_unknown_tooltip
    static final String NO_CATALOG_TEXT = "the unit catalog of labconstrictor-tools could not be loaded (update the app's labconstrictor-tools); enter the pixel size"
    static final String DAMAGED_PREFIX = "�"                                           // a micro sign that was damaged on its way here
    static final Set<String> NO_UNIT = ["", "pixel", "pixels", "px", "dimensionless", "none"] as Set      // text that says "no physical unit": unknown, and not worth a warning
    static final int PROCESS_SECONDS = 30
    private static List<Map> apps = []          // registry entries whose interpreter can give the conformance file
    private static Map data = null              // the parsed conformance file
    private static Map catalogs = null          // [symbols: text -> um, words: text -> um]

    /** The registered apps whose interpreter may be asked for the conformance file (a call with other apps forgets the loaded catalog only when none was loaded). */
    static synchronized void useApps(Collection entries) {
        apps = entries.findAll { it != null } as List<Map>
    }

    /** Forgets the loaded catalog (tests that change LC_CONFORMANCE or the apps). */
    static synchronized void reset() { data = null; catalogs = null }

    /** The parsed conformance file, or null (logged once) when no source could give it. */
    static synchronized Map conformance() {
        if (data != null) return data
        def candidates = []
        def fromEnv = System.getenv(CONFORMANCE_ENV)
        if (fromEnv) candidates << new File(fromEnv)
        for (entry in apps) {
            def path = pathFrom(entry)
            if (path != null) candidates << new File(path)
            if (path != null) break         // the first app that answers: every app of one machine ships the same file format
        }
        for (file in candidates) {
            try { data = LcJson.parse(file) as Map; return data }
            catch (Exception e) { LcLog.warn("conformance file " + file + " could not be read", e) }      // broad on purpose: a damaged file is the same as none
        }
        LcLog.once("no-conformance", "no conformance file: " + NO_CATALOG_TEXT)
        return null
    }

    /** `python -m labconstrictor_tools.conformance --path` of one app's interpreter, or null. */
    static String pathFrom(Map entry) {
        try {
            def process = new ProcessBuilder([entry.python as String, "-m", "labconstrictor_tools.conformance", "--path"]).redirectErrorStream(false).start()
            def out = process.inputStream.getText("UTF-8").trim()
            if (!process.waitFor(PROCESS_SECONDS, TimeUnit.SECONDS)) { process.destroyForcibly(); return null }
            return process.exitValue() == 0 && out ? out.readLines().last().trim() : null
        } catch (IOException e) {      // the interpreter cannot be started
            LcLog.warn("the conformance file could not be asked from " + entry.python, e)
            return null
        }
    }

    /** The calibration section of the conformance file (null without a catalog). */
    static Map calibrationData() { return conformance()?.wrong_science?.calibration as Map }

    private static synchronized Map catalog() {
        if (catalogs != null) return catalogs
        def section = calibrationData()
        if (section == null) return null
        def symbols = [:], words = [:]
        section.length_units.each { name, entry ->
            double factor = entry.um_per_unit as double
            entry.symbols.each { symbols[it as String] = factor }
            entry.words.each { words[it as String] = factor }
        }
        catalogs = [symbols: symbols, words: words]
        return catalogs
    }

    /** [micrometres per unit, warning] for a unit text. [null, null] is "no unit" (pixel, empty); [null, warning] a unit that is not in the catalog or is damaged. The factor is never guessed. */
    static List unitFactor(Object unit) {
        String text = unit == null ? "" : unit.toString()
        if (text.contains(DAMAGED_PREFIX)) return [null, DAMAGED_TEXT.replace("{unit}", text)]
        def known = catalog()
        if (known == null) return [null, NO_CATALOG_TEXT]
        if (known.symbols.containsKey(text)) return [known.symbols[text], null]       // exact: 'UM', ' um', 'mms' are not symbols
        String word = text.trim().toLowerCase(Locale.ROOT)
        if (known.words.containsKey(word)) return [known.words[word], null]
        return word in NO_UNIT ? [null, null] : [null, NOT_A_LENGTH_TEXT.replace("{unit}", text)]
    }

    /** 72 or 96 dpi (28.35 or 37.80 per cm), within the tolerance of the conformance file: what software writes when nobody calibrated the image. */
    static boolean defaultLooking(double resolution, boolean perInch) {
        def rule = calibrationData()?.default_resolution_dpi
        if (rule == null) return true           // no rule to compare with: a resolution tag alone is not trusted
        def defaults = perInch ? rule.values : rule.per_cm
        return defaults.any { Math.abs(resolution - (it as double)) <= (rule.tolerance_relative as double) * (it as double) }
    }
}

/** The pixel size of one source: `y` and `x` are micrometres per pixel, or null when unknown (`warning` may say why); `raw` keeps what the source said, [[scale, unit], [scale, unit]] for y and x. */
class LcCal {
    final Double y, x
    final List raw
    final String warning
    LcCal(Double y, Double x, List raw, String warning = null) { this.y = y; this.x = x; this.raw = raw; this.warning = warning }

    static final LcCal UNKNOWN = new LcCal(null, null, [[null, null], [null, null]])

    boolean isKnown() { return y != null && x != null }

    /** Y and X differ by at most the tolerance, relative to the larger. */
    boolean isSquare() { return known && Math.abs(y - x) <= LcConst.SQUARE_PIXEL_TOLERANCE * Math.max(Math.abs(y), Math.abs(x)) }

    /** What the scalar pixel-size field takes: the mean of X and Y when they agree within the tolerance, else null (unknown). The field is never filled from one axis. */
    Double getFieldValue() { return known && square ? (y + x) / 2d : null }

    /** The warning to show: why the source is unknown when it is a problem (not just "no calibration"), or that the pixels are not square. */
    String getNote() { return warning ?: (known && !square ? LcUnits.NOT_SQUARE_TEXT : null) }

    /** Each axis is (scale, unit text): scale times the unit's size in micrometres, or unknown. */
    static LcCal calibrate(Object yScale, Object yUnit, Object xScale, Object xUnit) {
        def raw = [[number(yScale), yUnit == null ? null : yUnit.toString()], [number(xScale), xUnit == null ? null : xUnit.toString()]]
        def sizes = []
        for (axis in [[yScale, yUnit], [xScale, xUnit]]) {
            def (factor, warning) = LcUnits.unitFactor(axis[1])
            def n = number(axis[0])
            if (factor == null || n == null || n <= 0d) return new LcCal(null, null, raw, warning as String)
            sizes << n * (factor as double)
        }
        return new LcCal(sizes[0] as Double, sizes[1] as Double, raw)
    }

    static Double number(Object v) {
        if (v == null) return null
        try { double d = (v instanceof Number) ? (v as Number).doubleValue() : Double.parseDouble(v.toString().trim()); return Double.isFinite(d) ? d : null }
        catch (NumberFormatException e) { return null }       // text that is not a number is no number
    }

    /** The pixel size of an image server: its PixelCalibration through the catalog, except for an unedited local TIFF, which says it through its own tags (see ofTiffFile). */
    static LcCal ofServer(def server) {
        def pc = server.pixelCalibration
        def fromServer = calibrate(pc.pixelHeight, pc.pixelHeightUnit, pc.pixelWidth, pc.pixelWidthUnit)
        def file = localTiff(server)
        if (file == null || calibrationEdited(server)) return fromServer      // a calibration the person entered in QuPath is explicit: kept
        try { return ofTiffFile(file) }
        catch (IOException e) { LcLog.warn("TIFF header of " + file + " could not be read (the calibration of the server is used)", e); return fromServer }
    }

    /** The local file behind a server when it is a TIFF (by its header), else null. */
    static File localTiff(def server) {
        try {
            def uri = server.URIs?.find { it.scheme == "file" }
            if (uri == null) return null
            def file = new File(uri)
            LcTiff.tags(file)
            return file
        } catch (IOException | RuntimeException e) {      // not a local file, or not a TIFF: the server's own calibration is used
            return null
        }
    }

    /** The calibration in the server's metadata differs from the one the reader found: somebody entered it in QuPath. */
    static boolean calibrationEdited(def server) {
        try { return server.metadata.pixelCalibration != server.originalMetadata.pixelCalibration }
        catch (RuntimeException e) { LcLog.warn("could not compare the calibration of the server with its original", e); return false }
    }

    /** The pixel size of a file: a TIFF by its tags (OME or ImageJ unit first, resolution tags only when they are not a 72 / 96 dpi default), any other file by its server. */
    static LcCal ofFile(String path) {
        def file = new File(path)
        try { return ofTiffFile(file) } catch (IOException e) { LcLog.warn("not read as a TIFF: " + path + " (" + e.message + ")") }
        def server = ImageServers.buildServer(file.toURI().toString())
        def cal
        try { cal = ofServer(server) } finally { server.close() }
        return cal
    }

    /** A TIFF's own metadata (SPEC 2.1.6): an OME physical size or an ImageJ `unit=` wins over the resolution tags; tags alone count only when ResolutionUnit is INCH or
     *  CENTIMETER and the resolution does not look like a 72 / 96 dpi default; NONE is unknown. Throws IOException for a file that is not a readable TIFF. */
    static LcCal ofTiffFile(File file) {
        def t = LcTiff.tags(file)
        def ome = omePixelSizes(t.description as String)
        if (ome != null) return calibrate(ome[0][0], ome[0][1], ome[1][0], ome[1][1])
        if (t.xres == null) return UNKNOWN
        double perX = t.xres[0] / t.xres[1], sizeX = t.xres[1] / t.xres[0]
        def yres = t.yres ?: t.xres          // a missing YResolution means square
        double perY = yres[0] / yres[1], sizeY = yres[1] / yres[0]
        def unit = imageJUnit(t.description as String)
        if (unit) return calibrate(sizeY, unit, sizeX, unit)       // ImageJ's own unit text names the unit, the tags give the number
        int code = t.unit == null ? 2 : (t.unit as int)             // no ResolutionUnit tag: the TIFF default, INCH
        if (code != 2 && code != 3) return UNKNOWN                  // NONE (or odd): the number has no unit
        String name = code == 2 ? "inch" : "centimeter"
        def raw = [[sizeY, name], [sizeX, name]]
        if (LcUnits.defaultLooking(perX, code == 2) || LcUnits.defaultLooking(perY, code == 2)) return new LcCal(null, null, raw)
        double factor = code == 2 ? 25400d : 10000d
        return new LcCal(sizeY * factor, sizeX * factor, raw)
    }

    /** [[sizeY, unitY], [sizeX, unitX]] of the first <Pixels> element of an OME-XML description, or null without a physical size. A missing PhysicalSizeY means square; a size
     *  without a unit text is unknown to the caller (the number alone is no evidence). */
    static List omePixelSizes(String description) {
        if (description == null || !description.contains("<OME")) return null
        def m = description =~ /<(?:\w+:)?Pixels\b[^>]*>/
        if (!m.find()) return null
        String element = m.group()
        def attr = { String name -> def a = element =~ (/\b/ + name + /\s*=\s*"([^"]*)"/); a.find() ? xmlText(a.group(1)) : null }
        def sx = attr("PhysicalSizeX")
        if (sx == null) return null
        def ux = attr("PhysicalSizeXUnit")
        def sy = attr("PhysicalSizeY")
        return [[sy ?: sx, sy != null ? attr("PhysicalSizeYUnit") : ux], [sx, ux]]
    }

    static String xmlText(String s) {
        return s.replaceAll(/&#x([0-9A-Fa-f]+);/) { all, hex -> new String(Character.toChars(Integer.parseInt(hex as String, 16))) }
                .replaceAll(/&#(\d+);/) { all, dec -> new String(Character.toChars(Integer.parseInt(dec as String))) }
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", '"').replace("&apos;", "'").replace("&amp;", "&")
    }

    /** The `unit=` of an ImageJ description ("ImageJ=1.x" first line), with \uXXXX escapes decoded; null without one. */
    static String imageJUnit(String description) {
        if (description == null || !description.startsWith("ImageJ=")) return null
        def line = description.readLines().find { it.startsWith("unit=") }
        if (line == null) return null
        def text = line.substring(5).replaceAll(/\\u([0-9A-Fa-f]{4})/) { all, hex -> new String(Character.toChars(Integer.parseInt(hex as String, 16))) }
        return text ?: null
    }
}

/** The few tags of the first page of a TIFF (classic or BigTIFF) the calibration needs: ImageDescription, XResolution, YResolution, ResolutionUnit. Read without decoding any pixel. */
class LcTiff {
    static final int TAG_DESCRIPTION = 270, TAG_XRES = 282, TAG_YRES = 283, TAG_UNIT = 296
    static final int MAX_DESCRIPTION_BYTES = 64 * 1024 * 1024
    static final int MAX_ENTRIES = 4096

    /** [description, xres: [numerator, denominator], yres, unit] of the first page; IOException when the file is not a TIFF or its first page cannot be read. */
    static Map tags(File file) {
        def raf = new RandomAccessFile(file, "r")
        try {
            def ch = raf.channel
            def head = read(ch, 0L, 16, ByteOrder.BIG_ENDIAN)
            if (head == null) throw new IOException("not a TIFF (too short)")
            int b0 = head.get(0), b1 = head.get(1)
            ByteOrder order = (b0 == 0x49 && b1 == 0x49) ? ByteOrder.LITTLE_ENDIAN : (b0 == 0x4D && b1 == 0x4D) ? ByteOrder.BIG_ENDIAN : null
            if (order == null) throw new IOException("not a TIFF (no byte order mark)")
            head.order(order)
            int magic = head.getShort(2) & 0xffff
            boolean big = magic == 43
            if (!big && magic != 42) throw new IOException("not a TIFF (bad magic number " + magic + ")")
            long ifd = big ? head.getLong(8) : (head.getInt(4) & 0xffffffffL)
            def countBuf = read(ch, ifd, big ? 8 : 2, order)
            if (countBuf == null) throw new IOException("TIFF: the first page lies outside the file")
            long entries = big ? countBuf.getLong(0) : (countBuf.getShort(0) & 0xffffL)
            if (entries <= 0 || entries > MAX_ENTRIES) throw new IOException("TIFF: odd number of tags in the first page: " + entries)
            int entrySize = big ? 20 : 12, inline = big ? 8 : 4
            def out = [description: null, xres: null, yres: null, unit: null]
            for (long i = 0; i < entries; i++) {
                long at = ifd + (big ? 8 : 2) + i * entrySize
                def e = read(ch, at, entrySize, order)
                if (e == null) throw new IOException("TIFF: a tag lies outside the file")
                int tag = e.getShort(0) & 0xffff, type = e.getShort(2) & 0xffff
                long count = big ? e.getLong(4) : (e.getInt(4) & 0xffffffffL)
                if (!(tag in [TAG_DESCRIPTION, TAG_XRES, TAG_YRES, TAG_UNIT])) continue
                long size = count * typeSize(type)
                long data = size <= inline ? at + (big ? 12 : 8) : (big ? e.getLong(12) : (e.getInt(8) & 0xffffffffL))
                if (tag == TAG_DESCRIPTION && type == 2) {
                    if (size > MAX_DESCRIPTION_BYTES) continue
                    def bytes = read(ch, data, (int) size, order)
                    if (bytes != null) out.description = new String(bytes.array(), 0, (int) size, "UTF-8").replace("\u0000", "")
                } else if ((tag == TAG_XRES || tag == TAG_YRES) && type == 5 && count >= 1) {
                    def r = read(ch, data, 8, order)
                    if (r != null) {
                        long num = r.getInt(0) & 0xffffffffL, den = r.getInt(4) & 0xffffffffL
                        if (num != 0 && den != 0) out[tag == TAG_XRES ? "xres" : "yres"] = [num as double, den as double]
                    }
                } else if (tag == TAG_UNIT && (type == 3 || type == 4) && count >= 1) {
                    def r = read(ch, data, type == 3 ? 2 : 4, order)
                    if (r != null) out.unit = type == 3 ? (r.getShort(0) & 0xffff) : r.getInt(0)
                }
            }
            return out
        } finally { raf.close() }
    }

    static int typeSize(int type) { return [1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 6: 1, 7: 1, 8: 2, 9: 4, 10: 8, 11: 4, 12: 8, 16: 8, 17: 8, 18: 8].get(type, 1) }

    /** `length` bytes at `position`, or null when they are not all in the file. */
    static ByteBuffer read(def channel, long position, int length, ByteOrder order) {
        if (position < 0 || position + length > channel.size()) return null
        def buf = ByteBuffer.allocate(length)
        while (buf.hasRemaining()) { if (channel.read(buf, position + buf.position()) < 0) return null }
        buf.flip()
        buf.order(order)
        return buf
    }
}

/** The state of a linked pixel-size field (SPEC 2.2) and the `_calibration` entry of a request (2.3.4). A `PixelSizeOf` field is unset, auto(source, value) or user(value).
 *  The state is a flag set by the person's edit events (userEdit), never inferred by comparing the field with the last automatic value, and a programmatic set
 *  (sourceChanged) never makes it user. The value a run sends is read from here, not from the box. */
class LcLink {
    static final String UNSET = "unset", AUTO = "auto", USER = "user"
    static final String CANONICAL_UNIT = "um"       // micrometres per pixel: the contract of PixelSizeOf
    final String param, of           // the manifest parameter and the image parameter it describes
    String state = UNSET
    LcCal cal                        // the source the AUTO value came from
    String source                    // "image" | "file" (where `cal` came from)
    Double user
    String invalid                   // text the person typed that is not a number (state USER, no value): a run refuses it by name

    LcLink(String param, String of) { this.param = param; this.of = of }

    /** The image, the file or the project image changed (or the field is derived again). True when the field's value may have changed. A user value stays; otherwise the field
     *  takes the new source's value, or is cleared when the source is unknown. */
    boolean sourceChanged(LcCal derived, String from) {
        if (state == USER) return false
        if (derived != null && derived.fieldValue != null) { state = AUTO; cal = derived; source = from }
        else { state = UNSET; cal = null; source = null }
        return true
    }

    /** The person typed or pasted a value. An empty text is the person asking for the image's calibration again: unset, and the caller derives it from the current source. */
    void userEdit(Double value) {
        invalid = null
        if (value == null) { state = UNSET; user = null; cal = null; source = null }
        else { state = USER; user = value }       // 0, a negative or a non-finite number is the person's too; a run refuses it by name
    }

    Double getValue() {
        if (state == USER) return user
        return state == AUTO && cal != null ? cal.fieldValue : null
    }

    String getOrigin() { return state == USER ? USER : (cal != null ? source : null) }

    /** The `_calibration` entry of this field (2.3.4), or null while it has no value. Phase 1: x and y only. */
    Map entry() {
        Double v = value
        if (v == null) return null
        def axis = { double number, String from, List raw ->
            def a = [value: number, unit: CANONICAL_UNIT, source: from]
            if (raw != null && (raw[0] != null || raw[1] != null)) a.raw = [scale: raw[0], unit: raw[1]]
            a
        }
        def axes
        if (state == USER || cal == null || !cal.known) axes = [x: axis(v, USER, null), y: axis(v, USER, null)]
        else axes = [x: axis(cal.x, source, cal.raw[1] as List), y: axis(cal.y, source, cal.raw[0] as List)]
        return [of: of, source: origin, axes: axes]
    }

    String tooltip() {
        Double v = value
        if (state == USER && v != null) return String.format(Locale.ROOT, "%s um per pixel, entered by you: it overrides the calibration of '%s'", LcLink.plain(v), of)
        if (state == AUTO && cal != null && v != null) return LcLink.plain(v) + " um per pixel, from the calibration of " + (source == "file" ? "the file" : "'" + of + "'")
        return LcUnits.NOT_CALIBRATED_TOOLTIP.replace("{of}", of)
    }

    String requiredMessage() { return LcUnits.CALIBRATION_REQUIRED.replace("{param}", param).replace("{of}", of) }

    /** A number as short as it can be written exactly (0.25, 1, 0.5002), never 1.0E-4. */
    static String plain(double v) { return new BigDecimal(Double.toString(v)).stripTrailingZeros().toPlainString() }
}

// ---------------------------------------------------------------------------------------------------- registry and trust checks
/** Finds the registered LabConstrictor apps and refuses entries that fail the trust checks in load(). */
class LcRegistry {
    /** Variables removed from the worker's environment so the app's own Python is not mixed with QuPath's or the user's. */
    static final List<String> SCRUBBED_ENV = ["PYTHONHOME", "VIRTUAL_ENV", "CONDA_PREFIX", "QT_PLUGIN_PATH", "PYTHONPATH"]

    /** The LabConstrictor home: $LC_HOME, else ~/.labconstrictor (registry, results, run records and the shared log live under it). */
    static File home() {
        def env = System.getenv()
        return env.LC_HOME ? new File(env.LC_HOME) : new File(System.getProperty("user.home"), ".labconstrictor")
    }

    /** Folders searched for app registry files, in priority order: $LC_HOME/apps (default ~/.labconstrictor/apps), $LC_APPS_PATH, then the machine-wide folder. */
    static List<File> searchPath() {
        def env = System.getenv()
        def dirs = [new File(home(), "apps")]
        if (env.LC_APPS_PATH) env.LC_APPS_PATH.split(File.pathSeparator).findAll { it }.each { dirs << new File(it) }
        def os = System.getProperty("os.name").toLowerCase()
        if (os.contains("win")) dirs << new File(env.ProgramData ?: "C:\\ProgramData", "LabConstrictor\\apps")
        else if (os.contains("mac")) dirs << new File("/Library/Application Support/LabConstrictor/apps")
        else dirs << new File("/etc/labconstrictor/apps")
        return dirs
    }

    /** Identifiers that become file names (app, tool and parameter names): the same plain-name rule as the Python registry (and the Fiji script). */
    static boolean plainName(def text) {
        return text instanceof String && text && text != "." && text != ".." && !text.any { it in ["/", "\\", "\0"] } && text == text.trim()
    }

    /** A Python identifier: parameter names must pass this before they become file names or form rows. */
    static boolean identifier(def text) { return text instanceof String && (text ==~ /[A-Za-z_][A-Za-z0-9_]*/) }

    /** A tool id from the schema (it becomes part of the `lc:<id>` task name and of result folder names). */
    static boolean toolId(def text) { return text instanceof String && (text ==~ /[A-Za-z0-9_][A-Za-z0-9_.-]*/) }

    /** False on Windows, where the owner / mode checks of the trust rules cannot be made. */
    static boolean isPosixHost() { return !System.getProperty("os.name").toLowerCase().contains("win") }

    /** Unix permission bits of `path`, or null where the file system has no such notion (logged: the permission checks then do not apply to it). */
    static Integer unixMode(java.nio.file.Path path) {
        try { return Files.getAttribute(path, "unix:mode") as Integer }
        catch (UnsupportedOperationException | IllegalArgumentException problem) {
            LcLog.warn("no unix permission bits for " + path + ": the permission checks do not apply to it", problem)
            return null
        }
    }

    /** Writable by everybody (a directory with the sticky bit, like /tmp, only lets owners replace their own files). */
    static boolean worldWritable(File file, boolean directory) {
        def path = file.toPath()
        def mode = unixMode(path)
        if (mode == null) return Files.getPosixFilePermissions(path).contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE)
        return (mode & LcConst.MODE_OTHERS_WRITE) != 0 && !(directory && (mode & LcConst.MODE_STICKY) != 0)
    }

    /** Ownership and permission policy for a file read to decide what to start (same as the Python registry's _file_reason). */
    static String fileReason(File file, boolean userDir, String what) {
        def path = file.toPath()
        def owner = Files.getOwner(path).name
        // per-user entries must be ours; entries in shared folders (LC_APPS_PATH, /etc) may also belong to root (the administrator)
        if (owner != System.getProperty("user.name") && !(owner == "root" && !userDir))
            return what + " is not owned by the current user" + (userDir ? "" : " or root")
        def permissions = Files.getPosixFilePermissions(path)
        if (permissions.contains(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE) || permissions.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE))
            return what + " is writable by other users"
        return null
    }

    /** The files and folders whose replacement would run code as this user at the next start: [file, is a folder, name]. */
    private static List executableParts(java.nio.file.Path python, java.nio.file.Path prefix) {
        return [[python.toFile(), false, "interpreter"], [python.parent.toFile(), true, "interpreter folder"], [prefix.toFile(), true, "install prefix"]]
    }

    /** Owner and permissions of the entry file, the schema file next to it, and the interpreter with its folders; null when all pass. */
    private static String permissionReason(File entryFile, Map entry, boolean userDir, java.nio.file.Path python, java.nio.file.Path prefix) {
        def reason = fileReason(entryFile, userDir, "entry file")
        if (reason) return reason
        def schemaFile = new File(entry.schema_path as String).absoluteFile
        if (schemaFile.parentFile.toPath().normalize() != entryFile.absoluteFile.parentFile.toPath().normalize())
            return "schema file " + entry.schema_path + " is not in the same folder as the entry"
        if (schemaFile.exists()) {                                       // a missing schema is reported later, with its own message
            reason = fileReason(schemaFile, userDir, "schema file")
            if (reason) return reason
        }
        for (item in executableParts(python, prefix)) {
            if (item[0].exists() && worldWritable(item[0] as File, item[1] as boolean)) return item[2] + " " + item[0] + " is writable by everybody"
        }
        return null
    }

    /** Why an entry must not be used to start a process, or null. Fails closed: a check that cannot be made is a reason (same rules as the Fiji script). */
    static String untrustedReason(File entryFile, Map entry, boolean userDir) {
        try {
            // lexical containment (symlinks inside the prefix are normal: a venv's python links to the base interpreter)
            def python = new File(entry.python as String).absoluteFile.toPath().normalize(), prefix = new File(entry.prefix as String).absoluteFile.toPath().normalize()
            if (!python.startsWith(prefix)) return "interpreter " + entry.python + " is not inside the install prefix " + entry.prefix
            if (prefix.parent == null) return "the install prefix " + prefix + " is a filesystem root"
            if (!isPosixHost()) return null                              // Windows: no POSIX ownership/mode to check (known, not an error)
            try { Files.getPosixFilePermissions(entryFile.toPath()) }
            catch (UnsupportedOperationException problem) {              // a file system without POSIX permissions (known): the entry cannot be checked
                LcLog.warn("the registry entry " + entryFile + " is not checked for permissions: its file system has no POSIX permissions", problem)
                return null
            }
            return permissionReason(entryFile, entry, userDir, python, prefix)
        } catch (Exception problem) {      // broad on purpose: a check that cannot be made refuses the entry (fails closed)
            LcLog.warn("cannot verify the registry entry " + entryFile, problem)
            return "cannot verify the registry entry permissions (" + problem + ")"
        }
    }

    /** The registry entry is untrusted JSON: check its shape before any field is used (same rules as the Python registry's _validated_entry). */
    static String entryProblem(def entry) {
        if (!(entry instanceof Map)) return "the entry is not a JSON object"
        for (key in ["name", "python", "prefix", "module", "schema_path"])
            if (!(entry[key] instanceof String) || !entry[key]) return "field '" + key + "' must be a non-empty string"
        if (!plainName(entry.name)) return "invalid app name '" + entry.name + "': it must be a plain name without path separators"
        if (entry.pythonpath != null && !(entry.pythonpath instanceof List && entry.pythonpath.every { it instanceof String }))
            return "field 'pythonpath' must be a list of strings"
        return (entry.runtime_path != null && !(entry.runtime_path instanceof String)) ? "field 'runtime_path' must be a string" : null
    }

    /** The cached schema is untrusted JSON too: shape, plus the names that later become file names. */
    static String schemaProblem(def schema) {
        if (!(schema instanceof Map)) return "schema is not a JSON object"
        if (!(schema.protocol instanceof Number) || !LcConst.SUPPORTED_PROTOCOLS.any { it == (schema.protocol as double) })
            return "schema protocol " + schema.protocol + " is not supported (this host speaks " + LcConst.SUPPORTED_PROTOCOLS.collect { it as long } + ")"      // the Tools wording (registry._validated_schema)
        if (!(schema.tools instanceof List)) return "schema has no list of tools"
        for (tool in schema.tools) {
            if (!(tool instanceof Map) || !(tool.label instanceof String) || !toolId(tool.id) || !(tool.inputs instanceof List) || !(tool.outputs instanceof List))
                return "a tool has an unexpected structure (needs a plain id, a text label, inputs and outputs lists)"
            for (p in tool.inputs) {
                if (!(p instanceof Map) || !identifier(p.name) || !(p.label instanceof String))
                    return "tool '" + tool.id + "' has a parameter without an identifier name and a text label"
            }
        }
        return null
    }

    /** One registry file: [app: [entry, schema]] when it passes every check, else [problem: "name: reason"]; a file that cannot be read is a problem too. */
    private static Map loadFile(File file, boolean userDir) {
        def entry = LcJson.parse(file)
        def malformed = entryProblem(entry)
        if (malformed) return [problem: file.name + ": " + malformed]
        if (!new File(entry.python as String).exists())
            return [problem: entry.name + ": not available on this machine (interpreter " + entry.python + " is missing)", name: entry.name]
        def reason = untrustedReason(file, entry as Map, userDir)
        if (reason) return [problem: entry.name + ": ignored: " + reason, name: entry.name]
        def schema = LcJson.parse(new File(entry.schema_path as String))
        def badSchema = schemaProblem(schema)
        return badSchema ? [problem: file.name + ": " + badSchema] : [app: [entry: entry, schema: schema], name: entry.name]
    }

    /** @return [apps: LinkedHashMap name -> [entry: Map, schema: Map], problems: List of "name: reason"] */
    static Map load() {
        def apps = new LinkedHashMap(), problems = [], skip = [] as Set
        searchPath().eachWithIndex { File dir, int index ->
            if (!dir.isDirectory()) return
            def files = (dir.listFiles({ File f -> f.name.endsWith(".json") && !f.name.endsWith(".schema.json") } as FileFilter) ?: []).sort { it.name }
            for (file in files) {
                def name = file.name.replaceAll(/\.json$/, "")
                if (apps.containsKey(name) || skip.contains(name)) continue // the first folder in the search order wins
                try {
                    def found = loadFile(file, index == 0)
                    if (found.problem) { problems << found.problem; skip << (found.name ?: name); continue }
                    if (apps.containsKey(found.name) || skip.contains(found.name)) continue    // the file name differs from the app name it claims: priority stays with the earlier folder
                    apps[found.name] = found.app
                } catch (Exception e) {     // broad on purpose: any broken registry entry (I/O, JSON) is one listed problem, never a failed start
                    LcLog.warn("registry entry '" + name + "' skipped", e)
                    problems << (file.name + ": " + e.message)
                }
            }
        }
        return [apps: new LinkedHashMap(apps.sort { it.key }), problems: problems]      // sorted by name, as Napari and the command line list them
    }
}

// ---------------------------------------------------------------------------------------------------- the worker
/** One Python worker process of an app: JSON lines on stdin/stdout, one reader thread per stream, one result queue per running task. */
class LcWorker {
    Process proc
    final Map<String, BlockingQueue<Map>> tasks = new java.util.concurrent.ConcurrentHashMap<>()
    final StringBuffer stderrTail = new StringBuffer()
    volatile boolean closed = false
    Thread stderrReader                                 // reads the worker's error output; a crash text waits for it to finish so the worker's last words are not missed
    volatile boolean killedAfterCancel = false          // the person pressed Cancel and the tool did not stop: the worker was ended by the host, not by a fault
    volatile boolean cancelRequested = false            // the person pressed Cancel during the run in progress (Tools Task.cancel_requested): a crash that follows is recorded as cancelled, not failed
    private int counter = 0

    LcWorker(Map entry) {
        def command = [entry.python as String, "-m", "labconstrictor_tools", "serve", "--module", entry.module as String]
        def pb = new ProcessBuilder(command)
        LcPython.applyEnvironment(pb, entry)
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
        stderrReader = Thread.start("lc-stderr") {
            try {
                proc.errorStream.withReader("UTF-8") { r -> char[] buf = new char[2048]; int n; while ((n = r.read(buf)) > 0) { stderrTail.append(buf, 0, n); if (stderrTail.length() > LcConst.STDERR_TRIM_ABOVE) stderrTail.delete(0, stderrTail.length() - LcConst.STDERR_KEEP) } }
            } catch (IOException e) {      // same as above, for the error stream
                if (!closed) LcLog.warn("lost the worker's error stream", e)
            }
        }
    }

    /** The worker's last error output (trimmed to the end when it grows too long, see LcConst.STDERR_*). */
    String stderrText() { stderrTail.toString() }

    /** The likely cause of a worker that died on its own: the sentences of labconstrictor_tools.log.hint_for_exit, rule for rule (a missing package first, then the exit
     *  code; Windows reports the access violation unsigned in Python and signed in Java, so both forms are listed). A worker with no known cause gets a plain sentence. */
    static String hintForExit(Integer code, String stderrTail) {
        def text = stderrTail ?: ""
        if (text.contains("ModuleNotFoundError") || text.contains("ImportError")) return "a Python package is missing or broken in the app's environment (see the traceback above)"
        if (code in [-9, 137]) return "the worker was killed (out of memory? the OS OOM killer ends big image jobs this way)"
        if ((code as Long) in LcConst.NATIVE_CRASH_CODES) return "the worker crashed natively (segmentation fault in a compiled library)"
        return (code == LcConst.EXIT_TOOL_IMPORT_FAILED && text.contains(LcConst.IMPORT_FAILED_MARKER)) ? "the app's tool module failed to import (see the traceback above)" : "the worker process stopped unexpectedly"
    }

    /** What to tell the person when the worker ends without answering: the likely cause (hintForExit), the exit code, then the worker's own last words. */
    String crashText() {
        Integer code = null
        try {
            if (proc.waitFor(LcConst.EXIT_WAIT_SECONDS, TimeUnit.SECONDS)) code = proc.exitValue()
            if (code != null && Thread.currentThread() != stderrReader) stderrReader?.join(LcConst.STREAM_DRAIN_MS)      // the process is gone: its last output is still being read
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); LcLog.warn("interrupted while waiting for the worker's exit code", e) }
        def tail = stderrText().takeRight(LcConst.CRASH_TAIL_CHARS).trim()
        return hintForExit(code, tail) + (code != null ? " (exit code " + code + ")" : "") + (tail ? ". " + tail : "")
    }

    /** Sends one EXECUTE and blocks until its terminal message; `onUpdate` gets (message, current, maximum). */
    Map run(String toolId, Map inputs, Closure onUpdate) {
        def id = "lcq-" + (++counter) + "-" + System.nanoTime()
        cancelRequested = false
        def queue = new LinkedBlockingQueue<Map>()
        tasks.put(id, queue)
        try {
            send([task: id, requestType: "EXECUTE", script: "lc:" + toolId, inputs: inputs])
            while (true) {
                def msg = queue.poll(LcConst.POLL_SECONDS, TimeUnit.SECONDS)
                if (msg == null) {
                    if (!proc.isAlive()) return endOfRun([responseType: "CRASH", error: crashText()])
                    continue
                }
                def type = msg.responseType
                if (type == "UPDATE") onUpdate?.call(msg.message, msg.current, msg.maximum)
                else if (type in ["COMPLETION", "FAILURE", "CANCELATION", "CRASH"]) return endOfRun(msg)
            }
        } finally {
            tasks.remove(id)
            currentTask = null
        }
    }
    volatile String currentTask = null      // id of the run in progress (cancel targets it)

    /** The terminal message of a run; a worker that ended after Cancel was pressed is a cancellation (the person asked for it), never a crash text that reads like out-of-memory
     *  (the crash text stays in `error`, for the run record). `cancelRequested` says whether Cancel was pressed before the end: the run record and the done markers read it. */
    private Map endOfRun(Map msg) {
        Map end = (msg.responseType == "CRASH" && killedAfterCancel) ? [responseType: "CANCELATION", workerStopped: true, error: msg.error] : msg
        end.cancelRequested = cancelRequested
        return end
    }

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
        cancelRequested = true
        try { send([task: id, requestType: "CANCEL"]) } catch (IOException e) { LcLog.warn("could not send the cancel request (the worker is killed after " + graceSeconds + " s)", e) }
        Thread.start("lc-cancel") {
            Thread.sleep(graceSeconds * 1000L)
            if (currentTask == id) { killedAfterCancel = true; kill() }
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
    /** A whole JSON number is a Long, a decimal one a Double: whole Doubles (3.0) are shown without ".0" too. */
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
    int idleSeconds = LcConst.WORKER_IDLE_SECONDS      // a kept worker that was not used for this long is closed (Napari keeps it for 10 minutes)
    PauseTransition idleTimer = new PauseTransition(Duration.seconds(LcConst.WORKER_IDLE_SECONDS))
    Label messageLabel = new Label()              // message results of the last run
    Map<String, Stage> resultWindows = [:]        // app/tool -> its last results window (Replace reuses it)
    Map<String, String> imageChoiceAtRun = [:]    // image parameter -> what was chosen when the run started
    Map<String, Node> wrappers = [:]             // the node placed in the form for each parameter (enabled_when disables this one)
    String lastCopied                              // the text the last Copy as command put on the clipboard (for tests)
    Map<String, CheckBox> selectionBoxes = [:]       // RegionOf: the 'use the selection' box of a region parameter
    Map<String, ComboBox<String>> imageBoxes = [:]
    Map<String, ComboBox<String>> areaBoxes = [:]     // Image area: the area chooser of each image / labels parameter
    volatile Map<String, Map> areasAtRun = [:]        // image parameter -> its area in the run in progress (only parameters with an area other than the whole image)
    volatile Map<String, Integer> planeAtRun = planeOf(0, 0)    // the plane the viewer showed when the run started: what the image open in QuPath is exported on and its results are placed on (SPEC 1.1.9, 1.2.7)
    volatile Map<String, Map> framesAtRun = [:]        // image parameter -> [width, height] of what the tool received (SPEC 1.1.7: a result that says ApplyTo and has another size is said)
    List<String> resultNotes = []                      // sentences about the results of the last run that go to the status line (e.g. size_mismatch), said once
    Map<String, String> imageOf = [:]            // pixel-size parameter -> image parameter
    Map<String, LcLink> links = [:]              // pixel-size parameter -> the state of its field (unset / auto / user), a flag set by the person's edits (SPEC 2.2)
    volatile Map<String, Map> calibrationAtRun = [:]     // pixel-size parameter -> its `_calibration` entry, frozen when the request was read (SPEC 2.3.4)
    volatile LcRun runSnapshot                           // the run in progress, or the last one: frozen when Run was pressed, before any worker call (SPEC 4.1); export, the record and the placement of results read it
    Map<String, Closure> formSources = [:]               // the image choices of the form (the Map the image choosers share); kept in step with the image open in QuPath
    List imageDataListeners = []                 // listeners on QuPath's image property (removed when the window closes)
    boolean programmatic = false                 // true while the host writes a pixel-size field (such a write is never a person's edit)
    Map<String, TextField> fileFields = [:]      // image parameter -> the field for a file path
    LcWorker worker
    String workerApp
    volatile boolean running = false
    String lastReport = ""
    Map currentTool

    LcDialog(QuPathGUI qupath, Map registry) {
        this.qupath = qupath
        this.apps = registry.apps
        this.problems = registry.problems
        LcUnits.useApps(apps.values().collect { it.entry })      // the unit catalog comes from the labconstrictor-tools of a registered app
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
        stage.onHidden = { if (running) worker?.cancelRequested = true; stopWorker(false); imageDataListeners.each { qupath?.imageDataProperty()?.removeListener(it as javafx.beans.value.ChangeListener) } }   // also when closed from code
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
        idleTimer.onFinished = { closeIdleWorker() }
        def follow = { o, a, b -> LcRun.refreshChoosers(this) } as javafx.beans.value.ChangeListener      // the open image changed: "Current image: ..." must name the image that is open now
        qupath?.imageDataProperty()?.addListener(follow)
        imageDataListeners << follow
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
        toolBox.items.setAll(app ? toolNames(app.schema.tools).keySet() : [])
        if (!toolBox.items.isEmpty()) toolBox.value = toolBox.items.first()
    }

    /** [name shown in the chooser: tool]: the label, or `label (id)` when two tools share a label (never pick the wrong one; the Napari rule). */
    static Map<String, Map> toolNames(List tools) {
        def labels = tools.collect { it.label }
        def names = new LinkedHashMap<String, Map>()
        tools.each { names[labels.count(it.label) == 1 ? it.label as String : it.label + " (" + it.id + ")"] = it }
        return names
    }

    /** The first parameter of a tool whose type this host does not know, as a sentence; null when every type is supported. */
    static String unsupportedParameter(Map tool) {
        def p = tool.inputs.find { !(it.type in LcConst.PARAMETER_TYPES) }
        return p == null ? null : "parameter '" + p.name + "' has the unknown type '" + p.type + "'"
    }

    /** A new tool was chosen: forget the old form's controls and build the new form. */
    void onTool() {
        def app = apps[appBox.value]
        currentTool = app ? toolNames(app.schema.tools)[toolBox.value] : null
        formBox.children.clear()
        getters.clear(); setters.clear(); controls.clear(); wrappers.clear(); imageBoxes.clear(); areaBoxes.clear(); imageOf.clear(); selectionBoxes.clear(); links.clear(); fileFields.clear()
        checks.clear(); choiceBoxes.clear(); choiceSeq.clear(); channelBoxes.clear()
        description.text = currentTool?.description ?: ""
        if (currentTool == null) return
        def unsupported = unsupportedParameter(currentTool)
        if (!running) runButton.disable = unsupported != null
        if (unsupported != null) {      // that tool only (the others of the app stay usable), and no guessed form: the Napari rule
            LcLog.warn("cannot build the form for " + currentTool.id + ": " + unsupported)
            status.text = "this tool cannot be shown: " + unsupported
            return
        }
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

    /** ClearAfterRun parameters of every type go back to their default (a text to ""; an optional one without default to unset) after a successful run (the Napari rule). */
    void clearAfterRun() {
        currentTool.inputs.findAll { it.clear_after_run }.each { p ->
            checks[p.name]?.selected = false
            choiceBoxes[p.name]?.value = ""
            if (p.pixel_size_of && links[p.name] != null) { links[p.name].userEdit(null); fillPixelSize(p); return }      // a linked pixel size starts again from its image, never from the declared default
            def start = p.default != null ? p.default : (p.type == "string" ? "" : null)
            if (start != null && p.type != "image" && p.type != "labels") setters[p.name](start)
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
        formSources = sources
        def ordered = presentationOrder(inputs)
        def shown = ordered.findAll { !it.advanced }, advanced = ordered.findAll { it.advanced }
        addSections(shown, sources)
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

    /** Inputs in the order the form shows them: the parameters of a `group` together (where the group first appears), `advanced` ones after all the others.
     *  Without group/advanced hints the order is unchanged. The same rule as the Napari form (presentation_order) and the Fiji dialog (presentationOrder). */
    static List presentationOrder(List inputs) {
        if (!inputs.any { it.group || it.advanced }) return inputs
        def first = [:]                                           // [advanced?, group] -> index where the group first appears
        inputs.eachWithIndex { p, i -> if (p.group) first.putIfAbsent([p.advanced ? 1 : 0, p.group], i) }
        def keyOf = { p, i -> [p.advanced ? 1 : 0, p.group ? first[[p.advanced ? 1 : 0, p.group]] : i, i] }
        def keyed = inputs.withIndex().collect { p, i -> [p, keyOf(p, i)] }
        return keyed.sort(false) { a, b -> a[1][0] <=> b[1][0] ?: a[1][1] <=> b[1][1] ?: a[1][2] <=> b[1][2] }.collect { it[0] }
    }

    /** The ordered, non-advanced inputs as form sections: consecutive normal parameters share a grid, a Collapsed group is a folded section where it first appears. */
    void addSections(List shown, Map sources) {
        def run = []
        def flush = {
            if (run) { def grid = newGrid(); addRows(grid, run, sources); formBox.children.add(grid); run = [] }
        }
        def i = 0
        while (i < shown.size()) {
            def p = shown[i]
            if (!p.group_collapsed) { run << p; i++; continue }
            flush()
            def members = shown.drop(i).takeWhile { it.group_collapsed && it.group == p.group }
            def foldedGrid = newGrid()
            addRows(foldedGrid, members.collect { it + [group: null] }, sources)
            def section = new TitledPane(p.group as String, foldedGrid)
            section.expanded = false
            formBox.children.add(section)
            i += members.size()
        }
        flush()
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
            case "float": built = p.pixel_size_of ? pixelSizeControl(p) : floatControl(p); break
            case "image":
            case "labels": built = imageControl(p, sources); break
            case "file":
            case "table":
            case "folder": built = fileControl(p); break
            default: built = stringControl(p)   // string
        }
        Node node = built.node
        Closure get = built.get, set = built.set
        if (p.nullable && !p.region_of && !p.pixel_size_of) {      // optional without a default: unticked = the tool receives None (a region has its own 'use the selection' box; a linked pixel size has its own state: unset sends null)
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

    /** A spinner (optionally with a slider) of whole numbers; with no declared limits it accepts +-LcConst.FORM_INTEGER_LIMIT, and a declared bound beyond that is cut to it.
     *  The text in the box is the value (LcNumbers): a number beyond a limit or text that is not a whole number is refused (LcNumberError), never put at the nearest limit. */
    Map integerControl(Map p) {
        long lo = p.minimum != null ? Math.max((p.minimum as Number).longValue(), -LcConst.FORM_INTEGER_LIMIT) : -LcConst.FORM_INTEGER_LIMIT      // Number.longValue() saturates, so a huge bound is cut, never wrapped
        long hi = p.maximum != null ? Math.min((p.maximum as Number).longValue(), LcConst.FORM_INTEGER_LIMIT) : LcConst.FORM_INTEGER_LIMIT
        long initial = p.default != null ? (p.default as Number).longValue() : Math.max(lo, Math.min(hi, 0L))
        String label = p.label as String
        LcIntegerFactory factory = new LcIntegerFactory(label, lo, hi, initial)
        def spinner = new Spinner<Long>(factory)
        spinner.editable = true
        followValue(spinner)
        commitOnFocusLost(spinner, label)
        controls[p.name] = spinner
        Node node = spinner
        if (wantsSlider(p)) node = sliderBeside(spinner, lo, hi, initial) { double b -> spinner.valueFactory.value = Math.round(b) }
        return [node: node, get: { LcNumbers.integer(label, spinner.editor.text, lo, hi) }, set: { setNumber(spinner, (it as Number).longValue()) }]
    }

    /** A spinner (optionally with a slider) of decimal numbers; with no declared limits it accepts +-LcConst.FLOAT_LIMIT. The step is 1% of a range of 100 or less, else 1. Same rules as
     *  integerControl: the text is the value, and a number beyond a limit is refused, never clamped. */
    Map floatControl(Map p) {
        Number lo = p.minimum != null ? p.minimum as Number : -LcConst.FLOAT_LIMIT
        Number hi = p.maximum != null ? p.maximum as Number : LcConst.FLOAT_LIMIT
        double initial = p.default != null ? p.default as double : Math.max(lo.doubleValue(), Math.min(hi.doubleValue(), 0d))
        double step = (hi.doubleValue() - lo.doubleValue()) <= 100 && p.maximum != null ? (hi.doubleValue() - lo.doubleValue()) / 100 : 1d
        String label = p.label as String
        LcDoubleFactory factory = new LcDoubleFactory(label, lo, hi, initial, step)
        def spinner = new Spinner<Double>(factory)
        spinner.editable = true
        followValue(spinner)
        commitOnFocusLost(spinner, label)
        controls[p.name] = spinner
        Node node = spinner
        if (wantsSlider(p)) node = sliderBeside(spinner, lo.doubleValue(), hi.doubleValue(), initial) { double b -> spinner.valueFactory.value = b }
        return [node: node, get: { LcNumbers.decimal(label, spinner.editor.text, lo, hi) }, set: { setNumber(spinner, it as double) }]
    }

    /** Puts a value into the box: the value AND its text (a value equal to the old one fires no change, and the text must not keep what was typed before). */
    static void setNumber(Spinner spinner, Object value) {
        spinner.valueFactory.value = value
        spinner.editor.text = spinner.valueFactory.converter.toString(value)
    }

    /** The box shows the text of its value whenever the value changes (the spinner's skin does this too, but a box that has no skin yet must hold its text as well: the text is what a run reads). */
    static void followValue(Spinner spinner) {
        spinner.editor.text = spinner.valueFactory.converter.toString(spinner.valueFactory.value)
        spinner.valueFactory.valueProperty().addListener({ o, a, b -> spinner.editor.text = spinner.valueFactory.converter.toString(b) } as javafx.beans.value.ChangeListener)
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
        fileFields[name] = fileField
        def sourceMap = sources
        def channelBox = p.pick_channel ? channelChooser(p, box, fileField, sourceMap) : null
        def selectionBox = p.region_of ? regionSelectionBox(name, box, fileField) : null
        def areaBox = areaChooser(p, box, selectionBox)
        Node node = new HBox(6, box, areaBox, fileField, browse)
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
                    def value = [source: sourceMap[v], channel: channel]
                    if (areaBox.value != LcArea.WHOLE && !areaBox.disable) value.area = areaBox.value      // Image area: only for the open image
                    return value
                }]
    }

    /** True for the "Current image: ..." entry of an image chooser (the image open in QuPath). */
    static boolean isCurrentImage(String choice) { return choice != null && choice.startsWith("Current image") }

    /** Image area: the part of the open image the tool receives. Only the open image has selections and a viewport, so for a project image or a file
     *  the chooser is disabled (and says why); so it is while "use the selection" is ticked (the area of the image the region belongs to counts). */
    ComboBox<String> areaChooser(Map p, ComboBox<String> box, CheckBox selectionBox) {
        def areaBox = new ComboBox<String>()
        areaBox.items.setAll(LcArea.CHOICES)
        areaBox.value = LcArea.WHOLE
        def tip = new Tooltip()
        tip.wrapText = true
        tip.maxWidth = LcConst.TOOLTIP_WIDTH
        areaBox.tooltip = tip
        areaBoxes[p.name] = areaBox
        def update = {
            boolean current = isCurrentImage(box.value as String), picked = selectionBox != null && selectionBox.selected
            areaBox.disable = !current || picked
            if (areaBox.disable) areaBox.value = LcArea.WHOLE
            tip.text = current && !picked ? "Image area: send the whole image, the bounding box of the selected annotation(s), or the part shown in the viewer. Results are placed back on the full image."
                     : current ? "Image area: the selection is the region; set the area on the image it belongs to"
                     : "Image area: a project image or a file is always sent whole; only the image open in QuPath can be limited to its selected annotations or the viewport"
        }
        box.valueProperty().addListener({ o, a, b -> update() } as javafx.beans.value.ChangeListener)
        if (selectionBox != null) selectionBox.selectedProperty().addListener({ o, a, b -> update() } as javafx.beans.value.ChangeListener)
        update()
        return areaBox
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

    /** A spinner only takes typed text on Enter; this also takes it when the box loses focus. Enter is handled here too, so that text the box refuses is said in the status line (commitSpinner) instead of
     *  raising the converter's error inside JavaFX's own key handler. */
    void commitOnFocusLost(Spinner spinner, String label = null) {
        spinner.focusedProperty().addListener({ o, a, focused -> if (!focused) commitSpinner(spinner, label) } as javafx.beans.value.ChangeListener)
        spinner.editor.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, { javafx.scene.input.KeyEvent e ->
            if (e.code == javafx.scene.input.KeyCode.ENTER) { commitSpinner(spinner, label); e.consume() }
        } as javafx.event.EventHandler)
    }

    /** Takes what was typed into the box (focus lost, Enter). Text the box refuses (not a number, beyond a limit) is said at once in the status line and stays in the box, and it is refused
     *  again when Run is pressed: the value is never changed to fit. */
    void commitSpinner(Spinner spinner, String label = null) {
        try {
            spinner.increment(0)
        } catch (LcNumberError e) {
            LcLog.warn(e.message + " (the box keeps what was typed; the run is refused until it is changed)", e)
            status?.text = e.message
        }
    }

    /** EnabledWhen: `equals` given = the controlling value is one of them (same type: 1 equals 1.0, never "1"); otherwise it is set (not unset, not false, not ""). */
    static boolean ruleSatisfied(Map rule, Object value) {
        if (rule.containsKey("equals")) {
            def wanted = rule.equals instanceof List ? rule.equals : [rule.equals]
            return wanted.any { w -> (w instanceof Number && value instanceof Number) ? (w as double) == (value as double) : (w == value && w?.class == value?.class) }
        }
        return value != null && !(value instanceof Boolean && !value) && value != ""
    }

    /** Calls `update` whenever the parameter `name` changes, whatever kind of control drives it: text, number, choice, yes/no, an image chooser or file field, its "set" box. */
    void onChange(String name, Closure update) {
        def listener = { o, a, b -> update() } as javafx.beans.value.ChangeListener
        def control = controls[name]
        if (control instanceof ComboBox) control.valueProperty().addListener(listener)
        else if (control instanceof CheckBox) control.selectedProperty().addListener(listener)
        else if (control instanceof Spinner) control.valueProperty().addListener(listener)
        else if (control instanceof TextField) control.textProperty().addListener(listener)
        checks[name]?.selectedProperty()?.addListener(listener)
        fileFields[name]?.textProperty()?.addListener(listener)
        selectionBoxes[name]?.selectedProperty()?.addListener(listener)
    }

    /** EnabledWhen: the parameter's row is disabled unless the driving parameter has the required value (or is set, when no value is named). */
    void hookEnabled(Map p) {
        def cond = p.enabled_when
        if (controls[cond.param] == null || controls[p.name] == null) return
        def update = {
            def driver = null
            try { driver = getters[cond.param]() } catch (LcNumberError e) { LcLog.once("enabled-driver:" + cond.param, "'" + cond.param + "' holds text the box refuses (" + e.message + "): '" + p.name + "' is greyed out until it is fixed") }
            wrappers[p.name].disable = !ruleSatisfied(cond, driver)
        }
        onChange(cond.param as String, update)
        update()
    }

    /** The control of a linked pixel-size field (PixelSizeOf, SPEC 2.2): a text box that shows the calibration of the chosen image or file, or nothing ("(not set)") when that is
     *  unknown, and a button that asks for the image's calibration again. What a run sends is the state in `links`, not the text. A write by the host is never an edit by the person:
     *  `programmatic` guards the text listener; typed or pasted text (also the same text again, through the key event) makes the value the person's. */
    Map pixelSizeControl(Map p) {
        def name = p.name as String
        def field = new TextField()
        field.promptText = "(not set)"
        field.prefColumnCount = 10
        def link = new LcLink(name, p.pixel_size_of as String)
        links[name] = link
        controls[name] = field
        field.textProperty().addListener({ o, a, b -> if (!programmatic) pixelTextEdited(p) } as javafx.beans.value.ChangeListener)
        field.addEventFilter(javafx.scene.input.KeyEvent.KEY_TYPED, { e -> if (!programmatic) Platform.runLater { pixelTextEdited(p) } } as javafx.event.EventHandler)      // typing the text the box already shows changes no text, but is still an edit
        field.focusedProperty().addListener({ o, was, now -> if (!now && link.state == LcLink.UNSET && !field.text?.trim()) fillPixelSize(p) } as javafx.beans.value.ChangeListener)      // left empty: the image's calibration again
        def useImage = new Button("Use the image's calibration")
        useImage.onAction = { link.userEdit(null); fillPixelSize(p) }
        return [node: new HBox(6, field, useImage), get: { link.value }, set: { writePixelText(p, it as Double) }]
    }

    /** The host writes `value` into a pixel-size field (null empties it): the state is not touched. */
    void writePixelText(Map p, Double value) {
        def field = controls[p.name] as TextField
        programmatic = true
        try { field.text = value == null ? "" : LcLink.plain(value) } finally { programmatic = false }
    }

    /** The person typed, pasted or deleted in a pixel-size field: the state becomes theirs. An empty box is "unset" (the image's calibration is taken again when the box loses the focus or a run starts); text that
     *  is not a number is kept as typed, and a run refuses it by name. */
    void pixelTextEdited(Map p) {
        def link = links[p.name as String]
        def field = controls[p.name] as TextField
        if (link == null || field == null) return
        def text = field.text?.trim()
        if (!text) { link.userEdit(null); return }
        def number = LcCal.number(text)
        if (number == null) { link.userEdit(null); link.state = LcLink.USER; link.invalid = text; return }      // text that is not a number: the person's, and refused at the run
        link.userEdit(number)
    }

    /** The calibration of what the field describes now, as [cal, source, label] or null (nothing to read yet): the file when the image input is a file (the file wins over the open
     *  image), else the chosen QuPath image. An image that cannot be read is said and counts as unknown: the value of another image is never kept. */
    Map deriveCalibration(Map p) {
        def box = imageBoxes[p.pixel_size_of]
        if (box == null) return null
        def choice = box.value as String
        if (choice == null || choice == NO_IMAGE) return null
        try {
            if (choice == FILE_CHOICE) {
                def path = fileFields[p.pixel_size_of]?.text?.trim()
                return (!path || !new File(path).isFile()) ? null : [cal: LcCal.ofFile(path), source: "file", label: path]      // a path still being typed: nothing to read yet
            }
            def server = imageSources()[choice]?.call()
            if (server == null) return null
            try { return [cal: LcCal.ofServer(server), source: "image", label: choice] }
            finally { if (!isCurrentImage(choice)) server.close() }          // the open image belongs to QuPath; a project image was opened here
        } catch (Exception e) {     // broad on purpose (any reader can fail on any file): a listener on the image chooser must never break the form; the person types the value
            LcLog.warn("pixel size of '" + choice + "' could not be read", e)
            status?.text = "could not read the pixel size from " + (choice == FILE_CHOICE ? fileFields[p.pixel_size_of]?.text?.trim() : choice) + " (" + e.message + "): type it"
            return null
        }
    }

    /** PixelSizeOf: the source changed (another image or file, or the field is derived again): an unset or automatic field takes the new source's calibration, or is cleared when that is
     *  unknown (SPEC 2.2); a value the person entered stays. A warning (pixels not square, a unit that is not a length) goes to the status line. */
    void fillPixelSize(Map p) {
        def link = links[p.name as String]
        if (link == null || imageBoxes[p.pixel_size_of] == null) return
        if (link.state != LcLink.USER) {
            def derived = deriveCalibration(p)
            link.sourceChanged(derived?.cal as LcCal, derived?.source as String)
            link.invalid = null
            def note = (derived?.cal as LcCal)?.note
            if (note) status?.text = "\u26a0 " + note + " (" + derived.label + ")"
        }
        showLink(p)
    }

    /** What the field shows for its state: the value (empty when unset) and the tooltip that says where it comes from. */
    void showLink(Map p) {
        def link = links[p.name as String]
        def field = controls[p.name] as TextField
        if (link.state != LcLink.USER) writePixelText(p, link.value)
        field.tooltip = new Tooltip(link.tooltip())
    }

    /** PixelSizeOf: the field follows the chosen image, the typed file and the image open in QuPath until the person enters a value. */
    void hookPixelSize(Map p) {
        def box = imageBoxes[p.pixel_size_of]
        if (box == null) return
        def again = { o, a, b -> fillPixelSize(p) } as javafx.beans.value.ChangeListener
        box.valueProperty().addListener(again)
        fileFields[p.pixel_size_of]?.textProperty()?.addListener(again)
        qupath?.imageDataProperty()?.addListener(again)      // the open image changed: "Current image" is another image now
        imageDataListeners << again
    }

    /** A run starts: every field that is not the person's own reads its source again (a calibration may have been edited in QuPath since it was chosen). */
    void refreshLinks() {
        currentTool?.inputs?.findAll { it.pixel_size_of && links[it.name] != null }?.each { p -> fillPixelSize(p) }
    }

    // ---- copy as command (the text format of labconstrictor_tools.command, the reference). Quoting only through two helpers: shellQuote (terminal words)
    // and pythonLiteral (Python snippet); no copied text is assembled with bare quotes around a value.
    /** The placeholder path written for a required file-like parameter that has no path. */
    static final Map<String, String> FILE_PLACEHOLDERS = [image: "image.tif", labels: "labels.tif", table: "table.csv", file: "file", folder: "folder"]

    /** Quotes one word for a POSIX shell or for Windows; text made only of safe characters stays as it is. The expected results for both are the vectors of
     *  labconstrictor-tools (tests/quote_vectors.json, copied to tests/ here): `command.quote` is the reference. */
    static String shellQuote(String text, boolean windows) {
        if (windows) return quoteWindows(text)
        return (text && text ==~ /[A-Za-z0-9_@%+=:,.\/-]+/) ? text : "'" + text.replace("'", "'\"'\"'") + "'"
    }

    /** One argument for a Windows command line, by the rules of the C runtime (CommandLineToArgvW): the text is put in double quotes unless it is made of safe characters
     *  only; backslashes are literal, except a run of them in front of a double quote (doubled, and the quote escaped) or at the end of the text, where it sits in front
     *  of the closing quote that is added (`"C:\My Data\"` would otherwise swallow it). */
    static String quoteWindows(String text) {
        if (text && text ==~ /[A-Za-z0-9_.:\/\\=+,-]+/) return text
        def out = new StringBuilder('"')
        int run = 0      // backslashes seen since the last other character
        for (char c in text.toCharArray()) {
            if (c == ('\\' as char)) { run++; continue }
            out.append('\\' * (c == ('"' as char) ? 2 * run + 1 : run)).append(c)
            run = 0
        }
        return out.append('\\' * (2 * run)).append('"').toString()
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
            if (v.area) {
                notes << ("# " + p.name + ": QuPath sent only the Image area '" + v.area + "' (the command line has no area); the command sends the whole file")
                LcLog.once("copy-area:" + currentTool?.id + ":" + p.name, "copy as command: the Image area '" + v.area + "' of '" + p.name + "' is not part of the command (it sends the whole file)")
            }
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

    /** One Python literal, written the way Python's repr writes it: True/False, a number as it is, a text in single quotes (double quotes when it holds a single
     *  quote and no double quote); backslash, the quote, \n \r \t and every other control or non-printable character are escaped, so a pasted snippet reads back the same value. */
    static String pythonLiteral(Object v) {
        if (v instanceof Boolean) return v ? "True" : "False"
        if (v instanceof Number) return v.toString()
        def text = v.toString()
        String quote = (text.contains("'") && !text.contains('"')) ? '"' : "'"
        def out = new StringBuilder(quote)
        text.codePoints().forEach { int cp ->
            if (cp == (int) ('\\' as char) || cp == (int) (quote as char)) out.append('\\').appendCodePoint(cp)
            else if (cp == 10) out.append("\\n")
            else if (cp == 13) out.append("\\r")
            else if (cp == 9) out.append("\\t")
            else if (cp < 32 || (cp >= 127 && cp < 160)) out.append(String.format("\\x%02x", cp))
            else if (!isPrintable(cp)) out.append(cp > 0xFFFF ? String.format("\\U%08x", cp) : String.format("\\u%04x", cp))
            else out.appendCodePoint(cp)
        }
        return out.append(quote).toString()
    }

    /** Python's str.isprintable() for one code point: not a control, format, surrogate, private-use or unassigned character, and no separator except the plain space. */
    static boolean isPrintable(int cp) {
        return !(Character.getType(cp) in LcConst.NON_PRINTABLE_TYPES) || cp == 32
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
        lastCopied = text
        def problem = putOnClipboard(text)
        if (problem == null) {
            status.text = "copied the " + (kind == "python" ? "Python snippet" : "terminal command") + " to the clipboard"
        } else {      // never report a copy that did not happen (the Napari wording); the command is in the log so it is not lost
            LcLog.warn("no clipboard to copy the " + (kind == "python" ? "Python snippet" : "terminal command") + " to (" + problem + "); the command was:\n" + text)
            status.text = "there is no clipboard to copy to"
        }
        return text
    }

    /** Puts `text` on the system clipboard; null, or what went wrong (headless, locked by another program, or refused). `sink` takes the content and says whether the clipboard accepted it:
     *  the system clipboard unless a test hands in another. */
    String putOnClipboard(String text, Closure sink = { content -> javafx.scene.input.Clipboard.systemClipboard.setContent(content) }) {
        try {
            def content = new javafx.scene.input.ClipboardContent()
            content.putString(text)
            return sink(content) ? null : "the clipboard refused the text"
        } catch (RuntimeException e) {      // broad on purpose: the clipboard belongs to the window system and can fail in many ways (no display, locked, wrong thread)
            return e.class.simpleName + ": " + e.message
        }
    }

    // ---- the request: what the form asks for
    /** The values of the form in the tool's order, or null (with the reason in the status line) when one cannot be read or a required one is missing. */
    Map readFormValues() {
        def values = new LinkedHashMap()
        for (p in currentTool.inputs) {
            def v
            try { v = getters[p.name]() }
            catch (LcNumberError e) { LcLog.warn("run refused: " + e.message); status.text = e.message; return null }      // a typed number the box refuses (SPEC 6.2): the sentence alone, nothing is sent
            catch (Exception e) { LcLog.warn("could not read '" + p.name + "'", e); status.text = "Check '" + p.label + "': " + e.message; return null }
            if (p.pixel_size_of && links[p.name] != null) {      // a linked pixel size (SPEC 2.3): the state decides; the declared default is never a stand-in
                def link = links[p.name]
                if (link.invalid != null) { status.text = "'" + p.label + "': '" + link.invalid + "' is not a number"; return null }
                if (v == null) {
                    if (!p.nullable) { status.text = link.requiredMessage(); return null }
                    values[p.name] = null       // Optional[float]: an unset field sends null, nothing is blocked
                    continue
                }
                if (!Double.isFinite(v as double)) { status.text = "'" + p.label + "' must be a finite number"; return null }
                if (!((v as double) > 0d)) { status.text = "'" + p.label + "' must be greater than 0 (um per pixel)"; return null }
                values[p.name] = v
                continue
            }
            if (v == null) {
                if (p.required && !p.nullable && p.type in ["image", "labels", "table", "file", "folder", "string"]) { status.text = requiredSentence(p); return null }
                continue
            }
            if (v instanceof Double && !Double.isFinite(v)) { status.text = "'" + p.label + "' must be a finite number"; return null }      // Gson cannot write NaN or infinity: refused here, with the worker's sentence
            values[p.name] = v
        }
        calibrationAtRun = links.collectEntries { name, link -> [(name): link.entry()] }.findAll { it.value != null && values[it.key] != null } as Map      // frozen with the values
        return values
    }

    /** The worker's template `'L' is required` (missing_parameter); an image also gets the way out, as the Fiji dialog says it. */
    static String requiredSentence(Map p) {
        return "'" + p.label + "' is required" + (p.type in ["image", "labels"] ? ": choose an image, or give a file" : "")
    }

    // ---- running
    /** Run pressed: read the form, then start the run in the background. */
    void run() {
        if (running || currentTool == null) return
        refreshLinks()
        def values = readFormValues()
        if (values == null) return
        imageChoiceAtRun = imageBoxes.collectEntries { k, b -> [(k): b.value as String] }
        def app = apps[appBox.value]
        Map<String, String> compat = LcCompat.checkApp(app)
        if (compat.status == LcCompat.REFUSE) { LcLog.warn("run of '" + currentTool.id + "' refused: " + compat.reason); status.text = compat.reason; return }
        showRunning()
        try {
            freeze(values)
        } catch (LcRefusal e) {
            LcLog.warn("run of '" + currentTool.id + "' refused" + (e.code ? " [" + e.code + "]" : "") + ": " + e.message)
            runSnapshot?.active = false
            finish(appBox.value as String, currentTool.label as String, [responseType: "FAILURE", error: e.message, code: LcConst.REFUSAL_CODE, reason: e.code], 0d)
            return
        }
        runInBackground(values)
    }

    /** Freezes the run (SPEC 4.1) on the FX thread: the image each input comes from (as objects), the selected regions, the plane and the Image areas, all as they are NOW. A refusal
     *  (an area outside the image, no selection, a chooser that names another image than the open one) is thrown before anything starts. */
    void freeze(Map values) {
        planeAtRun = viewerPlane()
        runSnapshot = LcRun.take(this, values)
        areasAtRun = resolveAreas(values, currentTool.inputs)
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
        idleTimer.stop()
        if (worker == null || workerApp != appName || worker.closed || !worker.proc.isAlive()) {
            stopWorker(false)
            worker = new LcWorker(app.entry)
            workerApp = appName
        }
    }

    /** Copies what the thread needs (the form may change meanwhile), runs on a thread, shows the outcome on the FX thread. */
    void runInBackground(Map values) {
        LcRun run = runSnapshot
        def toolId = run.toolId
        def app = run.app
        def appName = run.appName
        def toolLabel = run.toolLabel
        def toolInputs = run.tool.inputs
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
        Map inputs = null
        File recordDir = null
        def t0 = System.nanoTime()
        def tmp = Files.createTempDirectory("lcqupath_").toFile()
        try {
            inputs = prepareInputs(values, toolInputs, tmp, appName, toolId)
            recordDir = LcRuns.begin(appName, toolId)      // the record folder exists from the start of the run, owner marker first (SPEC 3.2)
            ensureWorker(app, appName)
            outcome = worker.run(toolId, inputs) { message, current, maximum ->
                Platform.runLater {
                    status.text = message
                    progress.progress = (current != null && maximum) ? (current as double) / (maximum as double) : ProgressBar.INDETERMINATE_PROGRESS
                }
            }
        } catch (LcRefusal e) {
            LcLog.warn("run of '" + toolId + "' refused" + (e.code ? " [" + e.code + "]" : "") + ": " + e.message)      // a sentence for the person, not a failure: no stack trace
            outcome = [responseType: "FAILURE", error: e.message, code: LcConst.REFUSAL_CODE, reason: e.code]       // reason: the host-side code (conformance host_internal_codes) when there is one
        } catch (Exception e) {
            // broad on purpose (isolation boundary of the run thread): any failure becomes the outcome the person reads (status line and Details, with the stack trace)
            LcLog.warn("run of '" + toolId + "' failed on the host", e)
            outcome = [responseType: "FAILURE", error: e.class.simpleName + ": " + e.message, code: "host_error", trace: LcLog.trace(e)]
        } finally {
            LcLog.deleteTemp(tmp)
        }
        if (inputs != null && outcome.code != LcConst.REFUSAL_CODE) {      // a refusal never reached the worker: no record
            LcRun run = runSnapshot
            Map<String, String> ended = LcRuns.finish(recordDir, new File(inputs["_job_dir"] as String), [entry: app.entry, app: appName, tool: toolId, inputs: inputs, outcome: outcome, seconds: (System.nanoTime() - t0) / 1e9,
                                                                                                          stderr: worker?.stderrText(), runId: run?.runId, startedUtc: run?.startedUtc])
            if (ended.problem) outcome.recordProblem = ended.problem
            else LcRetention.startPrune(app.entry as Map)      // after the done markers: the Tools CLI removes what is past the quota, this script never does (SPEC 3.1)
        }
        return outcome
    }

    // ---- the request: what the tool receives
    /** RegionOf: the selected annotations of the open image as a 16-bit label image the size of that image (labels 1..N, 0 outside).
     *  Whatever makes that impossible is said to the person, never guessed around. */
    File selectionMask(Map p, File tmp, String appName) {
        def label = p.label as String
        def chosen = imageChoiceAtRun[p.region_of]
        def data = LcRun.dataFor(this, p.region_of as String)
        if (data == null || chosen == null || !chosen.startsWith("Current image"))
            throw new LcRefusal("'" + label + "': the selection belongs to the image open in QuPath: choose 'Current image' for the image, or untick the selection")
        def selected = LcRun.roisOf(this, data).collect { [ROI: it] }      // the regions frozen when Run was pressed
        if (!selected) throw new LcRefusal("'" + label + "': no annotation is selected: select one or more annotations, or untick the selection")
        LcArea.checkSelectionPlane(label, selected*.ROI, planeAtRun.z, planeAtRun.t)       // the mask is for the plane that is exported (QuPath #21)
        if (selected.size() > LcConst.MAX_REGION_OBJECTS) throw new LcRefusal("'" + label + "': " + selected.size() + " annotations are selected; at most " + LcConst.MAX_REGION_OBJECTS + " are supported")
        def server = data.server
        def area = areasAtRun[p.region_of] ?: LcArea.whole(server, planeAtRun.z, planeAtRun.t)
        if (LcArea.pixels(area) > LcConst.MAX_REGION_PIXELS)
            throw new LcRefusal("'" + label + "': the " + (areasAtRun[p.region_of] ? "area" : "image") + " has " + LcArea.pixels(area) + " pixels; a selection region is supported up to " + LcConst.MAX_REGION_PIXELS + " (choose 'Image area: " + LcArea.SELECTION + "' on the image, or use a smaller image or a file)")
        def mask = paintLabelMask(selected, area.width as int, area.height as int, area.x0 as double, area.y0 as double)
        if (mask == null) throw new LcRefusal("'" + label + "': the selected annotations cover no pixel of the " + (areasAtRun[p.region_of] ? "Image area" : "image"))
        def out = new File(tmp, p.name + ".tif")
        ImageWriterTools.writeImage(mask, out.absolutePath)
        return out
    }

    /** The objects painted as a 16-bit label image (object i has label i + 1), or null when none covers a pixel. The image is the part of the slide
     *  that starts at (x0, y0) and is w x h pixels (the Image area; the whole image when they are 0). */
    static BufferedImage paintLabelMask(Collection selected, int w, int h, double x0 = 0d, double y0 = 0d) {
        def painted = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)       // each object painted in the colour of its number (no anti-aliasing: pixel centres decide)
        def g = painted.createGraphics()
        try {
            g.translate(-x0, -y0)
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
        LcRun run = runSnapshot
        LcRun.reach("prepare")
        boolean frozen = run != null && run.active      // a run started by Run: the plane, the areas, the images and the selection were frozen when it was pressed (SPEC 4.1)
        if (!frozen) {                                   // a direct call (no run in progress): one plane for the whole call, read once; before anything is exported, so a refused area costs nothing
            planeAtRun = viewerPlane()
            areasAtRun = resolveAreas(values, params)
        }
        framesAtRun = [:]
        LcRun.reach("export")
        values.each { name, v -> inputs[name] = prepareInput(params.find { it.name == name }, name as String, v, tmp, appName) }
        def calibration = calibrationAtRun.findAll { name, entry -> values[name] != null }
        if (calibration) inputs["_calibration"] = calibration      // the reserved per-axis input (SPEC 2.3.4): tools never see it, the run record keeps it
        inputs["_job_dir"] = newJobDir(appName, toolId).absolutePath
        if (frozen) run.resultsPath = inputs["_job_dir"]
        return inputs
    }

    /** What the tool receives for one value: images are exported (or the file's path is passed), files are checked, the rest passes unchanged. */
    Object prepareInput(Map p, String name, Object v, File tmp, String appName) {
        if (p.type in ["image", "labels"]) return prepareImageInput(p, name, v, tmp, appName)
        if (p.type == "folder") {
            def folder = new File(v as String)
            if (!folder.isDirectory()) throw new LcRefusal("'" + p.label + "': folder not found: " + folder)      // a regular file is not a folder; the worker's template, the sentence alone
            return folder.absolutePath
        }
        if (p.type in ["file", "table"]) return new File(v as String).absolutePath      // no host check: the worker answers a missing one (Tools: [file_not_found] table file not found: P), as it does for Napari and Fiji
        return v
    }

    /** The area of every image parameter whose Image area is not the whole image (parameter name -> area). Several image inputs must agree: the
     *  results of the tool can have only one offset, so a run that mixes areas (or an area with a whole image or a file) is refused. */
    Map<String, Map> resolveAreas(Map values, List params) {
        def areas = new LinkedHashMap<String, Map>()
        def exported = params.findAll { it.type in ["image", "labels"] && values[it.name] instanceof Map && !values[it.name].selection }
        for (p in exported) {
            def v = values[p.name]
            if (v.area && v.area != LcArea.WHOLE) areas[p.name as String] = resolveArea(p, v.area as String, v.source.call())
        }
        if (areas) {
            def first = areas.keySet().first()
            def odd = exported.find { !areas.containsKey(it.name) || !LcArea.same(areas[it.name], areas[first]) }
            if (odd != null) {
                def describe = { name -> areas.containsKey(name) ? LcArea.describe(areas[name]) : "the whole image (a project image, a file or Whole image)" }
                throw new LcRefusal("'" + odd.label + "' and '" + params.find { it.name == first }.label + "' use different areas: " + describe(odd.name) + " and " + describe(first) +
                        ". The results of a tool can have only one offset: use the same Image area for all image inputs, or Whole image")
            }
        }
        return areas
    }

    /** The plane the viewer shows now ([z, t]; 0 / 0 when no viewer shows an image): read on the FX thread. */
    Map<String, Integer> viewerPlane() {
        return onFx {
            def viewer = qupath?.viewer
            viewer?.imageData != null ? planeOf(viewer.getZPosition() as int, viewer.getTPosition() as int) : planeOf(0, 0)
        } as Map
    }

    /** A plane as the Map [z, t] (the form planeAtRun keeps). */
    static Map<String, Integer> planeOf(int z, int t) { return [z: z, t: t] }

    /** The area of the open image that an Image area choice other than the whole image names, or an LcRefusal that says why there is none. */
    Map resolveArea(Map p, String kind, def server) {
        return onFx { resolveAreaOnFx(p, kind, server) } as Map
    }

    /** Runs `c` on the FX thread (selections and the viewer belong to it) and returns its result or rethrows what it threw. */
    static Object onFx(Closure c) {
        if (Platform.isFxApplicationThread()) return c()
        def latch = new java.util.concurrent.CountDownLatch(1)
        def box = [null, null]
        Platform.runLater {
            try { box[0] = c() } catch (Exception e) { box[1] = e } finally { latch.countDown() }       // broad on purpose: handed back to the caller below, which rethrows it
        }
        latch.await()
        if (box[1] != null) throw box[1]
        return box[0]
    }

    Map resolveAreaOnFx(Map p, String kind, def server) {
        def label = p.label as String
        def data = LcRun.dataFor(this, p.name as String)
        if (data == null) throw new LcRefusal("'" + label + "': no image is open in QuPath for the Image area '" + kind + "': open the image, or choose '" + LcArea.WHOLE + "'")
        def viewer = qupath.viewer
        int z = planeAtRun.z, t = planeAtRun.t       // the plane the person was looking at when the run started
        def area = kind == LcArea.SELECTION ? selectionArea(label, LcRun.roisOf(this, data), server, z, t) : kind == LcArea.VIEWPORT ? viewportArea(label, viewer, server, z, t) : null
        if (area == null && kind != LcArea.SELECTION && kind != LcArea.VIEWPORT) throw new LcRefusal("'" + label + "': unknown Image area '" + kind + "'")
        if (area == null) throw new LcRefusal("'" + label + "': the " + kind + " lies outside the image: choose another area, or '" + LcArea.WHOLE + "'")
        return area
    }

    /** The bounding box of the selected annotations (points do not count), clamped to the image. */
    static Map selectionArea(String label, List rois, def server, int z, int t) {
        if (!rois) throw new LcRefusal("'" + label + "': no annotation is selected for the Image area '" + LcArea.SELECTION + "': select one or more annotations, or choose '" + LcArea.WHOLE + "'")
        LcArea.checkSelectionPlane(label, rois, z, t)       // one plane, the exported one (QuPath #21)
        def box = [rois*.boundsX.min(), rois*.boundsY.min(), rois.collect { it.boundsX + it.boundsWidth }.max(), rois.collect { it.boundsY + it.boundsHeight }.max()]
        return LcArea.clamped(LcArea.SELECTION, box as List<Double>, server, z, t)
    }

    /** The bounds of the region the viewer shows, clamped to the image. */
    static Map viewportArea(String label, def viewer, def server, int z, int t) {
        if (viewer == null || viewer.imageData == null) throw new LcRefusal("'" + label + "': no viewer shows the image for the Image area '" + LcArea.VIEWPORT + "': open the image in a viewer, or choose '" + LcArea.WHOLE + "'")
        def b = viewer.displayedRegionShape.bounds2D
        return LcArea.clamped(LcArea.VIEWPORT, [b.minX, b.minY, b.maxX, b.maxY], server, z, t)
    }

    /** An image value becomes a file path: the selection mask, the file as typed, or an exported TIFF (one channel when PickChannel chose one,
     *  only the Image area when one is chosen). An export above the size budget is refused. */
    String prepareImageInput(Map p, String name, Map v, File tmp, String appName) {
        if (v.selection) return selectionMask(p, tmp, appName).absolutePath          // RegionOf: the selected annotations
        boolean oneChannel = p.pick_channel && v.channel != null && v.channel >= 0
        if (v.file && !oneChannel) return passFile(p, name, existingFile(p, v.file as String))
        def server = v.file ? ImageServers.buildServer(existingFile(p, v.file as String).absolutePath) : v.source.call()
        def area = areasAtRun[name] ?: LcArea.whole(server, *exportedPlane(name, v))
        framesAtRun[name] = [width: area.width, height: area.height]
        guardExportSize(p, server, area)
        guardExportBytes(p, server, area, oneChannel ? 1 : server.nChannels())
        guardPlanes(p, server, area)
        if (oneChannel && server.nChannels() > 1)       // PickChannel: only the chosen channel is exported
            server = new TransformedServerBuilder(server).extractChannels(v.channel as int).build()
        def out = new File(tmp, name + ".tif")
        ImageWriterTools.writeImageRegion(server, LcArea.request(server, area), out.absolutePath)
        return out.absolutePath
    }

    /** The plane an image input is exported on when no area names one: the viewer's plane at run start for the image open in QuPath ("Current image"),
     *  plane 0 / 0 for a project image or a file (nothing shows them, and a file is its own pixel frame, SPEC 1.3). */
    List<Integer> exportedPlane(String name, Map v) {
        return (!v.file && isCurrentImage(imageChoiceAtRun[name])) ? [planeAtRun.z, planeAtRun.t] : [0, 0]
    }

    /** A file the tool reads as it is (no export): its path; its size is kept as the frame, for the size sentence of SPEC 1.1.7 (unreadable: no size known). */
    String passFile(Map p, String name, File file) {
        try {
            def server = ImageServers.buildServer(file.absolutePath)
            framesAtRun[name] = [width: server.width, height: server.height]
            server.close()
        } catch (Exception e) {      // broad on purpose (any reader can fail on any file): the tool reports an unreadable file; here only the size of the frame is missing
            LcLog.warn("size of '" + file + "' could not be read (no frame size for the size check)", e)
        }
        return file.absolutePath
    }

    /** Refuses an export above the budget (LcArea.budget): never a silent downsample or crop. */
    static void guardExportSize(Map p, def server, Map area) {
        long pixels = LcArea.pixels(area), budget = LcArea.budget()
        if (pixels <= budget) return
        def name = server.metadata?.name ?: new File(server.path.replaceFirst(/^[^:]*:\s*/, "")).name
        throw new LcRefusal("'" + p.label + "': the image '" + name + "' would be sent as " + area.width + " x " + area.height + " = " + pixels + " pixels (" + (area.kind == LcArea.WHOLE ? "whole image" : area.kind) +
                "), above the limit of " + budget + " pixels per plane (system property " + LcConst.MAX_EXPORT_PROPERTY + "). Choose 'Image area: " + LcArea.SELECTION + "' to send a part of it, or use a smaller image or a file")
    }

    /** Refuses an export above LcArea.byteBudget() bytes (4 GiB, what Napari refuses): the pixels of the area x the channels sent x the bytes of the pixel type. */
    static void guardExportBytes(Map p, def server, Map area, int channelsSent) {
        long bytesPerSample = server.pixelType.bytesPerPixel as long
        long size = LcArea.pixels(area) * channelsSent * bytesPerSample, budget = LcArea.byteBudget()
        if (size <= budget) return
        def name = server.metadata?.name ?: new File(server.path.replaceFirst(/^[^:]*:\s*/, "")).name
        throw new LcRefusal("'" + p.label + "': the image '" + name + "' is " + String.format("%.1f", size / (1024d * 1024d * 1024d)) + " GB (" + area.width + " x " + area.height + " pixels x " + channelsSent +
                " channel(s) x " + bytesPerSample + " byte(s)); exporting more than " + String.format("%.0f", budget / (1024d * 1024d * 1024d)) + " GB is not supported (system property " + LcConst.MAX_EXPORT_BYTES_PROPERTY +
                "). Choose 'Image area: " + LcArea.SELECTION + "' to send a part of it, or use a smaller image or a file")
    }

    /** A tool that declares its axes (Axes("YX")) gets no stack by chance: a Z or T axis the tool does not declare is refused for the whole image, with the Tools
     *  sentence (wrong_dimensions) first. The plane on screen is sent only when the person chooses an Image area (selection or viewport), which names that plane. */
    static void guardPlanes(Map p, def server, Map area) {
        String axes = p.axes as String
        if (!axes || area.kind != LcArea.WHOLE) return
        def extra = [[server.nZSlices(), "Z", "z-planes"], [server.nTimepoints(), "T", "time points"]].findAll { it[0] > 1 && !axes.contains(it[1] as String) }
        if (!extra) return
        def sizes = []
        if (server.nTimepoints() > 1) sizes << server.nTimepoints()
        if (server.nZSlices() > 1) sizes << server.nZSlices()
        if (server.nChannels() > 1) sizes << server.nChannels()
        sizes << server.height << server.width
        throw new LcRefusal("'" + p.label + "' must be a " + axes.length() + "D image (" + axes + ") but got " + sizes.size() + "D with shape (" + sizes.join(", ") + ")" +
                ": the image has " + extra.collect { it[0] + " " + it[2] }.join(" and ") + ". Choose 'Image area: " + LcArea.VIEWPORT + "' to send the plane on screen, or use a 2D image")
    }

    /** The file of image parameter `p`, or the refusal `'L': file not found: P`. */
    static File existingFile(Map p, String path) {
        def f = new File(path)
        if (!f.isFile()) throw new LcRefusal("'" + p.label + "': file not found: " + f)      // the Napari and Fiji sentence, shown alone
        return f
    }

    /** Results go to a new folder under <LC_HOME>/results, where the command line also puts its runs, with its owner marker written first (SPEC 3.2; the done marker comes when the run ends).
     *  The host never deletes a results folder: `labconstrictor-tools prune` does, started after the run (LcRetention). */
    static File newJobDir(String appName, String toolId) {
        def root = new File(LcRegistry.home(), "results")
        root.mkdirs()
        def stamp = new java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss").format(new Date())
        def job = null
        for (int attempt = 0; job == null && attempt < LcConst.RESULTS_DIR_ATTEMPTS; attempt++) {
            def random = new byte[LcConst.RESULTS_DIR_RANDOM_BYTES]
            new java.security.SecureRandom().nextBytes(random)
            def candidate = new File(root, stamp + "_" + random.collect { String.format("%02x", it & 0xff) }.join("") + "_" + slug(appName) + "_" + slug(toolId))
            if (candidate.mkdir()) job = candidate          // a name that exists is never reused
        }
        if (job == null) throw new LcRefusal("could not create a results folder in " + root)
        try { LcRetention.writeOwner(job, "result") }
        catch (IOException e) { throw new LcRefusal("could not mark the results folder " + job + " as this run's (" + e.message + "); nothing was started") }      // an unmarked folder could never be cleaned up: refuse instead
        return job
    }

    /** `text` made safe for a file name: runs of other characters become "_", no leading dots, never empty (the command line's _slug). */
    static String slug(String text) { return text.replaceAll(/[^A-Za-z0-9_.-]+/, "_").replaceFirst(/^\.+/, "") ?: "x" }

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
            status.text = outcome.workerStopped ? "cancelled (worker stopped)" : "cancelled"      // the Napari wording when the tool ignored Cancel and the worker was ended
        } else if (type == "CRASH") {
            status.text = "the worker stopped: " + outcome.error
            stopWorker(true)
        } else {
            finishFailed(toolLabel, outcome)
        }
        if (outcome.recordProblem) status.text = status.text + "  (" + outcome.recordProblem + ")"      // a record that could not be written is said, never dropped
        runSnapshot?.active = false
        if (!keepWorker.selected) stopWorker(false)
        else if (worker != null) { idleTimer.duration = Duration.seconds(idleSeconds); idleTimer.playFromStart() }      // the clock of an unused worker starts when its run ends
    }

    /** The idle timer ran out: the kept worker (a torch worker holds gigabytes) is closed; the next run starts a new one. */
    void closeIdleWorker() {
        if (running || worker == null) return
        LcLog.once("idle-worker:" + workerApp + ":" + idleSeconds, "the worker of '" + workerApp + "' was idle for " + idleSeconds + " s and was closed (the next run starts a new one)")
        stopWorker(false)
    }

    /** A completed run: status line with the values, results window, then the post-run updates. */
    void finishCompleted(String appName, String toolLabel, Map outcome, double seconds) {
        def results = outcome.outputs?.results ?: []
        def summary = results.findAll { it.type == "values" }.collect { r -> r.values.collect { k, v -> k + "=" + show(v) }.join(", ") }.join("; ")
        status.text = "done in " + String.format("%.1f", seconds) + "s  " + summary
        resultNotes = []
        targetNote = ""
        showResults(appName, toolLabel, results)
        if (resultNotes) status.text = status.text + "  " + resultNotes.unique().join(" ")      // what the person must know about where the results are (size mismatch, SPEC 1.1.7)
        if (targetNote) status.text = targetNote          // the results could not be drawn on their image (SPEC 4.4): the sentence says which run, which image and where the files are
        if (LcRun.formShowsToolOf(this)) clearAfterRun()              // another tool or app is shown now: its fields are not this run's to clear
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
        } else if (code == LcConst.REFUSAL_CODE) {
            status.text = error            // a refusal of the host: the sentence alone, like Napari and Fiji
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
                def text = (r.text as String).replaceAll(/\*\*(.+?)\*\*/, '$1').replaceAll(/(?m)^\s*[-*] /, "\u2022 ")      // bold removed, list items as bullets (the Fiji rendering)
                messageLabel.text = (messageLabel.text && messageLabel.visible ? messageLabel.text + "\n\n" : "") + text
                messageLabel.visible = true; messageLabel.managed = true
                break
            case "points":
                box.children.addAll(boldLabel((r.name ?: "points") + " (points)"), new Label(placePoints(appName, r)), tableView(r.path as String, (r.name ?: "points") as String))
                break
            case "shapes":
                box.children.addAll(boldLabel((r.name ?: "shapes") + " (outlines)"), wrapped(placeShapes(appName, r)))
                break
            case ["image", "labels"]: box.children.addAll(imageSection(r)); break
            case "table":
                box.children.addAll(boldLabel(r.name ?: "table"), tableView(r.path as String, (r.name ?: "table") as String))
                break
            case "affine": box.children.addAll(affineSection(r)); break
            case "file":
                def named = (r.name ?: "file") as String
                box.children.addAll(boldLabel(named + " (file)"), wrapped(new File(r.path as String).exists() ? r.path as String
                        : "could not show the result '" + named + "': the tool reported the file " + r.path + " but it does not exist"))
                break
            default:      // a result type this version does not know: the file (when there is one) is listed, with one clear sentence
                box.children.addAll(boldLabel((r.name ?: r.type) + " (" + r.type + ")"),
                        wrapped("the tool returned a result of the type '" + r.type + "', which this version of QuPath LabConstrictor cannot show" + (r.path ? ": " + r.path : "")))
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
        def mismatch = sizeMismatch(r)
        if (mismatch) { nodes << wrapped(mismatch); resultNotes << mismatch }
        return nodes
    }

    /** SPEC 1.1.7: an image or labels result that says ApplyTo and has another Y/X size than the part of its source the tool received is never aligned (QuPath opens a
     *  result as an image of its own at the pixel origin); the sentence is conformance `size_mismatch`. Empty when the sizes agree or one of them is unknown. */
    String sizeMismatch(Map r) {
        def declared = r.apply_to ?: LcRun.toolOf(this)?.outputs?.find { it.name == r.name }?.display?.apply_to      // only a result that says ApplyTo (the first image input is not assumed)
        def frame = declared ? framesAtRun[declared as String] : null
        if (frame == null) return ""
        try {
            def server = ImageServers.buildServer(r.path as String)
            if (server.width == frame.width && server.height == frame.height) return ""
            def source = LcRun.toolOf(this)?.inputs?.find { it.name == declared }?.label ?: declared
            return LcConst.SIZE_MISMATCH_TEMPLATE.replace("{name}", (r.name ?: r.type) as String).replace("{w}", server.width as String).replace("{h}", server.height as String)
                    .replace("{source}", source as String).replace("{w2}", frame.width as String).replace("{h2}", frame.height as String)
        } catch (Exception e) {      // broad on purpose (any reader can fail on any file): the preview reports an unreadable result; here the size check is just skipped
            LcLog.warn("size of the result '" + r.name + "' could not be read (no size check)", e)
            return ""
        }
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
        def replacing = LcRun.toolOf(this)?.outputs?.any { it.replace } && resultWindows[key]?.showing
        def stage2 = replacing ? resultWindows[key] : new Stage()
        stage2.title = appName + ": " + toolLabel
        stage2.userData = runSnapshot?.runId      // which run these results are of
        if (!replacing) stage2.initOwner(stage)
        def sc = new ScrollPane(box)
        sc.fitToWidth = true
        if (replacing) stage2.scene.root = sc else stage2.scene = new Scene(sc, LcConst.RESULTS_WIDTH, LcConst.RESULTS_HEIGHT)
        stage2.show()
        resultWindows[key] = stage2
        lastResultStage = stage2
    }
    Stage lastResultStage       // the window of the last results
    String targetNote = ""      // the sentence of SPEC 4.4 when results of the last run were not drawn because their image is gone or changed (the status line shows it)

    // ---- placing results on the image (points, outlines)
    /** The input an output's results belong to: the one its ApplyTo names (the result's own `apply_to`, else the declaration in the schema), else the first image or labels input. */
    String applyToOf(Map r) {
        return r.apply_to ?: LcRun.toolOf(this)?.outputs?.find { it.name == r.name }?.display?.apply_to ?: LcRun.toolOf(this)?.inputs?.find { it.type in ["image", "labels"] }?.name
    }

    /** The image the results of output `r` are drawn on: the image the run was started on (frozen when Run was pressed, SPEC 4.2), and only while that very `ImageData` is still open
     *  with the shape it was exported with (4.4). null when the input was not the image open in QuPath (a file or a project image), or when that image is gone or changed:
     *  notPlacedSentence says which. Never the image that happens to be open when the run ends. */
    qupath.lib.images.ImageData openImageFor(Map r) {
        LcRun run = runSnapshot
        def target = applyToOf(r)
        LcSource source = (run != null && target != null) ? run.sources[target as String] : null
        return (source == null || run.targetProblem(qupath, target as String) != null) ? null : source.imageData
    }

    /** The area the results of output `r` were found in: that of its apply_to image (a region parameter counts as the image it belongs to), else of the
     *  first image input; null when that input was sent whole (no offset). */
    Map areaFor(Map r) {
        def target = applyToOf(r)
        def param = LcRun.toolOf(this)?.inputs?.find { it.name == target }
        return areasAtRun[(param?.region_of ?: target) as String]
    }

    /** The plane the results of a run are drawn on (SPEC 1.1.9): the plane of the area that was exported, else the plane the viewer showed at run start
     *  (the whole image of the open image is exported on that plane), never plane 0 by default. */
    ImagePlane placementPlane(Map area) {
        return area != null ? LcArea.plane(area) : ImagePlane.getPlane(planeAtRun.z as int, planeAtRun.t as int)
    }

    /** Why the results of output `r` are not drawn on an image (SPEC 1.1.5, 1.3): a file input is its own pixel frame and says so (conformance `file_input_note`);
     *  any other source that is not the image open in QuPath is named; the files stay where they are. */
    String notPlacedSentence(Map r, String what) {
        def target = applyToOf(r)
        String problem = (runSnapshot != null && target != null) ? runSnapshot.targetProblem(qupath, target as String) : null
        if (problem != null) {
            targetNote = problem
            LcLog.warn("results of run " + runSnapshot.runId + " not placed [" + LcRun.CODE_TARGET_UNAVAILABLE + "]: " + problem)      // the code of conformance host_internal_codes; the person reads the sentence
            return "Not placed on an image: " + problem
        }      // the image of this run is gone or changed (SPEC 4.4): the sentence names the run and the folder
        def chosen = target ? imageChoiceAtRun[target] : null
        def where = r.path ? " The " + what + " are in " + r.path : ""
        if (chosen == LcDialog.FILE_CHOICE) { resultNotes << LcConst.FILE_INPUT_NOTE; return "Not placed on an image: " + LcConst.FILE_INPUT_NOTE + "." + where }      // also in the status line
        return "Not placed on an image (the " + what + " were not found in the image open in QuPath)." + where
    }

    /** The sentence that tells the person the results were moved by the area's offset (empty for the whole image). */
    static String offsetNote(Map area) {
        return area == null ? "" : " Placed with the offset of the Image area (x " + area.x0 + ", y " + area.y0 + ")."
    }

    /** Points go on the image they were found in when that was the image open in QuPath; otherwise only the table is shown. */
    String placePoints(String appName, Map r) {
        def data = openImageFor(r)
        if (data == null)
        {
            LcLog.once("points-not-placed:" + appName + ":" + r.name, "points '" + r.name + "' not placed: they were not found in the image open in QuPath (table only)")
            return notPlacedSentence(r, "points")
        }
        def lines = new File(r.path as String).readLines("UTF-8")
        def header = splitCsv(lines[0])
        def yi = header.indexOf("y"), xi = header.indexOf("x")
        def area = areaFor(r)
        def ys = [], xs = []
        lines.drop(1).each { line ->          // pixel centres, moved by the Image area's offset
            def cells = splitCsv(line)
            ys << LcArea.slideY(area, (cells[yi] as double) + 0.5d)
            xs << LcArea.slideX(area, (cells[xi] as double) + 0.5d)
        }
        def name = appName + ":" + r.name
        def hierarchy = data.hierarchy
        def previous = hierarchy.annotationObjects.findAll { it.name == name }
        if (previous && LcRun.toolOf(this).outputs.any { it.name == r.name && it.replace }) hierarchy.removeObjects(previous, true)   // Replace()
        def obj = PathObjects.createAnnotationObject(ROIs.createPointsROI(xs as double[], ys as double[], placementPlane(area)))
        obj.name = name
        runSnapshot?.tag(obj)             // the run id, app and tool of THIS run (SPEC 4.5)
        hierarchy.addObject(obj)
        return xs.size() + " point(s) added to the open image as the annotation '" + name + "'." + offsetNote(area)
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
            return notPlacedSentence(r, "outlines")
        }
        def collection = new Gson().fromJson(new File(r.path as String).getText("UTF-8"), Map)
        def prefix = appName + ":" + r.name
        def area = areaFor(r)
        def built = shapeAnnotations(collection, prefix, area, placementPlane(area))
        def objects = built.objects, holes = built.holes, total = collection.features.size()
        def hierarchy = data.hierarchy
        def replacing = LcRun.toolOf(this).outputs.any { it.name == r.name && it.replace }
        if (replacing) {
            def previous = hierarchy.annotationObjects.findAll { it.name?.startsWith(prefix + " ") }
            if (previous) hierarchy.removeObjects(previous, true)       // Replace()
        }
        objects.each { runSnapshot?.tag(it) }
        hierarchy.addObjects(objects)
        def text = objects.size() + " outline(s) added to the open image as annotations named '" + prefix + " <label>'."
        if (total > LcConst.MAX_SHAPES) text += " '" + r.name + "': showing the first " + LcConst.MAX_SHAPES + " of " + total + " outlines."
        if (holes) text += " " + holes + " outline(s) have holes (kept)."
        text += offsetNote(area)
        return text
    }

    /** One annotation per GeoJSON feature (at most LcConst.MAX_SHAPES), and the number of polygon parts that have holes. */
    Map shapeAnnotations(Map collection, String prefix, Map area = null, ImagePlane plane = null) {
        def factory = new org.locationtech.jts.geom.GeometryFactory()
        def ring = { List points -> factory.createLinearRing(points.collect { new org.locationtech.jts.geom.Coordinate(LcArea.slideX(area, (it[0] as double) + 0.5d), LcArea.slideY(area, (it[1] as double) + 0.5d)) } as org.locationtech.jts.geom.Coordinate[]) }   // pixel centres, moved by the Image area's offset
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
            def obj = PathObjects.createAnnotationObject(qupath.lib.roi.GeometryTools.geometryToROI(shape, plane ?: LcArea.plane(area)))
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

    /** The first LcConst.TABLE_ROWS_SHOWN rows of a CSV file as a table; under it "table 'name' (N rows, first M shown)" (the Napari sentence) and the path. */
    static Node tableView(String csvPath, String name) {
        def csv = readCsv(csvPath, LcConst.TABLE_ROWS_SHOWN + 1)      // the header and the rows that are shown
        def rows = csv.rows
        if (rows.isEmpty()) return new Label("(empty table)")
        def table = new TableView<List<String>>()
        rows[0].eachWithIndex { String h, int i ->
            def col = new TableColumn<List<String>, String>(h)
            col.cellValueFactory = { cd -> new javafx.beans.property.SimpleStringProperty(i < cd.value.size() ? cd.value[i] : "") } as javafx.util.Callback
            table.columns.add(col)
        }
        rows.drop(1).each { table.items.add(it) }
        table.prefHeight = LcConst.TABLE_HEIGHT
        int shown = rows.size() - 1
        def counted = shown + csv.hidden
        return new VBox(4, table, new Label("table '" + name + "' (" + (csv.hidden ? counted + " rows, first " + shown + " shown; the full table is in " + csvPath : counted + " rows") + ")  " + csvPath))
    }

    /** A CSV file as records: [rows: the first `keep` records (cells as text), hidden: how many more there are]. A quoted cell may hold commas, doubled quotes and line breaks. */
    static Map readCsv(String csvPath, int keep) {
        def rows = [], row = [], cell = new StringBuilder()
        int hidden = 0
        boolean quoted = false, any = false
        def endRecord = {
            row << cell.toString(); cell.setLength(0)
            if (rows.size() < keep) rows << row else hidden++
            row = []; any = false
        }
        new File(csvPath).withReader("UTF-8") { reader ->
            int previous = -1
            int c = reader.read()
            for (; c != -1; c = reader.read()) {
                any = true
                if (quoted) {
                    if (c == 34) { quoted = false } else cell.append((char) c)
                } else if (c == 34 && previous == 34) {         // a doubled quote inside a quoted cell: one quote
                    cell.append('"'); quoted = true
                } else if (c == 34) { quoted = true }
                else if (c == 44) { row << cell.toString(); cell.setLength(0) }
                else if (c == 10) { endRecord() }
                else if (c != 13) cell.append((char) c)
                previous = c
            }
        }
        if (any) endRecord()
        return [rows: rows, hidden: hidden]
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
