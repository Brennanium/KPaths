# KPaths

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

Use the guides in the navigation to learn the model, then open the [API reference](api/index.md) for the
complete generated KDoc. The reference includes public declarations from `kpaths-core` and
`kpaths-annotations`, but deliberately excludes processor internals and the sample module.

## Build locally

Install the pinned documentation dependency once, then build the complete static site:

```sh
python3 -m venv .venv-docs
. .venv-docs/bin/activate
python3 -m pip install -r docs/requirements.txt
./gradlew docsBuild
```

The result is `build/docs/site`. Run `./gradlew docsServe` to serve that exact output locally at
<http://localhost:8000>. The tasks automatically use `.venv-docs/bin/python`; use
`-PdocsPython=/absolute/path/to/python` to choose another interpreter in CI.
