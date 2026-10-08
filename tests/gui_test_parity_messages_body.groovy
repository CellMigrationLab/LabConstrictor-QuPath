// ================================================================== GUI test body: messages and dimensions (QP-2, QP-9) (appended to the tool script by tests/run_gui_test.sh)
// Needs the `interactions` example app of labconstrictor-tools in LC_HOME (lchome_w3), the fixtures of LabConstrictor-Fiji and /home/tester/v-napari for tifffile.
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
        fx {
            def dir = new File("/tmp/qp_project_parity_messages"); dir.deleteDir()
            qupath.lib.gui.prefs.PathPrefs.imageTypeSettingProperty().set(qupath.lib.gui.prefs.PathPrefs.ImageTypeSetting.AUTO_ESTIMATE)
            def project = qupath.lib.projects.Projects.createProject(dir, BufferedImage)
            def server = qupath.lib.images.servers.ImageServers.buildServer(makeImage("/tmp/gtc/wp/fx/zstack.tif", "tifffile.imwrite(OUTFILE, np.stack([b, b // 2, b // 3]), imagej=True, metadata={'axes': 'ZYX'})"))
            def entry = project.addImage(server.getBuilder()); entry.setImageName("zstack.tif")
            project.syncChanges(); qupath.setProject(project); qupath.openImageEntry(entry); entry
        }
        Thread.sleep(2500)
        def dlg = fx { lcShow() }
        Thread.sleep(800)
        def hierarchy = { fx { qupath.imageData.hierarchy } }

        // ---- QP-2: a refusal made by the host reads as the sentence alone (no "failed:", no class name, no internal code)
        pick(dlg, "interactions", "Outline the blobs"); Thread.sleep(1500)
        fx { dlg.areaBoxes["image"].value = "Selected annotation(s)" }
        fx { hierarchy().selectionModel.clearSelection() }
        run(dlg)
        def refused = status(dlg)
        println "STATUS_REFUSED " + refused
        expect("refusal_is_the_sentence", refused.startsWith("'Image': no annotation is selected for the Image area"), refused)
        expect("refusal_has_no_failed_prefix", !refused.startsWith("failed"), refused)
        expect("refusal_has_no_class_name_or_code", !refused.contains("IllegalStateException") && !refused.contains("LcRefusal") && !refused.contains("host_error") && !refused.contains("host_refusal"), refused)
        expect("refusal_starts_no_worker", fx { dlg.worker == null })
        expect("refusal_is_logged_without_a_stack_trace", LcLog.recent.any { it.contains("refused: 'Image': no annotation is selected") && !it.contains("IllegalState") }, LcLog.recent)
        expect("refusal_has_no_trace_in_details", fx { !dlg.lastReport.contains("\"trace\"") }, fx { dlg.lastReport.take(300) })
        fx { dlg.areaBoxes["image"].value = "Whole image" }

        // ---- QP-9: a stack given to a tool that declares Axes("YX") is refused, with the Tools sentence (wrong_dimensions)
        // (the z-stack is the image of the project: a second Bio-Formats image opened in the same session crashes QuPath natively in this environment)
        def outlines = { fx { hierarchy().annotationObjects.findAll { it.name?.startsWith("interactions:outlines ") } } }
        expect("zstack_is_open_with_three_planes", fx { qupath.imageData.server.nZSlices() } == 3, fx { qupath.imageData.server.nZSlices() })
        fx { hierarchy().clearAll() }
        run(dlg)
        def zs = status(dlg)
        println "STATUS_ZSTACK " + zs
        expect("zstack_is_refused_wrong_dimensions", zs.startsWith("'Image' must be a 2D image (YX) but got 3D with shape (3, 60, 80)"), zs)
        expect("zstack_refusal_names_the_way_out", zs.contains("3 z-planes") && zs.contains("Image area: Current viewport"), zs)
        expect("zstack_ran_no_tool", outlines().isEmpty() && fx { dlg.worker == null }, outlines()*.name)
        // the way out: an area names the plane (the one on screen) and the run goes through
        fx { dlg.areaBoxes["image"].value = "Current viewport" }
        run(dlg)
        expect("zstack_viewport_area_runs", status(dlg).startsWith("done") && outlines().size() == 4, [status(dlg), outlines().size()])
        fx { hierarchy().clearAll(); dlg.areaBoxes["image"].value = "Whole image" }
        // a time series (a fake server of 2 time points: opening a second stack in one session makes QuPath's Bio-Formats reader crash in this environment)
        def fake = { int z, int t, int c -> [nZSlices: { -> z }, nTimepoints: { -> t }, nChannels: { -> c }, height: 60, width: 80] }
        def refusalOf = { Map p, Map server, Map area -> try { LcDialog.guardPlanes(p, server, area); null } catch (LcRefusal e) { e.message } }
        def whole = LcArea.whole([width: 80, height: 60])
        def ts = refusalOf([label: "Image", axes: "YX"], fake(1, 2, 1), whole)
        expect("tstack_is_refused_too", ts != null && ts.startsWith("'Image' must be a 2D image (YX) but got 3D with shape (2, 60, 80)") && ts.contains("2 time points"), ts)
        def both = refusalOf([label: "Image", axes: "YX"], fake(3, 2, 4), whole)
        expect("z_t_and_channels_are_all_counted", both != null && both.contains("got 5D with shape (2, 3, 4, 60, 80)") && both.contains("3 z-planes and 2 time points"), both)
        // a tool that declares ZYX would take a z-stack, one that declares TYX a series: nothing in the host refuses on its behalf; areas and tools without axes are not checked
        expect("a_declared_z_axis_is_not_refused", refusalOf([label: "Image", axes: "ZYX"], fake(3, 1, 1), whole) == null)
        expect("a_declared_t_axis_is_not_refused", refusalOf([label: "Image", axes: "TYX"], fake(1, 2, 1), whole) == null)
        expect("a_tool_without_axes_is_not_checked", refusalOf([label: "Image"], fake(3, 2, 1), whole) == null)
        expect("an_area_names_the_plane_so_no_refusal", refusalOf([label: "Image", axes: "YX"], fake(3, 1, 1), whole + [kind: LcArea.VIEWPORT]) == null)
        expect("a_single_plane_image_is_not_refused", refusalOf([label: "Image", axes: "YX"], fake(1, 1, 3), whole) == null)

    } catch (Throwable t) { t.printStackTrace() }
    finally { println "RESULTS " + results; Thread.sleep(500); System.exit(0) }
}
