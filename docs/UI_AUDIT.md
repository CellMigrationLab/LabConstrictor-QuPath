# UI audit of the QuPath extension

A driver that presses every control of the LabConstrictor window in QuPath, opens the form of every tool of an INSTALLED app, runs each tool with the inputs of the app's own `lc_tests/cases.json`, and takes screenshots. It is the QuPath part of section 3 of the Tools repository's `docs/TEST_STRATEGY.md` (section 0 for the one-off run). Linux, Xvfb.

## Run it (one app)

    QUPATH=<QuPath 0.7 folder> LC_HOME=<registry with the installed app> LC_APP=<registry name> LC_APP_TESTS=<its lc_tests> LC_SHOTS=<out dir> \
      [LC_RELEASE_DIR=<folder with the released jar, LabConstrictorTools-all.groovy, SHA256SUMS>] PYTHON=<any python 3.9+> tests/run_ui_audit.sh

`LC_APP_TESTS` is a folder with `cases.json`, `fixtures/` and optionally `no_case.json` (the layout of an app's `lc_tests`). Exit status 1 on any FAIL or NOT_RUN. The script never pipes the output through `tail`.

| Variable | Meaning |
|---|---|
| `LC_EXPECT_QUPATH` | `listed` (default) or `not_listed`: for `not_listed` only `ui_qupath_not_listed_<app>` runs (the app is absent from the chooser; with `LC_EXPECT_SENTENCE` the Tools log must hold that sentence) and a NOTE says no form checks apply |
| `LC_APPS`, `LC_APP_TESTS_MAP` | `a,b`: first `ui_qupath_multi_lists_<app>` and `ui_qupath_multi_switch_<A>_<B>`, then each app is audited in turn; `LC_APP_TESTS_MAP="a=/x/lc_tests,b=/y/lc_tests"` names their cases |
| `LC_RELEASE_DIR` | test the RELEASED extension (checksums verified, jar and script from that folder); `host_version` of the matrix is its tag. Without it the checkout is tested and `host_version` is `git describe` |
| `LC_UI_TOOL_TIMEOUT` | seconds per tool run (default 300); `LC_UI_LAUNCH_TIMEOUT` (default 900) is the outer watchdog of one QuPath launch |
| `LC_UI_ONLY` | only these tools (the others get NOT_RUN rows) |
| `LC_UI_RUN_VIA` | `click` (default) or `call`: see "Bugs found", the Run button |
| `LC_UI_SEED` | negative controls only |

The harness modifies the QuPath installation (README): `tests/run_gui_test.sh` copies the jar to `lib/app/labconstrictor-qupath-0.1.0.jar`, edits `lib/app/QuPath.cfg` and keeps the first copy of it as `QuPath.cfg.before_lc_tests`. To restore: move that file back and delete the jar.

## How it works
* **One QuPath launch per phase and per tool** (`panel`, `panel_run`, one `tool` launch per tool) on a COPY of `lc_tests` (a tool that writes into the fixtures cannot spoil the next launch), each under `timeout`. A launch that hangs or crashes becomes FAIL rows for every id of its phase, and the next launch starts. A run that exceeds `LC_UI_TOOL_TIMEOUT` is cancelled, the worker killed and the row is FAIL.
* **Bookkeeping outside QuPath** (`tests/ui_audit_matrix.py`, standard library only): `plan` asks the app's own interpreter for the schema (`labconstrictor_tools list --json`, independent of the window), chooses the case of each tool and writes `expected_qupath_<app>.json` BEFORE anything runs; `finish` turns every id without a row into NOT_RUN (red).
* **Matrix** `<LC_SHOTS>/matrix_ui_qupath_<app>.json` (schema of Tools `tests/oneoff_2026-10/matrix.py` plus `host_version`): `{host, app, family: "ui", host_version, started, finished|null, expected, rows[{id, family, host, app, tool, control, status, detail, seconds, screenshot, ts}]}`, rewritten atomically (temp file, move) after every row. Statuses PASS, FAIL, NOT_RUN (red), HUMAN (for a person, never green), NOTE.
* **Cases.** Per tool the first case that expects COMPLETE (not skipped, not a cancel case, never a `stress_*` tool). For an ordered app (`LC_UI_ORDERED`, default GuessTheCondition) every case before it is replayed through the same form first (cases that expect FAILED too, since they may change the state, e.g. the last guess of the game; their expected outcome is asserted). Tools in `no_case.json` get form checks only: HUMAN rows say so.
* **File inputs become QuPath inputs** like this: the fixture image of the case is added to a new QuPath project and opened (the first one is "Current image: <name>", the others "Project: <name>"); the form's image chooser is set to that entry. A case input that is not an image file is typed into the field. Placement of results is asserted from the hierarchy of the open image (annotation `<app>:<output>`: points at the CSV's pixel centres, outlines at the GeoJSON's start, inside the fixture's size read by `javax.imageio`), and calibration shown by a pixel-size field against the TIFF resolution tags read by `javax.imageio`, not by QuPath's own reader.
* **Real events.** Buttons, the check box, the Copy menu and Details are activated with real mouse clicks (`javafx.scene.robot.Robot`) after scrolling them into view; `LC_UI_CLICK=fire` uses `fire()` instead. Typing into form fields sets the text and moves the focus on. Widget state is set through the properties where a robot is not practical (spinners, sliders, combo boxes), and read back through the getters the run uses.
* **Screenshots** (`<LC_SHOTS>/qupath/<app>/<tool>_form.png`, `_after_run.png`, `_result.png`) are EVIDENCE FOR A PERSON: `ui_qupath_screens_<tool>` only asserts that the files exist, are fresh, plausibly sized and not one colour.

## Control table
The window is scanned (every showing stage) and the controls found are compared in BOTH directions with the expectation: the fixed panel, one row of controls per declared parameter (from the schema), the folded sections, the Copy menu entries, and per other window (results, Details, QuPath's own dialogs) what it must hold. A control nobody expects is UNKNOWN, an expected one not found is MISSING, an unexpected or missing STAGE is a problem too, finding fewer than 12 controls fails; all of it is reported by `ui_qupath_controls_all_known`.

| Control | Kind | Expected effect |
|---|---|---|
| `app_chooser` | `ComboBox` | lists every registered app that the host lists; choosing one fills the tool chooser with exactly its tools |
| `tool_chooser` | `ComboBox` | lists the tools of the app; choosing one builds its form and shows its description |
| `run` | `Button:Run` | reads the form and starts the run (Run off, Cancel on); a missing required input is refused with its sentence and nothing starts |
| `cancel` | `Button:Cancel` | disabled while nothing runs, and a click then changes nothing; during a run it asks the worker to stop |
| `details` | `Button:Details...` | disabled until a run ended; then opens the read-only window with the report and the worker output |
| `rescan_apps` | `Button:Rescan apps` | reads the registry again, keeps the chosen app, says 'Found N app(s)' |
| `restart_worker` | `Button:Restart worker` | ends the kept worker and says 'worker stopped' |
| `keep_worker` | `CheckBox:Keep the worker running between runs (faster repeat runs)` | ticked: the worker stays after a run; unticked: it is closed when the run ends |
| `copy_as_command` | `MenuButton:Copy as command` | opens a menu with exactly the entries copy_terminal_command and copy_python_snippet |
| `copy_terminal_command` | `MenuItem:Terminal command` | puts the terminal line of the current form on the clipboard and says so |
| `copy_python_snippet` | `MenuItem:Python snippet` | puts the Python snippet of the current form on the clipboard and says so |
| `form_scroll` | `ScrollPane` | holds the generated form (no effect of its own) |
| `progress` | `ProgressBar` | idle at 0; indeterminate while a run is going |
| `group_section` | `TitledPane:<group>` | a Collapsed group of parameters: expanding shows its controls |
| `advanced_settings` | `TitledPane:Advanced settings` | the advanced parameters, folded at first: expanding shows them |
| `param_boolean` | `CheckBox` | toggles the yes/no value the run sends |
| `param_choice` | `ComboBox` | choosing an entry sets the declared choice at that index |
| `param_radio` | `RadioButton:<choice>` | selecting it sets the (hidden) choice to its text; the combo box behind it is the param_choice of the row |
| `param_integer` | `Spinner` | a typed or stepped whole number is what the run sends |
| `param_float` | `Spinner` | a typed or stepped decimal number is what the run sends |
| `param_slider` | `Slider` | moving it sets the spinner beside it |
| `param_text` | `TextField` | the text is the value; also the path field of a file, table or folder, the file field of an image, and the pixel-size field |
| `param_choices_dropdown` | `ComboBox` | a ChoicesFrom dropdown that replaces the text field when the source tool answers; choosing sets the text |
| `param_browse` | `Button:...` | opens the native file or folder chooser and puts the choice into the field beside it (HUMAN: a native dialog cannot be driven under Xvfb; presence, enabled state and an action are checked) |
| `param_image_source` | `ComboBox` | the image or labels source: (none), the open image, project images, File... |
| `param_image_area` | `ComboBox` | Image area: enabled only for the image open in QuPath; for a file or a project image it stays 'whole image' and is disabled |
| `param_image_channel` | `ComboBox` | PickChannel: the channels of an image that has more than one; the run sends the chosen index |
| `param_selection` | `CheckBox:use the selection` | RegionOf: use the selected annotations; disables the source and file field while ticked |
| `param_set` | `CheckBox:set` | the 'set' box of an optional parameter: ticked enables its control, unticked sends nothing |
| `param_use_calibration` | `Button:Use the image's calibration` | drops a typed pixel size and takes the image's own calibration again |
| `result_scroll` | `ScrollPane` | holds the results of a run |
| `result_table` | `TableView` | the rows of a table or points result |
| `result_open_in_qupath` | `Button:Open in QuPath` | opens the result image in the QuPath viewer |
| `details_text` | `TextArea` | the read-only report of the last run |
| `alert_ok` | `Button:OK` | closes the 'nothing found' dialog |

## Check ids
`ui_qupath_controls_all_known`; `ui_qupath_panel_<app_chooser|tool_chooser|rescan_apps|cancel|copy_as_command|copy_terminal_command|copy_python_snippet|run|details|restart_worker|keep_worker>`; per tool `ui_qupath_form_<tool>_opens` (one widget of the declared kind per parameter, geometry inside the screen and the scroll pane), `_controls` (every control activated and read back), `ui_qupath_run_<tool>`, `ui_qupath_screens_<tool>`; HUMAN rows `ui_qupath_form_<tool>_browse` (a native file chooser cannot be driven under Xvfb) and `ui_qupath_run_<tool>_unverified` (case expectations the GUI cannot see); `ui_qupath_multi_*`, `ui_qupath_not_listed_<app>`.

## Negative controls
`tests/run_ui_audit_negative.sh` seeds failures (extra control, removed control, blank result, a tool that fails, a worker that dies, a hung tool, a launch killed by the outer watchdog, an app listed that must not be) and asserts each one goes red with the expected text, and that the same audit can be green (`quick_values`). The tool apps are `tests/ui_audit_apps/*` plus `tests/frame_app` and `tests/runs_app`, registered with `labconstrictor-tools register` (see the script header).

## Bugs found in QuPath extension v0.1.0-alpha.1 (the audit's first runs; the Run button one is fixed on main, #38, with its own test body)
* **Run does nothing when clicked.** `runButton.onAction = { run() }` (LabConstrictorTools.groovy line 1111): inside a closure `run()` resolves to `Closure.run()` and recurses until `StackOverflowError` (logged by `QuPathUncaughtExceptionHandler`); the status does not change. Reproduce: open any form, press Run with the mouse. Every GUI body of this repository calls `dlg.run()` and so never saw it. Fix: `this.run()`; verified by a local patch (the panel and run checks then pass with real clicks). Until it is fixed the driver runs with `LC_UI_RUN_VIA=call`, which calls `dlg.run()`, and then `ui_qupath_panel_run` FAILS by design (a button that was not clicked is not shown to work).
* Observation, not shown to be reachable by a person: setting an image chooser's value to `null` throws `IllegalArgumentException: argument type mismatch` from the Image-area listener (`areaChooser`, line 1619) and QuPath opens its Log window. The driver does not set `null`.
* "Open in QuPath" with a project open does not open the file directly: QuPath asks "Import images to project" and then, because the results added annotations, "Save changes". The audit answers Import and No and checks that the viewer then shows the result file with the file's size.

## Not verified
Native file choosers; real clicks on spinners, sliders and combo popups (state is set and read back); the look of any screenshot; Windows and macOS; apps and cases other than those run.
