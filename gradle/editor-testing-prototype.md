# Editor project generation prototype

This experiment explores deriving an imported IDE test project from a local Gradle build. It is
not yet connected to `assertEditor`, Kotlin Plugin Matrix, the CI workflow, or an IDE observation
protocol. The current runner and existing imported fixture remain unchanged.

The experiment takes explicit Gradle project roles, then asks the evaluated build for the Kotlin
version from the `libs` version catalog, the plugin ID, and Maven publication coordinates. It
publishes the checkout's current artifacts to the existing local consumer-verification repository,
generates a JVM consumer project and wrapper, and can compile source files supplied by the caller.
No release is published. Artifact preparation currently uses the KPaths-specific local repository
initialization script and expects one Gradle plugin declaration, a JVM compiler plugin publication,
and Kotlin Multiplatform root and JVM runtime publications.

For example, from the repository root, put Kotlin sources in a directory and run:

```shell
python3 scripts/prototype_editor_project.py \
  --gradle-plugin-project :kpaths-gradle-plugin \
  --compiler-plugin-project :kpaths-compiler-plugin \
  --runtime-project :kpaths-core \
  --runtime-project :kpaths-annotations \
  --source-dir /path/to/kotlin-sources \
  --project-dir /path/to/new/temporary-project \
  --compile
```

The destination must not already exist. `--offline` restricts Gradle to cached dependencies;
`--kotlin-version` overrides the generated consumer's Kotlin version. That override does **not**
rebuild the compiler plugin for that version or certify compatibility. In the eventual matrix
integration, the compiler artifact and consumer must both be prepared for the chosen version.
When no override is provided, the prototype reads the `kotlin` entry from the `libs` catalog. A
catalog entry is not proof that a different build actually applies that version; robust generic
version discovery needs an explicitly exposed Gradle model or an override.

Local checks performed during this experiment:

- Exported the KPaths model, including plugin ID, Kotlin version, compiler artifact, and runtime
  publications; verified a Kotlin override and return to the default update the model.
- Generated a new project and compiled a source file using `Person.Paths.name` against locally
  built artifacts.
- Opened another generated project in an isolated Android Studio with KEFS and passed the existing
  imported reference assertions. Android Studio required an SDK location even for this JVM project;
  the experiment supplied the SDK location already configured for the local fixture. The IDE also
  logged a transient compiler-plugin class-loading error before the reference check passed, so
  startup stability remains an open issue.
- Unit-tested source generation, plain Kotlin, Kotlin string escaping, and invalid source paths.

The Gradle model integration test is opt-in because it launches Gradle:

```shell
KPATHS_RUN_EDITOR_MODEL_INTEGRATION=1 python3 -m unittest discover -s scripts -p 'test_editor_testing_model_integration.py'
```

Next steps include a project-level configuration format, generalized publication and plugin roles,
SDK and JDK discovery, building the IDE artifact automatically, and the process bridge that lets
an arbitrary `assertEditor` call send its source and expectations to the running IDE. Versioned
or compiled dependencies must not be assumed to have the same semantics as the host test
classpath. No IDE/OS compatibility is established by these local experiments alone.
