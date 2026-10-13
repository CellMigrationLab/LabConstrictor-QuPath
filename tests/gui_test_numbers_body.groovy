// ================================================================== GUI test body: number limits (SPEC_WRONG_SCIENCE section 6; NUM-01..05, HOST_PARITY A06, A07, I06)
// (appended to the tool script by tests/run_gui_test.sh). Needs the `runs` test app (tests/runs_app, registered by tests/ci_setup_apps.sh in LC_SHARED_HOME; copied into the scratch LC_HOME,
// a folder named lhome_numbers). The tool `Numbers` reports what its request carried (the text of each number and its Python type), so a case checks the REAL request, not the box.
// The limits and the sentences are read from conformance.json (limits.form_integer_max, limits.unbounded_float, messages.number_above_maximum / number_below_minimum, number_limits.cases);
// a number typed into a box that is beyond a limit is refused, never clamped: no run starts, nothing is sent, the text stays in the box and the status names the field and the limit.
import javafx.embed.swing.SwingFXUtils
import javax.imageio.ImageIO
import java.util.concurrent.CountDownLatch
import java.awt.image.BufferedImage
import javafx.stage.Window

def SHARED_HOME = new File(System.getenv("LC_SHARED_HOME") ?: System.getenv("LC_HOME"))
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
def pick = { dlg, app, tool -> fx { dlg.appBox.value = app }; fx { dlg.toolBox.value = tool }; Thread.sleep(500) }
def settle = { LcRetention.pruneThreads*.join(20000); LcRetention.pruneThreads.clear() }

Thread.start("lc-gui-test") {
    try {
        def home = new File(System.getenv("LC_HOME")); assert home.path.contains("lhome_numbers")
        home.deleteDir(); def apps = new File(home, "apps"); apps.mkdirs()
        def srcApps = new File(SHARED_HOME, "apps")
        new File(apps, "runs.schema.json").text = new File(srcApps, "runs.schema.json").text
        new File(apps, "runs.json").text = LcJson.toJson(LcJson.parse(new File(srcApps, "runs.json")) + [schema_path: new File(apps, "runs.schema.json").path])
        ["chmod", "644", new File(apps, "runs.json").path, new File(apps, "runs.schema.json").path].execute().waitFor()
        def PY = (LcJson.parse(new File(apps, "runs.json")) as Map).python as String
        def confPath = System.getenv("LC_CONFORMANCE") ?: [PY, "-m", "labconstrictor_tools.conformance", "--path"].execute().text.trim()
        def CONF = LcJson.parse(new File(confPath))
        def NUM = CONF.wrong_science.number_limits
        def numCase = { String id -> def c = NUM.cases.find { it.id == id }; assert c != null : "conformance.json has no case " + id; c }
        LcRetention.pruneCommandFor = { Map entry -> [PY, "-c", "pass"] as List<String> }      // the retention body checks prune; nothing is removed here
        def INT_MAX = 9007199254740991L

        // ---- the data this test stands on exists
        expect("conformance_has_NUM_01_to_05", (1..5).every { numCase(String.format("NUM-%02d", it)) != null } && NUM.limits == ["form_integer_max", "unbounded_float"], NUM.limits)
        expect("the_limits_are_the_conformance_limits", LcConst.FORM_INTEGER_LIMIT == CONF.limits.form_integer_max.value && LcConst.FORM_INTEGER_LIMIT == INT_MAX && LcConst.FLOAT_LIMIT == (CONF.limits.unbounded_float.value as double), [CONF.limits.form_integer_max, CONF.limits.unbounded_float])
        expect("the_sentences_are_the_conformance_templates", LcNumbers.ABOVE_TEMPLATE == CONF.messages.number_above_maximum.template && LcNumbers.BELOW_TEMPLATE == CONF.messages.number_below_minimum.template, [CONF.messages.number_above_maximum, CONF.messages.number_below_minimum])

        def dlg = fx { lcShow() }
        Thread.sleep(800)
        pick(dlg, "runs", "Numbers")
        def spinnerOf = { String n -> fx { dlg.controls[n] } }
        def typeIn = { String n, String text -> fx { def sp = dlg.controls[n]; sp.editor.text = text; null } }
        def textOf = { String n -> fx { dlg.controls[n].editor.text } }
        def runsRoot = new File(home, "runs"), resultsRoot = new File(home, "results")
        def count = { [(runsRoot.list() ?: []).size(), (resultsRoot.list() ?: []).size()] }
        def seen = { (LcJson.parseText((fx { dlg.lastReport } as String).split("\n\nworker output:")[0]) as Map).outputs?.results?.find { it.type == "values" }?.values }
        def pidOf = { fx { dlg.worker?.proc?.pid() } }
        // press Run: returns [started: a run reached the worker, status: the status line]
        def press = {
            def before = count(); def pidBefore = pidOf()
            fx { dlg.run() }; Thread.sleep(1000); waitIdle(dlg, 120); settle()
            [started: count() != before, status: status(dlg), pid: pidOf(), pidBefore: pidBefore]
        }
        def reset = { fx { dlg.setters["whole"](0); dlg.setters["ratio"](0d); dlg.setters["capped"](1); null } }
        def refused = { String field, String text, String sentence, String name ->
            reset(); typeIn(field, text)
            def r = press()
            expect(name + "_is_refused_with_the_sentence", r.status == sentence, [r.status, sentence])
            expect(name + "_starts_no_run_and_sends_nothing", !r.started, r)
            expect(name + "_leaves_the_typed_text_in_the_box", textOf(field) == text, textOf(field))
        }
        def accepted = { String field, String text, Closure check, String name ->
            reset(); typeIn(field, text)
            def r = press()
            expect(name + "_runs", r.started && r.status.startsWith("done"), r)
            def s = seen()
            expect(name + "_the_request_carries_exactly_that_number", s != null && check(s), s)
        }

        // ---- NUM-01: 9007199254740991 is accepted and the request carries exactly that integer
        accepted("whole", "9007199254740991", { s -> s.whole == "9007199254740991" && s.whole_type == "int" }, "NUM-01")
        def rec = LcJson.parse(new File((runsRoot.listFiles() as List).max { it.name }, "run.json")) as Map
        expect("NUM-01_the_run_record_has_the_exact_integer", rec.inputs.whole == INT_MAX && rec.inputs.whole instanceof Long, rec.inputs)
        expect("NUM-01_copy_as_command_has_the_exact_integer", fx { dlg.copyAsCommand("terminal") }.contains("9007199254740991") && fx { dlg.copyAsCommand("python") }.contains("9007199254740991"), "")
        // ---- NUM-02 / NUM-03: one beyond is refused, never clamped (not 9007199254740991, not 2147483647)
        refused("whole", "9007199254740992", "'Whole' must be <= 9007199254740991", "NUM-02")
        expect("NUM-02_the_value_was_never_clamped", fx { dlg.controls["whole"].value } != INT_MAX && fx { dlg.controls["whole"].value } != 2147483647L, fx { dlg.controls["whole"].value })
        refused("whole", "-9007199254740992", "'Whole' must be >= -9007199254740991", "NUM-03")
        expect("NUM-02_the_sentence_is_the_conformance_template_filled", "'Whole' must be <= 9007199254740991" == CONF.messages.number_above_maximum.template.replace("{label}", "Whole").replace("{maximum}", "9007199254740991") && numCase("NUM-02").expect.contains("must be <= 9007199254740991"), numCase("NUM-02"))
        accepted("whole", "-9007199254740991", { s -> s.whole == "-9007199254740991" && s.whole_type == "int" }, "NUM-03_the_lower_limit_itself")
        refused("whole", "99999999999999999999999", "'Whole' must be <= 9007199254740991", "NUM-02_a_number_beyond_a_long")
        refused("whole", "-99999999999999999999999", "'Whole' must be >= -9007199254740991", "NUM-03_a_number_beyond_a_long")
        // ---- NUM-04: a decimal box: 1e15 accepted, 1.0000000000000002e15 refused (also when a double could not tell them apart)
        accepted("ratio", "1e15", { s -> s.ratio == "1000000000000000.0" && s.ratio_type == "float" }, "NUM-04_1e15")
        refused("ratio", "1.0000000000000002e15", "'Ratio' must be <= 1000000000000000.0", "NUM-04_1_0000000000000002e15")
        refused("ratio", "1.00000000000000001e15", "'Ratio' must be <= 1000000000000000.0", "NUM-04_a_text_a_double_rounds_to_1e15")
        refused("ratio", "1e400", "'Ratio' must be <= 1000000000000000.0", "NUM-04_1e400")
        refused("ratio", "1e99999999999", "'Ratio' must be <= 1000000000000000.0", "NUM-04_an_exponent_beyond_BigDecimal")
        accepted("ratio", "-1e15", { s -> s.ratio == "-1000000000000000.0" }, "NUM-04_minus_1e15")
        refused("ratio", "-1.0000000000000002e15", "'Ratio' must be >= -1000000000000000.0", "NUM-04_minus_1_0000000000000002e15")
        accepted("ratio", "0.1", { s -> s.ratio == "0.1" && s.ratio_type == "float" }, "NUM-04_0_1_is_not_rounded")
        accepted("ratio", "123456789012345.67", { s -> s.ratio == "123456789012345.67" }, "NUM-04_a_long_decimal")
        // ---- NUM-05: a declared Max of 10 refuses 11 (it used to be put at 10 and sent)
        accepted("capped", "10", { s -> s.capped == "10" }, "NUM-05_the_declared_maximum_itself")
        refused("capped", "11", "'Capped' must be <= 10", "NUM-05_11")
        expect("NUM-05_the_conformance_case_says_refuse", numCase("NUM-05").expect.contains("refused"), numCase("NUM-05"))
        accepted("capped", "-5", { s -> s.capped == "-5" }, "NUM-05_below_zero_has_no_declared_minimum")

        // ---- the text rules: what is a number
        accepted("whole", "+5", { s -> s.whole == "5" }, "text_plus_five")
        accepted("whole", "007", { s -> s.whole == "7" }, "text_leading_zeros")
        accepted("whole", " 12 ", { s -> s.whole == "12" }, "text_surrounding_spaces_are_not_part_of_the_number")
        refused("whole", "5.0", "'Whole': '5.0' is not a whole number", "text_a_decimal_in_a_whole_number_box")
        refused("whole", "1e3", "'Whole': '1e3' is not a whole number", "text_an_exponent_in_a_whole_number_box")
        refused("whole", "12x", "'Whole': '12x' is not a number", "text_not_a_number")
        refused("whole", "1,5", "'Whole': '1,5' is not a number", "text_a_decimal_comma")
        refused("whole", "1_000", "'Whole': '1_000' is not a number", "text_an_underscore")
        refused("whole", "", "'Whole' is required", "text_empty")
        refused("ratio", "nan", "'Ratio' must be a finite number", "text_nan")
        refused("ratio", "-Infinity", "'Ratio' must be a finite number", "text_infinity")
        refused("whole", "NaN", "'Whole' must be a finite number", "text_nan_in_a_whole_number_box")

        // ---- an optional number: unticked sends nothing; ticked is read like any other box
        reset(); fx { dlg.checks["maybe"].selected = false; null }
        def r0 = press()
        expect("optional_unticked_sends_none", r0.started && seen()?.maybe == null, seen())
        fx { dlg.checks["maybe"].selected = true; null }
        typeIn("maybe", "9007199254740992")
        def r1 = press()
        expect("optional_ticked_beyond_the_limit_is_refused", !r1.started && r1.status == "'Maybe' must be <= 9007199254740991", r1)
        typeIn("maybe", "5")
        def r2 = press()
        expect("optional_ticked_with_a_number_is_sent", r2.started && seen()?.maybe == "5", [r2, seen()])
        fx { dlg.checks["maybe"].selected = false; null }

        // ---- the box itself: focus lost and Enter say it at once, the arrows stop at the limits and never move text that was typed
        reset(); typeIn("whole", "9007199254740993")
        fx { dlg.commitSpinner(dlg.controls["whole"], "Whole"); null }
        expect("focus_lost_says_the_sentence_at_once_and_keeps_the_text", status(dlg) == "'Whole' must be <= 9007199254740991" && textOf("whole") == "9007199254740993" && fx { dlg.controls["whole"].value } == 0L, [status(dlg), textOf("whole")])
        fx { dlg.status.text = "idle"; null }
        fx {
            def editor = dlg.controls["whole"].editor
            javafx.event.Event.fireEvent(editor, new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", javafx.scene.input.KeyCode.ENTER, false, false, false, false))
            null
        }
        expect("enter_says_the_sentence_too", status(dlg) == "'Whole' must be <= 9007199254740991" && textOf("whole") == "9007199254740993", status(dlg))
        fx { dlg.setters["whole"](INT_MAX); dlg.controls["whole"].increment(1); null }
        expect("the_arrow_never_passes_the_upper_limit", fx { dlg.getters["whole"]() } == INT_MAX && textOf("whole") == "9007199254740991", textOf("whole"))
        fx { dlg.setters["whole"](-INT_MAX); dlg.controls["whole"].decrement(5); null }
        expect("the_arrow_never_passes_the_lower_limit", fx { dlg.getters["whole"]() } == -INT_MAX, textOf("whole"))
        fx { dlg.setters["capped"](10); dlg.controls["capped"].increment(1); null }
        expect("the_arrow_stops_at_a_declared_maximum", fx { dlg.getters["capped"]() } == 10L, textOf("capped"))
        fx { dlg.setters["ratio"](1e15d); dlg.controls["ratio"].increment(1); null }
        expect("a_decimal_arrow_stops_at_the_limit", fx { dlg.getters["ratio"]() } == 1e15d, textOf("ratio"))
        fx { dlg.setters["ratio"](0.1d); dlg.controls["ratio"].increment(1); null }
        expect("a_decimal_arrow_adds_without_float_noise", textOf("ratio") == "1.1", textOf("ratio"))
        reset()

        // ---- the helper functions themselves
        expect("unit_python_float_text", [[5d, "5.0"], [1e15d, "1000000000000000.0"], [1e16d, "1e+16"], [0.325d, "0.325"], [1e-5d, "1e-05"], [1.5e20d, "1.5e+20"], [0.0001d, "0.0001"], [-1e15d, "-1000000000000000.0"], [0d, "0.0"]].every { LcNumbers.pythonFloat(it[0] as double) == it[1] },
                [5d, 1e15d, 1e16d, 1e-5d, 1.5e20d].collect { LcNumbers.pythonFloat(it) })
        expect("unit_float_text_reads_back_exactly", [0.1d, 1e15d, 123456789012345.67d, 1e-7d, 5e-324d, 0.30000000000000004d].every { (LcNumbers.floatText(it) as double) == it }, "")
        def sentences = []
        for (def probe : [["9007199254740992", 0L], ["-9007199254740992", 0L]]) { try { LcNumbers.integer("L", probe[0], -INT_MAX, INT_MAX) } catch (LcNumberError e) { sentences << e.message } }
        expect("unit_integer_refusals", sentences == ["'L' must be <= 9007199254740991", "'L' must be >= -9007199254740991"] && LcNumbers.integer("L", "9007199254740991", -INT_MAX, INT_MAX) == INT_MAX, sentences)
        expect("unit_a_declared_decimal_bound_is_written_the_python_way", { try { LcNumbers.decimal("L", "2", 0.5d, 1.5d); "" } catch (LcNumberError e) { e.message } }.call() == "'L' must be <= 1.5" && { try { LcNumbers.decimal("L", "0", 0.5d, 1.5d); "" } catch (LcNumberError e) { e.message } }.call() == "'L' must be >= 0.5" &&
                { try { LcNumbers.decimal("L", "5", 1, 3); "" } catch (LcNumberError e) { e.message } }.call() == "'L' must be <= 3", "")

    } catch (Throwable t) { t.printStackTrace(); results["EXCEPTION"] = false }
    finally { println "RESULTS " + results; Thread.sleep(500); System.exit(results && results.every { it.value } ? 0 : 1) }
}
