// ================================================================== GUI test body: retention markers, the prune subprocess, run records (SPEC_WRONG_SCIENCE section 3; HRET-01..06, RET-10, RET-11, QuPath #14)
// (appended to the tool script by tests/run_gui_test.sh). Needs the `runs` test app (tests/runs_app, registered by tests/ci_setup_apps.sh in LC_SHARED_HOME; copied into the scratch LC_HOME,
// a folder named lhome_retention). The host never prunes: it writes `.lc-owner.json` and `.lc-done.json` and starts the Tools CLI `prune` as a subprocess. Most checks replace that subprocess by
// a recorder (a command that logs what it saw, LcRetention.pruneCommandFor), one block runs the REAL `labconstrictor-tools prune` against folders that must survive, and the folders are
// also judged by Tools itself (`prune --dry-run --json`: eligible folders are listed as kept). The cases are the fixtures_hosts of conformance.json (LC_CONFORMANCE, else
// `python -m labconstrictor_tools.conformance --path`).
import javafx.embed.swing.SwingFXUtils
import javax.imageio.ImageIO
import java.util.concurrent.CountDownLatch
import java.awt.image.BufferedImage
import javafx.stage.Window

def FIXTURES = System.getProperty("lc.test.fixtures", new File("tests/fixtures").absolutePath)
def BLOBS = FIXTURES + "/blobs.tif"
def SHARED_HOME = new File(System.getenv("LC_SHARED_HOME") ?: System.getenv("LC_HOME"))
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
def waitFor = { Closure cond, int seconds = 60 -> def end = System.currentTimeMillis() + seconds * 1000; while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(100) }; return false }
def status = { dlg -> fx { dlg.status.text } }
def pick = { dlg, app, tool -> fx { dlg.appBox.value = app }; fx { dlg.toolBox.value = tool }; Thread.sleep(500) }
def settle = { LcRetention.pruneThreads*.join(20000); LcRetention.pruneThreads.clear() }       // the prune subprocesses of the runs so far have ended
def run = { dlg -> fx { dlg.run() }; Thread.sleep(800); def ok = waitIdle(dlg, 120); settle(); ok }
def gateOf = { String name -> new File(System.getProperty("java.io.tmpdir"), "lc_gate_" + name + "_" + System.nanoTime()).path }
def busy = { dlg -> waitFor({ fx { dlg.worker != null && dlg.worker.currentTask != null } }, 30) }

Thread.start("lc-gui-test") {
    try {
        // ---- a scratch LC_HOME with a copy of the `runs` app, and the conformance data
        def home = new File(System.getenv("LC_HOME")); assert home.path.contains("lhome_retention")
        home.deleteDir(); def apps = new File(home, "apps"); apps.mkdirs()
        def srcApps = new File(SHARED_HOME, "apps")
        def installApp = { String name, Closure edit = null ->
            def schema = LcJson.parse(new File(srcApps, "runs.schema.json")) as Map
            if (edit != null) edit(schema)
            new File(apps, name + ".schema.json").text = LcJson.toJson(schema)
            def entry = LcJson.parse(new File(srcApps, "runs.json")) as Map
            new File(apps, name + ".json").text = LcJson.toJson(entry + [name: name, schema_path: new File(apps, name + ".schema.json").path])
            ["chmod", "644", new File(apps, name + ".json").path, new File(apps, name + ".schema.json").path].execute().waitFor()
        }
        installApp("runs")
        def RUNS = LcJson.parse(new File(apps, "runs.json")) as Map
        def PY = RUNS.python as String
        def confPath = System.getenv("LC_CONFORMANCE") ?: [PY, "-m", "labconstrictor_tools.conformance", "--path"].execute().text.trim()
        def CONF = LcJson.parse(new File(confPath))
        def RET = CONF.wrong_science.retention
        def hostCase = { String id -> def c = RET.fixtures_hosts.find { it.id == id }; assert c != null : "conformance.json has no host case " + id; c }
        expect("conformance_has_the_host_retention_cases", ["HRET-01", "HRET-02", "HRET-03", "HRET-04", "HRET-05", "HRET-06", "RET-10", "RET-11"].every { hostCase(it) != null } && RET.markers.owner_file == LcRetention.OWNER_FILE && RET.markers.done_file == LcRetention.DONE_FILE, RET.keySet())
        expect("the_markers_are_the_conformance_markers", RET.markers.done_statuses == LcRetention.STATUSES && LcRetention.HOST in RET.markers.hosts && "result" in RET.markers.owner_kinds && "run" in RET.markers.owner_kinds, RET.markers)
        expect("the_quotas_are_the_conformance_limits", CONF.limits.results_kept.value == 20 && CONF.limits.run_records_kept.value == 50)

        fx {
            def dir = new File(System.getProperty("java.io.tmpdir"), "qp_project_retention"); dir.deleteDir()
            qupath.lib.gui.prefs.PathPrefs.imageTypeSettingProperty().set(qupath.lib.gui.prefs.PathPrefs.ImageTypeSetting.AUTO_ESTIMATE)
            def project = qupath.lib.projects.Projects.createProject(dir, BufferedImage)
            def server = qupath.lib.images.servers.ImageServers.buildServer(BLOBS)
            def entry = project.addImage(server.getBuilder()); entry.setImageName("blobs.tif")
            project.syncChanges(); qupath.setProject(project); qupath.openImageEntry(entry); entry
        }
        Thread.sleep(2500)
        def resultsRoot = new File(home, "results"), runsRoot = new File(home, "runs")
        def pruneLog = new File(home, "prune_calls.log")
        def RECORDER = "import json, os, sys\nhome = os.environ['LC_HOME']\ndone = {}\nfor root in ('results', 'runs'):\n    d = os.path.join(home, root)\n    for n in (sorted(os.listdir(d)) if os.path.isdir(d) else []):\n        done[root + '/' + n] = os.path.exists(os.path.join(d, n, '.lc-done.json'))\nopen(sys.argv[1], 'a').write(json.dumps({'argv': sys.argv[2:], 'done': done}) + '\\n')\n"
        def useRecorder = { LcRetention.pruneCommandFor = { Map entry -> [PY, "-c", RECORDER, pruneLog.path, "prune"] as List<String> } }
        def useRealPrune = { LcRetention.pruneCommandFor = null }
        useRecorder()
        def calls = { pruneLog.isFile() ? pruneLog.readLines().collect { LcJson.parseText(it) as Map } : [] }
        def dlg = fx { lcShow() }
        Thread.sleep(800)
        def names = { File root -> (root.listFiles() ?: []).findAll { it.isDirectory() }*.name.sort() }
        def json = { File f -> LcJson.parse(f) as Map }
        def newest = { File root -> (root.listFiles() ?: []).findAll { it.isDirectory() && !it.name.startsWith(".") }.max { it.name } }
        def owned = { File root -> (root.listFiles() ?: []).findAll { it.isDirectory() && new File(it, ".lc-owner.json").isFile() } }
        // folders Tools itself would treat as eligible (they carry valid markers): the dry run of the real prune lists them as kept (or deleted), never as skipped
        def toolsView = {
            def pr = new ProcessBuilder([PY, "-m", "labconstrictor_tools", "prune", "--dry-run", "--json"]).redirectErrorStream(true).start()
            def text = pr.inputStream.getText("UTF-8"); pr.waitFor()
            def report = LcJson.parseText(text) as Map
            report.roots.collectEntries { r -> [(r.kind): [eligible: (r.kept + r.deleted) as List, skipped: r.skipped.collectEntries { [(it.name): it.reason] }]] }
        }

        // ---- HRET-03: one run: both folders have a valid owner marker first and, after the run, a valid done marker with the right status; Tools judges them eligible
        pick(dlg, "runs", "Quick values")
        run(dlg)
        expect("HRET-03_run_completes", status(dlg).startsWith("done"), status(dlg))
        def resFolder = newest(resultsRoot), runFolder = newest(runsRoot)
        expect("HRET-03_results_folder_is_named_like_the_command_line", resFolder != null && resFolder.name ==~ /^\d{8}T\d{6}_[0-9a-f]{6}_runs_quick_values$/, resFolder?.name)
        for (def pair : [[resFolder, "result"], [runFolder, "run"]]) {
            File f = pair[0]; String kind = pair[1]
            def owner = json(new File(f, ".lc-owner.json")), done = json(new File(f, ".lc-done.json"))
            expect("HRET-03_" + kind + "_owner_marker_is_valid", owner.lc_marker == 1L && owner.lc_marker instanceof Long && owner.kind == kind && owner.name == f.name && owner.host == "qupath" && owner.pid == ProcessHandle.current().pid() &&
                    owner.token ==~ /^[A-Za-z0-9_-]{8,64}$/ && owner.created ==~ /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/ && owner.keySet() as List == RET.markers.owner_fields, owner)
            expect("HRET-03_" + kind + "_done_marker_says_ok", done.status == "ok" && done.finished ==~ /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/ && done.keySet() as List == RET.markers.done_fields, done)
        }
        expect("HRET-03_no_temporary_marker_file_is_left", [resFolder, runFolder].every { f -> f.list().every { !it.contains(".tmp-") } }, [resFolder.list(), runFolder.list()])
        def view = toolsView()
        expect("HRET-03_tools_itself_finds_both_folders_eligible", resFolder.name in view.result.eligible && runFolder.name in view.run.eligible && !view.result.skipped.containsKey(resFolder.name) && !view.run.skipped.containsKey(runFolder.name), view)
        def rec = json(new File(runFolder, "run.json"))
        expect("HRET-03_run_record_is_the_tools_record_with_the_run_identity", rec.status == "COMPLETE" && rec.app == "runs" && rec.tool == "quick_values" && rec.host == "qupath" && rec.run_id ==~ /^[0-9a-f-]{36}$/ && rec.started_utc ==~ /^\d{4}-.*Z$/ && rec.finished_utc.endsWith("Z") && !rec.inputs.containsKey("_job_dir"), rec.keySet())

        // ---- HRET-04: the prune subprocess starts after the done files exist; a failing command and one that cannot start do not fail the run
        def first = calls()
        expect("HRET-04_the_recorder_saw_prune_after_the_done_files", first.size() == 1 && first[0].argv == ["prune"] && first[0].done["results/" + resFolder.name] == true && first[0].done["runs/" + runFolder.name] == true, first)
        expect("HRET-04_the_real_command_is_the_tools_cli_in_the_apps_python", { useRealPrune(); def c = LcRetention.pruneCommand(RUNS); useRecorder(); c == [PY, "-m", "labconstrictor_tools", "prune"] }.call(), "")
        LcRetention.pruneCommandFor = { Map entry -> [PY, "-c", "import sys; sys.exit(3)"] as List<String> }
        run(dlg)
        expect("HRET-04_a_failing_prune_does_not_fail_the_run", status(dlg).startsWith("done") && LcLog.recent.any { it.contains("prune ended with exit code 3") }, [status(dlg), LcLog.recent.takeRight(3)])
        LcRetention.pruneCommandFor = { Map entry -> ["/nonexistent/prune-command"] as List<String> }
        run(dlg)
        expect("HRET-04_a_prune_that_cannot_start_does_not_fail_the_run_and_is_said", status(dlg).startsWith("done") && LcLog.recent.any { it.contains("prune could not be started") }, [status(dlg), LcLog.recent.takeRight(3)])
        expect("HRET-04_both_failures_are_in_the_shared_log", new File(home, "logs/labconstrictor.log").text.with { it.contains("prune ended with exit code 3") && it.contains("prune could not be started") })
        expect("HRET-04_a_failing_prune_deletes_nothing_itself", names(resultsRoot).size() == 3 && names(runsRoot).size() == 3, [names(resultsRoot), names(runsRoot)])
        useRecorder()

        // ---- HRET-01: foreign folders survive 60 runs; HRET-02: done(60) results and done(120) records already there survive 5 more (the recorder stands in for prune)
        def foreignResult = new File(resultsRoot, "20261008T120000_reanalysis_backup"); foreignResult.mkdirs(); new File(foreignResult, "x.txt").text = "keep"
        def foreignRun = new File(runsRoot, "0000_keep_this_analysis"); foreignRun.mkdirs(); new File(foreignRun, "x.txt").text = "keep"
        def seed = { File root, String kind, int n, String tag ->
            (1..n).collect { i ->
                def f = new File(root, String.format("20200101T%06d_%s_seed", i, tag)); f.mkdirs(); LcRetention.writeOwner(f, kind); new File(f, "sentinel.txt").text = "old " + i; LcRetention.writeDone(f, "ok"); f
            }
        }
        def seededResults = seed(resultsRoot, "result", 60, "aaaaaa"), seededRuns = seed(runsRoot, "run", 120, "bbbbbb")
        def before = [names(resultsRoot).size(), names(runsRoot).size()]
        for (int i = 0; i < 60; i++) { fx { dlg.run() }; Thread.sleep(300); waitIdle(dlg, 120); settle() }
        def intact = { List<File> list -> list.every { new File(it, "sentinel.txt").text.startsWith("old ") } }
        expect("HRET-01_foreign_folders_survive_60_runs", new File(foreignResult, "x.txt").text == "keep" && new File(foreignRun, "x.txt").text == "keep", names(resultsRoot).take(3))
        expect("HRET-02_no_old_folder_is_gone_after_60_runs", intact(seededResults) && intact(seededRuns) && names(resultsRoot).size() == before[0] + 60 && names(runsRoot).size() == before[1] + 60, [names(resultsRoot).size(), names(runsRoot).size()])
        expect("HRET-04_prune_was_started_after_every_run", calls().size() >= 1 + 60, calls().size())
        expect("HRET-04_each_prune_saw_the_runs_done_files", calls().drop(1).every { c -> c.done.findAll { k, v -> k.startsWith("runs/2026") }.values().every { it } }, calls().last())
        expect("HRET-02_the_markers_of_all_60_new_folders_are_valid_for_tools", { def v = toolsView(); owned(resultsRoot).findAll { !it.name.contains("seed") }.every { it.name in v.result.eligible } && owned(runsRoot).findAll { !it.name.contains("seed") }.every { it.name in v.run.eligible } }.call(), "")

        // ---- the real prune, started by the host after the run: newest 20 results and 50 records stay, everything else that is not eligible is untouched
        useRealPrune()
        def legacy = (1..30).collect { new File(resultsRoot, String.format("20190101T%06d_abcdef_old_tool", it)) }; legacy.each { it.mkdirs(); new File(it, "legacy.txt").text = "legacy" }
        def unfinished = new File(resultsRoot, "20200202T000000_unfinished_x"); unfinished.mkdirs(); LcRetention.writeOwner(unfinished, "result"); new File(unfinished, "work.txt").text = "active"
        def renamed = new File(resultsRoot, "20200303T000000_renamed_copy"); renamed.mkdirs(); LcRetention.writeOwner(renamed, "result"); LcRetention.writeDone(renamed, "ok"); new File(renamed, "r.txt").text = "copy"
        def ownerText = new File(renamed, ".lc-owner.json"); ownerText.text = ownerText.text.replace(renamed.name, "some_other_name")
        def outside = new File(home, "outside"); outside.mkdirs(); LcRetention.writeOwner(outside, "result"); LcRetention.writeDone(outside, "ok"); new File(outside, "precious.txt").text = "precious"
        def link = new File(resultsRoot, "20200404T000000_link_x_y"); java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        run(dlg)
        run(dlg)
        def survivors = names(resultsRoot)
        def ownedDone = owned(resultsRoot).findAll { new File(it, ".lc-done.json").isFile() && it != renamed && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
        expect("real_prune_keeps_exactly_the_newest_20_results", ownedDone.size() == 20, ownedDone.size())
        def runsKept = owned(runsRoot).findAll { new File(it, ".lc-done.json").isFile() }
        expect("real_prune_keeps_exactly_the_newest_50_run_records", runsKept.size() == 50, runsKept.size())
        expect("real_prune_kept_the_newest_by_created_not_the_oldest", ownedDone.every { it.name.contains("runs_quick_values") && !it.name.contains("seed") }, ownedDone*.name.take(3))
        expect("real_prune_never_touches_legacy_foreign_unfinished_renamed_or_linked_folders", legacy.every { new File(it, "legacy.txt").text == "legacy" } && new File(foreignResult, "x.txt").text == "keep" && new File(foreignRun, "x.txt").text == "keep" &&
                new File(unfinished, "work.txt").text == "active" && new File(renamed, "r.txt").text == "copy" && java.nio.file.Files.isSymbolicLink(link.toPath()) && new File(outside, "precious.txt").text == "precious" && new File(outside, ".lc-done.json").isFile(), survivors.take(5))
        expect("real_prune_removed_the_old_owned_ones_through_tools_only", !new File(resultsRoot, "20200101T000001_aaaaaa_seed").exists() && !new File(runsRoot, "20200101T000001_bbbbbb_seed").exists(), "")
        useRecorder()

        // ---- HRET-05: the person pressed Cancel, the tool ignores it and the worker is killed after the grace period: CRASHED with Cancel is `cancelled`
        pick(dlg, "runs", "Never returns")
        fx { dlg.setters["gate"](gateOf("never")) }
        def cnt = [names(resultsRoot).size(), names(runsRoot).size()]
        fx { dlg.run() }
        busy(dlg); Thread.sleep(500)
        fx { dlg.cancel() }
        expect("HRET-05_the_run_ends_after_the_grace_period", waitIdle(dlg, 60), status(dlg)); settle()
        expect("HRET-05_the_status_says_cancelled", status(dlg) == "cancelled (worker stopped)", status(dlg))
        def r5 = newest(runsRoot), s5 = newest(resultsRoot)
        def rec5 = json(new File(r5, "run.json"))
        expect("HRET-05_both_owned_folders_are_cancelled_not_failed", names(runsRoot).size() == cnt[1] + 1 && json(new File(r5, ".lc-done.json")).status == "cancelled" && json(new File(s5, ".lc-done.json")).status == "cancelled", [json(new File(r5, ".lc-done.json")), json(new File(s5, ".lc-done.json"))])
        expect("HRET-05_the_crash_text_is_in_the_run_record", rec5.status == "CRASHED" && rec5.error instanceof String && rec5.error.contains("worker"), rec5.error)
        expect("HRET-05_the_case_in_the_conformance_file_says_the_same", hostCase("HRET-05").task_status == "CRASHED" && hostCase("HRET-05").cancel_requested == true && hostCase("HRET-05").done_status == "cancelled" && RET.done_status.crashed_after_cancel == "cancelled")

        // ---- HRET-06: the worker dies with no Cancel pressed: CRASHED without Cancel is `failed`
        pick(dlg, "runs", "Dies")
        run(dlg)
        def r6 = newest(runsRoot), s6 = newest(resultsRoot)
        expect("HRET-06_the_status_is_the_crash", status(dlg).startsWith("the worker stopped:"), status(dlg))
        expect("HRET-06_both_owned_folders_are_failed", json(new File(r6, ".lc-done.json")).status == "failed" && json(new File(s6, ".lc-done.json")).status == "failed" && json(new File(r6, "run.json")).status == "CRASHED", [json(new File(r6, ".lc-done.json")), json(new File(s6, ".lc-done.json"))])
        expect("HRET-06_the_case_in_the_conformance_file_says_the_same", hostCase("HRET-06").task_status == "CRASHED" && hostCase("HRET-06").cancel_requested == false && hostCase("HRET-06").done_status == "failed" && RET.done_status.by_task_status.CRASHED == "failed")

        // ---- RET-11: a failed run and a cooperative cancel leave their owned folders failed / cancelled and the temporary export folder removed; a tool error is failed whatever Cancel did
        pick(dlg, "runs", "Fails")
        run(dlg)
        def r11 = newest(runsRoot)
        def exportDirOf = { File recFolder -> def img = (json(new File(recFolder, "run.json")).inputs as Map).values().find { it instanceof String && it.contains("lcqupath_") }; img ? new File(img as String).parentFile : null }
        expect("RET-11_a_failed_run_is_failed", status(dlg).startsWith("failed:") && json(new File(r11, ".lc-done.json")).status == "failed" && json(new File(newest(resultsRoot), ".lc-done.json")).status == "failed", status(dlg))
        pick(dlg, "runs", "Delayed values")
        fx { dlg.setters["gate"](gateOf("coop")) }
        fx { dlg.run() }
        busy(dlg); Thread.sleep(300)
        fx { dlg.cancel() }
        waitIdle(dlg, 60); settle()
        def r11c = newest(runsRoot)
        expect("RET-11_a_cancelled_run_is_cancelled", status(dlg) == "cancelled" && json(new File(r11c, ".lc-done.json")).status == "cancelled" && json(new File(newest(resultsRoot), ".lc-done.json")).status == "cancelled" && json(new File(r11c, "run.json")).status == "CANCELED", status(dlg))
        pick(dlg, "runs", "Delayed points")
        fx { dlg.setters["gate"](gateOf("tmpdir")) }
        fx { dlg.run() }
        busy(dlg)
        def liveExport = (fx { dlg.runSnapshot }).resultsPath      // the results folder exists while the run goes
        expect("RET-11_the_results_folder_exists_with_its_owner_marker_while_the_run_goes", new File(liveExport, ".lc-owner.json").isFile() && !new File(liveExport, ".lc-done.json").isFile(), liveExport)
        fx { dlg.cancel() }
        waitIdle(dlg, 60); settle()
        def r11d = newest(runsRoot)
        expect("RET-11_the_temporary_export_folder_is_removed", exportDirOf(r11d) != null && !exportDirOf(r11d).exists(), exportDirOf(r11d))

        // ---- RET-10: the reported file is under results/, owned, and still there after the window is closed
        pick(dlg, "runs", "Writes a table")
        def gate10 = gateOf("table"); new File(gate10).text = "go"
        fx { dlg.setters["gate"](gate10) }
        run(dlg)
        def table = json(new File(newest(runsRoot), "run.json")).results.find { it.type == "table" }
        expect("RET-10_the_reported_path_is_under_results_and_has_the_data", table != null && new File(table.path as String).isFile() && (table.path as String).startsWith(resultsRoot.path + "/") && new File(table.path as String).text.contains("1,x"), table)
        fx { dlg.stage.hide() }
        Thread.sleep(500); settle()
        expect("RET-10_the_file_is_still_there_after_the_window_closed", new File(table.path as String).isFile() && new File(new File(table.path as String).parentFile, ".lc-done.json").isFile(), table.path)
        dlg = fx { lcShow() }; Thread.sleep(800)

        // ---- the host has no code that deletes under results/ or runs/ (a scan of the script files; the only delete is the temporary export folder's)
        def resDir = new File("src/main/resources/org/cellmigrationlab/labconstrictor/qupath")
        def sources = new File(resDir, "parts.txt").readLines().findAll { it.trim() }.collectEntries { [(it): new File(resDir, it).readLines()] }
        def deleteWords = ~/deleteDir|deleteOnExit|deleteDirectory|\.delete\(|Files\.delete|walkFileTree|FileUtils|moveToTrash|\brmdir\b|\.deleteRecursively/
        def hits = sources.collectMany { name, lines -> lines.findAll { it =~ deleteWords && !it.contains("stderrTail.delete(") && !it.trim().startsWith("//") && !it.trim().startsWith("*") && !it.trim().startsWith("/**") }.collect { name + ": " + it.trim() } }
        println "DELETE HITS " + hits
        expect("no_code_deletes_a_folder_but_the_temporary_export_one", hits.size() == 1 && hits[0].contains("LcLog") == false && hits[0].contains("!dir.deleteDir()"), hits)
        def callers = sources.collectMany { name, lines -> lines.findAll { it.contains("deleteTemp(") && !it.contains("static void deleteTemp") }.collect { it.trim() } }
        expect("the_only_caller_of_it_passes_the_temporary_export_folder", callers.size() >= 1 && callers.every { it.contains("deleteTemp(tmp)") }, callers)

        // ---- Tools too old: the run is refused with the sentence, no folder is made and nothing starts
        installApp("runs_old") { Map schema -> schema.tools_version = "0.2.0rc1"; schema.features = ["reserved_inputs_v1", "retention_prune"] }
        installApp("runs_nofeature") { Map schema -> schema.features = ["reserved_inputs_v1"] }
        installApp("runs_unknown") { Map schema -> schema.remove("tools_version"); schema.remove("features") }
        fx { dlg.rescan() }
        def count = [names(resultsRoot).size(), names(runsRoot).size()]
        for (def pair : [["runs_old", "0.2.0rc1 is older than the 0.3.0a1 this host needs: update it"], ["runs_nofeature", "lacks retention_prune: update it"], ["runs_unknown", "it lists no features"]]) {
            pick(dlg, pair[0], "Quick values")
            fx { dlg.run() }
            Thread.sleep(500)
            expect("compat_" + pair[0] + "_is_refused_with_a_clear_sentence", status(dlg).startsWith("this app's labconstrictor-tools") && status(dlg).contains(pair[1] as String) && !fx { dlg.running }, status(dlg))
        }
        expect("compat_a_refused_run_made_no_folder_and_started_no_worker", names(resultsRoot).size() == count[0] && names(runsRoot).size() == count[1] && fx { dlg.worker == null || dlg.workerApp == "runs" }, count)

        // ---- a record that cannot be built is said, and its folders are neither finished nor removed
        def entryBad = [python: "/nonexistent/python", pythonpath: [], runtime_path: ""]
        def odd = LcRuns.begin("runs", "quick_values")
        def ended = LcRuns.finish(odd, null, [entry: entryBad, app: "runs", tool: "quick_values", inputs: [:], outcome: [responseType: "COMPLETION"], seconds: 0d, stderr: "", runId: "x", startedUtc: "y"])
        expect("an_unbuildable_record_is_a_problem_sentence_and_its_folder_stays_unfinished", ended.problem != null && ended.problem.contains("run record could not be written") && odd.isDirectory() && new File(odd, ".lc-owner.json").isFile() && !new File(odd, ".lc-done.json").exists() && !new File(odd, "run.json").exists(), ended)
        expect("that_problem_is_in_the_log_and_in_the_status", LcLog.recent.any { it.contains("could not write the run record") } && fx { dlg.finish("runs", "Quick values", [responseType: "COMPLETION", recordProblem: ended.problem], 0.1d); dlg.status.text }.contains("run record could not be written"), "")
        def badDone = null
        try { LcRetention.writeDone(odd, "weird") } catch (IllegalArgumentException e) { badDone = e.message }
        expect("a_done_status_outside_the_three_is_refused", badDone != null && badDone.contains("ok, failed, cancelled") && !new File(odd, ".lc-done.json").exists(), badDone)

    } catch (Throwable t) { t.printStackTrace(); results["EXCEPTION"] = false }
    finally { println "RESULTS " + results; Thread.sleep(500); System.exit(results && results.every { it.value } ? 0 : 1) }
}
