# KEFS integration experiment

This standalone test project installs Kotlin External FIR Support (KEFS) 0.5.0-262 into an
isolated Android Studio test sandbox. It does not install or change the plugin in the user's IDE.

The test builds IDE-compatible copies of KPaths and the independent contributor from the current
checkout against the selected Android Studio's bundled Kotlin compiler. It writes them to a
throwaway local Maven-style repository with the IDE-versioned artifact names and Maven metadata
expected by KEFS. It configures the real KEFS provider with two separate plugin descriptors,
waits for KEFS to resolve both artifacts, then asks IDE analysis to highlight source using both:

* A normal `@Pathable` property and case payload.
* `TrackedRecord.Draft`, a type synthesized by the separate contributor, and its generated
  `Paths.name` and `Paths.address` properties, which the contributor delegates to KPaths.

For the separate-bundle test, the same 0.1.0 contributor build is given an artificial 0.2.0-based Maven
version. Unique test-version suffixes prevent KEFS from reusing a previously cached artifact, and
the test compares the resolved JARs to the freshly built IDE artifacts. This checks independent version resolution, not compatibility with an actual 0.2.0 release.
The setup calls KEFS internals by reflection solely to configure this fixture; it is not an intended
consumer API or installation flow. The fixture sets IDE compiler arguments directly; it does not
verify Gradle import, marketplace publication, automatic KEFS configuration, or the real future
third-party compiler plugin. It runs compiler front-end analysis, not generated runtime behavior.

Verified with Android Studio 2026.2.2 (build 262.10968.63.2622.16730706), whose KEFS-resolved
compiler version is `2.4.20-ij262-52`. Other versions must be tested separately.

From the KPaths root, with JDK 17 for Gradle and a JDK 25 toolchain available:

```sh
./gradlew :kpaths-compiler-plugin:jvmJar :kpaths-compiler-plugin-contributor:jvmJar :kpaths-core:jvmJar :kpaths-annotations:jvmJar
./gradlew -p kpaths-kefs-ide-fixture test --tests '*KefsIntegrationTest' -Pkpaths.idePath="/path/to/Android Studio.app"
```
