// ================================================================== GUI test body: worker idle time, results folders, export bytes, shapes cap (QP-10, 12, 17, 18) (appended to the tool script by tests/run_gui_test.sh)
// Needs the `interactions` example app (copied into the scratch LC_HOME, a folder named lhome_life) and the blobs.tif fixture.
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
        def home = new File(System.getenv("LC_HOME")); assert home.path.contains("lhome_life")
        home.deleteDir(); def apps = new File(home, "apps"); apps.mkdirs()
        def srcApps = new File("/tmp/gtc/lchome_w3/apps")
        new File(apps, "interactions.schema.json").text = new File(srcApps, "interactions.schema.json").text
        new File(apps, "interactions.json").text = LcJson.toJson(LcJson.parse(new File(srcApps, "interactions.json")) + [schema_path: new File(apps, "interactions.schema.json").path])
        ["chmod", "644", new File(apps, "interactions.json").path, new File(apps, "interactions.schema.json").path].execute().waitFor()
        fx {
            def dir = new File("/tmp/qp_project_parity_lifecycle"); dir.deleteDir()
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

        def resultsRoot = new File(home, "results")
        def outlines = { fx { hierarchy().annotationObjects.findAll { it.name?.startsWith("interactions:outlines ") } } }
        pick(dlg, "interactions", "Outline the blobs"); Thread.sleep(1500)

        // ---- QP-18: a results folder is named like the command line's (<time>_<6 hex>_<app>_<tool>, names made safe), and only such folders are ever pruned
        expect("slug_unit", LcDialog.slug("../evil name") == "_evil_name" && LcDialog.slug("") == "x" && LcDialog.slug("..") == "x" && LcDialog.slug("a.b-c_d") == "a.b-c_d" && LcDialog.slug("My app/x") == "My_app_x")
        run(dlg)
        expect("run_completes", status(dlg).startsWith("done") && outlines().size() == 4, status(dlg))
        def folders = { (resultsRoot.listFiles() ?: []).findAll { it.isDirectory() }*.name.sort() }
        def pattern = ~/^\d{8}T\d{6}_[0-9a-f]{6}_interactions_outline_blobs$/
        expect("results_folder_name_has_time_6_hex_app_and_tool", folders().size() == 1 && folders()[0] ==~ pattern, folders())
        def odd = LcDialog.newJobDir("My app/x", "t:1/../z")
        expect("results_folder_name_is_made_safe", odd.name ==~ /^\d{8}T\d{6}_[0-9a-f]{6}_My_app_x_t_1_.._z$/ && odd.parentFile == resultsRoot && odd.isDirectory(), odd.name)
        // pruning: 25 old run folders, a foreign folder, a stray file, and a link that looks like a run folder
        def target = new File(home, "linktarget"); target.mkdirs(); new File(target, "precious.txt").text = "x"
        (1..25).each { new File(resultsRoot, String.format("20200101T0000%02d_abcdef_a_b", it)).mkdirs() }
        new File(resultsRoot, "keepme").mkdirs(); new File(resultsRoot, "keepme/notes.txt").text = "mine"
        new File(resultsRoot, "20200101T000000_stray.txt").text = "file"
        java.nio.file.Files.createSymbolicLink(new File(resultsRoot, "20200101T000099_link_x_y").toPath(), target.toPath())
        def job = LcDialog.newJobDir("a", "b")
        def runFolders = (resultsRoot.listFiles() ?: []).findAll { it.isDirectory() && !java.nio.file.Files.isSymbolicLink(it.toPath()) && it.name ==~ /^\d{8}T\d{6}_.*/ }
        expect("the_newest_20_run_folders_are_kept", runFolders.size() == 20 && runFolders.contains(job), runFolders.size())
        expect("the_oldest_run_folders_were_removed", !new File(resultsRoot, "20200101T000001_abcdef_a_b").exists() && new File(resultsRoot, "20200101T000025_abcdef_a_b").exists(), folders())
        expect("a_foreign_folder_and_a_stray_file_are_never_touched", new File(resultsRoot, "keepme/notes.txt").text == "mine" && new File(resultsRoot, "20200101T000000_stray.txt").exists(), folders())
        expect("a_link_named_like_a_run_folder_is_never_followed_or_removed", java.nio.file.Files.isSymbolicLink(new File(resultsRoot, "20200101T000099_link_x_y").toPath()) && new File(target, "precious.txt").exists())
        expect("two_calls_in_one_second_never_share_a_folder", LcDialog.newJobDir("a", "b") != LcDialog.newJobDir("a", "b"))

        // ---- QP-10: an export above 4 GiB (pixels x channels x bytes of the pixel type) is refused, like Napari; the pixel budget keeps working as before
        System.setProperty("lc.qupath.max_export_bytes", "4799")      // the fixture is 80 x 60 x 1 channel x 1 byte = 4800 bytes
        expect("byte_budget_comes_from_the_property", LcArea.byteBudget() == 4799L, LcArea.byteBudget())
        fx { hierarchy().clearAll() }
        run(dlg)
        def refused = status(dlg)
        println "STATUS_BYTES " + refused
        expect("one_byte_over_the_budget_is_refused", refused.startsWith("'Image': the image 'blobs.tif' is 0.0 GB (80 x 60 pixels x 1 channel(s) x 1 byte(s)); exporting more than 0 GB is not supported (system property lc.qupath.max_export_bytes)."), refused)
        expect("the_byte_refusal_names_both_ways_out", refused.contains("Image area: Selected annotation(s)") && refused.contains("smaller image or a file") && !refused.startsWith("failed"), refused)
        expect("the_byte_refusal_ran_no_tool", outlines().isEmpty(), outlines()*.name)
        System.setProperty("lc.qupath.max_export_bytes", "4800")
        run(dlg)
        expect("exactly_the_budget_runs", outlines().size() == 4, [status(dlg), outlines().size()])
        System.setProperty("lc.qupath.max_export_bytes", "not a number")
        expect("a_bad_byte_property_falls_back_to_4_gib", LcArea.byteBudget() == 4L * 1024 * 1024 * 1024 && LcLog.recent.any { it.contains("lc.qupath.max_export_bytes") && it.contains("not a whole number") }, LcArea.byteBudget())
        System.clearProperty("lc.qupath.max_export_bytes")
        expect("default_byte_budget_is_4_gib", LcArea.byteBudget() == 4294967296L, LcArea.byteBudget())
        def bigServer = { int channels, int bytes -> [nChannels: { -> channels }, pixelType: [bytesPerPixel: bytes], metadata: [name: "big.tif"], path: "big.tif"] }
        def bigArea = [kind: "Whole image", width: 10000, height: 10000]
        def refusal = null
        try { LcDialog.guardExportBytes([label: "Image"], bigServer(12, 4), bigArea, 12) } catch (LcRefusal e) { refusal = e.message }
        expect("a_multichannel_float_image_is_refused_in_gb_like_napari", refusal != null && refusal.startsWith("'Image': the image 'big.tif' is 4.5 GB (10000 x 10000 pixels x 12 channel(s) x 4 byte(s)); exporting more than 4 GB is not supported"), refusal)
        def fine = true
        try { LcDialog.guardExportBytes([label: "Image"], bigServer(12, 4), bigArea, 1) } catch (LcRefusal e) { fine = false }
        expect("one_channel_of_the_same_image_passes", fine)
        fine = true
        try { LcDialog.guardExportBytes([label: "Image"], bigServer(3, 1), bigArea, 3) } catch (LcRefusal e) { fine = false }
        expect("a_100_million_pixel_rgb_image_passes_the_byte_guard", fine)

        // ---- QP-12: a shapes result with more outlines than the cap says so in the Tools wording
        fx { hierarchy().clearAll() }
        def gj = new File(home, "many.geojson")
        def feature = { int i -> [type: "Feature", properties: [label: i + 1], geometry: [type: "Polygon", coordinates: [[[i % 70, i % 50], [i % 70 + 2, i % 50], [i % 70 + 2, i % 50 + 2], [i % 70, i % 50 + 2], [i % 70, i % 50]]]]] }
        gj.text = LcJson.toJson([type: "FeatureCollection", features: (0..<50001).collect { feature(it) }])
        def said = fx { dlg.placeShapes("interactions", [name: "outlines", type: "shapes", path: gj.path]) }
        println "SHAPES_SAID " + said
        expect("shapes_cap_sentence_is_the_tools_one", said.contains("'outlines': showing the first 50000 of 50001 outlines.") && !said.contains("Showing the first"), said)
        expect("shapes_cap_still_adds_50000", said.startsWith("50000 outline(s) added to the open image"), said)
        fx { hierarchy().clearAll() }

        // ---- QP-17: a kept worker that was idle for the idle time is closed; the next run starts a new one
        fx { dlg.idleSeconds = 3 }
        run(dlg)
        expect("idle_run_completes_and_keeps_the_worker", status(dlg).startsWith("done") && fx { dlg.worker != null && dlg.worker.proc.isAlive() }, status(dlg))
        def idlePid = fx { dlg.worker.proc.pid() }
        Thread.sleep(5000)
        expect("the_idle_worker_is_closed", fx { dlg.worker == null }, fx { dlg.worker })
        expect("the_idle_worker_process_is_gone", !ProcessHandle.of(idlePid).map { it.isAlive() }.orElse(false), idlePid)
        expect("closing_an_idle_worker_is_logged", LcLog.recent.any { it.contains("was idle for 3 s and was closed") }, LcLog.recent.takeRight(3))
        fx { hierarchy().clearAll() }
        run(dlg)
        expect("the_next_run_starts_a_new_worker", status(dlg).startsWith("done") && outlines().size() == 4 && fx { dlg.worker != null && dlg.worker.proc.pid() != idlePid }, status(dlg))
        // a run in progress is never closed, and the default idle time is 10 minutes
        expect("the_default_idle_time_is_600_s", LcConst.WORKER_IDLE_SECONDS == 600 && new LcDialog(null, [apps: [:], problems: []]).idleSeconds == 600)
        fx { dlg.keepWorker.selected = false }
        run(dlg)
        expect("a_worker_that_is_not_kept_is_closed_after_the_run_as_before", Thread.sleep(1500) == null && fx { dlg.worker == null }, fx { dlg.worker })

    } catch (Throwable t) { t.printStackTrace() }
    finally { println "RESULTS " + results; Thread.sleep(500); System.exit(0) }
}
