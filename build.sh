#!/bin/bash
# Builds target/labconstrictor-qupath-0.1.0.jar against a QuPath installation:  QUPATH=/path/to/QuPath ./build.sh
set -euo pipefail
cd "$(dirname "$0")"
QUPATH="${QUPATH:?set QUPATH to the QuPath folder (the one containing lib/app)}"
rm -rf target/classes && mkdir -p target/classes
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -cp "$QUPATH/lib/app/*" -d target/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/* target/classes/
"${JAVA_HOME:+$JAVA_HOME/bin/}jar" cf target/labconstrictor-qupath-0.1.0.jar -C target/classes .
echo "built target/labconstrictor-qupath-0.1.0.jar"
