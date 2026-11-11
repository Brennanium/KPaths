package dev.kpaths.compiler.testing

import java.math.BigInteger

/** Selects an exact override first, then a single matching pattern. Ambiguity is an error. */
internal fun selectCompilerVersion(selectors: Set<String>, compilerVersion: String): String? {
  val version = CompilerVersion.parse(compilerVersion)
  val patterns = selectors.associateWith(::parseSelector)
  if (compilerVersion in selectors) return compilerVersion
  val matches = patterns.filterValues { it(version) }.keys
  require(matches.size <= 1) {
    "Ambiguous compiler snapshot selectors for $compilerVersion: ${matches.sorted().joinToString()}."
  }
  return matches.singleOrNull()
}

private fun parseSelector(selector: String): (CompilerVersion) -> Boolean {
  val wildcard = Regex("^(\\d+)\\.(\\d+)\\.\\*$").matchEntire(selector)
  if (wildcard != null) {
    val major = wildcard.groupValues[1].toBigInteger()
    val minor = wildcard.groupValues[2].toBigInteger()
    return { it.major == major && it.minor == minor }
  }
  val parts = selector.trim().split(Regex("\\s+"))
  val comparisons = parts.map { part ->
    val match = Regex("^(>=|<=|>|<)(.+)$").matchEntire(part)
    if (match == null) {
      require(parts.size == 1) { "Invalid compiler version selector: $selector" }
      val exact = CompilerVersion.parse(part)
      val predicate: (CompilerVersion) -> Boolean = { candidate -> candidate.original == exact.original }
      predicate
    } else {
      val operator = match.groupValues[1]
      val boundary = CompilerVersion.parse(match.groupValues[2])
      val predicate: (CompilerVersion) -> Boolean = { candidate ->
        val comparison = candidate.compareTo(boundary)
        when (operator) {
          ">=" -> comparison >= 0
          "<=" -> comparison <= 0
          ">" -> comparison > 0
          else -> comparison < 0
        }
      }
      predicate
    }
  }
  return { candidate -> comparisons.all { it(candidate) } }
}

/** Kotlin-style version ordering: numeric core and qualifier components; final follows previews. */
private data class CompilerVersion(
  val original: String,
  val major: BigInteger,
  val minor: BigInteger,
  val patch: BigInteger,
  val qualifier: List<String>,
) : Comparable<CompilerVersion> {
  override fun compareTo(other: CompilerVersion): Int {
    for ((left, right) in listOf(major to other.major, minor to other.minor, patch to other.patch)) {
      val result = left.compareTo(right)
      if (result != 0) return result
    }
    if (qualifier.isEmpty() || other.qualifier.isEmpty()) return when {
      qualifier.isEmpty() && other.qualifier.isEmpty() -> 0
      qualifier.isEmpty() -> 1
      else -> -1
    }
    for (index in 0 until minOf(qualifier.size, other.qualifier.size)) {
      val left = qualifier[index]
      val right = other.qualifier[index]
      val leftNumber = left.toBigIntegerOrNull()
      val rightNumber = right.toBigIntegerOrNull()
      val result = when {
        leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
        leftNumber != null -> -1
        rightNumber != null -> 1
        else -> left.compareTo(right)
      }
      if (result != 0) return result
    }
    return qualifier.size.compareTo(other.qualifier.size)
  }

  companion object {
    fun parse(value: String): CompilerVersion {
      val match = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*))?$").matchEntire(value)
        ?: throw IllegalArgumentException("Invalid compiler version: $value")
      val qualifier = match.groupValues[4]
      return CompilerVersion(value, match.groupValues[1].toBigInteger(), match.groupValues[2].toBigInteger(),
        match.groupValues[3].toBigInteger(), if (qualifier.isEmpty()) emptyList() else qualifier.split('.', '-'))
    }
  }
}
