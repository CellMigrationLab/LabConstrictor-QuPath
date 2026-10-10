# Install the QuPath extension

The extension runs the tools of every installed LabConstrictor app from QuPath, with a form generated from each tool's declaration.

> [!WARNING]
> **Under heavy construction.** This plugin is untested and unlikely to be stable. Expect bugs, missing features and changes without notice. Do not rely on it for work you cannot redo.
>
> For QuPath 0.7. There is no ready-made file yet; the jar is built from its repository.

## What you need

- [QuPath](https://qupath.github.io) 0.7.
- At least one LabConstrictor app installed on your computer. The app's installer registers it, which is how the extension finds it.
- To build the jar: JDK 25 (QuPath 0.7 is built with Java 25), as described in the repository's README.

## Install

1. Get the [LabConstrictor-QuPath](https://github.com/CellMigrationLab/LabConstrictor-QuPath) repository and build `labconstrictor-qupath-0.1.0.jar` as its README describes.
2. Drop the jar onto the QuPath window, or put it in the QuPath extensions folder, and restart QuPath.

Without the jar: open `LabConstrictorTools.groovy` (under `src/main/resources/org/cellmigrationlab/labconstrictor/qupath/`) in QuPath's Script Editor and run it.

## Open it

Choose **Extensions > LabConstrictor tools...**, pick an app and a tool, and press Run.

- An image input can be the image open in QuPath, any image of the open project, or a file.
- For large images, choose the **Image area**: the whole image, the selected annotations, or the current viewport. Results come back on the right place of the slide.
- Points and outlines found in the open image become annotations named after the app and the output.
- Results open in a window. Cancel stops a run.

## If something does not work

- The status line and **Details** show what failed. The log is `~/.labconstrictor/logs/labconstrictor.log`.
- `labconstrictor-tools doctor` shows which apps are registered.

Source and issues: [LabConstrictor-QuPath](https://github.com/CellMigrationLab/LabConstrictor-QuPath).
