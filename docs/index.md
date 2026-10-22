# Module KPaths

KPaths provides typed, comparable routes through Kotlin values. A **key path** reads a property
or collection element. A writable key path also replaces that focus, either by returning a new
root or by mutating a reference root. A **case path** matches one variant of a sealed hierarchy
and can construct that variant again.

The library deliberately keeps Kotlin's own language model visible:

- `KProperty1` and `KMutableProperty1` make paths available for accessible properties, including
  types the application does not own.
- `@Pathable` uses KSP only to generate discoverable, canonical catalogs for source-owned types.
- Data-class updates are modeled as replacement values; mutable classes can opt into explicit
  reference mutation.
- Exact case matching uses `CaseMatch`, so a matching nullable payload is never confused with a
  different case.

## Start here

The repository guides are the best introduction. Read `docs/key-paths.md`,
`docs/case-paths.md`, `docs/generation.md`, and `docs/identity.md` alongside this reference.

## API reference

Run `./gradlew :dokkaGenerate` from the repository root. The HTML output is written to
`build/docs/api`. This reference is generated from the public KDoc in `kpaths-core` and
`kpaths-annotations`; it deliberately excludes processor internals and the sample module.
