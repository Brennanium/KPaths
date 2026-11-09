package dev.kpaths.compiler

import dev.kpaths.compiler.PathsIrSymbols.anyPathClassId
import dev.kpaths.compiler.PathsIrSymbols.keyPathClassId
import dev.kpaths.compiler.PathsIrSymbols.keyPathBaseClassId
import dev.kpaths.compiler.PathsIrSymbols.referenceWritableKeyPathClassId
import dev.kpaths.compiler.PathsIrSymbols.referenceWritableKeyPathBaseClassId
import dev.kpaths.compiler.PathsIrSymbols.writableKeyPathClassId
import dev.kpaths.compiler.PathsIrSymbols.writableKeyPathBaseClassId
import dev.kpaths.compiler.PathsIrSymbols.pathIdClassId
import dev.kpaths.compiler.PathsIrSymbols.typeIdClassId

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.addBackingField
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addDefaultGetter
import org.jetbrains.kotlin.ir.builders.declarations.addGetter
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.addTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildReceiverParameter
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irAs
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.irUnit
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.types.IrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

/** Emits direct, cached implementations for generated property paths. */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class PathsIrPropertyEmitter(
  private val pluginContext: IrPluginContext,
  private val deferredDeclarations: MutableMap<IrClass, MutableList<IrClass>>,
) {
  /**
   * Builds a synthetic implementation equivalent to:
   *
   * ```kotlin
   * private class __KPaths_name_Path : KeyPathBase<Person, String>() {
   *   override val __kpaths_id = PathId.property(TypeId("example.Person"), "name")
   *   override val __kpaths_valueType = TypeId("kotlin.String")
   *   override fun get(root: Person): String = root.name
   * }
   * ```
   *
   * It is private implementation detail nested in `Paths`, and the public path field caches its
   * one instance. Generic roots receive a parameterized implementation whose constructor captures
   * the catalog's structural root and value identities.
   */
  fun DeclarationIrBuilder.directKeyPathInitializer(
    pathsClass: IrClass,
    rootClass: IrClass,
    sourceProperty: IrProperty,
    pathKind: GeneratedPathKind,
  ) = run {
    val isGeneric = rootClass.typeParameters.isNotEmpty()
    val sourceGetter = sourceProperty.getter ?: return@run null
    val rootCopy = if (
      pathKind == GeneratedPathKind.COPY_WRITABLE ||
        pathKind == GeneratedPathKind.COPY_THEN_MUTATE
    ) {
      rootClass.declarations.filterIsInstance<IrSimpleFunction>()
        .singleOrNull { it.name.asString() == "copy" }
        ?: return@run null
    } else {
      null
    }
    val rootTypeSpec = rootClass.toKPathsTypeIdSpec() ?: return@run null
    val valueTypeSpec = sourceGetter.returnType.toKPathsTypeIdSpec() ?: return@run null
    val keyPathBase = pluginContext.referenceClass(keyPathBaseClassId) ?: return@run null
    val referenceWritableKeyPathBase = pluginContext.referenceClass(referenceWritableKeyPathBaseClassId)
      ?: return@run null
    val writableKeyPathBase = pluginContext.referenceClass(writableKeyPathBaseClassId)
      ?: return@run null
    val writableKeyPath = pluginContext.referenceClass(writableKeyPathClassId) ?: return@run null
    val referenceWritableKeyPath = pluginContext.referenceClass(referenceWritableKeyPathClassId)
      ?: return@run null
    val anyPath = pluginContext.referenceClass(anyPathClassId) ?: return@run null
    val keyPath = pluginContext.referenceClass(keyPathClassId) ?: return@run null
    val pathId = pluginContext.referenceClass(pathIdClassId) ?: return@run null
    val typeId = pluginContext.referenceClass(typeIdClassId) ?: return@run null
    val idProperty = anyPath.owner.declarations.filterIsInstance<IrProperty>()
      .singleOrNull { it.name.asString() == "__kpaths_id" }
      ?: return@run null
    val valueTypeProperty = anyPath.owner.declarations.filterIsInstance<IrProperty>()
      .singleOrNull { it.name.asString() == "__kpaths_valueType" }
      ?: return@run null
    val keyPathGet = keyPath.owner.declarations.filterIsInstance<IrSimpleFunction>()
      .singleOrNull { it.name.asString() == "get" }
      ?: return@run null
    val referenceWritableSetInPlace = referenceWritableKeyPath.owner.declarations
      .filterIsInstance<IrSimpleFunction>()
      .singleOrNull { it.name.asString() == "setInPlace" }
    val writableSet = writableKeyPath.owner.declarations
      .filterIsInstance<IrSimpleFunction>()
      .singleOrNull { it.name.asString() == "set" }
    val pathIdCompanion = pathId.owner.companionObject() ?: return@run null
    val pathIdProperty = pathIdCompanion.declarations.filterIsInstance<IrSimpleFunction>()
      .singleOrNull { it.name.asString() == "property" }
      ?: return@run null
    val selectedBase = when (pathKind) {
      GeneratedPathKind.READ_ONLY -> keyPathBase
      GeneratedPathKind.COPY_WRITABLE -> writableKeyPathBase
      GeneratedPathKind.COPY_THEN_MUTATE -> writableKeyPathBase
      GeneratedPathKind.REFERENCE_WRITABLE -> referenceWritableKeyPathBase
    }
    val baseConstructor = selectedBase.owner.primaryConstructor ?: return@run null
    val implementation = pluginContext.irFactory.buildClass {
      name = Name.identifier("__KPaths_${sourceProperty.name.asString()}_Path")
      kind = ClassKind.CLASS
      modality = Modality.FINAL
      visibility = DescriptorVisibilities.PRIVATE
      origin = IrDeclarationOrigin.DEFINED
    }.apply {
      parent = pathsClass
    }
    val implementationParameters = rootClass.typeParameters.map { parameter ->
      implementation.addTypeParameter(parameter.name.asString(), pluginContext.irBuiltIns.anyNType)
    }
    val implementationArguments = implementationParameters.map {
      IrSimpleTypeImpl(it.symbol, false, emptyList(), emptyList())
    }
    val substitutor = IrTypeSubstitutor(rootClass.typeParameters.map { it.symbol }, implementationArguments)
    implementationParameters.zip(rootClass.typeParameters).forEach { (generated, original) ->
      generated.superTypes = original.superTypes.map(substitutor::substitute)
    }
    val generatedRootType = substitutor.substitute(rootClass.defaultType)
    val generatedFocusType = substitutor.substitute(sourceGetter.returnType)
    implementation.superTypes = listOf(selectedBase.typeWith(generatedRootType, generatedFocusType))
    deferredDeclarations.getOrPut(pathsClass, ::mutableListOf) += implementation
    val implementationType = IrSimpleTypeImpl(
      implementation.symbol,
      false,
      implementationArguments,
      emptyList(),
    )
    implementation.thisReceiver = implementation.buildReceiverParameter {
      type = implementationType
      origin = IrDeclarationOrigin.DEFINED
      name = Name.special("<this>")
    }

    val constructor = implementation.addConstructor {
      isPrimary = true
      name = SpecialNames.INIT
      visibility = DescriptorVisibilities.PRIVATE
      returnType = implementationType
      origin = IrDeclarationOrigin.DEFINED
    }
    val idParameter = if (isGeneric) constructor.addValueParameter("__kpaths_id", pathId.owner.defaultType) else null
    val typeParameter = if (isGeneric) constructor.addValueParameter("__kpaths_valueType", typeId.owner.defaultType) else null
    constructor.body = irBlockBody {
      +irDelegatingConstructorCall(baseConstructor)
      +IrInstanceInitializerCallImpl(
        startOffset = -1,
        endOffset = -1,
        classSymbol = implementation.symbol,
        type = implementationType,
      )
    }

    fun resolveParameter(name: String) = pathsClass.declarations.filterIsInstance<IrField>()
      .singleOrNull { it.name.asString() == "__kpaths_type_$name" }
      ?.let { field -> irGetField(irGet(checkNotNull(pathsClass.thisReceiver)), field) }
    val rootTypeIdExpression = emitKPathsTypeId(pluginContext, rootTypeSpec, ::resolveParameter) ?: return@run null
    val valueTypeIdExpression = emitKPathsTypeId(pluginContext, valueTypeSpec, ::resolveParameter) ?: return@run null
    val idExpression = irCall(pathIdProperty.symbol).apply {
      dispatchReceiver = irGetObject(pathIdCompanion.symbol)
      arguments[1] = rootTypeIdExpression
      arguments[2] = irString(sourceProperty.name.asString())
    }

    val generatedId = implementation.addProperty {
      name = Name.identifier("__kpaths_id")
      visibility = DescriptorVisibilities.PUBLIC
      modality = Modality.FINAL
      origin = IrDeclarationOrigin.DEFINED
      isVar = false
    }.apply {
      overriddenSymbols = listOf(idProperty.symbol)
    }
    val generatedIdField = generatedId.addBackingField {
      name = Name.identifier("__kpaths_id")
      type = pathId.owner.defaultType
      visibility = DescriptorVisibilities.PRIVATE
      isFinal = true
    }
    generatedIdField.initializer = irExprBody(idParameter?.let(::irGet) ?: idExpression)
    if (isGeneric) {
      val getter = generatedId.addGetter {
        returnType = pathId.owner.defaultType
        visibility = DescriptorVisibilities.PUBLIC
      }
      getter.overriddenSymbols = listOf(checkNotNull(idProperty.getter).symbol)
      getter.parameters += getter.buildReceiverParameter { type = implementationType }
      val builder = DeclarationIrBuilder(pluginContext, getter.symbol)
      getter.body = builder.irBlockBody {
        +irReturn(irGetField(irGet(checkNotNull(getter.dispatchReceiverParameter)), generatedIdField))
      }
    } else generatedId.addDefaultGetter(implementation, pluginContext.irBuiltIns)

    val generatedValueType = implementation.addProperty {
      name = Name.identifier("__kpaths_valueType")
      visibility = DescriptorVisibilities.PUBLIC
      modality = Modality.FINAL
      origin = IrDeclarationOrigin.DEFINED
      isVar = false
    }.apply {
      overriddenSymbols = listOf(valueTypeProperty.symbol)
    }
    val generatedValueTypeField = generatedValueType.addBackingField {
      name = Name.identifier("__kpaths_valueType")
      type = typeId.owner.defaultType
      visibility = DescriptorVisibilities.PRIVATE
      isFinal = true
    }
    generatedValueTypeField.initializer = irExprBody(typeParameter?.let(::irGet) ?: valueTypeIdExpression)
    if (isGeneric) {
      val getter = generatedValueType.addGetter {
        returnType = typeId.owner.defaultType
        visibility = DescriptorVisibilities.PUBLIC
      }
      getter.overriddenSymbols = listOf(checkNotNull(valueTypeProperty.getter).symbol)
      getter.parameters += getter.buildReceiverParameter { type = implementationType }
      val builder = DeclarationIrBuilder(pluginContext, getter.symbol)
      getter.body = builder.irBlockBody {
        +irReturn(irGetField(irGet(checkNotNull(getter.dispatchReceiverParameter)), generatedValueTypeField))
      }
    } else generatedValueType.addDefaultGetter(implementation, pluginContext.irBuiltIns)

    val generatedGet = implementation.addFunction {
      name = Name.identifier("get")
      visibility = DescriptorVisibilities.PUBLIC
      modality = Modality.FINAL
      returnType = generatedFocusType
      origin = IrDeclarationOrigin.DEFINED
    }
    val generatedGetReceiver = generatedGet.buildReceiverParameter {
      type = implementationType
      origin = IrDeclarationOrigin.DEFINED
    }
    generatedGet.parameters += generatedGetReceiver
    val rootParameter = generatedGet.addValueParameter(
      name = Name.identifier("root"),
      type = generatedRootType,
      origin = IrDeclarationOrigin.DEFINED,
    )
    val generatedGetBuilder = DeclarationIrBuilder(pluginContext, generatedGet.symbol)
    val generatedRead = generatedGetBuilder.irCall(sourceGetter.symbol).apply {
      dispatchReceiver = generatedGetBuilder.irGet(rootParameter)
    }
    generatedGet.body = generatedGetBuilder.irBlockBody {
      +generatedGetBuilder.irReturn(generatedRead)
    }

    if (pathKind == GeneratedPathKind.REFERENCE_WRITABLE) {
      val sourceSetter = sourceProperty.setter ?: return@run null
      val setInPlace = referenceWritableSetInPlace ?: return@run null
      val generatedSetInPlace = implementation.addFunction {
        name = Name.identifier("setInPlace")
        visibility = DescriptorVisibilities.PUBLIC
        modality = Modality.FINAL
        returnType = pluginContext.irBuiltIns.unitType
        origin = IrDeclarationOrigin.DEFINED
      }.apply {
        overriddenSymbols = listOf(setInPlace.symbol)
      }
      val generatedSetInPlaceReceiver = generatedSetInPlace.buildReceiverParameter {
        type = implementationType
        origin = IrDeclarationOrigin.DEFINED
      }
      generatedSetInPlace.parameters += generatedSetInPlaceReceiver
      val setRoot = generatedSetInPlace.addValueParameter(
        name = Name.identifier("root"),
        type = generatedRootType,
        origin = IrDeclarationOrigin.DEFINED,
      )
      val setValue = generatedSetInPlace.addValueParameter(
        name = Name.identifier("value"),
        type = generatedFocusType,
        origin = IrDeclarationOrigin.DEFINED,
      )
      val setBuilder = DeclarationIrBuilder(pluginContext, generatedSetInPlace.symbol)
      val generatedWrite = setBuilder.irCall(sourceSetter.symbol).apply {
        dispatchReceiver = setBuilder.irGet(setRoot)
        arguments[1] = setBuilder.irGet(setValue)
      }
      generatedSetInPlace.body = setBuilder.irBlockBody {
        +generatedWrite
        +setBuilder.irReturn(setBuilder.irUnit())
      }
    }

    if (
      pathKind == GeneratedPathKind.COPY_WRITABLE ||
        pathKind == GeneratedPathKind.COPY_THEN_MUTATE
    ) {
      val copy = rootCopy ?: return@run null
      val set = writableSet ?: return@run null
      val copyPropertyGetters = rootClass.declarations.filterIsInstance<IrProperty>()
        .mapNotNull { property -> property.getter?.let { property.name to it } }
        .toMap()
      val generatedSet = implementation.addFunction {
        name = Name.identifier("set")
        visibility = DescriptorVisibilities.PUBLIC
        modality = Modality.FINAL
        returnType = generatedRootType
        origin = IrDeclarationOrigin.DEFINED
      }.apply {
        overriddenSymbols = listOf(set.symbol)
      }
      val generatedSetReceiver = generatedSet.buildReceiverParameter {
        type = implementationType
        origin = IrDeclarationOrigin.DEFINED
      }
      generatedSet.parameters += generatedSetReceiver
      val setRoot = generatedSet.addValueParameter(
        name = Name.identifier("root"),
        type = generatedRootType,
        origin = IrDeclarationOrigin.DEFINED,
      )
      val setValue = generatedSet.addValueParameter(
        name = Name.identifier("value"),
        type = generatedFocusType,
        origin = IrDeclarationOrigin.DEFINED,
      )
      val setBuilder = DeclarationIrBuilder(pluginContext, generatedSet.symbol)
      val copyArguments = copy.parameters.drop(1).map { parameter ->
        if (
          pathKind == GeneratedPathKind.COPY_WRITABLE &&
            parameter.name == sourceProperty.name
        ) {
          setBuilder.irGet(setValue)
        } else {
          val getter = checkNotNull(copyPropertyGetters[parameter.name]) {
            "KPaths could not find the source getter for data-class copy parameter '${parameter.name}'."
          }
          setBuilder.irCall(getter.symbol).apply {
            dispatchReceiver = setBuilder.irGet(setRoot)
          }
        }
      }
      val copiedRoot = setBuilder.irCall(copy.symbol).apply {
        dispatchReceiver = setBuilder.irGet(setRoot)
        copyArguments.forEachIndexed { index, argument ->
          arguments[index + 1] = argument
        }
      }
      generatedSet.body = setBuilder.irBlockBody {
        if (pathKind == GeneratedPathKind.COPY_THEN_MUTATE) {
          val copiedRootVariable = irTemporary(copiedRoot, "__kpaths_copy")
          val sourceSetter = sourceProperty.setter ?: return@irBlockBody
          +setBuilder.irCall(sourceSetter.symbol).apply {
            dispatchReceiver = setBuilder.irGet(copiedRootVariable)
            arguments[1] = setBuilder.irGet(setValue)
          }
          +setBuilder.irReturn(setBuilder.irGet(copiedRootVariable))
        } else {
          +setBuilder.irReturn(copiedRoot)
        }
      }
    }

    // This class is added after Kotlin's normal bridge-generation phase. Emit the JVM-erased
    // `get(Any?): Any?` bridge ourselves while retaining a direct, precisely typed accessor for
    // its body. `KeyPath.invoke` dispatches through this override.
    val erasedGet = implementation.addFunction {
      name = Name.identifier("get")
      visibility = DescriptorVisibilities.PUBLIC
      modality = Modality.FINAL
      returnType = pluginContext.irBuiltIns.anyNType
      origin = IrDeclarationOrigin.DEFINED
    }.apply {
      overriddenSymbols = listOf(keyPathGet.symbol)
    }
    val erasedGetReceiver = erasedGet.buildReceiverParameter {
      type = implementationType
      origin = IrDeclarationOrigin.DEFINED
    }
    erasedGet.parameters += erasedGetReceiver
    val erasedRoot = erasedGet.addValueParameter(
      name = Name.identifier("root"),
      type = pluginContext.irBuiltIns.anyNType,
      origin = IrDeclarationOrigin.DEFINED,
    )
    val erasedGetBuilder = DeclarationIrBuilder(pluginContext, erasedGet.symbol)
    val erasedRead = erasedGetBuilder.irCall(generatedGet.symbol).apply {
      dispatchReceiver = erasedGetBuilder.irGet(checkNotNull(erasedGet.dispatchReceiverParameter))
      arguments[1] = erasedGetBuilder.irAs(erasedGetBuilder.irGet(erasedRoot), generatedRootType)
    }
    erasedGet.body = erasedGetBuilder.irBlockBody {
      +erasedGetBuilder.irReturn(erasedRead)
    }
    val callerTypeArguments = pathsClass.typeParameters.map {
      IrSimpleTypeImpl(it.symbol, false, emptyList(), emptyList())
    }
    irCallConstructor(constructor.symbol, callerTypeArguments).apply {
      if (isGeneric) {
        arguments[0] = idExpression
        arguments[1] = valueTypeIdExpression
      }
    }
  }

}

internal enum class GeneratedPathKind {
  READ_ONLY,
  COPY_WRITABLE,
  COPY_THEN_MUTATE,
  REFERENCE_WRITABLE,
}
