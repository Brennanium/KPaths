# Kotlin Plugin Matrix

Kotlin Plugin Matrix is the working name for compatibility tooling that exercises compiler plugins
against Kotlin compiler and IDE versions. These scripts currently exercise KPaths specifically;
extracting reusable compatibility tools is future work.
The compiler snapshot assertions are a separate test facility: they check compiler output and
diagnostics for a source snippet, while these scripts run a project's tests across versions and IDEs.
The command-line tools should remain usable without GitHub Actions; CI workflows are consumers of
the tools and provide matrix scheduling, summaries, and artifact uploads.

The CLI currently lives in this repository and delegates to the existing runners:

```sh
python3 scripts/kotlin-plugin-matrix.py compiler 2.4.21 '2.5.0-dev*'
python3 scripts/kotlin-plugin-matrix.py ide 2026.2.2.8
python3 scripts/kotlin-plugin-matrix.py ide --help
```

On headless Linux, prefix the IDE command with `xvfb-run -a`. The compiler and IDE subcommands
also accept `--report-json <file>` for machine-readable results. See `compiler --help` and
`ide --help` for their options.

`verify-compiler-compatibility.py` runs compiler-plugin and downstream consumer tests for selected
Kotlin versions. `verify-android-studio-version.py` selects an Android Studio release and runs the
KEFS fixture and imported-consumer checks. See the [IDE fixture README](../kpaths-kefs-ide-fixture/README.md)
for requirements and options. The IDE checks currently support Apple Silicon macOS and x86-64 Linux;
The IDE CI matrix is configured for both platforms; the Ubuntu integration is being verified.
The macOS jobs for iOS simulator tests are separate.

The Android Studio selector also accepts `--driver /path/to/executable`. After preparing the
selected IDE it invokes that executable from its parent directory with `--ide-path <path>`; the
driver's exit status determines the result. A project can use this to build and test its own IDE
plugin instead of using the default KPaths KEFS fixture. The driver is responsible for its plugin
installation, consumer fixture, and assertions. This is an experimental integration boundary; it has
not yet been verified against a separately built IDE plugin. The example
`scripts/verify-local-ide-provider.sh` runs KPaths' existing non-KEFS IDE fixture through this
boundary. That fixture uses a test-only provider; it does not install or test a packaged IDE plugin.

For a different compiler plugin project, `compiler <versions...> --driver /path/to/executable`
invokes its driver once per resolved version with `--kotlin-version <version>`. The driver owns the
project's build and behavioral tests. The runner records each exit status and continues through the
remaining versions after a failure; `--report-json` saves the results. Without `--driver`, the
compiler command runs the KPaths-specific compatibility checks described below.

## Inspiration

- [Metro](https://github.com/ZacSweers/metro) demonstrates compiler and IDE compatibility matrices,
  including Android Studio tests on Ubuntu, SDK setup, isolated test projects, and log collection.
- [Kotlin External FIR Support](https://github.com/Mr3zee/Kotlin-External-FIR-Support) provides the
  IDE integration that the KEFS fixture exercises and informed its compiler-version discovery.
- The [Kotlin compiler plugin template](https://github.com/Kotlin/compiler-plugin-template) and
  [kotlin-structural-typing](https://github.com/obabichev/kotlin-structural-typing) informed the
  compiler plugin's test and fixture organization.
- Point-Free's [Macro Testing](https://github.com/pointfreeco/swift-macro-testing) inspired the
  compiler snapshot assertions and inline recording workflow used by KPaths' compiler tests.
- The compiler snippet tests use [KCTFork](https://github.com/ZacSweers/kotlin-compile-testing),
  a fork of Kotlin Compile Testing, to compile snippets in process. The separate `assertCompiler`
  snapshot helper currently compiles its test projects with Gradle.

These projects have different APIs and test strategies; the KPaths scripts do not depend on all of
them.
