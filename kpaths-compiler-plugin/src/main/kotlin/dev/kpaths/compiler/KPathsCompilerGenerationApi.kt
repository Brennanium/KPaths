package dev.kpaths.compiler

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.extensions.extensionService
import org.jetbrains.kotlin.fir.extensions.extensionSessionComponents
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.FirClassReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.name.ClassId
import java.util.concurrent.ConcurrentHashMap

/** Marks compiler-integration APIs whose binary compatibility follows the Kotlin compiler. */
@RequiresOptIn(
  message = "KPaths compiler integration is experimental and tied to supported Kotlin compiler versions.",
)
public annotation class ExperimentalKPathsCompilerApi

/**
 * Options supplied when another compiler plugin asks KPaths to generate a catalog for a class it
 * owns or synthesizes.
 *
 * [copyWritableValExclusions] identifies properties that should retain their ordinary path
 * semantics when [copyWritableVals] is enabled. Excluding a `var` does not make it read-only.
 */
@ExperimentalKPathsCompilerApi
public data class KPathsGenerationOptions(
  public val catalogName: String = "Paths",
  public val copyWritableVals: Boolean = false,
  public val copyWritableValExclusions: Set<FirPropertySymbol> = emptySet(),
)

/**
 * Session-scoped request registry shared with cooperating compiler plugins.
 *
 * A contributing plugin must make its request before KPaths is asked to enumerate nested
 * classifiers for [generatedClass]. A contributor that owns a generated class normally delegates
 * KPaths' nested-classifier callbacks to this registry in the same FIR extension.
 */
@ExperimentalKPathsCompilerApi
public class KPathsGenerationRegistry(session: FirSession) : FirExtensionSessionComponent(session) {
  private val requests = ConcurrentHashMap<ClassId, KPathsGenerationOptions>()

  public fun pathable(
    generatedClass: FirClassSymbol<*>,
    options: KPathsGenerationOptions = KPathsGenerationOptions(),
  ) {
    require(options.catalogName.isKotlinIdentifier()) {
      "KPaths catalog names must be a single Kotlin identifier: '${options.catalogName}'."
    }
    val previous = requests.putIfAbsent(generatedClass.classId, options)
    require(previous == null || previous == options) {
      "KPaths received incompatible catalog requests for ${generatedClass.classId.asSingleFqName()}."
    }
  }

  internal fun optionsFor(classSymbol: FirClassSymbol<*>): KPathsGenerationOptions? =
    requests[classSymbol.classId]

  /**
   * Returns the factory named by a resolved storage type's `@KPathsAdapter`, if present.
   *
   * Call this after the type's annotations and annotation arguments are resolved. The returned
   * class ID identifies an integration-owned factory; it does not register an adapter or cause
   * the compiler plugin to emit specialized paths.
   */
  @OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class)
  public fun adapterFactoryFor(storageClass: FirClassSymbol<*>): ClassId? {
    val annotation = storageClass.fir.annotations.firstOrNull {
      ((it.annotationTypeRef as? FirResolvedTypeRef)?.coneType as? ConeClassLikeType)
        ?.lookupTag?.classId == adapterAnnotationId
    } ?: return null
    val factory = annotation.argumentMapping.mapping[org.jetbrains.kotlin.name.Name.identifier("factory")]
      as? FirGetClassCall ?: return null
    return when (val argument = factory.argument) {
      is FirResolvedQualifier -> argument.qualifierSymbol?.classId
      is FirClassReferenceExpression ->
        ((argument.classTypeRef as? FirResolvedTypeRef)?.coneType as? ConeClassLikeType)?.lookupTag?.classId
      else -> null
    }
  }

  private companion object {
    val adapterAnnotationId: ClassId = ClassId.topLevel(org.jetbrains.kotlin.name.FqName("dev.kpaths.annotations.KPathsAdapter"))
  }
}

/** Returns the KPaths request registry installed by the KPaths compiler plugin for this session. */
@ExperimentalKPathsCompilerApi
public val FirSession.kpaths: KPathsGenerationRegistry
  get() = extensionService.extensionSessionComponents
    .filterIsInstance<KPathsGenerationRegistry>()
    .singleOrNull()
    ?: error("The KPaths compiler plugin must be applied before requesting generated KPaths catalogs.")

private fun String.isKotlinIdentifier(): Boolean =
  isNotEmpty() && first().isLetterOrUnderscore() && drop(1).all { it.isLetterOrDigitOrUnderscore() }

private fun Char.isLetterOrUnderscore(): Boolean = isLetter() || this == '_'

private fun Char.isLetterOrDigitOrUnderscore(): Boolean = isLetterOrDigit() || this == '_'
