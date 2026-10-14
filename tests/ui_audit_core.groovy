// ================================================================== UI audit, part 1: settings, matrix, guarded checks (joined in front of gui_test_ui_audit_body.groovy by tests/run_gui_test.sh, see gui_test_ui_audit_body.parts)
// The driver of LabConstrictor-Tools docs/TEST_STRATEGY.md section 3, host `qupath`, one QuPath launch per phase (tests/run_ui_audit.sh). Every assertion runs in its own guarded block: an exception or a timeout is
// `CHECK <id> FAIL <Type: message>` and the audit goes on. Every check rewrites <LC_SHOTS>/matrix_qupath_<app>.json (temp file, then an atomic move): a crash or a kill leaves a complete record.
// The expectations come from sources that are NOT the state asserted: the Tools CLI schema and the case files (plan_qupath_<app>.json, made by tests/ui_audit_matrix.py), the registry entry on disk,
// the files a run writes, and the metadata of the fixture images read by QuPath's own readers.
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.stage.Stage
import javafx.stage.Window

/** The matrix of one app: rows are appended by every launch (the file is read back at the start) and rewritten after every check. Status: PASS, FAIL, NOT_RUN (expected, not executed: red), HUMAN (not automatable: listed for a person, never green). */
class UaMatrix {
    static final int DETAIL_CHARS = 500
    final String app
    final File file
    String started = stamp()
    String finished = null
    final List<Map> rows = Collections.synchronizedList(new ArrayList<Map>())
    final List<String> notes = Collections.synchronizedList(new ArrayList<String>())      // sentences that are no check: why a whole group of checks does not apply (an app that must not be listed)
    int failed = 0                                                                         // FAIL rows added by this launch

    UaMatrix(String app, File dir) {
        this.app = app
        this.file = new File(dir, "matrix_qupath_" + app + ".json")
        dir.mkdirs()
        if (file.isFile()) {
            Map old = (Map) LcJson.parse(file)
            started = old.started ?: started
            rows.addAll((List<Map>) old.rows)
            notes.addAll((List<String>) (old.notes ?: []))
        }
        write()
    }

    /** The time now, UTC, to the second. */
    static String stamp() { return java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString() }

    /** One line, at most 500 characters, no line break. */
    static String oneLine(String text) {
        String flat = (text ?: "").replaceAll(/\s+/, " ").trim()
        return flat.length() > DETAIL_CHARS ? flat.substring(0, DETAIL_CHARS - 3) + "..." : flat
    }

    /** Records one result and prints its line: `CHECK <id> PASS|FAIL <detail>`, or `NOTE <id> not run: <reason> [STATUS]` for NOT_RUN and HUMAN. */
    synchronized void add(String id, String tool, String control, String status, String detail, double seconds = 0d, String screenshot = null) {
        String text = oneLine(detail)
        if (status == "FAIL") failed++
        rows << [id: id, host: "qupath", app: app, tool: tool, control: control, status: status, detail: text, seconds: Math.round(seconds * 10d) / 10d, screenshot: screenshot]
        println(status in ["PASS", "FAIL"] ? "CHECK " + id + " " + status + " " + text : "NOTE " + id + " not run: " + text + " [" + status + "]")
        write()
    }

    /** A NOTE that is not a check: printed as `NOTE <id> not run: <reason>` and kept in the matrix under `notes`. */
    synchronized void note(String id, String reason) {
        notes << (id + ": " + oneLine(reason))
        println "NOTE " + id + " not run: " + oneLine(reason)
        write()
    }

    int count(String status) { return (int) rows.count { it.status == status } }

    /** Atomic rewrite: the matrix on disk is always a whole JSON document. */
    synchronized void write() {
        Map doc = [host: "qupath", app: app, started: started, finished: finished, notes: new ArrayList<String>(notes), rows: new ArrayList<Map>(rows)]
        Path target = file.toPath()
        Path temp = Files.createTempFile(target.parent, "matrix_qupath_", ".tmp")
        Files.write(temp, LcJson.pretty(doc).getBytes("UTF-8"))
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
    }
}

/** The settings of the launch (environment variables) and the helpers every part uses. */
class UiAudit {
    static File shots
    static File home
    static File testsDir
    static String app
    static String phase                    // panel | panel_run | tool | multi | not_listed
    static String toolId                   // phase tool: the tool of this launch
    static String seed = ""                // test-only seeded failures (ui_audit_seed.groovy)
    static String clickMode = "robot"      // robot: a real mouse click through the window (javafx.scene.robot); fire: ButtonBase.fire() (skips hit-testing)
    static Map plan = [:]                  // plan_qupath_<app>.json
    static int toolTimeout = 300
    static int controlTimeout = 60
    static UaMatrix current                // the matrix the checks of this launch go to
    static final List<String> unknown = Collections.synchronizedList(new ArrayList<String>())   // controls found that no expectation accounts for
    static final List<String> missing = Collections.synchronizedList(new ArrayList<String>())   // expected controls that were not found
    static final List<String> windowProblems = Collections.synchronizedList(new ArrayList<String>())
    static long startedMillis = System.currentTimeMillis()
    static Map<String, String> caseImages = [:]      // the fixture files opened as project images for the case in hand: path -> image name in the project
    static String firstImage = null                  // the one of them that is the image open in QuPath
    static int controlCount = 0            // the largest number of controls found in one scan

    static String env(String name, String fallback = null) {
        String value = System.getenv(name)
        if (value == null || value.isEmpty()) {
            if (fallback == null) throw new IllegalStateException("the environment variable " + name + " is not set")
            return fallback
        }
        return value
    }

    /** A name for ids and file names: lower case letters, digits and underscores. */
    static String sanitize(String text) { return (text ?: "").toLowerCase(Locale.ROOT).replaceAll(/[^a-z0-9]+/, "_").replaceAll(/^_+|_+$/, "") ?: "x" }

    /** Runs `work` on the JavaFX thread and gives back its result (or throws what it threw). */
    static Object fx(Closure work) {
        if (Platform.isFxApplicationThread()) return work()
        CountDownLatch done = new CountDownLatch(1)
        List box = [null, null]
        Platform.runLater { try { box[0] = work() } catch (Throwable t) { box[1] = t } finally { done.countDown() } }
        if (!done.await(controlTimeout + toolTimeout, TimeUnit.SECONDS)) throw new IllegalStateException("the JavaFX thread did not answer in " + (controlTimeout + toolTimeout) + " s")
        if (box[1] != null) throw (Throwable) box[1]
        return box[0]
    }

    /** Runs `work` on a thread of its own and waits at most `seconds`: a block that hangs is abandoned (the thread is a daemon) and reported. Returns the closure's String (the detail). */
    static String limited(int seconds, Closure<String> work) {
        List box = [null, null, false]
        Thread t = new Thread({ try { box[0] = work() } catch (Throwable e) { box[1] = e } finally { box[2] = true } } as Runnable, "lc-ui-audit-block")
        t.daemon = true
        t.start()
        t.join(seconds * 1000L)
        if (!box[2]) throw new IllegalStateException("timeout: no end after " + seconds + " s")
        if (box[1] != null) throw (Throwable) box[1]
        return (String) box[0]
    }

    static String describe(Throwable t) {
        Throwable root = t
        while (root.cause != null && root.cause != root) root = root.cause
        return t.class.simpleName + ": " + (t.message ?: "(no message)") + (root != t ? " [cause " + root.class.simpleName + ": " + root.message + "]" : "")
    }

    /** The tool of the plan (the independent schema) with this id. */
    static Map planTool(String id) { return (Map) ((List<Map>) plan.tools).find { it.id == id } }
}

/** Runs one assertion in its own guarded block and records PASS (the closure returned the detail) or FAIL (it threw: Type: message, or timed out). `onTimeout` cleans up (cancel the run, close the windows). */
boolean uaCheck(String id, String tool, String control, int timeout, Closure<String> work, Closure onTimeout = null, String screenshot = null) {
    long t0 = System.nanoTime()
    try {
        String detail = UiAudit.limited(timeout, work)
        UiAudit.current.add(id, tool, control, "PASS", detail ?: "ok", (System.nanoTime() - t0) / 1e9d, screenshot)
        return true
    } catch (Throwable t) {
        UiAudit.current.add(id, tool, control, "FAIL", UiAudit.describe(t), (System.nanoTime() - t0) / 1e9d, screenshot)
        if (t.message?.startsWith("timeout") && onTimeout != null) {
            try { onTimeout() } catch (Throwable cleanup) { println "CHECK " + id + "_cleanup FAIL the clean-up after the timeout failed: " + UiAudit.describe(cleanup) }
        }
        return false
    }
}

/** An assertion with a message: throws when `ok` is false (the message becomes the FAIL detail). */
void uaAssert(boolean ok, String message) {
    if (!ok) throw new AssertionError(message)
}

/** Opens a new LabConstrictor window the way lcShow does (the entry closure of the script is not visible from a method). */
Object uaOpenDialog() {
    Object dialog = UiAudit.fx { def d = new LcDialog(QuPathGUI.getInstance(), LcRegistry.load()); d.show(); d }
    Thread.sleep(800)
    return dialog
}

/** Closes every window that is not the main window of the dialog or of QuPath: result windows, Details, alerts. */
void uaCloseExtraWindows(Object dialog) {
    UiAudit.fx {
        Stage main = QuPathGUI.getInstance()?.stage
        new ArrayList<Window>(Window.getWindows()).each { Window w -> if (w instanceof Stage && w.showing && w != dialog.stage && w != main) w.hide() }
        null
    }
}

/** Waits until `test` is true (checked on the audit thread) or `seconds` pass; true when it became true. */
boolean uaWaitFor(int seconds, Closure<Boolean> test) {
    long end = System.currentTimeMillis() + seconds * 1000L
    while (System.currentTimeMillis() < end) {
        if (test()) return true
        Thread.sleep(150)
    }
    return test()
}

/** Activates a button, check box or radio button the way a person does: a real mouse click on its centre through the window system (javafx.scene.robot.Robot), or `fire()` when LC_UI_CLICK=fire.
 *  There is no fallback: a click that does nothing is the check's FAIL (the effect is asserted by the caller). */
void uaClick(javafx.scene.Node node) {
    if (UiAudit.clickMode == "fire") { UiAudit.fx { ((javafx.scene.control.ButtonBase) node).fire(); null }; return }
    UiAudit.fx {
        javafx.geometry.Bounds b = node.localToScreen(node.boundsInLocal)
        if (b == null || !node.scene?.window?.showing) throw new IllegalStateException("cannot click: the control is not on a showing window")
        new javafx.scene.robot.Robot().mouseMove(b.minX + b.width / 2d, b.minY + b.height / 2d)
        null
    }
    Thread.sleep(250)
    UiAudit.fx { new javafx.scene.robot.Robot().mouseClick(javafx.scene.input.MouseButton.PRIMARY); null }
    Thread.sleep(250)
}

/** A snapshot of a stage's scene as a PNG file; returns the file. */
File uaSnapshot(Stage stage, File target) {
    target.parentFile.mkdirs()
    Thread.sleep(600)      // a layout pass after the last change
    UiAudit.fx {
        def image = stage.scene.snapshot(null)
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", target)
        null
    }
    return target
}

/** Problems with a screenshot file as evidence for a person: it exists, was written after `since` (fresh), has a plausible size and is not one flat colour. This does NOT prove the picture is right. */
List<String> uaScreenshotProblems(File png, long since) {
    if (!png.isFile()) return ["missing: " + png]
    if (png.lastModified() < since) return ["stale (older than this run): " + png]
    if (png.length() < 1000L) return ["only " + png.length() + " bytes: " + png]
    def img = ImageIO.read(png)
    if (img == null) return ["not a readable PNG: " + png]
    if (img.width < 200 || img.height < 100) return ["implausibly small (" + img.width + "x" + img.height + "): " + png]
    int first = img.getRGB(0, 0)
    for (int y = 0; y < img.height; y++) for (int x = 0; x < img.width; x++) if (img.getRGB(x, y) != first) return []
    return ["one flat colour (" + Integer.toHexString(first) + "): " + png]
}
