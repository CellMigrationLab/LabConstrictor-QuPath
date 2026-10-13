// ---------------------------------------------------------------------------------------------------- number fields (SPEC_WRONG_SCIENCE section 6)
/** A typed number this host refuses. The message is the whole sentence the person reads in the status line; for a limit it is the worker's template (HOST_PARITY I06,
 *  conformance messages.number_above_maximum and number_below_minimum): `'L' must be <= 9007199254740991`. Nothing is ever clamped to make it fit: the run does not start and
 *  nothing is sent. */
class LcNumberError extends IllegalArgumentException {
    LcNumberError(String sentence) { super(sentence) }
}

/** What a number box accepts and how its text is read. The TEXT in the box is the value (SPEC 6.2: "a GUI box that cannot hold such a number uses a text-backed widget; it must not
 *  silently shorten a value"): a whole number is read as an exact integer (no double ever holds it), a decimal number is compared with the limits as the exact decimal text, so a number
 *  one unit beyond a limit is refused even where a double could not tell them apart. */
class LcNumbers {
    static final String ABOVE_TEMPLATE = "'{label}' must be <= {maximum}"       // conformance messages.number_above_maximum
    static final String BELOW_TEMPLATE = "'{label}' must be >= {minimum}"       // messages.number_below_minimum
    static final java.util.regex.Pattern INTEGER_TEXT = ~/^[+-]?[0-9]+$/
    static final java.util.regex.Pattern FLOAT_TEXT = ~/^[+-]?([0-9]+([.][0-9]*)?|[.][0-9]+)([eE][+-]?[0-9]+)?$/
    static final java.util.regex.Pattern NOT_FINITE_TEXT = ~/(?i)^[+-]?(nan|inf|infinity)$/
    static final double PLAIN_BELOW = 1e16d             // Python writes a float in plain digits below this (repr(1e15) is 1000000000000000.0, repr(1e16) is 1e+16) ...
    static final double PLAIN_ABOVE = 1e-4d             // ... and above this (repr(0.0001) is 0.0001, repr(0.00001) is 1e-05)

    /** The sentence for a number beyond a limit: `'L' must be <= 9007199254740991` / `'L' must be >= -9007199254740991` (`limit` as the bound is written: a whole number plain, a decimal one the Python way). */
    static String above(String label, Number limit) { return ABOVE_TEMPLATE.replace("{label}", label).replace("{maximum}", limitText(limit)) }

    static String below(String label, Number limit) { return BELOW_TEMPLATE.replace("{label}", label).replace("{minimum}", limitText(limit)) }

    /** A bound as the worker's sentence writes it: a whole number as it is, a decimal number as Python's repr writes it. */
    static String limitText(Number limit) { return (limit instanceof Double || limit instanceof Float) ? pythonFloat(limit as double) : String.valueOf(limit) }

    /** Python's `repr` of a float: `5.0`, `1000000000000000.0`, `1e+16`, `1e-05`, `0.325`. */
    static String pythonFloat(double value) {
        double size = Math.abs(value)
        if (value == 0d || (size >= PLAIN_ABOVE && size < PLAIN_BELOW)) {
            String plain = new BigDecimal(Double.toString(value)).stripTrailingZeros().toPlainString()
            return plain.contains(".") ? plain : plain + ".0"
        }
        BigDecimal exact = new BigDecimal(Double.toString(value)).stripTrailingZeros()
        int exponent = exact.precision() - exact.scale() - 1
        String mantissa = exact.movePointLeft(exponent).stripTrailingZeros().toPlainString()
        return mantissa + "e" + (exponent < 0 ? "-" : "+") + String.format("%02d", Math.abs(exponent))
    }

    /** The text a float box shows for a value: plain digits where that is short, else Java's scientific form (both read back exactly). */
    static String floatText(double value) {
        double size = Math.abs(value)
        return (value == 0d || (size >= PLAIN_ABOVE && size < PLAIN_BELOW)) ? new BigDecimal(Double.toString(value)).stripTrailingZeros().toPlainString() : Double.toString(value)
    }

    /** The text in a whole-number box as the exact integer it says, between `min` and `max`; anything else is a refusal with its sentence. */
    static long integer(String label, String text, long min, long max) {
        String typed = text == null ? "" : text.trim()
        if (typed.isEmpty()) throw new LcNumberError("'" + label + "' is required")
        if (NOT_FINITE_TEXT.matcher(typed).matches()) throw new LcNumberError("'" + label + "' must be a finite number")
        if (!INTEGER_TEXT.matcher(typed).matches())
            throw new LcNumberError("'" + label + "': '" + typed + "' is " + (FLOAT_TEXT.matcher(typed).matches() ? "not a whole number" : "not a number"))
        BigInteger value = new BigInteger(typed.startsWith("+") ? typed.substring(1) : typed)
        if (value > BigInteger.valueOf(max)) throw new LcNumberError(above(label, max))
        if (value < BigInteger.valueOf(min)) throw new LcNumberError(below(label, min))
        return value.longValueExact()
    }

    /** The text in a decimal box as the double it says, between `min` and `max`; the limits are compared with the exact decimal text, never with a rounded double. */
    static double decimal(String label, String text, Number min, Number max) {
        String typed = text == null ? "" : text.trim()
        if (typed.isEmpty()) throw new LcNumberError("'" + label + "' is required")
        if (NOT_FINITE_TEXT.matcher(typed).matches()) throw new LcNumberError("'" + label + "' must be a finite number")
        if (!FLOAT_TEXT.matcher(typed).matches()) throw new LcNumberError("'" + label + "': '" + typed + "' is not a number")
        BigDecimal value
        try { value = new BigDecimal(typed.startsWith("+") ? typed.substring(1) : typed) }
        catch (NumberFormatException e) {      // an exponent too large for BigDecimal itself: beyond every limit, on the side of its sign
            throw new LcNumberError(typed.startsWith("-") ? below(label, min) : above(label, max))
        }
        if (value > new BigDecimal(max.toString())) throw new LcNumberError(above(label, max))
        if (value < new BigDecimal(min.toString())) throw new LcNumberError(below(label, min))
        return value.doubleValue()
    }
}

/** The value factory of a whole-number box. JavaFX's own IntegerSpinnerValueFactory stops at 2^31 - 1; this one holds any whole number between `min` and `max` (up to
 *  +-LcConst.FORM_INTEGER_LIMIT). The converter refuses text outside the range (LcNumberError) instead of putting it at the nearest limit: a typed number is never changed to fit. The arrows
 *  stop at the limits (they are the only thing that moves a value by itself). */
class LcIntegerFactory extends SpinnerValueFactory<Long> {
    final long min
    final long max

    LcIntegerFactory(String label, long min, long max, long initial) {
        this.min = min
        this.max = max
        setConverter(new javafx.util.StringConverter<Long>() {
            String toString(Long value) { return value == null ? "" : value.toString() }
            Long fromString(String text) { return LcNumbers.integer(label, text, min, max) }
        })
        setValue(initial)      // a start value outside the limits (a tool's own default) stays as it is: the run refuses it with its sentence
    }

    void decrement(int steps) { if (steps != 0) setValue(Math.max(min, getValue() - steps)) }      // getValue(): inside this class `value` is the factory's private field, not the number

    void increment(int steps) { if (steps != 0) setValue(Math.min(max, getValue() + steps)) }
}

/** The value factory of a decimal box: the same rules as LcIntegerFactory (the converter refuses text beyond the limits, the arrows stop at them). The limits are the declared ones or
 *  +-LcConst.FLOAT_LIMIT; `min` and `max` are their doubles. */
class LcDoubleFactory extends SpinnerValueFactory<Double> {
    final double min
    final double max
    final double step

    LcDoubleFactory(String label, Number minBound, Number maxBound, double initial, double step) {
        this.min = minBound.doubleValue()
        this.max = maxBound.doubleValue()
        this.step = step
        setConverter(new javafx.util.StringConverter<Double>() {
            String toString(Double value) { return value == null ? "" : LcNumbers.floatText(value) }
            Double fromString(String text) { return LcNumbers.decimal(label, text, minBound, maxBound) }
        })
        setValue(initial)
    }

    private void move(int steps) {
        if (steps == 0) return
        double moved = BigDecimal.valueOf(getValue()).add((BigDecimal.valueOf(step) * BigDecimal.valueOf(steps))).doubleValue()
        setValue(Math.max(min, Math.min(max, moved)))
    }

    void decrement(int steps) { move(-steps) }

    void increment(int steps) { move(steps) }
}
