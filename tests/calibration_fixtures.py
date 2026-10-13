"""Writes the images of the calibration tests (tests/gui_test_calibration_body.groovy) from a JSON list: python tests/calibration_fixtures.py spec.json
Each entry: {"file": path, "kind": "ome" | "imagej" | "tags" | "plain", ...}. `ome`: sx, ux, sy, uy (PhysicalSize and its unit text, kept exactly);
`imagej`: unit, resolution; `tags`: resolution (pixels per resolution unit, x then y), resolutionunit (INCH, CENTIMETER, NONE), optional bigtiff / byteorder; `plain`: nothing."""
import json
import sys

import numpy as np
import tifffile


def main(spec_path: str) -> None:
    image = np.arange(8 * 12, dtype=np.uint16).reshape(8, 12)
    for item in json.load(open(spec_path, encoding="utf-8")):
        kind = item["kind"]
        if kind == "ome":
            meta = {"axes": "YX", "PhysicalSizeX": item["sx"], "PhysicalSizeXUnit": item["ux"]}
            if item.get("sy") is not None:
                meta.update({"PhysicalSizeY": item["sy"], "PhysicalSizeYUnit": item["uy"]})
            tifffile.imwrite(item["file"], image, ome=True, metadata=meta)
        elif kind == "imagej":
            tifffile.imwrite(item["file"], image, imagej=True, resolution=tuple(item["resolution"]), metadata={"unit": item["unit"]})
        elif kind == "tags":
            extra = {k: item[k] for k in ("bigtiff", "byteorder") if k in item}  # BigTIFF and big-endian files: the header reader of the host must handle them
            tifffile.imwrite(item["file"], image, resolution=tuple(item["resolution"]), resolutionunit=item["resolutionunit"], **extra)
        else:
            tifffile.imwrite(item["file"], image)


if __name__ == "__main__":
    main(sys.argv[1])
