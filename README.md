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
* **Image inputs**: the image open in QuPath, any image of the open project, or a file. QuPath's image is exported losslessly as TIFF (the whole image, or the part chosen under **Image area**, see below). The pixel size of the chosen image fills the tool's pixel-size field (micrometres).
* Results open in a window: values, tables (as a table), images (a preview, the path and an "Open in QuPath" button), alignment matrices, files, messages (also shown under the status line) and points.
* **Interaction hints** of the manifest: a `choices_from` parameter is a dropdown filled by another tool of the app (a text field when that tool cannot answer), `clear_after_run` parameters are reset after a successful run, a `Collapsed` group is a folded section, and an output with `Replace()` reuses the results window of the tool instead of opening a new one.
* **Channel selector**: an image input declared with `PickChannel()` gets a Channel chooser (the channel names of the chosen image, or of a file); the tool receives only that channel.
* **Points** found in the image that is open in QuPath become a point annotation named `<app>:<output>` (replaced by the next run when the output declares `Replace()`); points of any other image are shown as a table.
* "No match" is a message, not an error. Progress bar, Cancel (a tool that ignores it is killed after 3 s), worker kept between runs (or not), details of the last run, no worker left behind when the window closes.
* **Trust checks** on every registry entry (same rules as the Python registry and the Fiji script): the entry names its five text fields; the app name is a plain name; the interpreter lies inside the install prefix (which is not a filesystem root); the entry and its schema file are owned by you (in a shared folder also by root) and not writable by group or others; the schema sits next to the entry; the interpreter, its folder and the prefix are not writable by everybody (a sticky folder like `/tmp` excepted); tool ids and parameter names are plain names, parameter names Python identifiers (a parameter name becomes a file name). An entry that fails is listed under "skipped" with its reason and never started. On Windows the owner and permission checks do not apply.
* Results are kept in `~/.labconstrictor/results/` (the newest 20), like the command line.

## Large images: the Image area and the size guard
Every image or labels input that uses the image open in QuPath ("Current image: ...") has an **Image area** chooser beside the image chooser:

* **Whole image** (default): as before.
* **Selected annotation(s)**: the bounding box of the selected annotations (points do not count), widened to whole pixels and clamped to the image; z and t are those of the viewer.
* **Current viewport**: the bounds of the region the viewer shows, clamped to the image.

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

``BODY=gui_test_fallbacks_body.groovy tests/run_gui_test.sh` checks that failures that used to be silent are reported (bad number typed into a field, unreadable value in Copy as command, host failure with stack trace in Details, unreadable channel file, intended fallbacks logged; 12 checks; needs the `interactions` example app in `LC_HOME`).
`BODY=gui_test_area_body.groovy tests/run_gui_test.sh` checks the Image area and the size guard against the `interactions` example app and the blobs fixture (guard at and one pixel over the budget, the exported area equals the server region, outlines and points land at the blobs' full-image coordinates, the viewport area, RegionOf over an area, differing areas refused, Copy as command note; 48 checks).

`BODY=gui_test_parity_trust_body.groovy LC_HOME=<an empty scratch folder named lhome_trust> tests/run_gui_test.sh` writes registry entries that break each trust check and checks that each one is refused with its reason and that a good entry loads (26 checks; needs root to test the owner check).

`BODY=gui_test_buttons_body.groovy tests/run_gui_test.sh` clicks "Rescan apps" and "Restart worker" (3 checks; on the version before the split the Rescan button threw `MissingMethodException` because a local variable named `rescan` shadowed the method).

### Lint (runs in CI) and what CI does not run

`.github/workflows/lint.yml` lints `LabConstrictorTools.groovy` with `npm-groovy-lint` (pinned version, CodeNarc rules, config in `.groovylintrc.json`; each disabled rule has its reason there). It fails on any finding, including info. Run it locally (needs Node and Java):

    npx --yes npm-groovy-lint@18.0.0 --path src/main/resources/org/cellmigrationlab/labconstrictor/qupath --files "**/*.groovy" --failon info

The GUI tests above are **not** run in CI: they need a real QuPath (0.7, Java 25), a virtual display and registered LabConstrictor apps, which a GitHub runner does not have. Run them by hand with `tests/run_gui_test.sh` before merging a change to the script.

`tests/gui_test_body.groovy` drives the real dialog in QuPath on a virtual screen (project with two calibrated images, both apps) and writes screenshots.
