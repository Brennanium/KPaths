# Compiler compatibility

The experimental KPaths compiler plugin is tested with Kotlin **2.4.20 and 2.4.21**. The
tested versions live in `gradle/kpaths-supported-kotlin-versions.txt`; CI reads that list to create
its compiler test matrix. Other versions are not blocked, but their compatibility has not been
established. The plain KPaths APIs and KSP processor have their own
compatibility requirements.

KPaths is built using the Kotlin version in `gradle/libs.versions.toml`. Each matrix entry tests
the **same built compiler plugin** in a separate consumer configured with the selected Kotlin
version. The Gradle consumer asserts the version actually resolved, preventing transitive Gradle
plugin dependencies from silently upgrading it. The JVM checks include copy-based writable paths,
case embedding, generated catalogs, generic paths, IR snapshots, and selected bytecode contracts.
The iOS simulator fixture tests generated writable and case paths using the same published compiler
plugin and runtime libraries, and also asserts the resolved Kotlin version.

Run individual Kotlin versions or development families with Knapshots:

```sh
./gradlew :kpaths-compatibility:compilerCompatibility --kotlin-version 2.4.21 --platform jvm
./gradlew :kpaths-compatibility:compilerCompatibility --kotlin-version 2.4.20 --kotlin-version '2.5.0-dev*' --platform jvm,native
```

A development selector resolves to the latest build available for both the Kotlin compiler and Gradle plugin. The JVM driver runs the compiler snippet, diagnostic, snapshot, bytecode, and downstream consumer suites. The Native driver runs the iOS simulator consumer and requires an Apple Silicon Mac with an available simulator. Reports identify each selected version and check in `kpaths-compatibility/build/reports/knapshots/compiler-compatibility.json`.

CI tests the listed stable selectors on JVM and Native independently. The separate `gradle/kpaths-preview-kotlin-versions.txt` tracks development families for non-blocking JVM probes. A passing preview probe does not establish Native or IDE support.

Android Studio uses a separate bundled Kotlin compiler. The [KEFS fixture](../kpaths-kefs-ide-fixture/README.md) and `editorCompatibility` task test that combination independently; see the [compatibility testing guide](../scripts/README.md).

Before adding a version to the supported list, run the consumer tests with that version and the current compiler plugin. The test harness accepts `-Pkpaths.testKotlinVersion=<version>` independently of how KPaths itself was built. A version number alone does not establish compatibility.
