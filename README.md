# LabConstrictor for QuPath

Run the analysis tools of your LabConstrictor apps from inside [QuPath](https://qupath.github.io). One window lists every installed app: pick an app and a tool, and run it on the image that is open. Outlines and points come back as QuPath annotations on your image; tables, values and result images open in a results window. The analysis runs in the app's own Python environment, so QuPath needs no Python setup.

![QuPath with an H&E image: nuclei found by the StarDist2D app are drawn as annotations, next to the LabConstrictor tools window](docs/img/qupath-stardist.png)

*The StarDist2D app's "Segment nuclei (H&E / RGB)" tool, run from QuPath: the nuclei it found are annotations on the open image.*

> **Experimental.** This is an alpha pre-release for **QuPath 0.7** (built and tested with QuPath 0.7.0; older QuPath versions are not supported). It has been tested on Linux, Windows and macOS, but expect bugs and changes. Please [tell us](https://github.com/CellMigrationLab/LabConstrictor-QuPath/issues) what you find.

## Install

Drag the jar from the [Releases page](https://github.com/CellMigrationLab/LabConstrictor-QuPath/releases) onto QuPath and restart it. Every step, what you need first and what to do when something goes wrong: [QuPath guide](https://cellmigrationlab.github.io/LabConstrictor-Site/guides/qupath/).

## Apps that work in QuPath

| App | What it does |
|---|---|
| [StarDist2D](https://cellmigrationlab.github.io/LabConstrictor-Site/apps/stardist2d/) | Segment nuclei and cells in 2D images with StarDist |
| [NucleiSky](https://cellmigrationlab.github.io/LabConstrictor-Site/apps/nucleisky/) | Align microscopy images by matching the pattern of their nuclei |
| [Guess the Condition](https://cellmigrationlab.github.io/LabConstrictor-Site/apps/guessthecondition/) | A blinded test of whether conditions can be told apart by eye |
| [Playground](https://cellmigrationlab.github.io/LabConstrictor-Site/apps/labconstrictor-playground/) | Check your computer and try every feature of the extension |

Any LabConstrictor app that declares QuPath as one of its hosts appears in the window; no extra extension is needed per app. The [App Centre](https://cellmigrationlab.github.io/LabConstrictor-Site/apps/?host=qupath) has the current list.

## Getting help

- **Something does not work?** The [QuPath guide](https://cellmigrationlab.github.io/LabConstrictor-Site/guides/qupath/#if-something-does-not-work) lists the common problems and what to do.
- **Guides:** the [LabConstrictor website](https://cellmigrationlab.github.io/LabConstrictor-Site/) and its [guides](https://cellmigrationlab.github.io/LabConstrictor-Site/guides/).
- **Bugs and questions:** the [issue tracker](https://github.com/CellMigrationLab/LabConstrictor-QuPath/issues).

## More detail

- [Technical reference](docs/REFERENCE.md): every rule the extension follows (large images and the Image area, pixel size, channels, annotations, number limits) and what is not done yet.
- The same apps also run in [napari](https://github.com/CellMigrationLab/LabConstrictor-napari) and [Fiji](https://github.com/CellMigrationLab/LabConstrictor-Fiji). To make your own app, start at the [LabConstrictor website](https://cellmigrationlab.github.io/LabConstrictor-Site/).

## For developers

Build the jar with JDK 25 (QuPath 0.7 is built with Java 25), then run a GUI test on a virtual screen (Linux):

```bash
QUPATH=/path/to/QuPath JAVA_HOME=/path/to/jdk-25 ./build.sh
tests/ci_setup_apps.sh /tmp/lc main                                   # test apps and a private registry
QUPATH=/path/to/QuPath LC_HOME=/tmp/lc/home tests/run_gui_ci.sh       # every GUI test
```

The GUI tests change the QuPath installation they are pointed at, so use a disposable copy. What each test covers, and the lint: [technical reference](docs/REFERENCE.md#build-from-source-and-test).
