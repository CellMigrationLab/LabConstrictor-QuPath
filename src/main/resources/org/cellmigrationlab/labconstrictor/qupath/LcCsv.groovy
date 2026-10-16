// ---------------------------------------------------------------------------------------------------- CSV (RFC 4180) for result tables and points (issue #17)
/** A CSV file this host cannot read. The message is the whole sentence the person reads (it names the record and the line); nothing is guessed to make a bad file fit. */
class LcCsvError extends IllegalArgumentException {
    LcCsvError(String sentence) { super(sentence) }
}

/** The CSV reader of the results: one record is one table row or one point, however many lines it spans. The Toolkit writes UTF-8 CSV (docs/PROTOCOL.md): a quoted cell holds commas,
 *  doubled quotes ("" is one quote) and line breaks; CRLF and LF both end a record; the last record needs no line break; a leading byte order mark is dropped. A quote that is not
 *  opened at the start of a cell, a cell with text after its closing quote, or a quote that is never closed is an LcCsvError, not a guess. */
class LcCsv {
    static final int QUOTE = 34, COMMA = 44, LINE_FEED = 10, CARRIAGE_RETURN = 13, BYTE_ORDER_MARK = 0xFEFF

    /** The file as records: [rows: the first `keep` records (cells as text), count: all the records of the file]. Every record is read, so a bad one beyond `keep` is still reported. */
    static Map read(String csvPath, int keep) {
        try {
            return new File(csvPath).withReader("UTF-8") { Reader reader -> read(reader, keep) } as Map
        } catch (LcCsvError problem) {
            throw new LcCsvError(csvPath + ": " + problem.message)
        }
    }

    /** The same from any reader (the file name is not part of the sentence). */
    static Map read(Reader reader, int keep) {
        List<List<String>> rows = []
        List<String> row = []
        StringBuilder cell = new StringBuilder()
        long count = 0, line = 1, recordLine = 1
        boolean quoted = false, closed = false, any = false         // inside quotes / just after the closing quote / something of the record was read
        Closure endRecord = {
            row << cell.toString(); cell.setLength(0)
            count++
            if (rows.size() < keep) rows << row
            row = []; closed = false; any = false
        }
        int c = reader.read()
        if (c == BYTE_ORDER_MARK) c = reader.read()
        for (; c != -1; c = reader.read()) {
            if (quoted) {
                if (c == QUOTE) { quoted = false; closed = true } else cell.append((char) c)
            } else if (c != CARRIAGE_RETURN) {          // a carriage return outside quotes is part of CRLF
                any = true
                if (c == LINE_FEED) endRecord()
                else if (c == COMMA) { row << cell.toString(); cell.setLength(0); closed = false }
                else if (closed && c == QUOTE) { cell.append('"'); quoted = true; closed = false }       // a doubled quote inside a quoted cell: one quote
                else if (closed) throw new LcCsvError("record " + (count + 1) + " (line " + recordLine + "): text after the closing quote of a cell")
                else if (c == QUOTE) {
                    if (cell.length() > 0) throw new LcCsvError("record " + (count + 1) + " (line " + recordLine + "): a quote inside a cell that was not opened with one")
                    quoted = true
                } else cell.append((char) c)
            }
            if (c == LINE_FEED) { line++; if (!quoted && !any) recordLine = line }
        }
        if (quoted) throw new LcCsvError("record " + (count + 1) + " (line " + recordLine + "): a quoted cell is never closed")
        if (any) endRecord()
        return [rows: rows, count: count]
    }

    /** The points of a CSV file with the columns `y` and `x` (pixel coordinates; other columns are metadata and may hold any text): [ys: List<Double>, xs: List<Double>], one pair per record.
     *  A missing column, a record with another number of cells than the header, and a coordinate that is not a finite number are an LcCsvError that names the record. */
    static Map points(String csvPath) {
        Map csv = read(csvPath, Integer.MAX_VALUE)
        List<List<String>> rows = csv.rows as List<List<String>>
        if (rows.isEmpty()) throw new LcCsvError(csvPath + ": the points file is empty (it needs a header with the columns y and x)")
        List<String> header = rows[0]
        int yi = header.indexOf("y"), xi = header.indexOf("x")
        if (yi < 0 || xi < 0) throw new LcCsvError(csvPath + ": the points file has no " + (yi < 0 && xi < 0 ? "'y' and 'x' columns" : (yi < 0 ? "'y' column" : "'x' column")) + " (header: " + header.join(",") + ")")
        List<Double> ys = [], xs = []
        rows.drop(1).eachWithIndex { List<String> cells, int index ->
            String where = csvPath + ": record " + (index + 2)
            if (cells.size() != header.size()) throw new LcCsvError(where + " has " + cells.size() + " cell(s), the header has " + header.size())
            ys << coordinate(cells[yi], "y", where)
            xs << coordinate(cells[xi], "x", where)
        }
        return [ys: ys, xs: xs]
    }

    /** One coordinate cell as a finite number, or an LcCsvError that says which cell. */
    private static double coordinate(String text, String column, String where) {
        double value
        try { value = Double.parseDouble(text.trim()) }
        catch (NumberFormatException problem) { throw new LcCsvError(where + ": '" + column + "' is '" + text + "', not a number") }
        if (Double.isNaN(value) || Double.isInfinite(value)) throw new LcCsvError(where + ": '" + column + "' is '" + text + "', not a finite number")
        return value
    }
}
