# Advanced KeyPath APIs

The [KeyPaths guide](key-paths.md) explains how to create, read, write, and compose paths. This
guide is about a different question: what can a reusable library do when it retains a KeyPath
instead of accepting an opaque closure or a string property name?

Typed, composable, structurally identifiable paths let these APIs retain the meaning of a field
instead of reducing it to a string name or an opaque closure.

## Build a heterogeneous field schema

`PartialKeyPath<Root>` keeps a concrete root type while erasing only the focused value type. It
can still read a value with `getAny`, and retains its `PathId` and `TypeId`. That makes it a useful
building block for an inspector, table, export format, or diagnostics panel with fields of many
different types:

```kotlin
@Pathable
data class Book(
  val title: String,
  val year: Int,
  val inPrint: Boolean,
) {
  companion object
}

data class Field<Root>(
  val title: String,
  val path: PartialKeyPath<Root>,
)

val bookFields: List<Field<Book>> = listOf(
  Field("Title", Book.Paths.title),
  Field("Year", Book.Paths.year),
  Field("In print", Book.Paths.inPrint),
)

fun <Root> inspect(root: Root, fields: List<Field<Root>>): Map<String, Any?> =
  fields.associate { field -> field.title to field.path.getAny(root) }
```

The schema keeps the root type checked: a `PartialKeyPath<Author>` cannot enter
`List<Field<Book>>`. The focused values are intentionally erased only at the point where a
heterogeneous UI or exporter needs them. A renderer can use `field.path.__kpaths_valueType` to
choose formatting, while `field.path.__kpaths_id` gives the field a stable in-process identity.

For a typed operation such as sorting, filtering, or validation, retain the full
`KeyPath<Root, Value>` instead. It is both a typed field descriptor and a Kotlin read function:

```kotlin
data class SortRule<Root, Value : Comparable<Value>>(
  val field: KeyPath<Root, Value>,
  val descending: Boolean,
)

fun <Root, Value : Comparable<Value>> List<Root>.sortedBy(
  rule: SortRule<Root, Value>,
): List<Root> =
  if (rule.descending) sortedByDescending(rule.field) else sortedBy(rule.field)

val byTitle = SortRule(Book.Paths.title, descending = false)
```

The same idea applies to allow-lists, validation metadata, redaction policies, analytics field
names, and query plans, all while preserving field identity and typed access.

## Record reversible edits

`WritableKeyPath` carries both halves of a value-style edit: how to read a field and how to put a
replacement value back into its root. An undo library can define its own change protocol without
KPaths needing to ship an undo manager:

```kotlin
interface Change<Root> {
  fun apply(root: Root): Root
  fun revert(root: Root): Root
}

data class FieldChange<Root, Value>(
  val path: WritableKeyPath<Root, Value>,
  val oldValue: Value,
  val newValue: Value,
) : Change<Root> {
  override fun apply(root: Root): Root = path.set(root, newValue)
  override fun revert(root: Root): Root = path.set(root, oldValue)
}

@Pathable
data class Draft(var title: String, var body: String) {
  companion object
}

val rename: Change<Draft> = FieldChange(
  path = Draft.Paths.title,
  oldValue = "First draft",
  newValue = "Final draft",
)

rename.apply(Draft("First draft", "…"))
// Draft("Final draft", "…")
```

Different `FieldChange<Root, Value>` values can be stored behind `Change<Root>`, so an undo stack
can hold edits to differently typed fields. The typed constructor prevents a title path from
receiving a non-`String` replacement before that erasure happens.

This pattern also supports edit coalescing: two edits concern the same field when their paths are
equal. It can form the basis of undo history, draft synchronization, or an event stream.

## Project state through a writable path

A state or binding library can project a container of `Root` into a container of `Value` using a
`WritableKeyPath`. The following small mutable box makes that projection explicit:

```kotlin
interface MutableBox<Value> {
  var value: Value
}

fun <Root, Value> MutableBox<Root>.focus(
  path: WritableKeyPath<Root, Value>,
): MutableBox<Value> {
  val rootBox = this
  return object : MutableBox<Value> {
    override var value: Value
      get() = rootBox.value[path]
      set(value) {
        rootBox.value = rootBox.value.setting(path, value)
      }
  }
}

class Box<Value>(override var value: Value) : MutableBox<Value>

val screen = Box(Draft(title = "First draft", body = "…"))
val title = screen.focus(Draft.Paths.title)

title.value = "Final draft"
screen.value.title // "Final draft"
```

Any state holder with a mutable value can offer this operation. A framework integration can provide
the same `focus` API for its native state type.

## Track related paths for selective invalidation

Dependency graphs, subscription registries, and cache invalidators can inspect path relationships.
The advanced relationship helpers compare paths directly:

```kotlin
@Pathable
data class Address(var city: String) {
  companion object
}

@Pathable
data class Profile(var address: Address) {
  companion object
}

val address = Profile.Paths.address
val city = address.then(Address.Paths.city)

address.isPrefixOf(city)      // true
city.isDescendantOf(address)  // true
city.descendantSegments(after = address)
// [PathSegment.Property("city")]
```

For example, an invalidator can treat two paths as related when either is an ancestor of the other:

```kotlin
fun pathsOverlap(first: AnyPath, second: AnyPath): Boolean =
  first.isPrefixOf(second) || first.isDescendantOf(second)
```

The helpers provide a structural relationship signal that an observer, registry, or cache can use
when deciding what to update.

## Keep typed paths at API boundaries

The common thread is that a KeyPath should remain a KeyPath while an API still needs to know what
field it addresses. Reducing it immediately to `(Root) -> Value` is fine for a one-off operation,
but discards composition, write capability, type metadata, and structural identity.

Use a closure for arbitrary behavior. Use a KeyPath for a field that a library needs to retain,
classify, compose, compare, or update. The [API design guide](route-aware-apis.md) contains smaller
examples of that decision, while [Identity and type metadata](identity.md) describes the underlying
equality model.
