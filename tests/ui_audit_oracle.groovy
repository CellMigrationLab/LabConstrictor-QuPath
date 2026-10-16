// ================================================================== UI audit, part 3: oracles that are not the window's own state
// The registry entry on disk, the case files, the text of Copy as command parsed back, the fixture images read by javax.imageio (not by the host's own TIFF reader), the QuPath project the fixtures are opened in,
// and the placement of results in the image's hierarchy compared with the files the tool wrote.
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.imageio.ImageReader

/** The registry entry of an app as written on disk (apps/<app>.json). */
Map uaEntry(String app) {
    File f = new File(UiAudit.home, "apps/" + app + ".json")
    if (!f.isFile()) throw new IllegalStateException("no registry entry " + f)
    return (Map) LcJson.parse(f)
}

/** POSIX word splitting of one command line (single quotes, double quotes with backslash escapes, backslash outside quotes). */
List<String> uaShellSplit(String line) {
    List<String> words = []
    StringBuilder cur = new StringBuilder()
    boolean inWord = false
    int i = 0
    while (i < line.length()) {
        char c = line.charAt(i)
        if (c == ('\'' as char)) {
            int end = line.indexOf("'", i + 1)
            if (end < 0) throw new IllegalStateException("unbalanced ' in: " + line)
            cur.append(line.substring(i + 1, end)); inWord = true; i = end + 1
        } else if (c == ('"' as char)) {
            i++
            while (i < line.length() && line.charAt(i) != ('"' as char)) {
                if (line.charAt(i) == ('\\' as char) && i + 1 < line.length()) i++
                cur.append(line.charAt(i)); i++
            }
            if (i >= line.length()) throw new IllegalStateException("unbalanced \" in: " + line)
            inWord = true; i++
        } else if (c == ('\\' as char) && i + 1 < line.length()) {
            cur.append(line.charAt(i + 1)); inWord = true; i += 2
        } else if (Character.isWhitespace(c)) {
            if (inWord) { words << cur.toString(); cur.setLength(0); inWord = false }
            i++
        } else { cur.append(c); inWord = true; i++ }
    }
    if (inWord) words << cur.toString()
    return words
}

/** What the form must send for each parameter of `tool` (the plan's schema) when the case gave `inputs`: only what can be predicted from the case and the schema defaults (name -> value; file paths resolved). */
Map<String, Object> uaExpectedValues(Map tool, Map inputs) {
    Map<String, Object> out = [:]
    ((List<Map>) tool.inputs).each { Map p ->
        String n = p.name
        if (inputs.containsKey(n) && inputs[n] != null) out[n] = (p.type in ["image", "labels", "file", "table", "folder"]) ? uaResolvePath(inputs[n].toString()) : inputs[n]
        else if (p.default != null && !p.nullable && !p.pixel_size_of) out[n] = p.default
    }
    return out
}

/** True when text `actual` of a command line equals the expected value (numbers numerically, booleans as true/false, everything else exactly). */
boolean uaSameValue(Object expected, String actual) {
    if (expected instanceof Boolean) return actual == (expected ? "true" : "false")
    if (expected instanceof Number) { try { return Math.abs((actual as double) - (expected as double)) <= 1e-9 * Math.max(1d, Math.abs(expected as double)) } catch (NumberFormatException e) { return false } }
    return actual == expected.toString()
}

/** Problems with the terminal line a copy put on the clipboard: parsed back, it names the app's own interpreter (from the registry entry on disk), the app, the tool, and every predicted value. */
List<String> uaTerminalProblems(String text, String app, Map tool, Map<String, Object> expected) {
    List<String> bad = []
    List<String> lines = text.split("\n").findAll { !it.startsWith("#") && it.trim() }
    if (lines.size() != 1) return ["the terminal text has " + lines.size() + " command line(s): " + text.take(120)]
    List<String> words = uaShellSplit(lines[0])
    if (words.size() < 6) return ["too few words in '" + lines[0].take(120) + "'"]
    Object python = uaEntry(app).python
    if (words[0] != python) bad << ("interpreter '" + words[0] + "', the registry entry says '" + python + "'")
    if (words.subList(1, 4) != ["-m", "labconstrictor_tools", "run"]) bad << ("words 2-4 are " + words.subList(1, 4))
    if (words[4] != app || words[5] != tool.id) bad << ("app/tool '" + words[4] + " " + words[5] + "', expected '" + app + " " + tool.id + "'")
    Map<String, String> given = [:]
    words.drop(6).each { String w -> int eq = w.indexOf("="); if (eq < 0) bad << ("argument '" + w + "' has no '='"); else given[w.substring(0, eq)] = w.substring(eq + 1) }
    Set<String> declared = ((List<Map>) tool.inputs).collect { it.name as String } as Set<String>
    given.keySet().findAll { !declared.contains(it) }.each { bad << ("argument '" + it + "' is not a parameter of the tool") }
    expected.each { String n, Object v -> if (!given.containsKey(n)) bad << ("'" + n + "' is missing from the command") else if (!uaSameValue(v, given[n])) bad << (n + "=" + given[n] + " but the case says " + v) }
    return bad
}

/** Problems with the Python snippet: the run_once call names the app and tool, and every predicted value follows its quoted name. */
List<String> uaPythonProblems(String text, String app, Map tool, Map<String, Object> expected) {
    List<String> bad = []
    if (!text.contains("client.run_once('" + app + "', '" + tool.id + "', ")) bad << ("no client.run_once('" + app + "', '" + tool.id + "', ...) in the snippet")
    expected.each { String n, Object v ->
        def m = (text =~ /(?m)^\s*'${java.util.regex.Pattern.quote(n)}': (.*?),\s*$/)
        if (!m.find()) { bad << ("'" + n + "' is not in the snippet"); return }
        String token = m.group(1)
        boolean ok = v instanceof Boolean ? token == (v ? "True" : "False") : v instanceof Number ? uaSameValue(v, token) : token == "'" + v.toString().replace("\\", "\\\\").replace("'", "\\'") + "'" || token == "\"" + v.toString() + "\""
        if (!ok) bad << (n + ": " + token + " but the case says " + v)
    }
    return bad
}

/** Width, height and pixel size (um per pixel, or null) of a TIFF read with javax.imageio only (the TIFF resolution tags): the oracle for what the form shows. */
Map uaImageIoInfo(String path) {
    File f = new File(path)
    def stream = ImageIO.createImageInputStream(f)
    try {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(stream)
        if (!readers.hasNext()) throw new IllegalStateException("javax.imageio has no reader for " + path)
        ImageReader reader = readers.next()
        reader.setInput(stream)
        def meta = reader.getImageMetadata(0)
        Double um = null
        def root = meta.metadataFormatNames.contains("javax_imageio_tiff_image_1.0") ? meta.getAsTree("javax_imageio_tiff_image_1.0") : null      // other formats (PNG): size only, no pixel size
        Closure field = { int tag ->
            if (root == null) return null
            def ifd = root.firstChild
            for (def e = ifd.firstChild; e != null; e = e.nextSibling) if (e.attributes.getNamedItem("number")?.nodeValue == String.valueOf(tag)) return e.firstChild.firstChild
            return null
        }
        def xres = field(282), unit = field(296)
        if (xres != null && unit != null) {
            String[] fraction = xres.attributes.getNamedItem("value").nodeValue.split("/")      // a TIFFRational: "numerator/denominator"
            double perUnit = Double.parseDouble(fraction[0]) / (fraction.length > 1 ? Double.parseDouble(fraction[1]) : 1d)
            int u = Integer.parseInt(unit.attributes.getNamedItem("value").nodeValue)      // a TIFFShort: 1 none, 2 inch, 3 centimetre
            if (perUnit > 0 && u == 3) um = 10000d / perUnit
            else if (perUnit > 0 && u == 2) um = 25400d / perUnit
        }
        return [width: reader.getWidth(0), height: reader.getHeight(0), pixelSizeUm: um]
    } finally { stream.close() }
}

/** Creates a QuPath project that holds the files as images and opens the first one; returns [file path -> image name in the project]. */
Map<String, String> uaOpenFixtures(List<String> paths) {
    File dir = Files.createTempDirectory("lc_ui_audit_project").toFile()
    return (Map<String, String>) UiAudit.fx {
        qupath.lib.gui.prefs.PathPrefs.imageTypeSettingProperty().set(qupath.lib.gui.prefs.PathPrefs.ImageTypeSetting.AUTO_ESTIMATE)
        def project = qupath.lib.projects.Projects.createProject(dir, BufferedImage)
        Map<String, String> names = [:]
        def first = null
        paths.each { String path ->
            def server = qupath.lib.images.servers.ImageServers.buildServer(path)
            def entry = project.addImage(server.getBuilder())
            entry.setImageName(new File(path).name)
            names[path] = new File(path).name
            if (first == null) first = entry
        }
        project.syncChanges()
        QuPathGUI.getInstance().setProject(project)
        if (first != null) QuPathGUI.getInstance().openImageEntry(first)
        names
    }
}

/** Opens the fixtures a case names (its image and labels inputs that are files), the first one as the open image, before the dialog is built. Returns [path -> project image name]. */
Map<String, String> uaOpenCaseImages(Map tool, Map inputs) {
    List<String> paths = ((List<Map>) tool.inputs).findAll { it.type in ["image", "labels"] && inputs[it.name] != null }.collect { uaResolvePath(inputs[it.name].toString()) }.unique()
    paths = paths.findAll { new File(it).isFile() }
    if (paths.isEmpty()) return [:]
    Map<String, String> names = uaOpenFixtures(paths)
    Thread.sleep(2500)      // the viewer opens the image on its own thread
    return names
}

/** Placement of results on the open image, from its hierarchy and the files the tool wrote: points at the CSV's pixel centres, outlines at the GeoJSON's, all inside the fixture's size read by javax.imageio. Returns problems. */
List<String> uaPlacementProblems(Object dlg, String app, Map outcome, String openPath) {
    List<String> bad = []
    def data = UiAudit.fx { QuPathGUI.getInstance()?.imageData }
    if (data == null) return ["no image is open in QuPath, so the results cannot have been placed on it"]
    Map info = uaImageIoInfo(openPath)
    List<Map> results = (List<Map>) (((Map) outcome.outputs)?.results ?: [])
    results.findAll { it.type in ["points", "shapes"] }.each { Map r ->
        String prefix = app + ":" + r.name
        List objs = (List) UiAudit.fx { new ArrayList(data.hierarchy.annotationObjects).findAll { it.name == prefix || it.name?.startsWith(prefix + " ") } }
        if (r.type == "points") {
            List<String> lines = new File(r.path as String).readLines("UTF-8")
            List<String> head = lines[0].split(",") as List
            int yi = head.indexOf("y"), xi = head.indexOf("x")
            List<List<Double>> want = lines.drop(1).findAll { it.trim() }.collect { String l -> def c = l.split(",", -1); [(c[xi] as double) + 0.5d, (c[yi] as double) + 0.5d] }
            if (objs.size() != 1) { bad << (prefix + ": " + objs.size() + " annotation(s) in the image, expected 1"); return }
            List pts = (List) UiAudit.fx { objs[0].ROI.allPoints.collect { [it.x, it.y] } }
            if (pts.size() != want.size()) bad << (prefix + ": " + pts.size() + " point(s) in the image, the CSV has " + want.size())
            else {
                Closure byXy = { a, b -> a[0] <=> b[0] ?: a[1] <=> b[1] }
                List sp = pts.sort(false, byXy), sw = want.sort(false, byXy)
                if (!sp.indices.every { Math.abs(sp[it][0] - sw[it][0]) < 1e-6 && Math.abs(sp[it][1] - sw[it][1]) < 1e-6 }) bad << (prefix + ": the points in the image are not the CSV's pixel centres")
            }
            if (!want.every { it[0] >= 0 && it[0] <= (info.width as double) && it[1] >= 0 && it[1] <= (info.height as double) }) bad << (prefix + ": a point lies outside the " + info.width + " x " + info.height + " image")
        } else {
            Map collection = (Map) LcJson.parse(new File(r.path as String))
            int features = ((List) collection.features).size()
            if (objs.size() != Math.min(features, LcConst.MAX_SHAPES)) bad << (prefix + ": " + objs.size() + " outline(s) in the image, the GeoJSON has " + features)
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE
            collection.features.each { f -> (f.geometry.type == "Polygon" ? [f.geometry.coordinates] : f.geometry.coordinates).each { part -> part[0].each { c -> minX = Math.min(minX, (c[0] as double) + 0.5d); minY = Math.min(minY, (c[1] as double) + 0.5d) } } }
            double gotX = (double) UiAudit.fx { objs.collect { it.ROI.boundsX }.min() }, gotY = (double) UiAudit.fx { objs.collect { it.ROI.boundsY }.min() }
            if (!objs.isEmpty() && (Math.abs(gotX - minX) > 1e-6 || Math.abs(gotY - minY) > 1e-6)) bad << (prefix + ": the outlines start at (" + gotX + ", " + gotY + "), the GeoJSON says (" + minX + ", " + minY + ")")
        }
    }
    return bad
}
