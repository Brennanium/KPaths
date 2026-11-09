package dev.kpaths.compiler

import dev.kpaths.compiler.PathsIrSymbols.keyPathCallableId
import dev.kpaths.compiler.PathsIrSymbols.caseKeyPathCallableId
import dev.kpaths.compiler.PathsIrSymbols.caseKeyPathBaseClassId
import dev.kpaths.compiler.PathsIrSymbols.partialCaseKeyPathClassId
import dev.kpaths.compiler.PathsIrSymbols.casePathCatalogClassId
import dev.kpaths.compiler.PathsIrSymbols.casePathCatalogBaseClassId
import dev.kpaths.compiler.PathsIrSymbols.referenceWritableKeyPathQualifiedName
import dev.kpaths.compiler.PathsIrSymbols.writableKeyPathQualifiedName
import dev.kpaths.compiler.PathsIrSymbols.caseKeyPathQualifiedName
import dev.kpaths.compiler.PathsIrSymbols.listOfCallableId

import dev.kpaths.codegen.model.KPathsCaseCatalogAccess
import dev.kpaths.codegen.model.KPathsCaseKind
import dev.kpaths.codegen.model.KPathsCasePathModel
import dev.kpaths.codegen.model.KPathsCasePayload
import dev.kpaths.codegen.model.KPathsCasePayloadDecision
import dev.kpaths.codegen.model.KPathsCasePayloadShape
import dev.kpaths.codegen.model.classifyForKPaths
import dev.kpaths.codegen.model.planKPathsCaseCatalog
import dev.kpaths.codegen.model.planKPathsBoundPayloadMembers
import dev.kpaths.codegen.model.KPathsTypeIdSpec
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallWithSubstitutedType
import org.jetbrains.kotlin.ir.builders.irAs
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.expressions.impl.IrDelegatingConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrPropertyReferenceImpl
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import java.nio.file.Path

internal fun IrAnnotationContainer.generatedCaseName(sourceName: Name): Name {
  val name = (getAnnotation(FqName("dev.kpaths.annotations.CaseName"))?.arguments?.firstOrNull() as? IrConst)
    ?.value as? String ?: return sourceName
  return if (dev.kpaths.codegen.model.validateKPathsConfiguredName(name) == null) Name.identifier(name) else sourceName
}

private fun IrClass.generatedCasePropertyCatalogName(): String {
  val configured = (getAnnotation(FqName("dev.kpaths.annotations.PathsName"))?.arguments?.firstOrNull() as? IrConst)
    ?.value as? String
  return configured?.takeIf { dev.kpaths.codegen.model.validateKPathsConfiguredName(it) == null }
    ?: dev.kpaths.codegen.model.kPathsCasePropertyCatalogName(name.asString())
}

/** Supplies the bodies for the FIR-generated `Paths` object and its cached path fields. */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class PathsIrGenerationExtension(
  private val irSnapshotPath: String?,
) : IrGenerationExtension {
  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    PathsConstructorBodyGenerator(pluginContext).also { generator ->
      moduleFragment.acceptChildrenVoid(generator)
      generator.installDeferredDeclarations()
    }
    irSnapshotPath?.let { PathsIrSnapshot.write(moduleFragment, Path.of(it)) }
  }
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private class PathsConstructorBodyGenerator(
  private val pluginContext: IrPluginContext,
) : IrVisitorVoid() {
  private val deferredDeclarations = linkedMapOf<IrClass, MutableList<IrClass>>()

  fun installDeferredDeclarations() {
    deferredDeclarations.forEach { (owner, declarations) ->
      owner.declarations += declarations
    }
  }

  override fun visitElement(element: IrElement) {
    when (element) {
      is IrClass -> element.declarations.toList().forEach { it.accept(this, null) }
      is IrDeclaration,
      is IrFile,
      is IrModuleFragment,
      -> element.acceptChildrenVoid(this)
    }
  }

  override fun visitConstructor(declaration: IrConstructor) {
    val origin = declaration.origin as? IrDeclarationOrigin.GeneratedByPlugin ?: return
    if (
      (origin.pluginKey != PathsDeclarationKey && origin.pluginKey != PathsCaseDeclarationKey &&
        origin.pluginKey != PathsCasePropertyCatalogKey && origin.pluginKey != PathsGenericCatalogKey) ||
        declaration.body != null
    ) {
      return
    }

    val objectType = declaration.returnType as? IrSimpleType ?: return
    val objectClass = declaration.parent as? IrClass ?: return
    when (origin.pluginKey) {
      PathsDeclarationKey, PathsCasePropertyCatalogKey, PathsGenericCatalogKey -> {
        val catalogBase = pluginContext.referenceClass(casePathCatalogBaseClassId)
        val catalogBaseType = catalogBase?.let { base ->
          objectClass.superTypes.filterIsInstance<IrSimpleType>()
            .singleOrNull { it.classifier == base }
        }
        val superConstructor = catalogBase?.owner?.primaryConstructor
          ?.takeIf { catalogBaseType != null }
          ?: pluginContext.irBuiltIns.anyClass.owner.primaryConstructor
          ?: return
        val superType = catalogBaseType ?: pluginContext.irBuiltIns.anyType
        declaration.body = pluginContext.irFactory.createBlockBody(-1, -1).also { body ->
          body.statements += IrDelegatingConstructorCallImpl(
            startOffset = -1,
            endOffset = -1,
            type = superType,
            symbol = superConstructor.symbol,
            typeArgumentsCount = catalogBaseType?.arguments?.size ?: 0,
          )
          body.statements += IrInstanceInitializerCallImpl(
            startOffset = -1,
            endOffset = -1,
            classSymbol = objectClass.symbol,
            type = objectType,
          )
        }
        if (origin.pluginKey == PathsGenericCatalogKey) {
          val builder = DeclarationIrBuilder(pluginContext, declaration.symbol)
          val tokenParameters = declaration.parameters.filter { it.kind == IrParameterKind.Regular }
          val fields = tokenParameters.map { parameter ->
            objectClass.addField {
              name = parameter.name
              type = parameter.type
              visibility = DescriptorVisibilities.PRIVATE
              isFinal = true
              this.origin = IrDeclarationOrigin.DEFINED
            }
          }
          declaration.body = builder.irBlockBody {
            +irDelegatingConstructorCall(superConstructor)
            fields.zip(tokenParameters).forEach { (field, parameter) ->
              +irSetField(irGet(checkNotNull(objectClass.thisReceiver)), field, irGet(parameter))
            }
            +IrInstanceInitializerCallImpl(-1, -1, objectClass.symbol, objectType)
          }
        }
      }
      PathsCaseDeclarationKey -> {
        val pathsClass = objectClass.parent as? IrClass ?: return
        val rootClass = pathsClass.parent as? IrClass ?: return
        val sourceCase = rootClass.declarations.filterIsInstance<IrClass>()
          .singleOrNull { it.generatedCaseName(it.name) == objectClass.name }
          ?: return
        val caseKeyPathBase = pluginContext.referenceClass(caseKeyPathBaseClassId) ?: return
        val baseConstructor = caseKeyPathBase.owner.primaryConstructor ?: return
        declaration.body = pluginContext.irFactory.createBlockBody(-1, -1).also { body ->
          body.statements += IrDelegatingConstructorCallImpl(
            startOffset = -1,
            endOffset = -1,
            type = caseKeyPathBase.typeWith(rootClass.defaultType, sourceCase.defaultType),
            symbol = baseConstructor.symbol,
            typeArgumentsCount = 0,
          )
          body.statements += IrInstanceInitializerCallImpl(
            startOffset = -1,
            endOffset = -1,
            classSymbol = objectClass.symbol,
            type = objectType,
          )
        }
        DeclarationIrBuilder(pluginContext, declaration.symbol).installDirectCaseObjectMembers(
          implementation = objectClass,
          rootClass = rootClass,
          sourceCase = sourceCase,
        )
      }
    }
  }

  override fun visitProperty(declaration: IrProperty) {
    super.visitProperty(declaration)
    val origin = declaration.origin as? IrDeclarationOrigin.GeneratedByPlugin ?: return
    if (origin.pluginKey == PathsDeclarationKey && declaration.name.asString() == "case") {
      val rootClass = declaration.parent as? IrClass ?: return
      if ((rootClass.origin as? IrDeclarationOrigin.GeneratedByPlugin)?.pluginKey != PathsDeclarationKey) {
        // `case` is computed from the instance. In particular, an interface cannot
        // carry the private instance backing field FIR creates for a generated val.
        declaration.backingField = null
        installRootCaseGetter(rootClass, declaration)
        return
      }
    }
    if (origin.pluginKey == PathsCaseDeclarationKey) {
      val casePathsClass = declaration.parent as? IrClass ?: return
      val pathsClass = casePathsClass.parent as? IrClass ?: return
      val rootClass = pathsClass.parent as? IrClass ?: return
      val sourceCase = rootClass.declarations.filterIsInstance<IrClass>()
        .singleOrNull { it.generatedCaseName(it.name) == casePathsClass.name }
        ?: return
      val sourceProperties = sourceCase.primaryConstructor?.parameters.orEmpty().mapNotNull { parameter ->
        sourceCase.declarations.filterIsInstance<IrProperty>().singleOrNull { it.name == parameter.name }
      }
      val getter = declaration.getter ?: return
      val backingField = declaration.backingField ?: return
      val builder = DeclarationIrBuilder(pluginContext, getter.symbol)
      val generatedInitializer = if (sourceProperties.size in 1..3 &&
        (declaration.name.asString() == "payload" && sourceProperties.size > 1 ||
          sourceProperties.size == 1 && declaration.name == sourceProperties.single().name)
      ) {
        builder.directCasePayloadKeyPathInitializer(
          owner = casePathsClass,
          rootClass = rootClass,
          sourceCase = sourceCase,
          declaration = declaration,
          sourceProperties = sourceProperties,
        )
      } else if (declaration.name.asString() == "unit" && sourceCase.kind == ClassKind.OBJECT) {
        builder.directCaseKeyPathInitializer(casePathsClass, rootClass, declaration)
      } else null
      val initializer: org.jetbrains.kotlin.ir.expressions.IrExpression = generatedInitializer ?: return
      backingField.initializer = DeclarationIrBuilder(pluginContext, getter.symbol).irExprBody(initializer)
      return
    }
    if (origin.pluginKey != PathsDeclarationKey && origin.pluginKey != PathsCasePropertyCatalogKey &&
      origin.pluginKey != PathsGenericCatalogKey) return

    val pathsClass = declaration.parent as? IrClass ?: return
    val rootClass = if (origin.pluginKey == PathsGenericCatalogKey) {
      (pathsClass.parent as? IrClass)?.parent as? IrClass ?: return
    } else if (origin.pluginKey == PathsCasePropertyCatalogKey) {
      val root = (pathsClass.parent as? IrClass)?.parent as? IrClass ?: return
      root.declarations.filterIsInstance<IrClass>()
        .singleOrNull { it.generatedCasePropertyCatalogName() == pathsClass.name.asString() } ?: return
    } else pathsClass.parent as? IrClass ?: return
    // Kotlin IR represents enum entries as synthetic static properties at this extension phase.
    // A bare enum's generated catalog contains case paths, never property key paths.
    val enumCase = rootClass.kind == ClassKind.ENUM_CLASS
    val sourceProperty = rootClass.declarations
      .filterIsInstance<IrProperty>()
      .singleOrNull { property ->
        val alias = property.getAnnotation(FqName("dev.kpaths.annotations.PathName"))
          ?.arguments?.firstOrNull() as? IrConst
        (alias?.value as? String ?: property.name.asString()) == declaration.name.asString()
      }
    val getter = declaration.getter ?: return
    val backingField = declaration.backingField ?: return
    val builder = DeclarationIrBuilder(pluginContext, getter.symbol)
    val generatedInitializer = if (sourceProperty != null && !enumCase) {
      val pathKind = declaration.generatedPathKind(rootClass, sourceProperty)
      builder.directKeyPathInitializer(
        pathsClass = pathsClass,
        rootClass = rootClass,
        sourceProperty = sourceProperty,
        pathKind = pathKind,
      )
        ?: builder.kPropertyFallback(rootClass, sourceProperty)
    } else {
      builder.directCaseKeyPathInitializer(pathsClass, rootClass, declaration)
        ?: builder.generatedCasePathFactoryInitializer(declaration)
    }
    val initializer: org.jetbrains.kotlin.ir.expressions.IrExpression = generatedInitializer ?: return
    backingField.initializer = builder.irExprBody(initializer)
  }

  override fun visitSimpleFunction(declaration: IrSimpleFunction) {
    super.visitSimpleFunction(declaration)
    val origin = declaration.origin as? IrDeclarationOrigin.GeneratedByPlugin ?: return
    if (origin.pluginKey == PathsCaseDeclarationKey) {
      installBoundCasePayloadFunction(declaration)
      return
    }
    if (origin.pluginKey == PathsDeclarationKey && declaration.name.asString() in setOf("invoke", "of")) {
      val pathsClass = declaration.parent as? IrClass ?: return
      val catalog = pathsClass.declarations.filterIsInstance<IrClass>()
        .singleOrNull { (it.origin as? IrDeclarationOrigin.GeneratedByPlugin)?.pluginKey == PathsGenericCatalogKey }
        ?: return
      val constructor = catalog.primaryConstructor ?: return
      val typeId = pluginContext.referenceClass(dev.kpaths.compiler.PathsIrSymbols.typeIdClassId) ?: return
      val companion = typeId.owner.companionObject() ?: return
      val of = companion.declarations.filterIsInstance<IrSimpleFunction>()
        .singleOrNull { it.name.asString() == "of" && it.typeParameters.size == 1 } ?: return
      val builder = DeclarationIrBuilder(pluginContext, declaration.symbol)
      val parameters = declaration.typeParameters.map {
        org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl(it.symbol, false, emptyList(), emptyList())
      }
      val instance = builder.irCallConstructor(constructor.symbol, parameters).apply {
        parameters.forEachIndexed { index, type ->
          arguments[index] = builder.irCallWithSubstitutedType(of.symbol, listOf(type)).apply {
            dispatchReceiver = builder.irGetObject(companion.symbol)
          }
        }
      }
      declaration.body = builder.irBlockBody { +builder.irReturn(instance) }
      return
    }
    if (origin.pluginKey != PathsDeclarationKey || declaration.name.asString() != "__kpaths_buildAllCasePaths") {
      return
    }
    val pathsClass = declaration.parent as? IrClass ?: return
    val rootClass = pathsClass.parent as? IrClass ?: return
    val builder = DeclarationIrBuilder(pluginContext, declaration.symbol)
    val result = builder.directAllCasePathsInitializer(pathsClass, rootClass) ?: return
    declaration.body = builder.irBlockBody {
      +builder.irReturn(result)
    }
  }

  /** Installs the body of a generated `propertyFor` function. */
  @Suppress("DEPRECATION")
  private fun installBoundCasePayloadFunction(function: IrSimpleFunction) {
    val caseCatalog = function.parent as? IrClass ?: return
    val pathsClass = caseCatalog.parent as? IrClass ?: return
    val root = pathsClass.parent as? IrClass ?: return
    val sourceCase = root.declarations.filterIsInstance<IrClass>()
      .singleOrNull { it.generatedCaseName(it.name) == caseCatalog.name } ?: return
    val properties = sourceCase.primaryConstructor?.parameters.orEmpty().mapNotNull { parameter ->
      sourceCase.declarations.filterIsInstance<IrProperty>().singleOrNull { it.name == parameter.name }
    }
    val member = planKPathsBoundPayloadMembers(properties.map { it.name.asString() })
      .singleOrNull { it.generatedName == function.name.asString() } ?: return
    val focusType = properties[member.targetIndex].getter?.returnType ?: return
    val payloadGetter = caseCatalog.declarations.filterIsInstance<IrProperty>()
      .singleOrNull { it.name.asString() == "payload" }?.getter ?: return
    val helper = pluginContext.referenceFunctions(CallableId(FqName("dev.kpaths.core"), Name.identifier("__kpaths_boundCasePayload")))
      .singleOrNull() ?: return
    val listOf = pluginContext.referenceFunctions(listOfCallableId)
      .singleOrNull { it.owner.parameters.singleOrNull()?.varargElementType != null } ?: return
    val builder = DeclarationIrBuilder(pluginContext, function.symbol)
    val caseTypeId = builder.typeIdExpression(sourceCase.toKPathsTypeIdSpec() ?: return) ?: return
    val focusTypeId = builder.typeIdExpression(focusType.toKPathsTypeIdSpec() ?: return) ?: return
    val values = function.parameters.filter { it.kind == IrParameterKind.Regular }.map { builder.irGet(it) }
    val boundList = builder.irCallWithSubstitutedType(listOf, listOf(pluginContext.irBuiltIns.anyNType)).apply {
      arguments[0] = builder.irVararg(pluginContext.irBuiltIns.anyNType, values)
    }
    val result = builder.irCallWithSubstitutedType(helper, listOf(root.defaultType, focusType)).apply {
      arguments[0] = builder.irGetObject(caseCatalog.symbol)
      arguments[1] = builder.irCall(payloadGetter.symbol).apply { dispatchReceiver = builder.irGetObject(caseCatalog.symbol) }
      arguments[2] = caseTypeId
      arguments[3] = focusTypeId
      arguments[4] = builder.irString(member.targetName)
      arguments[5] = builder.irInt(member.targetIndex)
      arguments[6] = boundList
    }
    function.body = builder.irBlockBody { +builder.irReturn(result) }
  }

  /** Implements `CasePathable.case` by dispatching to the generated catalog's default lookup. */
  @Suppress("DEPRECATION")
  private fun installRootCaseGetter(rootClass: IrClass, declaration: IrProperty) {
    val getter = declaration.getter ?: return
    val rootReceiver = getter.dispatchReceiverParameter ?: return
    val pathsClass = rootClass.declarations.filterIsInstance<IrClass>()
      .singleOrNull {
        (it.origin as? IrDeclarationOrigin.GeneratedByPlugin)?.pluginKey == PathsDeclarationKey
      }
      ?: return
    val catalog = pluginContext.referenceClass(casePathCatalogClassId) ?: return
    val caseOf = catalog.owner.declarations.filterIsInstance<IrSimpleFunction>()
      .singleOrNull { it.name.asString() == "caseOf" }
      ?: return
    val builder = DeclarationIrBuilder(pluginContext, getter.symbol)
    getter.body = builder.irBlockBody {
      +builder.irReturn(
        builder.irCall(caseOf.symbol).apply {
          dispatchReceiver = builder.irGetObject(pathsClass.symbol)
          arguments[1] = builder.irGet(rootReceiver)
        },
      )
    }
  }

  /** Emits the cached, type-erased collection used for generic case-path discovery. */
  @Suppress("DEPRECATION")
  private fun DeclarationIrBuilder.directAllCasePathsInitializer(
    pathsClass: IrClass,
    rootClass: IrClass,
  ) = run {
    if (rootClass.typeParameters.isNotEmpty()) return@run null
    val partialCaseKeyPath = pluginContext.referenceClass(partialCaseKeyPathClassId) ?: return@run null
    val listOf = pluginContext.referenceFunctions(listOfCallableId)
      .singleOrNull { it.owner.parameters.singleOrNull()?.varargElementType != null }
      ?: return@run null
    val catalogPlan = planKPathsCaseCatalog(rootClass.caseCatalogModels(pathsClass))
    val pathObjects = catalogPlan.entries.mapNotNull { entry ->
      val model = entry.case
      when (val access = entry.access) {
        KPathsCaseCatalogAccess.WholeCase -> {
          val wholeCase = pathsClass.declarations.filterIsInstance<IrProperty>()
            .singleOrNull { it.name.asString() == model.generatedName }
            ?.getter
            ?: return@mapNotNull null
          irAs(
            irCall(wholeCase.symbol).apply { dispatchReceiver = irGetObject(pathsClass.symbol) },
            partialCaseKeyPath.typeWith(rootClass.defaultType),
          )
        }
        is KPathsCaseCatalogAccess.Payload -> {
          val caseObject = pathsClass.declarations.filterIsInstance<IrClass>()
            .singleOrNull {
              it.name.asString() == model.generatedName &&
                (it.origin as? IrDeclarationOrigin.GeneratedByPlugin)?.pluginKey == PathsCaseDeclarationKey
            }
            ?: return@mapNotNull null
          val payload = caseObject.declarations.filterIsInstance<IrProperty>()
            .singleOrNull { it.name.asString() == access.payload.generatedMemberName }
            ?.getter
            ?: return@mapNotNull null
          irAs(
            irCall(payload.symbol).apply { dispatchReceiver = irGetObject(caseObject.symbol) },
            partialCaseKeyPath.typeWith(rootClass.defaultType),
          )
        }
      }
    }
    val partialType = partialCaseKeyPath.typeWith(rootClass.defaultType)
    irCallWithSubstitutedType(listOf, listOf(partialType)).apply {
      arguments[0] = irVararg(partialType, pathObjects)
    }
  }

  /**
   * Translates the compiler plugin's currently emitted case surface into the shared model.
   *
   * The shared model decides which generated member represents each payload shape.
   */
  private fun IrClass.caseCatalogModels(pathsClass: IrClass): List<KPathsCasePathModel> = when (kind) {
    ClassKind.ENUM_CLASS -> declarations.filterIsInstance<IrEnumEntry>().map { entry ->
      KPathsCasePathModel(
        sourceName = entry.name.asString(),
        generatedName = entry.generatedCaseName(entry.name).asString(),
        kind = KPathsCaseKind.ENUM_ENTRY,
        payload = KPathsCasePayload.Unit,
      )
    }
    else -> declarations.filterIsInstance<IrClass>().filter { sourceCase ->
      pathsClass.declarations.filterIsInstance<IrClass>().any {
        it.name == sourceCase.generatedCaseName(sourceCase.name) &&
          (it.origin as? IrDeclarationOrigin.GeneratedByPlugin)?.pluginKey == PathsCaseDeclarationKey
      }
    }.map { sourceCase ->
      val constructor = sourceCase.primaryConstructor
      val parameters = constructor?.parameters.orEmpty()
      val properties = parameters.mapNotNull { parameter ->
        sourceCase.declarations.filterIsInstance<IrProperty>().singleOrNull { it.name == parameter.name }
      }
      val payload = when {
        sourceCase.kind == ClassKind.OBJECT -> KPathsCasePayload.Unit
        else -> (KPathsCasePayloadShape(
          isDataClass = sourceCase.isData,
          hasPrimaryConstructor = constructor != null,
          isConstructorAccessible = constructor != null && constructor.visibility !in
            setOf(DescriptorVisibilities.PRIVATE, DescriptorVisibilities.PROTECTED),
          propertyNames = parameters.map { it.name.asString() },
          allParametersAreAccessibleProperties = properties.size == parameters.size && properties.all {
            it.visibility !in setOf(DescriptorVisibilities.PRIVATE, DescriptorVisibilities.PROTECTED)
          },
        ).classifyForKPaths() as? KPathsCasePayloadDecision.Available)?.payload
      }
      KPathsCasePathModel(
        sourceName = sourceCase.name.asString(),
        generatedName = sourceCase.generatedCaseName(sourceCase.name).asString(),
        kind = KPathsCaseKind.SEALED_VARIANT,
        payload = payload,
      )
    }
  }

  /**
   * Establishes the runtime shape for a generated whole-case path before replacing it with the
   * same direct implementation strategy used by property paths. This is deliberately isolated:
   * the FIR surface and all consumer code stay unchanged when the direct case emitter lands.
   */
  private fun DeclarationIrBuilder.generatedCasePathFactoryInitializer(
    declaration: IrProperty,
  ) = run {
    val returnType = declaration.getter?.returnType as? IrSimpleType ?: return@run null
    val classifier = returnType.classifier as? IrClassSymbol ?: return@run null
    if (classifier.owner.stableQualifiedName() != caseKeyPathQualifiedName) return@run null
    val typeArguments = returnType.arguments.map { it as? IrTypeProjection ?: return@run null }
    if (typeArguments.size != 2) return@run null
    val rootType = typeArguments[0].type
    val caseType = typeArguments[1].type
    val factory = pluginContext.referenceFunctions(caseKeyPathCallableId)
      .singleOrNull { it.owner.parameters.isEmpty() }
      ?: return@run null
    irCallWithSubstitutedType(factory, listOf(rootType, caseType))
  }

  private val caseEmitter = PathsIrCaseEmitter(pluginContext, deferredDeclarations)

  private fun DeclarationIrBuilder.directCaseKeyPathInitializer(
    pathsClass: IrClass, rootClass: IrClass, declaration: IrProperty,
  ) = with(caseEmitter) { directCaseKeyPathInitializer(pathsClass, rootClass, declaration) }

  private fun DeclarationIrBuilder.installDirectCaseObjectMembers(
    implementation: IrClass, rootClass: IrClass, sourceCase: IrClass,
  ) = with(caseEmitter) { installDirectCaseObjectMembers(implementation, rootClass, sourceCase) }

  private fun DeclarationIrBuilder.directCasePayloadKeyPathInitializer(
    owner: IrClass, rootClass: IrClass, sourceCase: IrClass, declaration: IrProperty,
    sourceProperties: List<IrProperty>,
  ) = with(caseEmitter) { directCasePayloadKeyPathInitializer(owner, rootClass, sourceCase, declaration, sourceProperties) }

  private val propertyEmitter = PathsIrPropertyEmitter(pluginContext, deferredDeclarations)

  private fun DeclarationIrBuilder.directKeyPathInitializer(
    pathsClass: IrClass, rootClass: IrClass, sourceProperty: IrProperty, pathKind: GeneratedPathKind,
  ) = with(propertyEmitter) { directKeyPathInitializer(pathsClass, rootClass, sourceProperty, pathKind) }

  private fun DeclarationIrBuilder.typeIdExpression(type: KPathsTypeIdSpec) =
    emitKPathsTypeId(pluginContext, type)

  /** The prior implementation remains as a temporary fallback while the direct emitter matures. */
  @Suppress("DEPRECATION")
  private fun DeclarationIrBuilder.kPropertyFallback(
    rootClass: IrClass,
    sourceProperty: IrProperty,
  ) = run {
    val sourceGetter = sourceProperty.getter ?: return@run null
    val keyPathFactory = pluginContext.referenceFunctions(keyPathCallableId)
      .singleOrNull { it.owner.parameters.size == 1 }
      ?: return@run null
    val propertyReference = IrPropertyReferenceImpl(
      -1,
      -1,
      pluginContext.irBuiltIns.kProperty1Class.typeWith(rootClass.defaultType, sourceGetter.returnType),
      sourceProperty.symbol,
      0,
      sourceProperty.backingField?.symbol,
      sourceGetter.symbol,
      sourceProperty.setter?.symbol,
      null,
    )
    irCallWithSubstitutedType(keyPathFactory, listOf(rootClass.defaultType, sourceGetter.returnType)).apply {
      arguments[0] = propertyReference
    }
  }

  private fun IrProperty.generatedPathKind(
    rootClass: IrClass,
    sourceProperty: IrProperty,
  ): GeneratedPathKind {
    val classifier = (getter?.returnType as? IrSimpleType)?.classifier as? IrClassSymbol
    return when (classifier?.owner?.stableQualifiedName()) {
      referenceWritableKeyPathQualifiedName -> GeneratedPathKind.REFERENCE_WRITABLE
      writableKeyPathQualifiedName -> {
        val isPrimaryConstructorProperty = rootClass.primaryConstructor
          ?.parameters
          ?.any { it.name == sourceProperty.name }
          ?: false
        if (isPrimaryConstructorProperty) {
          GeneratedPathKind.COPY_WRITABLE
        } else {
          GeneratedPathKind.COPY_THEN_MUTATE
        }
      }
      else -> GeneratedPathKind.READ_ONLY
    }
  }


}
