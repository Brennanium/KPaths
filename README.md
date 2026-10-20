# KPaths

KPaths is a Kotlin path library for immutable state and sealed hierarchies. It will provide
generated, stable, typed property and case paths designed for reducer-based architectures. Apply
one `@Pathable` annotation: data/class properties generate key paths, while sealed and enum
variants generate case key paths.

## Modules

- `kpaths-annotations` — the opt-in `@Pathable` annotation for model types.
- `kpaths-core` — runtime path interfaces and stable path identities.
- `kpaths-ksp` — the Kotlin Symbol Processing generator.
- `kpaths-sample` — a small compile-time smoke test for the generator.

## First build

This project uses the Gradle wrapper and requires JDK 17 or newer to run Gradle.

```sh
export JAVA_HOME="$(brew --prefix openjdk@17)"
./gradlew check
```

The first scaffold intentionally generates only marker objects. The next implementation step is
to generate `WritableKeyPath` values for annotated data-class constructor properties.
