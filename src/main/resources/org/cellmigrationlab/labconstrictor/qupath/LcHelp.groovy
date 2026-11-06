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

    static final String TAB_PRESSED = "lc-help-tab-pressed"  // scene property: System.nanoTime() of the last Tab / Shift+Tab pressed in the window
    static final long KEYBOARD_MOVE_NS = 500_000_000L         // focus gained this soon after a Tab came from the keyboard

    /** Every control inside `input` that the keyboard can reach shows the help under itself while it has focus that came from the keyboard (Tab, Shift+Tab),
     *  not from a mouse click. JavaFX's own focusVisible is not enough: a Spinner hands its focus to its text field and takes it back, which clears focusVisible,
     *  so the Tab itself is noted on the window (a key filter on the scene, added once) and a focus gained just after it counts as keyboard focus too. */
    static void showOnKeyboardFocus(Node input, String text) {
        def tip = tooltip(text)
        focusable(input).each { Node part ->
            if (part.scene != null) watchTab(part.scene)
            part.sceneProperty().addListener({ o, was, now -> if (now != null) watchTab(now) } as javafx.beans.value.ChangeListener)
            part.focusedProperty().addListener({ o, was, now ->
                if (now && (part.focusVisible || tabJustPressed(part))) {
                    def bounds = part.localToScreen(part.boundsInLocal)
                    if (bounds != null) tip.show(part, bounds.minX, bounds.maxY + 2)
                } else if (!now) {
                    tip.hide()
                }
            } as javafx.beans.value.ChangeListener)
        }
        input.properties.put(STYLE_CLASS, tip)              // the tooltip of the keyboard focus, for the tests
    }

    /** Notes every Tab pressed in this window (once per scene). A filter, so it runs before the Tab moves the focus. */
    static void watchTab(Scene scene) {
        if (scene.properties.containsKey(TAB_PRESSED)) return
        scene.properties.put(TAB_PRESSED, 0L)
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, { e ->
            if (e.code == javafx.scene.input.KeyCode.TAB) scene.properties.put(TAB_PRESSED, System.nanoTime())
        } as javafx.event.EventHandler)
    }

    static boolean tabJustPressed(Node part) {
        def last = part.scene?.properties?.get(TAB_PRESSED) as Long
        return last && System.nanoTime() - last < KEYBOARD_MOVE_NS
    }

    /** The controls in `node` (itself included) that take keyboard focus. */
    static List<Node> focusable(Node node) {
        if (node instanceof Control) return node.focusTraversable ? [node] : []
        if (node instanceof javafx.scene.Parent) return node.childrenUnmodifiable.collectMany { focusable(it) }
        return []
    }
}
