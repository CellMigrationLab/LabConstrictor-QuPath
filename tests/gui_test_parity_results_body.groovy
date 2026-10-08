// ================================================================== GUI test body: result tables (QP-11) (appended to the tool script by tests/run_gui_test.sh)
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
        tmpDir.deleteDir()

    } catch (Throwable t) { t.printStackTrace() }
    finally { println "RESULTS " + results; Thread.sleep(500); System.exit(0) }
}
