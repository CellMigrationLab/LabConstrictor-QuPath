// ================================================================== GUI test body: coordinate frame (SPEC_WRONG_SCIENCE section 1, cases CF-01..CF-14 of conformance.json)
// (appended to the tool script by tests/run_gui_test.sh). Needs the `frame` test app (tests/frame_app, registered by tests/ci_setup_apps.sh): its tool reports what it RECEIVED
// (image shape and pixels, mask shape and pixels) and returns a point, an outline and a labels image, so every case checks the real export and the real drawn coordinates.
// The cases are read from the conformance file of labconstrictor-tools (LC_CONFORMANCE, else `python -m labconstrictor_tools.conformance --path` of the registered app):
// ASYM (8 rows x 12 columns, value 100*y + x) is tests/fixtures/asym.tif, asym_zt.tif is the same image on 2 time points x 2 z-planes (value + 1000*z + 10000*t).
// QuPath frame: pixel i spans [i, i+1), the centre of pixel i is at i + 0.5; the fixture's vertices ([y, x], centres at integers) are therefore drawn at vertex + 0.5 (spec 1.1.3).
import javafx.embed.swing.SwingFXUtils
import javax.imageio.ImageIO
import java.util.concurrent.CountDownLatch
import java.awt.image.BufferedImage
import javafx.stage.Window

def OUT = new File(System.getProperty("lc.test.out", "/tmp/qp_shots")); OUT.mkdirs()
def FIXTURES = System.getProperty("lc.test.fixtures", new File("tests/fixtures").absolutePath)
def ASYM = FIXTURES + "/asym.tif", ASYM_ZT = FIXTURES + "/asym_zt.tif"
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
def PL = { int z, int t -> qupath.lib.regions.ImagePlane.getPlane(z, t) }
// a polygon annotation from vertices given as [y, x] (the axis order of the fixture's `rect`), on plane (z, t)
def polygon = { List yx, int z = 0, int t = 0 ->
    qupath.lib.objects.PathObjects.createAnnotationObject(qupath.lib.roi.ROIs.createPolygonROI(yx.collect { new qupath.lib.geom.Point2(it[1] as double, it[0] as double) }, PL(z, t)))
}
def rectXY = { double x0, double y0, double x1, double y1, int z = 0, int t = 0 -> qupath.lib.objects.PathObjects.createAnnotationObject(qupath.lib.roi.ROIs.createRectangleROI(x0, y0, x1 - x0, y1 - y0, PL(z, t))) }
// the conformance file: the case data come from labconstrictor-tools, not from this test
def SHARED_HOME = new File(System.getenv("LC_SHARED_HOME") ?: System.getenv("LC_HOME"))
def FRAME_APP = LcJson.parse(new File(SHARED_HOME, "apps/frame.json"))
def confPath = System.getenv("LC_CONFORMANCE") ?: [FRAME_APP.python, "-m", "labconstrictor_tools.conformance", "--path"].execute().text.trim()
def CONF = LcJson.parse(new File(confPath))
def CF = CONF.wrong_science.coordinate
def cfCase = { String id -> def c = CF.cases.find { it.id == id }; assert c != null : "conformance.json has no case " + id; c }

Thread.start("lc-gui-test") {
    try {
        // ---- the data this test stands on exists (no silent skip when a key is missing)
        expect("conformance_has_the_fixture_and_all_cases", CF.image.id == "ASYM" && CF.image.shape == [8, 12] && CF.rect.size() == 4 && (1..14).every { n -> cfCase(String.format("CF-%02d", n)) != null }, CF.keySet())
        expect("host_codes_are_the_conformance_codes", [LcConst.CODE_SELECTION_MIXED_PLANES, LcConst.CODE_SELECTION_PLANE_MISMATCH].every { it in CONF.host_internal_codes }, CONF.host_internal_codes)
        expect("sentences_are_the_conformance_sentences", LcConst.FILE_INPUT_NOTE == CONF.messages.file_input_note.template && LcConst.SIZE_MISMATCH_TEMPLATE == CONF.messages.size_mismatch.template, [CONF.messages.file_input_note, CONF.messages.size_mismatch])
        // the fixture files are the fixture image
        def readPlane = { String path, int z, int t ->
            def s = qupath.lib.images.servers.ImageServers.buildServer(path)
            def raster = s.readRegion(qupath.lib.regions.RegionRequest.createInstance(s.path, 1.0d, 0, 0, s.width, s.height, z, t)).raster
            [s.width, s.height, { int x, int y -> raster.getSample(x, y, 0) }, s]
        }
        def (aw, ah, aat, asymServer) = readPlane(ASYM, 0, 0)
        boolean asymOk = aw == 12 && ah == 8
        for (int y = 0; y < 8; y++) for (int x = 0; x < 12; x++) asymOk &= aat(x, y) == 100 * y + x
        expect("asym_fixture_is_the_conformance_image", asymOk, [aw, ah])
        def (zw, zh, zat, ztServer) = readPlane(ASYM_ZT, 1, 1)
        expect("asym_zt_fixture_has_two_z_and_two_t", ztServer.nZSlices() == 2 && ztServer.nTimepoints() == 2 && zat(4, 2) == 204 + 1000 + 10000, [ztServer.nZSlices(), ztServer.nTimepoints()])

        fx {
            def dir = new File(System.getProperty("java.io.tmpdir"), "qp_project_frame"); dir.deleteDir()
            qupath.lib.gui.prefs.PathPrefs.imageTypeSettingProperty().set(qupath.lib.gui.prefs.PathPrefs.ImageTypeSetting.AUTO_ESTIMATE)
            def project = qupath.lib.projects.Projects.createProject(dir, BufferedImage)
            [["asym.tif", ASYM], ["asym_zt.tif", ASYM_ZT]].each { n, f ->
                def s = qupath.lib.images.servers.ImageServers.buildServer(f); def e = project.addImage(s.getBuilder()); e.setImageName(n)
            }
            project.syncChanges(); qupath.setProject(project)
        }
        def openImage = { String name ->
            fx { qupath.imageData?.setChanged(false); qupath.openImageEntry(qupath.project.imageList.find { it.imageName == name }); null }       // unsaved annotations would ask "save changes?" and wait for a click
            Thread.sleep(2500)
            fx { qupath.imageData.hierarchy.clearAll(); null }
        }
        openImage("asym.tif")
        def dlg = fx { lcShow() }
        Thread.sleep(800)
        def newForm = { pick(dlg, "frame", "Frame probe"); fx { dlg.onTool() }; Thread.sleep(800) }
        newForm()
        def hierarchy = { fx { qupath.imageData.hierarchy } }
        def annotationsNamed = { String prefix -> fx { hierarchy().annotationObjects.findAll { it.name?.startsWith(prefix) } } }
        def userAnnotations = { fx { hierarchy().annotationObjects.findAll { !it.name?.startsWith("frame:") } } }
        def select = { List objs -> fx { hierarchy().clearAll(); hierarchy().addObjects(objs); hierarchy().selectionModel.setSelectedObjects(objs, null); null } }
        def setPlane = { int z, int t -> fx { qupath.viewer.setZPosition(z); qupath.viewer.setTPosition(t); null }; Thread.sleep(300) }
        def setPoint = { double y, double x -> fx { dlg.setters["y"](y); dlg.setters["x"](x); null } }
        def setArea = { String kind -> fx { dlg.areaBoxes["image"].value = kind; null } }
        def useSelection = { boolean on -> fx { dlg.selectionBoxes["region"].selected = on; null } }
        def seen = { def report = (dlg.lastReport as String).split("\n\nworker output:")[0]; LcJson.parseText(report).outputs?.results?.find { it.type == "values" }?.values }
        def reportOf = { LcJson.parseText((dlg.lastReport as String).split("\n\nworker output:")[0]) }
        def points = { List<String> names = null -> annotationsNamed("frame:marks").collectMany { it.ROI.allPoints } }
        def resultsDir = new File(LcRegistry.home(), "results")
        def jobCount = { resultsDir.list()?.length ?: 0 }
        def near = { double a, double b -> Math.abs(a - b) < 1e-9 }
        def sortedPairs = { List l -> l.collect { [it[0] as int, it[1] as int] }.sort { a, b -> a[0] <=> b[0] ?: a[1] <=> b[1] } }
        // QuPath draws the fixture (centres at integers) half a pixel further: pixel i spans [i, i+1)
        def rectQP = CF.rect.collect { [it[0] + 0.5d, it[1] + 0.5d] }

        // ---- CF-01 identity: the worker receives ASYM and the rect mask; the point, the outline and nothing else are drawn on that image
        def cf1 = cfCase("CF-01")
        select([polygon(rectQP)]); useSelection(true); setPoint(cf1.point[0], cf1.point[1])
        def jobsBefore = jobCount()
        run(dlg)
        def s1 = seen()
        println "CF-01 status: " + status(dlg)
        expect("CF-01_worker_got_ASYM_whole", s1 != null && s1.image_shape == [8, 12] && s1.image_first == 0 && s1.image_last == 711, s1)
        expect("CF-01_mask_pixels_are_the_conformance_pixels", s1 != null && s1.mask_shape == [8, 12] && sortedPairs(s1.mask_pixels) == sortedPairs(CF.rect_mask_pixels), s1?.mask_pixels)
        expect("CF-01_pixels_under_the_mask_are_the_conformance_values", s1 != null && (s1.mask_values as List).sort() == (CF.rect_mask_values as List).sort(), s1?.mask_values)
        def p1 = points()
        expect("CF-01_point_drawn_at_target_px_plus_half", p1.size() == 1 && near(p1[0].x, cf1.expect.target_px[1] + 0.5d) && near(p1[0].y, cf1.expect.target_px[0] + 0.5d), p1)
        def o1 = annotationsNamed("frame:outline ")
        def ob = o1.size() == 1 ? o1[0].ROI : null
        expect("CF-01_outline_drawn_on_the_masked_pixels", ob != null && near(ob.boundsX, 3.0d) && near(ob.boundsY, 2.0d) && near(ob.boundsWidth, 3.0d) && near(ob.boundsHeight, 2.0d), ob == null ? null : [ob.boundsX, ob.boundsY, ob.boundsWidth, ob.boundsHeight])
        expect("CF-01_nothing_else_drawn", annotationsNamed("frame:").size() == 2 && userAnnotations().size() == 1 && o1[0].ROI.z == 0 && o1[0].ROI.t == 0, annotationsNamed("frame:")*.name)
        expect("CF-01_a_normal_run_made_a_results_folder", jobCount() == jobsBefore + 1, [jobsBefore, jobCount()])

        // ---- CF-09 QuPath crop offset: the export is the bounding box of the annotation, the mask has the crop's shape, the result lands at c = x0 + (x + 0.5)
        def cf9 = cfCase("CF-09")
        def sel = cf9.selection_annotation
        select([rectXY(sel.x[0], sel.y[0], sel.x[1], sel.y[1])])
        setArea("Selected annotation(s)"); useSelection(true); setPoint(cf9.point[0], cf9.point[1])
        run(dlg)
        def s9 = seen()
        println "CF-09 status: " + status(dlg) + " seen " + s9
        expect("CF-09_export_has_the_crop_shape", s9 != null && s9.image_shape == cf9.expect.export_shape, s9)
        expect("CF-09_export_pixels_are_the_crop", s9 != null && s9.image_first == cf9.expect.export_value_0_0 && s9.image_last == cf9.expect.export_value_3_5, s9)
        expect("CF-09_regionof_mask_is_all_ones_in_the_crop_shape", s9 != null && s9.mask_shape == [4, 6] && s9.mask_pixels.size() == 24, s9?.mask_shape)
        def p9 = points()
        expect("CF-09_result_lands_at_offset_plus_centre", p9.size() == 1 && near(p9[0].x, cf9.result_expect.target_px[1] + 0.5d) && near(p9[0].y, cf9.result_expect.target_px[0] + 0.5d), p9)
        def o9 = annotationsNamed("frame:outline ")[0]?.ROI
        expect("CF-09_outline_of_the_mask_is_the_crop", o9 != null && near(o9.boundsX, 4.0d) && near(o9.boundsY, 2.0d) && near(o9.boundsWidth, 6.0d) && near(o9.boundsHeight, 4.0d), o9 == null ? null : [o9.boundsX, o9.boundsY, o9.boundsWidth, o9.boundsHeight])
        // the exported file itself (what prepareInputs writes), not only what the tool says
        fx { dlg.imageChoiceAtRun = dlg.imageBoxes.collectEntries { k, b -> [(k): b.value as String] } }
        def tmp9 = java.nio.file.Files.createTempDirectory("lcframe_").toFile()
        def in9 = dlg.prepareInputs(fx { dlg.readFormValues() }, dlg.currentTool.inputs, tmp9, "frame", "probe")
        def (ew, eh, eat) = readPlane(in9.image as String, 0, 0)
        expect("CF-09_exported_file_values", ew == 6 && eh == 4 && eat(0, 0) == 204 && eat(5, 3) == 509 && eat(2, 1) == 306, [ew, eh])
        def (mw, mh, mat) = readPlane(in9.region as String, 0, 0)
        boolean ones = mw == 6 && mh == 4
        for (int y = 0; y < mh; y++) for (int x = 0; x < mw; x++) ones &= mat(x, y) == 1
        expect("CF-09_exported_mask_file_is_all_ones", ones, [mw, mh])
        tmp9.deleteDir()
        // ... and the placement is the formula c = x0 + (x + 0.5) * downsample at the unit level
        expect("CF-09_formula_unit", near(LcArea.slideX([x0: 4, downsample: 1.0d], 2 + 0.5d), 6.5d) && near(LcArea.slideY([y0: 2, downsample: 1.0d], 1 + 0.5d), 3.5d), "")
        useSelection(false); setArea("Whole image")

        // ---- CF-12 Replace recomputes the placement: the run on the crop is replaced by a whole-image run, nothing of the crop's frame is kept
        def cf12 = cfCase("CF-12")
        select([rectXY(sel.x[0], sel.y[0], sel.x[1], sel.y[1])]); setArea("Selected annotation(s)"); setPoint(1, 2)
        run(dlg)
        def croppedPoint = points()
        def croppedOutline = annotationsNamed("frame:outline ")[0]?.ROI
        expect("CF-12_the_crop_run_drew_the_crop_outline", croppedOutline != null && near(croppedOutline.boundsX, 4.0d) && near(croppedOutline.boundsWidth, 6.0d), croppedOutline)
        setArea("Whole image"); useSelection(false)
        run(dlg)
        def replaced = points()
        expect("CF-12_replace_removes_the_old_and_places_with_the_new_frame", croppedPoint.size() == 1 && near(croppedPoint[0].x, 6.5d) && replaced.size() == 1 && near(replaced[0].x, 2.5d) && near(replaced[0].y, 1.5d), [croppedPoint, replaced])
        expect("CF-12_outlines_replaced_too", annotationsNamed("frame:outline ").size() == 1 && near(annotationsNamed("frame:outline ")[0].ROI.boundsX, 0.0d) && near(annotationsNamed("frame:outline ")[0].ROI.boundsWidth, 12.0d), annotationsNamed("frame:outline ")*.ROI)

        // ---- CF-02..06, CF-13: the source transform. A QuPath image has none (identity by construction, spec 1.3): nothing to convert, and the placement does not read any
        ["CF-02", "CF-03", "CF-04", "CF-05", "CF-06", "CF-13"].each { id -> cfCase(id); println "NOT_APPLICABLE " + id + ": a QuPath image has no spatial transform (SPEC 1.3: identity by construction)" }
        expect("no_transform_support_claimed", !LcDialog.metaClass.methods.any { it.name in ["transformOf", "worldOf", "dataToWorld"] }, "")

        // ---- CF-07 file input with a decoy open image / CF-08 an earlier output does not become the target
        openImage("asym_zt.tif"); newForm()                                     // the decoy: another image is open (and is the one the viewer shows)
        select([]); fx { hierarchy().clearAll(); null }
        fx { dlg.imageBoxes["image"].value = LcDialog.FILE_CHOICE; dlg.fileFields["image"].text = ASYM; null }
        setPoint(2, 3)
        jobsBefore = jobCount()
        run(dlg)
        def st7 = status(dlg)
        println "CF-07 status: " + st7
        def s7 = seen()
        expect("CF-07_worker_got_the_file", s7 != null && s7.image_shape == [8, 12] && s7.image_first == 0 && s7.image_last == 711, s7)
        expect("CF-07_decoy_gets_nothing", fx { hierarchy().annotationObjects.isEmpty() }, annotationsNamed("")*.name)
        expect("CF-07_status_says_the_file_frame", st7.contains(CONF.messages.file_input_note.template), st7)
        def resultTree = fx { dlg.lastResultStage.scene.root }
        def nodesOf
        nodesOf = { javafx.scene.Node n -> [n] + ((n instanceof javafx.scene.Parent ? n.childrenUnmodifiable : []).collectMany { nodesOf(it) }) + ((n instanceof javafx.scene.control.ScrollPane && n.content != null) ? nodesOf(n.content) : []) }
        def inWindow = fx { nodesOf(resultTree) }
        expect("CF-07_points_are_a_table_in_the_results_window", inWindow.any { it instanceof javafx.scene.control.TableView }, inWindow*.class*.simpleName.unique())
        expect("CF-07_outlines_are_listed_as_a_file", inWindow.any { it instanceof javafx.scene.control.Label && it.text.contains("outline") && it.text.contains(".geojson") || (it instanceof javafx.scene.control.Label && it.text.contains("Not placed on an image: " + CONF.messages.file_input_note.template) && it.text.contains("The outlines are in ")) }, inWindow.findAll { it instanceof javafx.scene.control.Label }*.text)
        // CF-08: run A on the open image (labels result shown), then run B with a file input: B's points go nowhere near A's labels or the open image
        fx { dlg.imageBoxes["image"].value = dlg.imageBoxes["image"].items.find { it.startsWith("Current image") }; null }
        setPoint(2, 3)
        run(dlg)                                                                  // A: the open image, whole, on plane 0/0; its labels result is shown
        def afterA = fx { hierarchy().annotationObjects.collect { it.name } }
        fx { dlg.imageBoxes["image"].value = LcDialog.FILE_CHOICE; dlg.fileFields["image"].text = ASYM; null }
        run(dlg)                                                                  // B: a file; its points must not be drawn on anything
        def afterB = fx { hierarchy().annotationObjects.collect { it.name } }
        expect("CF-08_the_earlier_run_is_the_only_thing_drawn", afterA.sort() == ["frame:marks", "frame:outline 1"].sort() && afterB.sort() == afterA.sort(), [afterA, afterB])

        // ---- CF-10 planes (QuPath #21): the viewer plane at run start is the exported plane; a selection on another plane is refused, never moved
        def cf10 = cfCase("CF-10")
        newForm(); fx { hierarchy().clearAll(); null }
        def cleanForm = { newForm(); fx { hierarchy().clearAll(); null } }
        def reason = { reportOf().reason }
        def refusedWith = { String code ->
            def st = status(dlg)
            [st, reason(), LcLog.recent.any { it.contains("[" + code + "]") }]
        }
        // variant 1: selection on (0, 0), viewer (0, 0): runs; results on z=0 t=0; exported plane is (0, 0)
        setPlane(0, 0)
        select([rectXY(3, 2, 6, 4, 0, 0)]); setArea("Selected annotation(s)"); setPoint(1, 1)
        run(dlg)
        def s10a = seen()
        def pa = points()
        expect("CF-10a_selection_on_the_viewer_plane_runs_and_results_stay_on_it", s10a != null && s10a.image_first == 203 && pa.size() == 1 && annotationsNamed("frame:marks")[0].ROI.z == 0 && annotationsNamed("frame:marks")[0].ROI.t == 0, [s10a, status(dlg)])
        // variant 2: the selection is on z=1, the viewer shows z=0: refused selection_plane_mismatch, nothing exported, nothing drawn
        select([rectXY(3, 2, 6, 4, 1, 0)]); setArea("Selected annotation(s)")
        jobsBefore = jobCount()
        run(dlg)
        def (st10b, why10b, logged10b) = refusedWith("selection_plane_mismatch")
        println "CF-10b status: " + st10b
        expect("CF-10b_other_plane_is_refused_with_its_code", why10b == "selection_plane_mismatch" && logged10b && st10b.contains("z=1 t=0") && st10b.contains("z=0 t=0") && st10b.contains("viewer") && !st10b.startsWith("failed"), [st10b, why10b])
        expect("CF-10b_nothing_was_exported_or_drawn", jobCount() == jobsBefore && annotationsNamed("frame:").isEmpty(), [jobsBefore, jobCount()])
        // the same selection as a RegionOf mask only (whole image, "use the selection"): refused too
        setArea("Whole image"); useSelection(true)
        run(dlg)
        def (st10b2, why10b2, l10b2) = refusedWith("selection_plane_mismatch")
        expect("CF-10b_regionof_mask_on_another_plane_is_refused_too", why10b2 == "selection_plane_mismatch" && jobCount() == jobsBefore, [st10b2, why10b2])
        useSelection(false)
        // a time point that differs is a different plane as well
        select([rectXY(3, 2, 6, 4, 0, 1)]); setArea("Selected annotation(s)")
        run(dlg)
        expect("CF-10b_another_time_point_is_refused_too", reason() == "selection_plane_mismatch" && status(dlg).contains("z=0 t=1"), status(dlg))
        // variant 3: annotations on two planes: refused selection_mixed_planes (also with the viewer on one of them)
        select([rectXY(3, 2, 6, 4, 0, 0), rectXY(4, 3, 7, 5, 1, 0)]); setArea("Selected annotation(s)")
        jobsBefore = jobCount()
        run(dlg)
        def (st10c, why10c, logged10c) = refusedWith("selection_mixed_planes")
        println "CF-10c status: " + st10c
        expect("CF-10c_mixed_planes_are_refused_with_their_code", why10c == "selection_mixed_planes" && logged10c && st10c.contains("z=0 t=0") && st10c.contains("z=1 t=0") && jobCount() == jobsBefore, [st10c, why10c])
        setArea("Whole image"); useSelection(true)
        run(dlg)
        expect("CF-10c_mixed_planes_as_a_mask_are_refused_too", reason() == "selection_mixed_planes" && jobCount() == jobsBefore, status(dlg))
        useSelection(false)
        // variant 4: no selection, whole image, viewer on z=1 t=0: the exported plane is z=1 t=0 and the results are on it
        cleanForm()
        setPlane(1, 0); setPoint(2, 3)
        run(dlg)
        def s10d = seen()
        def pd = annotationsNamed("frame:marks")
        expect("CF-10d_whole_image_is_exported_on_the_viewer_plane", s10d != null && s10d.image_first == 1000 && s10d.image_last == 1711, s10d)
        expect("CF-10d_results_are_on_that_plane", pd.size() == 1 && pd[0].ROI.z == 1 && pd[0].ROI.t == 0 && annotationsNamed("frame:outline ").every { it.ROI.z == 1 && it.ROI.t == 0 } && annotationsNamed("frame:outline ").size() == 1, [pd*.ROI*.z, pd*.ROI*.t])
        // variant 5: a point without t / z lands on the exported plane, not on plane 0 (the tool's point has no t and z columns); also on a later time point
        setPlane(1, 1)
        run(dlg)
        def s10e = seen(), pe = annotationsNamed("frame:marks")
        expect("CF-10e_point_without_t_z_lands_on_the_exported_plane", s10e != null && s10e.image_first == 11000 && pe.size() == 1 && pe[0].ROI.z == 1 && pe[0].ROI.t == 1, [s10e, pe*.ROI*.t])
        // the viewport area carries the viewer plane as well, and a mask (use the selection) on the viewer plane runs with the plane's pixels
        setPlane(0, 1)
        select([rectXY(3, 2, 6, 4, 0, 1)]); setArea("Whole image"); useSelection(true)
        run(dlg)
        def s10f = seen()
        expect("CF-10f_mask_on_the_viewer_plane_runs_on_that_plane", s10f != null && s10f.image_first == 10000 && s10f.mask_pixels.size() == 6 && annotationsNamed("frame:marks")[0].ROI.t == 1 && annotationsNamed("frame:marks")[0].ROI.z == 0, [s10f, status(dlg)])
        useSelection(false)
        // the plane is the one at run start: it is read once for the whole run (no plane read while exporting or placing)
        expect("CF-10g_plane_is_frozen_at_run_start", fx { dlg.planeAtRun } == [z: 0, t: 1], fx { dlg.planeAtRun })
        // a project image (not the one the viewer shows) has no viewer plane: plane 0 / 0 and its results are not drawn (table and file)
        // the unit: checkSelectionPlane
        def roi = { int z, int t -> [z: z, t: t] }
        def planeCheck = { List rois, int z, int t -> try { LcArea.checkSelectionPlane("Image", rois, z, t); null } catch (LcRefusal e) { e.code } }
        expect("CF-10_unit_checkSelectionPlane", planeCheck([roi(0, 0)], 0, 0) == null && planeCheck([roi(1, 0)], 0, 0) == "selection_plane_mismatch" && planeCheck([roi(0, 0), roi(0, 0)], 0, 0) == null && planeCheck([roi(0, 0), roi(0, 1)], 0, 0) == "selection_mixed_planes" && planeCheck([roi(2, 3), roi(2, 3)], 2, 3) == null, "")

        // ---- CF-11 size mismatch is stated: a labels result that says ApplyTo and is 6 x 4 for a source exported as 12 x 8
        def cf11 = cfCase("CF-11")
        cleanForm(); openImage("asym.tif"); newForm(); setPlane(0, 0)
        fx { dlg.setters["labels_rows"](cf11.result.shape[0]); dlg.setters["labels_cols"](cf11.result.shape[1]); null }
        run(dlg)
        def st11 = status(dlg)
        println "CF-11 status: " + st11 + " | labels result: " + reportOf().outputs.results.find { it.type == "labels" }
        def sentence11 = CONF.messages.size_mismatch.template.replace("{name}", "labels").replace("{w}", "6").replace("{h}", "4").replace("{source}", "Image").replace("{w2}", "12").replace("{h2}", "8")
        expect("CF-11_size_mismatch_is_said_once_in_the_status", st11.contains(sentence11) && st11.contains(cf11.expect.status_contains) && st11.indexOf(sentence11) == st11.lastIndexOf(sentence11), st11)
        fx { dlg.setters["labels_rows"](0); dlg.setters["labels_cols"](0); null }
        run(dlg)
        expect("CF-11_equal_sizes_say_nothing", !status(dlg).contains("not aligned"), status(dlg))
        // a crop is a different size from the labels of the whole image: the frame is the crop
        select([rectXY(4, 2, 10, 6)]); setArea("Selected annotation(s)")
        fx { dlg.setters["labels_rows"](8); dlg.setters["labels_cols"](12); null }
        run(dlg)
        expect("CF-11_the_frame_is_the_exported_crop", status(dlg).contains("is 12 x 8 but 'Image' was exported as 6 x 4"), status(dlg))
        fx { dlg.setters["labels_rows"](0); dlg.setters["labels_cols"](0); null }
        setArea("Whole image")

        // ---- CF-14 placement at downsample 2 and 4 (c = x0 + (x + 0.5) * d): the host exports at d = 1 only, so the export is UNSUPPORTED (never "passed"); the arithmetic of placement is checked
        def cf14 = cfCase("CF-14")
        def shapeAt = { Map area, double px, double py ->
            def sq = [[px, py], [px + 1, py], [px + 1, py + 1], [px, py + 1], [px, py]]
            def built = fx { dlg.shapeAnnotations([features: [[geometry: [type: "Polygon", coordinates: [sq]], properties: [label: 1]]]], "frame:cf14", area) }
            built.objects[0].ROI
        }
        cf14.variants.each { v ->
            def d = (v.downsample ?: 1) as double
            def off = v.offset_yx
            def area = [kind: "x", x0: off[1], y0: off[0], width: 12, height: 8, downsample: d, z: 0, t: 0]
            def roi14 = shapeAt(area, v.point[1] as double, v.point[0] as double)       // polygon with its first corner at the result point (x, y) = (point x, point y)
            boolean ok = near(roi14.boundsY, v.expect.c_yx[0] as double) && near(roi14.boundsX, v.expect.c_yx[1] as double)
            expect("CF-14_placement_formula_d" + (v.downsample ?: 1) + "_offset" + off, ok, [roi14.boundsY, roi14.boundsX, v.expect.c_yx])
            if (d != 1d) println("UNSUPPORTED CF-14 export at downsample " + v.downsample + ": this host exports at downsample 1.0 only (LcConst.FULL_RESOLUTION); only the placement arithmetic is checked")
        }
    } catch (Throwable t) { t.printStackTrace(); results["EXCEPTION"] = false }
    finally { println "RESULTS " + results; Thread.sleep(500); System.exit(results && results.every { it.value } ? 0 : 1) }
}
