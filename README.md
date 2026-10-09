# LabConstrictor for QuPath

Run tools from any **registered LabConstrictor application** on QuPath images. QuPath reads the application's tool manifest, builds a form and launches the tool in its own Python environment. Applications do not need separate QuPath plugins.

This is a **prototype targeting QuPath 0.7**. See [Using LabConstrictor in QuPath](docs/USING_QUPATH.md) for image-area choices, coordinates, results and limitations.

## Start here

1. Install a [LabConstrictor application](#applications) with registered tools. Check it using `labconstrictor-tools list` and `labconstrictor-tools doctor`.
2. Build the QuPath extension (see below), or obtain its JAR if available. Install it by dragging the JAR onto QuPath's main window or copying it to the QuPath user extensions directory. Restart QuPath.
3. Open an image and choose **Extensions > LabConstrictor tools...**.
4. Select an application and tool. For a region-based run, select an annotation, choose **Current image**, and set **Image area** to **Selected annotation(s)**.
5. Inspect the result window and any annotations created in the image hierarchy.

**Selected annotation(s)** exports the **bounding rectangle** of the selected objects, not just pixels inside their shapes. A separate `RegionOf(...)` input can supply a labelled selection mask if the tool declares one. The extension exports at full resolution, with a default limit of **100 million pixels per plane**.

Points and outlines associated with the image open in QuPath can become annotations in slide coordinates. Image and label results have previews, but **label images are not converted to native detections**, and result tables do not automatically become object measurements.

Do not switch the active QuPath image or change its selection during a running analysis until [run-state handling](https://github.com/CellMigrationLab/LabConstrictor-QuPath/issues/16) is corrected. Check the pixel-size field whenever you change image sources ([calibration issue](https://github.com/CellMigrationLab/LabConstrictor-QuPath/issues/15)).

## Install or build

The extension is packaged as `labconstrictor-qupath-0.1.0.jar`. For a development build against an installed QuPath 0.7 and JDK 25:

```bash
QUPATH=/path/to/QuPath JAVA_HOME=/path/to/jdk25 ./build.sh
```

The JAR is written to `target/labconstrictor-qupath-0.1.0.jar`. Follow [QuPath's manual extension installation instructions](https://qupath.readthedocs.io/en/stable/docs/intro/extensions.html#installing-extensions-manually) to install it. Alternatively, developers can open `src/main/resources/org/cellmigrationlab/labconstrictor/qupath/LabConstrictorTools.groovy` in QuPath's Script Editor.

For troubleshooting, check the extension's **Details** window and the LabConstrictor log under `~/.labconstrictor/logs/` (or the configured `LC_HOME`). See the [user guide](docs/USING_QUPATH.md).

## Applications

The manifest makes the bridge **application-independent**; the list below is a set of examples, not a compatibility allowlist. Choose tools according to how useful their inputs and outputs are in QuPath.

- [LabConstrictor Playground](https://github.com/CellMigrationLab/LabConstrictor-Playground) — test images and bridge diagnostics
- [NucleiSky](https://github.com/CellMigrationLab/NucleiSky) — microscopy-image registration
- [VLab4Mic desktop](https://github.com/CellMigrationLab/LabConstrictor-VLab4Mic) — fluorescence-image simulation
- [CellTracksColab desktop](https://github.com/CellMigrationLab/CellTracksColab_LabConstrictor) — cell-track analysis
- [Guess the Condition](https://github.com/CellMigrationLab/GuessTheCondition) — blinded microscopy-image classification

Each application must be installed and its tool module registered before its tools appear. The [LabConstrictor Toolkit](https://github.com/CellMigrationLab/LabConstrictor-Tools) documents the shared manifest and registration.

## Development and tests

The QuPath implementation is in `src/main/resources/org/cellmigrationlab/labconstrictor/qupath/LabConstrictorTools.groovy`. The extension entry point is `src/main/java/org/cellmigrationlab/labconstrictor/qupath/LabConstrictorExtension.java`.

The GUI test harness requires QuPath, Java and (on Linux) `xvfb-run`. **It alters the selected QuPath installation** by copying the JAR into `lib/app` and modifying `lib/app/QuPath.cfg`; use a disposable test installation:

```bash
QUPATH=/path/to/disposable-QuPath JAVA_HOME=/path/to/jdk25 ./build.sh
QUPATH=/path/to/disposable-QuPath LC_HOME=/path/to/test-registry tests/run_gui_test.sh
```

See `tests/` for test bodies and fixtures. The GUI tests were not run for this documentation change. The extension's [open issues](https://github.com/CellMigrationLab/LabConstrictor-QuPath/issues) record implementation work.

Related hosts: [Fiji](https://github.com/CellMigrationLab/LabConstrictor-Fiji) · [Napari](https://github.com/CellMigrationLab/napari-labconstrictor).
