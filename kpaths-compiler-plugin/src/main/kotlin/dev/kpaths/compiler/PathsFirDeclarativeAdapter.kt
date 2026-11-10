package dev.kpaths.compiler

import dev.kpaths.codegen.model.KPathsAdapterFactoryShape
import dev.kpaths.codegen.model.KPathsAdapterDiagnostics
import dev.kpaths.codegen.model.KPathsAdapterPlan
import dev.kpaths.codegen.model.KPathsAdapterType
import dev.kpaths.codegen.model.planKPathsAdapter
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutorByMap
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.ProjectionKind
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.IrTypeSubstitutor
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI

@OptIn(ExperimentalKPathsCompilerApi::class)
internal sealed interface CompilerAdapterAnalysis {
  data object Absent : CompilerAdapterAnalysis
  data class Invalid(val reason: String, val isIntegration: Boolean = false) : CompilerAdapterAnalysis
  data class Available(val recipe: KPathsCompilerPropertyRecipe) : CompilerAdapterAnalysis
}

/** Resolve a declarative object factory without loading or executing application code. */
@OptIn(ExperimentalKPathsCompilerApi::class, SymbolInternals::class, DirectDeclarationsAccess::class, UnsafeDuringIrConstructionAPI::class)
internal fun declarativeCompilerAdapter(
  session: FirSession, rootType: ConeKotlinType, storageType: ConeKotlinType,
): CompilerAdapterAnalysis {
  fun invalid(reason: String) = CompilerAdapterAnalysis.Invalid(reason)
  val storage = (storageType as? ConeClassLikeType)?.fullyExpandedType(session) ?: return CompilerAdapterAnalysis.Absent
  val storageClass = session.symbolProvider.getClassLikeSymbolByClassId(storage.lookupTag.classId) as? FirClassSymbol<*> ?: return CompilerAdapterAnalysis.Absent
  if (session.kpaths.adapterAnnotation(storageClass) == null) return CompilerAdapterAnalysis.Absent
  val factoryId = session.kpaths.adapterFactoryFor(storageClass) ?: return invalid(KPathsAdapterDiagnostics.UNRESOLVED_FACTORY)
  val factory = session.symbolProvider.getClassLikeSymbolByClassId(factoryId) as? FirClassSymbol<*> ?: return invalid(KPathsAdapterDiagnostics.UNRESOLVED_FACTORY)
  val declaration = factory.fir as? FirRegularClass ?: return invalid(KPathsAdapterDiagnostics.UNRESOLVED_FACTORY)
  val functions = declaration.declarations.filterIsInstance<FirNamedFunction>().filter { it.name.asString() == "make" }
  val function = functions.singleOrNull()
  val parameters = function?.typeParameters?.map { it.symbol.name.asString() }?.toSet().orEmpty()
  val parameterType = (function?.valueParameters?.singleOrNull()?.returnTypeRef as? FirResolvedTypeRef)?.coneType
  val returnType = (function?.returnTypeRef as? FirResolvedTypeRef)?.coneType
  val actualTypes = mutableMapOf<KPathsAdapterType, ConeKotlinType>()
  val rootModel = rootType.toAdapterModel(session, actualTypes) ?: return invalid(KPathsAdapterDiagnostics.UNSUPPORTED_ROOT)
  val storageModel = storage.toAdapterModel(session, actualTypes) ?: return invalid(KPathsAdapterDiagnostics.UNSUPPORTED_STORAGE)
  val shape = KPathsAdapterFactoryShape(
    functionCount = functions.size,
    isObjectFactory = declaration.classKind == ClassKind.OBJECT,
    isAccessible = function != null && function.status.visibility !in setOf(Visibilities.Private, Visibilities.Protected, Visibilities.Internal) &&
      declaration.status.visibility !in setOf(Visibilities.Private, Visibilities.Protected, Visibilities.Internal),
    isSuspend = function?.status?.isSuspend == true,
    isExtension = function?.receiverParameter != null,
    hasExplicitBounds = function?.typeParameters?.any { parameter -> parameter.symbol.resolvedBounds.any { bound ->
      val type = bound.coneType as? ConeClassLikeType
      type?.lookupTag?.classId?.asSingleFqName()?.asString() != "kotlin.Any" || type.isMarkedNullable != true
    } } == true,
    parameterCount = function?.valueParameters?.size ?: 0,
    parameterType = parameterType?.toAdapterModel(session),
    resultType = returnType?.toAdapterModel(session),
    typeParameters = parameters,
    resultIsKeyPath = returnType?.isAdapterKeyPath(session),
  )
  val plan = when (val result = planKPathsAdapter(shape, rootModel, storageModel)) {
    is KPathsAdapterPlan.Invalid -> return invalid(result.reason)
    is KPathsAdapterPlan.Supported -> result
  }
  val resolvedFunction = requireNotNull(function)
  val substitutions = resolvedFunction.typeParameters.associate { parameter ->
    parameter.symbol to (actualTypes[plan.substitutions[parameter.symbol.name.asString()]]
      ?: return invalid("make's type arguments could not be resolved"))
  }
  val publicType = ConeSubstitutorByMap.create(substitutions, session, false).substituteOrSelf(requireNotNull(returnType))
  val pattern = shape.parameterType as KPathsAdapterType.Classifier
  val names = resolvedFunction.typeParameters.map { it.symbol.name.asString() }
  return CompilerAdapterAnalysis.Available(KPathsCompilerPropertyRecipe(publicPathType = publicType, requiresDirectPath = true, emit = { ir ->
    val objectSymbol = requireNotNull(ir.pluginContext.referenceClass(factoryId))
    val make = objectSymbol.owner.declarations.filterIsInstance<IrSimpleFunction>().single { it.name.asString() == "make" }
    val implementationType = ir.defaultPath.type as IrSimpleType
    val implementation = (implementationType.classifier as IrClassSymbol).owner
    val implementationArguments = implementationType.arguments.map { (it as IrTypeProjection).type }
    val normalType = IrTypeSubstitutor(implementation.typeParameters.map { it.symbol }, implementationArguments)
      .substitute(implementation.superTypes.single()) as IrSimpleType
    val normalArguments = normalType.arguments.map { (it as IrTypeProjection).type }
    val bindings = mutableMapOf<String, IrType>()
    fun bind(model: KPathsAdapterType, actual: IrType) {
      when (model) {
        is KPathsAdapterType.Parameter -> bindings[model.name] = actual
        is KPathsAdapterType.Classifier -> {
          val typed = actual as IrSimpleType
          model.arguments.zip(typed.arguments).forEach { (m, arg) -> bind(m, (arg as IrTypeProjection).type) }
        }
      }
    }
    bind(pattern.arguments[0], normalArguments[0])
    bind(pattern.arguments[1], normalArguments[1])
    ir.builder.irCall(make.symbol).apply {
      dispatchReceiver = ir.builder.irGetObject(objectSymbol)
      names.forEachIndexed { index, name -> typeArguments[index] = requireNotNull(bindings[name]) }
      arguments[make.parameters.indexOfFirst { it.kind == IrParameterKind.Regular }] = ir.defaultPath
    }
  }))
}

@OptIn(SymbolInternals::class)
internal fun ConeKotlinType.isAdapterKeyPath(session: FirSession, visited: MutableSet<org.jetbrains.kotlin.name.ClassId> = mutableSetOf()): Boolean {
  val type = (this as? ConeClassLikeType)?.fullyExpandedType(session) ?: return false
  val id = type.lookupTag.classId
  if (id.asSingleFqName().asString() == "dev.kpaths.core.KeyPath") return true
  if (!visited.add(id)) return false
  val declaration = session.symbolProvider.getClassLikeSymbolByClassId(id) as? FirClassSymbol<*> ?: return false
  return declaration.resolvedSuperTypes.any { it.isAdapterKeyPath(session, visited) }
}

private fun ConeKotlinType.toAdapterModel(
  session: FirSession,
  actual: MutableMap<KPathsAdapterType, ConeKotlinType>? = null,
): KPathsAdapterType? {
  val model = when (this) {
    is ConeTypeParameterType -> {
      if (isMarkedNullable) return null
      KPathsAdapterType.Parameter(lookupTag.name.asString())
    }
    is ConeClassLikeType -> {
      val expanded = fullyExpandedType(session)
      val args = expanded.typeArguments.map {
        if (it.kind != ProjectionKind.INVARIANT) return null
        (it as? ConeKotlinTypeProjection)?.type?.toAdapterModel(session, actual) ?: return null
      }
      KPathsAdapterType.Classifier(expanded.lookupTag.classId.asSingleFqName().asString(), args, expanded.isMarkedNullable)
    }
    else -> return null
  }
  actual?.put(model, this)
  return model
}
