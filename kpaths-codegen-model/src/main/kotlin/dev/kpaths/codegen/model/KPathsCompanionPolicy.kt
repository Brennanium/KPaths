package dev.kpaths.codegen.model

/** Detects a bare companion followed by a declaration that Kotlin may parse as its name. */
public fun hasAmbiguousKPathsCompanion(source: String, companionLine: Int): Boolean {
  if (companionLine < 1) return false
  var lineStart = 0
  repeat(companionLine - 1) {
    lineStart = source.indexOf('\n', lineStart).takeIf { it >= 0 }?.plus(1) ?: return false
  }
  val lineEnd = source.indexOf('\n', lineStart).takeIf { it >= 0 } ?: source.length
  val match = companionObjectPattern.findAll(source)
    .takeWhile { it.range.first < lineEnd }.lastOrNull() ?: return false
  var index = match.range.last + 1
  var crossedLine = false
  while (index < source.length) {
    when {
      source[index].isWhitespace() -> {
        crossedLine = crossedLine || source[index] == '\n'
        index++
      }
      source.startsWith("//", index) -> {
        val end = source.indexOf('\n', index).takeIf { it >= 0 } ?: return false
        crossedLine = true
        index = end + 1
      }
      source.startsWith("/*", index) -> {
        val end = source.indexOf("*/", index + 2).takeIf { it >= 0 } ?: return false
        crossedLine = crossedLine || source.substring(index, end).contains('\n')
        index = end + 2
      }
      else -> break
    }
  }
  if (!crossedLine || index >= source.length || source[index] in setOf('{', '}')) return false
  if (source[index] == '@') return true
  return source.substring(index).takeWhile { it.isLetterOrDigit() || it == '_' } in classMemberStarters
}

private val companionObjectPattern = Regex("\\bcompanion\\s+object\\b")
private val classMemberStarters = setOf(
  "abstract", "actual", "annotation", "class", "const", "constructor", "data", "enum",
  "expect", "external", "final", "fun", "infix", "init", "inline", "inner", "interface",
  "internal", "lateinit", "object", "open", "operator", "override", "private", "protected",
  "public", "sealed", "suspend", "tailrec", "typealias", "val", "var",
)
