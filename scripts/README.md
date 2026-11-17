# Compiler and IDE compatibility checks

These scripts exercise KPaths against Kotlin compiler and Android Studio versions. They are
currently specific to this repository; extracting reusable compatibility tools is future work.
The compiler snapshot assertions are a separate test facility: they check compiler output and
diagnostics for a source snippet, while these scripts run a project's tests across versions and IDEs.

`verify-compiler-compatibility.py` runs compiler-plugin and downstream consumer tests for selected
Kotlin versions. `verify-android-studio-version.py` selects an Android Studio release and runs the
KEFS fixture and imported-consumer checks. See the [IDE fixture README](../kpaths-kefs-ide-fixture/README.md)
for requirements and options. The IDE checks currently support Apple Silicon macOS and x86-64 Linux;
The IDE CI matrix is configured for both platforms; the Ubuntu integration is being verified.
The macOS jobs for iOS simulator tests are separate.

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

These projects have different APIs and test strategies; the KPaths scripts do not depend on all of
them.
