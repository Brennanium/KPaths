package dev.kpaths.compiler

import dev.kpaths.codegen.model.KPathsAdapterFactoryShape
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
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.name.Name

/** Resolve a declarative object factory without loading or executing application code. */
@OptIn(ExperimentalKPathsCompilerApi::class, SymbolInternals::class, DirectDeclarationsAccess::class, UnsafeDuringIrConstructionAPI::class)
internal fun declarativeCompilerAdapter(
  session: FirSession, rootType: ConeKotlinType, storageType: ConeKotlinType,
): KPathsCompilerPropertyRecipe? {
  val storage = (storageType as? ConeClassLikeType)?.fullyExpandedType(session) ?: return null
  val storageClass = session.symbolProvider.getClassLikeSymbolByClassId(storage.lookupTag.classId) as? FirClassSymbol<*> ?: return null
  val factoryId = session.kpaths.adapterFactoryFor(storageClass) ?: return null
  val factory = session.symbolProvider.getClassLikeSymbolByClassId(factoryId) as? FirClassSymbol<*> ?: return null
  val declaration = factory.fir as? FirRegularClass ?: return null
  val functions = declaration.declarations.filterIsInstance<FirNamedFunction>().filter { it.name.asString() == "make" }
  val function = functions.singleOrNull() ?: return null
  val parameters = function.typeParameters.map { it.symbol.name.asString() }.toSet()
  val parameterType = (function.valueParameters.singleOrNull()?.returnTypeRef as? FirResolvedTypeRef)?.coneType
  val returnType = (function.returnTypeRef as? FirResolvedTypeRef)?.coneType ?: return null
  if (!returnType.isAdapterKeyPath(session)) return null
  val actualTypes = mutableMapOf<KPathsAdapterType, ConeKotlinType>()
  val rootModel = rootType.toAdapterModel(session, actualTypes) ?: return null
  val storageModel = storage.toAdapterModel(session, actualTypes) ?: return null
  val shape = KPathsAdapterFactoryShape(
    functionCount = functions.size,
    isObjectFactory = declaration.classKind == ClassKind.OBJECT,
    isAccessible = function.status.visibility !in setOf(Visibilities.Private, Visibilities.Protected, Visibilities.Internal) &&
      declaration.status.visibility !in setOf(Visibilities.Private, Visibilities.Protected, Visibilities.Internal),
    isSuspend = function.status.isSuspend,
    isExtension = function.receiverParameter != null,
    hasExplicitBounds = function.typeParameters.any { parameter -> parameter.symbol.resolvedBounds.any { bound ->
      val type = bound.coneType as? ConeClassLikeType
      type?.lookupTag?.classId?.asSingleFqName()?.asString() != "kotlin.Any" || type.isMarkedNullable != true
    } },
    parameterCount = function.valueParameters.size,
    parameterType = parameterType?.toAdapterModel(session),
    resultType = returnType.toAdapterModel(session),
    typeParameters = parameters,
  )
  val plan = planKPathsAdapter(shape, rootModel, storageModel) as? KPathsAdapterPlan.Supported ?: return null
  val substitutions = function.typeParameters.associate { parameter ->
    parameter.symbol to (actualTypes[plan.substitutions[parameter.symbol.name.asString()]] ?: return null)
  }
  val publicType = ConeSubstitutorByMap.create(substitutions, session, false).substituteOrSelf(returnType)
  val pattern = shape.parameterType as KPathsAdapterType.Classifier
  val names = function.typeParameters.map { it.symbol.name.asString() }
  return KPathsCompilerPropertyRecipe(publicPathType = publicType, requiresDirectPath = true, emit = { ir ->
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
  })
}

@OptIn(SymbolInternals::class)
private fun ConeKotlinType.isAdapterKeyPath(session: FirSession, visited: MutableSet<org.jetbrains.kotlin.name.ClassId> = mutableSetOf()): Boolean {
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
