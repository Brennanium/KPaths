# Knapshots extraction handoff

This is the handoff for a future agent creating **Knapshots**, a Kotlin testing project inspired by
Point-Free's SnapshotTesting, MacroTesting, and CompilationTesting. It also includes real IDE
assertions and compiler/IDE compatibility tooling. The source currently lives in KPaths. **Copy the
relevant code into the new project first; do not replace KPaths' local implementations or dependencies
until Knapshots is ready and its behavior has been verified.** Do not publish a release or start CI
without separate authorization.

This document records the intended architecture, what actually exists, where it lives, constraints,
and an initial verification plan. A named module or API below is a design direction unless it is
explicitly described as present. This is not an instruction to duplicate KPaths' product API or copy
its compiler plugin into the new library.

## Product direction

Knapshots is one coordinated project/release with independently consumable modules and a convenient
Gradle integration. An application author may need only snapshots; a compiler-plugin author may
need compiler assertions; IDE tests opt into the heavier IDE runner. A Gradle-facing dependency
should arrange compatible components together; users should not have to pin a separate Python CLI
through `uvx`. A standalone CLI can remain optional, preferably a thin interface to the same JVM
runner. The existing Python implementation is useful reference and a working KPaths integration,
not the target language for the unified runner.

The intended public testing surface has these distinct operations:

- **Snapshot assertions:** capture values using composable strategies and compare to recorded
  representations. Support file and inline recording where appropriate, clear diffs, and explicit
  recording policies.
- **One `assertCompiler`:** compile supplied source and assert whichever outputs are relevant:
  successful or unsuccessful compilation, diagnostics, FIR, IR, and eventually bytecode. Do not
  split ordinary compilation diagnostics into a separate `assertCompilation` product. A failing
  snippet must be testable; an unexpectedly successful compilation must fail the expected-error test.
- **Separate `assertEditor`:** inspect a running IDE's reference resolution, diagnostics, and later
  completion, inlays, and other editor behavior. Editor observations and compiler diagnostics are
  related but are not interchangeable.
- **Compatibility matrix:** exercise actual project tests against requested Kotlin versions and IDE
  versions; compilation, runtime behavior, the bundled IDE compiler, and editor behavior must be
  reported accurately. This is orchestration, not a replacement for the assertions.

An illustrative architecture, not an established artifact naming scheme:

```text
knapshots-snapshots       snapshot strategies, comparison, shared recording infrastructure
knapshots-compiler        assertCompiler, compiler capture and diagnostic adapters
knapshots-editor          assertEditor and editor observation types
knapshots-ide-runner      temporary projects, IDE session and observation transport
knapshots-gradle-plugin    coordinated setup and Kotlin/IDE compatibility tasks
```

Additional internal modules may be appropriate for the IDE-side IntelliJ plugin and for compiler
version-specific adapters. Keep compiler/IDE internals and their dependencies out of the basic
snapshot artifact. A plugin that only uses `assertCompiler` must not have to download an IDE. A
single Gradle plugin can provision coherent dependencies for the common workflow, while the
underlying modules remain independently usable. Names, group IDs, API stability, and eventual
publication are still open decisions.

## What exists and where to copy from

### Snapshot foundation

- `snapshot-testing/` is an unpublished, independently runnable Kotlin/JVM Gradle project. Its
  `Snapshotting<Value, Format>`, `Diffing<Format>`, `pullback`, `compareSnapshot`, and text strategy
  are in `src/main/kotlin/dev/kpaths/testing/snapshot/Snapshotting.kt`. Its tests are in
  `src/test/kotlin/.../SnapshottingTest.kt`.
- This is a small strategy/comparison foundation, **not** feature parity with Point-Free's complete
  snapshot library: general snapshot assertions, file management, flexible diffs, integrations,
  and reusable inline recording remain to be designed.
- `editor-testing` and `compiler-testing` both use this foundation. Current namespaces and
  `dev.kpaths.testing` coordinates are provisional. Select independent Knapshots namespaces and
  artifact coordinates in the new project rather than promising compatibility with them.

### Compiler assertions and recording

- `compiler-testing/` is an unpublished Kotlin/JVM module extracted from the KPaths test fixture.
  It contains `CompilerSnapshots.kt`, `CompilerVersionSelectors.kt`, and `CompilerSnapshotsTest.kt`.
  It can also run independently using its `settings.gradle.kts`, which composite-includes the
  adjacent `snapshot-testing` module. Its API is public so the existing fixture can consume it,
  but is experimental, not a published stability promise.
- `assertCompilerSnapshots` compares named captures provided by a caller. `CompilerSnapshotKind`
  currently names FIR/IR but supports additional named kinds. It fails if a requested capture is
  missing. `SnapshotExpectation` supplies a default and Kotlin-version overrides.
- Version selectors support exact versions, `major.minor.*`, and space-separated `<`, `<=`, `>`,
  `>=` bounds. Exact versions take precedence; ambiguous matching ranges fail. Prereleases sort
  before final releases. File-backed snapshots have exact-version overrides, and their recording
  intentionally fails after writing so users review and rerun.
- **The compiler engine is not in that module yet.** The private `assertCompiler` and
  `compileSuccessfully` in `kpaths-compiler-plugin-fixture/src/test/kotlin/dev/kpaths/compiler/fixture/FutureCompilerPluginContractTest.kt`
  create Gradle projects, locate KPaths-specific artifacts, and request KPaths-specific FIR/IR
  snapshots. The IR/FIR captures themselves are normalized, selected views of generated KPaths
  declarations, not general full compiler dumps. Refer to
  `kpaths-compiler-plugin-fixture/SNAPSHOT_TESTING.md` and
  `kpaths-compiler-plugin-fixture/src/test/snapshots/README.md`.
- `InlineCompilerSnapshots.kt` and `InlineCompilerSnapshotsTest.kt` remain in
  `kpaths-compiler-plugin-fixture/src/test/kotlin/dev/kpaths/compiler/testing/`. They are useful
  reference for inline recording, source-location recovery, literal handling, version-specific
  expectations, safe atomic replacements, and multiple writes to one file. They use Kotlin PSI
  and currently hard-code the `assertCompiler` callee and FIR/IR argument shapes. Do not present
  them as a generic inline recorder yet. Preserve ambiguity checks, external-edit checks, refusal
  of unsupported source shapes, and fail-after-recording semantics while generalizing them.
- KPaths' snippet compiler harness also uses KCTFork (`dev.zacsweers.kctfork:core`) for in-process
  compiler/plugin checks. It is separate from the Gradle-backed snapshot adapter. See
  `CompilerSnippetTest.kt` and `kpaths-compiler-plugin-fixture/build.gradle.kts`. Evaluate the
  existing approaches instead of assuming an API that works across all compiler versions.
- First-class diagnostic capture, annotated source ranges, warnings/notes/fix-its where available,
  execution outcome, automatic classpath/plugin discovery, and generic plugin setup remain work.
  Point-Free's CompilationTesting checks real compiler results; do not substitute IDE highlights
  for compiler diagnostics. Kotlin and Swift compiler capabilities can differ.

### Editor assertions

- `editor-testing/` is an unpublished, independently runnable Kotlin/JVM module. The source lives
  in `src/main/kotlin/dev/kpaths/testing/editor/`, with unit tests and a README.
- `EditorObservations` abstracts diagnostics and reference resolution; `EditorSourceHost` connects
  inline source to a prepared host. `assertEditor` checks observations and fails unexpected **error**
  diagnostics by default; warnings are checked when expected. Diagnostics use one-to-one matching,
  including when expectations overlap. Optional text diagnostic snapshots use the shared strategy.
- Reference results distinguish `Resolved`, `Unresolved`, and `Unavailable`. Missing or ambiguous
  source expressions must not silently pass as unresolved. `checkDiagnostics = false` is explicitly
  for a backend that cannot capture diagnostics; never fabricate an empty diagnostic list when
  capture is unavailable or analysis has not finished.
- The actual lightweight IntelliJ adapter and source host are test-only code at
  `kpaths-compiler-plugin-ide-fixture/src/test/kotlin/dev/kpaths/testing/editor/intellij/`.
  `EditorFixtureTestCase` can host an inline source file plus `additionalSources`; suite setup
  supplies dependencies and compiler-plugin configuration. `IntellijEditorObservations` uses real
  fixture highlighting and reports `File.kt:line:column` locations (one-based). The light fixture
  filters its known `MISSING_DEPENDENCY` stub-classpath diagnostic explicitly; do not apply that
  filter globally or conceal unexpected diagnostics in a real imported project.
- Completion, inlays, richer matching, reusable editor recording, and an imported-project
  `assertEditor(source = ...)` host are not implemented. The snapshot recorder above is currently
  compiler-specific. `assertEditor` does not yet auto-start a project or IDE.

### Real IDE runner and Gradle project preparation

- `kpaths-kefs-ide-fixture/` is a standalone Android Studio/KEFS fixture. Its `ConsumerProbe` runs
  in an isolated IDE against an imported Gradle project. It waits for project indexing and editor
  highlighting, checks KPaths-specific reference anchors, observes diagnostics, reports results,
  and exits its sandbox. `ImportedReferenceAssertions.kt` exercises enabled and disabled KEFS
  controls plus a deliberate error. The disabled case deliberately has unresolved references and
  opts out of automatic diagnostic checking; the enabled and diagnostic cases check actual errors.
- The imported probe's assertions and source anchors are **hard-coded KPaths examples**. The
  Python runner generates a multi-file project, but this is not a generic request/result protocol
  driven by arbitrary `assertEditor` tests. The missing bridge should prepare a project from the
  caller's source, communicate requested observations to the IDE, collect real diagnostics and
  references only after readiness, expose failures/timeouts distinctly, and clean up reliably.
  Prefer reusing a session when safe instead of launching a fresh IDE for each assertion.
- `kpaths-compiler-plugin-ide-fixture/` is a different, lightweight IDE test fixture. It has a
  test-only direct provider for an IDE-compatible KPaths compiler plugin. It proves focused IDE
  highlighting, but does not itself prove KEFS, Marketplace installation, or Gradle import. Do not
  confuse it with a packaged third-party IDE plugin integration.
- `editor-testing.toml` names the current KPaths Gradle plugin project, compiler plugin project,
  runtime projects, and KEFS integration. `gradle/editor-testing-model.init.gradle` exports
  evaluated local Gradle metadata. `scripts/editor_project_model.py` generates a consumer project;
  `scripts/prototype_editor_project.py` prepares local artifacts and consumes that model.
  `gradle/editor-testing-prototype.md` documents assumptions and commands. `--kotlin-version`
  changes the generated consumer version only; it does **not** rebuild or certify the compiler
  plugin for that version. `libs.versions.toml` is a KPaths default, not a reliable generic proof
  of the Kotlin Gradle plugin version used by every project.
- `scripts/verify-kefs-compatibility.py` now uses the generated consumer and TOML roles. It builds
  the local IDE artifact using KPaths-specific fixture code and copies existing fixture SDK settings
  or derives them from `ANDROID_HOME`/`ANDROID_SDK_ROOT`. Android Studio required an SDK even for
  the tested JVM-only project. More robust SDK/JDK discovery, explicit failures, and Windows support
  remain open. Startup logs have shown transient `PathsPluginRegistrar` class-loading errors even
  when a later reference check passed; investigate before declaring startup reliable.
- Existing IDE compatibility evidence is limited to recorded tested versions/platforms. See
  `kpaths-kefs-ide-fixture/ide-compatibility.json`, its README and
  `.github/workflows/ide-compatibility.yml`. Kotlin build-compiler compatibility and the IDE's
  bundled compiler compatibility are separate dimensions; selecting one does not prove the other.
  KEFS artifacts currently use Maven-compatible metadata and checksums. See
  `kpaths-kefs-ide-fixture/ide-artifact-release-strategy.md` and the Maven maintenance scripts.
- The VS Code/LSP investigation lives on a separate feature branch and in `experiments/`; do not
  promise live compiler-plugin generated editor declarations work there. IntelliJ-based IDEs and
  VS Code require independently verified integrations.

### Existing matrix/orchestration code

- `scripts/kotlin-plugin-matrix.py` dispatches Python compiler and IDE compatibility commands.
  `scripts/verify-compiler-compatibility.py` exercises KPaths specifically;
  `scripts/run-compiler-matrix.py` supports a project-provided driver. Version resolution includes
  stable versions, exact dev builds, and dev-family selectors. See `scripts/README.md`.
- `scripts/verify-android-studio-version.py` resolves IDE releases, KEFS versions, installation,
  platform differences, and reports. `scripts/verify-kefs-compatibility.py`, `ide_sandbox.py`,
  `editor_testing_config.py`, and `editor_project_model.py` handle KPaths-specific IDE scenarios,
  sandbox trust, TOML roles, and project generation. These are useful behavior and regression
  references for a Kotlin/Gradle port. The packaged Python CLI is a development experiment and is
  not a requirement to retain or publish for Knapshots.
- `.github/workflows/ide-compatibility.yml`, `verify.yml`, and `kefs-ide-artifacts.yml` contain
  existing CI consumers. The new library's runner should be independently callable; GitHub Actions
  should remain an optional scheduling/reporting integration. Preserve macOS and Ubuntu support;
  headless Linux requires a display such as Xvfb. Gradle/JVM orchestration can still launch external
  OS processes such as the IDE, virtual display, and macOS disk-image tools without Python.

## Suggested extraction sequence

1. Create a separate Knapshots checkout when authorized. Copy the two small snapshot and compiler
   utility modules and their tests, retaining appropriate license and attribution. Copy
   `editor-testing` and its tests. Do not replace KPaths code or commit unrelated work here.
2. Choose neutral package names and organize a Gradle multi-module build. Run every copied test
   before refactoring further. Keep the basic snapshot module independent of IDE and compiler
   internals. Retain JUnit usability and avoid making proposed public API names compatibility
   promises prematurely.
3. Design a generalized compilation result and `assertCompiler`: one source-driven API for
   success/failure, diagnostics, FIR and IR. Provide explicit adapters for the actual compiler and
   plugins, preserve version selectors and recording safety, and test negative and unexpected-
   success paths. Don't assume FIR/IR is available after a failed compilation.
4. Extract or rewrite the PSI inline recorder around generic assertion names and capture kinds;
   prove ambiguous calls, nonliteral arguments, changes during compilation, multiple writes, line
   shifts, versioned expectations, escaping, and recording failures. Do not edit source in normal
   verification mode. Integrate the resulting shared layer into editor recording only once real
   editor data is available.
5. Design a JVM/Gradle runner and IDE-side transport. Start with one real IDE and one fixture:
   generate a clean project from source, configure dependencies/plugins once, sync/index/analyze,
   query references and diagnostics, and prove both passing and deliberately failing assertions.
   Build a deterministic readiness/timeout and cleanup contract, isolate sandbox directories, and
   preserve logs for failures. Verify zero observations is genuinely available rather than missing.
6. Add a Gradle integration that wires the assertion and runner versions, infers configuration
   from the project when reliable, accepts explicit overrides, and has defaults for plain Kotlin.
   Test it in a separate consumer project; avoid Gradle daemon lock or recursive-build assumptions.
   Then port matrix/version resolution and OS handling incrementally, retaining the working Python
   runner as reference until JVM parity is independently established.
7. Test with both KEFS and, separately, an actual independently packaged IDE plugin. Existing
   KPaths fixtures do not establish arbitrary-plugin support. Exercise Ubuntu/Xvfb and macOS and
   maintain explicit tested IDE/compiler combinations; do not claim Windows support without tests.
8. Only after the new library is functional and released/available as instructed, update KPaths to
   consume it in a separate change and remove redundant local code. Until then, keep the KPaths
   build and its existing fixture behavior intact.

## Useful local verification

Set an appropriate `JAVA_HOME` and JDK toolchains for the machine; do not hard-code a developer's
local JDK path into public docs. These commands are run from the KPaths root:

```shell
./gradlew :snapshot-testing:test :editor-testing:test :compiler-testing:test
./gradlew -p compiler-testing test
./gradlew :kpaths-compiler-plugin-fixture:compileTestKotlinJvm
./gradlew :kpaths-compiler-plugin-fixture:jvmTest \
  --tests '*InlineCompilerSnapshotsTest' -Pkpaths.snapshotRecording=none
./gradlew :kpaths-compiler-plugin-fixture:compilerPluginContractTest \
  -Pkpaths.snapshotRecording=none
python3 -m unittest scripts/test_editor_testing_config.py \
  scripts/test_editor_project_model.py scripts/test_prototype_editor_project.py \
  scripts/test_verify_kefs_compatibility.py scripts/test_verify_android_studio_version.py
```

The standalone compiler testing module resolves the adjacent snapshot module as a composite build.
Gradle contract tests can launch nested builds and take longer; don't enable snapshot recording
unintentionally. The Python TOML reader uses the standard library on Python 3.11+ and the `tomli`
dependency on Python 3.9/3.10. An opt-in local Gradle metadata/consumer integration test is:

```shell
KPATHS_RUN_EDITOR_MODEL_INTEGRATION=1 python3 -m unittest \
  scripts/test_editor_testing_model_integration.py
```

The real imported IDE test requires an installed matching Android Studio, JDK toolchains, KEFS,
network/cached artifacts, an SDK, and a graphical session or Xvfb. It can launch visible windows:

```shell
python3 scripts/kotlin-plugin-matrix.py ide <android-studio-version> \
  --ide-path '/path/to/Android Studio.app' --suite all
```

The KPaths user prefers to monitor long CI runs themselves; do not launch CI for every addition.
Ask before publishing or replacing the KPaths implementation. Existing unrelated working-tree
changes belong to the user and must be preserved.

## References and inspiration

- Point-Free [SnapshotTesting](https://github.com/pointfreeco/swift-snapshot-testing),
  [MacroTesting](https://github.com/pointfreeco/swift-macro-testing), and
  [CompilationTesting beta preview](https://www.pointfree.co/blog/posts/234-beta-preview-compilationtesting).
- [Metro](https://github.com/ZacSweers/metro) for compiler/IDE compatibility testing and Ubuntu IDE
  automation; [KCTFork](https://github.com/ZacSweers/kotlin-compile-testing) for compiler snippets.
- [Kotlin External FIR Support](https://github.com/Mr3zee/Kotlin-External-FIR-Support) for
  IDE-compatible compiler plugin resolution; Gradle [TestKit](https://docs.gradle.org/current/userguide/test_kit.html)
  and JetBrains' [IDE testing extension](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-testing-extension.html).
- `experiments/metro`, `experiments/kotlin-structural-typing` (if present), and the Kotlin compiler
  plugin template clones are local references, not files to blindly copy into the new library.
