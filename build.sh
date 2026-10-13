#!/bin/bash
# Builds target/labconstrictor-qupath-<version>.jar against a QuPath installation:  QUPATH=/path/to/QuPath ./build.sh
# The version is 0.1.0 unless LC_QUPATH_VERSION is set (the release workflow sets it to the release tag); it is the file name and the
# Implementation-Version of the jar's manifest.
set -euo pipefail
cd "$(dirname "$0")"
QUPATH="${QUPATH:?set QUPATH to the QuPath folder (the one containing lib/app)}"
VERSION="${LC_QUPATH_VERSION:-0.1.0}"
rm -rf target/classes && mkdir -p target/classes
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -cp "$QUPATH/lib/app/*" -d target/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/* target/classes/
printf 'Implementation-Title: LabConstrictor-QuPath\nImplementation-Version: %s\n' "$VERSION" > target/manifest.txt
"${JAVA_HOME:+$JAVA_HOME/bin/}jar" cfm "target/labconstrictor-qupath-$VERSION.jar" target/manifest.txt -C target/classes .
echo "built target/labconstrictor-qupath-$VERSION.jar"
# one file with the whole script, for QuPath's Script Editor (the jar joins the files listed in parts.txt itself)
RES=src/main/resources/org/cellmigrationlab/labconstrictor/qupath
{ while IFS= read -r part || [ -n "$part" ]; do [ -z "$part" ] || { cat "$RES/$part"; echo; }; done < "$RES/parts.txt"; } > target/LabConstrictorTools-all.groovy
echo "built target/LabConstrictorTools-all.groovy (all parts in one file, for the Script Editor)"
