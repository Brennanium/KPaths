@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package dev.kpaths.compiler

import dev.kpaths.compiler.PathsIrSymbols.listOfCallableId
import dev.kpaths.compiler.PathsIrSymbols.typeArgumentClassId
import dev.kpaths.compiler.PathsIrSymbols.typeIdClassId
import dev.kpaths.codegen.model.KPathsTypeArgumentSpec
import dev.kpaths.codegen.model.KPathsTypeIdSpec
import dev.kpaths.codegen.model.KPathsTypeVariance
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irCallWithSubstitutedType
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTrue
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrStarProjection
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeArgument
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.SimpleTypeNullability
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.types.Variance

/**
 * Lowers the shared, frontend-neutral [KPathsTypeIdSpec] model to the core runtime `TypeId`
 * representation. Both generated property and case implementations use this one emitter so a
 * generic focus has identical identity semantics regardless of which catalog exposed it.
 */
@Suppress("DEPRECATION")
internal fun DeclarationIrBuilder.emitKPathsTypeId(
  pluginContext: IrPluginContext,
  type: KPathsTypeIdSpec,
  resolveParameter: (String) -> org.jetbrains.kotlin.ir.expressions.IrExpression? = { null },
): org.jetbrains.kotlin.ir.expressions.IrExpression? = run {
  val typeId = pluginContext.referenceClass(typeIdClassId) ?: return@run null
  val typeArgument = pluginContext.referenceClass(typeArgumentClassId) ?: return@run null
  val typeIdConstructor = typeId.owner.primaryConstructor ?: return@run null
  val typeArgumentCompanion = typeArgument.owner.companionObject() ?: return@run null
  val typeArgumentStar = typeArgumentCompanion.declarations.filterIsInstance<IrProperty>()
    .singleOrNull { it.name.asString() == "star" }
    ?.getter
    ?: return@run null
  fun typeArgumentFactory(name: String): IrSimpleFunction? =
    typeArgumentCompanion.declarations.filterIsInstance<IrSimpleFunction>()
      .singleOrNull { it.name.asString() == name }
  val invariantTypeArgument = typeArgumentFactory("invariant") ?: return@run null
  val inTypeArgument = typeArgumentFactory("in") ?: return@run null
  val outTypeArgument = typeArgumentFactory("out") ?: return@run null
  val listOf = pluginContext.referenceFunctions(listOfCallableId)
    .singleOrNull { function ->
      function.owner.parameters.singleOrNull()?.varargElementType != null
    }
    ?: return@run null

  lateinit var emit: (KPathsTypeIdSpec) -> org.jetbrains.kotlin.ir.expressions.IrExpression?

  fun typeArgumentExpression(argument: KPathsTypeArgumentSpec): org.jetbrains.kotlin.ir.expressions.IrExpression? =
    when (argument.variance) {
      KPathsTypeVariance.STAR -> irCall(typeArgumentStar.symbol).apply {
        dispatchReceiver = irGetObject(typeArgumentCompanion.symbol)
      }
      KPathsTypeVariance.INVARIANT,
      KPathsTypeVariance.IN,
      KPathsTypeVariance.OUT,
      -> {
        val argumentType = emit(argument.type ?: return null) ?: return null
        val factory = when (argument.variance) {
          KPathsTypeVariance.INVARIANT -> invariantTypeArgument
          KPathsTypeVariance.IN -> inTypeArgument
          KPathsTypeVariance.OUT -> outTypeArgument
          KPathsTypeVariance.STAR -> error("Handled above.")
        }
        irCall(factory.symbol).apply {
          dispatchReceiver = irGetObject(typeArgumentCompanion.symbol)
          arguments[1] = argumentType
        }
      }
    }

  emit = emitter@{ candidate -> when (candidate) {
    is KPathsTypeIdSpec.Parameter -> {
      val token = resolveParameter(candidate.name) ?: return@emitter null
      if (!candidate.nullable) token else {
        val withNullability = typeId.owner.declarations.filterIsInstance<IrSimpleFunction>()
          .singleOrNull { it.name.asString() == "withNullability" } ?: return@emitter null
        irCall(withNullability.symbol).apply {
          dispatchReceiver = token
          arguments[1] = irTrue()
        }
      }
    }
    is KPathsTypeIdSpec.Declared -> {
      val argumentExpressions = buildList {
        candidate.arguments.forEach { argument ->
          add(typeArgumentExpression(argument) ?: return@emitter null)
        }
      }
      irCallConstructor(typeIdConstructor.symbol, emptyList()).apply {
        arguments[0] = irString(candidate.classifier)
        if (argumentExpressions.isNotEmpty()) {
          arguments[1] = irCallWithSubstitutedType(
            listOf,
            listOf(typeArgument.owner.defaultType),
          ).apply {
            arguments[0] = irVararg(typeArgument.owner.defaultType, argumentExpressions)
          }
        }
        if (candidate.nullable) {
          arguments[2] = irTrue()
        }
      }
    }
  } }
  emit(type)
}

internal fun IrClass.companionObject(): IrClass? =
  declarations.filterIsInstance<IrClass>().singleOrNull { it.isCompanion }

internal fun IrClass.stableQualifiedName(): String? {
  val names = mutableListOf<String>()
  var parent: IrDeclarationParent? = this
  while (parent is IrClass) {
    names += parent.name.asString()
    parent = parent.parent
  }
  val packageName = (parent as? IrPackageFragment)?.packageFqName?.asString() ?: return null
  return (listOf(packageName) + names.asReversed()).filter(String::isNotBlank).joinToString(".")
}

internal fun IrClass.toKPathsTypeIdSpec(): KPathsTypeIdSpec.Declared? =
  stableQualifiedName()?.let { name ->
    KPathsTypeIdSpec.declaredRoot(name, typeParameters.map { it.name.asString() })
  }

internal fun IrType.toKPathsTypeIdSpec(): KPathsTypeIdSpec? {
  val simpleType = this as? IrSimpleType ?: return null
  val nullable = simpleType.nullability == SimpleTypeNullability.MARKED_NULLABLE
  return when (val classifier = simpleType.classifier) {
    is IrClassSymbol -> KPathsTypeIdSpec.Declared(
      classifier = classifier.owner.stableQualifiedName() ?: return null,
      arguments = simpleType.arguments.map { argument ->
        argument.toKPathsTypeArgumentSpec() ?: return null
      },
      nullable = nullable,
    )
    is IrTypeParameterSymbol -> KPathsTypeIdSpec.Parameter(classifier.owner.name.asString(), nullable)
    else -> null
  }
}

private fun IrTypeArgument.toKPathsTypeArgumentSpec(): KPathsTypeArgumentSpec? = when (this) {
  is IrStarProjection -> KPathsTypeArgumentSpec.star
  is IrTypeProjection -> {
    val type = type.toKPathsTypeIdSpec() ?: return null
    when (variance) {
      Variance.INVARIANT -> KPathsTypeArgumentSpec.invariant(type)
      Variance.IN_VARIANCE -> KPathsTypeArgumentSpec.`in`(type)
      Variance.OUT_VARIANCE -> KPathsTypeArgumentSpec.out(type)
    }
  }
}
