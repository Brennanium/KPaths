package dev.kpaths.compiler

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirTypeParameterRef
import org.jetbrains.kotlin.fir.plugin.DeclarationBuildingContext
import org.jetbrains.kotlin.fir.plugin.createConeType
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutorByMap
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

/** Copies root parameters and substitutes references between their bounds. */
internal fun DeclarationBuildingContext<*>.copyKPathsTypeParameters(
  source: List<FirTypeParameterRef>,
  session: FirSession,
  reified: Boolean = false,
  forceNonNull: Boolean = false,
) {
  source.forEach { parameter ->
    typeParameter(parameter.symbol.name, isReified = reified) {
      val bounds = parameter.symbol.resolvedBounds
      bounds.forEach { sourceBound ->
        bound { generated ->
          val replacements = source.map { it.symbol }.zip(generated.map {
            it.symbol.kPathsTypeParameterType()
          }).toMap()
          ConeSubstitutorByMap.create(replacements, session, false).substituteOrSelf(sourceBound.coneType)
        }
      }
      if (forceNonNull && bounds.none {
          (it.coneType as? ConeClassLikeType)?.isMarkedNullable == false
        }
      ) {
        bound(ClassId.topLevel(FqName("kotlin.Any")).createConeType(session))
      }
    }
  }
}
