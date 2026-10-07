
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
            def dir = new File("/tmp/qp_project4"); dir.deleteDir()
            qupath.lib.gui.prefs.PathPrefs.imageTypeSettingProperty().set(qupath.lib.gui.prefs.PathPrefs.ImageTypeSetting.AUTO_ESTIMATE)
            def project = qupath.lib.projects.Projects.createProject(dir, BufferedImage)
            def server = qupath.lib.images.servers.ImageServers.buildServer("/home/user/LabConstrictor-Fiji/tests/fixtures/three_channels.tif")
            println "SERVER CHANNELS " + server.nChannels() + " " + server.metadata.channels.collect { it.name }
            def entry = project.addImage(server.getBuilder()); entry.setImageName("three_channels.tif")
            project.syncChanges(); qupath.setProject(project); qupath.openImageEntry(entry); entry
        }
        Thread.sleep(2500)
        def dlg = fx { lcShow() }
        Thread.sleep(800)
        pick(dlg, "interactions", "Mean of a channel"); Thread.sleep(1500)
        def channels = fx { dlg.channelBoxes["image"] }
        expect("channel_chooser_lists_the_channels", fx { channels.visible && channels.items.size() == 3 }, fx { [channels.visible, channels.items as List] })
        println "CHANNEL NAMES " + fx { channels.items as List }
        shot("qp4_1_form", dlg.stage)
        for (int c = 0; c < 3; c++) {
            fx { channels.value = channels.items[c] }
            fx { dlg.run() }; Thread.sleep(1500); waitIdle(dlg, 120)
            println "STATUS channel " + (c + 1) + ": " + status(dlg)
            expect("channel_" + (c + 1) + "_is_what_the_tool_receives", status(dlg).contains("mean=" + (10 * (c + 1))) && status(dlg).contains("shape=32 x 32"), status(dlg))
        }
        shot("qp4_2_after", dlg.stage)
    } catch (Throwable t) { t.printStackTrace() }
    finally { println "RESULTS " + results; Thread.sleep(500); System.exit(0) }
}
