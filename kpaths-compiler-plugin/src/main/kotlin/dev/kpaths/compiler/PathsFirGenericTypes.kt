package dev.kpaths.compiler

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirTypeParameterRef
import org.jetbrains.kotlin.fir.plugin.DeclarationBuildingContext
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutorByMap
import org.jetbrains.kotlin.fir.types.ConeAttributes
import org.jetbrains.kotlin.fir.types.impl.ConeTypeParameterTypeImpl

/** Copies root parameters and substitutes references between their bounds. */
internal fun DeclarationBuildingContext<*>.copyKPathsTypeParameters(
  source: List<FirTypeParameterRef>,
  session: FirSession,
  reified: Boolean = false,
) {
  source.forEach { parameter ->
    typeParameter(parameter.symbol.name, isReified = reified) {
      parameter.symbol.resolvedBounds.forEach { sourceBound ->
        bound { generated ->
          val replacements = source.map { it.symbol }.zip(generated.map {
            ConeTypeParameterTypeImpl(it.symbol.toLookupTag(), false, ConeAttributes.Empty)
          }).toMap()
          ConeSubstitutorByMap.create(replacements, session, false).substituteOrSelf(sourceBound.coneType)
        }
      }
    }
  }
}
