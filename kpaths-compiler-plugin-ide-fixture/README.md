# Android Studio compiler-plugin fixture

This standalone test project checks KPaths editor analysis using a local Android Studio installation.
It compiles an IDE-specific copy of the compiler plugin against the Kotlin compiler bundled with that
IDE. A **test-only** IDE provider substitutes that copy for the compiler plugin JAR requested by the
project. The tests verify that the provider is called and that generated declarations, diagnostics,
and a negative Kotlin control are highlighted as expected.

This provider is a small test double for the `bundledFirCompilerPluginProvider` extension. It is not
an installable KPaths IDE integration and does not verify Kotlin External FIR Support, Gradle project
import, or Marketplace distribution. Most fixture tests use minimal core API stubs; a separate highlighting test uses the actual core
and annotations JARs. Runtime and cross-module behavior are tested by the other compiler-plugin fixtures.

With JDK 17 for Gradle and JDK 25 available as a toolchain:

```sh
./gradlew :kpaths-compiler-plugin:jvmJar :kpaths-core:jvmJar :kpaths-annotations:jvmJar
./gradlew -p kpaths-compiler-plugin-ide-fixture test -Pkpaths.idePath="/path/to/Android Studio.app"
```

The build JAR defaults to the current version under `kpaths-compiler-plugin/build/libs`;
set `-Pkpaths.buildCompilerPluginJar=/path/to/jar` when testing a different artifact version.
The IDE source copy currently adapts `FirResolvedQualifier.qualifierSymbol` to the local IDE's
`symbol` API. This is a version-specific experiment, not a general compiler compatibility layer.
The test was verified against Android Studio 2026.2.2 (build 262.10968.63.2622.16730706).

## Next integration step

A Gradle consumer's compiler JAR is not loaded by the IDE by default. An installable provider must
recognize the consumer's build artifact, resolve a KPaths compiler JAR built for that IDE's Kotlin
compiler, and return it to the IDE. The [Kotlin External FIR Support developer guide](https://github.com/Mr3zee/Kotlin-External-FIR-Support/blob/main/PLUGIN_AUTHORS.md)
describes one possible distribution and resolution strategy. Its default artifact convention is
`<ide-kotlin-version>-<library-version>`; it also supports configurable matching. Its Android Studio
version mapping and Maven metadata requirements must be verified before choosing that approach.

In particular, this fixture's direct local provider deliberately avoids artifact resolution, Kotlin
version discovery, Gradle import, and plugin installation. Those parts require their own end-to-end
checks in an isolated Android Studio sandbox before making IDE support available to users.
