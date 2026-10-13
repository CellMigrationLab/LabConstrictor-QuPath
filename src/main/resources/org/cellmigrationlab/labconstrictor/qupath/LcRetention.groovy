// ---------------------------------------------------------------------------------------------------- retention and run records (SPEC_WRONG_SCIENCE section 3)
/** The environment of the app's own Python: the variables that would mix it with QuPath's or the user's are removed, the paths of the registry entry are set. The worker, the run-record helper
 *  and the `prune` subprocess all start the app's interpreter this way. */
class LcPython {
    static final int HELPER_SECONDS = 60             // the run-record helper is a short Python call; a longer one is killed and reported

    /** Sets the environment of `builder` for the app described by the registry entry `entry` (the same rules as the worker's). */
    static void applyEnvironment(ProcessBuilder builder, Map entry) {
        LcRegistry.SCRUBBED_ENV.each { String name -> builder.environment().remove(name) }
        String path = ((entry.pythonpath ?: []) + [entry.runtime_path ?: ""]).findAll { it }.join(File.pathSeparator)
        builder.environment().putAll([PYTHONPATH: path, PYTHONNOUSERSITE: "1", PYTHONSAFEPATH: "1", PYTHONIOENCODING: "utf-8", PYTHONUNBUFFERED: "1"])
    }
}

/** The two marker files of SPEC_WRONG_SCIENCE 3.2 and the `prune` subprocess (3.1). This host only CREATES folders under <LC_HOME>/results and <LC_HOME>/runs: it writes `.lc-owner.json` when
 *  it makes a folder and `.lc-done.json` (atomically) when the run is over, then starts `labconstrictor-tools prune` in the app's Python environment. It never deletes anything there itself,
 *  and a prune that cannot start leaves the folders where they are (3.1.4): there is no deleting code in this script for them. */
class LcRetention {
    static final String OWNER_FILE = ".lc-owner.json"
    static final String DONE_FILE = ".lc-done.json"
    static final String HOST = "qupath"
    static final int MARKER_VERSION = 1
    static final List<String> STATUSES = ["ok", "failed", "cancelled"]
    /** Test hook (tests only): returns the command to start instead of `<python> -m labconstrictor_tools prune`; null (the default) means the real command. */
    static volatile Closure<List<String>> pruneCommandFor = null
    /** The `prune` processes started by this JVM (tests wait for them). */
    static final List<Thread> pruneThreads = Collections.synchronizedList(new ArrayList<Thread>())

    /** `2026-10-12T09:30:00.123Z`: the format of `created` and `finished`. */
    static String nowUtc() {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now())
    }

    /** Writes `.lc-owner.json` into a folder that was just created: the first thing in it. `kind` is "result" or "run". */
    static void writeOwner(File folder, String kind) {
        Map<String, Object> marker = [lc_marker: MARKER_VERSION, kind: kind, name: folder.name, host: HOST, pid: ProcessHandle.current().pid(), created: nowUtc(), token: UUID.randomUUID().toString()]
        new File(folder, OWNER_FILE).setText(LcJson.toJson(marker), "UTF-8")
    }

    /** Writes `.lc-done.json` atomically (a temporary file, then a rename): the last thing in the folder. */
    static void writeDone(File folder, String status) {
        if (!(status in STATUSES)) throw new IllegalArgumentException("a done status is one of " + STATUSES.join(", ") + ", got '" + status + "'")
        File temporary = new File(folder, DONE_FILE + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime())
        temporary.setText(LcJson.toJson([finished: nowUtc(), status: status]), "UTF-8")
        Files.move(temporary.toPath(), new File(folder, DONE_FILE).toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    /** The command of the `prune` subprocess: the app's interpreter, as the worker uses it. */
    static List<String> pruneCommand(Map entry) {
        Closure<List<String>> hook = pruneCommandFor
        if (hook != null) return hook.call(entry)
        List<String> command = [entry.python as String, "-m", "labconstrictor_tools", "prune"]
        return command
    }

    /** Starts `prune` and returns at once: a daemon thread waits for it, ignores its exit code except to log a failure, and never fails or delays the run (3.1.3). */
    static Thread startPrune(Map entry) {
        List<String> command = pruneCommand(entry)
        Thread thread = new Thread({ -> runPrune(entry, command) } as Runnable, "lc-prune")
        thread.daemon = true
        pruneThreads << thread
        thread.start()
        return thread
    }

    private static void runPrune(Map entry, List<String> command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
            LcPython.applyEnvironment(builder, entry)
            Process process = builder.start()
            process.outputStream.close()
            String output = process.inputStream.getText("UTF-8").trim()
            int code = process.waitFor()
            if (code != 0) LcLog.warn("prune ended with exit code " + code + " (the folders it could not remove stay): " + output.take(LcConst.LOG_LINE_CHARS))
        } catch (IOException e) {      // the command cannot be started: the folders accumulate until it can (nothing is deleted here instead)
            LcLog.warn("prune could not be started (" + command.join(" ") + "); older results and run records stay where they are", e)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
            LcLog.warn("interrupted while waiting for prune", e)
        }
    }
}

/** The run-record content, built by the Tools helpers (`labconstrictor_tools.runs.build_record`, `record_json`, `done_status`) in the app's own Python: this script keeps no copy of the
 *  record layout or of the status rule. The helpers arrived in Tools 0.3.0a1 (LcCompat.MIN_TOOLS_VERSION). */
class LcRunRecord {
    static final Map<String, String> STATUS = [COMPLETION: "COMPLETE", FAILURE: "FAILED", CANCELATION: "CANCELED", CRASH: "CRASHED"]   // the worker's response types as the client names them
    static final String HELPER = '''
import json, sys, types
from labconstrictor_tools import runs
request = json.loads(sys.stdin.read())
task = types.SimpleNamespace(status=request["status"], error=request.get("error"), code=request.get("code"), traceback=request.get("traceback"),
                             outputs=request.get("outputs") or {}, cancel_requested=bool(request.get("cancel_requested")))
content = runs.build_record(request["app"], request["tool"], request["inputs"], task, request["seconds"], request.get("stderr") or "",
                            run_id=request.get("run_id"), started_utc=request.get("started_utc"), finished_utc=request["finished_utc"])
content["host"] = request["host"]
sys.stdout.write("\\n" + json.dumps({"text": runs.record_json(content), "done_status": runs.done_status(request["status"], bool(request.get("cancel_requested")))}) + "\\n")
'''

    /** What the helper needs for one finished run: the task fields come from the worker's last message (`outcome`); a worker that was killed after Cancel is a crash that followed Cancel. */
    static Map<String, Object> request(Map run) {
        Map outcome = run.outcome as Map
        Map outputs = outcome.outputs instanceof Map ? outcome.outputs as Map : [:]
        String type = outcome.responseType as String
        String status = outcome.workerStopped ? "CRASHED" : (STATUS[type] ?: type)
        Map<String, Object> request = [app: run.app, tool: run.tool, inputs: (run.inputs as Map), seconds: run.seconds, stderr: run.stderr ?: "", status: status, error: outcome.error, code: outcome.code,
                                       traceback: outcome.trace ?: outcome.traceback, outputs: outputs, cancel_requested: outcome.cancelRequested ? true : false, run_id: run.runId, started_utc: run.startedUtc,
                                       finished_utc: java.time.Instant.now().toString(), host: LcRetention.HOST]
        return request
    }

    /** Runs the helper in the app's interpreter: [text: the text of run.json, doneStatus: the status for the done markers]. Throws IllegalStateException with the reason when it cannot. */
    static Map<String, String> build(Map entry, Map<String, Object> request) {
        ProcessBuilder builder = new ProcessBuilder([entry.python as String, "-c", HELPER])
        LcPython.applyEnvironment(builder, entry)
        Process process
        try { process = builder.start() }
        catch (IOException e) { throw new IllegalStateException("the app's Python could not be started: " + e.message, e) }
        StringBuffer errors = new StringBuffer()
        Thread reader = Thread.start("lc-record-stderr") { errors.append(process.errorStream.getText("UTF-8")) }
        process.outputStream.withWriter("UTF-8") { Writer w -> w.write(LcJson.toJson(request)) }
        String out = process.inputStream.getText("UTF-8")
        if (!process.waitFor(LcPython.HELPER_SECONDS, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("the Tools run-record helper did not finish in " + LcPython.HELPER_SECONDS + " s") }
        reader.join(LcConst.STREAM_DRAIN_MS)
        if (process.exitValue() != 0) throw new IllegalStateException("the Tools run-record helper failed (exit code " + process.exitValue() + "): " + errors.toString().trim().takeRight(LcConst.CRASH_TAIL_CHARS))
        Map answer = LcJson.parseText(out.readLines().findAll { it.trim() }.last()) as Map
        return [text: answer.text as String, doneStatus: answer.done_status as String]
    }
}

/** One folder per run under <LC_HOME>/runs with a run.json in the format of labconstrictor_tools.runs, so that `labconstrictor-tools support-bundle` finds QuPath runs. The folder is made when
 *  the run starts (owner marker first, so a QuPath that dies leaves an owned, unfinished folder that nobody removes); run.json and the done marker come when it ends. A record holds file paths
 *  and the values passed, never image or table contents. Nothing here deletes a folder: retention is `labconstrictor-tools prune`, started after the done markers (LcRetention). */
class LcRuns {
    static File runsDir() { return new File(LcRegistry.home(), "runs") }

    /** Makes the record folder `<time>_<app>_<tool>` (never an existing one) and writes its owner marker. Throws IOException when it cannot. */
    static File begin(String app, String toolId) {
        File root = runsDir()
        root.mkdirs()
        for (int attempt = 0; attempt < LcConst.RESULTS_DIR_ATTEMPTS; attempt++) {
            String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSSSSS").withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now())
            File folder = new File(root, stamp + "_" + LcDialog.slug(app) + "_" + LcDialog.slug(toolId))
            if (folder.mkdir()) { LcRetention.writeOwner(folder, "run"); return folder }
        }
        throw new IOException("could not create a run record folder in " + root)
    }

    /** Ends the run: builds the record with the Tools helpers, writes run.json, then the done markers of the record folder and of the results folder `resultsDir` (may be null). The caller
     *  starts `prune` afterwards (LcRetention.startPrune) when this returns no problem. Never throws: whatever goes wrong is returned as `problem` (a sentence for the status line) and logged, and the folders then stay unfinished, so nothing is ever removed.
     *  `run` holds entry, app, tool, inputs, outcome, seconds, stderr, runId and startedUtc. */
    static Map<String, String> finish(File recordDir, File resultsDir, Map run) {
        try {
            Map<String, String> built = LcRunRecord.build(run.entry as Map, LcRunRecord.request(run))
            new File(recordDir, "run.json").setText(built.text, "UTF-8")
            LcRetention.writeDone(recordDir, built.doneStatus)
            if (resultsDir != null) LcRetention.writeDone(resultsDir, built.doneStatus)
            return [doneStatus: built.doneStatus]
        } catch (IOException | RuntimeException e) {      // broad on purpose: whatever goes wrong here must not break the run, but its absence must be visible
            LcLog.warn("could not write the run record for " + run.app + " " + run.tool, e)
            return [problem: "the run record could not be written (" + e.message + "); its folders stay as they are and are never removed"]
        }
    }

    /** Makes and finishes a record in one call (what a run that has no folder yet needs): the folder, or null (said in the log) when it cannot be written. */
    static File record(Map run) {
        try {
            File folder = begin(run.app as String, run.tool as String)
            return finish(folder, null, run).problem ? null : folder
        } catch (IOException e) {
            LcLog.warn("could not write the run record for " + run.app + " " + run.tool, e)
            return null
        }
    }
}
