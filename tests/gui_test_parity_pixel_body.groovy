// ================================================================== GUI test body: pixel size (QP-8) (appended to the tool script by tests/run_gui_test.sh)
// Updated for SPEC_WRONG_SCIENCE section 2 (calibration): the field is unset/auto/user (a flag set by the person's edits), an unknown source clears it, a declared default is
// never a stand-in, pixels that are not square are unknown with a warning; the full CAL-01..20 / CAL-T1..T9 cases are in gui_test_calibration_body.groovy.
// Needs no real app: it registers a synthetic app (only its schema is used) in the scratch LC_HOME (a folder named lhome_pixel); the registered `interactions` app (LC_SHARED_HOME) gives the Python that writes the test images (tifffile).
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
        // a synthetic app with the pixel-size fields of a two-image tool (only its schema is used: the form is built, no tool is run)
        def home = new File(System.getenv("LC_HOME")); assert home.path.contains("lhome_pixel")
        home.deleteDir(); def apps = new File(home, "apps"); apps.mkdirs()
        def P = { String name, String type, Map more = [:] -> [name: name, label: name.capitalize().replace("_", " "), type: type, required: more.required != false] + more }
        new File(apps, "pixelapp.schema.json").text = LcJson.toJson([protocol: 1, tools: [[id: "relocalize", label: "Relocalize 2D", description: "", outputs: [], inputs: [
            P("reference", "image"), P("query", "image"),
            P("reference_pixel_size_um", "float", [default: 0.65, pixel_size_of: "reference", unit: "um"]), P("query_pixel_size_um", "float", [default: 0.325, pixel_size_of: "query", unit: "um"])]]]])
        new File(apps, "pixelapp.json").text = LcJson.toJson([schema: 1, name: "pixelapp", display_name: "pixelapp", version: "", prefix: INTERACTIONS.prefix, python: PY,
                module: "labconstrictor_tools.examples.interactions", pythonpath: [], runtime_path: "", schema_path: new File(apps, "pixelapp.schema.json").path])
        ["chmod", "644", new File(apps, "pixelapp.json").path, new File(apps, "pixelapp.schema.json").path].execute().waitFor()
        def dlg = fx { lcShow() }
        Thread.sleep(800)

        // ---- QP-8: the pixel-size field follows the chosen image or typed file until the person edits it; a file's header is read; non-square pixels are said
        def FXD = WP + "/fx"
        def um05 = makeImage(FXD + "/px_um05.tif", "tifffile.imwrite(OUTFILE, b, imagej=True, resolution=(2.0, 2.0), metadata={'unit': 'um'})")                // 0.5 um per pixel, ImageJ unit
        def um025 = makeImage(FXD + "/px_um025.tif", "tifffile.imwrite(OUTFILE, b, imagej=True, resolution=(4.0, 4.0), metadata={'unit': 'um'})")              // 0.25 um
        def aniso = makeImage(FXD + "/px_aniso.tif", "tifffile.imwrite(OUTFILE, b, imagej=True, resolution=(2.0, 4.0), metadata={'unit': 'um'})")              // X 0.5 um, Y 0.25 um
        def plain = makeImage(FXD + "/px_plain.tif", "tifffile.imwrite(OUTFILE, b)")                                                                              // no calibration
        def cm = makeImage(FXD + "/px_cm.tif", "tifffile.imwrite(OUTFILE, b, resolution=(10000.0, 10000.0), resolutionunit='CENTIMETER')")                       // 1 um per pixel by the TIFF unit
        def broken = FXD + "/px_broken.tif"; new File(broken).text = "this is text, not a TIFF"
        pick(dlg, "pixelapp", "Relocalize 2D"); Thread.sleep(1500)
        def refPixel = { fx { dlg.getters["reference_pixel_size_um"]() } }
        def refField = { fx { dlg.controls["reference_pixel_size_um"] } }
        // the person types: select all, then one key event per character (a host write through the setters is not an edit)
        def typeInto = { String name, String text ->
            def f = fx { dlg.controls[name] }
            fx { f.requestFocus(); f.selectAll(); null }
            text.each { ch -> fx { javafx.event.Event.fireEvent(f, new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_TYPED, ch.toString(), ch.toString(), javafx.scene.input.KeyCode.UNDEFINED, false, false, false, false)); null } }
            Thread.sleep(150)
        }
        def refFile = { String path -> fx { dlg.imageBoxes["reference"].value = LcDialog.FILE_CHOICE; dlg.fileFields["reference"].text = path } }
        def px = [name: "reference_pixel_size_um", pixel_size_of: "reference", label: "Reference pixel size"]
        expect("pixel_field_starts_unset_not_at_its_declared_default", refPixel() == null && dlg.links["reference_pixel_size_um"].state == "unset", refPixel())      // was: starts at its default 0.65 (CAL-T8: a default is no stand-in)
        refFile(um05)
        expect("typed_file_header_fills_the_field", Math.abs(refPixel() - 0.5d) < 1e-6, refPixel())
        refFile(um025)
        expect("another_file_refills_an_untouched_field", Math.abs(refPixel() - 0.25d) < 1e-6, refPixel())
        refFile(cm)
        expect("a_centimetre_tiff_unit_is_read", Math.abs(refPixel() - 1.0d) < 1e-6, refPixel())
        refFile(plain)
        expect("a_file_without_calibration_clears_the_field", refPixel() == null && refField().text == "", [refPixel(), refField().text])      // was: leaves the value (the value of another image must not stay, CAL-T5)
        // an edited value is kept when the image changes
        typeInto("reference_pixel_size_um", "0.9")
        refFile(um05)
        expect("typed_value_is_not_overwritten_by_another_file", refPixel() == 0.9d, refPixel())
        fx { dlg.fillPixelSize(px) }
        expect("typed_value_is_not_overwritten_by_a_refill", refPixel() == 0.9d, refPixel())
        // the query field is independent: nothing was chosen for it, so it is unset (its declared default 0.325 is no stand-in)
        def queryBefore = fx { dlg.getters["query_pixel_size_um"]() }
        expect("the_other_pixel_field_was_not_touched", queryBefore == null, queryBefore)
        // a field never edited follows again after the tool is chosen anew
        pick(dlg, "pixelapp", "Relocalize 2D")
        fx { dlg.onTool() }; Thread.sleep(500)
        refFile(um025)
        expect("a_fresh_form_follows_the_file", Math.abs(refPixel() - 0.25d) < 1e-6, refPixel())
        // non-square pixels: said in the status line, with the Napari sentence; the tool gets X
        refFile(aniso)
        def st = status(dlg)
        println "STATUS_ANISO " + st
        expect("anisotropic_pixels_are_said", st.contains("pixels are not square"), st)      // was: "pixel size differs: Y 0.25 µm, X 0.5 µm - the tool takes one value and gets X"
        expect("anisotropic_pixels_leave_the_field_unset", refPixel() == null, refPixel())      // was: the field gets X (CAL-06: never filled from one axis)
        refFile(um05)
        expect("square_pixels_say_nothing", !status(dlg).contains("pixels are not square") || status(dlg) == st, status(dlg))
        // a file that cannot be read: said, the field is unset (the value of another source must not stay)
        refFile(broken)
        expect("unreadable_file_is_said_and_the_field_is_unset", status(dlg).startsWith("could not read the pixel size from " + broken) && status(dlg).endsWith("): type it") && refPixel() == null, status(dlg))      // was: "... and value kept"
        expect("unreadable_file_is_logged", LcLog.recent.any { it.contains("pixel size of 'File...' could not be read") }, LcLog.recent)
        // a path still being typed (no such file yet) says nothing
        fx { dlg.status.text = "idle" }
        refFile(FXD + "/px_um0")
        expect("a_path_still_being_typed_is_silent", status(dlg) == "idle", status(dlg))

    } catch (Throwable t) { t.printStackTrace(); results["EXCEPTION"] = false }
    finally { println "RESULTS " + results; Thread.sleep(500); System.exit(results && results.every { it.value } ? 0 : 1) }
}
