// ================================================================== GUI test body: registry trust checks (QP-1; appended to the tool script by tests/run_gui_test.sh)
// Needs no image and no app: it writes its own registry entries into $LC_HOME/apps (a scratch folder, emptied first) and calls LcRegistry.load().
// Owner checks need a second user ("tester") and root to chown; they are skipped (and say so) when chown is not allowed.
def results = [:]
def expect = { String name, boolean ok, Object detail = "" ->
    results[name] = ok
    println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : " -> " + detail))
}
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
def GOOD_SCHEMA = [protocol: 1, tools: [[id: "t.one-2", label: "T", inputs: [[name: "x_1", label: "X", type: "string"]], outputs: []]]]
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
refused("trust_protocol_2", "unsupported protocol 2")
refused("trust_tool_no_outputs", "a tool has an unexpected structure")
if (canChown) {
    refused("trust_owner_other", "entry file is not owned by the current user")
    refused("trust_schema_owner_other", "schema file is not owned by the current user")
} else println "NOTE owner checks skipped: chown is not allowed here"
expect("trust_no_null_pointer_text_anywhere", !problems.any { it.contains("null") && !it.contains("not a JSON") && !it.contains("must be") }, problems)
// a hostile parameter name must never reach a file name: nothing was written outside the scratch folder
expect("trust_nothing_escaped", !new File(home.parentFile, "escape.tif").exists() && !new File("/tmp/escape.tif").exists())
println "RESULTS " + results
System.exit(0)
