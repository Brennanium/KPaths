package dev.kpaths.ksp

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.validate
import dev.kpaths.annotations.Pathable

private val pathableAnnotation = Pathable::class.qualifiedName!!

/** The first generator milestone: prove annotation discovery and generated-source wiring. */
public class KPathsProcessorProvider : SymbolProcessorProvider {
  override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
    KPathsProcessor(environment.codeGenerator, environment.logger)
}

private class KPathsProcessor(
  private val codeGenerator: CodeGenerator,
  private val logger: KSPLogger,
) : SymbolProcessor {
  override fun process(resolver: Resolver): List<KSAnnotated> {
    val deferred = mutableListOf<KSAnnotated>()
    val declarations = resolver.getSymbolsWithAnnotation(pathableAnnotation)
      .asSequence()
      .filterIsInstance<KSClassDeclaration>()

    declarations.forEach { declaration ->
      if (!declaration.validate(enableNewFeatures = true)) {
        deferred += declaration
        return@forEach
      }
      generateMarker(declaration)
    }

    return deferred
  }

  private fun generateMarker(declaration: KSClassDeclaration) {
    val qualifiedName = declaration.qualifiedName?.asString() ?: run {
      logger.error("@Pathable types must have a qualified name.", declaration)
      return
    }
    val packageName = declaration.packageName.asString()
    val generatedName = qualifiedName.replace('.', '_') + "__KPaths"
    val propertyCount = declaration.getDeclaredProperties().count()
    val containingFile = declaration.containingFile ?: run {
      logger.error("@Pathable types must originate from a source file.", declaration)
      return
    }

    codeGenerator.createNewFile(
      dependencies = Dependencies(false, containingFile),
      packageName = packageName,
      fileName = generatedName,
    ).bufferedWriter().use { writer ->
      writer.appendLine("package $packageName")
      writer.appendLine()
      writer.appendLine("/** Generated metadata for $qualifiedName. */")
      writer.appendLine("public object $generatedName {")
      writer.appendLine("  public const val sourceType: String = \"$qualifiedName\"")
      writer.appendLine("  public const val declaredPropertyCount: Int = $propertyCount")
      writer.appendLine("}")
    }
  }
}
