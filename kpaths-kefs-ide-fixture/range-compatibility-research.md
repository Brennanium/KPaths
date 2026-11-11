# KEFS compiler-artifact reuse experiment

This is a research report, not a support policy or a release artifact. The IDE builds in this
experiment were the only ones checked. The normal, tested IDE support matrix remains
[ide-compatibility.json](ide-compatibility.json).

## DevKit status

As of November 10, 2026, JetBrains' [compiler-plugin documentation](https://kotlinlang.org/docs/custom-compiler-plugins.html#ide-integration)
describes a compiler-plugin DevKit as future work and links to [KT-82617](https://youtrack.jetbrains.com/issue/KT-82617).
A JetBrains contributor [said in May 2026](https://slack-chats.kotlinlang.org/t/33307238/i-m-just-whining-i-ve-been-waiting-months-until-ksp-was-no-l)
that work was underway, but we found no public release date. The local KEFS source checkout
(`03a0c6e4228e5a157dce6dce133557d8135cef6b`, November 9, 2026) says the multi-version compiler
plugin DevKit is currently available only inside JetBrains. The public
[JetBrains/kotlin-compiler-devkit](https://github.com/JetBrains/kotlin-compiler-devkit) is a
*different* IDE plugin for editing and running compiler tests.

## Findings

| Host | Embedded compiler | KEFS | Experiment | Result |
| --- | --- | --- | --- | --- |
| IntelliJ IDEA 2026.1.5.1, build 261.27258.136 | 2.4.0-ij261-71 | 0.5.0-261 | Plugin built against this IDE; property-only highlighting | Pass |
| Same IntelliJ IDEA | Same | Same | Plugin built against this IDE; case-path highlighting | Fails in generated-declaration inlay rendering |
| Same IntelliJ IDEA | Same | Same | Binary built for the 262 IDE; property-only highlighting | Pass |
| Android Studio 2026.2.2, build 262.10968.63.2622.16730706 | 2.4.20-ij262-52 | 0.5.0-262 | Binary built for 261, loaded via ordinary KEFS Maven resolution; full fixture including a second plugin | Pass |
| Android Studio 2026.2.2 | Same | Same | 261-built Java 17 binary with a closed range marker; property and case highlighting | Pass |
| IntelliJ IDEA 2026.1.5.1 | Same | Same | Identical range-marked binary; property-only highlighting | Pass |

The IntelliJ 261 case failure occurs while the IDE renders an inlay for generated `Event.Paths.Message`.
Its analysis API reports that a containing `FirRegularClass` is missing. This observation does not
establish whether the root cause is in Kotlin, KPaths, or their interaction. Full 261 support
remains unverified. The range `261` through `262.*` includes many IDE builds we did not test;
it must not be interpreted as a release compatibility claim.

The same range-marked, 261-built JAR compiled an isolated Gradle/JVM consumer on JDK 17 with
Kotlin 2.4.0, 2.4.20, 2.4.21, and the development version in the preview matrix. Each run executed a
small probe covering property reading, copy-based writing, case extraction, and case embedding.
The initial JAR produced by the IDE fixture used Java 25 bytecode and failed on JDK 17; building
that same source with `-Pkpaths.idePluginJvmTarget=17` removed that specific failure.
These are focused smoke tests, not full JVM/Native or compiler compatibility certification.

On Android Studio, the range-marked JAR also compiled the standalone Gradle consumer and its
imported IDE project resolved `Person.Paths.name` and `Event.Paths.Message.text` while the normal
KPaths KEFS descriptor was disabled. The local Maven artifact and fixture settings were restored
afterward. A separate control using the same binary without the manifest marker was not resolved
by the provider in the fixture without a descriptor.

## What KEFS did

Ordinary KEFS Maven lookup filters artifacts by the exact IDE compiler version. Its `Exact`,
`Same Major`, and `Latest` choices match *library* versions; they do not choose an older IDE
compiler version. Re-publishing byte-identical JARs under several tested IDE-version coordinates
is a separate possible strategy, but still requires an artifact coordinate for each version.

KEFS 0.5.0 also has an internal path for JARs it recognizes as coming from JetBrains' compiler
plugin DevKit. In this experiment we added only these manifest entries to a normal plugin JAR:

```text
Kotlin-Compiler-Plugin-Since-Build: 261
Kotlin-Compiler-Plugin-Until-Build: 262.*
```

All other JAR entries were byte-identical. KEFS recognized and loaded the JAR directly without
Maven descriptor configuration. This is **not evidence that our JAR was produced by DevKit** or
that these internal detection details are a stable third-party API. Actual DevKit JARs can have
additional structure and version-selection behavior. The experiment should not be shipped or
published without establishing a supported contract with the relevant maintainers and testing
all versions we claim. The source is available in
[KEFS's DevKit detector](https://github.com/Mr3zee/Kotlin-External-FIR-Support/blob/03a0c6e4228e5a157dce6dce133557d8135cef6b/src/main/kotlin/com/github/mr3zee/kefs/KefsDevKit.kt).

## Reproducing the local experiment

The downloaded IDE image and experimental JARs are retained in the ignored
`experiments/ide-distributions/` directory. The image can be mounted read-only, or an installed
copy of the IDE may be used. Supply paths appropriate to the machine and use JDK 17 to run Gradle;
the fixture also requires a JDK 25 toolchain.

1. Build `ideCompilerPluginJar` against the 261 IDE with
   `-Pkpaths.idePluginJvmTarget=17` and `-Pkpaths.kefsVersion=0.5.0-261`. Save the result from
   `kpaths-kefs-ide-fixture/build/ide-compiler-plugin/kpaths-ide-compiler-plugin.jar` before
   changing IDEs; the fixture output is reused.
2. Create a **test-only** range-marked copy:

   ```sh
   python3 scripts/prepare-kefs-range-experiment.py <261-built.jar> <new-test-jar> \
     --since 261 --until '262.*'
   ```

3. Run the range fixture for each IDE using `--tests '*KefsRangeTaggedJarTest*'`,
   `-Pkpaths.idePath=<IDE>`, `-Pkpaths.kefsVersion=<matching-KEFS>`,
   `-Pkpaths.rangeTaggedCandidateJar=<new-test-jar>`, and
   `-Pkpaths.untaggedCandidateJar=<261-built.jar>`. Add `-Pkpaths.kefsScenario=properties`
   for the older IDE's property-only control. The ordinary full KEFS fixture can use
   `-Pkpaths.ideCandidateJar=<261-built.jar>` to test the same implementation via Maven-style
   lookup.
4. The isolated Gradle project and the temporary real-consumer probe are in
   `experiments/ide-distributions/`. The latter backs up and restores the local Maven artifact
   and fixture KEFS settings. It is exploratory and is not part of the release workflow.

The fixture's local publication task refuses partial scenarios and external experimental candidate
JARs so these experiments cannot accidentally certify a different published binary.
