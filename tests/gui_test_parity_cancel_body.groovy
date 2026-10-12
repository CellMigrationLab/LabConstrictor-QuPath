// ================================================================== GUI test body: Cancel and a killed worker (QP-16) (appended to the tool script by tests/run_gui_test.sh)
// Needs the LabConstrictor Playground app in LC_HOME: its tool "Stress: a tool that ignores Cancel".
import javafx.embed.swing.SwingFXUtils
import javax.imageio.ImageIO
import java.util.concurrent.CountDownLatch
import java.awt.image.BufferedImage
import javafx.stage.Window

def OUT = new File(System.getProperty("lc.test.out", "/tmp/qp_shots")); OUT.mkdirs()
def FIXTURES = System.getProperty("lc.test.fixtures", new File("tests/fixtures").absolutePath)   // the sample images in this repo (the runner passes the absolute path)
def BLOBS = FIXTURES + "/blobs.tif"
// the registered `interactions` app gives the interpreter (python with tifffile and labconstrictor_tools) and the prefix of the synthetic apps; a body that runs in a scratch LC_HOME
// finds the registry that holds it in LC_SHARED_HOME (tests/run_gui_ci.sh sets it; LC_HOME is the registry itself for the other bodies)
def SHARED_HOME = new File(System.getenv("LC_SHARED_HOME") ?: System.getenv("LC_HOME"))
def INTERACTIONS = LcJson.parse(new File(SHARED_HOME, "apps/interactions.json"))
def PY = INTERACTIONS.python
def WP = new File(System.getProperty("java.io.tmpdir"), "lc_parity_scratch").absolutePath      // files the bodies write (images, fake interpreters, literals)
new File(WP).mkdirs()
def results = [:]
def fx = { Closure c ->
    if (Platform.isFxApplicationThread()) return c()
    def latch = new CountDownLatch(1); def box = [null, null]
    Platform.runLater { try { box[0] = c() } catch (Throwable t) { box[1] = t } finally { latch.countDown() } }
    latch.await()
    if (box[1] != null) throw box[1]
    return box[0]
}
def expect = { String name, boolean ok, Object detail = "" ->
    results[name] = ok
    println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : " -> " + detail))
}
def waitIdle = { dlg, int seconds = 300 ->
    def end = System.currentTimeMillis() + seconds * 1000
    while (System.currentTimeMillis() < end) { Thread.sleep(200); if (!dlg.running && fx { !dlg.runButton.disable }) return true }
    return false
}
def status = { dlg -> fx { dlg.status.text } }
def pick = { dlg, app, tool -> fx { dlg.appBox.value = app }; fx { dlg.toolBox.value = tool } }
def run = { dlg -> fx { dlg.run() }; Thread.sleep(1500); waitIdle(dlg, 120) }
def plane = qupath.lib.regions.ImagePlane.getDefaultPlane()
def rect = { double x, double y, double w, double h -> qupath.lib.objects.PathObjects.createAnnotationObject(qupath.lib.roi.ROIs.createRectangleROI(x, y, w, h, plane)) }
// a test image written next to the others: python code (tifffile) that writes the file at `path`
def makeImage = { String path, String code ->
    def f = new File(path); f.parentFile.mkdirs()
    if (!f.exists()) { def pr = [PY, "-c", "import tifffile, numpy as np\nb = tifffile.imread('" + BLOBS + "')\n" + code.replace("OUTFILE", "'" + path + "'")].execute(); assert pr.waitFor() == 0 : pr.err.text }
    return path
}
// opens an image of the test project (added when new) in QuPath's viewer
def openImage = { String file, String name ->
    fx {
        def project = qupath.project
        def entry = project.imageList.find { it.imageName == name }
        if (entry == null) { def s = qupath.lib.images.servers.ImageServers.buildServer(file); entry = project.addImage(s.getBuilder()); entry.setImageName(name); project.syncChanges() }
        qupath.openImageEntry(entry); entry
    }
    Thread.sleep(2000)
}

Thread.start("lc-gui-test") {
    try {
        def dlg = fx { lcShow() }
        Thread.sleep(800)

        // ---- QP-16: Cancel on a tool that ignores it ends the worker after the grace period, and the status says cancelled, not an out-of-memory crash
        def A = "LabConstrictorPlayground"
        pick(dlg, A, "Stress: a tool that ignores Cancel"); Thread.sleep(1000)
        fx { dlg.setters["seconds"](60d) }
        fx { dlg.run() }
        def started = System.currentTimeMillis() + 60000
        while (!status(dlg).contains("ignoring Cancel") && System.currentTimeMillis() < started) Thread.sleep(200)      // Cancel only means something once the tool is running (a worker that is still starting answers it itself)
        expect("hang_tool_is_running", fx { dlg.running } && status(dlg).contains("ignoring Cancel"), status(dlg))
        def pid = fx { dlg.worker.proc.pid() }
        fx { dlg.cancel() }
        expect("cancel_says_cancelling_at_once", status(dlg) == "cancelling...", status(dlg))
        expect("the_run_ends_after_the_grace_period", waitIdle(dlg, 60), status(dlg))
        def st = status(dlg)
        println "STATUS_AFTER_KILL " + st
        expect("killed_worker_after_cancel_reads_cancelled", st == "cancelled (worker stopped)", st)
        expect("no_out_of_memory_text_after_cancel", !st.contains("memory") && !st.contains("killed") && !st.contains("exit code") && !st.contains("crashed"), st)
        expect("the_worker_process_is_gone", !ProcessHandle.of(pid).map { it.isAlive() }.orElse(false), pid)
        expect("details_say_cancelled_not_crash", fx { dlg.lastReport.contains("CANCELATION") && !dlg.lastReport.contains("\"CRASH\"") }, fx { dlg.lastReport.take(200) })
        // the next run starts a new worker and works
        pick(dlg, A, "List the devices"); Thread.sleep(800)
        fx { dlg.run() }; Thread.sleep(2000)
        expect("next_run_works_with_a_new_worker", waitIdle(dlg, 120) && status(dlg).startsWith("done"), status(dlg))
        // a real crash is still a crash text (not cancelled)
        pick(dlg, A, "Stress: crash the worker"); Thread.sleep(800)
        fx { dlg.run() }; Thread.sleep(3000); waitIdle(dlg, 120)
        expect("a_crash_without_cancel_is_still_reported", status(dlg).startsWith("the worker stopped: ") && !status(dlg).contains("cancelled"), status(dlg))

    } catch (Throwable t) { t.printStackTrace(); results["EXCEPTION"] = false }
    finally { println "RESULTS " + results; Thread.sleep(500); System.exit(results && results.every { it.value } ? 0 : 1) }
}
