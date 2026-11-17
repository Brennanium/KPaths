package dev.kpaths.testing.editor

/** Opens a test source in a prepared editor environment and exposes its observations. */
public fun interface EditorSourceHost {
  public fun open(fileName: String, source: String): EditorObservations
}

/**
 * Open inline source in a test host and check the resulting editor observations. The host owns
 * its IDE/project lifecycle and must make the relevant dependencies and plugin available.
 */
public fun assertEditor(
  source: String,
  host: EditorSourceHost,
  fileName: String = "Test.kt",
  block: EditorExpectations.() -> Unit,
) {
  require(fileName.isNotBlank()) { "The source file name must not be blank." }
  assertEditor(host.open(fileName, source.trimIndent()), block = block)
}
