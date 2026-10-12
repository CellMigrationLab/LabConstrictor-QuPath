// ================================================================== GUI test body: pixel size (QP-8) (appended to the tool script by tests/run_gui_test.sh)
// Needs the NucleiSky app (tool "Relocalize 2D") in LC_HOME (lchome_ns), the fixtures of LabConstrictor-Fiji and /home/tester/v-napari for tifffile.
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
        def dlg = fx { lcShow() }
        Thread.sleep(800)

        // ---- QP-8: the pixel-size field follows the chosen image or typed file until the person edits it; a file's header is read; non-square pixels are said
        def FXD = "/tmp/gtc/wp/fx"
        def um05 = makeImage(FXD + "/px_um05.tif", "tifffile.imwrite(OUTFILE, b, imagej=True, resolution=(2.0, 2.0), metadata={'unit': 'um'})")                // 0.5 um per pixel, ImageJ unit
        def um025 = makeImage(FXD + "/px_um025.tif", "tifffile.imwrite(OUTFILE, b, imagej=True, resolution=(4.0, 4.0), metadata={'unit': 'um'})")              // 0.25 um
        def aniso = makeImage(FXD + "/px_aniso.tif", "tifffile.imwrite(OUTFILE, b, imagej=True, resolution=(2.0, 4.0), metadata={'unit': 'um'})")              // X 0.5 um, Y 0.25 um
        def plain = makeImage(FXD + "/px_plain.tif", "tifffile.imwrite(OUTFILE, b)")                                                                              // no calibration
        def cm = makeImage(FXD + "/px_cm.tif", "tifffile.imwrite(OUTFILE, b, resolution=(10000.0, 10000.0), resolutionunit='CENTIMETER')")                       // 1 um per pixel by the TIFF unit
        def broken = FXD + "/px_broken.tif"; new File(broken).text = "this is text, not a TIFF"
        pick(dlg, "NucleiSky", "Relocalize 2D"); Thread.sleep(1500)
        def refPixel = { fx { dlg.getters["reference_pixel_size_um"]() } }
        def refFile = { String path -> fx { dlg.imageBoxes["reference"].value = LcDialog.FILE_CHOICE; dlg.fileFields["reference"].text = path } }
        def px = [name: "reference_pixel_size_um", pixel_size_of: "reference", label: "Reference pixel size"]
        expect("pixel_field_starts_at_its_default", refPixel() == 0.65d, refPixel())
        refFile(um05)
        expect("typed_file_header_fills_the_field", Math.abs(refPixel() - 0.5d) < 1e-6, refPixel())
        refFile(um025)
        expect("another_file_refills_an_untouched_field", Math.abs(refPixel() - 0.25d) < 1e-6, refPixel())
        refFile(cm)
        expect("a_centimetre_tiff_unit_is_read", Math.abs(refPixel() - 1.0d) < 1e-6, refPixel())
        refFile(plain)
        expect("a_file_without_calibration_leaves_the_value", Math.abs(refPixel() - 1.0d) < 1e-6, refPixel())
        // an edited value is kept when the image changes
        fx { dlg.setters["reference_pixel_size_um"](0.9d) }
        refFile(um05)
        expect("typed_value_is_not_overwritten_by_another_file", refPixel() == 0.9d, refPixel())
        fx { dlg.fillPixelSize(px) }
        expect("typed_value_is_not_overwritten_by_a_refill", refPixel() == 0.9d, refPixel())
        // the query field is independent
        def queryBefore = fx { dlg.getters["query_pixel_size_um"]() }
        expect("the_other_pixel_field_was_not_touched", queryBefore == 0.325d, queryBefore)
        // a field never edited follows again after the tool is chosen anew
        pick(dlg, "NucleiSky", "Relocalize 2D")
        fx { dlg.onTool() }; Thread.sleep(500)
        refFile(um025)
        expect("a_fresh_form_follows_the_file", Math.abs(refPixel() - 0.25d) < 1e-6, refPixel())
        // non-square pixels: said in the status line, with the Napari sentence; the tool gets X
        refFile(aniso)
        def st = status(dlg)
        println "STATUS_ANISO " + st
        expect("anisotropic_pixels_are_said", st.contains("pixel size differs: Y 0.25 µm, X 0.5 µm - the tool takes one value and gets X"), st)
        expect("anisotropic_pixels_send_x", Math.abs(refPixel() - 0.5d) < 1e-6, refPixel())
        refFile(um05)
        expect("square_pixels_say_nothing", !status(dlg).contains("pixel size differs") || status(dlg) == st, status(dlg))
        // a file that cannot be read: said, the field keeps its value
        fx { dlg.setters["reference_pixel_size_um"](0.5d) }
        fx { dlg.autoPixel["reference_pixel_size_um"] = 0.5d }
        refFile(broken)
        expect("unreadable_file_is_said_and_value_kept", status(dlg).startsWith("could not read the pixel size from " + broken) && status(dlg).endsWith("): type it") && refPixel() == 0.5d, status(dlg))
        expect("unreadable_file_is_logged", LcLog.recent.any { it.contains("pixel size of 'File...' could not be read") }, LcLog.recent)
        // a path still being typed (no such file yet) says nothing
        fx { dlg.status.text = "idle" }
        refFile(FXD + "/px_um0")
        expect("a_path_still_being_typed_is_silent", status(dlg) == "idle", status(dlg))

    } catch (Throwable t) { t.printStackTrace() }
    finally { println "RESULTS " + results; Thread.sleep(500); System.exit(0) }
}
