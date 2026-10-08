# Using LabConstrictor in QuPath

The QuPath extension runs registered LabConstrictor Python tools on image data selected in QuPath. It is currently a prototype targeting QuPath 0.7.

## Before you start

Install the QuPath extension and a registered LabConstrictor application. Open an image in QuPath. Use `labconstrictor-tools list` to inspect installed applications.

## Run a tool on a selected region

1. Select one or more annotations.
2. Open **Extensions > LabConstrictor tools...**.
3. Select an application and tool.
4. Choose **Current image** and **Selected annotation(s)** for the image area.
5. Run the tool and inspect the returned result.

A tool may instead accept a file or another project image. Image-area choices for the current image do not automatically apply to those sources.

## Image areas and export

### Whole image

Exports the full image at its native resolution, subject to the export-size limit.

### Selected annotations

Exports the bounding region of the selected annotation(s). The selected annotation mask can also be supplied separately when the tool declares `RegionOf(...)`.

### Current viewport

Exports the visible image region. Viewport size and zoom can affect the chosen area; check what is being analysed.

### Resolution and limits

QuPath exports at full resolution; it does not silently downsample. The default limit is 100 million pixels per plane. For larger regions, select a smaller area or provide an image file.

## Coordinates and results

The bridge keeps track of the exported region's position in the source image. Points and outlines associated with the current image are transformed back into slide coordinates.

| Return type | Current handling |
|---|---|
| Points | QuPath point annotations when associated with the open image |
| Outlines | QuPath annotations when associated with the open image |
| Image / labels | Preview or image path |
| Table | Result table |
| Values / message / file | Result display |

**Label images are not imported as native QuPath detections.** Tables are not added to object measurements. Do not interpret a displayed result as a persistent QuPath object unless it appears in the object hierarchy.

## Troubleshooting

If export fails, check the selected area, export size and calibration. If results are misplaced, check which input image the output refers to and whether the region was selected on the open image. Inspect the extension's error details and the shared LabConstrictor log.

## Maintainer checks

Test coordinate restoration with a region that starts away from (0, 0). Verify points, polygons, image previews, table display, multiple annotations, export guards and file-versus-current-image behavior. Record QuPath and Java versions and the operating system used for each test.
