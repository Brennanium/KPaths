# Package dev.kpaths.core

The KPaths runtime API.

Start with [KeyPath] for total property reads, [WritableKeyPath] for value-style replacement, and
[ReferenceWritableKeyPath] for explicit in-place mutation. [CasePath] models an exact match and
constructor for a sealed variant; [CaseKeyPath] is its non-null-focus specialization.

Prefer Kotlin property-reference factories such as `keyPath(Person::name)` for a one-off route.
Use generated `@Pathable` catalogs when a source-owned model exposes paths as part of its normal
API. See the repository guides for the full mental model and recipes.
