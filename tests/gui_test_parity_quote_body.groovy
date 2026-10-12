// ================================================================== GUI test body: quoting in Copy as command (appended to the tool script by tests/run_gui_test.sh)
// Checks LcDialog.shellQuote against tests/quote_vectors.json (the file of labconstrictor-tools, tests/quote_vectors.json, copied here; `command.quote` is the reference), against
// independent readers of the quoted text (the rules of the C runtime for Windows, a POSIX shell word reader), and through the real terminal command of the dialog with the
// operating system set to Windows. Needs the `interactions` example app in LC_HOME (lchome_w3; tool "Show a shape" has a text parameter).
import java.util.concurrent.CountDownLatch

def results = [:]
def fx = { Closure c ->
    if (javafx.application.Platform.isFxApplicationThread()) return c()
    def latch = new CountDownLatch(1); def box = [null, null]
    javafx.application.Platform.runLater { try { box[0] = c() } catch (Throwable t) { box[1] = t } finally { latch.countDown() } }
    latch.await()
    if (box[1] != null) throw box[1]
    return box[0]
}
def expect = { String name, boolean ok, Object detail = "" ->
    results[name] = ok
    println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : " -> " + detail))
}

/** The words a Windows program receives from `line`: the rules of the C runtime (CommandLineToArgvW), written independently of LcDialog.quoteWindows. */
def parseWindows = { String line ->
    def words = [], word = new StringBuilder()
    boolean inQuotes = false, inWord = false
    int i = 0
    while (i < line.length()) {
        char c = line.charAt(i)
        if (c == ('\\' as char)) {
            int n = 0
            while (i < line.length() && line.charAt(i) == ('\\' as char)) { n++; i++ }
            if (i < line.length() && line.charAt(i) == ('"' as char)) { word.append('\\' * (n.intdiv(2))); if (n % 2 == 1) { word.append('"'); i++ } }
            else word.append('\\' * n)
            inWord = true
            continue
        }
        if (c == ('"' as char)) { inQuotes = !inQuotes; inWord = true }
        else if ((c == (' ' as char) || c == ('\t' as char)) && !inQuotes) { if (inWord) { words << word.toString(); word.setLength(0); inWord = false } }
        else { word.append(c); inWord = true }
        i++
    }
    if (inWord) words << word.toString()
    return words
}

/** The words a POSIX shell makes of `line` when it holds only single-quoted, double-quoted and bare text (no variables, no escapes outside quotes). */
def parsePosix = { String line ->
    def words = [], word = new StringBuilder()
    boolean inWord = false
    int i = 0
    while (i < line.length()) {
        char c = line.charAt(i)
        if (c == ('\'' as char)) { int e = line.indexOf("'", i + 1); word.append(line.substring(i + 1, e)); i = e + 1; inWord = true; continue }
        if (c == ('"' as char)) { int e = line.indexOf('"', i + 1); word.append(line.substring(i + 1, e)); i = e + 1; inWord = true; continue }
        if (c == (' ' as char)) { if (inWord) { words << word.toString(); word.setLength(0); inWord = false } }
        else { word.append(c); inWord = true }
        i++
    }
    if (inWord) words << word.toString()
    return words
}

Thread.start("lc-gui-test") {
    try {
        def vectors = LcJson.parse(new File("tests/quote_vectors.json"))      // relative to the repository folder, where tests/run_gui_test.sh starts QuPath
        expect("the_vector_file_is_read", vectors instanceof List && vectors.size() >= 30, vectors?.size())

        // ---- every vector: the exact text for both shells
        def wrongPosix = vectors.findAll { LcDialog.shellQuote(it.text as String, false) != it.posix }.collect { it.label }
        def wrongWindows = vectors.findAll { LcDialog.shellQuote(it.text as String, true) != it.windows }.collect { it.label }
        expect("every_vector_quotes_as_posix_expects", wrongPosix.isEmpty(), wrongPosix)
        expect("every_vector_quotes_as_windows_expects", wrongWindows.isEmpty(), wrongWindows)

        // ---- the hard Windows cases named one by one (a failure says which)
        def win = { String text -> LcDialog.shellQuote(text, true) }
        expect("windows_a_trailing_backslash_after_a_space_is_doubled", win('C:\\My Data\\') == '"C:\\My Data\\\\"', win('C:\\My Data\\'))
        expect("windows_a_quote_after_a_backslash_gets_three", win('x y\\"z') == '"x y\\\\\\"z"', win('x y\\"z'))
        expect("windows_a_quote_is_escaped", win('say "hi"') == '"say \\"hi\\""', win('say "hi"'))
        expect("windows_plain_backslash_path_stays_bare", win('C:\\dir\\') == 'C:\\dir\\' && win('a\\b') == 'a\\b', win('C:\\dir\\'))
        expect("windows_empty_text_is_two_quotes", win("") == '""', win(""))

        // ---- independent readers: what a program (Windows) or a shell (POSIX) makes of the quoted text is the text
        def badWindows = vectors.findAll { parseWindows("prog " + LcDialog.shellQuote(it.text as String, true) + " next") != ["prog", it.text, "next"] }.collect { it.label }
        def badPosix = vectors.findAll { parsePosix("prog " + LcDialog.shellQuote(it.text as String, false) + " next") != ["prog", it.text, "next"] }.collect { it.label }
        expect("a_windows_program_reads_every_vector_back", badWindows.isEmpty(), badWindows)
        expect("a_posix_shell_reads_every_vector_back", badPosix.isEmpty(), badPosix)
        // the reader itself is checked: it must tell the wrong quoting of a trailing backslash from the right one
        expect("the_windows_reader_catches_the_old_quoting", parseWindows('prog "C:\\My Data\\" next') != ["prog", 'C:\\My Data\\', "next"])

        // ---- through the dialog: the terminal command on Windows quotes a value with spaces and a trailing backslash so that the program receives it as typed
        def dlg = fx { lcShow() }
        Thread.sleep(800)
        fx { dlg.appBox.value = "interactions" }; fx { dlg.toolBox.value = "Show a shape" }; Thread.sleep(800)
        def typed = 'C:\\My Data\\say "hi"\\'
        fx { dlg.checks["shape"].selected = true; dlg.setters["shape"](typed) }      // an optional text: left out of the command until its "set" box is ticked
        def realOs = System.getProperty("os.name")
        def line
        try { System.setProperty("os.name", "Windows 11"); line = fx { dlg.copyAsCommand("terminal") } } finally { System.setProperty("os.name", realOs) }
        def words = parseWindows(line.readLines().findAll { !it.startsWith("#") }.join(" "))
        expect("windows_terminal_command_gives_the_program_the_typed_value", words.contains("shape=" + typed), [line, words])
        def posixLine = fx { dlg.copyAsCommand("terminal") }
        expect("posix_terminal_command_gives_the_shell_the_typed_value", parsePosix(posixLine.readLines().findAll { !it.startsWith("#") }.join(" ")).contains("shape=" + typed), posixLine)
    } catch (Throwable t) { t.printStackTrace() }
    finally { println "RESULTS " + results; Thread.sleep(500); System.exit(0) }
}
