package dev.kpaths.compiler

import dev.kpaths.codegen.model.validateKPathsConfiguredName
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.name.Name

private fun FirAnnotationContainer.configuredName(annotationName: String): String? {
  val annotation = annotations.firstOrNull {
    ((it.annotationTypeRef as? FirResolvedTypeRef)?.coneType as? ConeClassLikeType)
      ?.lookupTag?.classId?.asString() == "dev/kpaths/annotations/$annotationName"
  } ?: return null
  return (annotation.argumentMapping.mapping[Name.identifier("value")] as? FirLiteralExpression)?.value as? String
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
