// ================================================================== GUI test body: which plugin an app needs (docs/PROTOCOL.md "Host requirements", Tools #155; appended to the tool script by tests/run_gui_test.sh)
// 1. the vectors of conformance.json, section `host_requirements`, through LcHostRequirements.check (the body FAILS when the section is missing); 2. registry files read by
// LcRegistry.load() with this plugin's version set to 0.1.0-alpha.6: an app that needs a newer QuPath plugin stays listed with its sentence in `warnings` (the dialog's status line),
// one that requires it is left out with its sentence in `problems`, and one this plugin satisfies says nothing.
// LC_HOME must be a scratch folder named lhome_hostreq (emptied first); the conformance file is the one of the installed labconstrictor-tools of an app registered in LC_SHARED_HOME.
def results = [:]
def expect = { String name, boolean ok, Object detail = "" ->
    results[name] = ok
    println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : " -> " + detail))
}

/** The `host_requirements` section of the conformance file of the Tools installed for the first app registered in `sharedHome`; null when the file has no such section. */
Map requirementsSection(File sharedHome) {
    List<File> entries = (new File(sharedHome, "apps").listFiles({ File f -> f.name.endsWith(".json") && !f.name.endsWith(".schema.json") } as FileFilter) as List<File>).sort { it.name }
    String python = (LcJson.parse(entries.first()) as Map).python as String
    String path = System.getenv("LC_CONFORMANCE") ?: [python, "-m", "labconstrictor_tools.conformance", "--path"].execute().text.trim()
    return (LcJson.parse(new File(path)) as Map).host_requirements as Map
}

/** One registry entry (and its schema, which has every feature this host needs) written under `apps`, with a fake interpreter that is never started; `extra` is merged into the entry. */
void writeApp(File home, String name, Map extra) {
    File apps = new File(home, "apps"), bin = new File(home, "prefixes/" + name + "/bin")
    bin.mkdirs()
    File python = new File(bin, "python"); python.text = "#!/bin/sh\n"
    [bin.parentFile, bin, python].each { File f -> assert ["chmod", "755", f.path].execute().waitFor() == 0 }
    Map schema = [protocol: 1, tools_version: "0.3.0a1", features: LcCompat.REQUIRED_FEATURES, tools: [[id: "t", label: "T", inputs: [], outputs: []]]]
    Map entry = [schema: 1, name: name, display_name: name, version: "", prefix: bin.parentFile.path, python: python.path, module: "m", pythonpath: [], runtime_path: "",
                 schema_path: new File(apps, name + ".schema.json").path, hosts: ["notebook", "qupath"]] + extra
    File entryFile = new File(apps, name + ".json"), schemaFile = new File(apps, name + ".schema.json")
    entryFile.text = LcJson.toJson(entry); schemaFile.text = LcJson.toJson(schema)
    [entryFile, schemaFile].each { File f -> assert ["chmod", "644", f.path].execute().waitFor() == 0 }
}

try {
    File home = new File(System.getenv("LC_HOME")), sharedHome = new File(System.getenv("LC_SHARED_HOME"))
    assert home.path.contains("lhome_hostreq") : "LC_HOME must be a scratch folder named lhome_hostreq"
    home.deleteDir(); new File(home, "apps").mkdirs()
    Map section = requirementsSection(sharedHome)
    expect("conformance_file_has_the_host_requirements_section", section != null, "the conformance.json of the installed labconstrictor-tools has no host_requirements key")
    if (section != null) {
        expect("the_feature_hosts_and_sentences_are_the_ones_of_the_file", section.feature == "host_requirements_v1" && "host_requirements_v1" in LcHostRequirements.HOST_FEATURES &&
               section.hosts == LcHostRequirements.HOSTS && LcHostRequirements.HOST == "qupath" && section.messages.too_old.template == LcHostRequirements.TOO_OLD &&
               section.messages.missing.template == LcHostRequirements.MISSING && section.messages.unknown_version.template == LcHostRequirements.UNKNOWN_VERSION &&
               section.messages.invalid.template == LcHostRequirements.INVALID && section.update_url.template == LcHostRequirements.UPDATE_URL, section.messages)
        List order = section.version_order as List
        expect("the_versions_sort_as_the_file_says", (1..<order.size()).every { int i -> LcHostRequirements.compare(order[i - 1], order[i]) < 0 }, order)
        (section.cases as List<Map>).each { Map c ->
            String label = "vector_" + (c.case as String).replaceAll(/[^A-Za-z0-9]+/, "_") + "_is_" + c.expect
            if (c.expect == "error") {
                boolean refused = false
                try { LcHostRequirements.check(c.entry as Map, c.host as String, c.host_version as String, c.host_features as List) } catch (IllegalArgumentException e) { refused = true }
                expect(label, refused)
                return
            }
            Map got = LcHostRequirements.check(c.entry as Map, c.host as String, c.host_version as String, c.host_features as List)
            expect(label, got.status == c.expect && got.sentence == c.sentence, got)
        }
        // ---- registry files, with this plugin's version set
        System.setProperty("lc.qupath.plugin_version", "0.1.0-alpha.6")
        writeApp(home, "NeedsNewer", [host_requirements: [qupath: [min_version: "0.1.0-alpha.7", why: "a test of the warning"]]])
        writeApp(home, "Requires", [host_requirements: [qupath: [min_version: "99.0", required: true]]])
        writeApp(home, "Satisfied", [host_requirements: [qupath: [min_version: "0.1.0-alpha.6", features: ["shapes_owner_v1"]], fiji: [min_version: "99.0"]]])
        Map registry = LcRegistry.load()
        String warn = "NeedsNewer needs LabConstrictor-QuPath 0.1.0-alpha.7 or newer (a test of the warning); this is 0.1.0-alpha.6. Update the plugin: " +
                      "https://github.com/CellMigrationLab/LabConstrictor-QuPath/releases/latest"
        String refuse = "Requires needs LabConstrictor-QuPath 99.0 or newer; this is 0.1.0-alpha.6. Update the plugin: https://github.com/CellMigrationLab/LabConstrictor-QuPath/releases/latest"
        expect("registry_an_app_that_needs_a_newer_plugin_stays_listed", registry.apps.containsKey("NeedsNewer"), registry.apps.keySet())
        expect("registry_its_sentence_is_a_warning", (registry.warnings as List<String>).contains(warn), registry.warnings)
        expect("registry_an_app_that_requires_a_newer_plugin_is_left_out_with_its_sentence", !registry.apps.containsKey("Requires") && (registry.problems as List<String>).contains(refuse), registry.problems)
        expect("registry_a_satisfied_app_says_nothing", registry.apps.containsKey("Satisfied") && !(registry.warnings as List<String>).any { it.contains("Satisfied") }, registry.warnings)
        System.setProperty("lc.qupath.plugin_version", "")
        Map unknown = LcRegistry.load()
        expect("registry_an_unknown_plugin_version_only_warns_even_when_required", unknown.apps.containsKey("Requires") &&
               (unknown.warnings as List<String>).any { it.startsWith("Requires needs LabConstrictor-QuPath 99.0 or newer; the version of this plugin is unknown.") }, unknown.warnings)
        System.clearProperty("lc.qupath.plugin_version")
    }
} catch (Throwable t) { t.printStackTrace(); results["EXCEPTION"] = false }
println "RESULTS " + results
System.exit(results && results.every { it.value } ? 0 : 1)
