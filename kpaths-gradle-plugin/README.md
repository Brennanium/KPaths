# KPaths Gradle plugin

The KPaths Gradle plugin configures the KPaths compiler plugin for Kotlin compilations. It is experimental and has not yet been released. The same compiler plugin build is tested with Kotlin 2.4.20 and 2.4.21; other versions can be tried but have not been verified. See the [compiler compatibility guide](../kpaths-compiler-plugin/COMPATIBILITY.md).

Once matching artifacts are available in a configured plugin repository, apply it alongside the Kotlin plugin:

```kotlin
plugins {
  kotlin("jvm") version "2.4.21"
  id("dev.kpaths.gradle-plugin") version "<kpaths-version>"
}

dependencies {
  implementation("io.github.brennanium:kpaths-core:<kpaths-version>")
  implementation("io.github.brennanium:kpaths-annotations:<kpaths-version>")
}
```

The plugin supplies the compiler artifact; add the KPaths runtime and annotations to the appropriate source set yourself. The plugin applies to JVM, Android JVM, Kotlin/Native, and common compilations. JavaScript and Wasm are not currently supported.

Use `@Pathable` on a supported model to access its generated `Paths` directly; no companion object is required. The compiler-plugin implementation is under development, so see the compiler-plugin documentation and tests for its current generation coverage and limitations.
