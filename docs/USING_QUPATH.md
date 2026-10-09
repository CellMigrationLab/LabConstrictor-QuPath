# Using LabConstrictor in QuPath

LabConstrictor reads the tool manifest of any registered scientific application. The same application can be accessed from other hosts; in QuPath, the useful workflows are generally those involving image regions, annotations and spatial results.

This guide describes the current QuPath 0.7 prototype. For installation, build instructions and application links, start with the [README](../README.md).

## Run a tool

1. Open an image in QuPath.
2. Select one or more annotations if you want to work on a smaller area.
3. Choose **Extensions > LabConstrictor tools...**, select an application and tool.
4. Select **Current image** for the image input. Set **Image area** to **Selected annotation(s)** to export their bounding rectangle, or choose another area.
5. Review parameters, especially pixel size, then run the tool.
6. Inspect the result window and any annotations added to the project.

An annotation selection does not automatically restrict the analysis to pixels inside its shapes: the **Image area** setting exports their bounding box. If a tool declares a separate `RegionOf(...)` labels input, the selected annotations can also be sent as a mask.

## Image inputs and areas

| Image source | Available area |
|---|---|
| Current image | Whole image, selected annotations or current viewport |
| Another project image | Whole image |
| File | File passed directly, unless channel extraction requires QuPath to export it |

**Whole image** exports the full-resolution image, using the first Z/T plane in the current implementation.

**Selected annotation(s)** exports the bounding rectangle of the chosen annotations, rounded to pixel boundaries and clipped to the image. The exported Z/T plane follows the viewer. Selections spanning different Z/T planes require particular care: the bounding-box calculation does not filter the selected annotations by plane.

**Current viewport** exports its visible bounds, clipped to the image, at full resolution. The viewer's Z/T plane is used.

The default export guard is **100 million pixels per plane**. This limits host-side image exports, not image files passed as paths without export. The system property `lc.qupath.max_export_pixels` can change the budget; higher limits require more memory. There is no automatic downsampling.

### Multiple image inputs

A tool can receive multiple images. When using cropped areas, the current bridge requires their spatial areas to agree, since it supports a single coordinate offset for outputs. A mixed cropped/whole-image run is refused. Check the result's `apply_to` association before interpreting spatial data.

### Calibration

A tool may declare `PixelSizeOf(...)` to prefill its pixel-size parameter from the chosen image. **Check the value after changing source**, particularly when switching to a file or an image without usable calibration. Until [issue #15](https://github.com/CellMigrationLab/LabConstrictor-QuPath/issues/15) is fixed, the field may retain an earlier value.

## Results and coordinates

The bridge stores the selected area's origin and uses it to map local pixel coordinates from results back to full-resolution slide coordinates.

| Output | Current behavior |
|---|---|
| Points | Point annotation on the open image, when associated with it; otherwise a table |
| Shapes | Polygon annotations, including holes and numeric feature measurements, when associated with the open image |
| Image and labels | Preview, path and an option to open the image |
| Table | View in the results window, with a display row limit |
| Affine | Matrix display (not an applied image transform) |
| Values and messages | Results panel or status |

A label-image preview is **not** an imported QuPath detection or annotation, and tabular outputs are **not** copied into measurements automatically. Points and shapes are persistent objects only when added to the image hierarchy.

**Do not change the active QuPath image or selected annotations while a tool is running.** Currently the result import and some input lookups access live QuPath state rather than the exact image/selection used to start the run. See [issue #16](https://github.com/CellMigrationLab/LabConstrictor-QuPath/issues/16).

### Checking placement

For spatial tools, inspect the original image and confirm that annotations are positioned where expected. A useful regression case places an annotation away from the origin, crops around it, then checks the restored coordinates.

## Troubleshooting

If no application appears, check its registration with `labconstrictor-tools list` and `labconstrictor-tools doctor`.

If export fails, check the area size and whether annotations are selected. If a worker fails, inspect **Details** and the shared LabConstrictor log. If a table is malformed, preserve the source CSV for an issue report: QuPath currently reads CSV by physical lines and [does not support multiline quoted cells correctly](https://github.com/CellMigrationLab/LabConstrictor-QuPath/issues/17).

The **Copy as command** feature cannot reproduce a selected annotation, viewport crop or channel extraction by itself; it adds explanatory notes and uses a whole-file path where possible.

## Maintainer regression checks

Verify the areas, pixel calibration, selected region masks, point and polygon offsets, Z/T planes, multiple image inputs, output types, cancellation and background worker state. Include a long-running test that switches active images before the result returns, and a multiline CSV case. Record QuPath, Java and operating-system versions used.

The test harness is documented in the [README](../README.md). It modifies the QuPath installation selected for testing, so use a disposable copy.
