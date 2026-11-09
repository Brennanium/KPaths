package dev.kpaths.compiler

import dev.kpaths.codegen.model.KPathsCaseCatalogAccess
import dev.kpaths.codegen.model.KPathsCaseKind
import dev.kpaths.codegen.model.KPathsCasePathModel
import dev.kpaths.codegen.model.KPathsCasePayload
import dev.kpaths.codegen.model.KPathsCasePayloadDecision
import dev.kpaths.codegen.model.KPathsCasePayloadShape
import dev.kpaths.codegen.model.classifyForKPaths
import dev.kpaths.codegen.model.planKPathsCaseCatalog
import dev.kpaths.codegen.model.planKPathsBoundPayloadMembers
import dev.kpaths.codegen.model.KPathsTypeArgumentSpec
import dev.kpaths.codegen.model.KPathsTypeIdSpec
import dev.kpaths.codegen.model.KPathsTypeVariance
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.declarations.addBackingField
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addDefaultGetter
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildReceiverParameter
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irCallWithSubstitutedType
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irAs
import org.jetbrains.kotlin.ir.builders.irEquals
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irIfThenElse
import org.jetbrains.kotlin.ir.builders.irIs
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.irTrue
import org.jetbrains.kotlin.ir.builders.irUnit
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.expressions.impl.IrDelegatingConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetEnumValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrPropertyReferenceImpl
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrStarProjection
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeArgument
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.SimpleTypeNullability
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.types.Variance
import java.nio.file.Files
import java.nio.file.Path

private fun IrAnnotationContainer.generatedCaseName(sourceName: Name): Name {
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

/**
 * A deliberately small, stable projection of the generated IR for compiler-plugin tests.
 *
 * Raw IR dumps contain offsets, fake overrides, and implementation details that change between
 * Kotlin releases. This reports only KPaths-owned declarations after the generator has completed,
 * so a fixture can assert the generated API and direct implementation shape without inspecting
 * class files by hand.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
private object PathsIrSnapshot {
  fun write(module: IrModuleFragment, destination: Path) {
    val generated = mutableListOf<IrClass>()
    module.acceptChildrenVoid(object : IrVisitorVoid() {
      override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
      }

      override fun visitClass(declaration: IrClass) {
        if (declaration.isKPathsGeneratedSnapshotClass()) generated += declaration
        declaration.acceptChildrenVoid(this)
      }
    })
    val output = buildString {
      appendLine("KPaths post-generation IR")
      generated.sortedBy { it.snapshotQualifiedName() }.forEach { declaration ->
        appendLine()
        appendLine("class ${declaration.snapshotQualifiedName()}")
        declaration.superTypes
          .map { it.snapshotTypeName() }
          .sorted()
          .forEach { appendLine("  extends $it") }
        declaration.declarations.filterIsInstance<IrProperty>()
          .sortedBy { it.name.asString() }
          .forEach { property ->
            appendLine("  val ${property.name.asString()}: ${property.getter?.returnType?.snapshotTypeName()}")
          }
        declaration.declarations.filterIsInstance<IrSimpleFunction>()
          .filter { it.name.asString() in generatedOperationNames || it.name.asString().endsWith("For") }
          .sortedBy { it.name.asString() }
          .forEach { function ->
            val parameters = function.parameters.drop(1).joinToString(", ") {
              "${it.name.asString()}: ${it.type.snapshotTypeName()}"
            }
            appendLine("  fun ${function.name.asString()}($parameters): ${function.returnType.snapshotTypeName()}")
          }
      }
    }
    Files.createDirectories(destination.parent ?: Path.of("."))
    Files.writeString(destination, output)
  }

  private fun IrClass.isKPathsGeneratedSnapshotClass(): Boolean {
    val pluginOrigin = origin as? IrDeclarationOrigin.GeneratedByPlugin
    return pluginOrigin?.pluginKey in setOf(PathsDeclarationKey, PathsCaseDeclarationKey, PathsCasePropertyCatalogKey) ||
      name.asString().startsWith("__KPaths_")
  }

  private fun IrClass.snapshotQualifiedName(): String {
    val names = mutableListOf(name.asString())
    var current = parent
    while (current is IrClass) {
      names += current.name.asString()
      current = current.parent
    }
    val packageName = (current as? IrPackageFragment)?.packageFqName?.asString().orEmpty()
    return (listOf(packageName) + names.asReversed()).filter(String::isNotBlank).joinToString(".")
  }

  private fun IrType.snapshotTypeName(): String {
    val type = this as? IrSimpleType ?: return toString()
    val baseName = when (val classifier = type.classifier) {
      is IrClassSymbol -> classifier.owner.stableSnapshotQualifiedName() ?: toString()
      is IrTypeParameterSymbol -> classifier.owner.name.asString()
      else -> toString()
    }
    if (type.arguments.isEmpty()) return baseName
    val arguments = type.arguments.joinToString(", ") { argument ->
      when (argument) {
        is IrTypeProjection -> argument.type.snapshotTypeName()
        is IrStarProjection -> "*"
      }
    }
    return "$baseName<$arguments>"
  }

  private fun IrClass.stableSnapshotQualifiedName(): String? {
    val names = mutableListOf<String>()
    var current: IrDeclarationParent? = this
    while (current is IrClass) {
      names += current.name.asString()
      current = current.parent
    }
    val packageName = (current as? IrPackageFragment)?.packageFqName?.asString() ?: return null
    return (listOf(packageName) + names.asReversed()).filter(String::isNotBlank).joinToString(".")
  }

  private val generatedOperationNames = setOf(
    "get",
    "set",
    "setInPlace",
    "match",
    "embed",
    "__kpaths_buildAllCasePaths",
  )
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
        origin.pluginKey != PathsCasePropertyCatalogKey) ||
        declaration.body != null
    ) {
      return
    }

    val objectType = declaration.returnType as? IrSimpleType ?: return
    val objectClass = declaration.parent as? IrClass ?: return
    when (origin.pluginKey) {
      PathsDeclarationKey, PathsCasePropertyCatalogKey -> {
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
    if (origin.pluginKey != PathsDeclarationKey && origin.pluginKey != PathsCasePropertyCatalogKey) return

    val pathsClass = declaration.parent as? IrClass ?: return
    val rootClass = if (origin.pluginKey == PathsCasePropertyCatalogKey) {
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
  private fun DeclarationIrBuilder.directCaseKeyPathInitializer(
    pathsClass: IrClass,
    rootClass: IrClass,
    declaration: IrProperty,
  ) = run {
    if (rootClass.typeParameters.isNotEmpty()) return@run null
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
        it.generatedCaseName(it.name) == pathsClass.name &&
          it.kind == ClassKind.OBJECT &&
          (it.origin as? IrDeclarationOrigin.GeneratedByPlugin)?.pluginKey != PathsDeclarationKey
      }
    if (caseClass.owner.typeParameters.isNotEmpty()) return@run null
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
    val rootTypeIdExpression = typeIdExpression(rootTypeSpec) ?: return@run null
    val valueTypeIdExpression = typeIdExpression(valueTypeSpec) ?: return@run null
    val caseMatchType = caseMatch.typeWith(caseType)

    val implementation = pluginContext.irFactory.buildClass {
      name = Name.identifier("__KPaths_${declaration.name.asString()}_CasePath")
      kind = ClassKind.CLASS
      modality = Modality.FINAL
      visibility = DescriptorVisibilities.PRIVATE
      origin = IrDeclarationOrigin.DEFINED
    }.apply {
      parent = pathsClass
      superTypes = listOf(caseKeyPathBase.typeWith(rootType, caseType))
    }
    deferredDeclarations.getOrPut(pathsClass, ::mutableListOf) += implementation
    val implementationType = IrSimpleTypeImpl(
      implementation.symbol,
      false,
      emptyList(),
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
      property.addBackingField {
        this.name = Name.identifier(name)
        this.type = type
        visibility = DescriptorVisibilities.PRIVATE
        isFinal = true
      }.initializer = irExprBody(initializer)
      property.addDefaultGetter(implementation, pluginContext.irBuiltIns)
    }

    addMetadataProperty(
      name = "__kpaths_id",
      type = pathId.owner.defaultType,
      override = idProperty,
      initializer = irCall(pathIdCase.symbol).apply {
        dispatchReceiver = irGetObject(pathIdCompanion.symbol)
        arguments[1] = rootTypeIdExpression
        arguments[2] = irString((sealedObjectCase?.name ?: enumEntry?.name ?: declaration.name).asString())
      },
    )
    addMetadataProperty(
      name = "__kpaths_valueType",
      type = typeId.owner.defaultType,
      override = valueTypeProperty,
      initializer = valueTypeIdExpression,
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
      type = rootType,
      origin = IrDeclarationOrigin.DEFINED,
    )
    val matchBuilder = DeclarationIrBuilder(pluginContext, generatedMatch.symbol)
    val matchedValue = matchBuilder.irCallConstructor(matchedConstructor.symbol, listOf(caseType)).apply {
      arguments[0] = if (sealedObjectCase != null) {
        matchBuilder.irGetObject(pluginContext.irBuiltIns.unitClass)
      } else if (enumEntry == null && enumEntryGetter == null) {
        matchBuilder.irAs(matchBuilder.irGet(matchRoot), caseType)
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
            matchBuilder.irIs(matchBuilder.irGet(matchRoot), caseType)
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
      returnType = rootType
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
      type = caseType,
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

    irCallConstructor(constructor.symbol, emptyList())
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
  private fun DeclarationIrBuilder.installDirectCaseObjectMembers(
    implementation: IrClass,
    rootClass: IrClass,
    sourceCase: IrClass,
  ) {
    if (rootClass.typeParameters.isNotEmpty() || sourceCase.typeParameters.isNotEmpty()) return
    val rootType = rootClass.defaultType
    val caseType = sourceCase.defaultType
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
    val rootTypeIdExpression = typeIdExpression(rootTypeSpec) ?: return
    val valueTypeIdExpression = typeIdExpression(valueTypeSpec) ?: return
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
      initializer: org.jetbrains.kotlin.ir.expressions.IrExpression,
    ) {
      val getter = property.getter ?: return
      val getterBuilder = DeclarationIrBuilder(pluginContext, getter.symbol)
      getter.body = getterBuilder.irBlockBody {
        +getterBuilder.irReturn(initializer)
      }
    }

    setGetterBody(
      generatedProperty("__kpaths_id") ?: return,
      irCall(pathIdCase.symbol).apply {
        dispatchReceiver = irGetObject(pathIdCompanion.symbol)
        arguments[1] = rootTypeIdExpression
        arguments[2] = irString(sourceCase.name.asString())
      },
    )
    setGetterBody(generatedProperty("__kpaths_valueType") ?: return, valueTypeIdExpression)

    val generatedMatch = implementation.declarations
      .filterIsInstance<IrSimpleFunction>()
      .singleOrNull { it.name.asString() == "match" && it.parameters.size == 2 }
      ?: return
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

    val generatedEmbed = implementation.declarations
      .filterIsInstance<IrSimpleFunction>()
      .singleOrNull { it.name.asString() == "embed" && it.parameters.size == 2 }
      ?: return
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
  private fun DeclarationIrBuilder.directCasePayloadKeyPathInitializer(
    owner: IrClass,
    rootClass: IrClass,
    sourceCase: IrClass,
    declaration: IrProperty,
    sourceProperties: List<IrProperty>,
  ) = run {
    check(rootClass.typeParameters.isEmpty() && sourceCase.typeParameters.isEmpty()) {
      "KPaths direct case payload paths do not yet support generic roots or cases."
    }
    val sourceGetters = sourceProperties.map { checkNotNull(it.getter) }
    val sourceConstructor = checkNotNull(sourceCase.primaryConstructor) {
      "KPaths could not locate the constructor for case '${sourceCase.name}'."
    }
    check(sourceConstructor.parameters.size == sourceProperties.size && sourceProperties.size in 1..3)
    val rootType = rootClass.defaultType
    val caseType = sourceCase.defaultType
    val valueType = ((declaration.getter?.returnType as? IrSimpleType)?.arguments?.getOrNull(1) as? IrTypeProjection)?.type
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
    val rootTypeIdExpression = checkNotNull(typeIdExpression(rootTypeSpec)) { "Missing root TypeId expression." }
    val valueTypeIdExpression = checkNotNull(typeIdExpression(valueTypeSpec)) { "Missing payload TypeId expression." }
    val implementation = pluginContext.irFactory.buildClass {
      name = Name.identifier("__KPaths_${declaration.name.asString()}_CasePath")
      kind = ClassKind.CLASS
      modality = Modality.FINAL
      visibility = DescriptorVisibilities.PRIVATE
      origin = IrDeclarationOrigin.DEFINED
    }.apply {
      parent = owner
      superTypes = listOf(caseKeyPathBase.typeWith(rootType, valueType))
    }
    deferredDeclarations.getOrPut(owner, ::mutableListOf) += implementation
    val implementationType = IrSimpleTypeImpl(implementation.symbol, false, emptyList(), emptyList())
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
      property.addBackingField {
        this.name = Name.identifier(name)
        this.type = type
        visibility = DescriptorVisibilities.PRIVATE
        isFinal = true
      }.initializer = irExprBody(initializer)
      property.addDefaultGetter(implementation, pluginContext.irBuiltIns)
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
    metadata("__kpaths_id", pathId.owner.defaultType, idProperty, payloadId)
    metadata("__kpaths_valueType", typeId.owner.defaultType, valueTypeProperty, valueTypeIdExpression)

    val match = implementation.addFunction {
      name = Name.identifier("match")
      visibility = DescriptorVisibilities.PUBLIC
      modality = Modality.FINAL
      returnType = caseMatch.typeWith(valueType)
      origin = IrDeclarationOrigin.DEFINED
    }.apply { overriddenSymbols = listOf(caseMatchFunction.symbol) }
    match.parameters += match.buildReceiverParameter {
      type = implementationType
      origin = IrDeclarationOrigin.DEFINED
    }
    val matchRoot = match.addValueParameter(Name.identifier("root"), rootType, IrDeclarationOrigin.DEFINED)
    val matchBuilder = DeclarationIrBuilder(pluginContext, match.symbol)
    val matchedPayload = if (sourceGetters.size == 1) {
      matchBuilder.irCall(sourceGetters.single().symbol).apply {
        dispatchReceiver = matchBuilder.irAs(matchBuilder.irGet(matchRoot), caseType)
      }
    } else {
      val tuple = (valueType as IrSimpleType).classifier as IrClassSymbol
      matchBuilder.irCallConstructor(tuple.owner.primaryConstructor!!.symbol, sourceGetters.map { it.returnType }).apply {
        sourceGetters.forEachIndexed { index, getter ->
          arguments[index] = matchBuilder.irCall(getter.symbol).apply {
            dispatchReceiver = matchBuilder.irAs(matchBuilder.irGet(matchRoot), caseType)
          }
        }
      }
    }
    val matchedValue = matchBuilder.irCallConstructor(
      matched.primaryConstructor?.symbol ?: return@run null,
      listOf(valueType),
    ).apply {
      arguments[0] = matchedPayload
    }
    match.body = matchBuilder.irBlockBody {
      +matchBuilder.irReturn(
        matchBuilder.irIfThenElse(
          caseMatch.typeWith(valueType),
          matchBuilder.irIs(matchBuilder.irGet(matchRoot), caseType),
          matchedValue,
          matchBuilder.irGetObject(noMatch.symbol),
        ),
      )
    }

    val embed = implementation.addFunction {
      name = Name.identifier("embed")
      visibility = DescriptorVisibilities.PUBLIC
      modality = Modality.FINAL
      returnType = rootType
      origin = IrDeclarationOrigin.DEFINED
    }.apply { overriddenSymbols = listOf(caseEmbedFunction.symbol) }
    embed.parameters += embed.buildReceiverParameter {
      type = implementationType
      origin = IrDeclarationOrigin.DEFINED
    }
    val embedValue = embed.addValueParameter(Name.identifier("value"), valueType, IrDeclarationOrigin.DEFINED)
    val embedBuilder = DeclarationIrBuilder(pluginContext, embed.symbol)
    embed.body = embedBuilder.irBlockBody {
      +embedBuilder.irReturn(
        embedBuilder.irCallConstructor(sourceConstructor.symbol, emptyList()).apply {
          if (sourceGetters.size == 1) {
            arguments[0] = embedBuilder.irGet(embedValue)
          } else {
            val tuple = (valueType as IrSimpleType).classifier as IrClassSymbol
            sourceGetters.forEachIndexed { index, getter ->
              val component = tuple.owner.declarations.filterIsInstance<IrProperty>()
                .single { it.name.asString() == listOf("first", "second", "third")[index] }.getter!!
              arguments[index] = embedBuilder.irAs(
                embedBuilder.irCall(component.symbol).apply { dispatchReceiver = embedBuilder.irGet(embedValue) },
                getter.returnType,
              )
            }
          }
        },
      )
    }
    irCallConstructor(constructor.symbol, emptyList())
  }

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
   * one instance. This emitter handles concrete generic values and nullable focuses directly.
   * Paths whose root itself has type parameters still use the temporary `KProperty1` fallback.
   */
  private fun DeclarationIrBuilder.directKeyPathInitializer(
    pathsClass: IrClass,
    rootClass: IrClass,
    sourceProperty: IrProperty,
    pathKind: GeneratedPathKind,
  ) = run {
    if (rootClass.typeParameters.isNotEmpty()) return@run null
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
      superTypes = listOf(
        selectedBase.typeWith(rootClass.defaultType, sourceGetter.returnType),
      )
    }
    deferredDeclarations.getOrPut(pathsClass, ::mutableListOf) += implementation
    val implementationType = IrSimpleTypeImpl(
      implementation.symbol,
      false,
      emptyList(),
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
    constructor.body = irBlockBody {
      +irDelegatingConstructorCall(baseConstructor)
      +IrInstanceInitializerCallImpl(
        startOffset = -1,
        endOffset = -1,
        classSymbol = implementation.symbol,
        type = implementationType,
      )
    }

    val rootTypeIdExpression = typeIdExpression(rootTypeSpec) ?: return@run null
    val valueTypeIdExpression = typeIdExpression(valueTypeSpec) ?: return@run null

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
    generatedIdField.initializer = irExprBody(
      irCall(pathIdProperty.symbol).apply {
        dispatchReceiver = irGetObject(pathIdCompanion.symbol)
        arguments[1] = rootTypeIdExpression
        arguments[2] = irString(sourceProperty.name.asString())
      },
    )
    generatedId.addDefaultGetter(implementation, pluginContext.irBuiltIns)

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
    generatedValueTypeField.initializer = irExprBody(valueTypeIdExpression)
    generatedValueType.addDefaultGetter(implementation, pluginContext.irBuiltIns)

    val generatedGet = implementation.addFunction {
      name = Name.identifier("get")
      visibility = DescriptorVisibilities.PUBLIC
      modality = Modality.FINAL
      returnType = sourceGetter.returnType
      origin = IrDeclarationOrigin.DEFINED
    }
    val generatedGetReceiver = generatedGet.buildReceiverParameter {
      type = implementationType
      origin = IrDeclarationOrigin.DEFINED
    }
    generatedGet.parameters += generatedGetReceiver
    val rootParameter = generatedGet.addValueParameter(
      name = Name.identifier("root"),
      type = rootClass.defaultType,
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
        type = rootClass.defaultType,
        origin = IrDeclarationOrigin.DEFINED,
      )
      val setValue = generatedSetInPlace.addValueParameter(
        name = Name.identifier("value"),
        type = sourceGetter.returnType,
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
        returnType = rootClass.defaultType
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
        type = rootClass.defaultType,
        origin = IrDeclarationOrigin.DEFINED,
      )
      val setValue = generatedSet.addValueParameter(
        name = Name.identifier("value"),
        type = sourceGetter.returnType,
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
      arguments[1] = erasedGetBuilder.irAs(erasedGetBuilder.irGet(erasedRoot), rootClass.defaultType)
    }
    erasedGet.body = erasedGetBuilder.irBlockBody {
      +erasedGetBuilder.irReturn(erasedRead)
    }
    irCallConstructor(constructor.symbol, emptyList())
  }

  /**
   * Lowers the shared, frontend-neutral [KPathsTypeIdSpec] model to the core runtime `TypeId`
   * representation. Both generated property and case implementations use this one emitter so a
   * generic focus has identical identity semantics regardless of which catalog exposed it.
   */
  @Suppress("DEPRECATION")
  private fun DeclarationIrBuilder.typeIdExpression(
    type: KPathsTypeIdSpec,
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
      is KPathsTypeIdSpec.Parameter -> null
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

  private fun IrClass.companionObject(): IrClass? =
    declarations.filterIsInstance<IrClass>().singleOrNull { it.isCompanion }

  private fun IrClass.stableQualifiedName(): String? {
    val names = mutableListOf<String>()
    var parent: IrDeclarationParent? = this
    while (parent is IrClass) {
      names += parent.name.asString()
      parent = parent.parent
    }
    val packageName = (parent as? IrPackageFragment)?.packageFqName?.asString() ?: return null
    return (listOf(packageName) + names.asReversed()).filter(String::isNotBlank).joinToString(".")
  }

  private fun IrClass.toKPathsTypeIdSpec(): KPathsTypeIdSpec.Declared? =
    stableQualifiedName()?.let(KPathsTypeIdSpec::Declared)

  private fun IrType.toKPathsTypeIdSpec(): KPathsTypeIdSpec? {
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

  private enum class GeneratedPathKind {
    READ_ONLY,
    COPY_WRITABLE,
    COPY_THEN_MUTATE,
    REFERENCE_WRITABLE,
  }

  private companion object {
    private val keyPathCallableId = CallableId(FqName("dev.kpaths.core"), Name.identifier("keyPath"))
    private val caseKeyPathCallableId = CallableId(
      FqName("dev.kpaths.core"),
      Name.identifier("caseKeyPath"),
    )
    private val anyPathClassId = ClassId.topLevel(FqName("dev.kpaths.core.AnyPath"))
    private val keyPathClassId = ClassId.topLevel(FqName("dev.kpaths.core.KeyPath"))
    private val keyPathBaseClassId = ClassId.topLevel(FqName("dev.kpaths.core.KeyPathBase"))
    private val caseKeyPathClassId = ClassId.topLevel(FqName("dev.kpaths.core.CaseKeyPath"))
    private val caseKeyPathBaseClassId = ClassId.topLevel(FqName("dev.kpaths.core.CaseKeyPathBase"))
    private val partialCaseKeyPathClassId = ClassId.topLevel(FqName("dev.kpaths.core.PartialCaseKeyPath"))
    private val casePathCatalogClassId = ClassId.topLevel(FqName("dev.kpaths.core.CasePathCatalog"))
    private val casePathCatalogBaseClassId = ClassId.topLevel(
      FqName("dev.kpaths.core.CasePathCatalogBase"),
    )
    private val caseMatchClassId = ClassId.topLevel(FqName("dev.kpaths.core.CaseMatch"))
    private val referenceWritableKeyPathClassId = ClassId.topLevel(
      FqName("dev.kpaths.core.ReferenceWritableKeyPath"),
    )
    private val referenceWritableKeyPathBaseClassId = ClassId.topLevel(
      FqName("dev.kpaths.core.ReferenceWritableKeyPathBase"),
    )
    private val writableKeyPathClassId = ClassId.topLevel(FqName("dev.kpaths.core.WritableKeyPath"))
    private val writableKeyPathBaseClassId = ClassId.topLevel(
      FqName("dev.kpaths.core.WritableKeyPathBase"),
    )
    private const val referenceWritableKeyPathQualifiedName =
      "dev.kpaths.core.ReferenceWritableKeyPath"
    private const val writableKeyPathQualifiedName = "dev.kpaths.core.WritableKeyPath"
    private const val caseKeyPathQualifiedName = "dev.kpaths.core.CaseKeyPath"
    private val pathIdClassId = ClassId.topLevel(FqName("dev.kpaths.core.PathId"))
    private val pathSegmentClassId = ClassId.topLevel(FqName("dev.kpaths.core.PathSegment"))
    private val pathSegmentPropertyClassId = ClassId.topLevel(FqName("dev.kpaths.core.PathSegment"))
      .createNestedClassId(Name.identifier("Property"))
    private val typeIdClassId = ClassId.topLevel(FqName("dev.kpaths.core.TypeId"))
    private val typeArgumentClassId = ClassId.topLevel(FqName("dev.kpaths.core.TypeArgument"))
    private val listOfCallableId = CallableId(FqName("kotlin.collections"), Name.identifier("listOf"))
  }
}
