// ================================================================== GUI test body: conformance.json of labconstrictor-tools (docs/CONFORMANCE.md, "How each host's test loads it")
// (appended to the tool script by tests/run_gui_test.sh). The file is the one of the installed labconstrictor-tools: LC_CONFORMANCE, else `python -m labconstrictor_tools.conformance --path` of a
// registered app. This body checks what needs no GUI: the file validates; every LIMIT and every MESSAGE template and every host-internal CODE of the file is compared with this script's own constant
// (a key this script does not know FAILS: a new rule is a failing test until the host has followed it); the COMPAT vectors run through LcCompat.check; the DONE-STATUS rule runs through the real
// Tools helper from every way a run can end; and a LEDGER says, for every fixture id of the file, which body runs it or why it does not apply to QuPath (an id that is in the file and not in the
// ledger FAILS; a case that cannot run here is printed as UNSUPPORTED, never passed). The fixtures themselves are run by the other bodies, through the real dialog.
import java.util.concurrent.CountDownLatch

def SHARED_HOME = new File(System.getenv("LC_SHARED_HOME") ?: System.getenv("LC_HOME"))
def results = [:]
def expect = { String name, boolean ok, Object detail = "" ->
    results[name] = ok
    println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : " -> " + detail))
}

Thread.start("lc-gui-test") {
    try {
        def interactions = (new File(SHARED_HOME, "apps").listFiles({ File f -> f.name.endsWith(".json") && !f.name.endsWith(".schema.json") } as FileFilter) as List).sort { it.name }.collect { LcJson.parse(it) as Map }.first()
        def PY = interactions.python as String
        def confPath = System.getenv("LC_CONFORMANCE") ?: [PY, "-m", "labconstrictor_tools.conformance", "--path"].execute().text.trim()
        def CONF = LcJson.parse(new File(confPath)) as Map
        def validate = new ProcessBuilder([PY, "-m", "labconstrictor_tools.conformance", "--validate"]).redirectErrorStream(true).start()
        def validateText = validate.inputStream.getText("UTF-8"); validate.waitFor()
        expect("the_file_validates_with_the_tools_schema", validate.exitValue() == 0, validateText.take(400))
        expect("the_file_has_the_sections_this_host_reads", ["conformance", "limits", "host_internal_codes", "messages", "compat", "wrong_science"].every { CONF.containsKey(it) } && CONF.conformance == 1L, CONF.keySet())

        // ---- limits: every key is compared with a constant of this script, or is listed as not this host's (it never prunes)
        def LIMITS = [
            form_integer_max: LcConst.FORM_INTEGER_LIMIT, unbounded_float: LcConst.FLOAT_LIMIT, choices_debounce_ms: LcConst.CHOICES_DEBOUNCE_MS, choices_busy_retry_ms: LcConst.CHOICES_BUSY_RETRY_MS,
            worker_close_timeout_s: LcConst.CLOSE_WAIT_SECONDS, lookup_in_flight_max: LcConst.LOOKUP_IN_FLIGHT_MAX, lookup_pending_max: LcConst.LOOKUP_PENDING_MAX, lookup_timeout_s: LcConst.LOOKUP_TIMEOUT_SECONDS]
        def NOT_THIS_HOST = [results_kept: "retention belongs to `labconstrictor-tools prune`; this host never prunes", run_records_kept: "retention belongs to `labconstrictor-tools prune`; this host never prunes"]
        CONF.limits.each { String key, Map entry ->
            if (LIMITS.containsKey(key)) expect("limit_" + key + "_is_the_conformance_value", (LIMITS[key] as double) == (entry.value as double), [LIMITS[key], entry.value])
            else if (NOT_THIS_HOST.containsKey(key)) println "UNSUPPORTED limit_" + key + ": " + NOT_THIS_HOST[key]
            else expect("limit_" + key + "_is_known_to_this_host", false, "the file has a limit this host does not know: " + key)
        }
        expect("every_limit_this_host_keeps_is_in_the_file", LIMITS.keySet().every { CONF.limits.containsKey(it) } && NOT_THIS_HOST.keySet().every { CONF.limits.containsKey(it) }, CONF.limits.keySet())
        expect("the_quotas_prune_applies_are_20_and_50", CONF.limits.results_kept.value == 20 && CONF.limits.run_records_kept.value == 50)
        expect("the_lookup_limits_are_marked_default_not_decided", ["lookup_in_flight_max", "lookup_pending_max", "lookup_timeout_s"].every { CONF.limits[it].status == "default" } && CONF.limits.choices_debounce_ms.status == "decided", "")

        // ---- host-internal codes
        def CODES = [selection_mixed_planes: LcConst.CODE_SELECTION_MIXED_PLANES, selection_plane_mismatch: LcConst.CODE_SELECTION_PLANE_MISMATCH, target_unavailable: LcRun.CODE_TARGET_UNAVAILABLE]
        CONF.host_internal_codes.each { String code ->
            if (CODES.containsKey(code)) expect("code_" + code + "_is_the_conformance_code", CODES[code] == code)
            else if (code == "frame_unsupported_transform") println "UNSUPPORTED code_frame_unsupported_transform: a QuPath image has no spatial transform, so the code is never produced"
            else expect("code_" + code + "_is_known_to_this_host", false, "the file has a code this host does not know: " + code)
        }

        // ---- messages: every template of the file is the text this script produces, or is listed as not this host's
        def MESSAGES = [
            size_mismatch: LcConst.SIZE_MISMATCH_TEMPLATE, calibration_required: LcUnits.CALIBRATION_REQUIRED, calibration_unknown_tooltip: LcUnits.NOT_CALIBRATED_TOOLTIP, unit_damaged_warning: LcUnits.DAMAGED_TEXT,
            pixels_not_square_warning: LcUnits.NOT_SQUARE_TEXT, unit_not_length_warning: LcUnits.NOT_A_LENGTH_TEXT, target_unavailable: LcRun.TARGET_UNAVAILABLE, target_changed_size: LcRun.TARGET_CHANGED_SIZE,
            number_above_maximum: LcNumbers.ABOVE_TEMPLATE, number_below_minimum: LcNumbers.BELOW_TEMPLATE, file_input_note: LcConst.FILE_INPUT_NOTE]
        def MESSAGES_NOT_HERE = [refusal_suffix: "'(nothing was changed; files are in {path})' follows a refusal that leaves files; every refusal of this host happens before the results folder exists, so no refusal has files"]
        CONF.messages.each { String key, Map entry ->
            if (MESSAGES.containsKey(key)) expect("message_" + key + "_is_the_conformance_template", MESSAGES[key] == entry.template, [MESSAGES[key], entry.template])
            else if (MESSAGES_NOT_HERE.containsKey(key)) println "UNSUPPORTED message_" + key + ": " + MESSAGES_NOT_HERE[key]
            else expect("message_" + key + "_is_known_to_this_host", false, "the file has a message this host does not know: " + key)
        }
        expect("every_message_this_host_uses_is_in_the_file", MESSAGES.keySet().every { CONF.messages.containsKey(it) }, CONF.messages.keySet())

        // ---- compat: the vectors of PROTOCOL.md, through LcCompat.check with the file's own minimum and required features
        def minVersion = CONF.compat.min_tools_version as String, required = CONF.compat.required_features as List<String>
        CONF.compat.cases.each { Map c ->
            def got = LcCompat.check(c.schema as Map, minVersion, required)
            expect("compat_" + (c.case as String).replace(" ", "_").replace("(", "").replace(")", "") + "_is_" + c.expect, got.status == c.expect && (!c.reason_contains || got.reason.contains(c.reason_contains as String)) && (c.expect != "ok" || got.reason == ""), got)
        }
        def order = CONF.compat.version_order as List<String>
        expect("compat_the_version_order_is_ascending", (0..<(order.size() - 1)).every { LcCompat.compareVersions(LcCompat.parseVersion(order[it]), LcCompat.parseVersion(order[it + 1])) < 0 } && order.every { LcCompat.parseVersion(it) != null }, order)
        expect("compat_a_text_that_is_no_version_is_not_parsed", LcCompat.parseVersion("banana") == null && LcCompat.parseVersion(null) == null && LcCompat.parseVersion(5) == null && LcCompat.parseVersion("v0.2.0-rc.1") == LcCompat.parseVersion("0.2.0rc1"), "")
        expect("compat_this_host_needs_what_the_run_record_and_prune_need", LcCompat.MIN_TOOLS_VERSION == "0.3.0a1" && LcCompat.REQUIRED_FEATURES == ["reserved_inputs_v1", "retention_prune"], "")
        def odd = null
        try { LcCompat.check([protocol: 1L], "banana", []) } catch (IllegalArgumentException e) { odd = e.message }
        expect("compat_a_minimum_that_is_no_version_is_a_programming_error", odd != null && odd.contains("programming error"), odd)
        expect("compat_warn_below_gives_a_warning_not_a_refusal", LcCompat.check([protocol: 1L, tools_version: "0.3.0a1", features: required], minVersion, required, "0.4.0").status == "warn" && LcCompat.check([protocol: 1L, tools_version: "0.4.0", features: required], minVersion, required, "0.4.0").status == "ok", "")
        expect("compat_the_apps_this_machine_registered_pass_it", LcRegistry.load().with { reg -> !reg.apps.isEmpty() && reg.apps.values().every { LcCompat.checkApp(it as Map).status == "ok" } }, "")

        // ---- done status: the rule of the file through the real Tools helper, from every way a run can end
        def RET = CONF.wrong_science.retention
        def ends = [
            [name: "complete", outcome: [responseType: "COMPLETION"], expect: RET.done_status.by_task_status.COMPLETE],
            [name: "cancelled_by_the_tool", outcome: [responseType: "CANCELATION", cancelRequested: true], expect: RET.done_status.by_task_status.CANCELED],
            [name: "failed", outcome: [responseType: "FAILURE", error: "x", cancelRequested: false], expect: RET.done_status.by_task_status.FAILED],
            [name: "failed_even_after_cancel", outcome: [responseType: "FAILURE", error: "x", cancelRequested: true], expect: RET.done_status.by_task_status.FAILED],
            [name: "crashed_without_cancel", outcome: [responseType: "CRASH", error: "x", cancelRequested: false], expect: RET.done_status.by_task_status.CRASHED],
            [name: "crashed_after_cancel_is_the_worker_killed_after_the_grace_period", outcome: [responseType: "CANCELATION", workerStopped: true, error: "x", cancelRequested: true], expect: RET.done_status.crashed_after_cancel]]
        ends.each { Map end ->
            def built = LcRunRecord.build(interactions, LcRunRecord.request([app: "interactions", tool: "t", inputs: [:], outcome: end.outcome, seconds: 0d, stderr: "", runId: "r", startedUtc: "2026-10-13T00:00:00.000Z"]))
            expect("done_status_" + end.name + "_is_" + end.expect, built.doneStatus == end.expect, built)
        }
        expect("done_status_the_file_names_the_four_task_statuses", RET.done_status.by_task_status.keySet() as Set == ["COMPLETE", "CANCELED", "FAILED", "CRASHED"] as Set && RET.done_status.crashed_after_cancel == "cancelled", RET.done_status)

        // ---- the ledger: every fixture id of the file is run by a body of this repository, or is said not to apply
        def WS = CONF.wrong_science
        def idsOf = { List<Map> list -> list.collect { it.id as String } }
        def fileIds = (idsOf(WS.coordinate.cases) + idsOf(WS.calibration.cases) + idsOf(WS.calibration.transitions) + idsOf(WS.retention.fixtures_tools) + idsOf(WS.retention.fixtures_hosts) +
                       idsOf(WS.run_identity.fixtures) + idsOf(WS.async_lookups.fixtures) + idsOf(WS.number_limits.cases)) as List<String>
        def LEDGER = [:]
        ["CF-01", "CF-07", "CF-08", "CF-09", "CF-10", "CF-11", "CF-12", "CF-14"].each { LEDGER[it] = "gui_test_frame_body" }
        ["CF-02", "CF-03", "CF-04", "CF-05", "CF-06", "CF-13"].each { LEDGER[it] = "n/a: QuPath has no spatial transform (README, coordinate frame)" }
        (1..20).each { LEDGER[String.format("CAL-%02d", it)] = "gui_test_calibration_body" }
        (1..9).each { LEDGER["CAL-T" + it] = "gui_test_calibration_body" }
        [1, 2, 3, 4, 5, 6, 7, 8, 9, 12, 13].each { LEDGER[String.format("RET-%02d", it)] = "n/a: run against Tools' retention.prune and its CLI, not a host test (the real prune is exercised by gui_test_retention_body)" }
        (1..6).each { LEDGER[String.format("HRET-%02d", it)] = "gui_test_retention_body" }
        ["RET-10", "RET-11"].each { LEDGER[it] = "gui_test_retention_body" }
        LEDGER["RET-14"] = "n/a: a result is never over a display limit here (the preview is a thumbnail) and every file already lives in the owned results folder"
        [1, 2, 3, 4, 6, 7].each { LEDGER[String.format("RUN-%02d", it)] = "gui_test_identity_body" }
        LEDGER["RUN-05"] = "n/a: Napari only (a layer is moved); an ImageData has no transform"
        (1..8).each { LEDGER[String.format("ASY-%02d", it)] = "gui_test_async_body" }
        (1..5).each { LEDGER[String.format("NUM-%02d", it)] = "gui_test_numbers_body" }
        def testsDir = new File(System.getProperty("lc.test.fixtures", new File("tests/fixtures").absolutePath)).parentFile
        fileIds.each { String id ->
            def how = LEDGER[id]
            if (how == null) expect("ledger_" + id + "_is_classified", false, "the file has a fixture this host has not classified: " + id)
            else if (how.startsWith("n/a")) println "UNSUPPORTED " + id + ": " + how
            else expect("ledger_" + id + "_has_its_body", new File(testsDir, how + ".groovy").isFile(), how)
        }
        expect("ledger_has_no_id_that_the_file_lacks", LEDGER.keySet().every { it in fileIds }, LEDGER.keySet().findAll { !(it in fileIds) })
        expect("ledger_the_file_has_the_fixture_counts_this_host_expects", fileIds.size() == LEDGER.size(), [fileIds.size(), LEDGER.size()])
        expect("the_conformance_file_is_not_read_from_this_repository", !confPath.startsWith(testsDir.parentFile.path), confPath)

    } catch (Throwable t) { t.printStackTrace(); results["EXCEPTION"] = false }
    finally { println "RESULTS " + results; Thread.sleep(500); System.exit(results && results.every { it.value } ? 0 : 1) }
}
