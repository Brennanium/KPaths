package dev.kpaths.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import dev.kpaths.annotations.Pathable
import dev.kpaths.ksp.codegen.KPathsCatalogGenerator
import dev.kpaths.ksp.codegen.KPathsCatalogRequest
import dev.kpaths.ksp.codegen.defaultKPathsCatalogName

private val pathableAnnotation = Pathable::class.qualifiedName!!
private const val generatedInternalPrefix = "__kpaths_"

/** Generates stable property and case-path APIs for [Pathable] declarations. */
public class KPathsProcessorProvider : SymbolProcessorProvider {
  override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
    KPathsProcessor(environment.codeGenerator, environment.logger)
}

/** Discovers `@Pathable` declarations and delegates catalog generation to the shared code generator. */
private class KPathsProcessor(
  codeGenerator: CodeGenerator,
  private val logger: KSPLogger,
) : SymbolProcessor {
  private val catalogGenerator = KPathsCatalogGenerator(codeGenerator, logger)

  override fun process(resolver: Resolver): List<KSAnnotated> {
    val requests = resolver.getSymbolsWithAnnotation(pathableAnnotation)
      .filterIsInstance<KSClassDeclaration>()
      .mapNotNull(::catalogRequest)
      .toList()
    return catalogGenerator.generate(resolver, requests)
  }

  private fun catalogRequest(declaration: KSClassDeclaration): KPathsCatalogRequest? {
    if (declaration.qualifiedName == null) {
      logger.error("@Pathable types must have a qualified name.", declaration)
      return null
    }
    val defaultName = checkNotNull(defaultKPathsCatalogName(declaration))
    val annotation = declaration.annotations.firstOrNull { candidate ->
      candidate.annotationType.resolve().declaration.qualifiedName?.asString() == pathableAnnotation
    }
    val configuredName = annotation?.arguments
      ?.firstOrNull { argument -> argument.name?.asString() == "name" }
      ?.value as? String
      ?: ""
    val copyWritableVals = annotation?.arguments
      ?.firstOrNull { argument -> argument.name?.asString() == "copyWritableVals" }
      ?.value as? Boolean
      ?: false
    val generatedName = when {
      configuredName.isEmpty() -> defaultName
      configuredName.startsWith(generatedInternalPrefix) -> {
        logger.error(
          "[KPaths] @Pathable name '$configuredName' uses the reserved '$generatedInternalPrefix' prefix for " +
            "KPaths-generated implementation details.",
          annotation ?: declaration,
        )
        return null
      }
      !configuredName.isKotlinIdentifier() -> {
        logger.error(
          "[KPaths] @Pathable name '$configuredName' must be a single Kotlin identifier, without a package or dot.",
          annotation ?: declaration,
        )
        return null
      }
      else -> configuredName
    }
    return KPathsCatalogRequest(
      declaration = declaration,
      generatedName = generatedName,
      copyWritableVals = copyWritableVals,
      annotationQualifiedName = pathableAnnotation,
      renameSuggestion = "@Pathable(\"Custom$generatedName\")",
    )
  }
}

private fun String.isKotlinIdentifier(): Boolean =
  isNotEmpty() && first().isLetterOrUnderscore() && drop(1).all { it.isLetterOrDigitOrUnderscore() }

private fun Char.isLetterOrUnderscore(): Boolean = isLetter() || this == '_'

private fun Char.isLetterOrDigitOrUnderscore(): Boolean = isLetterOrDigit() || this == '_'
