# KEFS integration experiment

For the proposed release repository and the non-publishing CI draft, see
[IDE artifact release strategy](ide-artifact-release-strategy.md).

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

The tested IDE, compiler, and KEFS versions are listed in
[`ide-compatibility.json`](ide-compatibility.json). Like the compiler matrix, this is a record of
tested combinations, not a restriction on other IDEs. To build and verify the local IDE artifact
and a standalone Gradle consumer, run from the repository root:

```sh
python3 scripts/verify-kefs-compatibility.py --ide-path "/path/to/Android Studio.app"
```

For an unlisted IDE build, also pass `--ide-kotlin-version` (from **KEFS: Copy Kotlin IDE Version**)
and `--kefs-version`. Add `--ide-probe` to launch an isolated IDE twice and check the imported
consumer with KEFS enabled and disabled. The script restores the fixture's KEFS settings afterward.
It writes only to local build repositories; it does not publish a release. It needs a JDK 17 Gradle
runtime and a JDK 25 toolchain for the currently tested IDE fixture.

The local publication task runs the fixture's KEFS integration test first. The test compares the
requested artifact version to the compiler version reported by KEFS, so an artifact is not published
under a mismatched IDE version through this workflow. This verification assumes Gradle's normal
task dependencies are not explicitly bypassed.

The separate [artifact reuse research](range-compatibility-research.md) records experiments with
two IDE compiler builds, an older IDE's case-inlay failure, and KEFS's internal DevKit detection.
Those experiments do not expand the supported matrix or change the publication format.

## Consumer setup after publication

Once an IDE-compatible KPaths artifact is published to Maven Central, an IDE user can install
**Kotlin External FIR Support** and add a plugin bundle in **Settings → Tools → Kotlin External FIR
Support → Artifacts** with:

* Coordinates: `io.github.brennanium:kpaths-compiler-plugin-jvm`.
* Version matching: `Exact`.
* Repository: `Maven Central`.

The library version must have an artifact compiled for the IDE's Kotlin compiler. KEFS's project
settings can be shared using `.idea/kotlin-plugins.xml`; see the
[KEFS guide](https://github.com/Mr3zee/kotlin-external-fir-support/blob/main/GUIDE.md#storing-settings).
KPaths is not currently in KEFS's built-in defaults, and this repository has not published the
IDE-compatible artifacts, so this is the planned consumer setup rather than a released workflow.

From the KPaths root, with JDK 17 for Gradle and a JDK 25 toolchain available:

```sh
./gradlew :kpaths-compiler-plugin:jvmJar :kpaths-compiler-plugin-contributor:jvmJar :kpaths-core:jvmJar :kpaths-annotations:jvmJar
./gradlew -p kpaths-kefs-ide-fixture test --tests '*KefsIntegrationTest' -Pkpaths.idePath="/path/to/Android Studio.app"
```

## Real Gradle consumer and IDE analysis

The `consumer` project uses the experimental Gradle plugin and published KPaths dependencies as a
standalone JVM project. The sandbox-only `ConsumerProbe` checks real PSI references in this project
after Android Studio imports it. It checks ordinary `@Pathable` and `Person::name` references as
controls, then checks generated property and case paths. Those generated references must resolve
when KEFS is enabled and be unresolved when it is disabled.

This was verified locally with Android Studio 2026.2.2 and KEFS 0.5.0-262: Gradle sync succeeded,
and both enabled and disabled checks passed. The IDE-compatible compiler artifact was published to
an actual local Maven repository. KEFS has no built-in KPaths entry, so `configure-consumer.py`
writes a **test-project-only** KEFS descriptor. It is a way to reproduce this experiment, not an
installation mechanism or evidence of automatic KPaths discovery. The probe verifies symbol
resolution; it does not yet verify editor highlighting, completion, navigation, or editing. The
separate fixture above checks highlighting in a synthetic project.

To reproduce, set `JAVA_HOME` for Gradle as needed and have a JDK 25 toolchain available. The
standalone consumer currently pins KPaths 0.1.0 and Kotlin 2.4.21 to match the checked-out fixture.
First publish the build artifacts locally:

```sh
./gradlew --no-configuration-cache -I gradle/local-test-repository.init.gradle \
  :kpaths-gradle-plugin:publishAllPublicationsToConsumerVerificationRepository \
  :kpaths-compiler-plugin:publishJvmPublicationToConsumerVerificationRepository \
  :kpaths-core:publishKotlinMultiplatformPublicationToConsumerVerificationRepository \
  :kpaths-core:publishJvmPublicationToConsumerVerificationRepository \
  :kpaths-annotations:publishKotlinMultiplatformPublicationToConsumerVerificationRepository \
  :kpaths-annotations:publishJvmPublicationToConsumerVerificationRepository

./gradlew -p kpaths-kefs-ide-fixture publishIdeCompilerPluginPublicationToKefsLocalRepository \
  -Pkpaths.idePath="/path/to/Android Studio.app" \
  -Pkpaths.ideKotlinVersion="2.4.20-ij262-52"

./gradlew -p kpaths-kefs-ide-fixture/consumer build
```

The IDE Kotlin version above applies to the tested Android Studio build. For another IDE, obtain
its version using **KEFS: Copy Kotlin IDE Version**, adapt the IDE compiler sources if necessary,
and pass the corresponding version. Before publishing, the fixture test verifies that KEFS reports
the requested version for the selected IDE.

Configure the enabled case, then launch the isolated sandbox and import the fixture:

```sh
python3 kpaths-kefs-ide-fixture/configure-consumer.py enabled
./gradlew -p kpaths-kefs-ide-fixture runIde \
  --args="$(pwd)/kpaths-kefs-ide-fixture/consumer" \
  -Pkpaths.idePath="/path/to/Android Studio.app" \
  -Pkpaths.consumerExpected=enabled
```

On the first launch Android Studio may ask for its SDK and Gradle setup. After import, inspect
`kpaths-kefs-ide-fixture/build/consumer-probe.txt` for `PASS (enabled)`. Close the sandbox before
repeating with `configure-consumer.py disabled` and `-Pkpaths.consumerExpected=disabled`; expect
`PASS (disabled)`. `WAITING` and `TIMEOUT` indicate that the expected result was not established.
The run task clears the previous report at startup. The probe runs only in this fixture sandbox and
does not change the user's installed IDE or its settings.

## Remote Maven resolution experiment

An opt-in fixture checks the real KEFS URL repository lookup against a separately published HTTPS
Maven repository, using exact library-version matching and comparing the resolved JAR byte-for-byte
with the expected IDE artifact. On Android Studio 2026.2.2 with KEFS 0.5.0-262, this succeeded
against a [temporary GitHub-hosted repository](https://github.com/Brennanium/KPaths/tree/ci/kefs-remote-lookup-2cec72baeb)
with library version `0.2.0-remote2cec72baeb` and IDE compiler version `2.4.20-ij262-52`.
Its metadata included decoy library and IDE versions. This is an experiment, not a released KPaths
version or a supported repository for consumers.

The initial test publication omitted the JAR checksum: KEFS found the correct version but
refused the download because its `.md5` file was missing. Gradle's Maven publication produces this
file; retain it when assembling and hosting the IDE repository. The published test was updated with
checksums before the successful retry. The fixture checks remote resolution, not an imported IDE
project or automatic KEFS configuration.

To repeat the test, publish a **fresh unique version** of an IDE-compatible JAR and Maven metadata
to an HTTPS repository, and pass the repository URL, version, and original local JAR for comparison:

```sh
./gradlew -p kpaths-kefs-ide-fixture test --tests '*KefsRemoteRepositoryTest' \
  -Pkpaths.idePath="/path/to/Android Studio.app" \
  -Pkpaths.ideKotlinVersion=2.4.20-ij262-52 -Pkpaths.kefsVersion=0.5.0-262 \
  -Pkpaths.remoteRepositoryUrl=https://example.com/test-maven \
  -Pkpaths.remoteTestVersion=0.2.0-remote-unique \
  -Pkpaths.remoteExpectedJar=/path/to/the-original-ide-plugin.jar
```

KEFS caches resolved artifacts, so changing the version for each experiment is necessary to prove a
fresh remote fetch. The fixture is excluded from ordinary test runs unless a remote URL is supplied.
