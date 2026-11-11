package dev.kpaths.compiler.testing

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/** Rewrites only identified assertion arguments, and refuses ambiguous or unsupported shapes. */
@Suppress("OPT_IN_USAGE_ERROR")
@OptIn(CompilerConfiguration.Internals::class, ExperimentalCompilerApi::class)
internal object InlineCompilerSnapshots {
  private val lock = Any()

  fun write(file: Path, line: Int, values: Map<CompilerSnapshotKind, String>, version: String, versioned: Boolean, expectedSource: String? = null) = synchronized(lock) {
    require(values.isNotEmpty())
    val original = Files.readString(file)
    check(expectedSource == null || original == expectedSource) { "Source changed while compilation was running: $file." }
    val disposable = Disposer.newDisposable()
    try {
      val configuration = CompilerConfiguration().apply { extensionsStorage = CompilerPluginRegistrar.ExtensionStorage() }
      val environment = KotlinCoreEnvironment.createForTests(disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)
      val parsed = KtPsiFactory(environment.project).createFile(original)
      fun lineOf(offset: Int) = original.take(offset).count { it == '\n' } + 1
      val candidates = PsiTreeUtil.collectElementsOfType(parsed, KtCallExpression::class.java).filter {
        it.calleeExpression?.text == "assertCompiler" &&
          line in lineOf(it.textRange.startOffset)..lineOf(it.textRange.endOffset)
      }
      require(candidates.size == 1) { "Expected exactly one assertCompiler call at $file:$line, found ${candidates.size}." }
      val call = candidates.single()
      val args = call.valueArgumentList ?: error("assertCompiler has no argument list at $file:$line.")
      val edits = mutableListOf<Triple<Int, Int, String>>()
      val additions = mutableListOf<Pair<String, String>>()
      val start = original.lastIndexOf('\n', call.textRange.startOffset - 1) + 1
      val indentation = original.substring(start, call.textRange.startOffset).takeWhile { it == ' ' || it == '\t' }
      fun literal(value: String): String {
        val clean = value.trimEnd('\n', '\r')
        // Kotlin raw strings still interpolate dollars. Regular escaped strings safely handle the
        // rare case where the compiler output itself contains a triple-quote delimiter.
        if ("\"\"\"" in clean) return "\"" + clean.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("\n", "\\n").replace("\r", "\\r") + "\""
        val content = clean.replace("$", "${'$'}{'${'$'}'}")
        val inner = indentation + "    "
        return "\"\"\"\n" + content.lines().joinToString("\n") { if (it.isEmpty()) "" else inner + it } + "\n" + inner + "\"\"\""
      }
      fun setExpression(expression: org.jetbrains.kotlin.psi.KtExpression, value: String) {
        require(expression is KtStringTemplateExpression) { "Snapshot must be a literal string, got ${expression.text}." }
        edits += Triple(expression.textRange.startOffset, expression.textRange.endOffset, literal(value))
      }
      for ((kind, value) in values) {
        val name = when (kind) { CompilerSnapshotKind.FIR -> "fir"; CompilerSnapshotKind.IR -> "ir"; else -> error("Unsupported inline snapshot kind: ${kind.name}.") }
        val matches = args.arguments.filter { it.getArgumentName()?.asName?.asString() == name }
        require(matches.size <= 1) { "More than one $name argument at $file:$line." }
        val argument = matches.singleOrNull()
        if (argument == null) {
          require(!versioned) { "Record the shared $name expectation before a version-specific one." }
          additions += name to value
          continue
        }
        val expression = argument.getArgumentExpression() as? KtCallExpression
          ?: error("$name must be snapshot() or SnapshotExpectation(...).")
        if (expression.calleeExpression?.text == "snapshot" && expression.valueArguments.isEmpty()) {
          require(!versioned) { "Record the shared $name expectation before a version-specific one." }
          edits += Triple(expression.textRange.startOffset, expression.textRange.endOffset, "SnapshotExpectation(${literal(value)})")
          continue
        }
        require(expression.calleeExpression?.text == "SnapshotExpectation") { "Unsupported $name expectation expression: ${expression.text}" }
        val expectationArgs = expression.valueArguments
        val default = expectationArgs.firstOrNull { it.getArgumentName()?.asName?.asString() == "default" }
          ?: expectationArgs.firstOrNull { it.getArgumentName() == null }
          ?: error("$name expectation has no default literal.")
        val versionArg = expectationArgs.firstOrNull { it.getArgumentName()?.asName?.asString() == "versions" }
          ?: expectationArgs.filter { it.getArgumentName() == null }.getOrNull(1)
        val versionMap = versionArg?.getArgumentExpression() as? KtCallExpression
        if (versionArg != null) require(versionMap?.calleeExpression?.text == "mapOf") { "Version overrides must use mapOf with literal keys." }
        val entry = versionMap?.valueArguments?.map { it.getArgumentExpression() as? KtBinaryExpression ?: error("Version overrides must be literal pairs.") }
          ?.firstOrNull { it.left?.text == "\"$version\"" && it.operationReference.text == "to" }
        when {
          entry != null -> setExpression(entry.right ?: error("Missing version expectation."), value)
          versioned && versionMap != null -> {
            val list = versionMap.valueArgumentList ?: error("Invalid version map.")
            val insert = list.rightParenthesis?.textRange?.startOffset ?: error("Invalid version map.")
            val separator = if (list.arguments.isEmpty()) "" else if (list.text.trimEnd().dropLast(1).trimEnd().endsWith(",")) " " else ", "
            edits += Triple(insert, insert, "$separator\"$version\" to ${literal(value)}")
          }
          versioned -> {
            val list = expression.valueArgumentList ?: error("Invalid expectation.")
            val insert = list.rightParenthesis?.textRange?.startOffset ?: error("Invalid expectation.")
            val separator = if (list.text.trimEnd().dropLast(1).trimEnd().endsWith(",")) " " else ", "
            edits += Triple(insert, insert, "${separator}versions = mapOf(\"$version\" to ${literal(value)})")
          }
          else -> setExpression(default.getArgumentExpression() ?: error("Missing default expectation."), value)
        }
      }
      if (additions.isNotEmpty()) {
        val close = args.rightParenthesis?.textRange?.startOffset ?: error("assertCompiler has no closing parenthesis.")
        val beforeClose = original.substring(args.textRange.startOffset, close)
        val separator = if (beforeClose.trimEnd().endsWith(",")) "" else ","
        val additionText = additions.joinToString("") { (name, value) -> "${indentation}  $name = SnapshotExpectation(${literal(value)}),\n" }
        val lineStart = original.lastIndexOf('\n', close - 1) + 1
        require(original.substring(lineStart, close).isBlank()) { "The closing parenthesis must be on its own line." }
        edits += Triple(lineStart, lineStart, additionText)
        if (separator.isNotEmpty()) {
          val lastArg = args.arguments.lastOrNull() ?: error("assertCompiler needs source and directory arguments.")
          edits += Triple(lastArg.textRange.endOffset, lastArg.textRange.endOffset, separator)
        }
      }
      var updated = original
      edits.sortedByDescending { it.first }.forEach { (start, end, replacement) ->
        updated = updated.replaceRange(start, end, replacement)
      }
      // Don't overwrite edits made by the developer while compilation was in progress.
      check(Files.readString(file) == original) { "Source changed while recording: $file." }
      val temporary = Files.createTempFile(file.parent, ".kpaths-snapshot-", ".tmp")
      try {
        Files.writeString(temporary, updated)
        val attributes = Files.getFileAttributeView(file, PosixFileAttributeView::class.java)
        if (attributes != null) Files.setPosixFilePermissions(temporary, attributes.readAttributes().permissions())
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      } finally {
        Files.deleteIfExists(temporary)
      }
    } finally {
      ApplicationManager.getApplication().runWriteAction { Disposer.dispose(disposable) }
    }
  }
}
