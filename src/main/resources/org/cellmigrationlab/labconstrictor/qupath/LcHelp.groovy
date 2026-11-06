// ---------------------------------------------------------------------------------------------------- input help marker (Tools #147)
/** The ⓘ after the label of each input that has a description (Tools docs/HOST_FEATURES.md, row "Input help marker"). The description is the input's help:
 *  hovering the ⓘ (or the label) shows it as a tooltip, a click on the ⓘ keeps it open (until the next click elsewhere or Escape), and moving into the input with the
 *  keyboard (Tab) shows it under the input. An input without a description (none, or only spaces) gets no ⓘ. */
class LcHelp {
    static final String MARKER = "ⓘ"                 // ⓘ, CIRCLED LATIN SMALL LETTER I
    static final String STYLE_CLASS = "lc-help-marker"     // how the tests find the markers

    /** Marks `label` (the input's label in the form) when `description` has text; `input` is the node placed in the form for the input. Returns the ⓘ, or null. */
    static Label mark(Label label, Node input, Object description) {
        String text = description == null ? "" : description.toString().trim()
        if (!text) return null
        label.tooltip = tooltip(text)
        def marker = new Label(MARKER)
        marker.styleClass.add(STYLE_CLASS)
        marker.cursor = javafx.scene.Cursor.HAND
        marker.accessibleText = "Help: " + label.text
        marker.accessibleHelp = text
        marker.tooltip = tooltip(text)
        def kept = tooltip(text)
        kept.autoHide = true                               // a click anywhere else closes it; Escape too (hideOnEscape is on by default)
        marker.onMouseClicked = { e ->
            def bounds = marker.localToScreen(marker.boundsInLocal)
            if (bounds != null) kept.show(marker, bounds.minX, bounds.maxY + 2)
            e.consume()
        }
        marker.properties.put(STYLE_CLASS, kept)            // the popup of the click, for the tests
        label.graphic = marker
        label.contentDisplay = ContentDisplay.RIGHT          // label text, then the ⓘ
        showOnKeyboardFocus(input, text)
        return marker
    }

    /** A tooltip in the form's style: wrapped at the form's tooltip width. */
    static Tooltip tooltip(String text) {
        def tip = new Tooltip(text)
        tip.wrapText = true
        tip.maxWidth = LcConst.TOOLTIP_WIDTH
        return tip
    }

    /** Every control inside `input` that the keyboard can reach shows the help under itself while it has keyboard focus (focusVisible: focus that came from the
     *  keyboard, not from a mouse click). */
    static void showOnKeyboardFocus(Node input, String text) {
        def tip = tooltip(text)
        focusable(input).each { Node part ->
            part.focusVisibleProperty().addListener({ o, was, now ->
                if (now) {
                    def bounds = part.localToScreen(part.boundsInLocal)
                    if (bounds != null) tip.show(part, bounds.minX, bounds.maxY + 2)
                } else {
                    tip.hide()
                }
            } as javafx.beans.value.ChangeListener)
        }
        input.properties.put(STYLE_CLASS, tip)              // the tooltip of the keyboard focus, for the tests
    }

    /** The controls in `node` (itself included) that take keyboard focus. */
    static List<Node> focusable(Node node) {
        if (node instanceof Control) return node.focusTraversable ? [node] : []
        if (node instanceof javafx.scene.Parent) return node.childrenUnmodifiable.collectMany { focusable(it) }
        return []
    }
}
