// ================================================================== GUI test body: form behaviour (QP-2c, 2d, 2e, 2f, 3, 4, 5) (appended to the tool script by tests/run_gui_test.sh)
// Needs no image and no real app: it registers a synthetic app (only its schema is used) in the scratch LC_HOME (a folder named lhome_form).
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
        // a synthetic app: only its schema matters (the form is built, no tool is run)
        def home = new File(System.getenv("LC_HOME")); assert home.path.contains("lhome_form")
        home.deleteDir(); def apps = new File(home, "apps"); apps.mkdirs()
        def P = { String name, String type, Map more = [:] -> [name: name, label: name.capitalize().replace("_", " "), type: type, required: more.required != false && !more.nullable] + more }
        def schema = [protocol: 1, tools_version: "0.3.0a1", features: ["reserved_inputs_v1", "retention_prune"], tools: [
            [id: "order", label: "Order", description: "", outputs: [], inputs: [
                P("a", "string"), P("x1", "integer", [group: "G1", default: 1]), P("b", "string"), P("x2", "float", [group: "G1", default: 2.5]),
                P("c1", "integer", [group: "C", group_collapsed: true, default: 3]), P("d", "string"), P("c2", "float", [group: "C", group_collapsed: true, default: 4.5]),
                P("adv", "string", [advanced: true, required: false]), P("e", "string")]],
            [id: "clear", label: "Clear", description: "", outputs: [], inputs: [
                P("n_int", "integer", [default: 5, clear_after_run: true]), P("n_float", "float", [default: 1.5, clear_after_run: true]),
                P("flag", "boolean", [default: false, clear_after_run: true]), P("pick", "choice", [choices: ["a", "b"], default: "a", clear_after_run: true]),
                P("opt_int", "integer", [nullable: true, required: false, clear_after_run: true]), P("text", "string", [default: "hi", clear_after_run: true]),
                P("txt_nodefault", "string", [clear_after_run: true, required: false]), P("kept_int", "integer", [default: 7])]],
            [id: "enable", label: "Enable", description: "", outputs: [], inputs: [
                P("drv_text", "string", [default: "", required: false]), P("drv_num", "integer", [default: 0]), P("drv_check", "boolean", [default: false]),
                P("drv_choice", "choice", [choices: ["x", "y"], default: "x"]), P("drv_opt", "integer", [nullable: true, required: false]),
                P("dep_text", "string", [enabled_when: [param: "drv_text"], required: false]), P("dep_num", "string", [enabled_when: [param: "drv_num"], required: false]),
                P("dep_check", "string", [enabled_when: [param: "drv_check"], required: false]), P("dep_choice", "string", [enabled_when: [param: "drv_choice", equals: ["y"]], required: false]),
                P("dep_opt", "string", [enabled_when: [param: "drv_opt"], required: false]), P("dep_eq_num", "string", [enabled_when: [param: "drv_num", equals: [1]], required: false])]],
            [id: "limits", label: "Limits", description: "", outputs: [], inputs: [
                P("free_int", "integer", [required: false, default: 0]), P("free_float", "float", [required: false, default: 0.0]),
                P("big_max", "integer", [minimum: -5000000000, maximum: 5000000000, default: 0]), P("huge_max", "integer", [minimum: -(2L ** 62), maximum: 2L ** 62, default: 0]), P("num_choice", "choice", [choices: [1, 2, 3], default: 1])]],
            [id: "required", label: "Required", description: "", outputs: [], inputs: [
                P("req_img", "image"), P("req_file", "file"), P("req_folder", "folder"), P("req_table", "table"), P("req_float", "float", [default: 1.0])]]]]
        new File(apps, "formapp.schema.json").text = LcJson.toJson(schema)
        new File(apps, "formapp.json").text = LcJson.toJson([schema: 1, name: "formapp", display_name: "formapp", version: "", prefix: INTERACTIONS.prefix, python: PY,
                                                                module: "labconstrictor_tools.examples.interactions", pythonpath: [], runtime_path: "", schema_path: new File(apps, "formapp.schema.json").path])
        ["chmod", "644", new File(apps, "formapp.json").path, new File(apps, "formapp.schema.json").path].execute().waitFor()
        def dlg = fx { lcShow() }
        Thread.sleep(800)
        expect("form_app_is_registered", fx { dlg.apps.containsKey("formapp") }, fx { dlg.problems })

        def rows = { // the labels of the form in order, with the sections between: ["grid:" ..., "pane:C", ...]
            fx {
                dlg.formBox.children.collect { n ->
                    def inner = n instanceof TitledPane ? n.content : n
                    def texts = inner.children.findAll { it instanceof Label && (GridPane.getColumnIndex(it) ?: 0) == 0 }*.text
                    (n instanceof TitledPane ? "pane:" + n.text + ":" : "grid:") + texts.join("|")
                }
            }
        }
        // ---- QP-5: the order of the parameters is the Napari / Fiji one (groups gathered at their first appearance, Collapsed groups where they first appear, advanced last)
        pick(dlg, "formapp", "Order"); Thread.sleep(800)
        def r = rows()
        println "ROWS " + r
        expect("order_groups_are_gathered_at_the_first_appearance", r[0] == "grid:A|G1|X1|X2|B", r)
        expect("order_collapsed_group_sits_where_it_first_appears", r[1] == "pane:C:C1|C2", r)
        expect("order_the_rest_follows_the_pane", r[2] == "grid:D|E", r)
        expect("order_advanced_is_last", r[3] == "pane:Advanced settings:Adv" && r.size() == 4, r)
        expect("presentation_order_unit_without_hints_is_unchanged", LcDialog.presentationOrder([[name: "p"], [name: "q"]])*.name == ["p", "q"])
        expect("presentation_order_unit_matches_napari", LcDialog.presentationOrder([[name: "a"], [name: "x1", group: "G"], [name: "b"], [name: "x2", group: "G"], [name: "z", advanced: true, group: "G"], [name: "y", advanced: true]])*.name == ["a", "x1", "x2", "b", "z", "y"])

        // ---- QP-3: ClearAfterRun resets every type of control
        pick(dlg, "formapp", "Clear"); Thread.sleep(800)
        fx { dlg.setters["n_int"](99); dlg.setters["n_float"](9.5d); dlg.setters["flag"](true); dlg.setters["pick"]("b"); dlg.checks["opt_int"].selected = true; dlg.setters["opt_int"](42)
             dlg.setters["text"]("changed"); dlg.setters["txt_nodefault"]("typed"); dlg.setters["kept_int"](8) }
        fx { dlg.clearAfterRun() }
        expect("clear_integer_returns_to_default", fx { dlg.getters["n_int"]() } == 5, fx { dlg.getters["n_int"]() })
        expect("clear_float_returns_to_default", fx { dlg.getters["n_float"]() } == 1.5d, fx { dlg.getters["n_float"]() })
        expect("clear_boolean_returns_to_default", fx { dlg.getters["flag"]() } == false, fx { dlg.getters["flag"]() })
        expect("clear_fixed_choice_returns_to_default", fx { dlg.getters["pick"]() } == "a", fx { dlg.getters["pick"]() })
        expect("clear_optional_number_is_unset", fx { dlg.checks["opt_int"].selected } == false && fx { dlg.getters["opt_int"]() } == null, fx { dlg.getters["opt_int"]() })
        expect("clear_text_returns_to_default", fx { dlg.getters["text"]() } == "hi", fx { dlg.getters["text"]() })
        expect("clear_text_without_default_is_empty", fx { dlg.getters["txt_nodefault"]() } == "", fx { dlg.getters["txt_nodefault"]() })
        expect("clear_leaves_other_parameters_alone", fx { dlg.getters["kept_int"]() } == 8, fx { dlg.getters["kept_int"]() })

        // ---- QP-4: EnabledWhen listens to every kind of driver, and "" is unset
        pick(dlg, "formapp", "Enable"); Thread.sleep(800)
        def on = { String name -> fx { !dlg.wrappers[name].disable } }
        expect("enabled_text_driver_empty_is_unset", !on("dep_text"))
        fx { dlg.setters["drv_text"]("hello") }
        expect("enabled_text_driver_wakes_the_field", on("dep_text"))
        fx { dlg.setters["drv_text"]("") }
        expect("enabled_text_driver_back_to_empty_greys_out", !on("dep_text"))
        expect("enabled_number_driver_is_set_even_at_zero", on("dep_num"))
        expect("enabled_boolean_driver_false_is_unset", !on("dep_check"))
        fx { dlg.setters["drv_check"](true) }
        expect("enabled_boolean_driver_true_wakes", on("dep_check"))
        expect("enabled_choice_driver_equals_not_met", !on("dep_choice"))
        fx { dlg.setters["drv_choice"]("y") }
        expect("enabled_choice_driver_equals_met", on("dep_choice"))
        expect("enabled_unticked_set_box_is_unset", !on("dep_opt"))
        fx { dlg.checks["drv_opt"].selected = true }
        expect("enabled_ticking_the_set_box_wakes_the_field", on("dep_opt"))
        fx { dlg.checks["drv_opt"].selected = false }
        expect("enabled_unticking_the_set_box_greys_it_out", !on("dep_opt"))
        expect("enabled_number_equals_not_met", !on("dep_eq_num"))
        fx { dlg.setters["drv_num"](1) }
        expect("enabled_number_driver_equals_met_by_a_spinner_change", on("dep_eq_num"))
        fx { dlg.setters["drv_num"](2) }
        expect("enabled_number_driver_equals_unmet_again", !on("dep_eq_num"))
        expect("rule_unit_equality_is_typed", LcDialog.ruleSatisfied([param: "p", equals: [1]], 1L) && LcDialog.ruleSatisfied([param: "p", equals: [1]], 1.0d) && !LcDialog.ruleSatisfied([param: "p", equals: [1]], "1") && !LcDialog.ruleSatisfied([param: "p", equals: ["1"]], 1L))
        expect("rule_unit_set_means_not_none_false_or_empty", LcDialog.ruleSatisfied([param: "p"], 0L) && LcDialog.ruleSatisfied([param: "p"], "x") && !LcDialog.ruleSatisfied([param: "p"], "") && !LcDialog.ruleSatisfied([param: "p"], false) && !LcDialog.ruleSatisfied([param: "p"], null))

        // ---- QP-2c: the limits of a whole-number box are +-(2^53-1) (Tools notebook.MAX_FORM_INTEGER), those of a float box +-1e15; a declared bound beyond 2^53-1 is cut to it
        pick(dlg, "formapp", "Limits"); Thread.sleep(800)
        def spin = { String n -> fx { dlg.controls[n] } }
        long LIMIT = 9007199254740991L
        expect("free_integer_limits_are_2_to_the_53_minus_1", spin("free_int").valueFactory.min == -LIMIT && spin("free_int").valueFactory.max == LIMIT, [spin("free_int").valueFactory.min, spin("free_int").valueFactory.max])
        expect("free_float_limits_are_1e15", spin("free_float").valueFactory.min == -1e15d && spin("free_float").valueFactory.max == 1e15d, [spin("free_float").valueFactory.min, spin("free_float").valueFactory.max])
        expect("a_declared_bound_beyond_int_is_kept", spin("big_max").valueFactory.max == 5000000000L && spin("big_max").valueFactory.min == -5000000000L, [spin("big_max").valueFactory.min, spin("big_max").valueFactory.max])
        expect("a_declared_bound_beyond_2_to_the_53_is_cut_not_wrapped", spin("huge_max").valueFactory.max == LIMIT && spin("huge_max").valueFactory.min == -LIMIT, [spin("huge_max").valueFactory.min, spin("huge_max").valueFactory.max])
        fx { dlg.setters["free_int"](2000000000) }
        expect("an_integer_above_a_billion_is_accepted", fx { dlg.getters["free_int"]() } == 2000000000, fx { dlg.getters["free_int"]() })
        fx { dlg.setters["free_int"](LIMIT) }
        expect("the_largest_integer_is_held_exactly_and_sent_as_it_is", fx { dlg.getters["free_int"]() } == LIMIT && LcJson.toJson([free_int: fx { dlg.getters["free_int"]() }]) == '{"free_int":9007199254740991}', fx { dlg.getters["free_int"]() })
        fx { spin("free_int").editor.text = "9007199254740993"; dlg.commitSpinner(spin("free_int"), "Free int") }
        expect("a_typed_integer_beyond_the_limit_stops_at_it", fx { dlg.getters["free_int"]() } == LIMIT, fx { dlg.getters["free_int"]() })
        fx { spin("free_int").editor.text = "9007199254740993"; spin("free_int").commitValue() }      // Enter in the box commits without the arrow code
        expect("a_typed_integer_beyond_the_limit_stops_at_it_on_enter", fx { dlg.getters["free_int"]() } == LIMIT, fx { dlg.getters["free_int"]() })
        fx { spin("free_int").increment(1) }
        expect("the_arrow_never_passes_the_limit", fx { dlg.getters["free_int"]() } == LIMIT, fx { dlg.getters["free_int"]() })
        fx { spin("free_int").editor.text = "-9007199254740991"; dlg.commitSpinner(spin("free_int"), "Free int"); spin("free_int").decrement(3) }
        expect("the_arrow_never_passes_the_lower_limit", fx { dlg.getters["free_int"]() } == -LIMIT, fx { dlg.getters["free_int"]() })
        fx { spin("free_int").editor.text = "12x"; dlg.commitSpinner(spin("free_int"), "Free int") }
        expect("a_typed_non_integer_is_said_and_the_value_kept", status(dlg).contains("not a number") && fx { dlg.getters["free_int"]() } == -LIMIT, status(dlg))
        fx { dlg.setters["free_int"](2000000000) }
        fx { dlg.setters["free_float"](5e14d) }
        expect("a_float_above_1e12_is_accepted", fx { dlg.getters["free_float"]() } == 5e14d, fx { dlg.getters["free_float"]() })

        // ---- QP-2d: a choice of integers goes back as an integer, not as 2.0
        fx { dlg.setters["num_choice"](2) }
        def got = fx { dlg.getters["num_choice"]() }
        expect("numeric_choice_is_an_integer", got == 2 && got instanceof Long, [got, got?.class])
        expect("numeric_choice_is_written_as_2", LcJson.toJson([num_choice: got]) == '{"num_choice":2}', LcJson.toJson([num_choice: got]))
        def parsed = LcJson.parseText('{"a": 1, "b": 1.5, "c": 2.0, "d": [3, 4.5], "e": 12345678901234}')
        expect("json_whole_numbers_are_long_and_decimals_double", parsed.a instanceof Long && parsed.b instanceof Double && parsed.c instanceof Double && parsed.d[0] instanceof Long && parsed.d[1] instanceof Double && parsed.e == 12345678901234L, parsed)
        expect("a_whole_double_is_shown_without_point_and_a_long_too", LcDialog.show(3.0d) == "3" && LcDialog.show(3L) == "3" && LcDialog.show(2.5d) == "2.5" && LcDialog.show(null) == "(not a finite number)")
        expect("numeric_choice_copies_as_2", LcDialog.pythonLiteral(got) == "2", LcDialog.pythonLiteral(got))

        // ---- QP-2e: a missing required input says the worker template `'L' is required` (an image adds the way out)
        pick(dlg, "formapp", "Required"); Thread.sleep(800)
        fx { dlg.imageBoxes["req_img"].value = LcDialog.FILE_CHOICE }
        fx { dlg.readFormValues() }
        expect("required_image_sentence", status(dlg) == "'Req img' is required: choose an image, or give a file", status(dlg))
        fx { dlg.fileFields["req_img"].text = "/tmp/x.tif" }
        fx { dlg.readFormValues() }
        expect("required_file_sentence_is_the_template", status(dlg) == "'Req file' is required", status(dlg))
        fx { dlg.setters["req_file"]("/tmp/f"); dlg.readFormValues() }
        expect("required_folder_sentence_is_the_template", status(dlg) == "'Req folder' is required", status(dlg))
        fx { dlg.setters["req_folder"]("/tmp"); dlg.readFormValues() }
        expect("required_table_sentence_is_the_template", status(dlg) == "'Req table' is required", status(dlg))

        // ---- QP-2f: NaN and infinity are refused before anything is sent, with the worker's sentence
        fx { dlg.setters["req_table"]("/tmp/t.csv") }
        fx { dlg.setters["req_float"](Double.NaN) }
        def withNaN = fx { dlg.readFormValues() }
        expect("nan_is_refused_before_sending", withNaN == null && status(dlg) == "'Req float' must be a finite number", [withNaN, status(dlg)])
        fx { dlg.setters["req_float"](Double.POSITIVE_INFINITY) }
        def withInf = fx { dlg.readFormValues() }
        expect("infinity_never_reaches_the_worker", withInf == null || (withInf.req_float as double) == 1e15d, withInf?.req_float)      // the box holds it at its limit (1e15), which is finite
        fx { dlg.setters["req_float"](2.5d) }
        def fine = fx { dlg.readFormValues() }
        expect("a_finite_number_goes_through", fine != null && fine.req_float == 2.5d, [fine, status(dlg)])

    } catch (Throwable t) { t.printStackTrace(); results["EXCEPTION"] = false }
    finally { println "RESULTS " + results; Thread.sleep(500); System.exit(results && results.every { it.value } ? 0 : 1) }
}
