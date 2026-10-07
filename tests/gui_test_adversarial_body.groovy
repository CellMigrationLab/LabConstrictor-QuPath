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
            def dir = new File("/tmp/qp_project3"); dir.deleteDir()
            qupath.lib.gui.prefs.PathPrefs.imageTypeSettingProperty().set(qupath.lib.gui.prefs.PathPrefs.ImageTypeSetting.AUTO_ESTIMATE)
            def project = qupath.lib.projects.Projects.createProject(dir, BufferedImage)
            def server = qupath.lib.images.servers.ImageServers.buildServer(FX + "/reference.tif")
            def entry = project.addImage(server.getBuilder()); entry.setImageName("reference.tif")
            project.syncChanges(); qupath.setProject(project); qupath.openImageEntry(entry); entry
        }
        Thread.sleep(2500)
        def dlg = fx { lcShow() }
        Thread.sleep(800)
        pick(dlg, "adv", "Pick"); Thread.sleep(3500)
        def many = fx { dlg.choiceBoxes["mode"] }
        expect("5000_options_fill_a_dropdown", fx { many.visible && many.items.size() >= 5000 && many.items.any { it.contains("\u4e2d") } }, fx { many.items.size() })
        expect("broken_source_keeps_text_field", fx { dlg.controls["a"].visible && !dlg.choiceBoxes["a"].visible })
        expect("non_list_source_keeps_text_field", fx { dlg.controls["b"].visible && !dlg.choiceBoxes["b"].visible })
        fx { dlg.choiceBoxes["c"].value = "opt 00042 \u00e9\u4e2d"; dlg.controls["a"].text = "free"; dlg.checks["a"].selected = true }
        fx { dlg.run() }; Thread.sleep(1500); waitIdle(dlg, 120)
        println "STATUS_PICK " + status(dlg)
        expect("run_with_unicode_choice_ok", status(dlg).contains("opt 00042"), status(dlg))
        expect("default_not_listed_by_the_source_is_kept", status(dlg).contains("mode=x"), status(dlg))
        expect("clear_after_run", fx { dlg.choiceBoxes["c"].value == "" && dlg.controls["a"].text == "" && !dlg.checks["a"].selected })
        pick(dlg, "adv", "Dims"); Thread.sleep(800)
        fx { dlg.setters["text_length"](3000) }
        fx { dlg.run() }; Thread.sleep(1500); waitIdle(dlg, 120)
        expect("long_message_ok", fx { dlg.messageLabel.visible && dlg.runButton.disable == false }, status(dlg))
        fx { dlg.setters["rows"](0) }
        fx { dlg.run() }; Thread.sleep(1500); waitIdle(dlg, 120)
        expect("zero_points_table_is_fine", status(dlg).startsWith("done"), status(dlg))
        fx { dlg.setters["rows"](50000) }
        fx { dlg.run() }; Thread.sleep(3000); waitIdle(dlg, 180)
        expect("fifty_thousand_points_table_is_fine", status(dlg).startsWith("done"), status(dlg))
        fx { dlg.setters["fail"](true) }
        fx { dlg.run() }; Thread.sleep(1500); waitIdle(dlg, 120)
        expect("failure_is_readable_and_hides_message", status(dlg).contains("deliberate failure") && fx { !dlg.messageLabel.visible }, status(dlg))
        pick(dlg, "adv", "Spots"); Thread.sleep(800)
        fx { dlg.setters["rows"](0) }
        fx { dlg.run() }; Thread.sleep(2000); waitIdle(dlg, 120)
        println "STATUS_SPOTS0 " + status(dlg)
        expect("zero_points_on_an_image_is_not_a_crash", status(dlg).startsWith("done") || status(dlg).contains("no "), status(dlg))
        fx { dlg.setters["rows"](5) }
        fx { dlg.run() }; Thread.sleep(2000); waitIdle(dlg, 120)
        fx { dlg.run() }; Thread.sleep(2000); waitIdle(dlg, 120)
        def anns = fx { qupath.imageData.hierarchy.annotationObjects.collect { it.name + ":" + it.ROI.numPoints } }
        println "ANNOTATIONS " + anns
        expect("replace_leaves_one_annotation", anns.count { it.startsWith("adv:pts:") } == 1, anns)
    } catch (Throwable t) { t.printStackTrace() }
    finally { println "RESULTS " + results; Thread.sleep(500); System.exit(0) }
}
