// ================================================================== GUI test body: host texts and chooser (QP-2b, 6, 7, 13, 14, 15, 21, results) (appended to the tool script by tests/run_gui_test.sh)
// Needs no real app: it registers synthetic apps (only their schemas are used) in the scratch LC_HOME (a folder named lhome_host); the registered `interactions` app (LC_SHARED_HOME) gives the Python for the round trip.
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
        // two synthetic apps (only their schemas are used; the fake interpreters below are the only thing that is started)
        def home = new File(System.getenv("LC_HOME")); assert home.path.contains("lhome_host")
        home.deleteDir(); def apps = new File(home, "apps"); apps.mkdirs()
        def P = { String name, String type, Map more = [:] -> [name: name, label: name.capitalize().replace("_", " "), type: type, required: more.required != false && !more.nullable] + more }
        def writeApp = { String app, List tools, String file = app ->      // `file`: the name of the registry file, which need not be the app's name
            new File(apps, file + ".schema.json").text = LcJson.toJson([protocol: 1, tools_version: "0.3.0a1", features: ["reserved_inputs_v1", "retention_prune"], tools: tools])
            new File(apps, file + ".json").text = LcJson.toJson([schema: 1, name: app, display_name: app, version: "", prefix: INTERACTIONS.prefix, python: PY,
                    module: "labconstrictor_tools.examples.interactions", pythonpath: [], runtime_path: "", schema_path: new File(apps, file + ".schema.json").path])
            ["chmod", "644", new File(apps, file + ".json").path, new File(apps, file + ".schema.json").path].execute().waitFor()
        }
        writeApp("zeta", [[id: "z1", label: "Zed", description: "", outputs: [], inputs: [P("x", "string", [required: false])]]], "a_file_that_sorts_first")      // found first (files are read by name), listed second (apps are sorted by their own names)
        writeApp("alpha", [
            [id: "d1", label: "Dup", description: "first", outputs: [], inputs: [P("one", "string", [required: false])]],
            [id: "d2", label: "Dup", description: "second", outputs: [], inputs: [P("two", "string", [required: false])]],
            [id: "solo", label: "Solo", description: "", outputs: [], inputs: [P("text", "string", [default: "x"])]],
            [id: "magic", label: "Magic", description: "", outputs: [], inputs: [P("fine", "string"), P("spell", "wand")]],
            [id: "folder", label: "Folder", description: "", outputs: [], inputs: [P("folder", "folder")]],
            [id: "img", label: "Image file", description: "", outputs: [], inputs: [P("pic", "image")]],
            [id: "tab", label: "Table and file", description: "", outputs: [], inputs: [P("tbl", "table"), P("doc", "file")]]])
        def dlg = fx { lcShow() }
        Thread.sleep(800)

        // ---- QP-21: the app list is sorted by name; two tools with one label are told apart by `label (id)`
        expect("apps_are_sorted_by_name", fx { dlg.appBox.items as List } == ["alpha", "zeta"], fx { dlg.appBox.items as List })
        expect("the_first_app_shown_is_the_first_by_name", fx { dlg.appBox.value } == "alpha", fx { dlg.appBox.value })
        expect("tools_with_one_label_are_listed_with_their_id", fx { dlg.toolBox.items as List } == ["Dup (d1)", "Dup (d2)", "Solo", "Magic", "Folder", "Image file", "Table and file"], fx { dlg.toolBox.items as List })
        fx { dlg.toolBox.value = "Dup (d2)" }
        expect("the_second_duplicate_can_be_chosen", fx { dlg.currentTool.id } == "d2" && fx { dlg.description.text } == "second", fx { dlg.currentTool?.id })
        fx { dlg.toolBox.value = "Dup (d1)" }
        expect("the_first_duplicate_is_still_the_first", fx { dlg.currentTool.id } == "d1")
        fx { dlg.toolBox.value = "Solo" }
        expect("a_unique_label_is_shown_alone", fx { dlg.currentTool.id } == "solo")

        // ---- QP-14: a tool with a parameter type this host does not know is refused (that tool only), with the Napari sentence
        fx { dlg.toolBox.value = "Magic" }
        expect("unknown_type_says_so_in_the_status", status(dlg) == "this tool cannot be shown: parameter 'spell' has the unknown type 'wand'", status(dlg))
        expect("unknown_type_has_no_guessed_form", fx { dlg.formBox.children.isEmpty() && dlg.runButton.disable }, fx { dlg.formBox.children.size() })
        expect("unknown_type_is_logged", LcLog.recent.any { it.contains("cannot build the form for magic") }, LcLog.recent)
        fx { dlg.toolBox.value = "Solo" }
        expect("the_next_tool_of_the_app_is_usable_again", fx { !dlg.runButton.disable && !dlg.formBox.children.isEmpty() }, status(dlg))
        expect("unsupported_parameter_unit", LcDialog.unsupportedParameter([inputs: [[name: "a", type: "string"], [name: "b", type: "table"]]]) == null && LcDialog.unsupportedParameter([inputs: [[name: "b", type: "tabel"]]]) == "parameter 'b' has the unknown type 'tabel'")

        // ---- QP-2b: a folder input must be a folder (a regular file is refused with the worker's template, the sentence alone)
        fx { dlg.toolBox.value = "Folder" }
        fx { dlg.setters["folder"]("/etc/hostname") }
        fx { dlg.run() }; Thread.sleep(1500); waitIdle(dlg, 60)
        expect("a_file_is_not_a_folder", status(dlg) == "'Folder': folder not found: /etc/hostname", status(dlg))
        fx { dlg.setters["folder"](WP + "/no_such_folder") }
        fx { dlg.run() }; Thread.sleep(1500); waitIdle(dlg, 60)
        expect("a_missing_folder_has_the_same_sentence", status(dlg) == "'Folder': folder not found: " + WP + "/no_such_folder", status(dlg))
        def tmpF = java.nio.file.Files.createTempDirectory("lcf_").toFile()
        expect("a_real_folder_passes", dlg.prepareInput([type: "folder", label: "Folder"], "folder", tmpF.path, tmpF, "alpha") == tmpF.absolutePath)
        tmpF.deleteDir()

        // ---- QP-2 (F01, F02, F06): a missing image file is refused with the Napari / Fiji sentence alone; a missing table or file is left to the worker, which answers it
        fx { dlg.toolBox.value = "Image file" }
        fx { dlg.imageBoxes["pic"].value = LcDialog.FILE_CHOICE; dlg.fileFields["pic"].text = WP + "/no_such_image.tif" }
        fx { dlg.stopWorker(true) }
        fx { dlg.run() }; Thread.sleep(1500); waitIdle(dlg, 60)
        expect("a_missing_image_file_is_the_sentence_alone", status(dlg) == "'Pic': file not found: " + WP + "/no_such_image.tif", status(dlg))
        expect("a_missing_image_file_started_no_worker", fx { dlg.worker == null })
        expect("a_directory_is_not_an_image_file", { try { LcDialog.existingFile([label: "Pic"], "/tmp"); false } catch (LcRefusal e) { e.message == "'Pic': file not found: /tmp" } }())
        fx { dlg.toolBox.value = "Table and file" }
        fx { dlg.setters["tbl"](WP + "/none.csv"); dlg.setters["doc"](WP + "/none.txt") }
        fx { dlg.run() }; Thread.sleep(1500); waitIdle(dlg, 60)
        expect("a_missing_table_reaches_the_worker", fx { dlg.worker != null } && status(dlg).startsWith("failed: ") && !status(dlg).contains("file not found: " + WP + "/none"), status(dlg))
        fx { dlg.stopWorker(true) }

        // ---- QP-6: a text value in the Python snippet is written the way Python's repr writes it, and reads back the same
        def lit = { String s -> LcDialog.pythonLiteral(s) }
        expect("literal_newline_is_escaped", lit("a\nb") == "'a\\nb'", lit("a\nb"))
        expect("literal_cr_and_tab_are_escaped", lit("a\r\tb") == "'a\\r\\tb'", lit("a\r\tb"))
        expect("literal_single_quote_switches_to_double_quotes", lit("it's") == "\"it's\"", lit("it's"))
        expect("literal_double_quote_stays_in_single_quotes", lit('say "hi"') == "'say \"hi\"'", lit('say "hi"'))
        expect("literal_both_quotes_escape_the_single_one", lit("it's \"x\"") == "'it\\'s \"x\"'", lit("it's \"x\""))
        expect("literal_backslash_is_doubled", lit("C:\\x") == "'C:\\\\x'", lit("C:\\x"))
        expect("literal_control_characters_are_hex", lit("\u0001\u007f") == "'\\x01\\x7f'", lit("\u0001\u007f"))
        expect("literal_invisible_formatting_is_escaped_but_letters_stay", lit("\u200bé") == "'\\u200bé'", lit("\u200bé"))
        expect("literal_numbers_and_booleans", LcDialog.pythonLiteral(2L) == "2" && LcDialog.pythonLiteral(0.5d) == "0.5" && LcDialog.pythonLiteral(true) == "True" && LcDialog.pythonLiteral(false) == "False")
        def samples = ["plain", "a\nb", "a\r\n\tb", "it's", 'say "hi"', "it's \"x\"", "C:\\new\\table", "\u0001\u007f\u0085", "\u200b\u2028é\u00e8\ud83d\ude00", "", " ", "tab\there", "'''\"\"\""]
        def pairs = new File(WP + "/literals.json"); pairs.text = LcJson.toJson(samples.collect { [lit(it), it] })
        def pb = new ProcessBuilder([PY, "-c", "import json, sys\nfor lit, original in json.load(open(sys.argv[1], encoding='utf-8')):\n    assert eval(lit) == original, (lit, original)\nprint('ROUNDTRIP-OK', len(json.load(open(sys.argv[1], encoding='utf-8'))))", pairs.path])
        pb.redirectErrorStream(true)
        def check = pb.start()
        def out = check.inputStream.getText("UTF-8"); check.waitFor()
        expect("every_literal_reads_back_in_python_as_the_same_text", out.contains("ROUNDTRIP-OK " + samples.size()), out)
        fx { dlg.toolBox.value = "Solo" }
        fx { dlg.setters["text"]("it's \"x\" C:\\new") }      // a text box holds no tab or line break, so the escapes of those are checked on the literal above; here the quotes and the backslash
        def snippet = fx { dlg.copyAsCommand("python") }
        expect("copy_as_python_escapes_quotes_and_a_backslash_in_a_value", snippet.contains("'text': 'it\\'s \"x\" C:\\\\new',"), snippet)

        // ---- QP-7: a message result: bold removed, list items as bullets
        fx { dlg.addResult(new VBox(), "alpha", [type: "message", text: "**Done**\n- one\n* two\n  - three\nplain - dash"]) }
        expect("message_renders_bullets", fx { dlg.messageLabel.text } == "Done\n\u2022 one\n\u2022 two\n\u2022 three\nplain - dash", fx { dlg.messageLabel.text })

        // ---- result types: a file the tool listed but that does not exist, and a type this version does not know
        def box = new VBox()
        fx { dlg.addResult(box, "alpha", [type: "file", name: "report", path: WP + "/no_such_report.txt"]) }
        def texts = { b -> fx { b.children.collect { it.text } } }
        expect("missing_result_file_is_said", texts(box).contains("could not show the result 'report': the tool reported the file " + WP + "/no_such_report.txt but it does not exist"), texts(box))
        def box2 = new VBox()
        fx { dlg.addResult(box2, "alpha", [type: "hologram", name: "holo", path: "/tmp/x.holo"]) }
        expect("unknown_result_type_gets_one_sentence_and_the_path", texts(box2).contains("the tool returned a result of the type 'hologram', which this version of QuPath LabConstrictor cannot show: /tmp/x.holo"), texts(box2))
        def box3 = new VBox()
        fx { dlg.addResult(box3, "alpha", [type: "file", name: "real", path: "/etc/hostname"]) }
        expect("an_existing_result_file_is_listed_by_path", texts(box3).contains("/etc/hostname"), texts(box3))

        // ---- QP-15: a copy that cannot be made is said, never reported as done
        fx { dlg.toolBox.value = "Solo" }
        dlg.metaClass.putOnClipboard = { String text -> "IllegalStateException: no display" }
        def copied = fx { dlg.copyAsCommand("terminal") }
        expect("failed_copy_says_there_is_no_clipboard", status(dlg) == "there is no clipboard to copy to" && copied != null && fx { dlg.lastCopied } == copied, status(dlg))
        expect("failed_copy_puts_the_command_in_the_log", LcLog.recent.any { it.contains("no clipboard to copy the terminal command to (IllegalStateException: no display)") && it.contains("labconstrictor_tools") }, LcLog.recent.takeRight(3))
        dlg.metaClass.putOnClipboard = null
        expect("a_clipboard_that_takes_the_text_is_no_problem", fx { dlg.putOnClipboard("x", { c -> c.string == "x" }) } == null)
        expect("a_clipboard_that_refuses_is_said", fx { dlg.putOnClipboard("x", { c -> false }) } == "the clipboard refused the text")
        expect("a_clipboard_that_throws_is_said_with_its_reason", fx { dlg.putOnClipboard("x", { c -> throw new IllegalStateException("no display") }) } == "IllegalStateException: no display")

        // ---- QP-13: the crash text uses the Tools sentences (a missing package first, then the exit code), the exit code and the worker's own last words
        expect("hint_missing_package", LcWorker.hintForExit(1, "Traceback...\nModuleNotFoundError: No module named 'x'") == "a Python package is missing or broken in the app's environment (see the traceback above)")
        expect("hint_import_error_too", LcWorker.hintForExit(1, "ImportError: DLL load failed").startsWith("a Python package is missing"))
        expect("hint_missing_package_beats_the_exit_code", LcWorker.hintForExit(137, "ModuleNotFoundError").startsWith("a Python package is missing"))
        expect("hint_killed", [-9, 137].every { LcWorker.hintForExit(it, "") == "the worker was killed (out of memory? the OS OOM killer ends big image jobs this way)" })
        expect("hint_native_crash_in_all_four_forms", [-11, 139, -1073741819].every { LcWorker.hintForExit(it, "") == "the worker crashed natively (segmentation fault in a compiled library)" } && LcWorker.hintForExit(null, "") != null)
        expect("hint_unsigned_windows_access_violation", (3221225477L as Long) in LcConst.NATIVE_CRASH_CODES)
        expect("hint_tool_import_failed", LcWorker.hintForExit(3, "") == "the app's tool module failed to import (see the traceback above)")
        expect("hint_unknown_cause_is_a_plain_sentence", LcWorker.hintForExit(1, "boom") == "the worker process stopped unexpectedly")
        def fake = { String name, String stderr, int code ->
            def f = new File(WP + "/" + name); f.text = "#!/bin/sh\necho \"" + stderr + "\" >&2\nexit " + code + "\n"; f.setExecutable(true); f
        }
        def crashOf = { File script -> def w = new LcWorker([python: script.path, module: "m", pythonpath: [], runtime_path: ""]); try { w.crashText() } finally { w.kill() } }
        def c1 = crashOf(fake("fake_missing.sh", "ModuleNotFoundError: No module named 'foo'", 1))
        expect("live_crash_missing_package", c1.startsWith("a Python package is missing or broken in the app's environment (see the traceback above) (exit code 1). ") && c1.contains("No module named 'foo'"), c1)
        def c2 = crashOf(fake("fake_killed.sh", "", 137))
        expect("live_crash_killed", c2 == "the worker was killed (out of memory? the OS OOM killer ends big image jobs this way) (exit code 137)", c2)
        def c3 = crashOf(fake("fake_import.sh", "tool module broke", 3))
        expect("live_crash_import_failed_with_the_last_words", c3.startsWith("the app's tool module failed to import (see the traceback above) (exit code 3). tool module broke"), c3)

    } catch (Throwable t) { t.printStackTrace(); results["EXCEPTION"] = false }
    finally { println "RESULTS " + results; Thread.sleep(500); System.exit(results && results.every { it.value } ? 0 : 1) }
}
