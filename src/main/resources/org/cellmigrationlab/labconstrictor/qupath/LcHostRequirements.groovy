// ---------------------------------------------------------------------------------------------------- which plugin an app needs (docs/PROTOCOL.md, "Host requirements"; feature host_requirements_v1)
// Does THIS plugin (LabConstrictor-QuPath) meet an app's host_requirements (Tools #155)? Written in Groovy exactly as labconstrictor_tools.host_requirements.check writes it in
// Python (same verdicts, same sentences, word for word); the same class as LabConstrictor-Fiji's LCHostRequirements, with this host's name and feature ids. The GUI test feeds it the
// vectors of conformance.json (`host_requirements`) and compares it with the reference function.

/** The host-requirements rule: [status: ok | warn | refuse, sentence: String] for a registry entry, the asking host, its version and its feature ids. */
class LcHostRequirements {
    static final String HOST = 'qupath'
    static final List<String> HOSTS = ['napari', 'fiji', 'qupath']
    static final Map<String, String> PLUGIN = [napari: 'napari', fiji: 'Fiji', qupath: 'QuPath']
    static final List<String> KEYS = ['min_version', 'features', 'why', 'required']
    // The host feature ids THIS plugin has (Tools docs/HOST_FEATURES.md); an id is added in the release that implements it.
    static final List<String> HOST_FEATURES = ['host_requirements_v1', 'shapes_owner_v1']
    static final String UPDATE_URL = 'https://github.com/CellMigrationLab/LabConstrictor-{plugin}/releases/latest'
    static final String TOO_OLD = '{app} needs LabConstrictor-{plugin} {min_version} or newer{why}; this is {host_version}. Update the plugin: {url}'
    static final String MISSING = '{app} needs LabConstrictor-{plugin} with {features}{why}; this plugin does not have it. Update the plugin: {url}'
    static final String UNKNOWN_VERSION = '{app} needs LabConstrictor-{plugin} {min_version} or newer{why}; the version of this plugin is unknown. ' +
        'Update the plugin if the app misbehaves: {url}'
    static final String INVALID = '{app} has a host_requirements declaration that is not valid ({problem}); register the app again with a valid one'
    static final String NOT_A_MAPPING = 'host_requirements must map host names (napari, fiji, qupath) to their requirements'
    static final String UNKNOWN_HOST = 'unknown host {name} in host_requirements (known: napari, fiji, qupath; the notebook needs no plugin)'
    static final String HOST_NOT_A_MAPPING = 'host_requirements.{host} must hold min_version and/or features'
    static final String UNKNOWN_KEY = 'unknown key {key} in host_requirements.{host} (known: ' + KEYS.join(', ') + ')'
    static final String NOTHING_REQUIRED = 'host_requirements.{host} needs min_version or features'
    static final String BAD_VERSION = 'host_requirements.{host}.min_version {value} is not a version like 0.1.0-alpha.6'
    static final String BAD_FEATURES = 'host_requirements.{host}.features must be a list of feature ids like shapes_owner_v1'
    static final String BAD_WHY = 'host_requirements.{host}.why must be one line of text'
    static final String BAD_REQUIRED = 'host_requirements.{host}.required must be true or false'

    static final java.util.regex.Pattern VERSION = java.util.regex.Pattern.compile(
        '^v?(\\d+(?:\\.\\d+)*)' +
        '(?:[-_.]?(a|alpha|b|beta|c|rc|pre|preview)[-_.]?(\\d*))?' +
        '(?:[-_.]?(post|rev|r)[-_.]?(\\d*))?' +
        '(?:[-_.]?(dev)[-_.]?(\\d*))?' +
        '(?:\\+[a-z0-9.]+)?$', java.util.regex.Pattern.CASE_INSENSITIVE)
    static final java.util.regex.Pattern FEATURE_ID = java.util.regex.Pattern.compile('^[a-z][a-z0-9_]*$')
    static final Map<String, Integer> PRE_RANK = [a: 0, alpha: 0, b: 1, beta: 1, c: 2, rc: 2, pre: 2, preview: 2]

    /** How Python's `%r` writes a value: text quoted as Python does, numbers and booleans as Python writes them, null as None. */
    static String python(Object value) {
        if (value == null) return 'None'
        if (value instanceof Boolean) return value ? 'True' : 'False'
        if (!(value instanceof String)) return value.toString()
        String text = value as String
        String quote = text.contains("'") && !text.contains('"') ? '"' : "'"
        String body = text.replace('\\', '\\\\').replace('\n', '\\n').replace('\r', '\\r').replace('\t', '\\t')
        if (quote == "'") body = body.replace("'", "\\'")
        return quote + body + quote
    }

    /** A sortable key (a list of numbers) for a PEP 440 version or a release tag (v0.1.0-alpha.6); an IllegalArgumentException when it is not one. */
    static List<Integer> versionKey(Object text) {
        def match = text instanceof String ? VERSION.matcher((text as String).trim()) : null
        if (match == null || !match.matches()) throw new IllegalArgumentException('not a version: ' + python(text))
        List<Integer> numbers = match.group(1).split('\\.').collect { String part -> Integer.parseInt(part) }
        while (numbers.size() > 1 && numbers[-1] == 0) numbers.remove(numbers.size() - 1)
        String pre = match.group(2), post = match.group(4), dev = match.group(6)
        List<Integer> phase = pre ? [0, PRE_RANK[pre.toLowerCase()], number(match.group(3))] : (dev && !post ? [-1, 0, 0] : [1, 0, 0])
        List<Integer> after = post ? [1, number(match.group(5))] : [0, 0]
        List<Integer> development = dev ? [0, number(match.group(7))] : [1, 0]
        // the release numbers are padded so that keys of different lengths compare position by position (0.1 = 0.1.0 after the zeros are dropped)
        List<Integer> padded = numbers + [-1] * (12 - Math.min(12, numbers.size()))
        return padded + phase + after + development
    }

    /** The verdict for nothing to say (a new map each time: callers may add to it). */
    static Map ok() { return [status: 'ok', sentence: ''] }

    private static int number(String digits) { return digits ? Integer.parseInt(digits) : 0 }

    /** -1, 0 or 1 as the version `a` is older than, equal to or newer than `b` (both must be versions). */
    static int compare(Object a, Object b) {
        List<Integer> x = versionKey(a), y = versionKey(b)
        for (int i = 0; i < x.size(); i++) {
            if (x[i] != y[i]) return x[i] < y[i] ? -1 : 1
        }
        return 0
    }

    private static Map<String, Object> parseHost(String host, Object value) {
        if (!(value instanceof Map)) throw new IllegalArgumentException(HOST_NOT_A_MAPPING.replace('{host}', host))
        Map need = value as Map
        need.keySet().each { Object key ->
            if (!(key in KEYS)) throw new IllegalArgumentException(UNKNOWN_KEY.replace('{key}', python(key)).replace('{host}', host))
        }
        if (!need.containsKey('min_version') && !need.containsKey('features')) throw new IllegalArgumentException(NOTHING_REQUIRED.replace('{host}', host))
        Map<String, Object> clean = [:]
        if (need.containsKey('min_version')) {
            Object minimum = need.min_version
            try { versionKey(minimum) } catch (IllegalArgumentException ignored) {
                throw new IllegalArgumentException(BAD_VERSION.replace('{host}', host).replace('{value}', python(minimum)))
            }
            clean.min_version = (minimum as String).trim()
        }
        if (need.containsKey('features')) {
            Object features = need.features
            if (!(features instanceof List) || !(features as List).every { Object f -> f instanceof String && FEATURE_ID.matcher(f as String).matches() }) {
                throw new IllegalArgumentException(BAD_FEATURES.replace('{host}', host))
            }
            clean.features = new ArrayList(features as List)
        }
        if (need.containsKey('why')) {
            Object why = need.why
            if (!(why instanceof String) || (why as String).contains('\n') || !(why as String).trim()) throw new IllegalArgumentException(BAD_WHY.replace('{host}', host))
            clean.why = (why as String).trim()
        }
        if (need.containsKey('required')) {
            if (!(need.required instanceof Boolean)) throw new IllegalArgumentException(BAD_REQUIRED.replace('{host}', host))
            clean.required = need.required
        }
        return clean
    }

    /** The declaration, cleaned, if it is valid; else an IllegalArgumentException whose message is the problem (one of the texts above). */
    static Map<String, Map<String, Object>> parseDeclared(Object value) {
        if (!(value instanceof Map)) throw new IllegalArgumentException(NOT_A_MAPPING)
        Map declared = value as Map
        declared.keySet().each { Object host ->
            if (!(host in HOSTS)) throw new IllegalArgumentException(UNKNOWN_HOST.replace('{name}', python(host)))
        }
        Map<String, Map<String, Object>> clean = [:]
        HOSTS.each { String host -> if (declared.containsKey(host)) clean[host] = parseHost(host, declared[host]) }
        return clean
    }

    /** Does this host plugin meet the app's requirements? [status: ok | warn | refuse, sentence ('' when there is nothing to say)]. An unknown asking host is the host's own
     *  programming error (IllegalArgumentException), never a verdict. A null or unreadable version is "unknown": a warning, never a refusal. */
    static Map check(Map entry, String host, String hostVersion, List hostFeatures) {
        if (!(host in HOSTS)) throw new IllegalArgumentException('unknown host ' + python(host) + ' (a programming error in the host; known: ' + HOSTS.join(', ') + ')')
        Object shown = entry.get('display_name') ?: entry.get('name')
        String app = shown == null ? '' : shown.toString()
        if (!entry.containsKey('host_requirements')) return ok()
        Map<String, Map<String, Object>> declared
        try {
            declared = parseDeclared(entry.get('host_requirements'))
        } catch (IllegalArgumentException problem) {
            return [status: 'warn', sentence: INVALID.replace('{app}', app).replace('{problem}', problem.message)]
        }
        Map<String, Object> need = declared[host]
        if (need == null) return ok()
        Closure<String> fill = { String template, String features ->
            template.replace('{app}', app).replace('{plugin}', PLUGIN[host]).replace('{min_version}', (need.min_version ?: '') as String)
                .replace('{why}', need.why ? ' (' + need.why + ')' : '').replace('{host_version}', hostVersion ?: '').replace('{features}', features)
                .replace('{url}', UPDATE_URL.replace('{plugin}', PLUGIN[host]))
        }
        String failed = need.required ? 'refuse' : 'warn'
        boolean unknown = false
        if (need.containsKey('min_version')) {
            try {
                if (compare(hostVersion, need.min_version) < 0) return [status: failed, sentence: fill(TOO_OLD, '')]
            } catch (IllegalArgumentException ignored) {
                unknown = true                                     // rule 4: said, never a refusal; the features still count
            }
        }
        List missing = ((need.features ?: []) as List).findAll { Object f -> !((hostFeatures ?: []) as List).contains(f) }
        if (missing) return [status: failed, sentence: fill(MISSING, missing.join(', '))]
        return unknown ? [status: 'warn', sentence: fill(UNKNOWN_VERSION, '')] : ok()
    }

    /** This plugin's version: the jar's Implementation-Version (build.sh writes the release tag there); null (unknown) when it is not run from the jar. The system property
     *  lc.qupath.plugin_version replaces it (tests, read at each use). */
    static String pluginVersion() {
        String forced = System.getProperty('lc.qupath.plugin_version')
        if (forced != null) return forced ?: null
        try {
            return Thread.currentThread().contextClassLoader.loadClass('org.cellmigrationlab.labconstrictor.qupath.LabConstrictorExtension').getPackage()?.getImplementationVersion()
        } catch (ClassNotFoundException ignored) {
            return null
        }
    }

    /** The rule for this plugin (QuPath), with its own version and feature ids. */
    static Map decide(Map entry) { return check(entry, HOST, pluginVersion(), HOST_FEATURES) }
}
