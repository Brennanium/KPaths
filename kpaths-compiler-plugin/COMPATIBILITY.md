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

To run the JVM matrix locally:

```sh
python3 scripts/verify-compiler-compatibility.py
```

Pass one or more matrix versions to restrict the run. On Apple Silicon macOS, add `--native` to
publish to a local test repository and run the iOS simulator tests too. A usable iOS simulator and
JDK are required. Example:

```sh
python3 scripts/verify-compiler-compatibility.py --native 2.4.20
```

CI executes the JVM and iOS consumer tests for each listed version independently. The iOS fixture
covers a focused runtime scenario; it is not a promise that every compiler-plugin feature has been
exercised on every target. The IDE has a separate compiler and compatibility story: the experimental
[KEFS fixture](../kpaths-kefs-ide-fixture/README.md) currently tests one Android Studio build and is
not part of this Kotlin compiler matrix. IDE-specific artifacts and versions require separate tests.

Before adding a version to the list, run the consumer tests with that version and the current
compiler plugin. The test harness accepts `-Pkpaths.testKotlinVersion=<version>` independently of
how KPaths itself was built. Add version-specific compatibility code when the tests expose an API
change; a version number alone does not establish compatibility. Pre-release compiler tests should
remain experimental until they pass and should not automatically expand the supported list.

The separate `gradle/kpaths-preview-kotlin-versions.txt` tracks development builds for early
warning. Run `python3 scripts/verify-compiler-compatibility.py --preview` to exercise the JVM
consumer and compiler contracts against them; the corresponding CI job is non-blocking. A passing
preview probe does not imply Native or IDE support. A Kotlin 2.5 development build exposed changes to FIR
type-parameter construction and lookup; KPaths uses a shared classifier factory to avoid depending
on the old implementation class. Development artifacts are resolved from JetBrains' test
repositories and may disappear or change availability.
