# Notes for agents and contributors

LabConstrictor-QuPath is the QuPath 0.7 extension that runs the tools of installed LabConstrictor apps. How to build it and run the tests: [technical reference](docs/REFERENCE.md#build-from-source-and-test).

## Documentation

Each topic of the LabConstrictor ecosystem has exactly one owner page, and every other page links to it instead of copying it. The table of owners is in "Sources of truth" in [LabConstrictor-Site's AGENTS.md](https://github.com/CellMigrationLab/LabConstrictor-Site/blob/main/AGENTS.md).

- This repository owns the **QuPath guide**, `docs/user-guide.md`: what you need, the install steps, the menu path, the current version and the common problems. The site publishes it at <https://cellmigrationlab.github.io/LabConstrictor-Site/guides/qupath/>, so every link and picture in it must be an absolute address.
- `README.md` is a landing page: it gives one install line and links to the guide. It names no version of the extension.
- `docs/REFERENCE.md` holds the technical rules, the build and the tests. It links to the guide for installing and using the extension.
- When a release changes the current version, update it in `docs/user-guide.md` only.
- Write as the site does ([LabConstrictor-Site's DESIGN.md](https://github.com/CellMigrationLab/LabConstrictor-Site/blob/main/DESIGN.md)): sentence case, plain words, and link text that says where it goes.
