# KPaths

KPaths is a Kotlin path library inspired by Swift key paths and case paths. It provides stable,
typed property and case paths for ordinary Kotlin models; reducer-based architectures are one
future use case, not the library's framing. Any accessible Kotlin property or ordinary sealed
case can become a manual path. Apply `@Pathable` to a source-owned model when you want KSP to
generate its complete, canonical optics catalog: named property paths, sealed/enum case paths,
case-property namespaces, and source-aware diagnostics.

## Modules

- `kpaths-annotations` — the opt-in `@Pathable` annotation for generated model path catalogs.
- `kpaths-core` — runtime path interfaces and stable path identities.
- `kpaths-ksp` — the Kotlin Symbol Processing generator.
- `kpaths-sample` — a small compile-time smoke test for the generator.

## First build

This project uses the Gradle wrapper and requires JDK 17 or newer to run Gradle.

```sh
export JAVA_HOME="$(brew --prefix openjdk@17)"
./gradlew check
```

## Generated model catalogs

For an annotated non-generic data class, KSP emits one stable singleton per accessible property:
`val` properties produce read-only `KeyPath`s, while primary-constructor `var` properties produce
copy-based `WritableKeyPath`s. This is optional for one-off paths—`keyPath(Person::name)` works
without an annotation—but gives a model a discoverable canonical API.

```kotlin
@Pathable
data class Person(val name: String, val age: Int, var address: Address)

@Pathable
data class Address(var city: String)

Person("Blob", 100, Address("Paris"))[PersonPaths.name]
// "Blob"

Person("Blob", 100, Address("Paris")).set(
  keyPath = PersonPaths.address,
  value = Address("Toronto"),
)
// Person(name = "Blob", age = 100, address = Address(city = "Toronto"))
```

Paths compose without losing their capabilities. Two writable paths produce another writable
path, so nested immutable updates stay concise:

```kotlin
import dev.kpaths.core.then

val city = PersonPaths.address.then(AddressPaths.city)
val updated = person.setting(city, "Toronto")
```

### Static and generic generated catalogs

Kotlin has no static generic objects. KSP therefore generates an `object` catalog for a
non-generic root and a catalog *class* for a generic root:

```kotlin
@Pathable
data class Person(val name: String)

PersonPaths.name
// an object member: KeyPath<Person, String>

@Pathable
data class Box<Value>(val value: Value)

BoxPaths<String>().value
// an instance member: KeyPath<Box<String>, String>
```

`BoxPaths<String>` is the type of a catalog instance, not an expression, so
`BoxPaths<String>.value` is not valid Kotlin. The `()` invokes KSP's generated inline factory,
which records `String` in the path's root `TypeId`. An explicit-token form is also available for
non-reified generic code:

```kotlin
val paths = BoxPaths.of<String>(TypeId.of<String>())
paths.value
```

The root determines whether a catalog is static or instance-based, not the focused property. A
generic model needs an instance even for a fixed property type, because
`KeyPath<Box<String>, String>` and `KeyPath<Box<Int>, String>` have different root identities.

Generic bounds are preserved by the generated catalog. For a non-null generic root:

```kotlin
@Pathable
sealed interface Loadable<out Value : Any> {
  data object Idle : Loadable<Nothing>
  data class Loaded<T : Any>(val value: T) : Loadable<T>
}

LoadablePaths<String>().Loaded.value
```

`LoadablePaths<String?>()` is rejected by Kotlin for the same reason that
`Loadable<String?>` is rejected: `Value : Any` excludes `null`. This bound also makes the unary
`Loaded.value` case path unambiguous. A generic root without that bound still uses the same
instance syntax, but only receives direct payload paths whose focus is independently non-null.

## Paths for any Kotlin type

`@Pathable` is convenience and source generation, not a requirement. Kotlin property references
provide a concise manual route for accessible properties, including types from another library:

```kotlin
val title = keyPath(ThirdPartyArticle::title)
title.get(article)

val token = referenceWritableKeyPath(ThirdPartySession::token)
token.set(session, "new-token") // mutates this class instance
```

Using `referenceWritableKeyPath` with a data-class root remains allowed, but emits a runtime
warning once per path identity. This catches an accidental in-place mutation of a type normally
used with value semantics. Configure `KPathsRuntimeWarnings.handler` to route those non-fatal
warnings in an application, or set it to `null` to disable the warning checks entirely.

The `keyPath` property-reference adapter deliberately returns a read-only `KeyPath`, even for a
`var`. Choose reference mutation explicitly with `referenceWritableKeyPath`. For value-style
replacement, a property reference can infer the route while the caller supplies reconstruction:

```kotlin
val name = writableKeyPath(
  get = User::name,
  set = { user, name -> user.copy(name = name) },
)
```

A mutable property, including a computed `var`, can instead copy first and invoke its ordinary
setter on that copy:

```kotlin
val displayName = writableKeyPath(
  property = User::displayName,
  copy = { user -> user.copy() },
)
```

Property-reference adapters preserve the concrete root and focus generic arguments at reified call
sites. In a non-reified generic context, use the overload that takes an explicit `valueType`
token (and a `PathId` with the root token you intend). The named custom factories capture the
root token automatically; their name only needs to be stable within that root type. The `PathId`
overloads remain available for a deliberately custom multi-segment identity.

## Identity and standard-library paths

`identityKeyPath` focuses an entire value. It is a copy-based `WritableKeyPath`: replacing its
focus simply returns the replacement root.

```kotlin
val wholePerson = identityKeyPath<Person>()

wholePerson.get(person)                  // person
wholePerson.set(person, replacement)     // replacement
```

KPaths also bundles stable paths for a small set of unambiguous Kotlin standard types. Generic
namespaces capture their concrete type arguments, just as generated namespaces do:

```kotlin
StringPaths.length.get("Blob") // 4
StringPaths.at(1).get("Blob") // 'l'

val pair = PairPaths<String, Int>()
pair.first.get("Blob" to 42) // "Blob"

val second = ListPaths<String>().at(1)
second.get(listOf("Blob", "Jr.")) // "Jr."

val items = mutableListOf("Blob", "Jr.")
MutableListPaths<String>().at(1).set(items, "Revised")
// items is now ["Blob", "Revised"]

val scores = MapPaths<String, Int>().at("blob")
scores.set(mapOf("blob" to 1), null) // removes "blob"
```

`PairPaths` and `TriplePaths` are read-only. `ListPaths` is read-only, including indexed paths;
`ArrayPaths` and `MutableListPaths` expose reference-writable indexed paths because Kotlin lets
those collections mutate their existing instances. `MapPaths` returns a copy-based nullable
lookup path and `MutableMapPaths` returns its in-place counterpart; setting their focus to `null`
always removes the key, including for maps with nullable value types. The primitive-array path
objects (`IntArrayPaths`, `BooleanArrayPaths`, and the other primitive variants) are likewise
reference-writable. Indexed reads and writes retain Kotlin's usual out-of-bounds behavior; string
and `CharSequence` indexing follows Kotlin's UTF-16 `Char` semantics.

Callable references for indexed operators can create canonical paths while supplying their index:

```kotlin
val second = keyPath(get = List<String>::get, at = 1)

val setSecond = referenceWritableKeyPath(
  get = MutableList<String>::get,
  set = MutableList<String>::set,
  at = 1,
)
```

These overloads are deliberately constrained to `List`, `Array`, and `MutableList` roots, and use
the same structural `Index` identity as the standard `at` path factories. Map helpers similarly
preserve the standard map policy:

```kotlin
val score = writableKeyPath(
  get = Map<String, Int>::get,
  key = "score",
)

val mutableScore = referenceWritableKeyPath(
  get = MutableMap<String, Int>::get,
  key = "score",
)
```

The map factories use a `Key` identity segment and treat an assigned `null` as key removal.

## Value-style and reference-style roots

Kotlin data classes are reference objects, but KPaths deliberately treats an annotated data class
as a value-style model: primary-constructor `var` properties receive copy-based
`WritableKeyPath`s. `val` properties always remain read-only `KeyPath`s.

```kotlin
@Pathable
data class Draft(var text: String)

val text = draft[DraftPaths.text]
val updated = draft.setting(DraftPaths.text, "Revised") // returns draft.copy(text = "Revised")
val uppercased = updated.modifying(DraftPaths.text) { it.uppercase() }
```

An annotated plain class instead exposes its accessible mutable properties as
`ReferenceWritableKeyPath`s and immutable properties as read-only `KeyPath`s:

```kotlin
@Pathable
class Session(var token: String, val identifier: String)

session[SessionPaths.token] = "new-token"
session.set(keyPath = SessionPaths.token, value = "another-token")
session[SessionPaths.identifier]
```

The generated path's equality and hash code are based on its `PathId`, rather than closure or
object identity. `PathId` contains structural `TypeId` tokens, preserving a classifier, generic
arguments, variance, and nullability without reflection:

Path metadata uses the reserved `__kpaths_id` and `__kpaths_valueType` names. This leaves ordinary
model and case-payload names such as `id` and `valueType` available in generated path catalogs.

```kotlin
@Pathable
data class Box<T>(val value: T)

val integerValue = BoxPaths.of<Int>(TypeId.of<Int>()).value
integerValue.__kpaths_id.rootType // TypeId("your.package.Box", [TypeArgument.invariant(TypeId.of<Int>())])
```

The preferred generic syntax uses an inline-reified factory, which captures the full type token:

```kotlin
val integerValue = BoxPaths<Int>().value
val stringListValue = BoxPaths<List<String>>().value
```

`BoxPaths.of(Int::class)` is available for simple, non-null types, while
`BoxPaths.of<List<String>>(TypeId.of<List<String>>())` remains the exact no-reflection escape
hatch. `TypeId.of<T>()` is the standard API for creating a complete type token when one is
needed.

## Sealed case paths

Sealed variants generate `CaseKeyPath`s that focus the concrete Kotlin case object, rather than a
tuple-like payload. A failed extraction is `null`; nullable fields remain safely inside the
non-null matched case object.

```kotlin
@Pathable
sealed interface Connection {
  data object Disconnected : Connection
  data class Connected(val host: String?) : Connection
}

val connection: Connection = Connection.Connected(host = null)

connection[ConnectionPaths.Connected]
// Connection.Connected(host = null)

Connection.Disconnected[ConnectionPaths.Connected]
// null

connection[ConnectionPaths.Connected]
  ?.get(keyPath = ConnectionPaths.ConnectedPaths.host)
// null, because the matched case's host is null
```

For a single ordinary sealed case, generation is optional. The concrete case type provides enough
information for KPaths to synthesize the safe cast and upcast:

```kotlin
val connected = caseKeyPath<Connection, Connection.Connected>()

connection[connected] // Connection.Connected(host = null)
```

KSP additionally emits canonical payload paths whenever a data-class case has one to three
accessible primary-constructor properties and can therefore be reconstructed without guessing:

```kotlin
@Pathable
sealed interface Request {
  data class Selected(val value: String) : Request
  data class Navigate(val id: String, val destination: String) : Request
  data class Route(val id: String, val destination: String, val replace: Boolean) : Request
}

RequestPaths.Selected.value
// CaseKeyPath<Request, String>

RequestPaths.Navigate.payload
// CaseKeyPath<Request, Pair<String, String>>

RequestPaths.Route.payload
// CaseKeyPath<Request, Triple<String, String, Boolean>>
```

The outer case singleton remains available (`RequestPaths.Navigate`), and the ordinary structural
property paths remain under `RequestPaths.NavigatePaths.id` and `.destination`. A unary nullable
property does not receive a canonical payload path because `CaseKeyPath` reserves `null` for a
failed match; its ordinary key path is still generated. Cases with more than three constructor
properties likewise retain their whole-case and individual-property paths; model a dedicated
payload data class when that group needs its own path.

Generic sealed roots receive the same direct paths when the focused payload is provably non-null.
The generated case path is an instance member, so it captures the concrete root type token:

```kotlin
@Pathable
sealed interface Loadable<out Value : Any> {
  data object Idle : Loadable<Nothing>
  data class Loaded<T : Any>(val value: T) : Loadable<T>
}

val loaded: CaseKeyPath<Loadable<String>, String> =
  LoadablePaths<String>().Loaded.value
```

An unconstrained `Value` may be nullable, so it intentionally does not receive this direct
payload path yet. The whole-case path (`LoadablePaths<Value>().Loaded`) and
`LoadablePaths<Value>().LoadedPaths.value` remain available.

## Case matching as a key path

`matching()` provides a read-only nullable `KeyPath` view of any `CaseKeyPath`:

```kotlin
val selected: CaseKeyPath<Request, Request.Selected> = RequestPaths.Selected
val selectedValue: KeyPath<Request, Request.Selected?> = selected.matching()

selectedValue.get(Request.Selected("Blob")) // Request.Selected("Blob")
selectedValue.get(Request.Navigate("id-1", "settings")) // null
```

It is intentionally read-only. Assigning `null` to such a view could mean “do nothing,” “reject
the update,” or “change to another case,” and KPaths does not choose one of those meanings.

An enum entry has no distinct Kotlin type, so pass its singleton value instead:

```kotlin
val north = caseKeyPath(Direction.NORTH)
```

For custom matching behavior, use `caseKeyPath(id = ..., valueType = ..., extract = ..., embed =
...)`. The focus must be non-null: `null` always denotes a failed match, while nullable fields
remain inside a matched case object.

For an ordinary data-class case, constructor references provide a concise manual path factory.
The property references read the case payload and the constructor reference proves that the case
can be reconstructed:

```kotlin
sealed interface Navigation

data class Document(val title: String)

data class OpenDocument(val document: Document) : Navigation

val openDocument = caseKeyPath<Navigation, OpenDocument, _>(
  extract = OpenDocument::document,
  embed = ::OpenDocument,
)
// CaseKeyPath<Navigation, Document>
```

Two and three payloads are represented losslessly as `Pair` and `Triple`, respectively. The
property order must match the constructor-reference parameter order:

```kotlin
data class Navigate(val id: String, val destination: String) : Navigation

val navigate = caseKeyPath<Navigation, Navigate, _, _>(
  extract1 = Navigate::id,
  extract2 = Navigate::destination,
  embed = ::Navigate,
)
// CaseKeyPath<Navigation, Pair<String, String>>

data class Route(val id: String, val destination: String, val replace: Boolean) : Navigation

val route = caseKeyPath<Navigation, Route, _, _, _>(
  extract1 = Route::id,
  extract2 = Route::destination,
  extract3 = Route::replace,
  embed = ::Route,
)
// CaseKeyPath<Navigation, Triple<String, String, Boolean>>
```

There are deliberately no higher-arity overloads. Prefer a dedicated payload data class when a
case has four or more independently meaningful values.

Case-path names retain their Kotlin declaration spelling: class-like sealed cases use `Connected`,
while enum entries use `NORTH`. The nested `ConnectedPaths` namespace is generated inside the
KSP-owned `ConnectionPaths` object; an eventual compiler-plugin façade may offer
`Connection.Paths` as an additional spelling.

For a sealed/enum root, the generated namespace is also a finite case catalog:

```kotlin
ConnectionPaths.allCasePaths
// [ConnectionPaths.Disconnected, ConnectionPaths.Connected]

ConnectionPaths.caseOf(connection)
// ConnectionPaths.Connected
```

`allCasePaths` contains `PartialCaseKeyPath<Connection>` values suitable for generic inspection;
`caseOf` returns the one generated path that matches a particular root value.

Kotlin backticked identifiers remain valid in generated paths. KSP preserves their semantic name
and emits the required backticks for properties, enum entries, and sealed cases:

```kotlin
@Pathable
data class KeywordProperty(val `display name`: String)

KeywordProperty("Blob")[KeywordPropertyPaths.`display name`]
// "Blob"

@Pathable
enum class KeywordDirection { `when` }

KeywordDirection.`when`[KeywordDirectionPaths.`when`]
// Unit
```
## Enum case paths

Enum entries have no associated data, so their generated `CaseKeyPath` focus is Kotlin's `Unit`
(the equivalent of Swift's `Void`):

```kotlin
@Pathable
enum class Direction { NORTH, SOUTH }

Direction.NORTH[DirectionPaths.NORTH] // Unit
Direction.SOUTH[DirectionPaths.NORTH] // null
DirectionPaths.NORTH.embed(Unit)      // Direction.NORTH
```

The first case-path slice supports non-generic sealed classes, sealed interfaces, and enums.

## Generic sealed case paths

Generic sealed roots preserve their concrete type arguments in their generated path identities:

```kotlin
@Pathable
sealed interface Loadable<out Value> {
  data object Idle : Loadable<Nothing>
  data class Loaded<T>(val value: T) : Loadable<T>
}

val paths = LoadablePaths<String>()
val state: Loadable<String> = Loadable.Loaded("Blob")

state[paths.Loaded]?.get(keyPath = paths.LoadedPaths.value)
// "Blob"
```

For a generic root, every variant must either forward a root type parameter or safely refine a
covariant parameter to `Nothing`. KPaths reports an error at `@Pathable` when Kotlin cannot embed
a variant as every specialization of the root.

## Generated-name collisions

KPaths preserves declared property and case names rather than silently renaming public paths. If
two generated members would share a namespace—for example, a `Connected` property and a
`Connected` sealed case—KSP reports an error at `@Pathable` explaining both members. Names
beginning with `__kpaths_` are reserved for generator implementation details.

By default, `@Pathable` generates `TypeNamePaths`. Supply a name to use an explicit top-level
namespace instead:

```kotlin
@Pathable("CustomRecordPaths")
data class Record(val identifier: String)

CustomRecordPaths.identifier
```

KSP diagnoses a collision with an existing top-level type, property, or function of the generated
name and points to `@Pathable`; it also diagnoses two `@Pathable` types that request the same
name. Choose a different explicit name in either case.

## Acknowledgments

KPaths is inspired by Swift's native key-path system and
[Point-Free's CasePaths](https://github.com/pointfreeco/swift-case-paths). It is designed as an
independent Kotlin library with Kotlin-native APIs and semantics.
