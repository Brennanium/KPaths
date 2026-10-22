# Package dev.kpaths.core

The KPaths runtime API.

Start with [KeyPath] for total property reads, [WritableKeyPath] for value-style replacement, and
[ReferenceWritableKeyPath] for explicit in-place mutation. [CaseKeyPath] conditionally matches a
sealed case; [WritableCaseKeyPath] conditionally replaces it, while
[ReferenceWritableCaseKeyPath] conditionally mutates its matched concrete case.

Prefer Kotlin property-reference factories such as `keyPath(Person::name)` for a one-off route.
Use generated `@Pathable` catalogs when a source-owned model exposes paths as part of its normal
API. See the repository guides for the full mental model and recipes.
