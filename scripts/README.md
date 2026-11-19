# Compatibility testing

KPaths uses Knapshots' Gradle tasks to test Kotlin compiler and Android Studio compatibility. The tasks resolve toolchain versions and invoke KPaths-specific drivers in this directory; they run locally as well as in CI.

```sh
./gradlew :kpaths-compatibility:compilerCompatibility --kotlin-version 2.4.21 --platform jvm
./gradlew :kpaths-compatibility:compilerCompatibility --kotlin-version '2.5.0-dev*' --platform jvm,native
./gradlew :kpaths-compatibility:editorCompatibility --ide-version 2026.2.2.8
```

Native verification requires an Apple Silicon Mac and an available iOS simulator. Android Studio verification uses an isolated IDE installation; on headless Linux set `KPATHS_USE_XVFB=true` and install Xvfb. The IDE driver builds and tests the KPaths IDE artifact and runs the enabled, disabled, and diagnostic editor scenarios. Reports are written under `kpaths-compatibility/build/reports/knapshots`.

A sibling Knapshots checkout is used by default. Pass `-Pknapshots.checkout=/path/to/Knapshots` to select another checkout; CI checks out a pinned Knapshots revision into `.knapshots`.

The earlier Python Kotlin Plugin Matrix scripts remain as migration references and for the specialized KEFS artifact preparation used by the editor driver. KPaths-specific publishing and documentation scripts also remain here.

## Inspiration

- [Metro](https://github.com/ZacSweers/metro) informed the compiler and IDE compatibility matrices and Linux editor testing.
- [Kotlin External FIR Support](https://github.com/Mr3zee/Kotlin-External-FIR-Support) provides the KEFS IDE integration and informed compiler-version discovery.
- The [Kotlin compiler plugin template](https://github.com/Kotlin/compiler-plugin-template) and [kotlin-structural-typing](https://github.com/obabichev/kotlin-structural-typing) informed compiler plugin fixture organization.
- Point-Free's [Macro Testing](https://github.com/pointfreeco/swift-macro-testing) inspired the inline compiler and KSP snapshot workflow.
