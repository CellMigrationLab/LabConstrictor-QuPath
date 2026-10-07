
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
        Thread.sleep(2500)
        def dlg = fx { lcShow() }
        Thread.sleep(800)
        def A = "LabConstrictorPlayground"
        expect("app_listed", fx { dlg.appBox.items as List }.contains(A), fx { dlg.appBox.items as List })
        pick(dlg, A, "Check everything"); Thread.sleep(600)
        fx { dlg.run() }; Thread.sleep(2000); waitIdle(dlg, 120)
        expect("check_everything_readout", fx { dlg.messageLabel.visible && dlg.messageLabel.text.contains("checks:") }, status(dlg))
        shot("pgq_1_check_everything", dlg.stage)
        def rs = fx { resultStages() }; if (rs) shot("pgq_2_results", rs[0])
        pick(dlg, A, "Run a small test on a device"); Thread.sleep(2500)
        def box = fx { dlg.choiceBoxes["device"] }
        expect("device_dropdown", fx { box.visible && (box.items as List).contains("cpu") }, fx { [box.visible, box.items as List] })
        fx { box.value = "cpu"; dlg.setters["size"](128) }
        fx { dlg.run() }; Thread.sleep(2000); waitIdle(dlg, 120)
        expect("device_test", fx { dlg.messageLabel.text.contains("ms per run") }, status(dlg))
        pick(dlg, A, "Stress: a big image"); Thread.sleep(500)
        fx { dlg.setters["megabytes"](50) }
        fx { dlg.run() }; Thread.sleep(3000); waitIdle(dlg, 180)
        expect("big_image", status(dlg).startsWith("done"), status(dlg))
        pick(dlg, A, "Stress: crash the worker"); Thread.sleep(500)
        fx { dlg.run() }; Thread.sleep(3000); waitIdle(dlg, 120)
        println "CRASH STATUS " + status(dlg)
        expect("crash_is_explained", status(dlg).contains("crashed natively") && fx { !dlg.runButton.disable }, status(dlg))
        shot("pgq_3_crash", dlg.stage)
        pick(dlg, A, "Stress: out of memory"); Thread.sleep(500)
        fx { dlg.run() }; Thread.sleep(3000); waitIdle(dlg, 120)
        println "OOM STATUS " + status(dlg)
        expect("out_of_memory_is_readable_and_worker_recovers", status(dlg).contains("MemoryError") || status(dlg).toLowerCase().contains("memory"), status(dlg))
        pick(dlg, A, "Stress: many points"); Thread.sleep(500)
        fx { dlg.setters["count"](200000) }
        fx { dlg.run() }; Thread.sleep(3000); waitIdle(dlg, 180)
        expect("many_points_table", status(dlg).startsWith("done"), status(dlg))
    } catch (Throwable t) { t.printStackTrace() }
    finally { println "RESULTS " + results; Thread.sleep(500); System.exit(0) }
}
