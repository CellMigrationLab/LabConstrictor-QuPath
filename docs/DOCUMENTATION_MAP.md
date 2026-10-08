# Documentation map

Selected regions and correct slide coordinates.

The [README](../README.md) is the entry point. Detailed material belongs in the sections below. This map records the intended structure; a named page may still need to be created or updated by the implementation maintainers.

| Section | Intended file | Scope |
|---|---|---|
| Overview and first run | `README.md` | Selected annotation workflow and expected result |
| Image export | `docs/IMAGE_AREAS.md` | Whole image, selected annotations, viewport, resolution, calibration, limits |
| Results and coordinates | `docs/RESULTS.md` | Coordinate transforms, native objects versus previews, tables, limits |
| Install and troubleshoot | `docs/INSTALL.md` | Extension/script, QuPath/JDK versions, registry, logs |
| Testing | `docs/TESTING.md` | GUI harness, region fixtures, platform matrix, unsupported workflows |

## Maintenance

Coordinate frames and whether results become native QuPath objects are the central contract. Code owners should check each output type and export guard against code.

When changing a tool or host behavior, update the relevant section in the same PR. Prefer one tested example to several unverified ones. Mark unsupported behavior explicitly; do not turn planned features into present-tense claims.
