# LabConstrictor for QuPath

**Run registered Python tools on QuPath image regions.**

LabConstrictor connects QuPath to tools installed with LabConstrictor applications. You choose an image or region in QuPath, run a tool in the application's own Python environment, and inspect the results without moving your analysis code into QuPath.

This is a **prototype for QuPath 0.7**. It does not convert label images into native detections or add result tables to object measurements.

## Run a tool on an image region

1. Install and register a LabConstrictor application with image-analysis tools.
2. Open an image in QuPath and select one or more annotations.
3. Choose **Extensions > LabConstrictor tools...**.
4. Pick the application and tool, select **Current image** and set **Image area** to **Selected annotation(s)**.
5. Run the tool. The bridge exports the bounding region at full resolution, passes it to the tool and presents the results.

For tools returning points or outlines associated with the current image, the bridge adds the region's origin back to their coordinates. A point found inside a crop can therefore be placed at the correct position on the original slide.

You can also choose **Current viewport** or **Whole image**. The region chooser applies to the currently open QuPath image, not to arbitrary image files or other project images.

## What QuPath sends

The extension builds a form from the tool's declared Python inputs. It supports numbers, choices, text, files, folders, channels and selected regions where the tool declares them.

- **Image input:** use the current image, another image in the project or an image file. QuPath exports its own image data to TIFF.
- **Image area:** whole image, selected annotation bounding box or current viewport for the open image.
- **Selection mask:** when a tool declares `RegionOf(...)`, selected annotations can also be sent as a labelled mask over the chosen area.
- **Channel:** tools declaring `PickChannel()` can receive one selected channel.
- **Calibration:** pixel size from QuPath can fill the tool's calibration field.

When multiple inputs require different image areas, the bridge refuses the run instead of silently combining incompatible coordinate systems.

### Export size limit

Before exporting an image from QuPath, the extension checks the width × height of the exported area. The default limit is **100 million pixels per plane**. Above that, the run stops with an explanation and asks you to select a smaller area or use a file.

It does **not** silently downsample the image. Exports currently use full resolution. File paths supplied directly to a tool are passed through rather than subjected to QuPath's export-size check.

Advanced users can change the limit with the JVM property `lc.qupath.max_export_pixels`, but doing so can substantially increase memory use.

## What comes back

| Tool result | Current behavior |
|---|---|
| Points associated with the open image | QuPath point annotation at the correct image coordinates |
| Outlines associated with the open image | QuPath annotations with the region offset restored |
| Image or labels | Preview and path; images can be opened in QuPath |
| Table | Table in the result window |
| Values, files, alignment or messages | Displayed in the result window or status area |

**Important:** label images are not currently converted into native QuPath detections or annotations. Result tables are not written into object measurements. Results from a file or another project image are not silently placed on the current image.

## Install

First install and register the LabConstrictor application you want to use. You can inspect registration with:

```bash
labconstrictor-tools list
labconstrictor-tools doctor
```

Build or obtain the extension jar, then drop `labconstrictor-qupath-0.1.0.jar` onto the QuPath window (or place it in the extensions folder) and restart QuPath.

Choose **Extensions > LabConstrictor tools...**.

For development, the bridge can also be run from QuPath's Script Editor using:

```text
src/main/resources/org/cellmigrationlab/labconstrictor/qupath/LabConstrictorTools.groovy
```

QuPath does not need the application's Python packages. The bridge launches the tool worker in the registered application's interpreter.

## Limitations

- QuPath 0.7 is the current target; native Windows and macOS testing is still needed.
- Image export is full-resolution only. There is no user-selectable downsample or target pixel size yet.
- There is no headless/script API or menu entry for each individual tool.
- Dynamic choice lists may fall back to a text field if their source tool cannot answer; failures are reported.
- The **Copy as command** output cannot reproduce a QuPath annotation or viewport selection by itself. It warns when the command would instead send a whole file.
- Very large tables and object outputs have display limits. A preview is not the same as importing measurements or detections into the project.

The shared log is under `~/.labconstrictor/logs/` by default. Use **Details** when a tool fails, or run `labconstrictor-tools support-bundle` when reporting an issue.

For more detail, see [QuPath image regions and results](docs/USING_QUPATH.md).

## Applications

[Playground](https://github.com/CellMigrationLab/LabConstrictor-Playground) provides synthetic images and outputs for checking QuPath's bridge. Other installable applications, including [Guess the Condition](https://github.com/CellMigrationLab/GuessTheCondition), [NucleiSky](https://github.com/CellMigrationLab/NucleiSky), [VLab4Mic](https://github.com/CellMigrationLab/LabConstrictor-VLab4Mic) and [CellTracksColab](https://github.com/CellMigrationLab/CellTracksColab_LabConstrictor), are listed in the [Toolkit application list](https://github.com/CellMigrationLab/LabConstrictor-Tools#applications).

Their availability as desktop applications does **not** establish that their workflows have been tested in QuPath. Inspect the registered tools and check inputs, outputs and coordinate handling before using them on whole-slide data.

## For developers and testers

The main extension script is `src/main/resources/org/cellmigrationlab/labconstrictor/qupath/LabConstrictorTools.groovy`. It contains the registry reader, worker connection, form, image export, coordinate conversion and result presentation.

The GUI tests require a real QuPath installation. They were not run for this documentation change. To run them on Linux:

```bash
QUPATH=/path/to/QuPath JAVA_HOME=/path/to/jdk25 ./build.sh
QUPATH=/path/to/QuPath LC_HOME=/path/to/test-registry tests/run_gui_test.sh
```

Additional test bodies cover image areas, export limits, interaction hints, channels, fallbacks and Playground stress cases. See `tests/` for the scripts and fixtures.

Related projects: [Toolkit](https://github.com/CellMigrationLab/LabConstrictor-Tools) · [Fiji](https://github.com/CellMigrationLab/LabConstrictor-Fiji) · [Napari](https://github.com/CellMigrationLab/napari-labconstrictor) · [Playground](https://github.com/CellMigrationLab/LabConstrictor-Playground).
