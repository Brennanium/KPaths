package dev.kpaths.compiler

import dev.kpaths.codegen.model.KPathsGenericCaseArgument
import dev.kpaths.codegen.model.KPathsGenericCasePlan
import dev.kpaths.codegen.model.KPathsGenericRootParameter
import dev.kpaths.codegen.model.planKPathsGenericCase
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.fir.types.ConeLookupTagBasedType
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ProjectionKind
import org.jetbrains.kotlin.types.Variance

/** Translates resolved FIR case supertypes into the frontend-neutral substitution policy. */
@OptIn(SymbolInternals::class)
context(context: CheckerContext)
internal fun FirRegularClass.kPathsGenericCasePlan(case: FirRegularClass): KPathsGenericCasePlan? {
  val rootId = symbol.classId
  val supertype = case.symbol.resolvedSuperTypes
    .filterIsInstance<ConeClassLikeType>()
    .map { it.fullyExpandedType() }
    .firstOrNull { it.lookupTag.classId == rootId } ?: return null
  return planKPathsGenericCase(
    rootParameters = typeParameters.map {
      KPathsGenericRootParameter(it.symbol.name.asString(), it.symbol.variance == Variance.OUT_VARIANCE)
    },
    caseParameters = case.typeParameters.map { it.symbol.name.asString() },
    arguments = supertype.typeArguments.map { projection ->
      val type = (projection as? ConeKotlinTypeProjection)?.type
      val lookup = type as? ConeLookupTagBasedType
      val classType = type as? ConeClassLikeType
      KPathsGenericCaseArgument(
        forwardedParameter = (type as? ConeTypeParameterType)?.lookupTag?.name?.asString(),
        isNullable = lookup?.isMarkedNullable == true,
        isNothing = classType?.lookupTag?.classId?.asSingleFqName()?.asString() == "kotlin.Nothing" &&
          !classType.isMarkedNullable,
        rendered = type?.kPathsDiagnosticType() ?: "<unresolved>",
      )
    },
  )
}

private fun ConeKotlinType.kPathsDiagnosticType(): String {
  val base = when (this) {
    is ConeTypeParameterType -> lookupTag.name.asString()
    is ConeClassLikeType -> lookupTag.classId.asSingleFqName().asString()
    else -> toString()
  }
  val arguments = typeArguments.takeIf { it.isNotEmpty() }?.joinToString(", ", "<", ">") { projection ->
    val nested = (projection as? ConeKotlinTypeProjection)?.type?.kPathsDiagnosticType()
    when (projection.kind) {
      ProjectionKind.STAR -> "*"
      ProjectionKind.IN -> "in $nested"
      ProjectionKind.OUT -> "out $nested"
      ProjectionKind.INVARIANT -> nested ?: "<unresolved>"
    }
  }.orEmpty()
  return base + arguments + if ((this as? ConeLookupTagBasedType)?.isMarkedNullable == true) "?" else ""
}
