// Smoke test of a built extension jar (tests/smoke_jar.sh). QuPath runs this file once started, with the jar on its class path:
// the menu entry Extensions > "LabConstrictor tools..." must exist and pressing it must open the "LabConstrictor tools" window
// (the joined script is read from the jar). Exit code 0 only when both hold.
import javafx.application.Platform
import javafx.scene.control.Menu
import javafx.stage.Stage
import javafx.stage.Window
import java.util.concurrent.CountDownLatch

def fx = { Closure c ->
    def latch = new CountDownLatch(1); def box = [null, null]
    Platform.runLater { try { box[0] = c() } catch (Throwable t) { box[1] = t } finally { latch.countDown() } }
    latch.await()
    if (box[1] != null) throw box[1]
    return box[0]
}
Thread.start("lc-smoke") {
    def ok = false
    try {
        def item = fx { qupath.getMenu("Extensions", false)?.items?.find { it.text == "LabConstrictor tools..." } }
        println((item != null ? "PASS " : "FAIL ") + "menu entry Extensions > LabConstrictor tools...")
        if (item != null) {
            fx { item.fire() }
            def end = System.currentTimeMillis() + 90_000
            def dialog = null
            while (dialog == null && System.currentTimeMillis() < end) {
                Thread.sleep(500)
                dialog = fx { Window.getWindows().find { it instanceof Stage && it.showing && it.title == "LabConstrictor tools" } }
            }
            println((dialog != null ? "PASS " : "FAIL ") + "dialog 'LabConstrictor tools' opens")
            ok = dialog != null
        }
    } catch (Throwable t) { t.printStackTrace() }
    println "SMOKE " + (ok ? "OK" : "FAILED")
    Thread.sleep(300)
    System.exit(ok ? 0 : 1)
}
