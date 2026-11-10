package dev.kpaths.compiler

import dev.kpaths.codegen.model.KPathsGenericCasePlan
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirTypeParameterRef
import org.jetbrains.kotlin.fir.plugin.createConeType
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.toTypeProjection
import org.jetbrains.kotlin.types.Variance

/** Builds the root and case types using parameters owned by a generated generic declaration. */
@OptIn(SymbolInternals::class)
internal fun kPathsGenericCaseTypes(
  root: FirRegularClass,
  case: FirRegularClass,
  parameters: List<FirTypeParameterRef>,
  session: FirSession,
): Pair<ConeKotlinType, ConeKotlinType>? {
  val plan = root.kPathsGenericCasePlan(case, session) as? KPathsGenericCasePlan.Supported ?: return null
  if (parameters.size != root.typeParameters.size) return null
  val arguments = parameters.map { it.symbol.kPathsTypeParameterType() }
  val byName = root.typeParameters.mapIndexed { index, parameter -> parameter.symbol.name.asString() to arguments[index] }.toMap()
  val caseArguments = case.typeParameters.map { parameter ->
    val rootName = plan.substitutions[parameter.symbol.name.asString()] ?: return null
    (byName[rootName] ?: return null).toTypeProjection(Variance.INVARIANT)
  }
  return root.symbol.classId.createConeType(session, arguments.map {
    it.toTypeProjection(Variance.INVARIANT)
  }.toTypedArray(), false) to case.symbol.classId.createConeType(session, caseArguments.toTypedArray(), false)
}
