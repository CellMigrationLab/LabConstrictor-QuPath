// ================================================================== GUI test body: result tables (QP-11) (appended to the tool script by tests/run_gui_test.sh)
// Needs the `interactions` example app of labconstrictor-tools in LC_HOME (its python needs tifffile) and the sample images of tests/fixtures.
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
        fx {
            def dir = new File("/tmp/qp_project_parity_results"); dir.deleteDir()
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

        // ---- QP-11: result tables show up to 100 000 rows (Napari's limit), say so in the Napari sentence, and read multi-line cells
        def tmpDir = java.nio.file.Files.createTempDirectory("lcres_").toFile()
        def csv = { String name, String text -> def f = new File(tmpDir, name); f.setText(text, "UTF-8"); f.path }
        def tableOf = { String path, String name -> fx { LcDialog.tableView(path, name) } }
        def tableItems = { node -> fx { node.children[0].items } }
        def labelOf = { node -> fx { node.children[1].text } }
        expect("rows_cap_is_100000", LcConst.TABLE_ROWS_SHOWN == 100000, LcConst.TABLE_ROWS_SHOWN)
        // 150 000 rows: the first 100 000 are shown, the sentence counts them all
        def big = new StringBuilder("a,b\n"); for (int i = 0; i < 150000; i++) big.append(i).append(",x").append(i).append("\n")
        def bigNode = tableOf(csv("big.csv", big.toString()), "big")
        expect("big_table_shows_100000_rows", tableItems(bigNode).size() == 100000, tableItems(bigNode).size())
        expect("big_table_last_shown_row_is_99999", tableItems(bigNode)[99999] == ["99999", "x99999"], tableItems(bigNode)[99999])
        expect("big_table_sentence_is_napari_s", labelOf(bigNode).startsWith("table 'big' (150000 rows, first 100000 shown; the full table is in " + tmpDir.path), labelOf(bigNode))
        // exactly the cap and one under it: no notice
        def capText = new StringBuilder("a\n"); for (int i = 0; i < 100000; i++) capText.append(i).append("\n")
        def capNode = tableOf(csv("cap.csv", capText.toString()), "cap")
        expect("table_at_the_cap_has_no_notice", tableItems(capNode).size() == 100000 && labelOf(capNode).startsWith("table 'cap' (100000 rows)") && !labelOf(capNode).contains("shown"), labelOf(capNode))
        def small = tableOf(csv("small.csv", "a,b\n1,2\n3,4\n"), "small")
        expect("small_table_says_its_rows", tableItems(small).size() == 2 && labelOf(small).startsWith("table 'small' (2 rows)"), labelOf(small))
        // a table of 2 001 rows used to be cut at 2 000
        def mid = new StringBuilder("a\n"); for (int i = 0; i < 2001; i++) mid.append(i).append("\n")
        expect("table_of_2001_rows_is_whole", tableItems(tableOf(csv("mid.csv", mid.toString()), "mid")).size() == 2001, "")
        // CSV grammar: quoted commas, doubled quotes, line breaks inside a cell, CRLF, no final newline, empty cells
        def odd = tableOf(csv("odd.csv", "name,note,n\r\n\"a,b\",\"say \"\"hi\"\"\",1\r\n\"two\nlines\",,2\r\nlast,\"\",3"), "odd")
        def items = tableItems(odd)
        expect("csv_quoted_comma_and_doubled_quote", items.size() == 3 && items[0] == ["a,b", "say \"hi\"", "1"], items)
        expect("csv_multi_line_cell_is_one_row", items[1] == ["two\nlines", "", "2"], items[1])
        expect("csv_last_row_without_newline_and_empty_quoted_cell", items[2] == ["last", "", "3"], items[2])
        expect("csv_header_is_the_columns", fx { odd.children[0].columns*.text } == ["name", "note", "n"], fx { odd.children[0].columns*.text })
        expect("empty_file_is_an_empty_table", fx { LcDialog.tableView(csv("empty.csv", ""), "e") }.with { it instanceof Label && it.text == "(empty table)" }, "")

        // ---- issue 17: one reader (LcCsv) for tables and points; a quoted line break never makes a second record, a malformed file is a sentence, not a guess
        def errorOf = { Closure c -> try { c(); return null } catch (LcCsvError e) { return e.message } }
        def noteFile = csv("points_note.csv", "y,x,note\n1,2,\"first line\nsecond line\"\n3.5,4.5,\"a,b \"\"q\"\" \u00e9\u4e2d\"\n5,6,\n")
        def found = LcCsv.points(noteFile)
        expect("issue17_points_multiline_record_is_one_point", found.ys == [1d, 3.5d, 5d] && found.xs == [2d, 4.5d, 6d], found)
        def noteRows = LcCsv.read(noteFile, 100)
        expect("issue17_read_keeps_the_full_multiline_text", noteRows.count == 4 && noteRows.rows[1] == ["1", "2", "first line\nsecond line"] && noteRows.rows[2][2] == "a,b \"q\" \u00e9\u4e2d" && noteRows.rows[3][2] == "", noteRows)
        expect("issue17_rows_beyond_keep_are_counted_by_record", LcCsv.read(noteFile, 2).with { it.rows.size() == 2 && it.count == 4 }, "")
        expect("issue17_byte_order_mark_is_dropped", LcCsv.read(csv("bom.csv", "\ufeffy,x\n1,2\n"), 10).rows[0] == ["y", "x"], "")
        expect("issue17_crlf_and_missing_last_newline", LcCsv.read(csv("crlf.csv", "y,x\r\n1,2\r\n3,4"), 10).rows == [["y", "x"], ["1", "2"], ["3", "4"]], "")
        expect("issue17_points_without_x_column_is_a_sentence", errorOf { LcCsv.points(csv("no_x.csv", "y,z\n1,2\n")) }?.contains("has no 'x' column"), errorOf { LcCsv.points(csv("no_x.csv", "y,z\n1,2\n")) })
        expect("issue17_points_empty_file_is_a_sentence", errorOf { LcCsv.points(csv("none.csv", "")) }?.contains("the points file is empty"), "")
        expect("issue17_points_short_record_is_a_sentence", errorOf { LcCsv.points(csv("short.csv", "y,x,n\n1,2,a\n3,4\n")) }?.contains("record 3 has 2 cell(s), the header has 3"), errorOf { LcCsv.points(csv("short.csv", "y,x,n\n1,2,a\n3,4\n")) })
        expect("issue17_points_text_coordinate_is_a_sentence", errorOf { LcCsv.points(csv("text.csv", "y,x\n1,two\n")) }?.contains("record 2: 'x' is 'two', not a number"), errorOf { LcCsv.points(csv("text.csv", "y,x\n1,two\n")) })
        expect("issue17_points_nan_coordinate_is_a_sentence", errorOf { LcCsv.points(csv("nan.csv", "y,x\nNaN,2\n")) }?.contains("not a finite number"), "")
        expect("issue17_unclosed_quote_names_the_record_and_line", errorOf { LcCsv.read(csv("open.csv", "a,b\n1,\"never closed\nstill open\n"), 10) }?.contains("record 2 (line 2): a quoted cell is never closed"), errorOf { LcCsv.read(csv("open.csv", "a,b\n1,\"never closed\nstill open\n"), 10) })
        expect("issue17_text_after_closing_quote_is_a_sentence", errorOf { LcCsv.read(csv("after.csv", "a\n\"x\"y\n"), 10) }?.contains("record 2 (line 2): text after the closing quote"), "")
        expect("issue17_stray_quote_in_unquoted_cell_is_a_sentence", errorOf { LcCsv.read(csv("stray.csv", "a\nx\"y\n"), 10) }?.contains("a quote inside a cell that was not opened with one"), "")
        def badTable = fx { LcDialog.tableView(csv("bad_table.csv", "a,b\n1,\"open\n"), "bad") }
        expect("issue17_malformed_table_says_why_instead_of_showing_rows", badTable instanceof Label && fx { badTable.text.startsWith("table 'bad' cannot be shown: ") && badTable.text.contains("a quoted cell is never closed") }, fx { badTable.text })
        tmpDir.deleteDir()

    } catch (Throwable t) { t.printStackTrace(); results["EXCEPTION"] = false }
    finally { println "RESULTS " + results; Thread.sleep(500); System.exit(results && results.every { it.value } ? 0 : 1) }
}
