# Identity and type metadata

KeyPaths and CaseKeyPaths are values. Two independently-created values that describe the same
KeyPath or CaseKeyPath should compare
equal and have the same hash code; equality must not depend on which closure instance happens to
implement a getter.

Every KeyPath and CaseKeyPath therefore carries two public, read-only pieces of metadata:

- `PathId` identifies the root type and ordered KeyPath or CaseKeyPath segments.
- `TypeId` identifies the focused type, retaining generic arguments, variance, and nullability.

The library-reserved metadata properties are named `__kpaths_id` and `__kpaths_valueType`. They
are intentionally unusual names so ordinary model properties such as `id` and `valueType` can be
generated without a collision.

```kotlin
val first = keyPath<Person, String>("normalizedName") { it.name.trim().lowercase() }
val second = keyPath<Person, String>("normalizedName") { it.name.trim().lowercase() }

check(first == second)
check(first.hashCode() == second.hashCode())
```

The name in a manual named factory is therefore semantic, not a display label. Choose a stable,
root-local name. If two distinct KeyPaths share a name and root type, they claim the same identity;
if the same conceptual KeyPath gets different names, it will not compare equal.

## Why `TypeId` is richer than `KClass`

`String::class` identifies the classifier `String`, but cannot distinguish `List<String>` from
`List<Int>` or `String` from `String?`. `TypeId.of<T>()` uses Kotlin's reified type information to
preserve those distinctions:

```kotlin
TypeId.of<List<String>>()
TypeId.of<List<Int>>()
TypeId.of<String?>()
```

The `TypeId.from(SomeType::class)` form is available when only a non-generic class token exists.
For a generic algorithm that is not inline/reified, accept an explicit `TypeId` from its caller
and use the low-level factory overloads. This is the Kotlin equivalent of explicitly carrying the
type metadata that Swift can often recover from its runtime generic metadata.

## Structural segments

`PathId` has property, case, index, key, and custom segments. Composition appends segments while
retaining the original root type. This is why a composed KeyPath or CaseKeyPath has one stable identity rather than
a nested chain of object identities.

Most code never constructs a `PathId` directly. Use generated catalogs, property references, or
named manual factories. The low-level `PathId` APIs exist for library authors defining a custom
KeyPath or CaseKeyPath whose identity must remain stable across modules or call sites.

## Why identity changes API design

A closure can perform a read, but Kotlin offers no meaningful structural equality or hash for two
closures. An API that accepts `(Root) -> Value` therefore cannot know whether two callers selected
the same field. It cannot safely deduplicate registrations, reuse a prepared KeyPath, or construct
a stable identifier from that selection.

KeyPaths and CaseKeyPaths solve that narrow problem without making arbitrary closures pretend to be comparable:

```kotlin
data class Book(val title: String)

val visibleColumns = linkedSetOf<AnyKeyPath>()
visibleColumns += keyPath(Book::title)
visibleColumns += keyPath(Book::title)

check(visibleColumns.size == 1)
```

This makes a KeyPath or CaseKeyPath a good component of a subscription, prepared-query, or configuration key.
When caching a computed **value**, include the input identity or version too; a KeyPath identifies the
field being read, not one particular root value. See [KeyPath and CaseKeyPath API design](route-aware-apis.md) for
the broader design patterns.
