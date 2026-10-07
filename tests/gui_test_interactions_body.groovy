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
        def entries = fx {
            def dir = new File("/tmp/qp_project2"); dir.deleteDir()
            qupath.lib.gui.prefs.PathPrefs.imageTypeSettingProperty().set(qupath.lib.gui.prefs.PathPrefs.ImageTypeSetting.AUTO_ESTIMATE)
            def project = qupath.lib.projects.Projects.createProject(dir, BufferedImage)
            def server = qupath.lib.images.servers.ImageServers.buildServer(FX + "/reference.tif")
            def entry = project.addImage(server.getBuilder()); entry.setImageName("reference.tif")
            project.syncChanges(); qupath.setProject(project); qupath.openImageEntry(entry); entry
        }
        Thread.sleep(2500)
        def dlg = fx { lcShow() }
        Thread.sleep(800)
        println "APPS " + fx { dlg.appBox.items as List }
        pick(dlg, "interactions", "Show a shape"); Thread.sleep(2500)
        def combo = fx { dlg.choiceBoxes["shape"] }
        expect("dropdown_filled", fx { combo.visible && (combo.items as List) == ["", "square", "bar", "dot"] }, fx { [combo.visible, combo.items as List] })
        expect("text_field_hidden_behind_dropdown", fx { !dlg.controls["shape"].visible })
        expect("options_group_starts_folded", fx { dlg.formBox.children.any { it instanceof TitledPane && it.text == "Options" && !it.expanded } })
        shot("qp2_1_form", dlg.stage)
        fx { combo.value = "square" }
        expect("picking_ticks_set", fx { dlg.checks["shape"].selected })
        fx { dlg.run() }; Thread.sleep(1500); waitIdle(dlg, 120)
        println "STATUS1 " + status(dlg)
        expect("message_shown", fx { dlg.messageLabel.visible && dlg.messageLabel.text.contains("square") }, fx { dlg.messageLabel.text })
        expect("field_cleared_after_run", fx { combo.value == "" && !dlg.checks["shape"].selected }, fx { [combo.value, dlg.checks["shape"].selected] })
        shot("qp2_2_after_first_run", dlg.stage)
        def rs1 = fx { resultStages() }
        println "RESULT STAGES 1: " + fx { rs1.collect { it.title } }
        if (rs1) shot("qp2_3_results_square", rs1[0])
        fx { combo.value = "bar" }
        fx { dlg.run() }; Thread.sleep(1500); waitIdle(dlg, 120)
        def rs2 = fx { resultStages() }
        expect("replace_keeps_one_results_window", rs2.size() == 1, rs2.collect { it.title })
        expect("message_updated", fx { dlg.messageLabel.text.contains("bar") && !dlg.messageLabel.text.contains("square") }, fx { dlg.messageLabel.text })
        if (rs2) shot("qp2_4_results_bar", rs2[0])
        pick(dlg, "interactions", "Find bright spots"); Thread.sleep(800)
        println "IMAGE CHOICE " + fx { dlg.imageBoxes["image"].value }
        fx { dlg.run() }; Thread.sleep(2000); waitIdle(dlg, 120)
        println "STATUS_SPOTS " + status(dlg)
        def annotations = fx { qupath.imageData.hierarchy.annotationObjects.collect { it.name + ":" + it.ROI.numPoints } }
        println "ANNOTATIONS " + annotations
        expect("points_become_an_annotation", annotations.any { it.startsWith("interactions:spots:") }, annotations)
        def rs3 = fx { resultStages() }
        if (rs3) shot("qp2_5_results_spots", rs3.last())
        shot("qp2_6_main", dlg.stage)
    } catch (Throwable t) { t.printStackTrace() }
    finally {
        println "RESULTS " + results
        Thread.sleep(500); System.exit(0)
    }
}
