# LabConstrictor-QuPath (prototype)

Runs the tools of every installed LabConstrictor app (NucleiSky, CellTracksColab, ...) from **Extensions > LabConstrictor tools...** in
QuPath 0.7. The form is generated from the tool's declared schema ([LabConstrictor-Tools](https://github.com/CellMigrationLab/LabConstrictor-Tools),
`docs/PROTOCOL.md`); the tool runs in the app's own Python environment through the LabConstrictor worker (JSON lines on stdin/stdout),
so QuPath never imports the app's packages and needs no Python of its own. Same idea as the Napari and Fiji front-ends.

## Install
1. Install the LabConstrictor app(s) (the installer registers them; check with `labconstrictor-tools list`).
2. Drop `labconstrictor-qupath-0.1.0.jar` on the QuPath window (or into the QuPath extensions folder) and restart QuPath.
3. **Extensions > LabConstrictor tools...**

Without the jar: open `src/main/resources/org/cellmigrationlab/labconstrictor/qupath/LabConstrictorTools.groovy` in QuPath's Script Editor and run it.

## What it does
* Pick an app and a tool. Numbers (range, unit), choices, checkboxes, text, files and folders become fields; parameters of a group sit under a heading, advanced ones in a collapsed section, optional ones without a default have a "set" box (unticked = the tool gets nothing), and `enabled_when` greys fields out.
* **Image inputs**: the image open in QuPath, any image of the open project, or a file. QuPath's image is exported losslessly as TIFF (the whole image, or the part chosen under **Image area**, see below). The pixel size of the chosen image or typed file fills the tool's pixel-size field (see **Pixel size** below).
* Results open in a window: values, tables (as a table: the first 100 000 rows, then "table 'name' (N rows, first M shown; the full table is in P)", the Napari wording; cells with commas, quotes and line breaks are read as CSV says), images (a preview, the path and an "Open in QuPath" button), alignment matrices, files, messages (also shown under the status line) and points.
* **Interaction hints** of the manifest: a `choices_from` parameter is a dropdown filled by another tool of the app (a text field when that tool cannot answer), `clear_after_run` parameters are reset after a successful run, a `Collapsed` group is a folded section, and an output with `Replace()` reuses the results window of the tool instead of opening a new one.
* **Channel selector**: an image input declared with `PickChannel()` gets a Channel chooser (the channel names of the chosen image, or of a file); the tool receives only that channel.
* **Points** found in the image that is open in QuPath become a point annotation named `<app>:<output>` (replaced by the next run when the output declares `Replace()`); points of any other image are shown as a table.
* "No match" is a message, not an error. Progress bar, Cancel (a tool that ignores it is killed after 3 s), worker kept between runs (or not), details of the last run, no worker left behind when the window closes.
* **Trust checks** on every registry entry (same rules as the Python registry and the Fiji script): the entry names its five text fields; the app name is a plain name; the interpreter lies inside the install prefix (which is not a filesystem root); the entry and its schema file are owned by you (in a shared folder also by root) and not writable by group or others; the schema sits next to the entry; the interpreter, its folder and the prefix are not writable by everybody (a sticky folder like `/tmp` excepted); tool ids and parameter names are plain names, parameter names Python identifiers (a parameter name becomes a file name). An entry that fails is listed under "skipped" with its reason and never started. On Windows the owner and permission checks do not apply.
* Results are kept in `~/.labconstrictor/results/<time>_<6 hex>_<app>_<tool>` (names made safe), like the command line. The host never deletes a results folder or a run record (retention belongs to the Tools reference code, QuPath issue #14). Every run that reached the tool leaves a record `~/.labconstrictor/runs/<time>_<app>_<tool>/run.json` (none ever deleted by the host, the Tools format plus `"host": "qupath"`), and every log line is also appended to the shared `~/.labconstrictor/logs/labconstrictor.log` (Tools line format, `qupath:` prefix, 1 MB rotation with 5 backups), so `labconstrictor-tools logs` and `support-bundle` see QuPath runs.
* **Same rules as the other hosts** (the host-parity inventory of labconstrictor-tools): a form shows parameters in the Napari/Fiji order (a group gathered where it first appears; a Collapsed group is folded where it first appears; advanced last); `clear_after_run` resets every type of field; `enabled_when` follows text, number, choice, yes/no and "set" drivers (`""` counts as unset, numbers compare as numbers); a whole-number box holds ±(2^53 - 1) at most, declared limits or not (the largest whole number a double holds exactly, Tools `notebook.MAX_FORM_INTEGER`; a typed number beyond it stops at the limit), a decimal box without declared limits ±1e15; a choice of integers is sent as an integer; a missing input says `'L' is required`; NaN is refused with `'L' must be a finite number`; a folder input must be a folder; a worker that is idle for 10 minutes is closed; a worker ended after Cancel reads `cancelled (worker stopped)`; a worker that died is explained with the Tools sentences (a missing package first, then the exit code); a tool with a parameter type this host does not know is refused alone (`this tool cannot be shown: ...`); two tools with one label are listed as `label (id)` and the apps are sorted by name; `Copy as command` writes Python text the way `repr` does (a newline cannot break the snippet) and says `there is no clipboard to copy to` when there is none; message results show `- ` items as bullets; a result file that is missing, or a result type this version does not know, is said in one sentence.

## Pixel size (calibration)
A tool parameter linked to an image with `PixelSizeOf` (micrometres per pixel) is a text box with a button "Use the image's calibration". It follows the chosen image, the typed file and the image open in QuPath until you type a value (then your value stays, also when the image changes; press the button, or empty the box, to take the image's calibration again).

* **Unknown is a value.** Only an explicit length unit plus a number is a calibration; the number alone never is. A source without a unit (QuPath's `px`), with a unit that is not a length, with a damaged unit text (U+FFFD) or with pixels that are not square (X and Y differ by more than 0.1 %, relative to the larger) is unknown: the box is emptied (tooltip "not calibrated: 'image' has no pixel size"), a warning goes to the status line, and the value of another image never stays. Within 0.1 % the box takes the mean of X and Y.
* **Units** come from the unit catalog of the installed labconstrictor-tools (`conformance.json`, loaded with `python -m labconstrictor_tools.conformance --path` of a registered app; this script keeps no list): symbols (`um`, `nm`, the micro sign forms, the Angstrom signs, ...) are matched as exact text, words (`micron`, ` MILLIMETER `, ...) case-insensitively after trimming. When the catalog cannot be loaded every calibration is unknown and the status says so.
* **Where it comes from.** The image open in QuPath and project images: QuPath's `PixelCalibration` through the catalog. A file input: its own TIFF tags (OME `PhysicalSize` or ImageJ `unit=` first; the resolution tags only for `INCH` or `CENTIMETER` and not when they look like a 72 or 96 dpi default; `NONE` is unknown); other file formats through QuPath's reader. For a TIFF that QuPath itself read (a project image, the open image) and whose calibration was not entered in QuPath, the TIFF rules apply as well, because QuPath's reader turns a 72 dpi tag into 352.8 um per pixel; a calibration entered in QuPath ("Set pixel width/height") is explicit and is kept. A file wins over the open image when the image input is a file.
* **A run needs a value.** A linked parameter that is not `Optional` and is unset blocks the run: `'pixel_size' needs a value: 'image' has no pixel size. Enter it in um.` (the declared default is never a stand-in). An `Optional[float]` that is unset sends `null`.
* **The request** carries the number in the manifest parameter and the reserved input `_calibration` (per axis, x and y, in um, with `source` image, file or user and the raw number and unit text); tools never see it, the run record (`run.json`, `inputs`) keeps it. A request with a pixel-size value and no `_calibration` entry is a bug.

## Large images: the Image area and the size guard
Every image or labels input that uses the image open in QuPath ("Current image: ...") has an **Image area** chooser beside the image chooser:

* **Whole image** (default): as before.
* **Selected annotation(s)**: the bounding box of the selected annotations (points do not count), widened to whole pixels and clamped to the image; z and t are those of the viewer when the run starts. All selected annotations must lie on that one plane: annotations on different planes are refused (`selection_mixed_planes`), and annotations on another plane than the one shown are refused (`selection_plane_mismatch`); the host never switches the plane for you. The same rule applies to a RegionOf mask ("use the selection").
* **Current viewport**: the bounds of the region the viewer shows, clamped to the image.

**Stacks and 2D tools.** A tool that declares its axes (`Axes("YX")`) is not given a stack by chance: an image with several z-planes or time points that would be sent whole is refused with the Tools sentence first, `'Image' must be a 2D image (YX) but got 3D with shape (3, 60, 80)`, then the way out ("Choose 'Image area: Current viewport' to send the plane on screen, or use a 2D image"). This is the same refusal (`wrong_dimensions`) the other hosts give; a multi-channel image is refused by the worker with the same sentence. Intended difference: choosing an Image area (selection or viewport) names the plane, the z and t of the viewer, so that run goes through; the durable fix (a `PickPlane` hint) is in the Tools Wave 2 spec.

**Refusals read as the sentence alone.** A refusal the host makes before any tool starts (no selection, no viewer, an area outside the image, a stack for a 2D tool, a region too big) shows the sentence in the status line with no `failed:` and no class name, like Napari and Fiji; it is logged without a stack trace. The size guard and a missing image file are such refusals too (`'Image': file not found: P`, the Napari and Fiji sentence). A missing table or file is not checked by the host: the worker answers it (`failed: ...`, as with Napari and Fiji).

**The plane.** The plane the viewer shows when you press Run is the plane that is exported for the image open in QuPath, whatever Image area is chosen (whole image included), and the plane the results are drawn on: a point or outline without z and t lands on that plane, not on plane 0. A project image or a file has no viewer plane and is exported from plane z=0, t=0.

**The coordinate frame (SPEC_WRONG_SCIENCE section 1).** A result coordinate `x` (pixel centres at integers, `(y, x)` for points, `[x, y]` for outlines) is drawn at `c = x0 + (x + 0.5) * downsample` in full-resolution pixels (pixel `i` of QuPath spans `[i, i+1)`), on the exported plane. `x0` is the left or top edge of the Image area (0 for the whole image); the downsample is 1 for now. A QuPath image has no spatial transform, so there is nothing else to convert. A **file input** is its own pixel frame: its results are never drawn on the open image; the points are a table, the outlines a file, and the status says `file input: results are in the file's pixel frame`. An image or labels result that says `ApplyTo` and has another size than what the tool received is said once in the status line (`'labels' is 6 x 4 but 'Image' was exported as 12 x 8; shown at the pixel origin, not aligned`); QuPath opens such a result as an image of its own and never aligns it.

A project image or a file is always sent whole (the chooser is disabled and its tooltip says why). A missing selection or viewer is reported, never guessed around.

The tool receives only the area; **results come back on the right place of the slide**: outlines (annotations) and points are moved by the area's origin (x0, y0). The offset comes from the image named by the output's `apply_to` (a RegionOf parameter counts as the image it belongs to), else from the first image input. Because results have only one offset, several image inputs must use the same area; a run that mixes different areas (or an area with a whole image or a file) is refused with a message. A RegionOf selection mask is painted over the area only (same crop and offset), so its 100 M pixel cap applies to the cropped size and a selection on a big image works.

**Size guard.** Before an image is exported through QuPath (a path to a file is passed on untouched and is not checked) the number of pixels of one plane of the exported area (width x height) is compared with a budget of 100 000 000 pixels. Above it the run is refused with a message that names the image and its size and gives the two ways out: "choose Image area: Selected annotation(s)", or use a smaller image or a file. Nothing is downsampled or cropped silently. Change the budget with the system property `lc.qupath.max_export_pixels` (a positive whole number of pixels), e.g. `-Dlc.qupath.max_export_pixels=400000000` in QuPath's JVM options; the tests set a tiny value to trigger the guard on a small image. An invalid value is ignored (logged once) and the default is used.

The downsample of an export is 1 for now and is kept next to the offset (`downsample`, always 1.0), so the later resolution step changes one place.

## Fallbacks (intended)
Each of these is deliberate and is written once to QuPath's log (logger `labconstrictor`) when it happens. Every other failure is logged with its stack trace and shown to the person (status line or Details).

| Where | What happens | What the person sees |
|---|---|---|
| `choices_from` parameter (dropdown twin) | The source tool is not ready (a value it depends on is empty), failed, or did not return a list: the text field stays | The plain text field; after a failure the status line says "The choices for '...' could not be loaded (type the value): reason" (first time only) |
| `PickChannel` image input | The chosen image has one channel (or none can be read): the channel chooser stays hidden and the tool gets the whole image | No Channel chooser; an unreadable file also shows "Channels of '...' could not be read" in the status line |
| Image area other than the whole image, in Copy as command | A terminal line cannot name a selection or a viewport: the line sends the whole file | A note line "# image: QuPath sent only the Image area '...' (the command line has no area); the command sends the whole file" before the command |
| Points / outlines output | The image searched was not the image open in QuPath (a file, or another project image): nothing is added to the open image | The table (points) or the line "Not placed on an image (...)" in the results window |

## Differences from the other hosts that are kept or still open
* **Kept (intended):** an Image area (selection or viewport) names the plane, so a z-stack is sent from the viewer's plane for a 2D tool; the other hosts have no such chooser (the durable fix is a `PickPlane` hint, Tools Wave 2). The export size has two limits: 4 GiB of data like Napari, and (kept from before) 100 000 000 pixels per plane, set with `-Dlc.qupath.max_export_pixels` (the bytes limit with `-Dlc.qupath.max_export_bytes`). The no-cause crash sentence ("the worker process stopped unexpectedly") has no counterpart in the Tools text (which says nothing then).
* **Copy as command, Windows:** a word is quoted by the rules of the C runtime (backslashes literal, except in front of a double quote or at the end of the text; the result for each case is listed in `tests/quote_vectors.json`, the file of labconstrictor-tools, `command.quote` being the reference); `%NAME%` is still expanded by cmd.exe inside quotes, and PowerShell reads quotes by its own rules (use the Python snippet there).

## Not done yet
* Images are exported at full resolution only (no downsample or pixel-size choice yet); RGB / brightfield images are exported as RGB TIFF.
* Label images are shown as a preview, not converted to QuPath annotations/detections, and tables are not added to the measurements.
* No headless/script API, no Windows/macOS testing, no menu entry per tool.

## Build and test
    QUPATH=/path/to/QuPath JAVA_HOME=<JDK 25> ./build.sh        # QuPath 0.7 is built with Java 25
    QUPATH=/path/to/QuPath LC_HOME=<registry with NucleiSky and CellTracksColab> tests/run_gui_test.sh   # needs xvfb; 21 checks

`BODY=gui_test_interactions_body.groovy tests/run_gui_test.sh` runs the interaction hints, messages and points against the example app of labconstrictor-tools (`labconstrictor_tools.examples.interactions`, registered in `LC_HOME`; 9 checks).

`BODY=gui_test_playground_body.groovy tests/run_gui_test.sh` runs the tools of the LabConstrictor Playground app (check, device dropdown, big image, crash, no memory, 200 000 points; 8 checks; register the Playground in `LC_HOME` first).

`BODY=gui_test_channel_body.groovy tests/run_gui_test.sh` checks the channel chooser of `PickChannel` inputs with a 3-channel TIFF (the tool receives only the chosen channel; 4 checks).

`BODY=gui_test_adversarial_body.groovy tests/run_gui_test.sh` throws odd input at the hints (a broken or malformed source tool, 5000 unicode options, a default the source does not list, empty and 50 000 points, a very long message, a failing run); register `tests/adversarial_app` as `adv` first (12 checks).

``BODY=gui_test_fallbacks_body.groovy tests/run_gui_test.sh` checks that failures that used to be silent are reported (bad number typed into a field, unreadable value in Copy as command, host failure with stack trace in Details, unreadable channel file, intended fallbacks logged; 13 checks; needs the `interactions` example app in `LC_HOME`).
`BODY=gui_test_frame_body.groovy tests/run_gui_test.sh` runs the coordinate-frame cases of `conformance.json` (CF-01, 07, 08, 09, 10, 11, 12, 14; CF-02..06 and CF-13 do not apply to QuPath) against the `frame` test app (`tests/frame_app`, registered by `tests/ci_setup_apps.sh`), a real worker that reports what it received; the cases are read from the conformance file of labconstrictor-tools (`LC_CONFORMANCE`, else `python -m labconstrictor_tools.conformance --path`).

`BODY=gui_test_area_body.groovy tests/run_gui_test.sh` checks the Image area and the size guard against the `interactions` example app and the blobs fixture (guard at and one pixel over the budget, the exported area equals the server region, outlines and points land at the blobs' full-image coordinates, the viewport area, RegionOf over an area, differing areas refused, Copy as command note; 48 checks).

The `gui_test_parity_*` bodies need the registered `interactions` app (its Python also writes the test images and runs `labconstrictor-tools`; the Playground app for the cancel body). The bodies that write their own registry and results (trust, form, host, lifecycle, pixel, support) empty their `LC_HOME` first, so that one must be a scratch folder with the name given below, and `LC_SHARED_HOME` must name the registry that holds `interactions`; `tests/run_gui_ci.sh` sets both for every body.

`BODY=gui_test_parity_trust_body.groovy LC_HOME=<an empty scratch folder named lhome_trust> tests/run_gui_test.sh` writes registry entries that break each trust check and checks that each one is refused with its reason and that a good entry loads (26 checks; needs root to test the owner check).

`BODY=gui_test_parity_messages_body.groovy tests/run_gui_test.sh` opens a 3-plane TIFF (written with tifffile from `/home/tester/v-napari`) and checks that a refusal is the sentence alone, that a z-stack is refused for a `YX` tool with the Tools sentence and the way out, that a series, z, t and channel counts are named, and when nothing is refused (18 checks).

`BODY=gui_test_parity_results_body.groovy tests/run_gui_test.sh` builds result tables of 150 000, 100 000, 2 001 and a few rows, with quoted commas, doubled quotes and multi-line cells, and checks the rows shown and the sentence (12 checks).

`BODY=gui_test_calibration_body.groovy tests/run_gui_test.sh` runs CAL-01..CAL-20 and CAL-T1..CAL-T9 of `conformance.json` through the form (a file input, project images and the image open in QuPath, one real worker run each for a required and an optional field) and checks the request and the `_calibration` input, not only the field; the images are written by `tests/calibration_fixtures.py` (tifffile) with the Python of the registered `frame` app.

`BODY=gui_test_parity_pixel_body.groovy LC_HOME=<scratch folder named lhome_pixel> tests/run_gui_test.sh` reads the pixel size of files (ImageJ unit, centimetre TIFF unit), keeps a typed value, says non-square pixels, reports an unreadable file (15 checks).

`BODY=gui_test_parity_form_body.groovy LC_HOME=<scratch folder named lhome_form> tests/run_gui_test.sh` registers a synthetic app and checks the form order, ClearAfterRun for every type, EnabledWhen for every driver, number limits (whole numbers up to 2^53 - 1, held and typed), integer choices, required and non-finite inputs (55 checks).

`BODY=gui_test_parity_host_body.groovy LC_HOME=<scratch folder named lhome_host> tests/run_gui_test.sh` checks the tool chooser, an unsupported parameter type, folder inputs, the Python literal (also read back by Python), bullets, missing result files, a failing clipboard and the crash sentences (fake interpreters that print and exit); a missing image file is refused with the sentence, a missing table is left to the worker, a clipboard that refuses or throws is said (49 checks).

`BODY=gui_test_parity_lifecycle_body.groovy LC_HOME=<scratch folder named lhome_life> tests/run_gui_test.sh` checks results folder names and that no folder is ever deleted, the byte limit, the shapes cap sentence and the idle worker (28 checks).

`BODY=gui_test_parity_cancel_body.groovy tests/run_gui_test.sh` cancels the Playground's "ignores Cancel" tool and checks `cancelled (worker stopped)` (9 checks; needs the Playground in `LC_HOME`).

`BODY=gui_test_parity_support_body.groovy LC_HOME=<scratch folder named lhome_support> tests/run_gui_test.sh` checks the shared log (format, rotation, read by `labconstrictor-tools logs`) and the run records (also in `support-bundle`) (25 checks).

`BODY=gui_test_parity_quote_body.groovy LC_HOME=<registry with the interactions app> tests/run_gui_test.sh` (from the repository folder) checks `shellQuote` against every case of `tests/quote_vectors.json` (the file of labconstrictor-tools, copied here), reads the quoted text back with independent readers (the C runtime rules for Windows, a POSIX word reader) and checks the terminal command of the dialog with the operating system set to Windows (13 checks).

`BODY=gui_test_buttons_body.groovy tests/run_gui_test.sh` clicks "Rescan apps" and "Restart worker" (3 checks; on the version before the split the Rescan button threw `MissingMethodException` because a local variable named `rescan` shadowed the method).

### Lint (runs in CI) and what CI does not run

`.github/workflows/lint.yml` lints `LabConstrictorTools.groovy` with `npm-groovy-lint` (pinned version, CodeNarc rules, config in `.groovylintrc.json`; each disabled rule has its reason there). It fails on any finding, including info. Run it locally (needs Node and Java):

    npx --yes npm-groovy-lint@18.0.0 --path src/main/resources/org/cellmigrationlab/labconstrictor/qupath --files "**/*.groovy" --failon info

### GUI tests in CI

`.github/workflows/qupath-gui.yml` (job `qupath-gui`; on every pull request, on demand and nightly) runs the GUI tests headless on Linux: it downloads QuPath 0.7.0 (pinned by version and SHA-256, cached), builds the jar from the repo with `build.sh` (Java 25), installs LabConstrictor-Tools from its `main` branch (a tag pin comes later: `TOOLS_REF` in the workflow), registers the apps with `tests/ci_setup_apps.sh` and runs every body under Xvfb through `tests/run_gui_ci.sh`, which calls `tests/run_gui_test.sh` once per body. A body fails the job when QuPath exits non-zero, a check prints `FAIL`, or the body never reaches its result line (crash or timeout, 15 minutes per body). Logs and screenshots are uploaded as the artifact `qupath-gui-logs`. Locally the same:

    QUPATH=/path/to/QuPath JAVA_HOME=<JDK 25> ./build.sh
    tests/ci_setup_apps.sh /tmp/lc main        # venvs + registry in /tmp/lc/home (needs Python 3.12; PYTHON=python3.12)
    QUPATH=/path/to/QuPath LC_HOME=/tmp/lc/home tests/run_gui_ci.sh [body.groovy ...]

Apps in CI: `interactions` (example app of labconstrictor-tools), `adv` (`tests/adversarial_app`) and the Playground (from its `main`, with CPU PyTorch). **Skipped, with the reason printed:** `gui_test_body.groovy` (NucleiSky and CellTracksColab have no LabConstrictor bridge on their `main` branch yet, so there is nothing to install; the body runs as soon as both are registered in `LC_HOME`). The sample images of the bodies are in `tests/fixtures` (`-Dlc.test.fixtures`).

`tests/gui_test_body.groovy` drives the real dialog in QuPath on a virtual screen (project with two calibrated images, both apps) and writes screenshots.
