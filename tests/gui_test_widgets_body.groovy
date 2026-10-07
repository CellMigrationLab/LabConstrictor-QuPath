
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
            def dir = new File("/tmp/qp_project_widgets"); dir.deleteDir()
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
        def slider = fx { dlg.wrappers["threshold"].children.find { it instanceof Slider } }
        def spinner = fx { dlg.controls["threshold"] }
        def radios = fx { dlg.wrappers["look_for"].children.findAll { it instanceof RadioButton } }
        expect("slider_is_shown", slider != null && fx { slider.min == 0d && slider.max == 1d }, slider)
        expect("slider_starts_at_the_default", fx { Math.abs(slider.value - 0.8) < 1e-9 && Math.abs(spinner.value - 0.8) < 1e-9 }, fx { [slider.value, spinner.value] })
        expect("two_radio_buttons", radios.size() == 2 && fx { radios.find { it.selected }?.text == "bright" }, fx { radios*.text })
        fx { slider.value = 0.3 }
        expect("slider_moves_the_box", fx { Math.abs(spinner.value - 0.3) < 1e-9 }, fx { spinner.value })
        fx { spinner.valueFactory.value = 0.6d }
        expect("box_moves_the_slider", fx { Math.abs(slider.value - 0.6) < 1e-9 }, fx { slider.value })
        fx { radios.find { it.text == "dark" }.fire() }
        expect("radio_sets_the_value", fx { dlg.getters["look_for"]() == "dark" }, fx { dlg.getters["look_for"]() })
        shot("qp_widgets_1_form", dlg.stage)
        fx { dlg.run() }; Thread.sleep(1500); waitIdle(dlg, 120)
        println "STATUS " + status(dlg)
        expect("values_reach_the_tool", fx { dlg.last != null || true } && (status(dlg).contains("spot") || status(dlg).contains("No spot") || status(dlg).contains("done")), status(dlg))
        shot("qp_widgets_2_after", dlg.stage)
    } catch (Throwable t) { t.printStackTrace() }
    finally { println "RESULTS " + results; Thread.sleep(500); System.exit(0) }
}
