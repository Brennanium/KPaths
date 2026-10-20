package dev.kpaths.core

/**
 * An extraction result that distinguishes an absent route from a route whose value is `null`.
 */
public sealed interface Match<out Value> {
  public data object Missing : Match<Nothing>
  public data class Found<Value>(public val value: Value) : Match<Value>
}
