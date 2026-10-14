// ================================================================== sandbox driver: does QuPath list this app? (appended to the tool script by tests/run_gui_test.sh)
// The contract of the host drivers of the release sandbox (LabConstrictor-Tools tests/release_sandbox/README.md). Environment:
//   LC_HOME             the registry that holds the installed app
//   LC_EXPECT_APP       the registry name of the app
//   LC_EXPECT_LISTED    listed | not_listed
//   LC_EXPECT_SENTENCE  the exact sentence of app_hosts.listed_for ("" when none): it must be in the Tools log <LC_HOME>/logs/labconstrictor.log
// It opens the real dialog and prints exactly ONE line: `CHECK qupath_lists_the_app PASS|FAIL <detail>` (listed) or `CHECK qupath_does_not_list_the_app PASS|FAIL <detail>` (not_listed);
// a driver that is not given what it needs prints `CHECK qupath_listing_driver_environment FAIL <what>`. The exit code is 0 only for PASS. For an app with no registry entry
// (an app that ships no tools) the not_listed check also prints `NOTE qupath_no_registry_entry` and needs no sentence in the log.
import java.util.concurrent.CountDownLatch
import javafx.application.Platform

/** The value of the environment variable `name`; an unset one is an error that says which. */
String requiredEnvironment(String name) {
    String value = System.getenv(name)
    if (value == null) throw new IllegalStateException("the environment variable " + name + " is not set")
    return value
}

/** Runs `work` on the JavaFX thread and gives its result back (or throws what it threw). */
Object onFxThread(Closure work) {
    CountDownLatch done = new CountDownLatch(1)
    List box = [null, null]
    Platform.runLater { try { box[0] = work() } catch (Throwable t) { box[1] = t } finally { done.countDown() } }
    done.await()
    if (box[1] != null) throw (Throwable) box[1]
    return box[0]
}

/** True when the Tools log of `home` has a line that ends with the sentence `text` (written by this host: `... qupath: <text>`). */
boolean toolsLogHas(File home, String text) {
    File log = new File(home, "logs/" + LcConst.LOG_NAME)
    return log.isFile() && log.getText("UTF-8").split("\n").any { String line -> line.endsWith(" qupath: " + text) }
}

/** Problems with what the dialog offers for the app that must be listed: it is in the app chooser and the tool chooser holds every tool of its schema. Empty when fine. */
List<String> listedProblems(Object dialog, String app) {
    List<String> items = (List<String>) onFxThread { new ArrayList<String>(dialog.appBox.items) }
    if (!items.contains(app)) return ["'" + app + "' is not in the app chooser (it offers: " + items + ")"]
    List<String> expected = ((Map) dialog.apps[app]).schema.tools.collect { Map tool -> (String) tool.label }
    if (expected.isEmpty()) return ["the schema of '" + app + "' has no tool"]
    onFxThread { dialog.appBox.value = app; null }
    List<String> offered = (List<String>) onFxThread { new ArrayList<String>(dialog.toolBox.items) }
    return expected.every { String label -> offered.any { String shown -> shown == label || shown.startsWith(label + " (") } } ? [] : ["the tool chooser of '" + app + "' offers " + offered + ", the schema has " + expected]
}

/** Problems with what the dialog and the log say for an app that must NOT be listed. Empty when fine. */
List<String> notListedProblems(Object dialog, File home, String app, String sentence, boolean registered) {
    List<String> items = (List<String>) onFxThread { new ArrayList<String>(dialog.appBox.items) }
    List<String> problems = []
    if (items.contains(app)) problems << ("'" + app + "' is in the app chooser")
    if (registered && sentence.isEmpty()) problems << "LC_EXPECT_SENTENCE is empty for a registered app that must not be listed"
    if (registered && !sentence.isEmpty()) {
        String status = (String) onFxThread { dialog.rescan(); dialog.status.text }      // "Rescan apps" is where the dialog says what it skipped
        if (!status.contains(sentence)) problems << ("the dialog's list of skipped apps does not say: " + sentence + " (it says: " + status + ")")
        if (!toolsLogHas(home, sentence)) problems << ("the Tools log does not hold: " + sentence)
    }
    return problems
}

/** The one CHECK line of this run and its exit code. */
String check(String expectation, String id, List<String> problems) {
    return "CHECK " + id + " " + (problems.isEmpty() ? "PASS " + expectation : "FAIL " + problems.join("; "))
}

// the test script runs on the JavaFX thread: the work is done on a thread of its own, which asks the JavaFX thread for each GUI step
Thread.start("lc-gui-test") {
    int exitCode = 1
    String id = "qupath_listing_driver_environment"      // the id of the CHECK line: the one of the expectation as soon as it is known
    try {
        String expectation = requiredEnvironment("LC_EXPECT_LISTED"), app = requiredEnvironment("LC_EXPECT_APP"), sentence = requiredEnvironment("LC_EXPECT_SENTENCE")
        File home = new File(requiredEnvironment("LC_HOME"))
        if (!(expectation in ["listed", "not_listed"])) throw new IllegalStateException("LC_EXPECT_LISTED must be listed or not_listed, not '" + expectation + "'")
        id = expectation == "listed" ? "qupath_lists_the_app" : "qupath_does_not_list_the_app"
        Object dialog = onFxThread { lcShow() }
        boolean registered = new File(home, "apps/" + app + ".json").isFile()
        if (expectation == "listed") {
            List<String> problems = listedProblems(dialog, app)
            if (!sentence.isEmpty() && !toolsLogHas(home, sentence)) problems << ("the Tools log does not hold: " + sentence)
            println check("the app is offered with every tool of its schema", id, problems)
            exitCode = problems.isEmpty() ? 0 : 1
        } else {
            List<String> problems = notListedProblems(dialog, home, app, sentence, registered)
            if (!registered) println "NOTE qupath_no_registry_entry"
            println check("the app is not offered and the skip is said", id, problems)
            exitCode = problems.isEmpty() ? 0 : 1
        }
    } catch (Throwable t) {
        t.printStackTrace()
        println "CHECK " + id + " FAIL " + t.class.simpleName + ": " + t.message
    } finally {
        Thread.sleep(500)
        System.exit(exitCode)
    }
}
