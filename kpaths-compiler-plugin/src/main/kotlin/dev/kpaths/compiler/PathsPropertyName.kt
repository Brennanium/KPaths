package dev.kpaths.compiler

import dev.kpaths.codegen.model.validateKPathsConfiguredName
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirNamedArgumentExpression
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.name.Name

private fun FirAnnotationContainer.configuredName(annotationName: String): String? {
  val annotation = annotations.firstOrNull {
    ((it.annotationTypeRef as? FirResolvedTypeRef)?.coneType as? ConeClassLikeType)
      ?.lookupTag?.classId?.asString() == "dev/kpaths/annotations/$annotationName"
  } ?: return null
  val mapped = annotation.argumentMapping.mapping[Name.identifier("value")]
  if (mapped != null) return (mapped as? FirLiteralExpression)?.value as? String

  // IDE analysis may request generated member names before FIR fills argumentMapping. These
  // annotations each have a single `value` argument, so a literal can be read from the original
  // argument list without forcing a later resolution phase and re-entering member generation.
  val argument = (annotation as? FirAnnotationCall)?.argumentList?.arguments?.singleOrNull() ?: return null
  val expression = when (argument) {
    is FirNamedArgumentExpression -> argument.expression.takeIf { argument.name.asString() == "value" }
    else -> argument
  }
  return (expression as? FirLiteralExpression)?.value as? String
}

internal fun FirProperty.configuredKPathsName(): String? = configuredName("PathName")

internal fun FirAnnotationContainer.configuredKPathsCaseName(): String? = configuredName("CaseName")

internal fun FirAnnotationContainer.configuredKPathsCatalogName(): String? = configuredName("PathsName")

internal fun FirAnnotationContainer.generatedKPathsCaseName(sourceName: Name): Name = configuredKPathsCaseName()
  ?.takeIf { validateKPathsConfiguredName(it) == null }
  ?.let(Name::identifier) ?: sourceName

internal fun FirProperty.generatedKPathsName(): Name = configuredKPathsName()
  ?.takeIf { validateKPathsConfiguredName(it) == null }
  ?.let(Name::identifier) ?: name
