package dev.kpaths.compiler.fixture

/**
 * Kotlin permits type arguments on an object's generic `invoke` operator. This is useful because
 * a generated nested object itself cannot capture `Box`'s type parameter, but it can expose
 * `Box.Paths<String>()` as a generic factory.
 */
class GenericBox<Value> {
  object Paths {
    operator fun <Value> invoke(): GenericBox<Value>? = null
  }
}

val genericBoxPathsSyntax: GenericBox<String>? = GenericBox.Paths<String>()
