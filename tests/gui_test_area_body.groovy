// ================================================================== GUI test body: Image area and size guard (appended to the tool script by tests/run_gui_test.sh)
// Needs the `interactions` example app of labconstrictor-tools in LC_HOME (tools "Outline the blobs" and "Find bright spots") and the blobs.tif fixture
// (80 x 60, four blobs). The size guard is triggered with a small budget set through the system property lc.qupath.max_export_pixels (set and cleared here).
import javafx.embed.swing.SwingFXUtils
import javax.imageio.ImageIO
import java.util.concurrent.CountDownLatch
import java.awt.image.BufferedImage
import javafx.stage.Window

def OUT = new File(System.getProperty("lc.test.out", "/tmp/qp_shots")); OUT.mkdirs()
def BLOBS = "/home/user/LabConstrictor-Fiji/tests/fixtures/blobs.tif"
def PROP = "lc.qupath.max_export_pixels"
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
def shot = { String name, Stage st ->
    Thread.sleep(600)
    fx { ImageIO.write(SwingFXUtils.fromFXImage(st.scene.snapshot(null), null), "png", new File(OUT, name + ".png")) }
    println "SHOT " + name
}
def waitIdle = { dlg, int seconds = 300 ->
    def end = System.currentTimeMillis() + seconds * 1000
    while (System.currentTimeMillis() < end) { Thread.sleep(200); if (!dlg.running && fx { !dlg.runButton.disable }) return true }
    return false
}
def status = { dlg -> fx { dlg.status.text } }
def pick = { dlg, app, tool -> fx { dlg.appBox.value = app }; fx { dlg.toolBox.value = tool } }
def plane = qupath.lib.regions.ImagePlane.getDefaultPlane()
def rect = { double x, double y, double w, double h -> qupath.lib.objects.PathObjects.createAnnotationObject(qupath.lib.roi.ROIs.createRectangleROI(x, y, w, h, plane)) }
def run = { dlg -> fx { dlg.run() }; Thread.sleep(1500); waitIdle(dlg, 120) }
// the pixels of an image file (a TIFF the host exported, or the fixture): [width, height, closure (x, y) -> sample 0]
def pixelsOf = { String path ->
    def s = qupath.lib.images.servers.ImageServers.buildServer(path)
    def raster = s.readRegion(RegionRequest.createInstance(s.path, 1.0d, 0, 0, s.width, s.height)).raster
    [s.width, s.height, { int x, int y -> raster.getSample(x, y, 0) }]
}
// blobs of the fixture found independently of the host: 4-connected components of the pixels >= half of the maximum -> [minX, maxX, minY, maxY, centre x, centre y] (pixel centres are at +0.5)
def knownBlobs = {
    def (w, h, at) = pixelsOf(BLOBS)
    int peak = 0
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) peak = Math.max(peak, at(x, y))
    def seen = new boolean[w * h], blobs = []
    for (int y0 = 0; y0 < h; y0++) for (int x0 = 0; x0 < w; x0++) {
        if (seen[y0 * w + x0] || at(x0, y0) < 0.5 * peak) continue
        def stack = [[x0, y0]], xs = [], ys = []
        seen[y0 * w + x0] = true
        while (stack) {
            def (x, y) = stack.pop()
            xs << x; ys << y
            [[1, 0], [-1, 0], [0, 1], [0, -1]].each { d ->
                int nx = x + d[0], ny = y + d[1]
                if (nx >= 0 && ny >= 0 && nx < w && ny < h && !seen[ny * w + nx] && at(nx, ny) >= 0.5 * peak) { seen[ny * w + nx] = true; stack << [nx, ny] }
            }
        }
        blobs << [minX: xs.min(), maxX: xs.max(), minY: ys.min(), maxY: ys.max(), cx: xs.sum() / xs.size() + 0.5d, cy: ys.sum() / ys.size() + 0.5d]
    }
    blobs
}

Thread.start("lc-gui-test") {
    try {
        fx {
            def dir = new File("/tmp/qp_project_area"); dir.deleteDir()
            qupath.lib.gui.prefs.PathPrefs.imageTypeSettingProperty().set(qupath.lib.gui.prefs.PathPrefs.ImageTypeSetting.AUTO_ESTIMATE)
            def project = qupath.lib.projects.Projects.createProject(dir, BufferedImage)
            def server = qupath.lib.images.servers.ImageServers.buildServer(BLOBS)
            def entry = project.addImage(server.getBuilder()); entry.setImageName("blobs.tif")
            project.syncChanges(); qupath.setProject(project); qupath.openImageEntry(entry); entry
        }
        Thread.sleep(2500)
        def dlg = fx { lcShow() }
        Thread.sleep(800)
        def hierarchy = fx { qupath.imageData.hierarchy }
        def server = fx { qupath.imageData.server }
        def blobs = knownBlobs()
        expect("fixture_has_four_blobs", blobs.size() == 4 && server.width == 80 && server.height == 60, [blobs.size(), server.width, server.height])
        pick(dlg, "interactions", "Outline the blobs"); Thread.sleep(1500)
        def outlines = { fx { hierarchy.annotationObjects.findAll { it.name?.startsWith("interactions:outlines ") } } }
        def clearAll = { fx { hierarchy.clearAll() } }
        def areaBox = { String name -> fx { dlg.areaBoxes[name] } }
        def setArea = { String name, String kind -> fx { dlg.areaBoxes[name].value = kind } }
        // what run() does before it starts the worker, for tests that look at the exported files
        def prepare = { Map values ->
            fx { dlg.imageChoiceAtRun = dlg.imageBoxes.collectEntries { k, b -> [(k): b.value as String] } }
            def tmp = java.nio.file.Files.createTempDirectory("lcarea_").toFile()
            [dlg.prepareInputs(values, dlg.currentTool.inputs, tmp, "interactions", "test"), tmp]
        }

        // ---- the control
        expect("area_control_defaults_to_whole_image", fx { dlg.areaBoxes["image"].value == "Whole image" && !dlg.areaBoxes["image"].disable && (dlg.areaBoxes["image"].items as List) == ["Whole image", "Selected annotation(s)", "Current viewport"] }, fx { dlg.areaBoxes["image"].value })
        shot("qp_area_1_form", dlg.stage)

        // ---- (a) the size guard
        System.setProperty(PROP, "1000")      // the fixture has 4800 pixels
        expect("budget_comes_from_the_property", LcArea.budget() == 1000L, LcArea.budget())
        fx { dlg.worker = null }
        def jobsBefore = new File(LcRegistry.searchPath().first().parentFile, "results").list()?.length ?: 0
        run(dlg)
        def refused = status(dlg)
        println "STATUS_REFUSED " + refused
        expect("guard_refuses_over_the_budget", refused.startsWith("failed:") && refused.contains("above the limit of 1000 pixels"), refused)
        expect("guard_names_image_size_and_both_ways_out", refused.contains("blobs.tif") && refused.contains("80 x 60 = 4800 pixels") && refused.contains("Image area: Selected annotation(s)") && refused.contains("smaller image or a file"), refused)
        expect("guard_ran_no_tool", fx { dlg.worker == null } && outlines().isEmpty() && (new File(LcRegistry.searchPath().first().parentFile, "results").list()?.length ?: 0) == jobsBefore, "")
        System.setProperty(PROP, "4800")      // exactly the size of the image: allowed
        run(dlg)
        expect("a_size_at_the_budget_runs", outlines().size() == 4, [status(dlg), outlines()*.name])
        System.setProperty(PROP, "4799")
        clearAll()
        run(dlg)
        expect("one_pixel_over_the_budget_is_refused", status(dlg).contains("above the limit of 4799 pixels") && outlines().isEmpty(), status(dlg))
        System.setProperty(PROP, "not a number")
        expect("a_bad_property_falls_back_to_the_default", LcArea.budget() == LcConst.MAX_EXPORT_PIXELS && LcLog.recent.any { it.contains(PROP) && it.contains("not a whole number") }, LcArea.budget())
        System.setProperty(PROP, "-5")
        expect("a_non_positive_property_falls_back_to_the_default", LcArea.budget() == LcConst.MAX_EXPORT_PIXELS, LcArea.budget())
        System.clearProperty(PROP)
        expect("default_budget_is_100_million", LcArea.budget() == 100_000_000L, LcArea.budget())
        // a size under the budget with an area: the guard counts the area, not the image
        System.setProperty(PROP, "2100")      // the area below has 42 x 50 = 2100 pixels, the image 4800
        def A = rect(28, 2, 42, 50)
        fx { hierarchy.addObject(A); hierarchy.selectionModel.setSelectedObject(A) }
        setArea("image", "Whole image")
        run(dlg)
        expect("whole_image_over_the_budget_refused", status(dlg).contains("above the limit of 2100 pixels"), status(dlg))
        setArea("image", "Selected annotation(s)")
        run(dlg)
        expect("area_under_the_budget_runs", outlines().size() == 3, [status(dlg), outlines()*.name])
        System.clearProperty(PROP)

        // ---- (b) the exported image of a selected-annotation area is exactly the bounding box, with the pixels of the server region
        clearAll()
        fx { hierarchy.addObject(A); hierarchy.selectionModel.setSelectedObject(A) }
        setArea("image", "Selected annotation(s)")
        def values = fx { dlg.readFormValues() }
        expect("value_carries_the_area", values?.image?.area == "Selected annotation(s)", values)
        def (inputs, tmp) = prepare(values)
        def (ew, eh, exported) = pixelsOf(inputs.image as String)
        expect("exported_image_has_the_bounding_box_size", ew == 42 && eh == 50, [ew, eh])
        def region = server.readRegion(RegionRequest.createInstance(server.path, 1.0d, 28, 2, 42, 50)).raster
        def whole = pixelsOf(BLOBS)[2]
        boolean same = true
        for (int y = 0; y < 50; y++) for (int x = 0; x < 42; x++) same &= exported(x, y) == region.getSample(x, y, 0) && exported(x, y) == whole(x + 28, y + 2)
        expect("exported_pixels_equal_the_server_region", same, "")
        tmp.deleteDir()
        // fractional and out-of-image bounds: widened to whole pixels, clamped to the image, plane kept
        def odd = rect(-10.5, 3.25, 20.5, 100)
        fx { hierarchy.clearAll(); hierarchy.addObject(odd); hierarchy.selectionModel.setSelectedObject(odd) }
        def oddArea = dlg.resolveArea([label: "Image"], "Selected annotation(s)", server)
        expect("area_is_widened_and_clamped", oddArea.x0 == 0 && oddArea.y0 == 3 && oddArea.width == 10 && oddArea.height == 57 && oddArea.downsample == 1.0d && oddArea.z == 0 && oddArea.t == 0, oddArea)
        def twoAreas = fx { hierarchy.clearAll(); def p1 = rect(30, 30, 20, 20), p2 = rect(58, 3, 8, 10); hierarchy.addObjects([p1, p2]); hierarchy.selectionModel.setSelectedObjects([p1, p2], null); null }
        def bothArea = dlg.resolveArea([label: "Image"], "Selected annotation(s)", server)
        expect("several_annotations_give_one_box", bothArea.x0 == 30 && bothArea.y0 == 3 && bothArea.width == 36 && bothArea.height == 47, bothArea)
        // the region maths as a unit
        def fake = [width: 80, height: 60]
        def u1 = LcArea.clamped("x", [10.4d, 3.2d, 20.1d, 9.9d], fake, 2, 3)
        expect("unit_clamped_widens_to_whole_pixels", u1.x0 == 10 && u1.y0 == 3 && u1.width == 11 && u1.height == 7 && u1.z == 2 && u1.t == 3, u1)
        expect("unit_clamped_clamps_to_the_image", LcArea.clamped("x", [-5d, -5d, 200d, 200d], fake, 0, 0).with { [x0, y0, width, height] } == [0, 0, 80, 60], "")
        expect("unit_clamped_outside_is_null", LcArea.clamped("x", [100d, 100d, 120d, 120d], fake, 0, 0) == null && LcArea.clamped("x", [5d, 5d, 5d, 9d], fake, 0, 0) == null, "")
        expect("unit_slide_coordinates_add_the_offset", LcArea.slideX(u1, 2.5d) == 12.5d && LcArea.slideY(u1, 2.5d) == 5.5d && LcArea.slideX(null, 2.5d) == 2.5d, "")
        def noSel = null
        fx { hierarchy.selectionModel.clearSelection() }
        try { dlg.resolveArea([label: "Image"], "Selected annotation(s)", server) } catch (IllegalStateException e) { noSel = e.message }
        expect("no_selection_is_said", noSel != null && noSel.contains("no annotation is selected") && noSel.contains("Whole image"), noSel)
        def noViewer = null
        try { LcDialog.viewportArea("Image", null, server, 0, 0) } catch (IllegalStateException e) { noViewer = e.message }
        expect("no_viewer_is_said", noViewer != null && noViewer.contains("no viewer shows the image"), noViewer)

        // ---- (c) the offset: outlines of the tool run on the area land at the blobs' places in full-image coordinates
        clearAll()
        fx { hierarchy.addObject(A); hierarchy.selectionModel.setSelectedObject(A) }
        setArea("image", "Selected annotation(s)")
        run(dlg)
        println "STATUS_AREA_RUN " + status(dlg)
        def found = outlines()
        def inside = blobs.findAll { it.minX >= 28 && it.maxX < 70 && it.minY >= 2 && it.maxY < 52 }
        expect("area_run_made_one_outline_per_blob_in_the_area", found.size() == 3 && inside.size() == 3, [found*.name, inside.size()])
        def centres = found.collect { [it.ROI.centroidX, it.ROI.centroidY] }
        println "OUTLINE CENTRES " + centres + " EXPECTED " + inside.collect { [it.cx, it.cy] }
        expect("outlines_are_at_the_blobs_in_full_image_coordinates", inside.every { b -> centres.any { Math.abs(it[0] - b.cx) <= 0.75 && Math.abs(it[1] - b.cy) <= 0.75 } } && found.size() == inside.size(), [centres, inside.collect { [it.cx, it.cy] }])
        expect("outlines_bounds_match_the_blobs", inside.every { b -> found.any { Math.abs(it.ROI.boundsX - b.minX) <= 1 && Math.abs(it.ROI.boundsY - b.minY) <= 1 && Math.abs(it.ROI.boundsWidth - (b.maxX - b.minX + 1)) <= 1 } }, found.collect { [it.ROI.boundsX, it.ROI.boundsY, it.ROI.boundsWidth] })
        expect("area_run_completes", status(dlg).startsWith("done"), status(dlg))
        shot("qp_area_2_area_run", dlg.stage)
        // whole image again: no offset, all four blobs, same coordinates convention
        setArea("image", "Whole image")
        run(dlg)
        def all = outlines()
        def allCentres = all.collect { [it.ROI.centroidX, it.ROI.centroidY] }
        expect("whole_image_run_is_unchanged", all.size() == 4 && blobs.every { b -> allCentres.any { Math.abs(it[0] - b.cx) <= 0.75 && Math.abs(it[1] - b.cy) <= 0.75 } }, allCentres)

        // ---- (d) the viewport area
        def viewer = fx { qupath.viewer }
        def viewerSize = fx { [viewer.view.width, viewer.view.height] }
        println "VIEWER SIZE " + viewerSize
        fx { viewer.setDownsampleFactor(0.1d); viewer.setCenterPixelLocation(60d, 40d) }
        Thread.sleep(1200)
        def shape = fx { viewer.displayedRegionShape.bounds2D }
        def expectedView = LcArea.clamped("x", [shape.minX, shape.minY, shape.maxX, shape.maxY], server, 0, 0)
        println "VIEWPORT SHAPE " + [shape.minX, shape.minY, shape.maxX, shape.maxY] + " EXPECTED " + expectedView
        if (expectedView != null && expectedView.width < 80 && expectedView.height <= 60) {
            clearAll()
            setArea("image", "Current viewport")
            def vv = fx { dlg.readFormValues() }
            def (vin, vtmp) = prepare(vv)
            def (vw, vh, vat) = pixelsOf(vin.image as String)
            expect("viewport_export_has_the_viewport_size", vw == expectedView.width && vh == expectedView.height && vw < 80, [vw, vh, expectedView])
            def vok = true
            for (int y = 0; y < vh; y++) for (int x = 0; x < vw; x++) vok &= vat(x, y) == whole(x + expectedView.x0, y + expectedView.y0)
            expect("viewport_export_pixels_equal_the_slide_region", vok, "")
            vtmp.deleteDir()
            run(dlg)
            def vo = outlines()
            expect("viewport_outlines_land_inside_the_viewport_in_full_coordinates",
                    vo.size() >= 1 && vo.every { it.ROI.boundsX >= expectedView.x0 - 0.01 && it.ROI.boundsX + it.ROI.boundsWidth <= expectedView.x0 + expectedView.width + 0.01 && it.ROI.boundsY >= expectedView.y0 - 0.01 && it.ROI.boundsY + it.ROI.boundsHeight <= expectedView.y0 + expectedView.height + 0.01 } &&
                    blobs.findAll { it.minX >= expectedView.x0 && it.maxX < expectedView.x0 + expectedView.width && it.minY >= expectedView.y0 && it.maxY < expectedView.y0 + expectedView.height }.every { b -> vo.any { Math.abs(it.ROI.centroidX - b.cx) <= 0.75 && Math.abs(it.ROI.centroidY - b.cy) <= 0.75 } },
                    [status(dlg), vo.collect { [it.ROI.boundsX, it.ROI.boundsY] }, expectedView])
        } else {
            println "NOTE the viewer under xvfb shows no partial region (" + expectedView + "); the viewport export is NOT exercised, only the region maths above"
            expect("viewport_could_not_be_exercised_headless_unit_maths_tested", false, expectedView)
        }
        fx { viewer.setDownsampleFactor(1d) }
        setArea("image", "Whole image")

        // ---- (e) RegionOf with an area: the label mask has the area's size and the labels land right; points of the run are in full coordinates
        pick(dlg, "interactions", "Find bright spots"); Thread.sleep(1500)
        clearAll()
        def P = rect(30, 30, 20, 20), Q = rect(58, 3, 8, 10)       // P is blob 4 with a margin of none, Q holds blob 2: bounding box x 30..66, y 3..50
        fx { hierarchy.addObjects([P, Q]); hierarchy.selectionModel.setSelectedObjects([P, Q], null) }
        fx { dlg.selectionBoxes["region"].selected = true }
        expect("region_has_no_own_area_while_ticked", fx { dlg.areaBoxes["region"].disable }, "")
        setArea("image", "Selected annotation(s)")
        def rv = fx { dlg.readFormValues() }
        def (rin, rtmp) = prepare(rv)
        def (mw, mh, mat) = pixelsOf(rin.region as String)
        def (iw, ih, iat) = pixelsOf(rin.image as String)
        expect("mask_has_the_area_size_and_matches_the_image", mw == 36 && mh == 47 && iw == 36 && ih == 47, [mw, mh, iw, ih])
        int lp = mat(40 - 30, 40 - 3), lq = mat(61 - 30, 8 - 3)
        def counts = [:]
        for (int y = 0; y < mh; y++) for (int x = 0; x < mw; x++) { int v = mat(x, y); counts[v] = (counts[v] ?: 0) + 1 }
        expect("mask_labels_land_on_the_annotations", lp != 0 && lq != 0 && lp != lq && counts[lp] == 400 && counts[lq] == 80 && counts[0] == 36 * 47 - 480 && mat(0, 27) == lp && mat(19, 46) == lp && mat(20, 46) == 0 && mat(28, 0) == lq && mat(35, 9) == lq && mat(36 - 1, 10) == 0, [lp, lq, counts])
        rtmp.deleteDir()
        run(dlg)
        println "STATUS_REGION " + status(dlg)
        def spots = fx { hierarchy.annotationObjects.findAll { it.name == "interactions:spots" } }
        def pts = spots.collectMany { it.ROI.allPoints }
        expect("region_run_with_an_area_succeeds_and_places_points", spots.size() == 1 && pts.size() > 0 && !status(dlg).startsWith("failed"), [status(dlg), spots.size(), pts.size()])
        expect("points_are_inside_the_selected_annotations_in_full_coordinates", pts.every { (it.x >= 30 && it.x < 50 && it.y >= 30 && it.y < 50) || (it.x >= 58 && it.x < 66 && it.y >= 3 && it.y < 13) }, pts.take(8).collect { [it.x, it.y] })
        // whole image with the selection (as before): the mask has the size of the image
        setArea("image", "Whole image")
        def (wv) = [fx { dlg.readFormValues() }]
        def (win, wtmp) = prepare(wv)
        expect("region_without_an_area_is_the_whole_image", pixelsOf(win.region as String)[0] == 80 && pixelsOf(win.region as String)[1] == 60, "")
        wtmp.deleteDir()
        // the 100 M cap is on the area: a mask over the area is allowed where the whole image would not be (checked by constants, not by a big image)
        expect("mask_cap_is_the_region_constant", LcConst.MAX_REGION_PIXELS == 100_000_000L, LcConst.MAX_REGION_PIXELS)
        fx { hierarchy.selectionModel.clearSelection() }
        setArea("image", "Selected annotation(s)")
        run(dlg)
        expect("area_without_selection_is_refused_with_the_reason", status(dlg).contains("no annotation is selected"), status(dlg))

        // ---- (f) differing areas for two image inputs are refused
        fx { dlg.selectionBoxes["region"].selected = false }
        fx { hierarchy.selectionModel.setSelectedObjects([P, Q], null) }
        fx { dlg.imageBoxes["region"].value = dlg.imageBoxes["image"].value }       // the labels input is the open image too
        setArea("image", "Selected annotation(s)")
        setArea("region", "Current viewport")
        run(dlg)
        println "STATUS_DIFFERING " + status(dlg)
        expect("differing_areas_are_refused", status(dlg).contains("use different areas") && status(dlg).contains("only one offset"), status(dlg))
        setArea("region", "Whole image")
        run(dlg)
        expect("an_area_next_to_a_whole_image_is_refused", status(dlg).contains("use different areas") && status(dlg).contains("the whole image"), status(dlg))
        setArea("region", "Selected annotation(s)")
        def (sv) = [fx { dlg.readFormValues() }]
        def (sin, stmp) = prepare(sv)
        expect("the_same_area_for_both_is_accepted", pixelsOf(sin.image as String)[0] == 36 && pixelsOf(sin.region as String)[0] == 36 && pixelsOf(sin.region as String)[1] == 47, "")
        stmp.deleteDir()

        // ---- project image and file: the area is the whole image (control disabled, tooltip says why)
        fx { dlg.imageBoxes["image"].value = "Project: blobs.tif" }
        expect("project_image_has_no_area_choice", fx { dlg.areaBoxes["image"].disable && dlg.areaBoxes["image"].value == "Whole image" && dlg.areaBoxes["image"].tooltip.text.contains("project image or a file") }, fx { dlg.areaBoxes["image"].tooltip.text })
        fx { dlg.imageBoxes["image"].value = "File..." }
        expect("file_has_no_area_choice", fx { dlg.areaBoxes["image"].disable }, "")
        fx { dlg.imageBoxes["image"].value = dlg.imageBoxes["image"].items.first() }
        expect("open_image_has_the_area_choice_again", fx { !dlg.areaBoxes["image"].disable }, "")

        // ---- copy as command: a note, logged once
        fx { dlg.imageBoxes["region"].value = "(none)" }
        setArea("image", "Selected annotation(s)")
        def line = fx { dlg.copyAsCommand("terminal") }
        expect("copy_notes_the_area", line.contains("# image: QuPath sent only the Image area 'Selected annotation(s)'") && line.contains("the command sends the whole file"), line)
        fx { dlg.copyAsCommand("terminal") }
        expect("copy_area_fallback_logged_once", LcLog.recent.count { it.startsWith("copy as command: the Image area") } == 1, LcLog.recent.findAll { it.contains("Image area") })
        shot("qp_area_3_end", dlg.stage)
    } catch (Throwable t) { t.printStackTrace() }
    finally { println "RESULTS " + results; Thread.sleep(500); System.exit(0) }
}
