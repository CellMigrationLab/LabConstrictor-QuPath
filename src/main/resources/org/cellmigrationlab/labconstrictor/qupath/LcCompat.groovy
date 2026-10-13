// ---------------------------------------------------------------------------------------------------- Tools compatibility (docs/PROTOCOL.md, "Compatibility rules for hosts")
/** May this host use a schema written by the labconstrictor-tools of an app? The rules of `labconstrictor_tools.compat.check_schema`, written in Groovy (Fiji and QuPath
 *  apply the same rules in their own language; the vectors are in conformance.json, `compat`, and the GUI tests run them). The result is [status: "ok" | "warn" | "refuse",
 *  reason: one sentence for the person ("" when ok)]. A missing field means unknown or old, never an error; odd input never throws. */
class LcCompat {
    static final String OK = "ok"
    static final String WARN = "warn"
    static final String REFUSE = "refuse"
    /** What this host needs from the Tools of an app: the run-record helpers (`runs.build_record`, `record_json`, `done_status`) arrived in 0.3.0a1, and the `prune`
     *  command is the feature `retention_prune`; `_job_dir` and `_calibration` are `reserved_inputs_v1`. */
    static final String MIN_TOOLS_VERSION = "0.3.0a1"
    static final List<String> REQUIRED_FEATURES = ["reserved_inputs_v1", "retention_prune"]
    private static final java.util.regex.Pattern VERSION = java.util.regex.Pattern.compile('^v?(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:[-.]?(a|alpha|b|beta|rc|c)[-.]?(\\d+))?$', java.util.regex.Pattern.CASE_INSENSITIVE)
    private static final Map<String, Integer> PRE_ORDER = [a: 0, alpha: 0, b: 1, beta: 1, rc: 2, c: 2]
    private static final int RELEASE_RANK = 3        // a release sorts after its pre-releases

    /** `0.2.0rc1` / `v0.2.0-rc.1` / `0.2` as a sortable list of five numbers (a release sorts after its pre-releases); null when it is not a version. */
    static List<Integer> parseVersion(Object text) {
        if (!(text instanceof String)) return null
        java.util.regex.Matcher match = VERSION.matcher((text as String).trim())
        if (!match.matches()) return null
        String pre = match.group(4)
        List<Integer> rank = pre != null ? [PRE_ORDER[pre.toLowerCase(Locale.ROOT)], Integer.parseInt(match.group(5))] : [RELEASE_RANK, 0]
        return [Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)), match.group(3) != null ? Integer.parseInt(match.group(3)) : 0] + rank
    }

    /** -1, 0 or 1 like Python's tuple comparison of two parsed versions. */
    static int compareVersions(List<Integer> a, List<Integer> b) {
        for (int i = 0; i < a.size(); i++) {
            int step = Integer.compare(a[i], b[i])
            if (step != 0) return step
        }
        return 0
    }

    private static Map<String, String> result(String status, String reason) { return [status: status, reason: reason] }

    /** The rules of the Tools reference for one cached schema. `minToolsVersion` and `warnBelowToolsVersion` may be null; a limit that is not a version is a programming error (IllegalArgumentException). */
    static Map<String, String> check(Map schema, String minToolsVersion, Collection<String> requiredFeatures, String warnBelowToolsVersion = null) {
        for (List<Object> limit : [["min_tools_version", minToolsVersion], ["warn_below_tools_version", warnBelowToolsVersion]]) {
            if (limit[1] && parseVersion(limit[1]) == null)
                throw new IllegalArgumentException(limit[0] + " " + limit[1] + " is not a version (programming error in the host)")
        }
        List<String> required = (requiredFeatures as Set<String>).sort() as List<String>
        Object protocol = schema.protocol
        boolean supported = protocol instanceof Number && LcConst.SUPPORTED_PROTOCOLS.any { it == (protocol as double) }
        if (!supported) return result(REFUSE, "this app speaks protocol " + pythonText(protocol) + ", which this host does not support")
        Object rawVersion = schema.tools_version
        Object rawFeatures = schema.features
        List<Integer> version = parseVersion(rawVersion)
        Set<String> features = rawFeatures instanceof List ? (rawFeatures as List).collect { String.valueOf(it) } as Set<String> : null
        if (features == null && required)
            return result(REFUSE, "this app's labconstrictor-tools is too old (it lists no features, this host needs " + required.join(", ") + "): update it")
        List<String> missing = required.findAll { String f -> features != null && !features.contains(f) }
        if (missing) return result(REFUSE, "this app's labconstrictor-tools lacks " + missing.join(", ") + ": update it")
        if (minToolsVersion) {
            if (rawVersion == null || rawVersion == "")
                return result(REFUSE, "this app's labconstrictor-tools is too old (no version recorded, this host needs " + minToolsVersion + " or newer): update it")
            List<Integer> wanted = parseVersion(minToolsVersion)
            if (version != null && compareVersions(version, wanted) < 0)
                return result(REFUSE, "this app's labconstrictor-tools " + rawVersion + " is older than the " + minToolsVersion + " this host needs: update it")
        }
        if (rawVersion == null || rawVersion == "") return result(WARN, "the version of this app's labconstrictor-tools is unknown (it predates version 0.2)")
        if (version == null) return result(WARN, "the labconstrictor-tools version " + pythonText(rawVersion) + " of this app is not understood")
        List<Integer> soft = parseVersion(warnBelowToolsVersion)
        if (soft != null && compareVersions(version, soft) < 0)
            return result(WARN, "this app's labconstrictor-tools " + rawVersion + " is older than the recommended " + warnBelowToolsVersion + ": update it")
        return result(OK, "")
    }

    /** What this host needs from the app's Tools (LcCompat.MIN_TOOLS_VERSION, LcCompat.REQUIRED_FEATURES), as the result of `check` for the app's cached schema. */
    static Map<String, String> checkApp(Map app) {
        return check(app.schema as Map, MIN_TOOLS_VERSION, REQUIRED_FEATURES)
    }

    /** A value the way Python's `%r` shows it in the Tools sentences: a string in single quotes, a whole number without ".0", None for null. */
    static String pythonText(Object value) {
        if (value == null) return "None"
        if (value instanceof String) return "'" + value + "'"
        if (value instanceof Double && (value as double) == Math.rint(value as double) && Math.abs(value as double) < 1e15d) return String.valueOf((value as double).longValue())
        return String.valueOf(value)
    }
}
