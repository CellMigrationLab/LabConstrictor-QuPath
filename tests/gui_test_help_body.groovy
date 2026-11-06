
// ================================================================== GUI test body: the input help marker (Tools #147), appended to the tool script by tests/run_gui_test.sh
// An ⓘ after the label of each input with a description: hover shows it, a click keeps it open, keyboard focus (Tab) of the input shows it under the input.
// Apps: interactions (every input described) and runs (Delayed points: no input described).
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import java.util.concurrent.CountDownLatch

def results = [:]
def fx = { Closure c ->
    if (Platform.isFxApplicationThread()) return c()
    def latch = new CountDownLatch(1); def box = [null, null]
    Platform.runLater { try { box[0] = c() } catch (Throwable t) { box[1] = t } finally { latch.countDown() } }
    latch.await()
    if (box[1] != null) throw box[1]
    return box[0]
}
def expect = { String name, boolean ok, Object detail = "" ->
    results[name] = ok
    println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : " -> " + detail))
}
def pick = { dlg, app, tool -> fx { dlg.appBox.value = app }; fx { dlg.toolBox.value = tool } }
def click = { Node node ->
    fx {
        def b = node.localToScene(node.boundsInLocal), s = node.localToScreen(node.boundsInLocal)
        def x = b.width / 2, y = b.height / 2
        javafx.event.Event.fireEvent(node, new MouseEvent(MouseEvent.MOUSE_CLICKED, x, y, s.minX + x, s.minY + y, MouseButton.PRIMARY, 1,
            false, false, false, false, true, false, false, true, false, true, null))
        null
    }
}
def tab = { Node from -> fx { javafx.event.Event.fireEvent(from, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, false, false, false, false)); null } }

Thread.start("lc-gui-test") {
    try {
        def dlg = fx { lcShow() }
        Thread.sleep(800)
        pick(dlg, "interactions", "Find bright spots"); Thread.sleep(1500)
        def tool = fx { dlg.currentTool }
        def described = tool.inputs.findAll { it.description?.toString()?.trim() }
        expect("every_described_input_has_a_marker", fx { described.every { dlg.helpMarkers[it.name]?.text == LcHelp.MARKER } },
            fx { dlg.helpMarkers.keySet() } + " vs " + described*.name)
        expect("hover_text_is_the_description", fx { described.every { dlg.helpMarkers[it.name].tooltip?.text == it.description } },
            fx { described.collect { [it.name, dlg.helpMarkers[it.name]?.tooltip?.text] } })
        def threshold = fx { dlg.helpMarkers["threshold"] }
        expect("marker_sits_after_the_label_text", fx { dlg.stage.scene.root.lookupAll(".label").any { it.graphic.is(threshold) && it.contentDisplay == ContentDisplay.RIGHT } })
        expect("label_text_unchanged", fx { dlg.stage.scene.root.lookupAll(".label").any { it.graphic.is(threshold) && it.text == "Threshold" } })
        expect("accessible_text_says_help", fx { threshold.accessibleText == "Help: Threshold" && threshold.accessibleHelp == described.find { it.name == "threshold" }.description },
            fx { threshold.accessibleText })

        // a click keeps the help open: it stays after the mouse has left, until a click elsewhere or Escape
        def kept = fx { threshold.properties[LcHelp.STYLE_CLASS] }
        click(threshold); Thread.sleep(600)
        expect("click_keeps_the_help_open", fx { kept.showing && kept.text == described.find { it.name == "threshold" }.description }, fx { kept.showing })
        Thread.sleep(1500)
        expect("help_stays_open", fx { kept.showing })
        expect("kept_help_closes_on_a_click_elsewhere", fx { kept.autoHide && kept.hideOnEscape })
        fx { kept.hide() }

        // keyboard focus (Tab) of an input shows its help under it; focus given by a mouse click (programmatic here) does not
        def inputOf = { Node n -> fx { dlg.wrappers.find { name, w -> w.is(n) || (w instanceof javafx.scene.Parent && LcHelp.focusable(w).any { it.is(n) }) }?.key } }
        def firstControl = fx { LcHelp.focusable(dlg.wrappers["image"])[0] }
        fx { firstControl.requestFocus() }; Thread.sleep(300)
        expect("mouse_focus_shows_nothing", fx { !dlg.wrappers["image"].properties[LcHelp.STYLE_CLASS].showing && !firstControl.focusVisible })
        def seen = []
        Node at = firstControl
        for (int i = 0; i < 6; i++) {
            tab(at); Thread.sleep(400)
            at = fx { dlg.stage.scene.focusOwner }
            def name = inputOf(at)
            if (name == null) continue
            def tip = fx { dlg.wrappers[name].properties[LcHelp.STYLE_CLASS] }
            def text = described.find { it.name == name }?.description
            seen << [name, fx { at.focusVisible }, fx { tip?.showing }]
            // the help, not JavaFX's focusVisible: a Spinner clears that when it hands its focus to its text field and takes it back
            expect("tab_into_" + name + "_shows_its_help", fx { tip.showing && tip.text == text }, seen.last() + [at.class.simpleName])
        }
        expect("tab_reached_at_least_two_inputs", seen*.get(0).unique().size() >= 2, seen)
        fx { dlg.wrappers.values().each { w -> w.properties[LcHelp.STYLE_CLASS]?.hide() } }

        // a folded group and a ChoicesFrom input are marked too
        pick(dlg, "interactions", "Show a shape"); Thread.sleep(1500)
        expect("folded_group_and_choices_inputs_marked", fx { ["shape", "size"].every { dlg.helpMarkers[it]?.text == LcHelp.MARKER } }, fx { dlg.helpMarkers.keySet() })
        expect("old_form_markers_forgotten", fx { !dlg.helpMarkers.containsKey("threshold") })

        // an input without a description has no marker
        pick(dlg, "runs", "Delayed points"); Thread.sleep(1500)
        expect("undescribed_inputs_have_none", fx { dlg.helpMarkers.isEmpty() && dlg.stage.scene.root.lookupAll("." + LcHelp.STYLE_CLASS).isEmpty() }, fx { dlg.helpMarkers.keySet() })
        expect("blank_description_counts_as_none", fx { LcHelp.mark(new Label("x"), new Label(), "   ") == null && LcHelp.mark(new Label("x"), new Label(), null) == null })
    } catch (Throwable t) { t.printStackTrace(); results["EXCEPTION"] = false }
    finally { println "RESULTS " + results; Thread.sleep(500); System.exit(results && results.every { it.value } ? 0 : 1) }
}
