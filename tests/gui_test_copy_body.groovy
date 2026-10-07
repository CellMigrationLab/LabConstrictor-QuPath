
// ================================================================== GUI test body (appended to the tool script by tests/run_gui_test.sh)
import javafx.embed.swing.SwingFXUtils
import javax.imageio.ImageIO
import java.util.concurrent.CountDownLatch
import java.awt.image.BufferedImage
import javafx.stage.Window

def OUT = new File(System.getProperty("lc.test.out", "/tmp/qp_shots")); OUT.mkdirs()
def FX = "/home/tester/q/fx"
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
    fx {
        def img = st.scene.snapshot(null)
        ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", new File(OUT, name + ".png"))
    }
    println "SHOT " + name
}
def waitIdle = { dlg, int seconds = 300 ->
    def end = System.currentTimeMillis() + seconds * 1000
    while (System.currentTimeMillis() < end) { Thread.sleep(200); if (!dlg.running && fx { !dlg.runButton.disable }) return true }
    return false
}
def status = { dlg -> fx { dlg.status.text } }
def pick = { dlg, app, tool -> fx { dlg.appBox.value = app }; fx { dlg.toolBox.value = tool } }
def resultStages = { Window.getWindows().findAll { it instanceof Stage && it.showing && it.title?.contains(": ") && it.title != "LabConstrictor: last run" } }


Thread.start("lc-gui-test") {
    try {
        fx {
            def dir = new File("/tmp/qp_project_copy"); dir.deleteDir()
            qupath.lib.gui.prefs.PathPrefs.imageTypeSettingProperty().set(qupath.lib.gui.prefs.PathPrefs.ImageTypeSetting.AUTO_ESTIMATE)
            def project = qupath.lib.projects.Projects.createProject(dir, BufferedImage)
            def server = qupath.lib.images.servers.ImageServers.buildServer("/home/user/LabConstrictor-Fiji/tests/fixtures/sample.tif")
            def entry = project.addImage(server.getBuilder()); entry.setImageName("sample.tif")
            project.syncChanges(); qupath.setProject(project); qupath.openImageEntry(entry); entry
        }
        Thread.sleep(2500)
        def dlg = fx { lcShow() }
        Thread.sleep(800)
        pick(dlg, "interactions", "Find bright spots"); Thread.sleep(1500)
        fx { dlg.setters["threshold"](0.5d) }
        def radios = fx { dlg.wrappers["look_for"].children.findAll { it instanceof RadioButton } }
        fx { radios.find { it.text == "dark" }.fire() }
        def line = fx { dlg.copyAsCommand("terminal") }
        expect("terminal_line_names_app_tool_and_values", line != null && line.contains("run interactions find_bright_spots") && line.contains("threshold=0.5") && line.contains("look_for=dark"), line)
        expect("image_file_is_used", line != null && line.contains("image=/home/user/LabConstrictor-Fiji/tests/fixtures/sample.tif"), line)
        expect("clipboard_holds_the_line", fx { javafx.scene.input.Clipboard.systemClipboard.getString() } == line, line)
        def snippet = fx { dlg.copyAsCommand("python") }
        expect("python_snippet_has_real_values", snippet != null && snippet.contains("client.run_once('interactions', 'find_bright_spots'") && snippet.contains("'look_for': 'dark'") && snippet.contains("'threshold': 0.5"), snippet)
        new File("/tmp/gtc/qp_copied_line.txt").text = line
        shot("qp_copy_1_form", dlg.stage)
    } catch (Throwable t) { t.printStackTrace() }
    finally { println "RESULTS " + results; Thread.sleep(500); System.exit(0) }
}
