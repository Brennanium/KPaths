# K2 `Type.Paths` compiler plugin

This module is a deliberately scoped K2 compiler-plugin implementation. It is not yet a
published KPaths module and has no production API.

It proves that a `FirDeclarationGenerationExtension` can discover a source-visible `@Pathable`
declaration through FIR's predicate index and synthesize a *real* nested `Paths` object:

```kotlin
@Pathable
data class Person(val name: String)

val name: KeyPath<Person, String> = Person.Paths.name
```

No companion object and no generated Kotlin source file are involved. For concrete root types, the
plugin's IR extension emits a private `KeyPathBase` subclass behind each generated field. That
subclass stores its `PathId` and structural `TypeId`—including nested nullability, generic
arguments, use-site variance, and star projections—and calls the source getter directly. There is
no `KProperty1` reference or stored closure in those emitted paths. Generic class roots also expose
a typed factory:

```kotlin
@Pathable
data class Box<Value>(var value: Value)

val value: WritableKeyPath<Box<String?>, String?> = Box.Paths<String?>().value
val sameValue = Box.Paths.of<String?>().value
val stringValue = Box.Paths.of(String::class).value
val explicitValue = Box.Paths.of<String?>(TypeId.of<String?>()).value
```

The factory captures each reified type argument as a `TypeId`, so path identity preserves generic
arguments and nullability. The catalog caches direct path implementations; the `TypeId.of` call
uses Kotlin type metadata at the factory call site. A `KClass` can be passed when a non-null class
token is already available; it does not retain nested generic arguments or nullability.
The explicit `TypeId` overload is useful in non-reified generic code; callers are responsible for
passing a token that matches each type argument.

On supported non-generic roots, `@PathName` changes the generated property-path spelling while
preserving its identity:

```kotlin
@Pathable
data class Person(@PathName("display") var name: String)

val display = Person.Paths.display  // Same path identity as Person::name.
```

Invalid names and collisions are diagnosed. A source declaration named `Paths` also conflicts with
the generated nested catalog and must be renamed.

For a concrete sealed hierarchy, direct nested variants receive the same nested catalog shape:

```kotlin
@Pathable
sealed interface Delivery {
  data object Refresh : Delivery
  data class Message(val text: String) : Delivery
}

val message: CaseKeyPath<Delivery, Delivery.Message> = Delivery.Paths.Message
```

Those whole-case members are direct `CaseKeyPathBase` implementations: matching is an `is` check,
embedding returns the supplied case, and their `PathId`/`TypeId` metadata is emitted without
reflection. This slice covers variants declared directly inside the sealed root, including direct
payload paths for object cases and cases with one to three constructor properties.

Generic sealed roots also generate whole-case paths, including cases that forward or reorder root
type parameters and covariant `Nothing` cases:

```kotlin
@Pathable
sealed interface Loadable<out Value> {
  data object Idle : Loadable<Nothing>
  data class Loaded<Value>(val value: Value) : Loadable<Value>
}

val loaded: CaseKeyPath<Loadable<String>, Loadable.Loaded<String>> =
  Loadable.Paths<String>().Loaded
val value: CaseKeyPath<Loadable<String>, String> = Loadable.Paths<String>().Loaded.value
val property: KeyPath<Loadable.Loaded<String>, String> = Loadable.Paths<String>().LoadedPaths.value
val cases = Loadable.Paths<String>().allCasePaths
```

Generic catalogs expose whole-case paths, direct payload paths for object cases and data cases with
one to three constructor properties, case property KeyPath catalogs, bound payload factories, and
`allCasePaths`. Automatic `CasePathable` conformance for generic roots remains future work.

Use `@CaseName` on a sealed case or enum entry to choose its generated member name. Its identity
continues to use the original case name, and invalid or colliding names are diagnosed:

```kotlin
@Pathable
sealed interface Event {
  @CaseName("MessageCase") data class Message(val text: String) : Event
}

val text = Event.Paths.MessageCase.text
```

The generated catalog also exposes property KeyPaths for each case. For cases with two or three
constructor properties, named factories bind the other values and focus one payload property:

```kotlin
@Pathable
sealed interface Event {
  data class Message(val id: Int, var text: String) : Event
}

val textProperty = Event.Paths.MessagePaths.text
val messageText = Event.Paths.Message.textFor(id = 1)
```

`@PathsName` renames the case's property catalog, and `@PathName` renames a property path within
that catalog. The bound path's identity includes its bound values. Its generated implementation
uses an internal, reflection-free core helper.

The compiler plugin currently discovers only cases declared inside the sealed root. Kotlin also
allows a sealed subclass to be declared outside the root, in the same package and module:

```kotlin
@Pathable sealed interface Event
data object Idle : Event
```

The plugin does not currently generate a case path for `Idle` in this form. The KSP processor
supports both nested and package-level sealed subclasses. For the compiler plugin, declare cases
inside the sealed root for now. The local case-discovery experiment records the investigation into
identifying nested cases. Direct nested sealed-interface variants are diagnosed as unsupported,
using the same case-shape policy as KSP; use a class or object for a generated case path.

Case discovery is experimental. There may be edge cases where the plugin omits a generated case
path without reporting a diagnostic.

When a non-generic sealed root has a generic direct variant, the compiler plugin reports the same
unsupported-variant diagnostic as KSP rather than silently omitting the case.

As with KSP, a bare unnamed `companion object` must be the last member of an annotated type.
Otherwise Kotlin can interpret a following keyword as the companion's name and change the parsed
declarations. A named companion or `companion object {}` can appear earlier in the body.

Bare Kotlin enums receive the corresponding unit-focused case paths:

```kotlin
@Pathable
enum class ConnectionStatus { Offline, Connecting, Online }

val online: CaseKeyPath<ConnectionStatus, Unit> = ConnectionStatus.Paths.Online
```

The generated path compares directly with `ConnectionStatus.Online` and embeds that same entry;
it does not depend on Kotlin reflection.

### Enum `CasePathable` conformance

The plugin generates the `case` property, case catalog, and `CasePathable` conformance for an enum:

```kotlin
@Pathable
enum class ConnectionStatus {
  Offline, Connecting, Online,
}

val status: CasePathable<ConnectionStatus> = ConnectionStatus.Online
val activeCase = status.case
```

The plugin works around a Kotlin 2.4.21 FIR issue when adding enum supertypes; a local standalone
reproduction records the finding.
An upstream issue has not yet been filed. Supported non-generic sealed roots receive
the `CasePathable` conformance automatically. With KSP, root types must declare the conformance
and implement `case` themselves, although their generated catalogs implement `CasePathCatalog`.

The companion modules demonstrate two important boundaries:

* `kpaths-compiler-plugin-fixture` compiles the synthetic declaration and a real
  `NativePerson.Paths.name` KeyPath on JVM and `iosSimulatorArm64`.
* `kpaths-compiler-plugin-consumer` intentionally does **not** apply the compiler plugin,
  yet compiles `Person.Paths` from the fixture's published project dependency. This confirms the
  generated nested declaration is present in ordinary Kotlin metadata/bytecode for consumers.
* `kpaths-compiler-plugin-integration-fixture` simulates a separately published library. It
  supplies a `@KPathsPathable` meta-annotation and a `@KPathsAdapter`-annotated generic storage
  type. The fixture receives `ImportedDocument.Paths` through the meta-annotation while compiling
  against the adapter type's binary metadata.
* `kpaths-compiler-plugin-contributor` is a second compiler plugin. It calls
  `session.kpaths.pathable(generatedClass, options)` and the fixture successfully resolves the
  resulting `RequestedDocument.Paths` catalog. This is the intended integration path for a type
  owned or synthesized by another compiler plugin.

Contributors can opt individual properties out of `copyWritableVals` by passing their FIR property
symbols in `KPathsGenerationOptions.copyWritableValExclusions`. An excluded `val` keeps its
ordinary read-only path; excluding a `var` does not remove its ordinary write access. The same
option applies to properties of supported sealed cases. Properties are identified by their owners
as well as their names, so excluding one case's property does not exclude a namesake in another.

Useful verification commands:

```sh
# Fast edit/test loop: generated JVM paths, runtime behavior, and binary consumer metadata.
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew verifyCompilerPluginJvm

# The JVM loop plus a Kotlin/Native compiler-backend check.
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew verifyCompilerPlugin

# Downstream compile contracts. Planned API contracts stay disabled until their feature begins.
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew verifyCompilerPluginContracts

# Individual backend checks remain available when narrowing down a failure.
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
  ./gradlew :kpaths-compiler-plugin-consumer:compileKotlinJvm

JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
  ./gradlew :kpaths-compiler-plugin-fixture:compileKotlinIosSimulatorArm64
```

The JVM fixture includes in-process snippet compilation for fast compiler tests. The separate
Android Studio analysis fixture tests editor highlighting against the Kotlin compiler bundled with
a local IDE; that experimental fixture is not part of the committed plugin modules.

This intentionally proves source resolution, object construction, cached direct read-only,
reference-writable, and data-class copy-writable KeyPath members, direct whole-case CaseKeyPaths,
enum-entry CaseKeyPaths,
binary meta-annotation discovery, an external compiler-plugin request, metadata, and JVM/Native
backend portability.
`copyWritableVals` works through direct `@Pathable`, `@KPathsPathable` meta-annotations, and a
contributing compiler plugin's `KPathsGenerationOptions`. Generated `allCasePaths` catalogs use
the canonical associated-value paths for object cases and cases with one to three properties.
Cases without a supported canonical payload are omitted, so `case` can fail for those cases;
the plugin warns when it generates an incomplete catalog. Adapter-backed property members, diagnostics, and broader
conformance coverage remain future work.
`@KPathsAdapter` currently establishes the binary metadata contract; producing an adapter's
specialized path remains the next FIR/IR layer.

`kpaths-compiler-plugin-fixture/LanguageSyntaxExperiments.kt` separately demonstrates the Kotlin
generic `invoke` syntax used by generated catalogs.
