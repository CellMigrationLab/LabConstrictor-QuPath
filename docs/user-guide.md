# Install the QuPath extension

The extension runs the tools of every installed LabConstrictor app from QuPath, with a form generated from each tool's declaration.

> [!WARNING]
> **Under heavy construction.** This plugin is untested and unlikely to be stable. Expect bugs, missing features and changes without notice. Do not rely on it for work you cannot redo.
>
> For QuPath 0.7 (tested with 0.7.0 on Linux; not tested on Windows or macOS). The jar is attached to each release; until the first release is published, build it from the repository.

## What you need

- [QuPath](https://qupath.github.io) 0.7.
- At least one LabConstrictor app installed on your computer. The app's installer registers it, which is how the extension finds it.
- Only to build the jar yourself: JDK 25 (QuPath 0.7 is built with Java 25), as described in the repository's README.

## Install

1. Download `labconstrictor-qupath-<tag>.jar` from the [Releases page](https://github.com/CellMigrationLab/LabConstrictor-QuPath/releases).
2. Drop the jar onto the QuPath window, or put it in the QuPath extensions folder (shown in QuPath's Preferences), and restart QuPath.

Without the jar: download `LabConstrictorTools-all.groovy` from the same release, open it in QuPath's Script Editor and run it.

## Open it

Choose **Extensions > LabConstrictor tools...**, pick an app and a tool, and press Run.

- An image input can be the image open in QuPath, any image of the open project, or a file.
- For large images, choose the **Image area**: the whole image, the selected annotations, or the current viewport. Results come back on the right place of the slide.
- Points and outlines found in the open image become annotations named after the app and the output.
- Results open in a window. Cancel stops a run.

## If something does not work

- The status line and **Details** show what failed. The log is `~/.labconstrictor/logs/labconstrictor.log`.
- Results and run records are kept in `~/.labconstrictor/results` and `~/.labconstrictor/runs`. The extension never deletes them; after each run it starts `labconstrictor-tools prune`, which keeps the newest 20 results and 50 run records and removes only folders LabConstrictor made and finished. Folders from older versions of the extension are not removed automatically.
- The app's `labconstrictor-tools` must be 0.3.0a1 or newer; with an older one the run is refused and the status line says to update it.
- If you open another image while a tool runs, the results are not drawn on the new image: the status line says where the files are.
- An app whose `labconstrictor-tools` is older than 0.3.0a1 is not offered; the status line (or the list of skipped apps after **Rescan apps**) says to update it.
- A number you type that is outside the allowed range (or is not a number) is refused with a sentence like `'Threshold' must be <= 10`; it is never changed to fit, and the run does not start.
- `labconstrictor-tools doctor` shows which apps are registered.

Source and issues: [LabConstrictor-QuPath](https://github.com/CellMigrationLab/LabConstrictor-QuPath).
