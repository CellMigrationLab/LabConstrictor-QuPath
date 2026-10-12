// ================================================================== GUI test body: shared log and run records (QP-19, QP-20) (appended to the tool script by tests/run_gui_test.sh)
// Needs the `interactions` example app (copied into the scratch LC_HOME, a folder named lhome_support), the blobs.tif fixture and /home/tester/v-napari (the command line labconstrictor-tools reads what is written).
import javafx.embed.swing.SwingFXUtils
import javax.imageio.ImageIO
import java.util.concurrent.CountDownLatch
import java.awt.image.BufferedImage
import javafx.stage.Window

def OUT = new File(System.getProperty("lc.test.out", "/tmp/qp_shots")); OUT.mkdirs()
def BLOBS = "/home/user/LabConstrictor-Fiji/tests/fixtures/blobs.tif"
def THREE = "/home/user/LabConstrictor-Fiji/tests/fixtures/three_channels.tif"
def PY = "/home/tester/v-napari/bin/python"
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
        // a scratch LC_HOME with a copy of the `interactions` example app (its results, log and runs go to the scratch folder)
        def home = new File(System.getenv("LC_HOME")); assert home.path.contains("lhome_support")
        home.deleteDir(); def apps = new File(home, "apps"); apps.mkdirs()
        def srcApps = new File("/tmp/gtc/lchome_w3/apps")
        new File(apps, "interactions.schema.json").text = new File(srcApps, "interactions.schema.json").text
        new File(apps, "interactions.json").text = LcJson.toJson(LcJson.parse(new File(srcApps, "interactions.json")) + [schema_path: new File(apps, "interactions.schema.json").path])
        ["chmod", "644", new File(apps, "interactions.json").path, new File(apps, "interactions.schema.json").path].execute().waitFor()
        fx {
            def dir = new File("/tmp/qp_project_parity_support"); dir.deleteDir()
            qupath.lib.gui.prefs.PathPrefs.imageTypeSettingProperty().set(qupath.lib.gui.prefs.PathPrefs.ImageTypeSetting.AUTO_ESTIMATE)
            def project = qupath.lib.projects.Projects.createProject(dir, BufferedImage)
            def server = qupath.lib.images.servers.ImageServers.buildServer(BLOBS)
            def entry = project.addImage(server.getBuilder()); entry.setImageName("blobs.tif")
            project.syncChanges(); qupath.setProject(project); qupath.openImageEntry(entry); entry
        }
        Thread.sleep(2500)
        def dlg = fx { lcShow() }
        Thread.sleep(800)
        def hierarchy = { fx { qupath.imageData.hierarchy } }

        def logFile = new File(home, "logs/labconstrictor.log")
        def runsDir = new File(home, "runs")
        def PYTHON = "/home/tester/v-napari/bin/python"
        def lcTools = { List args ->      // the command line of labconstrictor-tools, on the same LC_HOME
            def pb = new ProcessBuilder([PYTHON, "-m", "labconstrictor_tools"] + args); pb.environment().put("LC_HOME", home.path); pb.redirectErrorStream(true)
            def pr = pb.start(); def out = pr.inputStream.getText("UTF-8"); pr.waitFor(); out
        }
        def lines = { logFile.exists() ? logFile.readLines("UTF-8") : [] }

        // ---- QP-20: QuPath's log lines also go to the shared log file, in the format of labconstrictor_tools.log, with its rotation
        LcLog.warn("parity: first warning")
        LcLog.once("parity-once", "parity: an intended fallback")
        LcLog.once("parity-once", "parity: an intended fallback")
        LcLog.warn("parity: with a trace", new IllegalStateException("boom"))
        def mine = lines().findAll { it.contains("qupath: parity:") }
        def lineFormat = ~/^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3} (WARNING|INFO)\s* pid=\d+ qupath: parity: .*$/
        expect("shared_log_file_is_written", logFile.isFile(), logFile)
        expect("a_line_has_the_tools_format", mine.every { it ==~ lineFormat } && mine[0] ==~ /^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3} WARNING pid=\d+ qupath: parity: first warning$/, mine)
        expect("an_intended_fallback_is_an_info_line_and_written_once", mine.count { it.contains("an intended fallback") } == 1 && mine.find { it.contains("an intended fallback") }.contains(" INFO    pid="), mine)
        expect("level_names_are_padded_like_python", mine.find { it.contains("an intended fallback") } ==~ /^.{23} INFO    pid=\d+ qupath: .*$/, mine)
        expect("a_stack_trace_follows_its_line", lines().any { it.contains("java.lang.IllegalStateException: boom") } && lines().indexOf(mine.find { it.contains("with a trace") }) < lines().findIndexOf { it.contains("java.lang.IllegalStateException: boom") })
        def seen = lcTools(["logs"])
        expect("labconstrictor_tools_logs_shows_the_quPath_lines", seen.contains("qupath: parity: first warning") && seen.contains("qupath: parity: an intended fallback"), seen.take(300))
        // rotation: at 1 MB the log becomes .log.1 and the older backups move up; five backups are kept
        (1..5).each { new File(logFile.parentFile, "labconstrictor.log." + it).text = "backup " + it + "\n" }
        logFile.text = "x" * 1_000_000 + "\n"
        LcLog.warn("parity: after rotation")
        def backup = { int i -> new File(logFile.parentFile, "labconstrictor.log." + i) }
        expect("rotation_starts_a_fresh_log", lines().size() == 1 && lines()[0].endsWith("qupath: parity: after rotation"), lines().size())
        expect("rotation_makes_the_old_log_backup_1", backup(1).length() > 1_000_000, backup(1).length())
        expect("rotation_moves_the_older_backups_up", backup(2).text == "backup 1\n" && backup(3).text == "backup 2\n" && backup(4).text == "backup 3\n" && backup(5).text == "backup 4\n", [backup(2).text, backup(5).text])
        expect("the_oldest_backup_is_replaced_and_there_is_no_sixth", !backup(6).exists())

        // ---- QP-19: every run that reached the worker leaves a record under <LC_HOME>/runs (the format of labconstrictor_tools.runs), the newest 50 are kept
        pick(dlg, "interactions", "List the shapes"); Thread.sleep(1200)
        def before = (runsDir.listFiles() ?: []).size()
        run(dlg)
        expect("the_run_completes", status(dlg).startsWith("done"), status(dlg))
        def records = (runsDir.listFiles() ?: []).findAll { it.isDirectory() }
        expect("one_record_folder_per_run_named_like_tools", records.size() == before + 1 && records.any { it.name ==~ /^\d{8}T\d{12}_interactions_list_shapes$/ }, records*.name)
        def rec = LcJson.parse(new File(records.find { it.name.endsWith("_interactions_list_shapes") }, "run.json"))
        println "RECORD KEYS " + rec.keySet()
        def toolsKeys = ["app", "app_version", "tool", "finished_utc", "seconds", "status", "error", "code", "traceback", "inputs", "results", "timings", "interpreter", "stderr_tail"]
        expect("the_record_has_every_key_of_the_tools_record", rec.keySet().containsAll(toolsKeys) && rec.host == "qupath", rec.keySet())
        expect("the_record_says_what_ran_and_how_it_ended", rec.app == "interactions" && rec.tool == "list_shapes" && rec.status == "COMPLETE" && rec.seconds instanceof Number && rec.finished_utc.endsWith("Z"), rec)
        expect("the_record_keeps_results_but_not_the_host_folder", rec.results instanceof List && !rec.results.isEmpty() && !rec.inputs.containsKey("_job_dir"), [rec.results, rec.inputs])
        expect("the_record_keeps_the_interpreter_diagnostics_and_timings", rec.interpreter != null && rec.timings != null, [rec.interpreter, rec.timings])
        // other endings, written directly: failure with its code and traceback, cancellation, crash; the big matrix is left out
        def folderOf = { Map outcome -> LcRuns.record([entry: [version: "1.2"], app: "interactions", tool: "x/y z", inputs: [a: 1L, _job_dir: "/tmp/j"], outcome: outcome, seconds: 1.234, stderr: "e" * 5000]) }
        def failed = LcJson.parse(new File(folderOf([responseType: "FAILURE", error: "[bad_parameter] no", code: "bad_parameter", trace: "Traceback (most recent call last)"]), "run.json"))
        expect("a_failed_run_has_status_code_and_traceback", failed.status == "FAILED" && failed.code == "bad_parameter" && failed.error == "[bad_parameter] no" && failed.traceback == "Traceback (most recent call last)" && failed.app_version == "1.2" && failed.seconds == 1.23d, failed)
        expect("a_record_keeps_the_last_4000_characters_of_the_worker_output", failed.stderr_tail.size() == 4000, failed.stderr_tail.size())
        expect("a_cancelled_run_is_canceled_and_a_crash_is_crashed", LcJson.parse(new File(folderOf([responseType: "CANCELATION"]), "run.json")).status == "CANCELED" && LcJson.parse(new File(folderOf([responseType: "CRASH", error: "x"]), "run.json")).status == "CRASHED")
        def withMatrix = LcJson.parse(new File(folderOf([responseType: "COMPLETION", outputs: [results: [[type: "affine", matrix_yx: [[1, 0, 0]], name: "m"]]]]), "run.json"))
        expect("the_matrix_is_not_kept_in_a_record", withMatrix.results[0].name == "m" && !withMatrix.results[0].containsKey("matrix_yx"), withMatrix.results)
        expect("the_tool_name_is_made_safe_in_the_folder_name", runsDir.list().any { it.endsWith("_interactions_x_y_z") }, runsDir.list())
        // a refusal never reached the worker: no record
        pick(dlg, "interactions", "Outline the blobs"); Thread.sleep(1200)
        fx { dlg.areaBoxes["image"].value = "Selected annotation(s)" }
        fx { hierarchy().selectionModel.clearSelection() }
        def count = (runsDir.listFiles() ?: []).size()
        run(dlg)
        expect("a_refusal_writes_no_record", status(dlg).startsWith("'Image': no annotation is selected") && (runsDir.listFiles() ?: []).size() == count, status(dlg))
        // pruning: 60 old records, a foreign folder and a link
        (1..60).each { new File(runsDir, String.format("20200101T0000%02d000000_a_b", it)).mkdirs() }
        new File(runsDir, "notes").mkdirs()
        def kept = LcRuns.record([entry: [:], app: "interactions", tool: "t", inputs: [:], outcome: [responseType: "COMPLETION"], seconds: 0d, stderr: ""])
        def left = (runsDir.listFiles() ?: []).findAll { it.isDirectory() && it.name ==~ /^\d{8}T\d{6,12}_.*/ }
        expect("the_newest_50_records_are_kept", left.size() == 50 && left.contains(kept), left.size())
        expect("a_foreign_folder_under_runs_is_not_touched", new File(runsDir, "notes").isDirectory())
        def zip = new File(home, "bundle.zip")
        def wrote = lcTools(["support-bundle", "--out", zip.path])
        def listing = new ProcessBuilder([PYTHON, "-c", "import sys, zipfile\nprint('\\n'.join(zipfile.ZipFile(sys.argv[1]).namelist()))", zip.path]).redirectErrorStream(true).start().with { def s = it.inputStream.getText("UTF-8"); it.waitFor(); s }
        println "BUNDLE " + wrote.take(120) + " | " + listing.readLines().findAll { it.contains("run") || it.contains("log") }.take(6)
        expect("support_bundle_contains_the_log_and_the_quPath_run_records", zip.isFile() && listing.contains("labconstrictor.log") && listing.readLines().any { it.contains("runs/") && it.endsWith("_interactions_list_shapes/run.json") }, listing.take(400))

    } catch (Throwable t) { t.printStackTrace() }
    finally { println "RESULTS " + results; Thread.sleep(500); System.exit(0) }
}
