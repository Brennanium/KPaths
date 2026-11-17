# Compiler snapshot utilities

This unpublished module contains the reusable comparison, file-backed snapshot recording, and
compiler-version selection currently used by the KPaths compiler fixture. It does not compile code,
load a compiler plugin, inspect FIR or IR, or provide a finished `assertCompiler` API. The caller
provides a capture function; a missing requested capture fails the assertion.

The existing inline recorder and compiler invocation are still in the KPaths fixture. These APIs
are public only so that fixture can consume this module; their names and package are provisional and
are not a published compatibility promise. See the Knapshots extraction handoff for the proposed
library boundary and the work needed for unified compiler and diagnostic assertions.

Run the tests from the root project with `./gradlew :compiler-testing:test`, or run the module as a
standalone Gradle build with `./gradlew -p compiler-testing test`.
