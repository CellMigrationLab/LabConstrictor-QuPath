// ================================================================== GUI test body: calibration (SPEC_WRONG_SCIENCE section 2: CAL-01..CAL-20 and CAL-T1..CAL-T9 of conformance.json)
// (appended to the tool script by tests/run_gui_test.sh). Needs the `frame` test app (tests/frame_app: tools "Pixel size required" with a declared default of 1.0 and "Pixel size optional").
// Every case checks the REQUEST the host would send (readFormValues + prepareInputs: the value of the manifest parameter and the reserved `_calibration` input), not only the
// field, and the transitions also run the real worker and read the run record. The cases and the unit catalog are read from the conformance file of labconstrictor-tools
// (LC_CONFORMANCE, else `python -m labconstrictor_tools.conformance --path` of the registered app: the way the host itself loads the catalog).
// Sources in QuPath: a file input (TIFF/OME tags and units, ImageJ units), a project image and the image open in QuPath (its PixelCalibration, through the unit catalog).
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import java.util.concurrent.CountDownLatch
import java.awt.image.BufferedImage

def FIXTURES = System.getProperty("lc.test.fixtures", new File("tests/fixtures").absolutePath)
def TESTS = new File(FIXTURES).parentFile
def WORK = new File(System.getProperty("java.io.tmpdir"), "lc_cal_scratch"); WORK.deleteDir(); WORK.mkdirs()
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
def SHARED_HOME = new File(System.getenv("LC_SHARED_HOME") ?: System.getenv("LC_HOME"))
def FRAME_APP = LcJson.parse(new File(SHARED_HOME, "apps/frame.json"))
def PY = FRAME_APP.python
def confPath = System.getenv("LC_CONFORMANCE") ?: [PY, "-m", "labconstrictor_tools.conformance", "--path"].execute().text.trim()
def CONF = LcJson.parse(new File(confPath))
def CAL = CONF.wrong_science.calibration
def calCase = { String id -> def c = CAL.cases.find { it.id == id }; assert c != null : "conformance.json has no case " + id; c }
def transition = { String id -> def c = CAL.transitions.find { it.id == id }; assert c != null : "conformance.json has no transition " + id; c }
def near = { double a, double b -> Math.abs(a - b) <= 1e-9 * Math.max(Math.abs(a), Math.abs(b)) }
def REQUIRED = { String param, String of -> CONF.messages.calibration_required.template.replace("{param}", param).replace("{of}", of) }
def TOOLTIP = { String of -> CONF.messages.calibration_unknown_tooltip.template.replace("{of}", of) }
def NOT_SQUARE = CONF.messages.pixels_not_square_warning.template
def NOT_LENGTH = { String u -> CONF.messages.unit_not_length_warning.template.replace("{unit}", u) }
def DAMAGED = { String u -> CONF.messages.unit_damaged_warning.template.replace("{unit}", u) }
// the files of the cases: written by one python call (tests/calibration_fixtures.py), named c000.tif ...
def specs = [], counter = 0
def spec = { Map s -> def f = new File(WORK, String.format("c%03d.tif", counter++)).absolutePath; specs << ([file: f] + s); f }
def ome = { sx, ux, sy = null, uy = null -> spec([kind: "ome", sx: sx, ux: ux, sy: sy, uy: uy]) }

Thread.start("lc-gui-test") {
    try {
        // ---- the data this test stands on exists (no silent skip when a key is missing)
        expect("conformance_has_the_calibration_data", CAL.length_units.keySet().containsAll(["nm", "um", "mm", "cm", "m", "angstrom"]) && CAL.cases.size() == 20 && CAL.transitions.size() == 9 && CAL.unit_match_refused.size() > 40 && CAL.reserved_input == "_calibration", CAL.keySet())
        expect("constants_are_the_conformance_values", LcConst.SQUARE_PIXEL_TOLERANCE == (CAL.anisotropy_tolerance.relative_to_larger as double) && LcUnits.NOT_SQUARE_TEXT == NOT_SQUARE && LcUnits.CALIBRATION_REQUIRED == CONF.messages.calibration_required.template &&
                LcUnits.NOT_CALIBRATED_TOOLTIP == CONF.messages.calibration_unknown_tooltip.template && LcUnits.NOT_A_LENGTH_TEXT == CONF.messages.unit_not_length_warning.template && LcUnits.DAMAGED_TEXT == CONF.messages.unit_damaged_warning.template && LcUnits.DAMAGED_PREFIX == CAL.damaged_micro_prefix, "")

        // ---- the files
        def imageJFile = { double res, String unit -> spec([kind: "imagej", unit: unit, resolution: [res, res]]) }
        def A = imageJFile(4.0, "um"), B = imageJFile(2.0, "um"), U = spec([kind: "tags", resolution: [1, 1], resolutionunit: "NONE"]), U2 = spec([kind: "plain"])   // A 0.25 um, B 0.5 um, U and U2 uncalibrated
        def cases = [:]            // case id -> list of [label, file, expected]
        def add = { String id, String label, String file, def expected, String warning = null -> cases.get(id, []) << [label: label, file: file, expected: expected, warning: warning] }
        // CAL-01..04 (Napari layers) as OME files: a number with an explicit unit, a unit that is the default 'pixel', or none
        add("CAL-01", "(1,1) micrometer", ome(1.0, "micrometer", 1.0, "micrometer"), 1.0)
        add("CAL-02", "(1,1) pixel", ome(1.0, "pixel", 1.0, "pixel"), "unknown")
        add("CAL-03", "(1,1) millimeter", ome(1.0, "millimeter", 1.0, "millimeter"), 1000.0)
        add("CAL-04", "(0.25,0.25) pixel", ome(0.25, "pixel", 0.25, "pixel"), "unknown")
        add("CAL-04", "(0.25,0.25) no unit", ome(0.25, "", 0.25, ""), "unknown")
        calCase("CAL-05").input.variants.each { v -> add("CAL-05", v.scale + " " + v.unit, ome(v.scale, v.unit, v.scale, v.unit), 0.325) }
        add("CAL-06", "(0.5,0.25) um", ome(0.25, "um", 0.5, "um"), "unknown", NOT_SQUARE)             // OME: x 0.25, y 0.5 (the case's scale is (y, x) = (0.5, 0.25))
        add("CAL-07", "(1,1) second", ome(1.0, "second", 1.0, "second"), "unknown", NOT_LENGTH("second"))
        // CAL-08 (Fiji Calibration): the same unit text as an ImageJ file
        [["micron", 1.0], ["pixel", "unknown"], ["", "unknown"]].each { u, e -> add("CAL-08", "ImageJ unit '" + u + "'", u == "" ? spec([kind: "imagej", unit: "", resolution: [1.0, 1.0]]) : imageJFile(1.0, u), e) }
        // CAL-10..14 (TIFF)
        add("CAL-10", "ImageJ unit=micron, 1 um", imageJFile(1.0, "micron"), 1.0)
        add("CAL-10", "no unit, ResolutionUnit NONE", U, "unknown")
        add("CAL-10", "no tags at all", U2, "unknown")
        add("CAL-11", "INCH 72 dpi", spec([kind: "tags", resolution: [72, 72], resolutionunit: "INCH"]), "unknown")
        add("CAL-12", "INCH 96 dpi", spec([kind: "tags", resolution: [96, 96], resolutionunit: "INCH"]), "unknown")
        add("CAL-13", "CENTIMETER 28.35", spec([kind: "tags", resolution: [28.35, 28.35], resolutionunit: "CENTIMETER"]), "unknown")
        add("CAL-13", "CENTIMETER 37.80", spec([kind: "tags", resolution: [37.8, 37.8], resolutionunit: "CENTIMETER"]), "unknown")
        add("CAL-14", "INCH 25400 dpi", spec([kind: "tags", resolution: [25400, 25400], resolutionunit: "INCH"]), 1.0)
        add("CAL-14", "CENTIMETER 10000 per cm", spec([kind: "tags", resolution: [10000, 10000], resolutionunit: "CENTIMETER"]), 1.0)
        add("CAL-11", "INCH 72.2 dpi (within 0.5 %)", spec([kind: "tags", resolution: [72.2, 72.2], resolutionunit: "INCH"]), "unknown")
        add("CAL-14", "INCH 73 dpi (outside 0.5 %)", spec([kind: "tags", resolution: [73, 73], resolutionunit: "INCH"]), 25400.0 / 73)
        calCase("CAL-15").input.variants.each { v -> add("CAL-15", "unit " + v.unit, ome(v.scale, v.unit, v.scale, v.unit), 0.5) }
        add("CAL-16", "damaged micro sign", ome(0.5, "�m", 0.5, "�m"), "unknown", DAMAGED("�m"))
        calCase("CAL-17").input.variants.each { v -> add("CAL-17", "unit " + v.unit, ome(v.scale, v.unit, v.scale, v.unit), 0.0001) }
        add("CAL-18", "(0.5, 0.5004)", ome(0.5004, "um", 0.5, "um"), 0.5002)
        add("CAL-18", "(0.5, 0.5006)", ome(0.5006, "um", 0.5, "um"), "unknown", NOT_SQUARE)
        CAL.unit_match_refused.each { u -> add("CAL-19", "refused '" + u + "'", ome(1.0, u, 1.0, u), "unknown") }
        def c20 = calCase("CAL-20")
        c20.input.variants.eachWithIndex { v, i -> add("CAL-20", "unit '" + v.unit + "'", ome(v.scale, v.unit, v.scale, v.unit), c20.expect.um_per_px_each[i]) }
        def specFile = new File(WORK, "spec.json"); specFile.setText(LcJson.toJson(specs), "UTF-8")
        def make = [PY, new File(TESTS, "calibration_fixtures.py").path, specFile.path].execute()
        def makeErr = make.err.text
        expect("fixture_files_were_written", make.waitFor() == 0 && specs.every { new File(it.file).isFile() }, makeErr)
        // the premise of the unit cases: the file keeps the unit text exactly
        def damagedFile = cases["CAL-16"][0].file
        expect("premise_the_damaged_unit_text_is_in_the_file", LcTiff.tags(new File(damagedFile)).description.contains("PhysicalSizeXUnit=\"�m\""), "")

        // ---- the project: A, B, U as project images, A open in QuPath
        fx {
            def dir = new File(System.getProperty("java.io.tmpdir"), "qp_project_cal"); dir.deleteDir()
            qupath.lib.gui.prefs.PathPrefs.imageTypeSettingProperty().set(qupath.lib.gui.prefs.PathPrefs.ImageTypeSetting.AUTO_ESTIMATE)
            def project = qupath.lib.projects.Projects.createProject(dir, BufferedImage)
            [["A", A], ["B", B], ["U", U]].each { n, f -> def s = qupath.lib.images.servers.ImageServers.buildServer(f); def e = project.addImage(s.getBuilder()); e.setImageName(n) }
            project.syncChanges(); qupath.setProject(project)
        }
        def openImage = { String name ->
            fx { qupath.imageData?.setChanged(false); qupath.openImageEntry(qupath.project.imageList.find { it.imageName == name }); null }
            Thread.sleep(2500)
        }
        openImage("A")
        def dlg = fx { lcShow() }
        Thread.sleep(800)
        pick(dlg, "frame", "Pixel size required"); Thread.sleep(800)
        def p = [name: "pixel_size", pixel_size_of: "image", label: "Pixel size"]
        def field = fx { dlg.controls["pixel_size"] }
        def fieldText = { fx { field.text } }
        def link = { fx { dlg.links["pixel_size"] } }
        def imageBox = fx { dlg.imageBoxes["image"] }
        def chooseImage = { String prefix -> fx { imageBox.value = imageBox.items.find { it.startsWith(prefix) }; null } }
        def chooseFile = { String path -> fx { imageBox.value = LcDialog.FILE_CHOICE; dlg.fileFields["image"].text = path; null } }
        // what the host would send: the values of the form and the request built from them (null values: the run is blocked, the status says why)
        def requestOf = {
            def values = fx { dlg.readFormValues() }
            if (values == null) return [values: null, status: status(dlg), inputs: null]
            fx { dlg.imageChoiceAtRun = dlg.imageBoxes.collectEntries { k, b -> [(k): b.value as String] } }
            def tmp = java.nio.file.Files.createTempDirectory("lccal_").toFile()
            def inputs = dlg.prepareInputs(values, dlg.currentTool.inputs, tmp, "frame", "pixel")
            tmp.deleteDir()
            return [values: values, status: status(dlg), inputs: inputs]
        }
        def xyOf = { Map req, String axis -> req.inputs?._calibration?.pixel_size?.axes?.get(axis) }

        // ---- CAL-01..20 as file inputs: field, request and `_calibration`
        cases.each { id, list ->
            list.each { c ->
                chooseFile(c.file); Thread.sleep(50)
                def afterFill = status(dlg)
                def req = requestOf()
                def name = id + "_" + c.label
                def shown = fieldText()
                if (c.expected == "unknown") {
                    boolean ok = req.values == null && req.status == REQUIRED("pixel_size", "image") && shown == "" && link().value == null && fx { field.tooltip.text } == TOOLTIP("image")
                    if (c.warning) ok &= afterFill.contains(c.warning)
                    expect(name + "_is_unknown_and_the_run_is_blocked", ok, [req.status, shown, afterFill])
                } else {
                    def want = c.expected as double
                    def x = xyOf(req, "x"), y = xyOf(req, "y")
                    boolean ok = req.values != null && near(req.values.pixel_size as double, want) && x != null && y != null && x.unit == "um" && y.unit == "um" && x.source == "file" && y.source == "file" &&
                            req.inputs._calibration.pixel_size.of == "image" && req.inputs._calibration.pixel_size.source == "file" && shown != "" && near(Double.parseDouble(shown), want)
                    expect(name + "_gives_" + c.expected, ok, [req.values, req.inputs?._calibration, shown])
                }
            }
        }
        // per axis: the request keeps both axes while the field takes their mean (CAL-18) and the raw text of the file
        chooseFile(cases["CAL-18"][0].file); def req18 = requestOf()
        expect("CAL-18_axes_are_kept_per_axis_with_their_raw_text", near(xyOf(req18, "x").value as double, 0.5004) && near(xyOf(req18, "y").value as double, 0.5) && xyOf(req18, "x").raw == [scale: 0.5004d, unit: "um"], req18.inputs?._calibration)
        chooseFile(cases["CAL-15"][0].file); def req15 = requestOf()
        expect("CAL-15_the_raw_unit_text_is_kept_in_the_request", xyOf(req15, "x").raw.unit == "µm" && near(xyOf(req15, "x").raw.scale as double, 0.5), req15.inputs?._calibration)

        // ---- CAL-09 QuPath's PixelCalibration (hasPixelSizeMicrons true with 1.0 / false) on the image open in QuPath
        def cal09 = calCase("CAL-09")
        chooseImage("Current image")
        def server = fx { qupath.imageData.server }
        def originalMetadata = fx { server.originalMetadata }
        def setCalibration = { Double w, Double h ->
            fx {
                def b = new qupath.lib.images.servers.ImageServerMetadata.Builder(originalMetadata)
                b.pixelSizeMicrons(w, h)       // null, null: a server that says nothing about the pixel size (hasPixelSizeMicrons() false)
                server.setMetadata(b.build())
                dlg.fillPixelSize(p)
                null
            }
        }
        setCalibration(1.0d, 1.0d)
        def r09a = requestOf()
        expect("CAL-09_hasPixelSizeMicrons_true_gives_1_0", fx { server.pixelCalibration.hasPixelSizeMicrons() } && r09a.values != null && near(r09a.values.pixel_size as double, 1.0d) && xyOf(r09a, "x").source == "image" && xyOf(r09a, "x").raw.unit == "µm", [r09a.values, r09a.inputs?._calibration])
        fx { server.setMetadata(originalMetadata); null }
        setCalibration(null, null)
        def r09b = requestOf()
        expect("CAL-09_hasPixelSizeMicrons_false_is_unknown", fx { !server.pixelCalibration.hasPixelSizeMicrons() } && r09b.values == null && r09b.status == REQUIRED("pixel_size", "image") && fieldText() == "", [r09b.status, fieldText()])
        // a calibration entered in QuPath beats the TIFF rules: the file says 72 dpi (unknown), the person's entry is explicit
        setCalibration(0.4d, 0.4d)
        def r09c = requestOf()
        expect("CAL-09_a_calibration_entered_in_QuPath_is_explicit", r09c.values != null && near(r09c.values.pixel_size as double, 0.4d), [r09c.values, r09c.status])
        fx { server.setMetadata(originalMetadata); dlg.fillPixelSize(p); null }
        def r09d = requestOf()
        expect("CAL-09_back_to_the_files_own_calibration", r09d.values != null && near(r09d.values.pixel_size as double, 0.25d), [r09d.values, r09d.status])
        // a plain TIFF with a 72 dpi tag: QuPath's own reader says 352.78 um, the host says unknown (CAL-11 for a project image)
        def dpiFile = cases["CAL-11"][0].file
        def dpiServer = qupath.lib.images.servers.ImageServers.buildServer(dpiFile)
        println "PREMISE QuPath's own calibration of the 72 dpi TIFF: " + dpiServer.pixelCalibration.pixelWidthMicrons + " um (hasPixelSizeMicrons " + dpiServer.pixelCalibration.hasPixelSizeMicrons() + ")"
        fx {
            def e = qupath.project.addImage(dpiServer.getBuilder()); e.setImageName("dpi72"); qupath.project.syncChanges(); null
        }
        pick(dlg, "frame", "Pixel size required"); fx { dlg.onTool() }; Thread.sleep(800)
        imageBox = fx { dlg.imageBoxes["image"] }; field = fx { dlg.controls["pixel_size"] }
        chooseImage("Project: dpi72")
        def r72 = requestOf()
        expect("CAL-11_a_72_dpi_project_image_is_unknown_though_QuPath_says_a_size", dpiServer.pixelCalibration.hasPixelSizeMicrons() && r72.values == null && r72.status == REQUIRED("pixel_size", "image") && fieldText() == "", [r72.status, fieldText(), dpiServer.pixelCalibration.pixelWidthMicrons])
        dpiServer.close()

        // ---- CAL-01, 03, 05 also as project images (source image): the same answer, the source is the image
        chooseImage("Project: A"); def rA = requestOf()
        expect("CAL-source_a_project_image_says_source_image", rA.values != null && near(rA.values.pixel_size as double, 0.25d) && xyOf(rA, "x").source == "image" && rA.inputs._calibration.pixel_size.source == "image", rA.inputs?._calibration)

        // ---- transitions (each asserts the field and the request)
        def typeText = { String text ->
            fx { field.requestFocus(); field.selectAll(); null }
            text.each { ch -> fx { javafx.event.Event.fireEvent(field, new KeyEvent(KeyEvent.KEY_TYPED, ch.toString(), ch.toString(), KeyCode.UNDEFINED, false, false, false, false)); null } }
            Thread.sleep(150)
        }
        def clearByHand = { fx { field.requestFocus(); field.selectAll(); field.deleteText(0, field.text.length()); null }; Thread.sleep(150) }
        def reset = { fx { dlg.onTool(); null }; Thread.sleep(800); imageBox = fx { dlg.imageBoxes["image"] }; field = fx { dlg.controls["pixel_size"] } }
        def sourceOf = { Map req -> req.inputs?._calibration?.pixel_size?.source }
        // T1
        reset(); openImage("A"); reset()
        chooseImage("Project: A"); def t1a = requestOf()
        chooseImage("Project: B"); def t1b = requestOf()
        expect("CAL-T1_A_to_B_field_request_source", t1a.values != null && near(t1a.values.pixel_size as double, 0.25d) && fieldText() == "0.5" && t1b.values != null && near(t1b.values.pixel_size as double, 0.5d) && sourceOf(t1b) == "image" && near(xyOf(t1b, "x").value as double, 0.5d), [t1a.values, t1b.values, fieldText()])
        // T2
        chooseImage("Project: A"); chooseImage("Project: U"); def t2 = requestOf()
        expect("CAL-T2_uncalibrated_B_empties_the_field_and_blocks_the_run", fieldText() == "" && t2.values == null && t2.status == REQUIRED("pixel_size", "image") && fx { field.tooltip.text } == TOOLTIP("image"), [fieldText(), t2.status])
        // T2, Optional: the request has null, A's value is in no request
        def runTool = { String tool -> pick(dlg, "frame", tool); fx { dlg.onTool() }; Thread.sleep(800); imageBox = fx { dlg.imageBoxes["image"] }; field = fx { dlg.controls["pixel_size"] } }
        runTool("Pixel size optional")
        chooseImage("Project: A"); chooseImage("Project: U")
        def t2o = requestOf()
        expect("CAL-T2_optional_sends_null_and_no_calibration", t2o.values != null && t2o.inputs.containsKey("pixel_size") && t2o.inputs.pixel_size == null && !t2o.inputs.containsKey("_calibration") && fieldText() == "", [t2o.values, t2o.inputs])
        run(dlg)
        def recordOf = { def runs = new File(LcRegistry.home(), "runs").listFiles().findAll { it.isDirectory() }.sort { it.name }; LcJson.parse(new File(runs.last(), "run.json")) }
        def seenOf = { def report = (dlg.lastReport as String).split("\n\nworker output:")[0]; LcJson.parseText(report).outputs?.results?.find { it.type == "values" }?.values }
        expect("CAL-T2_optional_run_reaches_the_tool_with_none", seenOf()?.pixel_size == null && seenOf()?.image_shape != null && !recordOf().inputs.containsKey("_calibration"), seenOf())
        // T1 as a real run: the tool receives 0.5 and the run record keeps `_calibration`
        runTool("Pixel size required")
        chooseImage("Project: B"); run(dlg)
        def rec = recordOf()
        expect("CAL-T1_real_run_the_tool_got_0_5_and_the_record_has_the_calibration", near(seenOf().pixel_size as double, 0.5d) && rec.inputs.pixel_size == 0.5d && rec.inputs._calibration.pixel_size.axes.x.value == 0.5d && rec.inputs._calibration.pixel_size.axes.x.unit == "um" && rec.inputs._calibration.pixel_size.of == "image", rec.inputs)
        // T3: A, the person types 0.3, switch to B -> 0.3, source user
        reset(); chooseImage("Project: A"); typeText("0.3")
        expect("CAL-T3_typed_text_shows", fieldText() == "0.3" && link().state == LcLink.USER, [fieldText(), link().state])
        chooseImage("Project: B"); def t3 = requestOf()
        expect("CAL-T3_a_typed_value_survives_a_source_change", t3.values != null && near(t3.values.pixel_size as double, 0.3d) && sourceOf(t3) == "user" && xyOf(t3, "x").source == "user" && xyOf(t3, "y").source == "user" && fx { field.tooltip.text }.contains("entered by you"), [t3.values, t3.inputs?._calibration])
        // T4: user(0.3), the person clears the field, source B -> auto 0.5
        clearByHand()
        expect("CAL-T4_an_empty_field_is_unset", link().state == LcLink.UNSET, link().state)
        chooseImage("Project: A"); chooseImage("Project: B"); def t4 = requestOf()
        expect("CAL-T4_cleared_then_source_B_is_auto_0_5", link().state == LcLink.AUTO && t4.values != null && near(t4.values.pixel_size as double, 0.5d) && sourceOf(t4) == "image", [link().state, t4.values])
        // ... the button does the same for a typed value
        typeText("0.7"); fx { dlg.wrappers["pixel_size"].children[1].fire(); null }; def t4b = requestOf()
        expect("CAL-T4_the_button_asks_for_the_image_calibration_again", link().state == LcLink.AUTO && t4b.values != null && near(t4b.values.pixel_size as double, 0.5d), [link().state, t4b.values])
        // T5 / T6: open image A, a file B that is uncalibrated -> unset, run blocked; file cleared -> auto 0.25 (A)
        reset(); chooseImage("Current image"); def t5a = requestOf()
        chooseFile(U); def t5 = requestOf()
        expect("CAL-T5_open_image_A_then_an_uncalibrated_file_is_unset_and_blocked", t5a.values != null && near(t5a.values.pixel_size as double, 0.25d) && fieldText() == "" && t5.values == null && t5.status == REQUIRED("pixel_size", "image"), [t5a.values, fieldText(), t5.status])
        chooseImage("Current image"); def t6 = requestOf()
        expect("CAL-T6_file_cleared_gives_A_again", link().state == LcLink.AUTO && t6.values != null && near(t6.values.pixel_size as double, 0.25d) && sourceOf(t6) == "image", [link().state, t6.values])
        // a file input with a calibration wins over the open image
        chooseFile(B); def t6b = requestOf()
        expect("CAL-T5_a_calibrated_file_wins_over_the_open_image", near(t6b.values.pixel_size as double, 0.5d) && sourceOf(t6b) == "file", [t6b.values, t6b.inputs?._calibration])
        // T7 (QuPath): project image A -> a file image that is uncalibrated -> unset
        reset(); chooseImage("Project: A"); def t7a = requestOf(); chooseFile(U2); def t7 = requestOf()
        expect("CAL-T7_project_image_then_an_uncalibrated_file_is_unset", t7a.values != null && link().state == LcLink.UNSET && fieldText() == "" && t7.values == null, [t7a.values, link().state, fieldText()])
        // T8: a required linked field with a declared default 1.0, source unknown: blocked, the default is not sent
        reset(); chooseImage("Project: U"); def t8 = requestOf()
        expect("CAL-T8_default_1_0_is_not_a_stand_in", fx { dlg.currentTool.inputs.find { it.name == "pixel_size" }.default } == 1.0d && t8.values == null && t8.status == REQUIRED("pixel_size", "image") && fieldText() == "", [t8.status, fieldText()])
        // ... also at the first form of a tool (nothing was chosen yet but an uncalibrated image)
        // T9: user types 0.25 equal to the last auto value, then switch to the uncalibrated B -> stays 0.25, source user
        reset(); chooseImage("Project: A")
        def lastAuto = fieldText()
        typeText("0.25"); def sameAsAuto = fieldText()
        chooseImage("Project: U"); def t9 = requestOf()
        expect("CAL-T9_typing_the_value_the_box_shows_is_still_the_persons", lastAuto == "0.25" && sameAsAuto == "0.25" && link().state == LcLink.USER && t9.values != null && near(t9.values.pixel_size as double, 0.25d) && sourceOf(t9) == "user", [lastAuto, sameAsAuto, link().state, t9.values])
        // ... and the same text typed over itself, key by key, with no change of the text in between (a paste of the same text): the key event alone is an edit
        reset(); chooseImage("Project: A")
        fx { field.requestFocus(); javafx.event.Event.fireEvent(field, new KeyEvent(KeyEvent.KEY_TYPED, "x", "x", KeyCode.UNDEFINED, false, false, false, false)); null }; Thread.sleep(150)
        expect("CAL-T9_a_key_event_that_leaves_the_text_alone_still_counts_as_an_edit", link().state == LcLink.USER, [link().state, fieldText()])
        // a host write is never the person's: setters, the programmatic write, a source change keep the field automatic
        reset(); chooseImage("Project: A"); fx { dlg.setters["pixel_size"](0.9d); null }
        expect("programmatic_set_does_not_make_the_value_the_persons", link().state == LcLink.AUTO && fieldText() == "0.9", [link().state, fieldText()])
        chooseImage("Project: B")
        expect("programmatic_value_follows_the_next_source", fieldText() == "0.5" && link().state == LcLink.AUTO, [fieldText(), link().state])
        // text that is not a number, zero and negative numbers are refused by name
        typeText("abc"); def bad1 = requestOf()
        expect("a_text_that_is_not_a_number_is_refused_by_name", bad1.values == null && bad1.status == "'" + fx { dlg.currentTool.inputs.find { it.name == "pixel_size" }.label } + "': 'abc' is not a number", bad1.status)
        typeText("0"); def bad2 = requestOf()
        expect("zero_is_refused", bad2.values == null && bad2.status.contains("must be greater than 0"), bad2.status)
        typeText("-0.5"); def bad3 = requestOf()
        expect("a_negative_value_is_refused", bad3.values == null && bad3.status.contains("must be greater than 0"), bad3.status)
        typeText("NaN"); def bad4 = requestOf()
        expect("not_finite_is_refused", bad4.values == null, bad4.status)
        // ClearAfterRun and the declared default never stand in: after a reset the field is the image's
        reset(); chooseImage("Project: A")
        expect("the_form_starts_from_the_image_not_the_declared_default", fieldText() == "0.25" && link().state == LcLink.AUTO, [fieldText(), link().state])
        // a calibration edited in QuPath between choosing the image and pressing Run is read again at the click (not the person's own value)
        openImage("A"); reset(); chooseImage("Current image")
        def srv = fx { qupath.imageData.server }; def orig = fx { srv.originalMetadata }
        fx { srv.setMetadata(new qupath.lib.images.servers.ImageServerMetadata.Builder(orig).pixelSizeMicrons(0.8d, 0.8d).build()); null }
        fx { dlg.refreshLinks(); null }
        def rEdit = requestOf()
        expect("a_calibration_edited_in_QuPath_is_read_again_before_the_run", rEdit.values != null && near(rEdit.values.pixel_size as double, 0.8d), [rEdit.values, fieldText()])
        fx { srv.setMetadata(orig); null }

        // ---- the catalog and the unit rules at the conversion level (the same function the files and servers go through)
        expect("unit_catalog_is_loaded_from_the_conformance_file_not_from_this_script", LcUnits.conformance().is(null) == false && LcUnits.calibrationData().length_units.keySet() == CAL.length_units.keySet(), "")
        expect("symbols_are_exact_text", LcUnits.unitFactor("um")[0] == 1.0d && LcUnits.unitFactor("UM")[0] == null && LcUnits.unitFactor(" um")[0] == null && LcUnits.unitFactor("mms")[0] == null, "")
        expect("words_ignore_case_and_surrounding_blanks", LcUnits.unitFactor(" MILLIMETER ")[0] == 1000.0d && LcUnits.unitFactor("micro meter")[0] == null, "")
        expect("calibrate_never_assumes_um", LcCal.calibrate(1.0, "pixel", 1.0, "pixel").known == false && LcCal.calibrate(0.25, null, 0.25, null).known == false && LcCal.calibrate(1.0, "um", 1.0, "um").known, "")
        expect("tolerance_is_relative_to_the_larger", LcCal.calibrate(0.5, "um", 0.5004, "um").square && !LcCal.calibrate(0.5, "um", 0.5006, "um").square, "")
        // without a catalog nothing is converted by a list of this script: every calibration is unknown, with the sentence that says why
        if (System.getenv("LC_CONFORMANCE") == null) {
            def appEntries = dlg.apps.values().collect { it.entry }
            LcUnits.useApps([]); LcUnits.reset()
            def none = LcCal.calibrate(1.0, "um", 1.0, "um")
            LcUnits.useApps(appEntries); LcUnits.reset()
            expect("a_missing_catalog_is_never_replaced_by_a_guess", !none.known && none.warning == LcUnits.NO_CATALOG_TEXT && LcCal.calibrate(1.0, "um", 1.0, "um").known, [none.warning])
        } else println "NOT_APPLICABLE a_missing_catalog_is_never_replaced_by_a_guess: LC_CONFORMANCE is set"
    } catch (Throwable t) { t.printStackTrace(); results["EXCEPTION"] = false }
    finally { println "RESULTS " + results; Thread.sleep(500); System.exit(results && results.every { it.value } ? 0 : 1) }
}
