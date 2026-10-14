// ================================================================== GUI test body: the per-host discovery rule (docs/PROTOCOL.md "App hosts"; appended to the tool script by tests/run_gui_test.sh)
// 1. the vectors of conformance.json, section `app_hosts`, through LcAppHosts.listedFor, LcAppHosts's sentences and LcCompat (the body FAILS when the section is missing: a host that does not
//    know the rule must not pass); 2. the same entries written as real registry files in a scratch registry and read by LcRegistry.load(): an app that is not listed is left out of `apps`,
//    its skip sentence is in `problems` (where the dialog says what it skipped) and in the Tools log, and the "declares no hosts" sentence is logged every time the registry is read.
// LC_HOME must be a scratch folder named lhome_hosts (emptied first); the conformance file is the one of the installed labconstrictor-tools of an app registered in LC_SHARED_HOME.
def results = [:]
def expect = { String name, boolean ok, Object detail = "" ->
    results[name] = ok
    println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : " -> " + detail))
}

/** The `app_hosts` section of the conformance file of the Tools installed for the first app registered in `sharedHome`; null when the file has no such section. */
Map appHostsSection(File sharedHome) {
    List<File> entries = (new File(sharedHome, "apps").listFiles({ File f -> f.name.endsWith(".json") && !f.name.endsWith(".schema.json") } as FileFilter) as List<File>).sort { it.name }
    String python = (LcJson.parse(entries.first()) as Map).python as String
    String path = System.getenv("LC_CONFORMANCE") ?: [python, "-m", "labconstrictor_tools.conformance", "--path"].execute().text.trim()
    return (LcJson.parse(new File(path)) as Map).app_hosts as Map
}

/** Runs one vector through the rule: [ok, detail]. An `error` vector (an unknown asking host) must throw IllegalArgumentException, never give a verdict. */
List runCase(Map vector) {
    if (vector.expect == "error") {
        try { LcAppHosts.listedFor(vector.entry as Map, vector.host as String) } catch (IllegalArgumentException e) { return [true, e.message] }
        return [false, "no exception for host " + vector.host]
    }
    Map<String, Object> got = LcAppHosts.listedFor(vector.entry as Map, vector.host as String)
    boolean ok = got.listed == (vector.expect == "listed") && got.sentence == vector.sentence
    return [ok, got]
}

/** One registry entry (and its schema, which has every feature this host needs) written under `apps`, with a fake interpreter that is never started; `extra` is merged into the entry. */
void writeApp(File home, String name, Map extra) {
    File apps = new File(home, "apps"), bin = new File(home, "prefixes/" + name + "/bin")
    bin.mkdirs()
    File python = new File(bin, "python"); python.text = "#!/bin/sh\n"
    [bin.parentFile, bin, python].each { File f -> assert ["chmod", "755", f.path].execute().waitFor() == 0 }
    Map schema = [protocol: 1, tools_version: "0.3.0a1", features: LcCompat.REQUIRED_FEATURES, tools: [[id: "t", label: "T", inputs: [], outputs: []]]]
    Map entry = [schema: 1, name: name, display_name: name, version: "", prefix: bin.parentFile.path, python: python.path, module: "m", pythonpath: [], runtime_path: "",
                 schema_path: new File(apps, name + ".schema.json").path] + extra
    File entryFile = new File(apps, name + ".json"), schemaFile = new File(apps, name + ".schema.json")
    entryFile.text = LcJson.toJson(entry); schemaFile.text = LcJson.toJson(schema)
    [entryFile, schemaFile].each { File f -> assert ["chmod", "644", f.path].execute().waitFor() == 0 }
}

/** How many times the Tools log of `home` holds `text`. */
int timesLogged(File home, String text) {
    File log = new File(home, "logs/" + LcConst.LOG_NAME)
    return log.isFile() ? log.getText("UTF-8").split("\n").count { String line -> line.endsWith(" qupath: " + text) } as int : 0
}

try {
    File home = new File(System.getenv("LC_HOME")), sharedHome = new File(System.getenv("LC_SHARED_HOME"))
    assert home.path.contains("lhome_hosts") : "LC_HOME must be a scratch folder named lhome_hosts"
    home.deleteDir(); new File(home, "apps").mkdirs()
    Map section = appHostsSection(sharedHome)
    expect("conformance_file_has_the_app_hosts_section", section != null, "the conformance.json of the installed labconstrictor-tools has no app_hosts key")
    if (section != null) {
        // ---- the vectors, as the reference function gives them
        expect("the_feature_and_the_known_hosts_are_the_ones_of_the_file", section.feature == LcAppHosts.FEATURE && section.known_hosts == LcAppHosts.HOSTS && LcAppHosts.THIS_HOST == "qupath", [section.feature, section.known_hosts])
        List<Map> cases = section.cases as List<Map>
        expect("the_file_has_vectors", cases.size() >= 30, cases.size())
        cases.each { Map vector ->
            List got = runCase(vector)
            expect("vector_" + (vector.case as String).replaceAll(/[^A-Za-z0-9]+/, "_") + "_is_" + vector.expect, got[0] as boolean, got[1])
        }
        Map messages = section.messages as Map
        expect("the_three_sentences_are_the_templates_of_the_file",
               messages.not_listed.template == LcAppHosts.NOT_LISTED && messages.absent.template == LcAppHosts.ABSENT && messages.invalid.template == LcAppHosts.INVALID, messages)
        // ---- what this host needs from the Tools of an app
        Map rule = section.compat_rule as Map
        expect("compat_this_host_needs_the_minimum_and_the_feature_of_the_file", rule.min_tools_version == LcCompat.MIN_TOOLS_VERSION && (rule.required_features as List).every { it in LcCompat.REQUIRED_FEATURES }, rule)
        (section.compat_cases as List<Map>).each { Map c ->
            Map<String, String> got = LcCompat.check(c.schema as Map, rule.min_tools_version as String, rule.required_features as List<String>)
            expect("compat_" + (c.case as String).replaceAll(/[^A-Za-z0-9]+/, "_") + "_is_" + c.expect, got.status == c.expect && (!c.reason_contains || got.reason.contains(c.reason_contains as String)), got)
        }
        // ---- the same entries as registry files read by LcRegistry.load()
        List<Map> mine = cases.findAll { it.host == "qupath" && it.expect != "error" }
        expect("the_qupath_vectors_have_one_app_each", mine.collect { (it.entry as Map).name }.toSet().size() == mine.size(), mine.collect { (it.entry as Map).name })
        mine.each { Map vector -> Map entry = vector.entry as Map; writeApp(home, entry.name as String, entry - [name: entry.name]) }
        Map registry = LcRegistry.load()
        mine.each { Map vector ->
            String app = (vector.entry as Map).name, sentence = vector.sentence as String
            boolean listed = vector.expect == "listed"
            expect("registry_" + app + "_is_" + vector.expect, registry.apps.containsKey(app) == listed, registry.apps.keySet())
            if (!listed) expect("registry_" + app + "_skip_sentence_is_in_the_problems", (registry.problems as List<String>).contains(sentence), registry.problems)
            else expect("registry_" + app + "_has_no_problem", !(registry.problems as List<String>).any { it.contains("'" + app + "'") }, registry.problems)
            expect("registry_" + app + "_sentence_is_in_the_tools_log", sentence == "" || timesLogged(home, sentence) == 1, timesLogged(home, sentence))
        }
        // a declaration that is `null` in the JSON file (the key is there): not listed, said as invalid
        writeApp(home, "NullHosts", [hosts: null])
        Map again = LcRegistry.load()
        String oldApp = "'OldApp' declares no hosts, so it is listed in every host; register it with --app-manifest (or --hosts) to choose"
        expect("registry_null_hosts_is_not_listed_and_says_why", !again.apps.containsKey("NullHosts") && (again.problems as List<String>).any { it.startsWith("'NullHosts' is not listed in qupath: its hosts declaration is not valid (hosts must be a list of host names)") }, again.problems)
        expect("registry_the_absent_sentence_is_logged_every_time_the_registry_is_read", timesLogged(home, oldApp) == 2, timesLogged(home, oldApp))
        expect("registry_every_app_of_the_matrix_but_vlab4mic_is_offered", ["Playground", "NucleiSky", "GuessTheCondition"].every { again.apps.containsKey(it) } && !again.apps.containsKey("VLab4Mic") && !again.apps.containsKey("CellTracksColab"), again.apps.keySet())
    }
} catch (Throwable t) { t.printStackTrace(); results["EXCEPTION"] = false }
println "RESULTS " + results
System.exit(results && results.every { it.value } ? 0 : 1)
