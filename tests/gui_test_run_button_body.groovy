// ================================================================== GUI test body (appended to the tool script by tests/run_gui_test.sh)
// The Run BUTTON is pressed (every other body calls dlg.run() directly and so never exercised the click handler): a tool without inputs must start and finish.
import java.util.concurrent.CountDownLatch

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
def runRecords = { new File(System.getenv("LC_HOME") ?: (System.getProperty("user.home") + "/.labconstrictor"), "runs").with { it.isDirectory() ? it.list().length : 0 } }

Thread.start("lc-gui-test") {
    try {
        def dlg = fx { lcShow() }
        Thread.sleep(800)
        fx { dlg.appBox.value = "interactions" }; fx { dlg.toolBox.value = "List the shapes" }
        Thread.sleep(500)
        def run = fx { dlg.stage.scene.root.lookupAll(".button").find { it.text == "Run" } }
        expect("run_button_exists_and_is_enabled", run != null && fx { !run.disable }, run)
        def before = fx { dlg.status.text }, recordsBefore = runRecords()
        def sawRunning = false
        fx { run.fire() }                                   // the click handler itself, not dlg.run()
        def end = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < end) {
            Thread.sleep(50)
            if (fx { dlg.running }) sawRunning = true
            if (sawRunning && !fx { dlg.running }) break
            if (!sawRunning && runRecords() > recordsBefore && fx { !dlg.running }) break
        }
        def after = fx { dlg.status.text }
        expect("pressing_run_starts_the_tool", sawRunning || runRecords() > recordsBefore, "status stayed '" + after + "', running never became true")
        expect("pressing_run_ends_with_a_new_status", after != before && !fx { dlg.running } && fx { !run.disable }, "status before '" + before + "', after '" + after + "'")
        expect("pressing_run_wrote_a_run_record", runRecords() > recordsBefore, "run folders " + recordsBefore + " -> " + runRecords())
    } catch (Throwable t) { t.printStackTrace(); results["EXCEPTION"] = false }
    finally { println "RESULTS " + results; Thread.sleep(500); System.exit(results && results.every { it.value } ? 0 : 1) }
}
