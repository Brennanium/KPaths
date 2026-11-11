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

To run the supported matrix locally:

```sh
python3 scripts/verify-compiler-compatibility.py
```

Pass any exact versions to test them, including versions outside the supported matrix. A quoted
development selector resolves to the highest numbered build available for both the Kotlin compiler
and Gradle plugin in JetBrains' development repositories:

```sh
python3 scripts/verify-compiler-compatibility.py 2.4.20 '2.5.0-dev*'
```

The script prints the exact resolved versions and runs the compiler snippet, diagnostic, snapshot,
bytecode and Gradle consumer suites, followed by a separate JVM consumer compiled and run with each
selected version. On Apple Silicon macOS with an available iOS simulator, it also runs the Native
consumer for each version. Use `--native` to require that check, `--jvm-only` to skip it,
`--resolve-only` to inspect selectors without running tests, and `--report-json <file>` to save
the results. A JDK is required. Gradle caches and a local test repository are used for the builds.

CI executes the JVM and iOS consumer tests for each listed version independently. The Verify workflow
can also be started manually with a space- or comma-separated list of exact versions and development
selectors; its summary records the resolved versions. The iOS fixture
covers a focused runtime scenario; it is not a promise that every compiler-plugin feature has been
exercised on every target. The IDE has a separate compiler and compatibility story: the experimental
[KEFS fixture](../kpaths-kefs-ide-fixture/README.md) currently tests one Android Studio build and is
not part of this Kotlin compiler matrix. IDE-specific artifacts and versions require separate tests.
The fixture maintains its own [IDE compatibility matrix](../kpaths-kefs-ide-fixture/ide-compatibility.json)
and an automated local verification workflow for the selected IDE.

Before adding a version to the list, run the consumer tests with that version and the current
compiler plugin. The test harness accepts `-Pkpaths.testKotlinVersion=<version>` independently of
how KPaths itself was built. Add version-specific compatibility code when the tests expose an API
change; a version number alone does not establish compatibility. Pre-release compiler tests should
remain experimental until they pass and should not automatically expand the supported list.

The separate `gradle/kpaths-preview-kotlin-versions.txt` tracks development families for early
warning. Run `python3 scripts/verify-compiler-compatibility.py --preview` to test the latest available
builds; the corresponding CI job is non-blocking and runs the JVM checks. The local script includes
Native when available. A passing JVM preview probe does not imply Native or IDE support. A Kotlin 2.5 development build exposed changes to FIR
type-parameter construction and lookup; KPaths uses a shared classifier factory to avoid depending
on the old implementation class. Development artifacts are resolved from JetBrains' test
repositories and may disappear or change availability.

The in-process test harness needs a KAPT artifact even though these tests do not run annotation
processors. It uses the KPaths build's KAPT version while loading and checking the selected Kotlin
compiler version; the matrix does not verify compatibility with third-party annotation processors.
