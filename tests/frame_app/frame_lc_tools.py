"""Test tool for the coordinate-frame tests (CF-01..14): it reports what it RECEIVED (image shape, pixel values, mask) and returns results at positions the test chooses.

The tool never sees a world, slide or viewer coordinate: it gets the exported file only (SPEC_WRONG_SCIENCE 1.1.1), so what it reports is the host's export."""
from typing import Annotated, Optional

import numpy as np
from labconstrictor_tools import ApplyTo, Image, LabelsOut, Labels, MessageOut, Name, PointsOut, RegionOf, Replace, Scalars, ShapesOut, tool


@tool("Frame probe")
def probe(
    image: Image,
    region: Annotated[Optional[Labels], RegionOf("image")] = None,
    y: float = 1.0,
    x: float = 2.0,
    labels_rows: int = 0,
    labels_cols: int = 0,
) -> tuple[
    Annotated[Scalars, Name("seen")],
    Annotated[PointsOut, Name("marks"), ApplyTo("image"), Replace()],
    Annotated[ShapesOut, Name("outline"), ApplyTo("image"), Replace()],
    Annotated[LabelsOut, Name("labels"), ApplyTo("image")],
    Annotated[MessageOut, Name("done")],
]:
    """Report the image and the mask it received; return one point at (y, x), the exact box around the masked pixels (or around the whole image without a mask) and a labels image."""
    arr = np.asarray(image)
    seen = {"image_shape": [int(n) for n in arr.shape], "image_first": int(arr.flat[0]), "image_last": int(arr.flat[-1])}
    outline_labels = np.ones(arr.shape[-2:], np.uint16)  # without a mask the outline is the box around the whole image the tool received
    if region is not None:
        mask = np.asarray(region) > 0
        seen.update({"mask_shape": [int(n) for n in mask.shape], "mask_pixels": [[int(r), int(c)] for r, c in zip(*np.nonzero(mask))], "mask_values": [int(v) for v in arr[..., :, :][mask]]})
        outline_labels = mask.astype(np.uint16)
    labels = np.ones((labels_rows or arr.shape[-2], labels_cols or arr.shape[-1]), np.uint16)
    rows, cols = np.nonzero(outline_labels)  # the outline is the exact box around the marked pixels (pixel edges at half pixels), not a simplified contour
    x0, x1, y0, y1 = cols.min() - 0.5, cols.max() + 0.5, rows.min() - 0.5, rows.max() + 0.5
    box = {"type": "FeatureCollection", "features": [{"type": "Feature", "properties": {"label": 1}, "geometry": {"type": "Polygon", "coordinates": [[[x0, y0], [x1, y0], [x1, y1], [x0, y1], [x0, y0]]]}}]}
    return seen, [{"y": float(y), "x": float(x)}], box, labels, "probed"
