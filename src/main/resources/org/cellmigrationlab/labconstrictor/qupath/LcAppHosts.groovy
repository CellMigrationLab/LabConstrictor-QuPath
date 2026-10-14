// ---------------------------------------------------------------------------------------------------- which host lists an app (docs/PROTOCOL.md, "App hosts"; feature app_hosts_v1)
/** The per-host discovery rule of labconstrictor_tools.app_hosts.listed_for(entry, host), written in Groovy for the host "qupath". The registry entry of an app may declare
 *  `hosts` (a list of notebook | napari | fiji | qupath); this host never offers an app that excludes it. The sentences are the exact texts of conformance.json (`app_hosts`),
 *  which the GUI tests run. The command line and the notebooks are never filtered by this rule: it is about discovery in a host only. Never throws on an odd entry;
 *  an unknown asking host is a programming error (IllegalArgumentException), never an answer. */
class LcAppHosts {
    static final String FEATURE = "app_hosts_v1"
    static final String THIS_HOST = "qupath"
    static final List<String> HOSTS = ["notebook", "napari", "fiji", "qupath"]
    // the sentences: {app}, {host}, {hosts} and {problem} are filled in by sentence()
    static final String NOT_LISTED = "'{app}' is not listed in {host}: the app declares hosts: {hosts}"
    static final String ABSENT = "'{app}' declares no hosts, so it is listed in every host; register it with --app-manifest (or --hosts) to choose"
    static final String INVALID = "'{app}' is not listed in {host}: its hosts declaration is not valid ({problem}); register the app again with a valid one"
    // the problems an invalid declaration can have
    static final String NOT_A_LIST = "hosts must be a list of host names"
    static final String EMPTY = "hosts is empty; write at least notebook"
    static final String NOT_TEXT = "hosts must contain only text"
    static final String UNKNOWN_TEMPLATE = "unknown host name {name} (known: notebook, napari, fiji, qupath)"
    static final String REPEATED_TEMPLATE = "host name {name} appears twice"

    /** The sentence of `template` with each `{key}` replaced by its value (the keys of `values`). */
    static String sentence(String template, Map<String, String> values) {
        String text = template
        values.each { String key, String value -> text = text.replace("{" + key + "}", value) }
        return text
    }

    /** What is wrong with a declared `hosts` value, as a sentence for the person; null when it is a valid declaration (a non-empty list of unique known host names).
     *  A name is shown like Python's repr for a plain text (single quotes). */
    static String declarationProblem(Object declared) {
        if (!(declared instanceof List)) return NOT_A_LIST
        List names = declared as List
        if (names.isEmpty()) return EMPTY
        if (!names.every { it instanceof String }) return NOT_TEXT
        for (Object name : names) {
            if (!HOSTS.contains(name)) return sentence(UNKNOWN_TEMPLATE, [name: LcCompat.pythonText(name)])
        }
        for (Object name : names) {
            if (names.count(name) > 1) return sentence(REPEATED_TEMPLATE, [name: LcCompat.pythonText(name)])
        }
        return null
    }

    /** May `host` list the registry entry? [listed: boolean, sentence: String]; the sentence is "" when there is nothing to say. */
    static Map<String, Object> listedFor(Map entry, String host) {
        if (!HOSTS.contains(host))
            throw new IllegalArgumentException("unknown host '" + host + "' (a programming error in the host; known: " + HOSTS.join(", ") + ")")
        String app = entry.name == null ? "" : String.valueOf(entry.name)
        if (!entry.containsKey("hosts")) return [listed: true, sentence: sentence(ABSENT, [app: app])]
        String problem = declarationProblem(entry.hosts)
        if (problem != null) return [listed: false, sentence: sentence(INVALID, [app: app, host: host, problem: problem])]
        List<String> declared = entry.hosts as List<String>
        if (declared.contains(host)) return [listed: true, sentence: ""]
        return [listed: false, sentence: sentence(NOT_LISTED, [app: app, host: host, hosts: declared.join(", ")])]
    }

    /** The rule for this host: a non-empty sentence (a skip, or the "declares no hosts" note, said every time the registry is read) goes to the Tools log; the
     *  caller shows the skip sentence of an app that is not listed wherever it reports left-out apps. */
    static Map<String, Object> decide(Map entry) {
        Map<String, Object> verdict = listedFor(entry, THIS_HOST)
        if (verdict.sentence) LcLog.warn(verdict.sentence as String)
        return verdict
    }
}
