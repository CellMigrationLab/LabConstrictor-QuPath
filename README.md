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
* **Image inputs**: the image open in QuPath, any image of the open project, or a file. QuPath's image is exported losslessly as TIFF (whole image). The pixel size of the chosen image fills the tool's pixel-size field (micrometres).
* Results open in a window: values, tables (as a table), images (path and an "Open in QuPath" button), alignment matrices, files.
* "No match" is a message, not an error. Progress bar, Cancel (a tool that ignores it is killed after 3 s), worker kept between runs (or not), details of the last run, no worker left behind when the window closes.
* Results are kept in `~/.labconstrictor/results/` (the newest 20), like the command line.

## Not done yet
* Only whole images are exported (no region or annotation selection); RGB / brightfield images are exported as RGB TIFF.
* Label images are not converted to QuPath annotations/detections and tables are not added to the measurements: the outputs are shown, not imported. That is the next step for a useful QuPath integration.
* No headless/script API, no Windows/macOS testing, no menu entry per tool.

## Build and test
    QUPATH=/path/to/QuPath JAVA_HOME=<JDK 25> ./build.sh        # QuPath 0.7 is built with Java 25
    QUPATH=/path/to/QuPath LC_HOME=<registry with NucleiSky and CellTracksColab> tests/run_gui_test.sh   # needs xvfb; 21 checks

`tests/gui_test_body.groovy` drives the real dialog in QuPath on a virtual screen (project with two calibrated images, both apps) and writes screenshots.
