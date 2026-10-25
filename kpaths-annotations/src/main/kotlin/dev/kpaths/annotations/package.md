# Package dev.kpaths.annotations

The opt-in annotations consumed by the KPaths KSP processor.

[Pathable] asks KSP to generate a canonical property and case-path catalog for a source-owned
Kotlin type. It is a convenience and discoverability feature: the runtime path factories remain
available for any accessible type without annotation processing.

[PathName] renames a generated property-path member. [CaseName] renames a generated whole-case
member, while [PathsName] renames either a sealed data case's nested property-path catalog or a
companion's generated root facade. None of these annotations changes a path's structural identity.
