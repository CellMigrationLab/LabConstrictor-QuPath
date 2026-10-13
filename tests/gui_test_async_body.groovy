// ================================================================== GUI test body: asynchronous ChoicesFrom lookups and who owns the worker (SPEC_WRONG_SCIENCE section 5; ASY-01..08, QuPath #19)
// (appended to the tool script by tests/run_gui_test.sh). Needs the `runs` and `adv` test apps (registered by tests/ci_setup_apps.sh in LC_SHARED_HOME; copied into the scratch LC_HOME, a folder
// named lhome_async). The source tools of `runs` answer at once, wait for a GATE file the test creates (the dependency of the dropdown IS the gate path, so changing it is changing the dependency)
// or fail after it: that is the deterministic barrier of the fixtures, nothing depends on timing. The fixtures are read from conformance.json (async_lookups, limits.lookup_*). What each
// case checks is what the person would see (the dropdown's items and value, the status line, tooltips) and what the machine has (worker processes, threads), never only that no error occurred.
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class FakeWorker implements LcWorkerLike {
    final AtomicInteger closes = new AtomicInteger(0), kills = new AtomicInteger(0)
    volatile boolean up = true
    boolean alive() { return up }
    void close() { closes.incrementAndGet(); up = false }
    void kill() { kills.incrementAndGet(); up = false }
    int ends() { return closes.get() + kills.get() }
}

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
def waitFor = { Closure cond, int seconds = 60 -> def end = System.currentTimeMillis() + seconds * 1000; while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(50) }; return false }
def waitIdle = { dlg, int seconds = 120 -> waitFor({ !dlg.running && fx { !dlg.runButton.disable } }, seconds) }
def status = { dlg -> fx { dlg.status.text } }
def pick = { dlg, app, tool -> fx { dlg.appBox.value = app }; fx { dlg.toolBox.value = tool }; Thread.sleep(300) }
def gateOf = { String name -> new File(System.getProperty("java.io.tmpdir"), "lc_gate_" + name + "_" + System.nanoTime()) }
def release = { File gate -> gate.text = "go" }
def settle = { LcRetention.pruneThreads*.join(20000); LcRetention.pruneThreads.clear() }
def alive = { Long pid -> pid != null && ProcessHandle.of(pid).map { it.isAlive() }.orElse(false) }

Thread.start("lc-gui-test") {
    try {
        def home = new File(System.getenv("LC_HOME")); assert home.path.contains("lhome_async")
        home.deleteDir(); def apps = new File(home, "apps"); apps.mkdirs()
        def srcApps = new File(SHARED_HOME, "apps")
        ["runs", "adv"].each { name ->
            new File(apps, name + ".schema.json").text = new File(srcApps, name + ".schema.json").text
            new File(apps, name + ".json").text = LcJson.toJson(LcJson.parse(new File(srcApps, name + ".json")) + [schema_path: new File(apps, name + ".schema.json").path])
            ["chmod", "644", new File(apps, name + ".json").path, new File(apps, name + ".schema.json").path].execute().waitFor()
        }
        def PY = (LcJson.parse(new File(apps, "runs.json")) as Map).python as String
        LcRetention.pruneCommandFor = { Map entry -> [PY, "-c", "pass"] as List<String> }
        def confPath = System.getenv("LC_CONFORMANCE") ?: [PY, "-m", "labconstrictor_tools.conformance", "--path"].execute().text.trim()
        def CONF = LcJson.parse(new File(confPath)) as Map
        def asy = { String id -> def c = CONF.wrong_science.async_lookups.fixtures.find { it.id == id }; assert c != null : "conformance.json has no case " + id; c }
        expect("conformance_has_ASY_01_to_08", (1..8).every { asy(String.format("ASY-%02d", it)) != null }, CONF.wrong_science.async_lookups.keySet())
        expect("the_limits_are_the_conformance_limits", LcConst.LOOKUP_IN_FLIGHT_MAX == CONF.limits.lookup_in_flight_max.value && LcConst.LOOKUP_PENDING_MAX == CONF.limits.lookup_pending_max.value && LcConst.LOOKUP_TIMEOUT_SECONDS == CONF.limits.lookup_timeout_s.value &&
                LcConst.CHOICES_DEBOUNCE_MS == CONF.limits.choices_debounce_ms.value && LcConst.CHOICES_BUSY_RETRY_MS == CONF.limits.choices_busy_retry_ms.value && LcConst.CLOSE_WAIT_SECONDS == CONF.limits.worker_close_timeout_s.value, CONF.limits)
        expect("the_default_time_limit_of_a_lookup_is_60_s", LcLookups.timeoutSeconds() == 60, LcLookups.timeoutSeconds())

        def dlg = fx { lcShow() }
        Thread.sleep(800)
        def combo = { String n -> fx { dlg.choiceBoxes[n] } }
        def itemsOf = { String n -> fx { dlg.choiceBoxes[n].items.toList() } }
        def shown = { String n -> fx { dlg.choiceBoxes[n].visible } }
        def valueOf = { String n -> fx { dlg.choiceBoxes[n].value } }
        def inFlight = { fx { dlg.lookups.inFlightCount() } }
        def setText = { String n, String text -> fx { dlg.setters[n](text); null } }
        def gen = { LcLookups.FORM_GENERATION.get() }

        // ---- the generation is process-wide and never reset; the request id is per window and never reset
        def g0 = gen()
        pick(dlg, "runs", "Tool B")
        expect("generation_grows_whenever_a_form_is_built_or_destroyed", gen() >= g0 + 2, [g0, gen()])
        waitFor({ shown("mode") }, 30)
        expect("a_lookup_at_form_build_gives_the_dropdown_its_choices", itemsOf("mode") == ["", "b1", "b2", "b3"] && valueOf("mode") == "", itemsOf("mode"))
        def g1 = gen()
        pick(dlg, "runs", "Tool A"); pick(dlg, "runs", "Tool B")
        expect("generation_is_never_reset_by_a_second_window", gen() > g1 && { def other = fx { new LcDialog(null, [apps: [:], problems: []]) }; gen() > g1 }.call(), [g1, gen()])

        // ---- ASY-01: A's lookup is delayed, switch to B, release A: B's dropdown shows only B's choices
        def gateA = gateOf("asy01")
        pick(dlg, "runs", "Tool A")
        setText("gate", gateA.path)
        expect("ASY-01_A_lookup_is_in_flight", waitFor({ inFlight() == 1 }, 30), inFlight())
        pick(dlg, "runs", "Tool B")
        release(gateA)
        expect("ASY-01_B_shows_its_own_choices", waitFor({ shown("mode") && itemsOf("mode") == ["", "b1", "b2", "b3"] }, 30), [shown("mode"), itemsOf("mode")])
        expect("ASY-01_no_choice_of_A_is_displayed_and_the_value_is_B_s", !itemsOf("mode").any { it.startsWith("a") } && valueOf("mode") == "" && fx { dlg.currentTool.id } == "tool_b", [itemsOf("mode"), valueOf("mode")])
        expect("ASY-01_A_s_reply_was_dropped", waitFor({ fx { dlg.lookups.droppedReplies.contains("mode") } }, 30), fx { dlg.lookups.droppedReplies.toList() })
        expect("ASY-01_the_form_is_B_s_and_not_pending", !fx { dlg.lookups.isPending("mode") }, "")

        // ---- ASY-02: change the dependency, release the old reply before the debounce fires: dropped, pending, no old choices
        def gate1 = gateOf("asy02a"), gate2 = gateOf("asy02b"); release(gate2)
        pick(dlg, "runs", "Tool A")
        setText("gate", gate1.path)
        waitFor({ inFlight() == 1 }, 30)
        def droppedBefore = fx { dlg.lookups.droppedReplies.size() }
        setText("gate", gate2.path)                       // the dependency changes now: the old question is stale at once
        expect("ASY-02_the_parameter_is_pending_at_once", fx { dlg.lookups.isPending("mode") && !dlg.choiceBoxes["mode"].visible && dlg.choiceBoxes["mode"].items.isEmpty() }, [fx { dlg.lookups.isPending("mode") }, shown("mode")])
        release(gate1)                                    // the old reply comes back before the debounce (400 ms) fires
        expect("ASY-02_the_old_reply_is_dropped", waitFor({ fx { dlg.lookups.droppedReplies.size() } > droppedBefore }, 30), "")
        expect("ASY-02_it_is_still_pending_and_shows_no_choices_after_the_old_reply", fx { dlg.lookups.isPending("mode") && !dlg.choiceBoxes["mode"].visible && dlg.choiceBoxes["mode"].items.isEmpty() }, [fx { dlg.lookups.isPending("mode") }, shown("mode"), itemsOf("mode")])
        expect("ASY-02_the_new_question_is_answered_for_the_new_value_and_not_the_old_one", waitFor({ shown("mode") && itemsOf("mode") == ["", "a1:" + gate2.name, "a2:" + gate2.name] }, 30) && !fx { dlg.lookups.isPending("mode") } && !itemsOf("mode").any { it.contains(gate1.name) }, [shown("mode"), itemsOf("mode")])

        // ---- the five parts of a lookup's identity, one at a time (SPEC 5.2): a reply that differs from the current question in only ONE of them is dropped whole, the same reply with all five is applied
        pick(dlg, "runs", "Tool B")
        waitFor({ shown("mode") }, 30)
        // a dropdown that was filled goes back to the text field at once when a value it depends on changes (the choices belong to the old value), and is filled again by the new answer
        setText("key", "k2")
        expect("ASY-02_a_filled_dropdown_is_pending_and_empty_at_once_when_its_dependency_changes", fx { dlg.lookups.isPending("mode") && !dlg.choiceBoxes["mode"].visible && dlg.choiceBoxes["mode"].items.isEmpty() && dlg.controls["mode"].visible && dlg.controls["mode"].tooltip?.text == "waiting for the choices..." }, [fx { dlg.lookups.isPending("mode") }, shown("mode"), itemsOf("mode")])
        expect("ASY-02_and_it_is_filled_again_by_the_answer_for_the_new_value", waitFor({ shown("mode") && itemsOf("mode") == ["", "b1", "b2", "b3"] && !fx { dlg.lookups.isPending("mode") } }, 30) && fx { dlg.controls["mode"].tooltip == null }, [shown("mode"), itemsOf("mode")])
        def modeParam = fx { dlg.currentTool.inputs.find { it.name == "mode" } }
        def depsNow = fx { LcJson.toJson(dlg.choiceRequest(modeParam)) }
        def reqOf = { Map over -> [gen: LcLookups.FORM_GENERATION.get(), app: "runs", tool: "tool_b", param: "mode", id: fx { dlg.lookups.latestId("mode") }, deps: depsNow] + over }
        def tryApply = { Map req -> fx { int before = dlg.lookups.droppedReplies.size(); dlg.lookups.apply(req, ["zz1", "zz2"], null); [dropped: dlg.lookups.droppedReplies.size() > before, items: dlg.choiceBoxes["mode"].items.toList()] } }
        def untouched = ["", "b1", "b2", "b3"]
        def byGen = tryApply(reqOf([gen: LcLookups.FORM_GENERATION.get() - 1]))
        expect("ASY-02_a_reply_of_another_form_generation_is_dropped", byGen.dropped && byGen.items == untouched, byGen)
        def byApp = tryApply(reqOf([app: "adv"]))
        expect("ASY-02_a_reply_of_another_app_is_dropped", byApp.dropped && byApp.items == untouched, byApp)
        def byTool = tryApply(reqOf([tool: "tool_a"]))
        expect("ASY-02_a_reply_of_another_tool_is_dropped", byTool.dropped && byTool.items == untouched, byTool)
        def byId = tryApply(reqOf([id: fx { dlg.lookups.latestId("mode") } - 1]))
        expect("ASY-02_a_reply_that_is_not_the_latest_request_of_the_parameter_is_dropped", byId.dropped && byId.items == untouched, byId)
        def byDeps = tryApply(reqOf([deps: '{"key":"other"}']))
        expect("ASY-02_a_reply_for_other_dependencies_than_the_current_ones_is_dropped", byDeps.dropped && byDeps.items == untouched, byDeps)
        def byParam = tryApply(reqOf([param: "nothing"]))
        expect("ASY-02_a_reply_for_a_parameter_the_form_does_not_have_is_dropped", byParam.dropped && byParam.items == untouched, byParam)
        def all = tryApply(reqOf([:]))
        expect("ASY-02_the_same_reply_with_all_five_parts_current_is_applied", !all.dropped && all.items == ["", "zz1", "zz2"], all)

        // ---- ASY-03: A's lookup fails after the switch to B: no error and no tooltip on any B field
        def gateF = gateOf("asy03")
        pick(dlg, "runs", "Tool A that fails")
        setText("gate", gateF.path)
        waitFor({ inFlight() == 1 }, 30)
        pick(dlg, "runs", "Tool B")
        fx { dlg.status.text = "idle"; null }
        def droppedBefore3 = fx { dlg.lookups.droppedReplies.size() }
        release(gateF)
        waitFor({ fx { dlg.lookups.droppedReplies.size() } > droppedBefore3 && inFlight() == 0 }, 30)
        waitFor({ shown("mode") && itemsOf("mode") == ["", "b1", "b2", "b3"] }, 30)
        Thread.sleep(500)
        expect("ASY-03_no_error_in_the_status_line", !status(dlg).contains("could not be loaded") && !status(dlg).contains("source") && status(dlg) == "idle", status(dlg))
        expect("ASY-03_no_tooltip_on_any_field_of_B", fx { dlg.controls.values().every { it.tooltip == null } }, fx { dlg.controls.collectEntries { k, v -> [(k): v.tooltip?.text] } })
        expect("ASY-03_B_shows_its_own_choices", itemsOf("mode") == ["", "b1", "b2", "b3"], itemsOf("mode"))
        expect("ASY-03_a_failure_for_the_current_form_is_still_said", { pick(dlg, "runs", "Tool A that fails"); release(gateF); setText("gate", gateF.path); waitFor({ status(dlg).contains("The choices for 'mode' could not be loaded") }, 30) }.call(), status(dlg))

        // ---- ASY-04: 50 changes of a dependency in about a second: at most one lookup in flight, at most one waiting per parameter, at most one worker process started
        pick(dlg, "runs", "Tool B")
        waitFor({ shown("mode") }, 30)
        def startedBefore = LcWorker.STARTED.get()
        def lookupsBefore = fx { dlg.lookups.started.get() }
        for (int i = 1; i <= 50; i++) { fx { dlg.setters["key"]("key" + i); null }; Thread.sleep(20) }
        waitFor({ fx { dlg.lookups.started.get() } > lookupsBefore && inFlight() == 0 && shown("mode") }, 30)
        expect("ASY-04_at_most_one_lookup_in_flight", fx { dlg.lookups.maxInFlight.get() } <= LcConst.LOOKUP_IN_FLIGHT_MAX, fx { dlg.lookups.maxInFlight.get() })
        expect("ASY-04_at_most_one_waiting_per_parameter", fx { dlg.lookups.maxWaiting.get() } <= 1, fx { dlg.lookups.maxWaiting.get() })
        expect("ASY-04_at_most_one_worker_process_started", LcWorker.STARTED.get() - startedBefore <= 1, LcWorker.STARTED.get() - startedBefore)
        expect("ASY-04_the_changes_were_coalesced_into_few_questions", fx { dlg.lookups.started.get() } - lookupsBefore <= 5, fx { dlg.lookups.started.get() } - lookupsBefore)
        expect("ASY-04_the_dropdown_has_the_choices_of_the_last_value", itemsOf("mode") == ["", "b1", "b2", "b3"] && !fx { dlg.lookups.isPending("mode") }, itemsOf("mode"))
        // ... and without the pause: 12 questions for 12 parameters at once while one lookup is stuck: 8 wait, the oldest 4 are dropped and logged
        def gateS = gateOf("asy04c")
        pick(dlg, "runs", "Tool A"); setText("gate", gateS.path)
        waitFor({ inFlight() == 1 }, 30)
        def droppedFrom = fx { dlg.lookups.dropped.get() }
        fx { (1..12).each { i -> dlg.lookups.ask([name: "x" + i, choices_from: [tool: "choices_b", field: "choices"]], [key: "k"]) }; null }
        expect("ASY-04_at_most_8_lookups_wait_and_the_oldest_are_dropped", fx { dlg.lookups.maxWaiting.get() } == LcConst.LOOKUP_PENDING_MAX && fx { dlg.lookups.dropped.get() } - droppedFrom == 4, [fx { dlg.lookups.maxWaiting.get() }, fx { dlg.lookups.dropped.get() }])
        expect("ASY-04_the_drops_are_logged_oldest_first", ["x1", "x2", "x3", "x4"].every { n -> LcLog.recent.any { it.contains("choices lookup of '" + n + "' dropped") } } && !LcLog.recent.any { it.contains("choices lookup of 'x5' dropped") }, LcLog.recent.takeRight(5))
        release(gateS)
        waitFor({ inFlight() == 0 && fx { dlg.lookups.inFlightCount() } == 0 }, 30); Thread.sleep(600)

        // ---- ASY-05: two parameters with ChoicesFrom, both requested at once: one worker process, each reply goes to its own control
        fx { dlg.stopWorker(true); null }; Thread.sleep(500)
        def started5 = LcWorker.STARTED.get()
        pick(dlg, "runs", "Tool with two dropdowns")
        expect("ASY-05_both_dropdowns_are_filled", waitFor({ shown("first") && shown("second") }, 30), [shown("first"), shown("second")])
        expect("ASY-05_each_reply_went_to_its_own_control", itemsOf("first") == ["", "b1", "b2", "b3"] && itemsOf("second") == ["", "c1", "c2"], [itemsOf("first"), itemsOf("second")])
        expect("ASY-05_never_more_than_one_lookup_at_a_time", fx { dlg.lookups.maxInFlight.get() } <= LcConst.LOOKUP_IN_FLIGHT_MAX, fx { dlg.lookups.maxInFlight.get() })
        expect("ASY-05_one_worker_process_served_both", LcWorker.STARTED.get() - started5 == 1 && fx { dlg.workers.made.size() } >= 1, LcWorker.STARTED.get() - started5)

        // ---- ASY-06: start a run, switch the app, request a lookup: the run keeps its worker to the end; the lookup runs after, in the new app's worker
        pick(dlg, "runs", "Delayed values")
        def gateR = gateOf("asy06"); fx { dlg.setters["gate"](gateR.path); null }
        fx { dlg.run() }
        waitFor({ fx { dlg.worker != null && dlg.worker.currentTask != null } }, 30)
        def w1 = fx { dlg.worker }, pid1 = fx { dlg.worker.proc.pid() }
        def startedRun = LcWorker.STARTED.get(), lookupsRun = fx { dlg.lookups.started.get() }
        pick(dlg, "adv", "Pick")                                     // its dropdowns ask at once; while a run is active they wait (1500 ms retries)
        Thread.sleep(2300)
        expect("ASY-06_the_run_still_has_its_worker_and_the_app_switch_did_not_replace_it", fx { dlg.worker.is(w1) && dlg.workerApp == "runs" && dlg.worker.alive() && dlg.running }, [fx { dlg.workerApp }, fx { dlg.running }])
        expect("ASY-06_no_lookup_started_and_no_worker_was_made_for_the_new_app_during_the_run", fx { dlg.lookups.started.get() } == lookupsRun && LcWorker.STARTED.get() == startedRun, [fx { dlg.lookups.started.get() }, lookupsRun, LcWorker.STARTED.get(), startedRun])
        release(gateR)
        expect("ASY-06_the_run_ends_on_its_own_worker", waitIdle(dlg, 60) && status(dlg).startsWith("done"), status(dlg)); settle()
        expect("ASY-06_the_lookup_then_runs_in_the_new_apps_worker", waitFor({ fx { dlg.workerApp == "adv" } && itemsOf("mode").size() > 100 }, 60), [fx { dlg.workerApp }, itemsOf("mode").size()])
        expect("ASY-06_the_new_worker_is_another_process_and_the_run_s_worker_was_closed", fx { dlg.worker.proc.pid() } != pid1 && waitFor({ !alive(pid1 as Long) }, 30), [fx { dlg.worker.proc.pid() }, pid1])
        expect("ASY-06_the_run_record_names_the_run_s_app", (LcJson.parse((new File(home, "runs").listFiles() as List).max { it.name }.with { new File(it, "run.json") }) as Map).with { app == "runs" && tool == "delayed_values" }, "")

        // ---- a lookup that takes longer than its limit is ended with its worker (5.4); the next one works on a new worker
        System.setProperty(LcConst.LOOKUP_TIMEOUT_PROPERTY, "2")
        expect("the_time_limit_comes_from_the_property", LcLookups.timeoutSeconds() == 2, LcLookups.timeoutSeconds())
        def gateT = gateOf("timeout")
        pick(dlg, "runs", "Tool A"); setText("gate", gateT.path)
        waitFor({ inFlight() == 1 }, 30)
        def stuckPid = fx { dlg.worker.proc.pid() }
        expect("lookup_over_the_limit_is_ended_and_said", waitFor({ status(dlg).contains("The choices for 'mode' could not be loaded") && status(dlg).contains("did not answer within 2 s") }, 30), status(dlg))
        expect("lookup_over_the_limit_discards_its_worker", waitFor({ !alive(stuckPid as Long) }, 30), stuckPid)
        System.clearProperty(LcConst.LOOKUP_TIMEOUT_PROPERTY)
        release(gateT)
        pick(dlg, "runs", "Tool B")
        expect("lookup_after_the_timeout_works_on_a_new_worker", waitFor({ shown("mode") && itemsOf("mode") == ["", "b1", "b2", "b3"] }, 30) && fx { dlg.worker.proc.pid() } != stuckPid, itemsOf("mode"))

        // ---- ASY-07: close the window with a lookup in flight: no worker process and no thread of the window stays; a worker being made while it closes does not survive
        def dlg7 = fx { lcShow() }
        Thread.sleep(800)
        fx { dlg7.appBox.value = "runs" }; fx { dlg7.toolBox.value = "Tool A" }; Thread.sleep(300)
        def gate7 = gateOf("asy07"); fx { dlg7.setters["gate"](gate7.path); null }
        waitFor({ fx { dlg7.lookups.inFlightCount() } == 1 }, 30)
        def pid7 = fx { dlg7.worker.proc.pid() }, thread7 = fx { dlg7.lookups.threadName }, started7 = LcWorker.STARTED.get()
        fx { dlg7.stage.hide(); null }
        expect("ASY-07_the_lookup_thread_stops", fx { dlg7.lookups.awaitStopped(20000) } && !Thread.getAllStackTraces().keySet().any { it.name == thread7 && it.alive }, thread7)
        expect("ASY-07_the_worker_process_is_gone", waitFor({ !alive(pid7 as Long) }, 30) && fx { dlg7.workers.joinClosing(20000) } && fx { dlg7.workers.current == null && dlg7.workers.closed }, pid7)
        expect("ASY-07_no_thread_that_closes_workers_is_left_and_nothing_was_started_after_the_close", !Thread.getAllStackTraces().keySet().any { it.name == "lc-worker-close" && it.alive } && LcWorker.STARTED.get() == started7, LcWorker.STARTED.get() - started7)
        release(gate7)
        def dlg7b = fx { lcShow() }
        Thread.sleep(800)
        LcWorkers.creating = { long end = System.currentTimeMillis() + 1500; while (System.currentTimeMillis() < end) { try { Thread.sleep(50) } catch (InterruptedException e) { /* the hook ignores interrupts: the worker IS made while the window closes */ } } }
        fx { dlg7b.appBox.value = "runs" }; fx { dlg7b.toolBox.value = "Tool B" }
        Thread.sleep(700)                                          // the lookup thread is inside the creation of its worker
        fx { dlg7b.stage.hide(); null }
        LcWorkers.creating = null
        def made7 = fx { dlg7b.workers.made.toList() }
        expect("ASY-07_a_worker_made_while_the_window_closed_does_not_survive", waitFor({ fx { dlg7b.workers.joinClosing(20000) } && fx { dlg7b.workers.made.every { !it.alive() } } }, 40) && fx { dlg7b.workers.closed }, made7.size())
        expect("ASY-07_the_lookup_thread_of_that_window_stops_too", fx { dlg7b.lookups.awaitStopped(20000) }, "")

        // ---- ASY-08: acquire / release / reap / close from three threads with barriers: the cache stays consistent (each worker owned once), no double close
        // (a) fixed interleaving: T1 holds A (a lookup), T2 leases B for a run (A is retired, not closed: it is held), T3 closes the window while T1 is about to release
        def ws = new LcWorkers({ Map app -> new FakeWorker() } as Closure<LcWorkerLike>)
        def leaseA = ws.lease("A", [:], LcWorkers.LOOKUP)
        def a = leaseA.worker as FakeWorker
        def t2Done = new CountDownLatch(1), t1AtRelease = new CountDownLatch(1), t3Done = new CountDownLatch(1)
        LcWorkers.point = { String point -> if (point == "release") { t1AtRelease.countDown(); t2Done.await(); } }
        def leaseB = null
        def t1 = Thread.start { ws.release(leaseA) }                       // T1 stops at "release" until T2 has its lease
        t1AtRelease.await()
        def t2 = Thread.start { LcWorkers.point = null; leaseB = ws.lease("B", [:], LcWorkers.RUN); t2Done.countDown() }
        t2.join(10000); t1.join(10000)
        def b = leaseB.worker as FakeWorker
        expect("ASY-08_the_old_worker_was_closed_once_when_its_last_lease_ended", ws.joinClosing(5000) && a.ends() == 1 && !a.alive() && b.ends() == 0 && b.alive() && ws.current.is(b) && ws.currentApp == "B", [a.ends(), b.ends()])
        def t3 = Thread.start { ws.closeAll(false); t3Done.countDown() }
        t3.join(10000)
        ws.release(leaseB)                                               // the lease of the closed window ends later: nothing is closed twice
        ws.closeAll(false)
        expect("ASY-08_closing_twice_and_releasing_after_the_close_close_nothing_twice", ws.joinClosing(5000) && b.ends() == 1 && a.ends() == 1 && ws.leasesOf(b) == 0 && ws.current == null, [a.ends(), b.ends()])
        def refusedAfterClose = null
        try { ws.lease("A", [:], LcWorkers.LOOKUP) } catch (IllegalStateException e) { refusedAfterClose = e.message }
        expect("ASY-08_no_lease_is_given_after_the_close", refusedAfterClose == "the window is closed" && ws.made.size() == 2, refusedAfterClose)
        // (b) a lookup never uses or replaces a run's worker and never starts while a run is active; a run waits for the lookup that holds its worker
        def ws2 = new LcWorkers({ Map app -> new FakeWorker() } as Closure<LcWorkerLike>)
        def runLease = ws2.lease("A", [:], LcWorkers.RUN)
        expect("ASY-08_no_lookup_lease_while_a_run_is_active", ws2.lease("A", [:], LcWorkers.LOOKUP) == null && ws2.lease("B", [:], LcWorkers.LOOKUP) == null && ws2.made.size() == 1, ws2.made.size())
        def other = ws2.lease("B", [:], LcWorkers.RUN)                    // a run for another app: its own worker, the first is retired and still held
        expect("ASY-08_an_app_switch_does_not_close_the_worker_a_run_holds", (runLease.worker as FakeWorker).ends() == 0 && (runLease.worker as FakeWorker).alive() && ws2.current.is(other.worker), "")
        ws2.release(runLease); ws2.release(other)
        expect("ASY-08_the_retired_worker_is_closed_when_the_run_ends", ws2.joinClosing(5000) && (runLease.worker as FakeWorker).ends() == 1 && (other.worker as FakeWorker).ends() == 0, "")
        def lookupLease = ws2.lease("B", [:], LcWorkers.LOOKUP)
        def runWaits = new CountDownLatch(1), gotRun = [null]
        def tr = Thread.start { runWaits.countDown(); gotRun[0] = ws2.lease("B", [:], LcWorkers.RUN) }
        runWaits.await(); Thread.sleep(400)
        expect("ASY-08_a_run_waits_for_the_lookup_that_holds_its_worker", gotRun[0] == null && tr.alive, gotRun[0])
        ws2.release(lookupLease); tr.join(10000)
        expect("ASY-08_then_it_uses_the_same_worker", gotRun[0] != null && gotRun[0].worker.is(lookupLease.worker) && ws2.made.size() == 2, ws2.made.size())
        ws2.release(gotRun[0] as LcLease)
        expect("ASY-08_reap_closes_an_idle_worker_and_not_a_held_one", { def held = ws2.lease("B", [:], LcWorkers.LOOKUP); def r1 = ws2.reap(false); ws2.release(held); def r2 = ws2.reap(false); ws2.joinClosing(5000); !r1 && r2 && (held.worker as FakeWorker).ends() == 1 && ws2.current == null }.call(), "")
        ws2.closeAll(true)
        // (c) stress: three threads lease, release, reap and close at random; nothing is closed twice or while held, and no worker is lost
        def ws3 = new LcWorkers({ Map app -> new FakeWorker() } as Closure<LcWorkerLike>)
        def failures = Collections.synchronizedList([])
        def workersThreads = (1..3).collect { int n ->
            Thread.start {
                def rnd = new Random(1000 + n)
                for (int i = 0; i < 300; i++) {
                    try {
                        int op = rnd.nextInt(10)
                        if (op < 6) {
                            def l = ws3.lease(rnd.nextBoolean() ? "A" : "B", [:], n == 1 ? LcWorkers.RUN : LcWorkers.LOOKUP)
                            if (l != null) {
                                if ((l.worker as FakeWorker).ends() != 0) failures << "a leased worker was already closed"
                                Thread.yield()
                                if ((l.worker as FakeWorker).ends() != 0 && !ws3.closed) failures << "a worker was closed while it was held"
                                ws3.release(l)
                            }
                        } else if (op < 9) ws3.reap(false)
                        else if (n == 3 && i > 250) ws3.closeAll(false)
                    } catch (IllegalStateException e) { /* closed window: leases are refused */ }
                }
            }
        }
        workersThreads*.join(60000)
        ws3.closeAll(false)
        expect("ASY-08_stress_nothing_failed_and_every_worker_was_closed_exactly_once", failures.isEmpty() && ws3.joinClosing(10000) && ws3.made.every { (it as FakeWorker).ends() == 1 } && ws3.made.every { ws3.leasesOf(it) == 0 }, [failures.take(3), ws3.made.collect { (it as FakeWorker).ends() }.unique()])
        LcWorkers.point = null

    } catch (Throwable t) { t.printStackTrace(); results["EXCEPTION"] = false }
    finally { println "RESULTS " + results; Thread.sleep(500); System.exit(results && results.every { it.value } ? 0 : 1) }
}
