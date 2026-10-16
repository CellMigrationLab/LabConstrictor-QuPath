// ================================================================== UI audit, part 7: seeded failures (NEGATIVE CONTROLS; only with LC_UI_SEED, see docs/UI_AUDIT.md and tests/run_ui_audit_negative.sh)
// Each seed damages the window or the result in one way the audit must catch, so that a green audit is known to be able to go red:
//   extra_control    adds a button that no expectation accounts for        -> ui_qupath_controls_all_known FAIL names it (unknown control)
//   removed_control  removes the Rescan apps button                        -> ui_qupath_controls_all_known FAIL (expected control missing) and ui_qupath_panel_rescan_apps FAIL
//   blank_result     empties the results window and the message after a run -> ui_qupath_run_<tool> FAIL (nothing shown)
//   (a tool whose run fails: not a seed of the window but a case file, tests/ui_audit_apps/runs_seeded, whose case expects COMPLETE from a tool that raises an error -> ui_qupath_run_fails FAIL)
import javafx.scene.control.*
import javafx.scene.layout.Pane

/** Called right after the dialog opened: applies the seed that damages the window. */
void uaSeedAfterOpen(Object dlg) {
    if (UiAudit.seed == "extra_control") {
        UiAudit.fx { dlg.stage.scene.root.children.add(new Button("Seeded extra control")); null }
        println "NOTE ui_qupath_seed applied: extra_control (a Button 'Seeded extra control' was added to the window)"
    } else if (UiAudit.seed == "removed_control") {
        UiAudit.fx {
            Button b = uaAllNodes(dlg.stage.scene.root).find { it instanceof Button && it.text == "Rescan apps" }
            ((Pane) b.parent).children.remove(b)
            null
        }
        println "NOTE ui_qupath_seed applied: removed_control (the Rescan apps button was removed from the window)"
    }
}

/** Called after a run, before the results are asserted: applies the seed that blanks them. */
void uaSeedBeforeAssert(Object dlg) {
    if (UiAudit.seed != "blank_result") return
    UiAudit.fx {
        if (dlg.lastResultStage != null) dlg.lastResultStage.scene.root = new ScrollPane(new javafx.scene.layout.VBox())
        dlg.messageLabel.text = ""
        null
    }
    println "NOTE ui_qupath_seed applied: blank_result (the results window and the message were emptied)"
}
