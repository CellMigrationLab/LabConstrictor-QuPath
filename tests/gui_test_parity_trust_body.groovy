// ================================================================== GUI test body: registry trust checks (QP-1; appended to the tool script by tests/run_gui_test.sh)
// Needs no image and no app: it writes its own registry entries into $LC_HOME/apps (a scratch folder, emptied first) and calls LcRegistry.load().
// Owner checks need a second user ("tester") and root to chown; they are skipped (and say so) when chown is not allowed.
def results = [:]
def expect = { String name, boolean ok, Object detail = "" ->
    results[name] = ok
    println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : " -> " + detail))
}
try {
def home = new File(System.getenv("LC_HOME")), apps = new File(home, "apps")
assert home.path.contains("lhome_trust") : "LC_HOME must be a scratch folder named lhome_trust"
home.deleteDir(); apps.mkdirs()
def chmod = { String mode, File f -> assert ["chmod", mode, f.path].execute().waitFor() == 0 }
def prefixes = new File(home, "prefixes"); prefixes.mkdirs()
// a fake install prefix with an interpreter (never started: the registry only checks files and permissions)
def newPrefix = { String name ->
    def prefix = new File(prefixes, name), bin = new File(prefix, "bin"); bin.mkdirs()
    def python = new File(bin, "python"); python.text = "#!/bin/sh\n"
    chmod("755", prefix); chmod("755", bin); chmod("755", python)
    return [prefix, bin, python]
}
def GOOD_SCHEMA = [protocol: 1, tools_version: "0.3.0a1", features: ["app_hosts_v1", "reserved_inputs_v1", "retention_prune"], tools: [[id: "t.one-2", label: "T", inputs: [[name: "x_1", label: "X", type: "string"]], outputs: []]]]
// writes <name>.json and its schema; `entryMods` and `schemaMods` are applied to the maps before they are written
def mk = { String name, Closure entryMods = null, Closure schemaMods = null ->
    def (prefix, bin, python) = newPrefix(name)
    def entry = [schema: 1, name: name, display_name: name, version: "", prefix: prefix.path, python: python.path, module: "m", pythonpath: [], runtime_path: "",
                 schema_path: new File(apps, name + ".schema.json").path]
    def schema = LcJson.parseText(LcJson.toJson(GOOD_SCHEMA))
    if (entryMods) entryMods(entry, [prefix, bin, python])
    if (schemaMods) schemaMods(schema)
    def entryFile = new File(apps, name + ".json"), schemaFile = new File(apps, name + ".schema.json")
    entryFile.text = LcJson.toJson(entry); schemaFile.text = LcJson.toJson(schema)
    chmod("644", entryFile); chmod("644", schemaFile)
    return [entryFile, schemaFile]
}
def probe = new File(home, "probe"); probe.text = "x"
def canChown = ["chown", "tester", probe.path].execute().waitFor() == 0

mk("trust_ok")
mk("trust_group_writable_entry") { e, p -> }.with { chmod("664", it[0]) }
mk("trust_group_writable_schema").with { chmod("664", it[1]) }
mk("trust_schema_elsewhere") { e, p -> def other = new File(home, "elsewhere"); other.mkdirs(); new File(other, "s.json").text = LcJson.toJson(GOOD_SCHEMA); e.schema_path = new File(other, "s.json").path }
mk("trust_interp_world_writable") { e, p -> chmod("777", p[2]) }
mk("trust_interp_folder_world_writable") { e, p -> chmod("777", p[1]) }
mk("trust_prefix_world_writable") { e, p -> chmod("777", p[0]) }
mk("trust_sticky_folder_ok") { e, p -> chmod("1777", p[1]) }
mk("trust_prefix_is_root") { e, p -> e.prefix = "/"; e.python = "/bin/sh" }
mk("trust_interp_outside_prefix") { e, p -> e.python = "/bin/sh" }
mk("trust_interp_missing") { e, p -> e.python = new File(p[1], "nothere").path }
mk("trust_name_with_slash") { e, p -> e.name = "../evil" }
mk("trust_name_dotdot") { e, p -> e.name = ".." }
mk("trust_missing_field") { e, p -> e.remove("module") }
mk("trust_field_not_text") { e, p -> e.python = 7 }
mk("trust_pythonpath_not_list") { e, p -> e.pythonpath = "x" }
mk("trust_param_name_path") { e, p -> } { s -> s.tools[0].inputs[0].name = "../../escape" }
mk("trust_param_name_space") { e, p -> } { s -> s.tools[0].inputs[0].name = "a b" }
mk("trust_tool_id_path") { e, p -> } { s -> s.tools[0].id = "../x" }
mk("trust_protocol_2") { e, p -> } { s -> s.protocol = 2 }
mk("trust_tool_no_outputs") { e, p -> } { s -> s.tools[0].remove("outputs") }
// issue 20: malformed entries and cached schemas are refused with a reason before they reach the app selector or the form
mk("trust_tools_none") { e, p -> } { s -> s.tools = null }
mk("trust_tools_not_list") { e, p -> } { s -> s.tools = "x" }
mk("trust_tool_not_object") { e, p -> } { s -> s.tools = [3] }
mk("trust_tool_no_label") { e, p -> } { s -> s.tools[0].remove("label") }
mk("trust_inputs_not_list") { e, p -> } { s -> s.tools[0].inputs = "x" }
mk("trust_param_not_object") { e, p -> } { s -> s.tools[0].inputs = [7] }
mk("trust_param_name_missing") { e, p -> } { s -> s.tools[0].inputs[0].remove("name") }
mk("trust_param_name_not_text") { e, p -> } { s -> s.tools[0].inputs[0].name = 5 }
mk("trust_param_no_label") { e, p -> } { s -> s.tools[0].inputs[0].remove("label") }
mk("trust_choice_without_choices") { e, p -> } { s -> s.tools[0].inputs[0].type = "choice" }
mk("trust_choice_empty_choices") { e, p -> } { s -> s.tools[0].inputs[0].type = "choice"; s.tools[0].inputs[0].choices = [] }
mk("trust_output_not_object") { e, p -> } { s -> s.tools[0].outputs = ["x"] }
mk("trust_output_no_name") { e, p -> } { s -> s.tools[0].outputs = [[type: "table"]] }
mk("trust_output_name_path") { e, p -> } { s -> s.tools[0].outputs = [[name: "../x", type: "table"]] }
mk("trust_output_no_type") { e, p -> } { s -> s.tools[0].outputs = [[name: "t"]] }
mk("trust_pythonpath_item_not_text") { e, p -> e.pythonpath = [1] }
mk("trust_runtime_path_not_text") { e, p -> e.runtime_path = 5 }
mk("trust_module_empty") { e, p -> e.module = "" }
mk("trust_choice_and_outputs_ok") { e, p -> } { s -> s.tools[0].inputs[0].type = "choice"; s.tools[0].inputs[0].choices = ["a", "b"]; s.tools[0].outputs = [[name: "t", type: "table"], [name: "pts", type: "points", replace: true]] }
mk("trust_tools_old") { e, p -> } { s -> s.tools_version = "0.2.0rc1" }
mk("trust_tools_no_feature") { e, p -> } { s -> s.features = ["app_hosts_v1", "reserved_inputs_v1"] }
mk("trust_tools_no_hosts_feature") { e, p -> } { s -> s.features = ["reserved_inputs_v1", "retention_prune"] }
mk("trust_tools_no_fields") { e, p -> } { s -> s.remove("tools_version"); s.remove("features") }
mk("trust_tools_no_version") { e, p -> } { s -> s.remove("tools_version") }
mk("trust_tools_banana") { e, p -> } { s -> s.tools_version = "banana" }
mk("trust_tools_newer") { e, p -> } { s -> s.tools_version = "0.10.0"; s.features = ["app_hosts_v1", "reserved_inputs_v1", "retention_prune", "later"] }
mk("trust_owner_other") { e, p -> }.with { if (canChown) assert ["chown", "tester", it[0].path].execute().waitFor() == 0 }
mk("trust_schema_owner_other").with { if (canChown) assert ["chown", "tester", it[1].path].execute().waitFor() == 0 }

def registry = LcRegistry.load()
def loaded = registry.apps.keySet(), problems = registry.problems as List<String>
def problemOf = { String name -> problems.find { it.startsWith(name + ": ") || it.startsWith(name + ".json: ") } }
def refused = { String name, String needle ->
    def p = problemOf(name)
    expect("trust_" + name.replace("trust_", "") + "_refused", p != null && p.contains(needle) && !loaded.contains(name), p ?: ("loaded: " + loaded.contains(name)))
}
expect("trust_ok_loads", loaded.contains("trust_ok") && problemOf("trust_ok") == null, problems)
expect("trust_ok_tool_id_with_dot_and_dash_loads", registry.apps["trust_ok"]?.schema?.tools?[0]?.id == "t.one-2")
expect("trust_sticky_folder_is_not_world_writable", loaded.contains("trust_sticky_folder_ok"), problemOf("trust_sticky_folder_ok"))
refused("trust_group_writable_entry", "entry file is writable by other users")
refused("trust_group_writable_schema", "schema file is writable by other users")
refused("trust_schema_elsewhere", "is not in the same folder as the entry")
refused("trust_interp_world_writable", "interpreter " + new File(prefixes, "trust_interp_world_writable/bin/python").path + " is writable by everybody")
refused("trust_interp_folder_world_writable", "interpreter folder")
refused("trust_prefix_world_writable", "install prefix")
refused("trust_prefix_is_root", "is a filesystem root")
refused("trust_interp_outside_prefix", "is not inside the install prefix")
refused("trust_interp_missing", "not available on this machine (interpreter")
refused("trust_name_with_slash", "invalid app name '../evil'")
refused("trust_name_dotdot", "invalid app name '..'")
refused("trust_missing_field", "field 'module' must be a non-empty string")
refused("trust_field_not_text", "field 'python' must be a non-empty string")
refused("trust_pythonpath_not_list", "field 'pythonpath' must be a list of strings")
refused("trust_param_name_path", "parameter without an identifier name")
refused("trust_param_name_space", "parameter without an identifier name")
refused("trust_tool_id_path", "a tool has an unexpected structure")
refused("trust_protocol_2", "schema protocol 2 is not supported (this host speaks [1])")
refused("trust_tool_no_outputs", "a tool has an unexpected structure")
refused("trust_tools_none", "schema has no list of tools")
refused("trust_tools_not_list", "schema has no list of tools")
refused("trust_tool_not_object", "a tool has an unexpected structure")
refused("trust_tool_no_label", "a tool has an unexpected structure")
refused("trust_inputs_not_list", "a tool has an unexpected structure")
refused("trust_param_not_object", "parameter without an identifier name")
refused("trust_param_name_missing", "parameter without an identifier name")
refused("trust_param_name_not_text", "parameter without an identifier name")
refused("trust_param_no_label", "parameter without an identifier name and a text label")
refused("trust_choice_without_choices", "choice parameter 'x_1' has no choices")
refused("trust_choice_empty_choices", "choice parameter 'x_1' has no choices")
refused("trust_output_not_object", "has an output without a plain name and a text type")
refused("trust_output_no_name", "has an output without a plain name and a text type")
refused("trust_output_name_path", "has an output without a plain name and a text type")
refused("trust_output_no_type", "has an output without a plain name and a text type")
refused("trust_pythonpath_item_not_text", "field 'pythonpath' must be a list of strings")
refused("trust_runtime_path_not_text", "field 'runtime_path' must be a string")
refused("trust_module_empty", "field 'module' must be a non-empty string")
expect("trust_valid_choice_and_outputs_load", loaded.contains("trust_choice_and_outputs_ok") && problemOf("trust_choice_and_outputs_ok") == null, problemOf("trust_choice_and_outputs_ok"))
// the compatibility rules (LcCompat, the vectors of conformance.json): an app whose Tools lacks what this host needs is not offered, with one sentence; an unparsable version only warns
refused("trust_tools_old", "this app's labconstrictor-tools 0.2.0rc1 is older than the 0.3.0a1 this host needs: update it")
refused("trust_tools_no_feature", "this app's labconstrictor-tools lacks retention_prune: update it")
refused("trust_tools_no_hosts_feature", "this app's labconstrictor-tools lacks app_hosts_v1: update it")
refused("trust_tools_no_fields", "this app's labconstrictor-tools is too old (it lists no features, this host needs app_hosts_v1, reserved_inputs_v1, retention_prune): update it")
refused("trust_tools_no_version", "this app's labconstrictor-tools is too old (no version recorded, this host needs 0.3.0a1 or newer): update it")
expect("trust_tools_banana_is_offered_with_a_warning", loaded.contains("trust_tools_banana") && problemOf("trust_tools_banana") == null && (registry.warnings as List<String>).any { it.startsWith("trust_tools_banana: the labconstrictor-tools version 'banana' of this app is not understood") }, [registry.warnings, problemOf("trust_tools_banana")])
expect("trust_tools_newer_is_offered_without_a_warning", loaded.contains("trust_tools_newer") && !(registry.warnings as List<String>).any { it.startsWith("trust_tools_newer") }, registry.warnings)
expect("trust_a_good_app_has_no_warning", !(registry.warnings as List<String>).any { it.startsWith("trust_ok:") }, registry.warnings)
if (canChown) {
    refused("trust_owner_other", "entry file is not owned by the current user")
    refused("trust_schema_owner_other", "schema file is not owned by the current user")
} else println "NOTE owner checks skipped: chown is not allowed here"
expect("trust_no_null_pointer_text_anywhere", !problems.any { it.contains("null") && !it.contains("not a JSON") && !it.contains("must be") }, problems)
// a hostile parameter name must never reach a file name: nothing was written outside the scratch folder
expect("trust_nothing_escaped", !new File(home.parentFile, "escape.tif").exists() && !new File("/tmp/escape.tif").exists())
} catch (Throwable t) { t.printStackTrace(); results["EXCEPTION"] = false }
println "RESULTS " + results
System.exit(results && results.every { it.value } ? 0 : 1)
