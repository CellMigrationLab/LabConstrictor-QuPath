# QuPath extension reference

How the QuPath extension behaves, rule by rule, and how to build and test it. To install and use it, read the [QuPath guide](https://cellmigrationlab.github.io/LabConstrictor-Site/guides/qupath/).

The extension builds a form from the schema an app's tools declare and runs the tool in the app's own Python environment through the LabConstrictor worker (JSON lines on stdin/stdout). QuPath never imports the app's packages and needs no Python of its own. The schema, the worker protocol and the rules every host shares are in the [protocol reference](https://cellmigrationlab.github.io/LabConstrictor-Site/guides/protocol/); this page says how QuPath applies them.

The extension is a Groovy script split into the part files listed in `src/main/resources/org/cellmigrationlab/labconstrictor/qupath/parts.txt`. A small Java class (`LabConstrictorExtension`) adds the menu entry **Extensions > LabConstrictor tools...** and runs the parts joined into one script. The part files do not run on their own in the Script Editor; use the all-in-one `LabConstrictorTools-all.groovy` of a release instead.

## Requirements and compatibility

- **QuPath 0.7.** The jar is built against QuPath 0.7.0, which runs on Java 25. Older QuPath versions cannot load it; other 0.7.x versions are not tested.
- **Systems.** Tested on Linux, Windows and macOS. Every pull request runs the full GUI test suite on Linux; the release workflow also tries the release jar on Windows and macOS (Apple silicon).
- **labconstrictor-tools 0.3.0a1 or newer** in the app's environment, with the features `app_hosts_v1`, `reserved_inputs_v1` and `retention_prune`. The check runs when the registry is read (at start and at **Rescan apps**) and again when Run is pressed. An app that fails it is not offered and is listed under "skipped" with one sentence, for example `this app's labconstrictor-tools 0.2.0rc1 is older than the 0.3.0a1 this host needs: update it`. An app whose version text cannot be read is offered with a warning. The rules are those of the protocol's "Compatibility rules for hosts", written in Groovy (`LcCompat`).
- **Which apps are listed.** QuPath lists an app only when its `hosts` declaration names `qupath`, as the protocol's "App hosts" section says (`LcAppHosts`). An app without a declaration (installed with an older installer) is listed, and the log says so each time the registry is read. Sentences for skipped apps go to the log and to the "skipped" list in the status line.
- **Apps that need a newer extension.** An app can declare the oldest QuPath extension it needs (the protocol's "Host requirements"; `LcHostRequirements`). The extension's own version is the jar's `Implementation-Version`. When it is too old the app stays listed with a warning, or is skipped when the app marks the requirement `required: true`.
- **Trust checks.** Every registry entry is checked before it is listed, with the same rules as the Python registry and the Fiji script: the entry has its text fields, the app name and tool and parameter names are plain names, the interpreter lies inside the install prefix, the entry and its schema are owned by you (or root in a shared folder) and not writable by group or others, and nothing on the interpreter's path is writable by everybody. An entry that fails is listed under "skipped" with its reason and never started. On Windows the owner and permission checks do not apply.

Every release lists in its notes the QuPath version it was built against and the minimum Tools version; `SHA256SUMS` holds the checksums of its files.

## The form

- Numbers (with range and unit), choices, checkboxes, text, files and folders become fields. Parameters of a group sit under a heading, a `Collapsed` group is a folded section, advanced parameters are in a collapsed section at the end, and the order is the one napari and Fiji use.
- An optional parameter without a default has a "set" box; unticked, the tool gets nothing. `enabled_when` greys fields out and follows text, number, choice, yes/no and "set" drivers.
- An input with a description has an **ⓘ** after its label. Hover over it (or the label) to read the description, click it to keep it open, or move into the input with Tab to see it below.
- `choices_from` makes a dropdown filled by another tool of the app (see [Dynamic choices and workers](#dynamic-choices-and-workers)). `clear_after_run` resets a field after a successful run. `Replace()` on an output reuses the tool's results window and replaces its earlier annotations.
- An image input with `PickChannel()` gets a **Channel** chooser; the tool receives only that channel.
- Two tools with the same label are listed as `label (id)`; apps are sorted by name. A tool with a parameter type this host does not know is refused on its own: `this tool cannot be shown: ...`.
- **Copy as command** puts a terminal command or a Python snippet on the clipboard. On Windows, words are quoted by the C runtime rules (the cases are in `tests/quote_vectors.json`, copied from labconstrictor-tools); cmd.exe still expands `%NAME%` inside quotes and PowerShell has its own quoting, so use the Python snippet there.

### Number limits

- Without declared `Min` / `Max`, a whole-number box accepts ±9007199254740991 (2^53 - 1) and a decimal box ±1e15. A declared limit is used as declared; a whole-number limit beyond 2^53 - 1 is cut to it.
- The text in the box is the value: a whole number is read exactly, a decimal is compared with the limits as its exact text, and the request carries what was typed.
- A number beyond a limit is refused, never moved to the limit. The run does not start and the status line says, for example, `'Threshold' must be <= 10`. The typed text stays in the box. The check runs when the box loses focus, when Enter is pressed and when Run is pressed. The arrows stop at the limits.
- Other refusals: `'L': '12x' is not a number`, `'L' must be a finite number` (`nan`, `inf`), `'L' is required` (empty).

## Images

An image input can be the image open in QuPath, any image of the open project, or a file. QuPath's images are exported losslessly as TIFF; a file is passed on as it is. RGB and brightfield images are exported as RGB TIFF.

### Image area

Every image or labels input that uses the image open in QuPath has an **Image area** chooser:

- **Whole image** (the default).
- **Selected annotation(s)**: the bounding box of the selected annotations (points do not count), widened to whole pixels and clamped to the image. All selected annotations must lie on the plane the viewer shows; otherwise the run is refused. The extension never switches the plane for you. The same rule applies to a `RegionOf` mask.
- **Current viewport**: the region the viewer shows, clamped to the image.

A project image or a file is always sent whole (the chooser is disabled). Results have one offset, so all image inputs of a run must use the same area; a run that mixes areas, or an area with a whole image or a file, is refused.

### Plane and stacks

The plane the viewer shows when you press Run is the plane exported for the open image, whatever the area, and the plane the results are drawn on. A project image or a file is exported from z = 0, t = 0.

A tool that declares 2D axes (`Axes("YX")`) is not sent a stack: an image with several z-planes or time points is refused with the Tools sentence, for example `'Image' must be a 2D image (YX) but got 3D with shape (3, 60, 80)`, followed by `Choose 'Image area: Current viewport' to send the plane on screen, or use a 2D image`. Choosing a selection or viewport area names the plane, so that run goes through. napari and Fiji have no such chooser.

### Size limits

Before QuPath exports an image, the area is checked against two limits: 100 000 000 pixels per plane (width × height) and 4 GiB of data. Above either, the run is refused with a message that names the image and its size and ends `Choose 'Image area: Selected annotation(s)' to send a part of it, or use a smaller image or a file`. Nothing is downsampled or cropped silently. A file path is not checked.

To change the limits, set the system properties `lc.qupath.max_export_pixels` and `lc.qupath.max_export_bytes` (positive whole numbers) in QuPath's JVM options, for example `-Dlc.qupath.max_export_pixels=400000000`. An invalid value is logged once and the default is used. A `RegionOf` mask is painted over the exported area only, and is limited to 100 000 000 pixels too.

Refusals made before any tool starts (no selection, no viewer, an area outside the image, a stack for a 2D tool, an image too big, a missing image file) show the sentence alone in the status line, as in napari and Fiji. A missing table or file is left to the worker, which answers `failed: ...`.

## Pixel size

A parameter linked to an image with `PixelSizeOf` (micrometres per pixel) is a text box with a **Use the image's calibration** button. It follows the chosen image until you type a value; your value then stays when the image changes. Press the button, or empty the box, to take the image's value again.

- **Only a length unit plus a number is a calibration.** No unit (QuPath's `px`), a unit that is not a length, a damaged unit text, or pixels that are not square (X and Y differ by more than 0.1 %) count as unknown: the box is emptied, its tooltip says `not calibrated: 'image' has no pixel size`, and a warning goes to the status line. Within 0.1 % the box takes the mean of X and Y.
- **Units** come from the unit catalog of the app's labconstrictor-tools (`conformance.json`). If the catalog cannot be loaded, every calibration is unknown and the status line says so.
- **Sources.** The open image and project images: QuPath's pixel calibration. A file: its TIFF tags (OME `PhysicalSize` or ImageJ `unit=` first; resolution tags only in inches or centimetres and not when they look like a 72 or 96 dpi default); other formats through QuPath's reader. For a TIFF that QuPath read, the same TIFF rules apply unless a pixel size was set in QuPath, because QuPath's reader turns a 72 dpi tag into 352.8 µm per pixel.
- **A run needs a value.** An unset, non-optional linked parameter blocks the run with `'pixel_size' needs a value: 'image' has no pixel size. Enter it in um.` The declared default is never used instead. An unset `Optional[float]` sends `null`.
- The request carries the number in the parameter and, per axis with its source, in the reserved input `_calibration` (see the protocol's "Reserved inputs"). The run record keeps it.

## Results

Results open in a window: values, messages (also shown under the status line, `- ` items as bullets), tables, images (a preview, the path and an **Open in QuPath** button), alignment matrices, files and points. "No match" is a message, not an error. A missing result file, or a result type this version does not know, is said in one sentence.

- **Tables** show the first 100 000 rows, then `table 'name' (N rows, first M shown; the full table is in P)`. Tables and points files are read by one CSV reader: quoted commas, doubled quotes and multi-line cells are read as CSV defines them. A malformed file, or a points file without `y` and `x` columns or with a coordinate that is not a finite number, is reported in one sentence and not shown in part.
- **Points** found in the open image become a point annotation named `<app>:<output>`. Points of any other image are shown as a table.
- **Outlines** found in the open image become annotations classified `<app>:<output>`, with their numeric properties as measurements. Up to 50 outlines are named `<app>:<output> <label>`; above 50 they get no names, so the names do not hide the image. A numeric label is then the measurement `label`, a text label is kept in the annotation's metadata, and an outline without a label gets the measurement `outline_index`. At most 50 000 outlines are added; the message says how many were left out.
- **What `Replace()` removes.** Every annotation a run draws carries the metadata `labconstrictor.run_id`, `labconstrictor.app`, `labconstrictor.tool` and `labconstrictor.output`. `Replace()` removes only annotations whose metadata says they are this app's output, even if you renamed or reclassified them. Your own annotations are never removed, even with the same classification.
- **Results of a file input** are in the file's own pixels: they are never drawn on the open image, and the status line says `file input: results are in the file's pixel frame`.

### Coordinate frame

A result coordinate (pixel centres at integers; `(y, x)` for points, `[x, y]` for outlines) is drawn at `x0 + (x + 0.5)` in full-resolution pixels on the exported plane, where `x0` is the left or top edge of the Image area (0 for the whole image). Exports are always at full resolution. A QuPath image has no spatial transform, so nothing else is converted. The offset comes from the image named by the output's `apply_to` (a `RegionOf` parameter counts as its image), else from the first image input.

An image or labels result that says `ApplyTo` but has another size than what the tool received is opened as a separate image, never aligned, and the status line says so: `'labels' is 6 x 4 but 'Image' was exported as 12 x 8; shown at the pixel origin, not aligned`.

### Run identity

A run's results go to the image it was exported from, never to whatever is open when it ends.

- When Run is pressed, the extension freezes the run: a `run_id`, the app and tool, the form values, the plane, the Image areas, the QuPath image object of each "Current image" input with its shape, and the regions of the selected annotations. Export, request, run record and placement all use this copy, so opening another image or changing the selection during a run changes nothing.
- When the open image changes, the "Current image: ..." entry of every image chooser follows it. A run whose chooser names an image that is no longer open is refused: `'Image': 'A' is no longer the image open in QuPath ('B' is): choose the image again`.
- Points and outlines are drawn only on the image the run started on, while a viewer still shows it with the same shape (in any viewer, not only the active one). Otherwise nothing is drawn, the files stay in the results folder, and the status line says either `run <run_id> on 'A': 'A' is no longer open; results are in <path>` or `run <run_id> on 'A': 'A' changed size while the tool ran; results are in <path>`.
- Switching the tool or app during a run does not affect that run; `clear_after_run` resets only the form the run started from.

## Dynamic choices and workers

A `choices_from` dropdown asks another tool of the app for its choices.

- An answer is applied only if the form, app, tool, parameter and the values it depends on are still the same when it arrives; otherwise it is dropped. When a value the dropdown depends on changes, the dropdown becomes a text field with the tooltip `waiting for the choices...`, and a new question is asked after a 400 ms pause in typing.
- One lookup runs at a time per window, at most 8 wait (the oldest is dropped and logged), and a lookup that takes longer than 60 s is ended with its worker. A lookup never uses the worker of a run and waits while a run is going.
- If the source tool fails or does not return a list, the plain text field stays and the status line says once: `The choices for '...' could not be loaded (type the value): reason`.

Workers:

- With **Keep the worker running between runs** ticked (the default), a worker stays between runs and is closed after 10 minutes without use. **Restart worker** ends it.
- **Cancel** asks the tool to stop; a tool that ignores it is ended after 3 s and the status line says `cancelled (worker stopped)`.
- A worker that died is explained with the Tools sentences: a missing package first, then the exit code. A worker that crashed or hit its time limit is never reused.
- Closing the window stops pending lookups and closes every worker; a run in progress counts as cancelled.

## Results folders, run records and logs

Each run makes a results folder `~/.labconstrictor/results/<time>_<6 hex>_<app>_<tool>` and a run-record folder `~/.labconstrictor/runs/<time>_<app>_<tool>` (under `$LC_HOME` when it is set). The extension never deletes anything there. It marks each run finished and then starts `<the app's Python> -m labconstrictor_tools prune` without waiting for it; Tools keeps the newest 20 results and 50 run records and removes only folders it can prove LabConstrictor made. The markers and the policy are in the protocol's "Result folders and run records (retention)" section.

- The finished status is `ok`, `cancelled` (the tool honoured Cancel, the worker was ended after Cancel, or the window was closed during the run) or `failed`.
- A prune that cannot start or fails is logged (`prune could not be started ...`, `prune ended with exit code N ...`) and never fails the run.
- The run record `run.json` is built by the Tools helpers, plus `"host": "qupath"`, the `run_id`, the start time and the `_calibration` input. If it cannot be written, the status line says `the run record could not be written (...); its folders stay as they are and are never removed`.
- Every log line is appended to `~/.labconstrictor/logs/labconstrictor.log` in the Tools format with a `qupath:` prefix (1 MB, 5 backups), so `labconstrictor-tools logs` and `support-bundle` include QuPath runs. The status line and **Details...** of the window show what failed.

## Fallbacks

Each of these is written once to QuPath's log (logger `labconstrictor`) when it happens. Every other failure is logged with its stack trace and shown in the status line or in **Details...**.

| Where | What happens | What you see |
|---|---|---|
| `choices_from` parameter | The source tool is not ready, failed, or did not return a list: the text field stays | The text field; after a failure, the status line message above (first time only) |
| `PickChannel` image input | The image has one channel, or its channels cannot be read: the tool gets the whole image | No Channel chooser; an unreadable file also shows `Channels of '...' could not be read` |
| Copy as command with an Image area | A terminal line cannot name a selection or viewport, so it sends the whole file | The note `# image: QuPath sent only the Image area '...' (the command line has no area); the command sends the whole file` |
| Points or outlines output | The image searched was not the open image: nothing is added to it | The table (points), or `Not placed on an image (...)` in the results window |

## Not available yet

- Exports at a lower resolution or a chosen pixel size: images are always exported at full resolution.
- Label images become a preview, not QuPath annotations or detections; tables are not added to QuPath's measurements.
- A script API, or one menu entry per tool.

## Build from source and test

Build with JDK 25:

```bash
QUPATH=/path/to/QuPath JAVA_HOME=/path/to/jdk-25 ./build.sh
```

This writes `target/labconstrictor-qupath-0.1.0.jar` (set `LC_QUPATH_VERSION` for another version; it is also the jar's `Implementation-Version`) and `target/LabConstrictorTools-all.groovy` (all parts in one file, for the Script Editor).

### Releases

`.github/workflows/release.yml` runs when a release is published. It builds `labconstrictor-qupath-<tag>.jar`, starts it in QuPath 0.7.0 on a virtual screen (`tests/smoke_jar.sh`: the menu entry exists and the dialog opens), and only then attaches the jar, a copy named `labconstrictor-qupath.jar`, `LabConstrictorTools-all.groovy` and `SHA256SUMS`, and appends the QuPath and Tools versions to the notes. Run it by hand (Actions > release > Run workflow, with a tag name) for a dry run that keeps the files as workflow artifacts. The `os-smoke` jobs then try the same jar on Windows and macOS (`tests/smoke_os.sh`); they never block the release.

### GUI tests

> [!WARNING]
> The GUI tests modify the QuPath installation that `QUPATH` points to: `tests/run_gui_test.sh` copies the jar into `$QUPATH/lib/app/` and adds it to `app.classpath` in `$QUPATH/lib/app/QuPath.cfg`. Use a disposable copy of QuPath.

Run every test locally (Linux, needs Xvfb and Python 3.12):

```bash
QUPATH=/path/to/QuPath JAVA_HOME=/path/to/jdk-25 ./build.sh
tests/ci_setup_apps.sh /tmp/lc main                                       # test apps and a private registry in /tmp/lc/home
QUPATH=/path/to/QuPath LC_HOME=/tmp/lc/home tests/run_gui_ci.sh [body.groovy ...]
```

`tests/ci_setup_apps.sh` installs labconstrictor-tools from the given branch or tag and registers the test apps: `interactions` (the example app of labconstrictor-tools), `adv` (`tests/adversarial_app`), `frame` (`tests/frame_app`), `runs` (`tests/runs_app`) and the Playground. `tests/run_gui_ci.sh` runs each body through `tests/run_gui_test.sh`; to run one body, set `BODY=<body>.groovy` and call `tests/run_gui_test.sh`. A body fails when QuPath exits non-zero, a check prints `FAIL`, or it never prints its result line (crash or 15-minute timeout). Bodies that write their own registry empty their `LC_HOME` first; `tests/run_gui_ci.sh` gives each a scratch folder and sets `LC_SHARED_HOME` to the registry with the shared apps. Sample images are in `tests/fixtures`.

| Body (`tests/gui_test_<name>_body.groovy`) | What it checks |
|---|---|
| `interactions`, `adversarial`, `fallbacks`, `buttons`, `help`, `widgets` | Interaction hints, odd input, reported failures, the window's buttons, input help, field types |
| `playground`, `channel`, `shapes`, `copy`, `run_button`, `region` | Playground tools, the Channel chooser, outlines, Copy as command, the Run button, `RegionOf` |
| `area` | Image area, size limits, placement of results at full-image coordinates |
| `frame`, `calibration` | The coordinate-frame and calibration cases of `conformance.json`, through a real worker |
| `conformance` | Every limit, message and code of `conformance.json` matches the script; every case is run by some body or marked as not applying to QuPath |
| `numbers`, `async`, `identity`, `retention` | Number limits, dynamic choices and workers, run identity, results folders and `prune` |
| `app_hosts`, `host_requirements`, `listing` | The listing rules (`listing` is the release-sandbox driver, run by `tests/run_listing_matrix.sh`) |
| `parity_*` | The rules shared with napari and Fiji: trust checks, form, messages, results, pixel size, lifecycle, cancel, logs and run records, quoting |

The owner checks of `parity_trust` need a second user `tester` and root or passwordless sudo; without them they are skipped with a NOTE, and `LC_REQUIRE_OWNER_CHECKS=1` makes the skip a failure.

### GUI tests in CI

`.github/workflows/qupath-gui.yml` runs every body on Linux under Xvfb on every pull request, nightly and on demand. It downloads QuPath 0.7.0 (pinned by SHA-256), builds the jar, installs labconstrictor-tools from `main` (`TOOLS_REF`), runs `tests/ci_setup_apps.sh` and `tests/run_gui_ci.sh`, and uploads logs and screenshots as `qupath-gui-logs`.

### Lint

`.github/workflows/lint.yml` lints the joined script with `npm-groovy-lint` (CodeNarc rules, config and the reason for each disabled rule in `.groovylintrc.json`) and fails on any finding. It also fails when a part file other than `LabConstrictorTools.groovy` reaches 600 lines: put new code in a new file listed in `parts.txt`. Locally (needs Node and Java):

```bash
mkdir -p lint-input && cp target/LabConstrictorTools-all.groovy lint-input/
npx --yes npm-groovy-lint@18.0.0 --path lint-input --files "**/*.groovy" --config .groovylintrc.json --failon info
```

### UI audit

`tests/run_ui_audit.sh` presses every control of the window, opens every tool of one installed app and runs it with the cases of the app's `lc_tests/cases.json`. It takes minutes per app, so CI does not run it. Variables, checks and negative controls: [UI_AUDIT.md](UI_AUDIT.md).
