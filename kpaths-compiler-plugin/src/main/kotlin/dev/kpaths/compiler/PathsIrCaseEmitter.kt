package dev.kpaths.compiler

import dev.kpaths.compiler.PathsIrSymbols.anyPathClassId
import dev.kpaths.compiler.PathsIrSymbols.caseKeyPathClassId
import dev.kpaths.compiler.PathsIrSymbols.caseKeyPathBaseClassId
import dev.kpaths.compiler.PathsIrSymbols.caseMatchClassId
import dev.kpaths.compiler.PathsIrSymbols.caseKeyPathQualifiedName
import dev.kpaths.compiler.PathsIrSymbols.pathIdClassId
import dev.kpaths.compiler.PathsIrSymbols.pathSegmentClassId
import dev.kpaths.compiler.PathsIrSymbols.pathSegmentPropertyClassId
import dev.kpaths.compiler.PathsIrSymbols.typeIdClassId

import dev.kpaths.codegen.model.KPathsTypeIdSpec
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.addBackingField
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addDefaultGetter
import org.jetbrains.kotlin.ir.builders.declarations.addGetter
import org.jetbrains.kotlin.ir.builders.declarations.addTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildReceiverParameter
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irAs
import org.jetbrains.kotlin.ir.builders.irEquals
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irIfThenElse
import org.jetbrains.kotlin.ir.builders.irIs
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.impl.IrGetEnumValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.types.IrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

/** Emits whole-case and payload CaseKeyPath implementations. */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class PathsIrCaseEmitter(
  private val pluginContext: IrPluginContext,
  private val deferredDeclarations: MutableMap<IrClass, MutableList<IrClass>>,
) {
  /**
   * Emits a direct, cached implementation for a generated whole-case path. This is the case
   * equivalent of [directKeyPathInitializer]: it performs its type check, `CaseMatch` creation,
   * embedding, and `PathId` construction without Kotlin reflection or closure allocation.
   *
   * Direct sealed children are deliberately the first slice. Generic roots and multi-property
   * associated values can keep using the stable factory fallback while their catalog policies are
   * added.
   */
  @Suppress("DEPRECATION")
  fun DeclarationIrBuilder.directCaseKeyPathInitializer(
    pathsClass: IrClass,
    rootClass: IrClass,
    declaration: IrProperty,
  ) = run {
    val isGeneric = rootClass.typeParameters.isNotEmpty()
    val pathReturnType = declaration.getter?.returnType as? IrSimpleType ?: return@run null
    val returnClassifier = pathReturnType.classifier as? IrClassSymbol ?: return@run null
    if (returnClassifier.owner.stableQualifiedName() != caseKeyPathQualifiedName) return@run null
    val typeArguments = pathReturnType.arguments.map { it as? IrTypeProjection ?: return@run null }
    if (typeArguments.size != 2) return@run null
    val rootType = typeArguments[0].type as? IrSimpleType ?: return@run null
    val caseType = typeArguments[1].type as? IrSimpleType ?: return@run null
    val caseClass = caseType.classifier as? IrClassSymbol ?: return@run null
    val enumEntry = rootClass.declarations.filterIsInstance<IrEnumEntry>()
      .singleOrNull { it.generatedCaseName(it.name) == declaration.name }
    val enumEntryGetter = rootClass.declarations.filterIsInstance<IrProperty>()
      .singleOrNull { property ->
        property.name == enumEntry?.name || (enumEntry == null && property.name == declaration.name)
      }
      ?.getter
    val sealedObjectCase = rootClass.declarations.filterIsInstance<IrClass>()
      .singleOrNull {
        (if (isGeneric) it.generatedCaseName(it.name).asString() + "CasePath" == pathsClass.name.asString()
        else it.generatedCaseName(it.name) == pathsClass.name) &&
          it.kind == ClassKind.OBJECT &&
          (it.origin as? IrDeclarationOrigin.GeneratedByPlugin)?.pluginKey != PathsDeclarationKey
      }
    if (!isGeneric && caseClass.owner.typeParameters.isNotEmpty()) return@run null
    val rootTypeSpec = rootType.toKPathsTypeIdSpec() ?: return@run null
    val valueTypeSpec = caseType.toKPathsTypeIdSpec() ?: return@run null

    val caseKeyPathBase = pluginContext.referenceClass(caseKeyPathBaseClassId) ?: return@run null
    val caseKeyPath = pluginContext.referenceClass(caseKeyPathClassId) ?: return@run null
    val caseMatch = pluginContext.referenceClass(caseMatchClassId) ?: return@run null
    val matched = caseMatch.owner.declarations.filterIsInstance<IrClass>()
      .singleOrNull { it.name.asString() == "Matched" }
      ?: return@run null
    val noMatch = caseMatch.owner.declarations.filterIsInstance<IrClass>()
      .singleOrNull { it.name.asString() == "NoMatch" }
      ?: return@run null
    val matchedConstructor = matched.primaryConstructor ?: return@run null
    val anyPath = pluginContext.referenceClass(anyPathClassId) ?: return@run null
    val pathId = pluginContext.referenceClass(pathIdClassId) ?: return@run null
    val typeId = pluginContext.referenceClass(typeIdClassId) ?: return@run null
    val idProperty = anyPath.owner.declarations.filterIsInstance<IrProperty>()
      .singleOrNull { it.name.asString() == "__kpaths_id" }
      ?: return@run null
    val valueTypeProperty = anyPath.owner.declarations.filterIsInstance<IrProperty>()
      .singleOrNull { it.name.asString() == "__kpaths_valueType" }
      ?: return@run null
    val caseMatchFunction = caseKeyPath.owner.declarations.filterIsInstance<IrSimpleFunction>()
      .singleOrNull { it.name.asString() == "match" }
      ?: return@run null
    val caseEmbedFunction = caseKeyPath.owner.declarations.filterIsInstance<IrSimpleFunction>()
      .singleOrNull { it.name.asString() == "embed" }
      ?: return@run null
    val pathIdCompanion = pathId.owner.companionObject() ?: return@run null
    val pathIdCase = pathIdCompanion.declarations.filterIsInstance<IrSimpleFunction>()
      .singleOrNull { it.name.asString() == "case" }
      ?: return@run null
    val baseConstructor = caseKeyPathBase.owner.primaryConstructor ?: return@run null
    fun resolveParameter(name: String) = pathsClass.declarations.filterIsInstance<IrField>()
      .singleOrNull { it.name.asString() == "__kpaths_type_$name" }
      ?.let { field -> irGetField(irGet(checkNotNull(pathsClass.thisReceiver)), field) }
    val rootTypeIdExpression = emitKPathsTypeId(pluginContext, rootTypeSpec, ::resolveParameter) ?: return@run null
    val valueTypeIdExpression = emitKPathsTypeId(pluginContext, valueTypeSpec, ::resolveParameter) ?: return@run null

    val implementation = pluginContext.irFactory.buildClass {
      name = Name.identifier("__KPaths_${declaration.name.asString()}_CasePath")
      kind = ClassKind.CLASS
      modality = Modality.FINAL
      visibility = DescriptorVisibilities.PRIVATE
      origin = IrDeclarationOrigin.DEFINED
    }.apply {
      parent = pathsClass
    }
    val implementationParameters = pathsClass.typeParameters.map { parameter ->
      implementation.addTypeParameter(parameter.name.asString(), pluginContext.irBuiltIns.anyNType)
    }
    val implementationArguments = implementationParameters.map {
      IrSimpleTypeImpl(it.symbol, false, emptyList(), emptyList())
    }
    val substitutor = IrTypeSubstitutor(pathsClass.typeParameters.map { it.symbol }, implementationArguments)
    implementationParameters.zip(pathsClass.typeParameters).forEach { (generated, original) ->
      generated.superTypes = original.superTypes.map(substitutor::substitute)
    }
    val generatedRootType = substitutor.substitute(rootType)
    val generatedCaseType = substitutor.substitute(caseType)
    val caseMatchType = caseMatch.typeWith(generatedCaseType)
    implementation.superTypes = listOf(caseKeyPathBase.typeWith(generatedRootType, generatedCaseType))
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

    fun addMetadataProperty(
      name: String,
      type: IrType,
      override: IrProperty,
      initializer: org.jetbrains.kotlin.ir.expressions.IrExpression,
    ) {
      val existing = implementation.declarations.filterIsInstance<IrProperty>()
        .singleOrNull { it.name.asString() == name }
      if (existing != null) {
        val getter = existing.getter ?: return
        val getterBuilder = DeclarationIrBuilder(pluginContext, getter.symbol)
        getter.body = getterBuilder.irBlockBody {
          +getterBuilder.irReturn(initializer)
        }
        return
      }
      val property = implementation.addProperty {
        this.name = Name.identifier(name)
        visibility = DescriptorVisibilities.PUBLIC
        modality = Modality.FINAL
        origin = IrDeclarationOrigin.DEFINED
        isVar = false
      }.apply {
        overriddenSymbols = listOf(override.symbol)
      }
      val field = property.addBackingField {
        this.name = Name.identifier(name)
        this.type = type
        visibility = DescriptorVisibilities.PRIVATE
        isFinal = true
      }
      field.initializer = irExprBody(initializer)
      if (isGeneric) {
        val getter = property.addGetter {
          returnType = type
          visibility = DescriptorVisibilities.PUBLIC
        }
        getter.overriddenSymbols = listOf(checkNotNull(override.getter).symbol)
        getter.parameters += getter.buildReceiverParameter { this.type = implementationType }
        val builder = DeclarationIrBuilder(pluginContext, getter.symbol)
        getter.body = builder.irBlockBody {
          +irReturn(irGetField(irGet(checkNotNull(getter.dispatchReceiverParameter)), field))
        }
      } else property.addDefaultGetter(implementation, pluginContext.irBuiltIns)
    }

    val sourceCaseName = sealedObjectCase?.name ?: if (isGeneric) caseClass.owner.name else enumEntry?.name ?: declaration.name
    val idExpression = irCall(pathIdCase.symbol).apply {
      dispatchReceiver = irGetObject(pathIdCompanion.symbol)
      arguments[1] = rootTypeIdExpression
      arguments[2] = irString(sourceCaseName.asString())
    }
    addMetadataProperty(
      name = "__kpaths_id",
      type = pathId.owner.defaultType,
      override = idProperty,
      initializer = idParameter?.let(::irGet) ?: idExpression,
    )
    addMetadataProperty(
      name = "__kpaths_valueType",
      type = typeId.owner.defaultType,
      override = valueTypeProperty,
      initializer = typeParameter?.let(::irGet) ?: valueTypeIdExpression,
    )

    val generatedMatch = implementation.addFunction {
      name = Name.identifier("match")
      visibility = DescriptorVisibilities.PUBLIC
      modality = Modality.FINAL
      returnType = caseMatchType
      origin = IrDeclarationOrigin.DEFINED
    }.apply {
      overriddenSymbols = listOf(caseMatchFunction.symbol)
    }
    generatedMatch.parameters += generatedMatch.buildReceiverParameter {
      type = implementationType
      origin = IrDeclarationOrigin.DEFINED
    }
    val matchRoot = generatedMatch.addValueParameter(
      name = Name.identifier("root"),
      type = generatedRootType,
      origin = IrDeclarationOrigin.DEFINED,
    )
    val matchBuilder = DeclarationIrBuilder(pluginContext, generatedMatch.symbol)
    val matchedValue = matchBuilder.irCallConstructor(matchedConstructor.symbol, listOf(generatedCaseType)).apply {
      arguments[0] = if (sealedObjectCase != null) {
        matchBuilder.irGetObject(pluginContext.irBuiltIns.unitClass)
      } else if (enumEntry == null && enumEntryGetter == null) {
        matchBuilder.irAs(matchBuilder.irGet(matchRoot), generatedCaseType)
      } else {
        matchBuilder.irGetObject(pluginContext.irBuiltIns.unitClass)
      }
    }
    generatedMatch.body = matchBuilder.irBlockBody {
      +matchBuilder.irReturn(
        matchBuilder.irIfThenElse(
          type = caseMatchType,
          condition = if (sealedObjectCase != null) {
            matchBuilder.irIs(matchBuilder.irGet(matchRoot), sealedObjectCase.defaultType)
          } else if (enumEntry == null && enumEntryGetter == null) {
            matchBuilder.irIs(matchBuilder.irGet(matchRoot), generatedCaseType)
          } else {
            matchBuilder.irEquals(
              matchBuilder.irGet(matchRoot),
              matchBuilder.irGetEnumValue(enumEntry, enumEntryGetter),
            )
          },
          thenPart = matchedValue,
          elsePart = matchBuilder.irGetObject(noMatch.symbol),
        ),
      )
    }

    val generatedEmbed = implementation.addFunction {
      name = Name.identifier("embed")
      visibility = DescriptorVisibilities.PUBLIC
      modality = Modality.FINAL
      returnType = generatedRootType
      origin = IrDeclarationOrigin.DEFINED
    }.apply {
      overriddenSymbols = listOf(caseEmbedFunction.symbol)
    }
    generatedEmbed.parameters += generatedEmbed.buildReceiverParameter {
      type = implementationType
      origin = IrDeclarationOrigin.DEFINED
    }
    val embedValue = generatedEmbed.addValueParameter(
      name = Name.identifier("value"),
      type = generatedCaseType,
      origin = IrDeclarationOrigin.DEFINED,
    )
    val embedBuilder = DeclarationIrBuilder(pluginContext, generatedEmbed.symbol)
    generatedEmbed.body = embedBuilder.irBlockBody {
      +embedBuilder.irReturn(
        if (sealedObjectCase != null) {
          embedBuilder.irGetObject(sealedObjectCase.symbol)
        } else if (enumEntry == null && enumEntryGetter == null) {
          embedBuilder.irGet(embedValue)
        } else {
          embedBuilder.irGetEnumValue(enumEntry, enumEntryGetter)
        },
      )
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

  private fun DeclarationIrBuilder.irGetEnumValue(
    entry: IrEnumEntry?,
    getter: IrSimpleFunction?,
  ) = when {
    entry != null -> IrGetEnumValueImpl(
      startOffset,
      endOffset,
      (entry.parent as? IrClass)?.defaultType
        ?: error("KPaths enum entry '${entry.name}' is not owned by an enum class."),
      entry.symbol,
    )
    getter != null -> irCall(getter.symbol)
    else -> error("KPaths could not locate the generated enum entry.")
  }

  /** Fills the concrete `Paths.Case` object generated by FIR with its direct whole-case behavior. */
  @Suppress("DEPRECATION")
  fun DeclarationIrBuilder.installDirectCaseObjectMembers(
    implementation: IrClass,
    rootClass: IrClass,
    sourceCase: IrClass,
  ) {
    val isGeneric = rootClass.typeParameters.isNotEmpty()
    val generatedMatch = implementation.declarations.filterIsInstance<IrSimpleFunction>()
      .singleOrNull { it.name.asString() == "match" && it.parameters.size == 2 } ?: return
    val generatedEmbed = implementation.declarations.filterIsInstance<IrSimpleFunction>()
      .singleOrNull { it.name.asString() == "embed" && it.parameters.size == 2 } ?: return
    val rootType = if (isGeneric) generatedMatch.parameters.last().type else rootClass.defaultType
    val caseType = if (isGeneric) generatedEmbed.parameters.last().type else sourceCase.defaultType
    val rootTypeSpec = rootType.toKPathsTypeIdSpec() ?: return
    val valueTypeSpec = caseType.toKPathsTypeIdSpec() ?: return
    val caseKeyPath = pluginContext.referenceClass(caseKeyPathClassId) ?: return
    val caseMatch = pluginContext.referenceClass(caseMatchClassId) ?: return
    val matched = caseMatch.owner.declarations.filterIsInstance<IrClass>()
      .singleOrNull { it.name.asString() == "Matched" }
      ?: return
    val noMatch = caseMatch.owner.declarations.filterIsInstance<IrClass>()
      .singleOrNull { it.name.asString() == "NoMatch" }
      ?: return
    val matchedConstructor = matched.primaryConstructor ?: return
    val anyPath = pluginContext.referenceClass(anyPathClassId) ?: return
    val pathId = pluginContext.referenceClass(pathIdClassId) ?: return
    val typeId = pluginContext.referenceClass(typeIdClassId) ?: return
    val idProperty = anyPath.owner.declarations.filterIsInstance<IrProperty>()
      .singleOrNull { it.name.asString() == "__kpaths_id" }
      ?: return
    val valueTypeProperty = anyPath.owner.declarations.filterIsInstance<IrProperty>()
      .singleOrNull { it.name.asString() == "__kpaths_valueType" }
      ?: return
    val caseMatchFunction = caseKeyPath.owner.declarations.filterIsInstance<IrSimpleFunction>()
      .singleOrNull { it.name.asString() == "match" }
      ?: return
    val caseEmbedFunction = caseKeyPath.owner.declarations.filterIsInstance<IrSimpleFunction>()
      .singleOrNull { it.name.asString() == "embed" }
      ?: return
    val pathIdCompanion = pathId.owner.companionObject() ?: return
    val pathIdCase = pathIdCompanion.declarations.filterIsInstance<IrSimpleFunction>()
      .singleOrNull { it.name.asString() == "case" }
      ?: return
    val implementationType = implementation.defaultType
    val caseMatchType = caseMatch.typeWith(caseType)

    /**
     * These members are declared in FIR, rather than bolted onto the IR class. That matters for
     * JVM bridges: FIR can see that `match(Delivery)` implements the erased interface member and
     * emits the bridge that an ordinary Kotlin `object : CaseKeyPathBase<…>` receives.
     */
    fun generatedProperty(name: String): IrProperty? = implementation.declarations
      .filterIsInstance<IrProperty>()
      .singleOrNull { it.name.asString() == name }
    fun setGetterBody(
      property: IrProperty,
      initializer: DeclarationIrBuilder.() -> org.jetbrains.kotlin.ir.expressions.IrExpression?,
    ) {
      val getter = property.getter ?: return
      val getterBuilder = DeclarationIrBuilder(pluginContext, getter.symbol)
      val expression = getterBuilder.initializer() ?: return
      getter.body = getterBuilder.irBlockBody {
        +getterBuilder.irReturn(expression)
      }
    }

    setGetterBody(
      generatedProperty("__kpaths_id") ?: return,
    ) {
      val rootTypeIdExpression = emitKPathsTypeId(pluginContext, rootTypeSpec) { name ->
        implementation.declarations.filterIsInstance<IrField>()
          .singleOrNull { it.name.asString() == "__kpaths_type_$name" }
          ?.let { irGetField(irGet(checkNotNull((generatedProperty("__kpaths_id")?.getter)?.dispatchReceiverParameter)), it) }
      } ?: return@setGetterBody null
      irCall(pathIdCase.symbol).apply {
        dispatchReceiver = irGetObject(pathIdCompanion.symbol)
        arguments[1] = rootTypeIdExpression
        arguments[2] = irString(sourceCase.name.asString())
      }
    }
    val valueProperty = generatedProperty("__kpaths_valueType") ?: return
    setGetterBody(valueProperty) {
      emitKPathsTypeId(pluginContext, valueTypeSpec) { name ->
        implementation.declarations.filterIsInstance<IrField>()
          .singleOrNull { it.name.asString() == "__kpaths_type_$name" }
          ?.let { irGetField(irGet(checkNotNull(valueProperty.getter?.dispatchReceiverParameter)), it) }
      }
    }

    val matchRoot = generatedMatch.parameters.last()
    val matchBuilder = DeclarationIrBuilder(pluginContext, generatedMatch.symbol)
    val matchedValue = matchBuilder.irCallConstructor(matchedConstructor.symbol, listOf(caseType)).apply {
      arguments[0] = matchBuilder.irAs(matchBuilder.irGet(matchRoot), caseType)
    }
    generatedMatch.body = matchBuilder.irBlockBody {
      +matchBuilder.irReturn(
        matchBuilder.irIfThenElse(
          type = caseMatchType,
          condition = matchBuilder.irIs(matchBuilder.irGet(matchRoot), caseType),
          thenPart = matchedValue,
          elsePart = matchBuilder.irGetObject(noMatch.symbol),
        ),
      )
    }

    val embedValue = generatedEmbed.parameters.last()
    val embedBuilder = DeclarationIrBuilder(pluginContext, generatedEmbed.symbol)
    generatedEmbed.body = embedBuilder.irBlockBody {
      +embedBuilder.irReturn(embedBuilder.irGet(embedValue))
    }
  }

  /**
   * Emits the leaf of `Parent.Paths.Case.payload` directly inside the generated concrete case
   * object, with direct handling for one to three constructor properties.
   */
  @Suppress("DEPRECATION")
  fun DeclarationIrBuilder.directCasePayloadKeyPathInitializer(
    owner: IrClass,
    rootClass: IrClass,
    sourceCase: IrClass,
    declaration: IrProperty,
    sourceProperties: List<IrProperty>,
  ) = run {
    val isGeneric = rootClass.typeParameters.isNotEmpty()
    val sourceGetters = sourceProperties.map { checkNotNull(it.getter) }
    val sourceConstructor = checkNotNull(sourceCase.primaryConstructor) {
      "KPaths could not locate the constructor for case '${sourceCase.name}'."
    }
    check(sourceConstructor.parameters.size == sourceProperties.size && sourceProperties.size in 1..3)
    val pathArguments = (declaration.getter?.returnType as? IrSimpleType)?.arguments.orEmpty()
    val rootType = if (isGeneric) (pathArguments.getOrNull(0) as? IrTypeProjection)?.type ?: return@run null
      else rootClass.defaultType
    val caseType = if (isGeneric) {
      val base = owner.superTypes.filterIsInstance<IrSimpleType>()
        .singleOrNull { (it.classifier as? IrClassSymbol)?.owner?.stableQualifiedName() ==
          "dev.kpaths.core.CaseKeyPathBase" } ?: return@run null
      (base.arguments.getOrNull(1) as? IrTypeProjection)?.type ?: return@run null
    } else sourceCase.defaultType
    val valueType = (pathArguments.getOrNull(1) as? IrTypeProjection)?.type
      ?: error("KPaths could not resolve the case payload type.")
    val rootTypeSpec = checkNotNull(rootType.toKPathsTypeIdSpec()) { "KPaths could not form root TypeId." }
    val valueTypeSpec = checkNotNull(valueType.toKPathsTypeIdSpec()) { "KPaths could not form payload TypeId." }
    val caseKeyPathBase = checkNotNull(pluginContext.referenceClass(caseKeyPathBaseClassId)) { "Missing CaseKeyPathBase." }
    val caseKeyPath = checkNotNull(pluginContext.referenceClass(caseKeyPathClassId)) { "Missing CaseKeyPath." }
    val caseMatch = checkNotNull(pluginContext.referenceClass(caseMatchClassId)) { "Missing CaseMatch." }
    val matched = caseMatch.owner.declarations.filterIsInstance<IrClass>()
      .singleOrNull { it.name.asString() == "Matched" }
      ?: error("Missing CaseMatch.Matched.")
    val noMatch = caseMatch.owner.declarations.filterIsInstance<IrClass>()
      .singleOrNull { it.name.asString() == "NoMatch" }
      ?: error("Missing CaseMatch.NoMatch.")
    val anyPath = checkNotNull(pluginContext.referenceClass(anyPathClassId)) { "Missing AnyPath." }
    val pathId = checkNotNull(pluginContext.referenceClass(pathIdClassId)) { "Missing PathId." }
    val typeId = checkNotNull(pluginContext.referenceClass(typeIdClassId)) { "Missing TypeId." }
    val pathSegment = checkNotNull(pluginContext.referenceClass(pathSegmentClassId)) { "Missing PathSegment." }
    val pathSegmentProperty = checkNotNull(pluginContext.referenceClass(pathSegmentPropertyClassId)) {
      "Missing PathSegment.Property."
    }
    val idProperty = anyPath.owner.declarations.filterIsInstance<IrProperty>()
      .singleOrNull { it.name.asString() == "__kpaths_id" }
      ?: error("Missing AnyPath.__kpaths_id.")
    val valueTypeProperty = anyPath.owner.declarations.filterIsInstance<IrProperty>()
      .singleOrNull { it.name.asString() == "__kpaths_valueType" }
      ?: error("Missing AnyPath.__kpaths_valueType.")
    val caseMatchFunction = caseKeyPath.owner.declarations.filterIsInstance<IrSimpleFunction>()
      .singleOrNull { it.name.asString() == "match" }
      ?: error("Missing CaseKeyPath.match.")
    val caseEmbedFunction = caseKeyPath.owner.declarations.filterIsInstance<IrSimpleFunction>()
      .singleOrNull { it.name.asString() == "embed" }
      ?: error("Missing CaseKeyPath.embed.")
    val pathIdCompanion = pathId.owner.companionObject() ?: error("Missing PathId companion.")
    val pathIdCase = pathIdCompanion.declarations.filterIsInstance<IrSimpleFunction>()
      .singleOrNull { it.name.asString() == "case" }
      ?: error("Missing PathId.case.")
    val pathIdAppending = pathId.owner.declarations.filterIsInstance<IrSimpleFunction>()
      .singleOrNull {
        it.name.asString() == "appending" &&
          (it.parameters.lastOrNull()?.type as? IrSimpleType)?.classifier == pathSegment
      }
      ?: error("Missing PathId.appending(PathSegment).")
    val propertySegmentConstructor = pathSegmentProperty.owner.primaryConstructor
      ?: error("Missing PathSegment.Property constructor.")
    fun resolveParameter(name: String) = owner.declarations.filterIsInstance<IrField>()
      .singleOrNull { it.name.asString() == "__kpaths_type_$name" }
      ?.let { field -> irGetField(irGet(checkNotNull(owner.thisReceiver)), field) }
    val rootTypeIdExpression = checkNotNull(emitKPathsTypeId(pluginContext, rootTypeSpec, ::resolveParameter)) { "Missing root TypeId expression." }
    val valueTypeIdExpression = checkNotNull(emitKPathsTypeId(pluginContext, valueTypeSpec, ::resolveParameter)) { "Missing payload TypeId expression." }
    val implementation = pluginContext.irFactory.buildClass {
      name = Name.identifier("__KPaths_${declaration.name.asString()}_CasePath")
      kind = ClassKind.CLASS
      modality = Modality.FINAL
      visibility = DescriptorVisibilities.PRIVATE
      origin = IrDeclarationOrigin.DEFINED
    }.apply {
      parent = owner
    }
    val implementationParameters = owner.typeParameters.map { parameter ->
      implementation.addTypeParameter(parameter.name.asString(), pluginContext.irBuiltIns.anyNType)
    }
    val implementationArguments = implementationParameters.map {
      IrSimpleTypeImpl(it.symbol, false, emptyList(), emptyList())
    }
    val substitutor = IrTypeSubstitutor(owner.typeParameters.map { it.symbol }, implementationArguments)
    implementationParameters.zip(owner.typeParameters).forEach { (generated, original) ->
      generated.superTypes = original.superTypes.map(substitutor::substitute)
    }
    val generatedRootType = substitutor.substitute(rootType)
    val generatedCaseType = substitutor.substitute(caseType)
    val generatedValueType = substitutor.substitute(valueType)
    val generatedCaseArguments = (generatedCaseType as? IrSimpleType)?.arguments.orEmpty()
      .mapNotNull { (it as? IrTypeProjection)?.type }
    val caseSubstitutor = IrTypeSubstitutor(sourceCase.typeParameters.map { it.symbol }, generatedCaseArguments)
    implementation.superTypes = listOf(caseKeyPathBase.typeWith(generatedRootType, generatedValueType))
    deferredDeclarations.getOrPut(owner, ::mutableListOf) += implementation
    val implementationType = IrSimpleTypeImpl(implementation.symbol, false, implementationArguments, emptyList())
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
      +irDelegatingConstructorCall(caseKeyPathBase.owner.primaryConstructor ?: return@run null)
      +IrInstanceInitializerCallImpl(-1, -1, implementation.symbol, implementationType)
    }

    fun metadata(
      name: String,
      type: IrType,
      overridden: IrProperty,
      initializer: org.jetbrains.kotlin.ir.expressions.IrExpression,
    ) {
      val property = implementation.addProperty {
        this.name = Name.identifier(name)
        visibility = DescriptorVisibilities.PUBLIC
        modality = Modality.FINAL
        origin = IrDeclarationOrigin.DEFINED
        isVar = false
      }.apply { overriddenSymbols = listOf(overridden.symbol) }
      val field = property.addBackingField {
        this.name = Name.identifier(name)
        this.type = type
        visibility = DescriptorVisibilities.PRIVATE
        isFinal = true
      }
      field.initializer = irExprBody(initializer)
      if (isGeneric) {
        val getter = property.addGetter {
          returnType = type
          visibility = DescriptorVisibilities.PUBLIC
        }
        getter.overriddenSymbols = listOf(checkNotNull(overridden.getter).symbol)
        getter.parameters += getter.buildReceiverParameter { this.type = implementationType }
        val builder = DeclarationIrBuilder(pluginContext, getter.symbol)
        getter.body = builder.irBlockBody {
          +irReturn(irGetField(irGet(checkNotNull(getter.dispatchReceiverParameter)), field))
        }
      } else property.addDefaultGetter(implementation, pluginContext.irBuiltIns)
    }
    val caseId = irCall(pathIdCase.symbol).apply {
      dispatchReceiver = irGetObject(pathIdCompanion.symbol)
      arguments[1] = rootTypeIdExpression
      arguments[2] = irString(sourceCase.name.asString())
    }
    val payloadId = sourceProperties.fold(caseId as org.jetbrains.kotlin.ir.expressions.IrExpression) { prefix, property ->
      irCall(pathIdAppending.symbol).apply {
        dispatchReceiver = prefix
        arguments[1] = irCallConstructor(propertySegmentConstructor.symbol, emptyList()).apply {
          arguments[0] = irString(property.name.asString())
        }
      }
    }
    metadata("__kpaths_id", pathId.owner.defaultType, idProperty, idParameter?.let(::irGet) ?: payloadId)
    metadata("__kpaths_valueType", typeId.owner.defaultType, valueTypeProperty, typeParameter?.let(::irGet) ?: valueTypeIdExpression)

    val match = implementation.addFunction {
      name = Name.identifier("match")
      visibility = DescriptorVisibilities.PUBLIC
      modality = Modality.FINAL
      returnType = caseMatch.typeWith(generatedValueType)
      origin = IrDeclarationOrigin.DEFINED
    }.apply { overriddenSymbols = listOf(caseMatchFunction.symbol) }
    match.parameters += match.buildReceiverParameter {
      type = implementationType
      origin = IrDeclarationOrigin.DEFINED
    }
    val matchRoot = match.addValueParameter(Name.identifier("root"), generatedRootType, IrDeclarationOrigin.DEFINED)
    val matchBuilder = DeclarationIrBuilder(pluginContext, match.symbol)
    val matchedPayload = if (sourceGetters.size == 1) {
      matchBuilder.irCall(sourceGetters.single().symbol).apply {
        dispatchReceiver = matchBuilder.irAs(matchBuilder.irGet(matchRoot), generatedCaseType)
      }
    } else {
      val tuple = (generatedValueType as IrSimpleType).classifier as IrClassSymbol
      matchBuilder.irCallConstructor(tuple.owner.primaryConstructor!!.symbol, sourceGetters.map { caseSubstitutor.substitute(it.returnType) }).apply {
        sourceGetters.forEachIndexed { index, getter ->
          arguments[index] = matchBuilder.irCall(getter.symbol).apply {
            dispatchReceiver = matchBuilder.irAs(matchBuilder.irGet(matchRoot), generatedCaseType)
          }
        }
      }
    }
    val matchedValue = matchBuilder.irCallConstructor(
      matched.primaryConstructor?.symbol ?: return@run null,
      listOf(generatedValueType),
    ).apply {
      arguments[0] = matchedPayload
    }
    match.body = matchBuilder.irBlockBody {
      +matchBuilder.irReturn(
        matchBuilder.irIfThenElse(
          caseMatch.typeWith(generatedValueType),
          matchBuilder.irIs(matchBuilder.irGet(matchRoot), generatedCaseType),
          matchedValue,
          matchBuilder.irGetObject(noMatch.symbol),
        ),
      )
    }

    val embed = implementation.addFunction {
      name = Name.identifier("embed")
      visibility = DescriptorVisibilities.PUBLIC
      modality = Modality.FINAL
      returnType = generatedRootType
      origin = IrDeclarationOrigin.DEFINED
    }.apply { overriddenSymbols = listOf(caseEmbedFunction.symbol) }
    embed.parameters += embed.buildReceiverParameter {
      type = implementationType
      origin = IrDeclarationOrigin.DEFINED
    }
    val embedValue = embed.addValueParameter(Name.identifier("value"), generatedValueType, IrDeclarationOrigin.DEFINED)
    val embedBuilder = DeclarationIrBuilder(pluginContext, embed.symbol)
    embed.body = embedBuilder.irBlockBody {
      +embedBuilder.irReturn(
        embedBuilder.irCallConstructor(sourceConstructor.symbol, generatedCaseArguments).apply {
          if (sourceGetters.size == 1) {
            arguments[0] = embedBuilder.irGet(embedValue)
          } else {
            val tuple = (generatedValueType as IrSimpleType).classifier as IrClassSymbol
            sourceGetters.forEachIndexed { index, getter ->
              val component = tuple.owner.declarations.filterIsInstance<IrProperty>()
                .single { it.name.asString() == listOf("first", "second", "third")[index] }.getter!!
              arguments[index] = embedBuilder.irAs(
                embedBuilder.irCall(component.symbol).apply { dispatchReceiver = embedBuilder.irGet(embedValue) },
                caseSubstitutor.substitute(getter.returnType),
              )
            }
          }
        },
      )
    }
    val callerTypeArguments = owner.typeParameters.map {
      IrSimpleTypeImpl(it.symbol, false, emptyList(), emptyList())
    }
    irCallConstructor(constructor.symbol, callerTypeArguments).apply {
      if (isGeneric) {
        arguments[0] = payloadId
        arguments[1] = valueTypeIdExpression
      }
    }
  }

  private fun DeclarationIrBuilder.typeIdExpression(type: KPathsTypeIdSpec) =
    emitKPathsTypeId(pluginContext, type)
}
