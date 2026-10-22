# Generated catalogs

`@Pathable` is an optional KSP convenience. It does not grant the runtime capabilities of a path:
manual factories work for any accessible property or ordinary sealed case. Its job is to generate
the canonical path objects that a source-owned model uses repeatedly.

```kotlin
@Pathable
data class Account(
  val id: String,
  var displayName: String,
)

AccountPaths.id          // KeyPath<Account, String>
AccountPaths.displayName // WritableKeyPath<Account, String>
```

This makes paths discoverable in code completion, avoids repeated property-reference adaptation,
and gives callers a shared singleton with a stable structural identity.

## What is generated

For accessible properties on an annotated type:

| Kotlin declaration | Generated path |
| --- | --- |
| `val property` | `KeyPath<Root, Value>` |
| data-class primary-constructor `var property` | copy-based `WritableKeyPath<Root, Value>` |
| mutable plain-class property | `ReferenceWritableKeyPath<Root, Value>` |

The `val`/`var` distinction is preserved even for data classes. A `val` never becomes writable
merely because its containing data class has a `copy` method.

For a sealed root, KSP additionally emits a case catalog. The catalog contains writable whole-case
paths, optional direct payload conveniences, a `caseOf(root)` lookup, and `allCasePaths` for
type-erased catalog inspection. Enum entries become `WritableCaseKeyPath<Enum, Unit>` paths.

## Static versus generic catalogs

Kotlin object declarations cannot capture an outer type argument. KSP therefore uses an object
for non-generic roots and a catalog class for generic roots:

```kotlin
@Pathable data class Person(val name: String)
PersonPaths.name

@Pathable data class Box<Value>(val value: Value)
BoxPaths<String>().value
```

The parentheses in `BoxPaths<String>()` create the generated catalog with the concrete generic
type token. `BoxPaths<String>.value` is a type member access, not an object instance, and is not
valid Kotlin. In non-reified generic code, use the generated explicit-token `of` factory.

## Naming and diagnostics

The default catalog name is `TypeNamePaths`. Give the annotation a string argument to choose a
different public catalog name:

```kotlin
@Pathable("AccountOptics")
data class Account(val id: String)

AccountOptics.id
```

KSP preserves declared case names wherever Kotlin permits them, including backticked identifiers.
It reports collisions that would make a generated catalog or member ambiguous. Generated internal
metadata uses the deliberately reserved `__kpaths_` prefix; applications should not declare that
prefix in an annotated model.

## When not to use it

Use a manual property-reference path when the property is one-off, belongs to a third-party type,
or the model should not take a code-generation dependency. Use a manual named factory for a
computed route that is not a Kotlin property. `@Pathable` is most valuable when a model's paths
are part of a reusable public API.
