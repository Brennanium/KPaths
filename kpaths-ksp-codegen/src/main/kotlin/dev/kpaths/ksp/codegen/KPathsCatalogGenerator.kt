package dev.kpaths.ksp.codegen

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Origin
import com.google.devtools.ksp.symbol.Variance
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName
import dev.kpaths.annotations.CaseName
import dev.kpaths.annotations.PathsAdapter
import dev.kpaths.annotations.PathName
import dev.kpaths.annotations.PathsName
import dev.kpaths.codegen.model.KPathsCaseCatalogAccess
import dev.kpaths.codegen.model.KPathsCaseKind
import dev.kpaths.codegen.model.KPathsCasePathModel
import dev.kpaths.codegen.model.KPathsCasePayload
import dev.kpaths.codegen.model.KPathsCasePayloadDecision
import dev.kpaths.codegen.model.KPathsCasePayloadShape
import dev.kpaths.codegen.model.planKPathsCaseCatalog
import dev.kpaths.codegen.model.KPathsCasePolicyResult
import dev.kpaths.codegen.model.KPathsCaseShape
import dev.kpaths.codegen.model.KPathsDiagnostics
import dev.kpaths.codegen.model.KPathsGeneratedMember as GeneratedMember
import dev.kpaths.codegen.model.KPathsGenericRootParameter
import dev.kpaths.codegen.model.KPathsGenericCaseArgument
import dev.kpaths.codegen.model.KPathsGenericCasePlan
import dev.kpaths.codegen.model.planKPathsGenericCase
import dev.kpaths.codegen.model.kPathsGenericCaseDiagnostic
import dev.kpaths.codegen.model.hasAmbiguousKPathsCompanion
import dev.kpaths.codegen.model.isKPathsGenericVariantOfConcreteRoot
import dev.kpaths.codegen.model.findKPathsGeneratedMemberCollisions
import dev.kpaths.codegen.model.isKPathsReservedName
import dev.kpaths.codegen.model.KPATHS_INTERNAL_PREFIX
import dev.kpaths.codegen.model.validateKPathsConfiguredName
import dev.kpaths.codegen.model.kPathsConfiguredNameDiagnostic
import dev.kpaths.codegen.model.kPathsCasePropertyCatalogName
import dev.kpaths.codegen.model.planKPathsBoundPayloadMembers
import dev.kpaths.codegen.model.KPathsPropertyPathKind
import dev.kpaths.codegen.model.KPathsPropertyPolicyResult
import dev.kpaths.codegen.model.KPathsPropertyShape
import dev.kpaths.codegen.model.KPathsTypeArgumentSpec
import dev.kpaths.codegen.model.KPathsTypeIdSpec
import dev.kpaths.codegen.model.KPathsTypeVariance
import dev.kpaths.codegen.model.kPathsAdapterDiagnostic
import dev.kpaths.codegen.model.classifyForKPaths
import java.nio.file.Files
import java.nio.file.Path

private val caseNameAnnotation = CaseName::class.qualifiedName!!
private val kpathsAdapterAnnotation = PathsAdapter::class.qualifiedName!!
private val pathNameAnnotation = PathName::class.qualifiedName!!
private val pathsNameAnnotation = PathsName::class.qualifiedName!!

/**
 * A request to generate one KPaths property and case catalog.
 *
 * This API is intended for other KSP processors which want to generate the
 * exact same catalogs as `@Pathable`. Ordinary applications should configure
 * the `kpaths-ksp` processor and use `@Pathable` instead.
 */
public data class KPathsCatalogRequest(
  public val declaration: KSClassDeclaration,
  public val generatedName: String,
  public val annotationQualifiedName: String? = null,
  public val annotationDisplayName: String = "@Pathable",
  public val renameSuggestion: String? = null,
  /** Whether primary-constructor `val` properties of data classes receive copy-writable paths. */
  public val copyWritableVals: Boolean = false,
  /**
   * Primary-constructor `val` properties that remain read-only when [copyWritableVals] is enabled.
   *
   * A requesting processor supplies the exact KSP property declarations it wants to exclude, which
   * avoids coupling this shared generator to another annotation's naming convention.
   */
  public val copyWritableValExclusions: Set<KSPropertyDeclaration> = emptySet(),
  /**
   * Ordered integrations that may replace the ordinary generated path implementation for an
   * otherwise eligible property. The first non-null recipe wins.
   *
   * Adapters cannot alter generated catalog names, member names, collision diagnostics, or path
   * identity. They only choose the generated property's public path type and anonymous-object base.
   */
  public val propertyPathAdapters: List<KPathsPropertyPathAdapter> = emptyList(),
)

/** Lets an integration provide a specialized KeyPath-compatible implementation for a property. */
public fun interface KPathsPropertyPathAdapter {
  /** Returns a recipe for [context], or `null` to keep KPaths' ordinary generated path. */
  public fun pathRecipe(context: KPathsPropertyPathContext): KPathsPropertyPathRecipe?
}

/**
 * The constrained information available while generating a single eligible property path.
 *
 * This context intentionally does not expose the containing catalog builder or generated member
 * names. Use [pathId] and [propertyAccess] rather than reconstructing KPaths source details.
 */
public class KPathsPropertyPathContext internal constructor(
  public val declaration: KSPropertyDeclaration,
  public val rootType: TypeName,
  public val sourceValueType: TypeName,
  public val isPrimaryConstructorProperty: Boolean,
  public val isDataClass: Boolean,
  public val isMutable: Boolean,
  public val copyWritableVals: Boolean,
  /** The generated catalog member name, escaped when Kotlin source requires it. */
  public val escapedPropertyName: String,
  private val rootTypeId: CodeBlock,
  private val sourcePropertyName: String,
  private val typeIdRenderer: (KSType) -> CodeBlock,
) {
  /**
   * The qualified name of the storage type's declared [PathsAdapter.factory], if it has one.
   *
   * An integration uses this as an identity check before returning a specialized recipe. KPaths
   * does not instantiate the factory: processor and compiler-plugin artifacts own that behavior,
   * which keeps the annotation module free of compiler dependencies.
   */
  public val adapterFactoryQualifiedName: String?
    get() {
      val storageType = declaration.type.resolve()
      val storageDeclaration = storageType.declaration as? KSClassDeclaration ?: return null
      val adapter = storageDeclaration.annotations.firstOrNull { annotation ->
        annotation.annotationType.resolve().declaration.qualifiedName?.asString() == kpathsAdapterAnnotation
      } ?: return null
      val factory = adapter.arguments
        .singleOrNull { argument -> argument.name?.asString() == "factory" }
        ?.value
      return when (factory) {
        is KSType -> factory.declaration.qualifiedName?.asString()
        is KSClassDeclaration -> factory.qualifiedName?.asString()
        else -> null
      }
    }

  /** Builds KPaths' stable identity for this property. [valueType] records the adapter's focus. */
  public fun pathId(valueType: TypeName): CodeBlock {
    @Suppress("UNUSED_VARIABLE")
    val ignoredValueType = valueType
    return CodeBlock.of(
      "%T.property(%L, %S)",
      ClassName("dev.kpaths.core", "PathId"),
      rootTypeId,
      sourcePropertyName,
    )
  }

  /**
   * Renders KPaths' structural [dev.kpaths.core.TypeId] metadata for [type].
   *
   * Specialized paths commonly focus a component of the source property rather than its declared
   * type (for example, a wrapper's contained value). Using this helper preserves generic type
   * tokens and nullability without requiring an integration to duplicate KPaths' type-ID rules.
   */
  public fun typeId(type: KSType): CodeBlock = typeIdRenderer(type)

  /** Produces safe Kotlin member access for this property, including backtick identifiers. */
  public fun propertyAccess(rootExpression: CodeBlock): CodeBlock =
    CodeBlock.of("%L.%L", rootExpression, escapeKotlinIdentifier(sourcePropertyName))
}

/** The KeyPath capability an adapter-provided implementation exposes. */
public enum class KPathsPathCapability {
  READ_ONLY,
  WRITABLE,
  REFERENCE_WRITABLE,
}

/**
 * Structured replacement for one generated property-path implementation.
 *
 * KPaths emits `object : implementationBaseType(constructorArguments...) {}` and assigns it to
 * [publicPathType]. The integration owns the behavior in its base type; KPaths retains all catalog
 * structure and identity policy.
 */
public data class KPathsPropertyPathRecipe(
  public val publicPathType: TypeName,
  public val implementationBaseType: TypeName,
  public val constructorArguments: List<CodeBlock>,
  public val capability: KPathsPathCapability,
)

private fun escapeKotlinIdentifier(identifier: String): String =
  if (identifier in kotlinKeywordsForCodegen || !identifier.isKotlinIdentifierForCodegen()) "`$identifier`" else identifier

private fun String.isKotlinIdentifierForCodegen(): Boolean =
  isNotEmpty() && first().isLetterOrUnderscoreForCodegen() && drop(1).all { it.isLetterOrDigitOrUnderscoreForCodegen() }

private fun Char.isLetterOrUnderscoreForCodegen(): Boolean = isLetter() || this == '_'

private fun Char.isLetterOrDigitOrUnderscoreForCodegen(): Boolean = isLetterOrDigit() || this == '_'

private val kotlinKeywordsForCodegen = setOf(
  "as", "as?", "break", "class", "continue", "do", "else", "false", "for", "fun", "if",
  "in", "!in", "interface", "is", "!is", "null", "object", "package", "return", "super",
  "this", "throw", "true", "try", "typealias", "val", "var", "when", "while",
)

/** Returns KPaths' conventional top-level catalog name, or `null` for a local declaration. */
public fun defaultKPathsCatalogName(declaration: KSClassDeclaration): String? {
  val qualifiedName = declaration.qualifiedName?.asString() ?: return null
  val packageName = declaration.packageName.asString()
  return qualifiedName.removePrefix("$packageName.").replace('.', '_') + "Paths"
}

/**
 * Shared KSP analysis and emission for KPaths catalogs.
 *
 * A processor discovers the declarations it owns, converts them into
 * [KPathsCatalogRequest] values, and passes the entire set to [generate].
 * Passing a complete set lets the generator diagnose colliding catalog names
 * before writing any source files.
 */
public class KPathsCatalogGenerator(
  private val codeGenerator: CodeGenerator,
  private val logger: KSPLogger,
) {
  private var requestsByDeclaration: Map<KSClassDeclaration, KPathsCatalogRequest> = emptyMap()

  /** Generates catalogs for [requests], returning symbols KSP should defer to a later round. */
  public fun generate(
    resolver: Resolver,
    requests: Iterable<KPathsCatalogRequest>,
  ): List<KSAnnotated> {
    val deferred = mutableListOf<KSAnnotated>()
    val targets = mutableListOf<KPathsCatalogRequest>()
    val allRequests = requests.toList()
    requestsByDeclaration = allRequests.associateBy(KPathsCatalogRequest::declaration)

    allRequests.forEach { request ->
      val declaration = request.declaration
      if (!declaration.validate(enableNewFeatures = true)) {
        deferred += declaration
        return@forEach
      }
      if (declaration.qualifiedName == null) {
        reportGenerationError(declaration, "KPaths catalog types must have a qualified name.")
        return@forEach
      }
      targets += request
    }

    val duplicateNames = targets.groupBy { target ->
      generatedQualifiedName(target.declaration.packageName.asString(), target.generatedName)
    }.filterValues { it.size > 1 }
    duplicateNames.values.flatten().forEach { target ->
      val others = duplicateNames.getValue(
        generatedQualifiedName(target.declaration.packageName.asString(), target.generatedName),
      ).filterNot { it.declaration == target.declaration }
      reportGenerationError(
        target.declaration,
        "Cannot generate '${target.generatedName}' because ${target.annotationDisplayName} also requests that name for " +
          others.joinToString(" and ") { "'${it.declaration.qualifiedName?.asString()}'" } + ". " +
          "Choose distinct generated catalog names.",
      )
    }
    targets.filter { target ->
      generatedQualifiedName(target.declaration.packageName.asString(), target.generatedName) !in duplicateNames
    }.forEach { target ->
      val packageName = target.declaration.packageName.asString()
      if (validateTopLevelGeneratedName(target.declaration, resolver, packageName, target.generatedName)) {
        generatePaths(
          declaration = target.declaration,
          generatedName = target.generatedName,
          copyWritableVals = target.copyWritableVals,
          copyWritableValExclusions = target.copyWritableValExclusions,
          propertyPathAdapters = target.propertyPathAdapters,
          resolver = resolver,
        )
      }
    }

    return deferred
  }

  private fun generatePaths(
    declaration: KSClassDeclaration,
    generatedName: String,
    copyWritableVals: Boolean,
    copyWritableValExclusions: Set<KSPropertyDeclaration>,
    propertyPathAdapters: List<KPathsPropertyPathAdapter>,
    resolver: Resolver,
  ) {
    val qualifiedName = declaration.qualifiedName?.asString() ?: run {
      reportGenerationError(declaration, "KPaths catalog types must have a qualified name.")
      return
    }
    val packageName = declaration.packageName.asString()
    val generatedIdentifier = escapeIdentifier(generatedName)
    if (!validateUnnamedCompanionPlacement(declaration)) {
      return
    }
    val copyWritableValExclusionIds = copyWritableValExclusions
      .mapNotNull(::copyWritableValExclusionId)
      .toSet()
    val typeTokenNames = declaration.typeParameters.associate { parameter ->
      parameter.name.asString() to typeTokenName(parameter.name.asString())
    }
    val typeTokenArgumentNames = declaration.typeParameters.associate { parameter ->
      parameter.name.asString() to typeTokenArgumentName(parameter.name.asString())
    }
    val generatedRootTypeName = generatedInternalPrefix + "rootType"
    val rootKotlinType = renderKotlinQualifiedName(qualifiedName) + declaration.typeParameters
      .takeIf { it.isNotEmpty() }
      ?.joinToString(prefix = "<", postfix = ">") { escapeIdentifier(it.name.asString()) }
      .orEmpty()
    val rootTypeId = renderDeclaredTypeId(qualifiedName, declaration.typeParameters, typeTokenNames)
    val unadaptedPathProperties = pathProperties(declaration, copyWritableVals, copyWritableValExclusionIds)
    val unadaptedCasePaths = casePaths(declaration, copyWritableVals, copyWritableValExclusionIds)
    val companionPaths = companionPathsExtension(declaration, unadaptedPathProperties, unadaptedCasePaths)
    val genericCompanionPaths = genericCompanionPathsFactory(
      declaration,
      unadaptedPathProperties,
      unadaptedCasePaths,
    )
    val companionGeneratedMember = when {
      companionPaths != null -> CompanionGeneratedMember(
        companion = companionPaths.companion,
        name = companionPaths.namespaceName,
      )
      genericCompanionPaths != null -> CompanionGeneratedMember(
        companion = genericCompanionPaths.companion,
        name = if (genericCompanionPaths.isDirectCompanionNamespace) "of" else genericCompanionPaths.namespaceName,
      )
      else -> null
    }
    if (!validateGeneratedMemberNames(
        declaration,
        unadaptedPathProperties,
        unadaptedCasePaths,
        companionGeneratedMember,
        resolver,
      )
    ) {
      return
    }
    val pathProperties = adaptPathProperties(
        properties = unadaptedPathProperties,
        rootDeclaration = declaration,
        rootType = declaredTypeName(declaration),
        rootTypeId = CodeBlock.of("%L", rootTypeId),
        copyWritableVals = copyWritableVals,
        adapters = propertyPathAdapters,
        typeTokenNames = typeTokenNames,
      )
    val resolvedCasePaths = unadaptedCasePaths.mapNotNull { casePath ->
      resolveCaseType(declaration, typeTokenNames, casePath)?.let { resolvedCaseType ->
        val substitutions = resolvedCaseType.typeParameterSubstitutions.mapValues { (_, name) ->
          TypeVariableName(name)
        }
        val adaptedProperties = adaptPathProperties(
            properties = casePath.propertyPaths,
            rootDeclaration = casePath.declaration,
            rootType = declaredTypeName(
              declaration = casePath.declaration,
              typeParameterSubstitutions = substitutions,
            ),
            rootTypeId = CodeBlock.of("%L", resolvedCaseType.typeId),
            copyWritableVals = copyWritableVals,
            adapters = propertyPathAdapters,
            typeParameterSubstitutions = substitutions,
            typeTokenNames = resolvedCaseType.typeTokenSubstitutions,
          )
        ResolvedCasePath(casePath.copy(propertyPaths = adaptedProperties), resolvedCaseType)
      }
    }
    val casePaths = resolvedCasePaths.map(ResolvedCasePath::casePath)
    val containingFile = declaration.containingFile ?: run {
      reportGenerationError(declaration, "KPaths catalog types must originate from a source file.")
      return
    }

    val sourceFiles = (listOf(containingFile) + casePaths.mapNotNull { it.declaration.containingFile })
      .distinct()
      .toTypedArray()
    codeGenerator.createNewFile(
      dependencies = Dependencies(false, *sourceFiles),
      packageName = packageName,
      fileName = generatedName,
    ).bufferedWriter().use { writer ->
      writer.appendLine("package $packageName")
      writer.appendLine()
      writer.appendLine("/** Generated paths for $qualifiedName. */")
      val caseCatalogSupertype = if (resolvedCasePaths.isEmpty()) {
        ""
      } else {
        " : dev.kpaths.core.CasePathCatalog<$rootKotlinType>"
      }
      if (declaration.typeParameters.isEmpty()) {
        writer.appendLine("public object $generatedIdentifier$caseCatalogSupertype {")
      } else {
        val typeParameters = renderTypeParameters(declaration.typeParameters)
        val tokenParameters = declaration.typeParameters.joinToString(", ") { parameter ->
          "${typeTokenArgumentNames.getValue(parameter.name.asString())}: dev.kpaths.core.TypeId"
        }
        val constructorParameters = typeTokenNames.values.joinToString(", ") { name ->
          "private val $name: dev.kpaths.core.TypeId"
        }
        writer.appendLine("public class $generatedIdentifier<${typeParameters.declaration}> private constructor(")
        writer.appendLine("  $constructorParameters,")
        writer.appendLine(")$caseCatalogSupertype${typeParameters.whereClause} {")
        writer.appendLine("  public companion object {")
        writer.appendLine("    public fun <${typeParameters.declaration}> of($tokenParameters): $generatedIdentifier<${typeParameters.names}>${typeParameters.whereClause} =")
        writer.appendLine("      $generatedIdentifier(${declaration.typeParameters.joinToString { typeTokenArgumentNames.getValue(it.name.asString()) }})")
        val classTokenParameters = declaration.typeParameters.joinToString(", ") { parameter ->
          "${typeTokenClassArgumentName(parameter.name.asString())}: kotlin.reflect.KClass<${escapeIdentifier(parameter.name.asString())}>"
        }
        val nonNullTypeParameters = renderTypeParameters(declaration.typeParameters, forceNonNull = true)
        writer.appendLine("    public fun <${nonNullTypeParameters.declaration}> of($classTokenParameters): $generatedIdentifier<${nonNullTypeParameters.names}>${nonNullTypeParameters.whereClause} =")
        writer.appendLine("      $generatedIdentifier(${declaration.typeParameters.joinToString { "dev.kpaths.core.TypeId.from(${typeTokenClassArgumentName(it.name.asString())})" }})")
        val reifiedTypeParameters = renderTypeParameters(declaration.typeParameters, reified = true)
        writer.appendLine("    public inline fun <${reifiedTypeParameters.declaration}> of(): $generatedIdentifier<${reifiedTypeParameters.names}>${reifiedTypeParameters.whereClause} =")
        writer.appendLine("      of(${declaration.typeParameters.joinToString { "dev.kpaths.core.TypeId.of<${escapeIdentifier(it.name.asString())}>()" }})")
        writer.appendLine("  }")
      }
      writer.appendLine("  private val $generatedRootTypeName: dev.kpaths.core.TypeId = $rootTypeId")
      pathProperties.forEach { property ->
        writePropertyPath(
          writer = writer,
          rootKotlinType = rootKotlinType,
          rootTypeId = generatedRootTypeName,
          pathProperty = property,
          typeTokenNames = typeTokenNames,
          capturesTypeTokens = declaration.typeParameters.isNotEmpty(),
          declarationIndent = "  ",
        )
      }
      if (companionPaths != null) {
        writeCompanionPathsView(
          writer = writer,
          rootKotlinType = rootKotlinType,
          generatedIdentifier = generatedIdentifier,
          pathProperties = pathProperties,
          resolvedCasePaths = resolvedCasePaths,
        )
      }
      if (genericCompanionPaths?.isDirectCompanionNamespace == false) {
        writeGenericCompanionPathsView(
          writer = writer,
          generatedIdentifier = generatedIdentifier,
          typeParameters = declaration.typeParameters,
        )
      }
      resolvedCasePaths.forEach { resolvedCasePath ->
        writeCasePath(
          writer = writer,
          rootKotlinType = rootKotlinType,
          rootTypeId = generatedRootTypeName,
          rootDeclaration = declaration,
          rootTypeTokenNames = typeTokenNames,
          capturesTypeTokens = declaration.typeParameters.isNotEmpty(),
          casePath = resolvedCasePath.casePath,
          resolvedCaseType = resolvedCasePath.resolvedCaseType,
          payload = generatedCasePayload(resolvedCasePath.casePath),
        )
      }
      if (resolvedCasePaths.isNotEmpty()) {
        writeCaseCatalog(
          writer = writer,
          declaration = declaration,
          rootKotlinType = rootKotlinType,
          resolvedCasePaths = resolvedCasePaths,
        )
      }
      writer.appendLine("}")
      if (companionPaths != null) {
        writeCompanionPathsExtension(
          writer = writer,
          rootType = renderKotlinQualifiedName(qualifiedName),
          companionName = escapeIdentifier(companionPaths.companion.simpleName.asString()),
          namespaceName = companionPaths.namespaceName,
          generatedIdentifier = generatedIdentifier,
          generatedName = generatedName,
          pathProperties = pathProperties,
          rootKotlinType = rootKotlinType,
          resolvedCasePaths = resolvedCasePaths,
        )
      }
      if (declaration.typeParameters.isNotEmpty()) {
        val typeParameters = renderTypeParameters(declaration.typeParameters, reified = true)
        writer.appendLine()
        writer.appendLine("public inline fun <${typeParameters.declaration}> $generatedIdentifier(): $generatedIdentifier<${typeParameters.names}>${typeParameters.whereClause} =")
        writer.appendLine("  $generatedIdentifier.of(${declaration.typeParameters.joinToString { "dev.kpaths.core.TypeId.of<${escapeIdentifier(it.name.asString())}>()" }})")
        if (genericCompanionPaths != null) {
          writeGenericCompanionPathsFactory(
            writer = writer,
            rootType = renderKotlinQualifiedName(qualifiedName),
            companionName = escapeIdentifier(genericCompanionPaths.companion.simpleName.asString()),
            namespaceName = genericCompanionPaths.namespaceName,
            isDirectCompanionNamespace = genericCompanionPaths.isDirectCompanionNamespace,
            generatedIdentifier = generatedIdentifier,
            typeParameters = declaration.typeParameters,
          )
        }
      }
    }
  }

  @OptIn(KspExperimental::class)
  private fun validateTopLevelGeneratedName(
    declaration: KSClassDeclaration,
    resolver: Resolver,
    packageName: String,
    generatedName: String,
  ): Boolean {
    val qualifiedGeneratedName = generatedQualifiedName(packageName, generatedName)
    val name = resolver.getKSNameFromString(qualifiedGeneratedName)
    val conflicts = buildList {
      resolver.getClassDeclarationByName(name)?.let { add("type '${it.qualifiedName?.asString()}'") }
      resolver.getPropertyDeclarationByName(name, includeTopLevel = true)
        ?.takeIf { it.parentDeclaration == null }
        ?.let { add("property '${it.qualifiedName?.asString()}'") }
      resolver.getFunctionDeclarationsByName(name, includeTopLevel = true)
        .filter { it.parentDeclaration == null }
        .forEach { add("function '${it.qualifiedName?.asString()}'") }
      resolver.getDeclarationsFromPackage(packageName)
        .filterIsInstance<KSTypeAlias>()
        .filter { it.simpleName.asString() == generatedName }
        .forEach { add("type alias '${it.qualifiedName?.asString()}'") }
    }
    if (conflicts.isEmpty()) {
      return validateGeneratedFileName(declaration, resolver, packageName, generatedName)
    }
    reportGenerationError(
      declaration,
        "Cannot generate '$generatedName' because it conflicts with existing top-level " +
        conflicts.joinToString(" and ") + ". ${renameAdvice(declaration, generatedName)}",
    )
    return false
  }

  private fun validateGeneratedFileName(
    declaration: KSClassDeclaration,
    resolver: Resolver,
    packageName: String,
    generatedName: String,
  ): Boolean {
    val fileName = "$generatedName.kt"
    val conflictingFile = resolver.getAllFiles().firstOrNull { file ->
      file.packageName.asString() == packageName && file.fileName == fileName
    } ?: return true
    reportGenerationError(
      declaration,
      "Cannot generate '$fileName' because source file '${conflictingFile.filePath}' has the same " +
        "Kotlin/JVM file name. ${renameAdvice(declaration, generatedName, "source file")}",
    )
    return false
  }

  private fun generatedQualifiedName(packageName: String, generatedName: String): String =
    "$packageName.$generatedName"

  /** Returns direct sealed variants or payload-less enum entries. */
  private fun casePaths(
    declaration: KSClassDeclaration,
    copyWritableVals: Boolean,
    copyWritableValExclusionIds: Set<String>,
  ): List<CasePath> {
    if (Modifier.SEALED in declaration.modifiers) {
      return declaration.getSealedSubclasses().mapNotNull { caseDeclaration ->
        val qualifiedName = caseDeclaration.qualifiedName?.asString()
        when (
          KPathsCaseShape(
            kind = KPathsCaseKind.SEALED_VARIANT,
            isClassOrObject = caseDeclaration.classKind in setOf(ClassKind.CLASS, ClassKind.OBJECT),
            hasQualifiedName = qualifiedName != null,
          ).classifyForKPaths()
        ) {
          KPathsCasePolicyResult.Supported -> Unit
          KPathsCasePolicyResult.SealedVariantMustBeClassOrObject -> {
            logger.error(
              "[KPaths] ${KPathsDiagnostics.sealedVariantMustBeClassOrObject(caseDeclaration.simpleName.asString())}",
              caseDeclaration,
            )
            return@mapNotNull null
          }
          KPathsCasePolicyResult.SealedVariantRequiresQualifiedName -> {
            logger.error("[KPaths] ${KPathsDiagnostics.sealedVariantRequiresQualifiedName()}", caseDeclaration)
            return@mapNotNull null
          }
        }
        val sourceName = caseDeclaration.simpleName.asString()
        val generatedCaseName = configuredGeneratedMemberName(
          declaration = caseDeclaration,
          annotationName = "@CaseName",
          annotationQualifiedName = caseNameAnnotation,
          defaultName = sourceName,
        ) ?: return@mapNotNull null
        val generatedPathsName = configuredGeneratedMemberName(
          declaration = caseDeclaration,
          annotationName = "@PathsName",
          annotationQualifiedName = pathsNameAnnotation,
          defaultName = kPathsCasePropertyCatalogName(sourceName),
        ) ?: return@mapNotNull null
        val propertyPaths = pathProperties(
          declaration = caseDeclaration,
          copyWritableVals = copyWritableVals,
          copyWritableValExclusionIds = copyWritableValExclusionIds,
        )
        if (
          hasAnnotation(caseDeclaration, pathsNameAnnotation) &&
            propertyPaths.isEmpty()
        ) {
          logger.error(
            "[KPaths] @PathsName requires a sealed case with at least one accessible property path.",
            caseDeclaration,
          )
          return@mapNotNull null
        }
        CasePath(
          declaration = caseDeclaration,
          sourceName = sourceName,
          generatedCaseName = generatedCaseName,
          generatedPathsName = generatedPathsName,
          kind = CaseKind.SEALED_VARIANT,
          qualifiedName = requireNotNull(qualifiedName),
          propertyPaths = propertyPaths,
        )
      }.toList()
    }

    if (declaration.classKind == ClassKind.ENUM_CLASS) {
      return declaration.declarations
        .filterIsInstance<KSClassDeclaration>()
        .filter { it.classKind == ClassKind.ENUM_ENTRY }
        .mapNotNull { entry ->
          val sourceName = entry.simpleName.asString()
          val generatedCaseName = configuredGeneratedMemberName(
            declaration = entry,
            annotationName = "@CaseName",
            annotationQualifiedName = caseNameAnnotation,
            defaultName = sourceName,
          ) ?: return@mapNotNull null
          CasePath(
            declaration = entry,
            sourceName = sourceName,
            generatedCaseName = generatedCaseName,
            generatedPathsName = null,
            kind = CaseKind.ENUM_ENTRY,
            qualifiedName = null,
            propertyPaths = emptyList(),
          )
        }
        .toList()
    }

    return emptyList()
  }

  private fun configuredGeneratedMemberName(
    declaration: com.google.devtools.ksp.symbol.KSDeclaration,
    annotationName: String,
    annotationQualifiedName: String,
    defaultName: String,
  ): String? {
    val annotation = declaration.annotations.firstOrNull { candidate ->
      candidate.annotationType.resolve().declaration.qualifiedName?.asString() == annotationQualifiedName
    }
    if (annotation == null) return defaultName
    val configuredName = annotation.arguments
      .firstOrNull { argument -> argument.name?.asString() == "value" }
      ?.value as? String
      ?: ""
    val error = validateKPathsConfiguredName(configuredName)
    if (error != null) {
      logger.error("[KPaths] ${kPathsConfiguredNameDiagnostic(annotationName, configuredName, error)}", declaration)
      return null
    }
    return configuredName
  }

  private fun configuredPathMemberName(property: KSPropertyDeclaration): String? =
    configuredGeneratedMemberName(
      declaration = property,
      annotationName = "@PathName",
      annotationQualifiedName = pathNameAnnotation,
      defaultName = property.simpleName.asString(),
    )

  private fun hasAnnotation(declaration: KSClassDeclaration, annotationQualifiedName: String): Boolean =
    declaration.annotations.any { candidate ->
      candidate.annotationType.resolve().declaration.qualifiedName?.asString() == annotationQualifiedName
    }

  /** Applies the first matching restricted integration recipe after KPaths' normal eligibility pass. */
  private fun adaptPathProperties(
    properties: List<PathProperty>,
    rootDeclaration: KSClassDeclaration,
    rootType: TypeName,
    rootTypeId: CodeBlock,
    copyWritableVals: Boolean,
    adapters: List<KPathsPropertyPathAdapter>,
    typeParameterSubstitutions: Map<String, TypeName> = emptyMap(),
    typeTokenNames: Map<String, String> = emptyMap(),
  ): List<PathProperty> {
    val primaryConstructorPropertyNames = rootDeclaration.primaryConstructor
      ?.parameters
      ?.filter { it.isVal || it.isVar }
      ?.mapNotNull { it.name?.asString() }
      ?.toSet()
      ?: emptySet()
    val isDataClass = Modifier.DATA in rootDeclaration.modifiers

    return properties.map { property ->
      val declaration = property.declaration
      val context = KPathsPropertyPathContext(
        declaration = declaration,
        rootType = rootType,
        sourceValueType = declaration.type.resolve().toAdapterTypeName(typeParameterSubstitutions),
        isPrimaryConstructorProperty = declaration.simpleName.asString() in primaryConstructorPropertyNames,
        isDataClass = isDataClass,
        isMutable = declaration.isMutable,
        copyWritableVals = copyWritableVals,
        escapedPropertyName = escapeIdentifier(property.generatedName),
        rootTypeId = rootTypeId,
        sourcePropertyName = declaration.simpleName.asString(),
        typeIdRenderer = { type ->
          CodeBlock.of("%L", type.renderTypeId(typeTokenNames))
        },
      )
      val recipe = adapters.firstNotNullOfOrNull { adapter -> adapter.pathRecipe(context) }
      if (recipe != null) property.copy(recipe = recipe) else {
        when (val result = declarativeAdapter(declaration, rootDeclaration,
          typeParameterSubstitutions.mapValues { it.value.toString() })) {
          DeclarativeAdapterResult.Absent -> property
          is DeclarativeAdapterResult.Available -> property.copy(declarativeAdapter = result.adapter)
          is DeclarativeAdapterResult.Invalid -> {
            logger.error("[KPaths] ${kPathsAdapterDiagnostic(declaration.simpleName.asString(), result.reason)}", declaration)
            property
          }
        }
      }
    }
  }

  /** Renders a declaration with its in-scope generic arguments for the public adapter API. */
  private fun declaredTypeName(
    declaration: KSClassDeclaration,
    typeParameterSubstitutions: Map<String, TypeName> = emptyMap(),
  ): TypeName {
    val rawType = classNameFor(declaration)
    val arguments = declaration.typeParameters.map { parameter ->
      typeParameterSubstitutions[parameter.name.asString()] ?: TypeVariableName(parameter.name.asString())
    }
    return if (arguments.isEmpty()) rawType else rawType.parameterizedBy(arguments)
  }

  /** Converts KSP's resolved type to a source type without exposing KSP's type model to recipes. */
  private fun KSType.toAdapterTypeName(
    typeParameterSubstitutions: Map<String, TypeName>,
  ): TypeName {
    val declaration = declaration
    if (declaration is KSTypeParameter) {
      return (typeParameterSubstitutions[declaration.name.asString()]
        ?: TypeVariableName(declaration.name.asString()))
        .copy(nullable = isMarkedNullable)
    }
    if (declaration.qualifiedName == null) return ANY.copy(nullable = isMarkedNullable)
    val rawType = classNameFor(declaration)
    val arguments = arguments.map { argument ->
      argument.type?.resolve()?.toAdapterTypeName(typeParameterSubstitutions) ?: STAR
    }
    return (if (arguments.isEmpty()) rawType else rawType.parameterizedBy(arguments))
      .copy(nullable = isMarkedNullable)
  }

  /** Builds a KotlinPoet class name from KSP declarations without guessing from capitalization. */
  private fun classNameFor(declaration: KSDeclaration): ClassName {
    val enclosingNames = generateSequence(declaration) { current ->
      current.parentDeclaration as? KSClassDeclaration
    }
      .map { current -> current.simpleName.asString() }
      .toList()
      .asReversed()
    return ClassName(
      declaration.packageName.asString(),
      enclosingNames.first(),
      *enclosingNames.drop(1).toTypedArray(),
    )
  }

  /** Classifies Kotlin properties into the library's explicit value-style and reference-style APIs. */
  private fun pathProperties(
    declaration: KSClassDeclaration,
    copyWritableVals: Boolean,
    copyWritableValExclusionIds: Set<String>,
  ): List<PathProperty> {
    if (declaration.classKind !in setOf(ClassKind.CLASS, ClassKind.ENUM_CLASS)) {
      return emptyList()
    }

    val accessibleProperties = declaration.getDeclaredProperties()
      .filter { it.origin == Origin.KOTLIN }
      .filter(::isAccessible)
      .toList()

    if (Modifier.DATA !in declaration.modifiers) {
      return accessibleProperties.mapNotNull { property ->
        val generatedName = configuredPathMemberName(property) ?: return@mapNotNull null
        val result = KPathsPropertyShape(
          ownerIsDataClass = false,
          isMutable = property.isMutable,
          isPrimaryConstructorProperty = false,
          hasAccessibleSetter = isSetterAccessible(property),
          copyWritableVals = copyWritableVals,
          isExcludedFromCopyWritableVals = false,
        ).classifyForKPaths() as KPathsPropertyPolicyResult.Supported
        PathProperty(
          declaration = property,
          generatedName = generatedName,
          kind = result.kind,
        )
      }
    }

    val constructorPropertyNames = declaration.primaryConstructor
      ?.parameters
      ?.filter { it.isVal || it.isVar }
      ?.mapNotNull { it.name?.asString() }
      ?.toSet()
      ?: emptySet()

    return accessibleProperties.mapNotNull { property ->
      val generatedName = configuredPathMemberName(property) ?: return@mapNotNull null
      when (val result = KPathsPropertyShape(
        ownerIsDataClass = true,
        isMutable = property.isMutable,
        isPrimaryConstructorProperty = property.simpleName.asString() in constructorPropertyNames,
        hasAccessibleSetter = isSetterAccessible(property),
        copyWritableVals = copyWritableVals,
        isExcludedFromCopyWritableVals = copyWritableValExclusionId(property) in copyWritableValExclusionIds,
      ).classifyForKPaths()) {
        is KPathsPropertyPolicyResult.Supported -> PathProperty(property, generatedName, result.kind)
        KPathsPropertyPolicyResult.InaccessibleDataClassSetter -> {
          logger.error(
            "[KPaths] ${KPathsDiagnostics.inaccessibleDataClassSetter(property.simpleName.asString())}",
            property,
          )
          null
        }
      }
    }
  }

  /** Returns a stable source-level identity for an externally selected property. */
  private fun copyWritableValExclusionId(property: KSPropertyDeclaration): String? {
    val owner = (property.parentDeclaration as? KSClassDeclaration)
      ?.qualifiedName
      ?.asString()
      ?: return null
    return "$owner.${property.simpleName.asString()}"
  }

  /** Returns the constructor properties that can form a lossless generated case payload path. */
  private fun generatedCasePayload(casePath: CasePath): CasePayload? {
    if (casePath.kind != CaseKind.SEALED_VARIANT) return null
    val constructor = casePath.declaration.primaryConstructor ?: return null
    val parameters = constructor.parameters
    val propertiesByName = casePath.propertyPaths.associateBy { property ->
      property.declaration.simpleName.asString()
    }
    val properties = parameters.mapNotNull { parameter ->
      parameter.name?.asString()?.let(propertiesByName::get)
    }
    val decision = KPathsCasePayloadShape(
      isDataClass = Modifier.DATA in casePath.declaration.modifiers,
      hasPrimaryConstructor = true,
      isConstructorAccessible = Modifier.PRIVATE !in constructor.modifiers && Modifier.PROTECTED !in constructor.modifiers,
      propertyNames = parameters.map { it.name?.asString().orEmpty() },
      allParametersAreAccessibleProperties = parameters.all { it.isVal || it.isVar } && properties.size == parameters.size,
    ).classifyForKPaths()
    if (decision !is KPathsCasePayloadDecision.Available) return null
    return CasePayload(casePath, properties)
  }

  private fun isAccessible(property: KSPropertyDeclaration): Boolean =
    Modifier.PRIVATE !in property.modifiers && Modifier.PROTECTED !in property.modifiers

  private fun isSetterAccessible(property: KSPropertyDeclaration): Boolean =
    property.setter?.let(::isAccessible) ?: false

  private fun isAccessible(setter: com.google.devtools.ksp.symbol.KSPropertySetter): Boolean =
    Modifier.PRIVATE !in setter.modifiers && Modifier.PROTECTED !in setter.modifiers

  /** Only a concrete class can bind a generated companion view to one precise root type. */
  private fun companionPathsExtension(
    declaration: KSClassDeclaration,
    pathProperties: List<PathProperty>,
    casePaths: List<CasePath>,
  ): CompanionPaths? {
    if (
      declaration.typeParameters.isNotEmpty() ||
        (pathProperties.isEmpty() && casePaths.isEmpty())
    ) {
      return null
    }
    val companion = visibleCompanion(declaration) ?: return null
    val namespaceName = configuredGeneratedMemberName(
      declaration = companion,
      annotationName = "@PathsName",
      annotationQualifiedName = pathsNameAnnotation,
      defaultName = "Paths",
    ) ?: return null
    if (companion.simpleName.asString() == namespaceName) {
      logger.error(
        "[KPaths] Cannot generate ${declaration.simpleName.asString()}.$namespaceName because its " +
          "companion object is also named '$namespaceName'. Use @PathsName(\"KPaths\") on the " +
          "companion object to generate ${declaration.simpleName.asString()}.$namespaceName.KPaths instead.",
        companion,
      )
      return null
    }
    return CompanionPaths(companion, namespaceName)
  }

  /** A generic companion function can retain the catalog's reified type arguments. */
  private fun genericCompanionPathsFactory(
    declaration: KSClassDeclaration,
    pathProperties: List<PathProperty>,
    casePaths: List<CasePath>,
  ): GenericCompanionPaths? {
    if (declaration.typeParameters.isEmpty() || (pathProperties.isEmpty() && casePaths.isEmpty())) {
      return null
    }
    val companion = visibleCompanion(declaration) ?: return null
    if (companion.simpleName.asString() == "Companion") {
      logger.warn(
        "[KPaths] Generic path namespaces require an explicitly named companion object. Prefer " +
          "`companion object Paths`, or use ${declaration.simpleName.asString()}Paths.of<…>() directly.",
        declaration,
      )
      return null
    }
    val namespaceName = configuredGeneratedMemberName(
      declaration = companion,
      annotationName = "@PathsName",
      annotationQualifiedName = pathsNameAnnotation,
      defaultName = "Paths",
    ) ?: return null
    val isDirectCompanionNamespace = companion.simpleName.asString() == "Paths"
    if (isDirectCompanionNamespace && namespaceName != "Paths") {
      logger.error(
        "[KPaths] @PathsName cannot rename a generic companion object named 'Paths'. Remove the " +
          "annotation or use @PathsName(\"Paths\").",
        companion,
      )
      return null
    }
    return GenericCompanionPaths(companion, namespaceName, isDirectCompanionNamespace)
  }

  private fun visibleCompanion(declaration: KSClassDeclaration): KSClassDeclaration? =
    declaration.declarations
      .filterIsInstance<KSClassDeclaration>()
      .firstOrNull(KSClassDeclaration::isCompanionObject)
      ?.takeIf { companion ->
        Modifier.PRIVATE !in companion.modifiers &&
          Modifier.PROTECTED !in companion.modifiers &&
          Modifier.INTERNAL !in companion.modifiers
      }

  /**
   * Kotlin can parse `companion object` followed by a soft keyword on the next line as a named
   * companion (for example, `data` from a following `data class`). Requiring an unbraced,
   * unnamed companion to be last avoids that surprising interpretation while preserving concise
   * `companion object` syntax.
   */
  private fun validateUnnamedCompanionPlacement(declaration: KSClassDeclaration): Boolean {
    val companion = declaration.declarations
      .filterIsInstance<KSClassDeclaration>()
      .firstOrNull(KSClassDeclaration::isCompanionObject)
      ?: return true
    val location = companion.location as? FileLocation ?: return true
    val source = runCatching { Files.readString(Path.of(location.filePath)) }.getOrNull() ?: return true
    if (!hasAmbiguousKPathsCompanion(source, location.lineNumber)) return true

    reportGenerationError(
      declaration,
      KPathsDiagnostics.ambiguousCompanion(),
    )
    return false
  }

  private fun writeCompanionPathsView(
    writer: java.io.BufferedWriter,
    rootKotlinType: String,
    generatedIdentifier: String,
    pathProperties: List<PathProperty>,
    resolvedCasePaths: List<ResolvedCasePath>,
  ) {
    val viewName = escapeIdentifier(generatedInternalPrefix + "CompanionView")
    writer.appendLine()
    writer.appendLine("  /** Instance-facing view used by the generated companion [Paths] extension. */")
    writer.appendLine("  public interface $viewName {")
    pathProperties.forEach { pathProperty ->
      val property = pathProperty.declaration
      val identifier = escapeIdentifier(pathProperty.generatedName)
      val valueType = property.type.resolve().renderKotlinType()
      writer.appendLine("    public val $identifier: ${pathPublicType(pathProperty, rootKotlinType, valueType)}")
    }
    resolvedCasePaths.forEach { resolvedCasePath ->
      val casePath = resolvedCasePath.casePath
      val caseIdentifier = escapeIdentifier(casePath.generatedCaseName)
      writer.appendLine("    public val $caseIdentifier: $generatedIdentifier.$caseIdentifier")
      if (casePath.kind == CaseKind.SEALED_VARIANT && casePath.propertyPaths.isNotEmpty()) {
        val namespaceIdentifier = escapeIdentifier(requireNotNull(casePath.generatedPathsName))
        writer.appendLine("    public val $namespaceIdentifier: ${casePropertyPathsViewIdentifier(casePath)}")
      }
    }
    if (resolvedCasePaths.isNotEmpty()) {
      writer.appendLine("    public val allCasePaths: kotlin.collections.List<dev.kpaths.core.PartialCaseKeyPath<$rootKotlinType>>")
      writer.appendLine("    public fun caseOf(root: $rootKotlinType): dev.kpaths.core.PartialCaseKeyPath<$rootKotlinType>")
    }
    writer.appendLine("  }")
    resolvedCasePaths
      .filter { resolvedCasePath ->
        resolvedCasePath.casePath.kind == CaseKind.SEALED_VARIANT &&
          resolvedCasePath.casePath.propertyPaths.isNotEmpty()
      }
      .forEach { resolvedCasePath ->
        writeCasePropertyPathsCompanionView(
          writer = writer,
          rootKotlinType = resolvedCasePath.resolvedCaseType.kotlinType,
          casePath = resolvedCasePath.casePath,
        )
      }
  }

  private fun writeCompanionPathsExtension(
    writer: java.io.BufferedWriter,
    rootType: String,
    companionName: String,
    namespaceName: String,
    generatedIdentifier: String,
    generatedName: String,
    pathProperties: List<PathProperty>,
    rootKotlinType: String,
    resolvedCasePaths: List<ResolvedCasePath>,
  ) {
    val viewName = escapeIdentifier(generatedInternalPrefix + "CompanionView")
    val viewType = "$generatedIdentifier.$viewName"
    val backingName = escapeIdentifier(generatedInternalPrefix + generatedName + "CompanionView")
    writer.appendLine()
    resolvedCasePaths
      .filter { resolvedCasePath ->
        resolvedCasePath.casePath.kind == CaseKind.SEALED_VARIANT &&
          resolvedCasePath.casePath.propertyPaths.isNotEmpty()
      }
      .forEach { resolvedCasePath ->
        writeCasePropertyPathsCompanionViewBacking(
          writer = writer,
          generatedIdentifier = generatedIdentifier,
          generatedName = generatedName,
          casePath = resolvedCasePath.casePath,
          resolvedCaseType = resolvedCasePath.resolvedCaseType,
        )
        writer.appendLine()
      }
    writer.appendLine("private val $backingName: $viewType = object : $viewType {")
    pathProperties.forEach { pathProperty ->
      val property = pathProperty.declaration
      val identifier = escapeIdentifier(pathProperty.generatedName)
      val valueType = property.type.resolve().renderKotlinType()
      writer.appendLine("  override val $identifier: ${pathPublicType(pathProperty, rootType, valueType)}")
      writer.appendLine("    get() = $generatedIdentifier.$identifier")
    }
    resolvedCasePaths.forEach { resolvedCasePath ->
      val casePath = resolvedCasePath.casePath
      val caseIdentifier = escapeIdentifier(casePath.generatedCaseName)
      writer.appendLine("  override val $caseIdentifier: $generatedIdentifier.$caseIdentifier")
      writer.appendLine("    get() = $generatedIdentifier.$caseIdentifier")
      if (casePath.kind == CaseKind.SEALED_VARIANT && casePath.propertyPaths.isNotEmpty()) {
        val namespaceIdentifier = escapeIdentifier(requireNotNull(casePath.generatedPathsName))
        writer.appendLine("  override val $namespaceIdentifier: $generatedIdentifier.${casePropertyPathsViewIdentifier(casePath)}")
        writer.appendLine("    get() = ${casePropertyPathsViewBackingIdentifier(generatedName, casePath)}")
      }
    }
    if (resolvedCasePaths.isNotEmpty()) {
      writer.appendLine("  override val allCasePaths: kotlin.collections.List<dev.kpaths.core.PartialCaseKeyPath<$rootKotlinType>>")
      writer.appendLine("    get() = $generatedIdentifier.allCasePaths")
      writer.appendLine("  override fun caseOf(root: $rootKotlinType): dev.kpaths.core.PartialCaseKeyPath<$rootKotlinType> =")
      writer.appendLine("    $generatedIdentifier.caseOf(root)")
    }
    writer.appendLine("}")
    writer.appendLine()
    writer.appendLine("/** Returns the generated paths for $rootType. */")
    writer.appendLine("public val $rootType.$companionName.${escapeIdentifier(namespaceName)}: $viewType")
    writer.appendLine("  get() = $backingName")
  }

  private fun writeCasePropertyPathsCompanionView(
    writer: java.io.BufferedWriter,
    rootKotlinType: String,
    casePath: CasePath,
  ) {
    writer.appendLine()
    writer.appendLine("  /** Instance-facing view for a generated sealed-case property catalog. */")
    writer.appendLine("  public interface ${casePropertyPathsViewIdentifier(casePath)} {")
    casePath.propertyPaths.forEach { pathProperty ->
      val identifier = escapeIdentifier(pathProperty.generatedName)
      val valueType = pathProperty.declaration.type.resolve().renderKotlinType()
      writer.appendLine("    public val $identifier: ${pathPublicType(pathProperty, rootKotlinType, valueType)}")
    }
    writer.appendLine("  }")
  }

  private fun writeCasePropertyPathsCompanionViewBacking(
    writer: java.io.BufferedWriter,
    generatedIdentifier: String,
    generatedName: String,
    casePath: CasePath,
    resolvedCaseType: ResolvedCaseType,
  ) {
    val namespaceIdentifier = escapeIdentifier(requireNotNull(casePath.generatedPathsName))
    val viewIdentifier = casePropertyPathsViewIdentifier(casePath)
    val backingIdentifier = casePropertyPathsViewBackingIdentifier(generatedName, casePath)
    writer.appendLine("private val $backingIdentifier: $generatedIdentifier.$viewIdentifier = object : $generatedIdentifier.$viewIdentifier {")
    casePath.propertyPaths.forEach { pathProperty ->
      val identifier = escapeIdentifier(pathProperty.generatedName)
      val valueType = pathProperty.declaration.type.resolve().renderKotlinType(resolvedCaseType.typeParameterSubstitutions)
      writer.appendLine("  override val $identifier: ${pathPublicType(pathProperty, resolvedCaseType.kotlinType, valueType)}")
      writer.appendLine("    get() = $generatedIdentifier.$namespaceIdentifier.$identifier")
    }
    writer.appendLine("}")
  }

  private fun casePropertyPathsViewIdentifier(casePath: CasePath): String =
    escapeIdentifier(generatedInternalPrefix + requireNotNull(casePath.generatedPathsName) + "View")

  private fun casePropertyPathsViewBackingIdentifier(
    generatedName: String,
    casePath: CasePath,
  ): String = escapeIdentifier(
    generatedInternalPrefix + generatedName + requireNotNull(casePath.generatedPathsName) + "View",
  )

  private fun writeGenericCompanionPathsFactory(
    writer: java.io.BufferedWriter,
    rootType: String,
    companionName: String,
    namespaceName: String,
    isDirectCompanionNamespace: Boolean,
    generatedIdentifier: String,
    typeParameters: List<KSTypeParameter>,
  ) {
    val renderedTypeParameters = renderTypeParameters(typeParameters, reified = true)
    writer.appendLine()
    writer.appendLine("/** Creates the generated paths for $rootType with concrete type metadata. */")
    val nonNullTypeParameters = renderTypeParameters(typeParameters, forceNonNull = true)
    val classTokenParameters = typeParameters.joinToString(", ") { parameter ->
      "${typeTokenClassArgumentName(parameter.name.asString())}: kotlin.reflect.KClass<${escapeIdentifier(parameter.name.asString())}>"
    }
    if (isDirectCompanionNamespace) {
      writer.appendLine("public inline fun <${renderedTypeParameters.declaration}> $rootType.$companionName.of(): $generatedIdentifier<${renderedTypeParameters.names}>${renderedTypeParameters.whereClause} =")
      writer.appendLine("  $generatedIdentifier.of<${renderedTypeParameters.names}>()")
      writer.appendLine("public fun <${nonNullTypeParameters.declaration}> $rootType.$companionName.of($classTokenParameters): $generatedIdentifier<${nonNullTypeParameters.names}>${nonNullTypeParameters.whereClause} =")
      writer.appendLine("  $generatedIdentifier.of(${typeParameters.joinToString { typeTokenClassArgumentName(it.name.asString()) }})")
    } else {
      val namespaceIdentifier = escapeIdentifier(namespaceName)
      val viewIdentifier = escapeIdentifier(generatedInternalPrefix + "GenericCompanionPaths")
      writer.appendLine("public val $rootType.$companionName.$namespaceIdentifier: $generatedIdentifier.$viewIdentifier")
      writer.appendLine("  get() = $generatedIdentifier.$viewIdentifier")
      writer.appendLine("public inline fun <${renderedTypeParameters.declaration}> $rootType.$companionName.${escapeIdentifier(namespaceName)}(): $generatedIdentifier<${renderedTypeParameters.names}>${renderedTypeParameters.whereClause} =")
      writer.appendLine("  $generatedIdentifier.of<${renderedTypeParameters.names}>()")
    }
  }

  private fun writeGenericCompanionPathsView(
    writer: java.io.BufferedWriter,
    generatedIdentifier: String,
    typeParameters: List<KSTypeParameter>,
  ) {
    val viewIdentifier = escapeIdentifier(generatedInternalPrefix + "GenericCompanionPaths")
    val reifiedTypeParameters = renderTypeParameters(typeParameters, reified = true)
    val nonNullTypeParameters = renderTypeParameters(typeParameters, forceNonNull = true)
    val classTokenParameters = typeParameters.joinToString(", ") { parameter ->
      "${typeTokenClassArgumentName(parameter.name.asString())}: kotlin.reflect.KClass<${escapeIdentifier(parameter.name.asString())}>"
    }
    writer.appendLine()
    writer.appendLine("  public object $viewIdentifier {")
    writer.appendLine("    public inline fun <${reifiedTypeParameters.declaration}> of(): $generatedIdentifier<${reifiedTypeParameters.names}>${reifiedTypeParameters.whereClause} =")
    writer.appendLine("      $generatedIdentifier.of<${reifiedTypeParameters.names}>()")
    writer.appendLine("    public fun <${nonNullTypeParameters.declaration}> of($classTokenParameters): $generatedIdentifier<${nonNullTypeParameters.names}>${nonNullTypeParameters.whereClause} =")
    writer.appendLine("      $generatedIdentifier.of(${typeParameters.joinToString { typeTokenClassArgumentName(it.name.asString()) }})")
    writer.appendLine("  }")
  }

  /** Rejects ambiguous public members instead of silently changing the generated API. */
  @OptIn(KspExperimental::class)
  private fun validateGeneratedMemberNames(
    declaration: KSClassDeclaration,
    pathProperties: List<PathProperty>,
    casePaths: List<CasePath>,
    companionGeneratedMember: CompanionGeneratedMember?,
    resolver: Resolver,
  ): Boolean {
    val members = mutableListOf<GeneratedMember>()
    if (declaration.typeParameters.isNotEmpty()) {
      members += GeneratedMember(
        name = "Companion",
        description = "the generated generic-path companion",
      )
    }

    pathProperties.forEach { property ->
      members += GeneratedMember(
        name = property.generatedName,
        description = "the key path for property '${property.declaration.simpleName.asString()}'",
      )
    }
    casePaths.forEach { casePath ->
      members += GeneratedMember(
        name = casePath.generatedCaseName,
        description = "the case path for '${casePath.sourceName}'",
      )
      if (casePath.kind == CaseKind.SEALED_VARIANT && casePath.propertyPaths.isNotEmpty()) {
        members += GeneratedMember(
          name = requireNotNull(casePath.generatedPathsName),
          description = "the property-path namespace for case '${casePath.sourceName}'",
        )
        if (declaration.typeParameters.isNotEmpty()) {
          members += GeneratedMember(
            name = casePath.sourceName + "PropertyPaths",
            description = "the generated property-path type for case '${casePath.sourceName}'",
          )
        }
      }
      if (declaration.typeParameters.isNotEmpty() && generatedCasePayload(casePath) != null) {
        members += GeneratedMember(
          name = casePath.generatedCaseName + "CasePath",
          description = "the generated payload-bearing case-path type for case '${casePath.sourceName}'",
        )
      }
    }
    if (casePaths.isNotEmpty()) {
      members += GeneratedMember(
        name = "allCasePaths",
        description = "the generated catalog of all case paths",
      )
      members += GeneratedMember(
        name = "caseOf",
        description = "the generated case-path lookup function",
      )
    }

    if (companionGeneratedMember != null) {
      val companion = companionGeneratedMember.companion
      val memberName = companionGeneratedMember.name
      val renderedTarget = "${declaration.simpleName.asString()}.${companion.simpleName.asString()}.$memberName"
      if (companion.declarations.any { it.simpleName.asString() == memberName }) {
        reportGenerationError(
          declaration,
          "Cannot generate $renderedTarget because the companion already declares a member named " +
            "'$memberName'. Rename that member or choose a different generated facade name.",
        )
        return false
      }
      val existingExtension = resolver.getDeclarationsFromPackage(declaration.packageName.asString())
        .filter { candidate ->
          val receiver = when (candidate) {
            is KSPropertyDeclaration -> candidate.extensionReceiver
            is KSFunctionDeclaration -> candidate.extensionReceiver
            else -> null
          }
          candidate.parentDeclaration == null &&
            candidate.simpleName.asString() == memberName &&
            (receiver?.resolve()?.declaration as? KSClassDeclaration)
              ?.let { receiver ->
                receiver.isCompanionObject && receiver.parentDeclaration == declaration
              } == true
        }
        .firstOrNull()
      if (existingExtension != null) {
        reportGenerationError(
          declaration,
          "Cannot generate $renderedTarget because the top-level " +
            "extension '${existingExtension.qualifiedName?.asString()}' already declares it. Rename that " +
            "extension or choose a different generated facade name.",
        )
        return false
      }
    }

    var isValid = true
    (pathProperties.asSequence() + casePaths.asSequence().flatMap { it.propertyPaths.asSequence() })
      .map { it.declaration }
      .filter { isKPathsReservedName(it.simpleName.asString()) }
      .forEach { property ->
        reportGenerationError(
          declaration,
          KPathsDiagnostics.reservedSourceName("property", property.simpleName.asString()),
        )
        isValid = false
      }
    casePaths
      .filter { isKPathsReservedName(it.sourceName) }
      .forEach { casePath ->
        reportGenerationError(
          declaration,
          KPathsDiagnostics.reservedSourceName("case", casePath.sourceName),
        )
        isValid = false
      }

    findKPathsGeneratedMemberCollisions(members)
      .forEach { collision ->
        reportGenerationError(
          declaration,
          KPathsDiagnostics.generatedMemberCollision(collision),
        )
        isValid = false
      }
    return isValid
  }

  private fun writePropertyPath(
    writer: java.io.BufferedWriter,
    rootKotlinType: String,
    rootTypeId: String,
    pathProperty: PathProperty,
    typeTokenNames: Map<String, String>,
    typeParameterSubstitutions: Map<String, String> = emptyMap(),
    capturesTypeTokens: Boolean,
    declarationIndent: String,
  ) {
    val property = pathProperty.declaration
    val name = property.simpleName.asString()
    val identifier = escapeIdentifier(pathProperty.generatedName)
    val sourceIdentifier = escapeIdentifier(name)
    val valueKotlinType = property.type.resolve().renderKotlinType(typeParameterSubstitutions)
    val valueTypeId = property.type.resolve().renderTypeId(typeTokenNames)
    pathProperty.recipe?.let { recipe ->
      val constructorArguments = recipe.constructorArguments.joinToString(", ")
      writer.appendLine()
      writer.appendLine(
        "${declarationIndent}public val $identifier: ${recipe.publicPathType} =",
      )
      writer.appendLine(
        "${declarationIndent}  object : ${recipe.implementationBaseType}($constructorArguments) {}",
      )
      return
    }
    val pathInterface = pathInterface(pathProperty.kind)
    val pathBase = when (pathProperty.kind) {
      PathKind.READ_ONLY -> "dev.kpaths.core.KeyPathBase"
      PathKind.COPY_WRITABLE -> "dev.kpaths.core.WritableKeyPathBase"
      PathKind.COPY_THEN_MUTATE -> "dev.kpaths.core.WritableKeyPathBase"
      PathKind.REFERENCE_WRITABLE -> "dev.kpaths.core.ReferenceWritableKeyPathBase"
    }
    writer.appendLine()
    val declarative = pathProperty.declarativeAdapter
    if (declarative != null) {
      val factory = declarative.factoryName.split('.').joinToString(".", transform = ::escapeIdentifier)
      writer.appendLine("${declarationIndent}public val $identifier: ${declarative.publicType} =")
      writer.appendLine("${declarationIndent}  $factory.make(object : $pathBase<$rootKotlinType, $valueKotlinType>() {")
      writer.appendLine("${declarationIndent}    override val __kpaths_id: dev.kpaths.core.PathId = dev.kpaths.core.PathId.property($rootTypeId, \"$name\")")
      writer.appendLine("${declarationIndent}    override val __kpaths_valueType: dev.kpaths.core.TypeId = $valueTypeId")
      writer.appendLine("${declarationIndent}    override fun get(root: $rootKotlinType): $valueKotlinType = root.$sourceIdentifier")
      writeSetter(writer, pathProperty.kind, rootKotlinType, valueKotlinType, sourceIdentifier, indent = "${declarationIndent}    ")
      writer.appendLine("${declarationIndent}  })")
    } else if (capturesTypeTokens) {
      writer.appendLine("${declarationIndent}public val $identifier: $pathInterface<$rootKotlinType, $valueKotlinType> =")
      writer.appendLine("${declarationIndent}  object : $pathBase<$rootKotlinType, $valueKotlinType>() {")
      writer.appendLine("${declarationIndent}    override val __kpaths_id: dev.kpaths.core.PathId = dev.kpaths.core.PathId.property($rootTypeId, \"$name\")")
      writer.appendLine("${declarationIndent}    override val __kpaths_valueType: dev.kpaths.core.TypeId = $valueTypeId")
      writer.appendLine("${declarationIndent}    override fun get(root: $rootKotlinType): $valueKotlinType = root.$sourceIdentifier")
      writeSetter(writer, pathProperty.kind, rootKotlinType, valueKotlinType, sourceIdentifier, indent = "${declarationIndent}    ")
      writer.appendLine("${declarationIndent}  }")
    } else {
      writer.appendLine("${declarationIndent}public object $identifier : $pathBase<$rootKotlinType, $valueKotlinType>() {")
      writer.appendLine("${declarationIndent}  override val __kpaths_id: dev.kpaths.core.PathId = dev.kpaths.core.PathId.property($rootTypeId, \"$name\")")
      writer.appendLine("${declarationIndent}  override val __kpaths_valueType: dev.kpaths.core.TypeId = $valueTypeId")
      writer.appendLine("${declarationIndent}  override fun get(root: $rootKotlinType): $valueKotlinType = root.$sourceIdentifier")
      writeSetter(writer, pathProperty.kind, rootKotlinType, valueKotlinType, sourceIdentifier, indent = "${declarationIndent}  ")
      writer.appendLine("${declarationIndent}}")
    }
  }

  private fun pathInterface(kind: PathKind): String = when (kind) {
    PathKind.READ_ONLY -> "dev.kpaths.core.KeyPath"
    PathKind.COPY_WRITABLE, PathKind.COPY_THEN_MUTATE -> "dev.kpaths.core.WritableKeyPath"
    PathKind.REFERENCE_WRITABLE -> "dev.kpaths.core.ReferenceWritableKeyPath"
  }

  private fun pathPublicType(
    property: PathProperty,
    rootKotlinType: String,
    valueKotlinType: String,
  ): String = property.recipe?.publicPathType?.toString() ?: property.declarativeAdapter?.publicType
    ?: "${pathInterface(property.kind)}<$rootKotlinType, $valueKotlinType>"

  private fun writeCasePath(
    writer: java.io.BufferedWriter,
    rootKotlinType: String,
    rootTypeId: String,
    rootDeclaration: KSClassDeclaration,
    rootTypeTokenNames: Map<String, String>,
    capturesTypeTokens: Boolean,
    casePath: CasePath,
    resolvedCaseType: ResolvedCaseType,
    payload: CasePayload?,
  ) {
    val caseIdentifier = escapeIdentifier(casePath.generatedCaseName)

    writer.appendLine()
    if (capturesTypeTokens) {
      if (payload == null) {
        if (casePath.kind == CaseKind.SEALED_VARIANT && casePath.declaration.classKind == ClassKind.OBJECT) {
          writeGenericCasePathWithUnitPayload(
            writer = writer,
            rootKotlinType = rootKotlinType,
            rootTypeId = rootTypeId,
            casePath = casePath,
            resolvedCaseType = resolvedCaseType,
          )
        } else {
          writer.appendLine("  public val $caseIdentifier: dev.kpaths.core.CaseKeyPath<$rootKotlinType, ${resolvedCaseType.kotlinType}> =")
          writer.appendLine("    object : dev.kpaths.core.CaseKeyPathBase<$rootKotlinType, ${resolvedCaseType.kotlinType}>() {")
          writeCasePathBody(
            writer = writer,
            indent = "      ",
            rootKotlinType = rootKotlinType,
            rootTypeId = rootTypeId,
            casePath = casePath,
            resolvedCaseType = resolvedCaseType,
          )
          writer.appendLine("    }")
        }
      } else {
        writeGenericCasePathWithPayload(
          writer = writer,
          rootKotlinType = rootKotlinType,
          rootTypeId = rootTypeId,
          casePath = casePath,
          resolvedCaseType = resolvedCaseType,
          payload = payload,
        )
      }
    } else {
      writer.appendLine("  public object $caseIdentifier : dev.kpaths.core.CaseKeyPathBase<$rootKotlinType, ${resolvedCaseType.kotlinType}>() {")
      writeCasePathBody(
        writer = writer,
        indent = "    ",
        rootKotlinType = rootKotlinType,
        rootTypeId = rootTypeId,
        casePath = casePath,
        resolvedCaseType = resolvedCaseType,
      )
      payload?.let {
        writeCasePayloadPath(
          writer = writer,
          indent = "    ",
          rootKotlinType = rootKotlinType,
          rootTypeId = rootTypeId,
          casePath = casePath,
          resolvedCaseType = resolvedCaseType,
          payload = it,
        )
      } ?: run {
        if (casePath.kind == CaseKind.SEALED_VARIANT && casePath.declaration.classKind == ClassKind.OBJECT) {
          writeCaseUnitPayloadPath(
            writer = writer,
            indent = "    ",
            rootKotlinType = rootKotlinType,
            rootTypeId = rootTypeId,
            casePath = casePath,
            resolvedCaseType = resolvedCaseType,
          )
        }
      }
      writer.appendLine("  }")
    }

    if (casePath.kind != CaseKind.SEALED_VARIANT) {
      return
    }

    val caseProperties = casePath.propertyPaths
    if (caseProperties.isEmpty()) {
      return
    }

    writer.appendLine()
    if (capturesTypeTokens) {
      writeGenericCasePropertyPaths(
        writer = writer,
        rootDeclaration = rootDeclaration,
        rootTypeTokenNames = rootTypeTokenNames,
        casePath = casePath,
        resolvedCaseType = resolvedCaseType,
        caseProperties = caseProperties,
      )
    } else {
      writer.appendLine("  public object ${escapeIdentifier(requireNotNull(casePath.generatedPathsName))} {")
      caseProperties.forEach { property ->
        writePropertyPath(
          writer = writer,
          rootKotlinType = resolvedCaseType.kotlinType,
          rootTypeId = resolvedCaseType.typeId,
          pathProperty = property,
          typeTokenNames = resolvedCaseType.typeTokenSubstitutions,
          typeParameterSubstitutions = resolvedCaseType.typeParameterSubstitutions,
          capturesTypeTokens = false,
          declarationIndent = "    ",
        )
      }
      writer.appendLine("  }")
    }
  }

  /** Emits the `Unit` payload carried by a Kotlin object case. */
  private fun writeCaseUnitPayloadPath(
    writer: java.io.BufferedWriter,
    indent: String,
    rootKotlinType: String,
    rootTypeId: String,
    casePath: CasePath,
    resolvedCaseType: ResolvedCaseType,
  ) {
    writer.appendLine()
    writer.appendLine("${indent}public val unit: dev.kpaths.core.CaseKeyPath<$rootKotlinType, kotlin.Unit> =")
    writer.appendLine("${indent}  object : dev.kpaths.core.CaseKeyPathBase<$rootKotlinType, kotlin.Unit>() {")
    writer.appendLine("${indent}    override val __kpaths_id: dev.kpaths.core.PathId = dev.kpaths.core.PathId.case($rootTypeId, \"${casePath.sourceName}\")")
    writer.appendLine("${indent}    override val __kpaths_valueType: dev.kpaths.core.TypeId = dev.kpaths.core.TypeId.of<kotlin.Unit>()")
    writer.appendLine("${indent}    override fun match(root: $rootKotlinType): dev.kpaths.core.CaseMatch<kotlin.Unit> = if (root is ${resolvedCaseType.kotlinType}) dev.kpaths.core.CaseMatch.Matched(kotlin.Unit) else dev.kpaths.core.CaseMatch.NoMatch")
    writer.appendLine("${indent}    override fun embed(value: kotlin.Unit): $rootKotlinType = ${resolvedCaseType.kotlinType}")
    writer.appendLine("${indent}  }")
  }

  /**
   * A generic generated namespace cannot use a nested object for a payload path: its root type
   * tokens live on the enclosing namespace instance. An `inner` case-path class captures that
   * instance while preserving the same `Paths.Loaded.value` call site as a static catalog.
   */
  private fun writeGenericCasePathWithPayload(
    writer: java.io.BufferedWriter,
    rootKotlinType: String,
    rootTypeId: String,
    casePath: CasePath,
    resolvedCaseType: ResolvedCaseType,
    payload: CasePayload,
  ) {
    val caseIdentifier = escapeIdentifier(casePath.generatedCaseName)
    val casePathClassIdentifier = escapeIdentifier(casePath.generatedCaseName + "CasePath")

    writer.appendLine("  public val $caseIdentifier: $casePathClassIdentifier = $casePathClassIdentifier()")
    writer.appendLine()
    writer.appendLine("  public inner class $casePathClassIdentifier internal constructor() : dev.kpaths.core.CaseKeyPathBase<$rootKotlinType, ${resolvedCaseType.kotlinType}>() {")
    writeCasePathBody(
      writer = writer,
      indent = "    ",
      rootKotlinType = rootKotlinType,
      rootTypeId = rootTypeId,
      casePath = casePath,
      resolvedCaseType = resolvedCaseType,
    )
    writeCasePayloadPath(
      writer = writer,
      indent = "    ",
      rootKotlinType = rootKotlinType,
      rootTypeId = rootTypeId,
      casePath = casePath,
      resolvedCaseType = resolvedCaseType,
      payload = payload,
      capturesTypeTokens = true,
    )
    writer.appendLine("  }")
  }

  /** Emits a generic object case with its Unit associated-value path. */
  private fun writeGenericCasePathWithUnitPayload(
    writer: java.io.BufferedWriter,
    rootKotlinType: String,
    rootTypeId: String,
    casePath: CasePath,
    resolvedCaseType: ResolvedCaseType,
  ) {
    val caseIdentifier = escapeIdentifier(casePath.generatedCaseName)
    val casePathClassIdentifier = escapeIdentifier(casePath.generatedCaseName + "CasePath")

    writer.appendLine("  public val $caseIdentifier: $casePathClassIdentifier = $casePathClassIdentifier()")
    writer.appendLine()
    writer.appendLine("  public inner class $casePathClassIdentifier internal constructor() : dev.kpaths.core.CaseKeyPathBase<$rootKotlinType, ${resolvedCaseType.kotlinType}>() {")
    writeCasePathBody(
      writer = writer,
      indent = "    ",
      rootKotlinType = rootKotlinType,
      rootTypeId = rootTypeId,
      casePath = casePath,
      resolvedCaseType = resolvedCaseType,
    )
    writeCaseUnitPayloadPath(
      writer = writer,
      indent = "    ",
      rootKotlinType = rootKotlinType,
      rootTypeId = rootTypeId,
      casePath = casePath,
      resolvedCaseType = resolvedCaseType,
    )
    writer.appendLine("  }")
  }

  /** Emits a reconstructable case payload path inside a generated concrete-case path. */
  private fun writeCasePayloadPath(
    writer: java.io.BufferedWriter,
    indent: String,
    rootKotlinType: String,
    rootTypeId: String,
    casePath: CasePath,
    resolvedCaseType: ResolvedCaseType,
    payload: CasePayload,
    capturesTypeTokens: Boolean = false,
  ) {
    val properties = payload.properties
    val propertyTypes = properties.map { property ->
      property.declaration.type.resolve().renderKotlinType(resolvedCaseType.typeParameterSubstitutions)
    }
    val focusKotlinType = when (properties.size) {
      1 -> propertyTypes.single()
      2 -> "kotlin.Pair<${propertyTypes.joinToString(", ")}>"
      3 -> "kotlin.Triple<${propertyTypes.joinToString(", ")}>"
      else -> error("Case payloads must contain one to three properties.")
    }
    val focusTypeId = when (properties.size) {
      1 -> properties.single().declaration.type.resolve().renderTypeId(resolvedCaseType.typeTokenSubstitutions)
      2 -> "dev.kpaths.core.TypeId.of<kotlin.Pair<${propertyTypes.joinToString(", ")}>>()"
      3 -> "dev.kpaths.core.TypeId.of<kotlin.Triple<${propertyTypes.joinToString(", ")}>>()"
      else -> error("Case payloads must contain one to three properties.")
    }
    val memberName = if (properties.size == 1) {
      properties.single().declaration.simpleName.asString()
    } else {
      "payload"
    }
    val memberIdentifier = escapeIdentifier(memberName)
    val pathInterface = "dev.kpaths.core.CaseKeyPath"
    val pathBase = "dev.kpaths.core.CaseKeyPathBase"

    writer.appendLine()
    if (capturesTypeTokens) {
      writer.appendLine("${indent}public val $memberIdentifier: $pathInterface<$rootKotlinType, $focusKotlinType> =")
      writer.appendLine("${indent}  object : $pathBase<$rootKotlinType, $focusKotlinType>() {")
      writeCasePayloadPathBody(
        writer = writer,
        indent = "$indent    ",
        rootKotlinType = rootKotlinType,
        rootTypeId = rootTypeId,
        casePath = casePath,
        resolvedCaseType = resolvedCaseType,
        properties = properties,
        focusKotlinType = focusKotlinType,
        focusTypeId = focusTypeId,
      )
      writer.appendLine("${indent}  }")
    } else {
      writer.appendLine("${indent}public val $memberIdentifier: $pathInterface<$rootKotlinType, $focusKotlinType> =")
      writer.appendLine("${indent}  object : $pathBase<$rootKotlinType, $focusKotlinType>() {")
      writeCasePayloadPathBody(
        writer = writer,
        indent = "$indent    ",
        rootKotlinType = rootKotlinType,
        rootTypeId = rootTypeId,
        casePath = casePath,
        resolvedCaseType = resolvedCaseType,
        properties = properties,
        focusKotlinType = focusKotlinType,
        focusTypeId = focusTypeId,
      )
      writer.appendLine("${indent}  }")
    }
    writeBoundCasePayloadPaths(
      writer = writer,
      indent = indent,
      rootKotlinType = rootKotlinType,
      rootTypeId = rootTypeId,
      casePath = casePath,
      resolvedCaseType = resolvedCaseType,
      properties = properties,
    )
  }

  /** Emits named, context-bound payload CaseKeyPaths such as `actionFor(id)`. */
  private fun writeBoundCasePayloadPaths(
    writer: java.io.BufferedWriter,
    indent: String,
    rootKotlinType: String,
    rootTypeId: String,
    casePath: CasePath,
    resolvedCaseType: ResolvedCaseType,
    properties: List<PathProperty>,
  ) {
    planKPathsBoundPayloadMembers(properties.map { it.declaration.simpleName.asString() }).forEach { member ->
      val targetIndex = member.targetIndex
      val target = properties[targetIndex]
      val targetName = target.declaration.simpleName.asString()
      val targetIdentifier = escapeIdentifier(targetName)
      val targetType = target.declaration.type.resolve().renderKotlinType(resolvedCaseType.typeParameterSubstitutions)
      val targetTypeId = target.declaration.type.resolve().renderTypeId(resolvedCaseType.typeTokenSubstitutions)
      val bound = properties.filterIndexed { index, _ -> index != targetIndex }
      val parameters = bound.joinToString(", ") { property ->
        val name = property.declaration.simpleName.asString()
        "${escapeIdentifier(name)}: ${property.declaration.type.resolve().renderKotlinType(resolvedCaseType.typeParameterSubstitutions)}"
      }
      val conditions = bound.joinToString(" && ") { property ->
        val name = escapeIdentifier(property.declaration.simpleName.asString())
        "case.$name == $name"
      }
      val construction = properties.joinToString(", ") { property ->
        if (property == target) "value" else escapeIdentifier(property.declaration.simpleName.asString())
      }
      val pathId = bound.fold(
        "dev.kpaths.core.PathId.case($rootTypeId, \"${casePath.sourceName}\").appending(dev.kpaths.core.PathId.property(${resolvedCaseType.typeId}, \"$targetName\"))",
      ) { accumulated, property ->
        "$accumulated.appending(dev.kpaths.core.PathSegment.Key(${escapeIdentifier(property.declaration.simpleName.asString())}))"
      }
      writer.appendLine()
      writer.appendLine("${indent}public fun ${targetIdentifier}For($parameters): dev.kpaths.core.CaseKeyPath<$rootKotlinType, $targetType> =")
      writer.appendLine("${indent}  object : dev.kpaths.core.CaseKeyPathBase<$rootKotlinType, $targetType>() {")
      writer.appendLine("${indent}    override val __kpaths_id: dev.kpaths.core.PathId = $pathId")
      writer.appendLine("${indent}    override val __kpaths_valueType: dev.kpaths.core.TypeId = $targetTypeId")
      writer.appendLine("${indent}    override fun match(root: $rootKotlinType): dev.kpaths.core.CaseMatch<$targetType> = (root as? ${resolvedCaseType.kotlinType})?.let { case -> if ($conditions) dev.kpaths.core.CaseMatch.Matched(case.$targetIdentifier) else dev.kpaths.core.CaseMatch.NoMatch } ?: dev.kpaths.core.CaseMatch.NoMatch")
      writer.appendLine("${indent}    override fun embed(value: $targetType): $rootKotlinType = ${resolvedCaseType.kotlinType}($construction)")
      writer.appendLine("${indent}  }")
    }
  }

  private fun writeCasePayloadPathBody(
    writer: java.io.BufferedWriter,
    indent: String,
    rootKotlinType: String,
    rootTypeId: String,
    casePath: CasePath,
    resolvedCaseType: ResolvedCaseType,
    properties: List<PathProperty>,
    focusKotlinType: String,
    focusTypeId: String,
  ) {
    val pathId = properties.fold(
      "dev.kpaths.core.PathId.case($rootTypeId, \"${casePath.sourceName}\")",
    ) { accumulated, property ->
      val propertyName = property.declaration.simpleName.asString()
      "$accumulated.appending(dev.kpaths.core.PathId.property(${resolvedCaseType.typeId}, \"$propertyName\"))"
    }
    writer.appendLine("${indent}override val __kpaths_id: dev.kpaths.core.PathId = $pathId")
    writer.appendLine("${indent}override val __kpaths_valueType: dev.kpaths.core.TypeId = $focusTypeId")
    writer.appendLine("${indent}override fun match(root: $rootKotlinType): dev.kpaths.core.CaseMatch<$focusKotlinType> = (root as? ${resolvedCaseType.kotlinType})?.let { case ->")
    when (properties.size) {
      1 -> writer.appendLine("${indent}  dev.kpaths.core.CaseMatch.Matched(case.${escapeIdentifier(properties.single().declaration.simpleName.asString())})")
      2 -> writer.appendLine(
        "${indent}  dev.kpaths.core.CaseMatch.Matched(kotlin.Pair(${properties.joinToString(", ") { property -> "case.${escapeIdentifier(property.declaration.simpleName.asString())}" }}))",
      )
      3 -> writer.appendLine(
        "${indent}  dev.kpaths.core.CaseMatch.Matched(kotlin.Triple(${properties.joinToString(", ") { property -> "case.${escapeIdentifier(property.declaration.simpleName.asString())}" }}))",
      )
    }
    writer.appendLine("${indent}} ?: dev.kpaths.core.CaseMatch.NoMatch")
    val constructionArguments = when (properties.size) {
      1 -> "value"
      2 -> "value.first, value.second"
      3 -> "value.first, value.second, value.third"
      else -> error("Case payloads must contain one to three properties.")
    }
    writer.appendLine("${indent}override fun embed(value: $focusKotlinType): $rootKotlinType = ${resolvedCaseType.kotlinType}($constructionArguments)")
  }

  /** Emits the generated namespace's finite case catalog and a lookup for a root value's case. */
  private fun writeCaseCatalog(
    writer: java.io.BufferedWriter,
    declaration: KSClassDeclaration,
    rootKotlinType: String,
    resolvedCasePaths: List<ResolvedCasePath>,
  ) {
    val partialCasePathType = "dev.kpaths.core.PartialCaseKeyPath<$rootKotlinType>"
    val caseModels = resolvedCasePaths.map { resolvedCasePath ->
      val casePath = resolvedCasePath.casePath
      casePath.catalogModel(generatedCasePayload(casePath))
    }
    val catalogPlan = planKPathsCaseCatalog(caseModels)
    if (!catalogPlan.isComplete) {
      logger.warn(
        "[KPaths] ${KPathsDiagnostics.incompleteCaseCatalog(catalogPlan.unavailableCases.map { it.sourceName })}",
        declaration,
      )
    }
    val payloadPaths = catalogPlan.entries.map { entry ->
      val caseIdentifier = escapeIdentifier(entry.case.generatedName)
      when (val access = entry.access) {
        KPathsCaseCatalogAccess.WholeCase -> caseIdentifier
        is KPathsCaseCatalogAccess.Payload ->
          "$caseIdentifier.${escapeIdentifier(access.payload.generatedMemberName)}"
      }
    }

    writer.appendLine()
    writer.appendLine("  public override val allCasePaths: kotlin.collections.List<$partialCasePathType> by kotlin.lazy {")
    writer.appendLine("    kotlin.collections.listOf(${payloadPaths.joinToString()})")
    writer.appendLine("  }")
    writer.appendLine()
    writer.appendLine("  public override fun caseOf(root: $rootKotlinType): $partialCasePathType {")
    payloadPaths.forEach { identifier ->
      writer.appendLine("    if ($identifier.matches(root)) return $identifier")
    }
    writer.appendLine("    error(\"No generated case path matched the supplied ${rootKotlinType.substringBefore('<')} value.\")")
    writer.appendLine("  }")
  }

  /** Translates KSP case discovery into the renderer-neutral associated-value model. */
  private fun CasePath.catalogModel(payload: CasePayload?): KPathsCasePathModel =
    KPathsCasePathModel(
      sourceName = sourceName,
      generatedName = generatedCaseName,
      kind = kind,
      payload = when {
        kind == CaseKind.ENUM_ENTRY -> KPathsCasePayload.Unit
        declaration.classKind == ClassKind.OBJECT -> KPathsCasePayload.Unit
        payload != null -> KPathsCasePayload.fromProperties(
          payload.properties.map { property -> property.declaration.simpleName.asString() },
        )
        else -> null
      },
    )

  private fun writeCasePathBody(
    writer: java.io.BufferedWriter,
    indent: String,
    rootKotlinType: String,
    rootTypeId: String,
    casePath: CasePath,
    resolvedCaseType: ResolvedCaseType,
  ) {
    writer.appendLine("${indent}override val __kpaths_id: dev.kpaths.core.PathId = dev.kpaths.core.PathId.case($rootTypeId, \"${casePath.sourceName}\")")
    writer.appendLine("${indent}override val __kpaths_valueType: dev.kpaths.core.TypeId = ${resolvedCaseType.typeId}")
    when (casePath.kind) {
      CaseKind.SEALED_VARIANT -> {
        writer.appendLine("${indent}@Suppress(\"UNCHECKED_CAST\")")
        writer.appendLine("${indent}override fun match(root: $rootKotlinType): dev.kpaths.core.CaseMatch<${resolvedCaseType.kotlinType}> = (root as? ${resolvedCaseType.kotlinType})?.let { dev.kpaths.core.CaseMatch.Matched<${resolvedCaseType.kotlinType}>(it) } ?: dev.kpaths.core.CaseMatch.NoMatch")
        writer.appendLine("${indent}override fun embed(value: ${resolvedCaseType.kotlinType}): $rootKotlinType = value")
      }
      CaseKind.ENUM_ENTRY -> {
        val enumEntryIdentifier = escapeIdentifier(casePath.sourceName)
        writer.appendLine("${indent}override fun match(root: $rootKotlinType): dev.kpaths.core.CaseMatch<kotlin.Unit> = if (root == $rootKotlinType.$enumEntryIdentifier) dev.kpaths.core.CaseMatch.Matched(kotlin.Unit) else dev.kpaths.core.CaseMatch.NoMatch")
        writer.appendLine("${indent}override fun embed(value: kotlin.Unit): $rootKotlinType = $rootKotlinType.$enumEntryIdentifier")
      }
    }
  }

  private fun writeGenericCasePropertyPaths(
    writer: java.io.BufferedWriter,
    rootDeclaration: KSClassDeclaration,
    rootTypeTokenNames: Map<String, String>,
    casePath: CasePath,
    resolvedCaseType: ResolvedCaseType,
    caseProperties: List<PathProperty>,
  ) {
    val rootTypeParameters = renderTypeParameters(rootDeclaration.typeParameters)
    val tokenParameters = rootDeclaration.typeParameters.joinToString(", ") { parameter ->
      "private val ${rootTypeTokenNames.getValue(parameter.name.asString())}: dev.kpaths.core.TypeId"
    }
    val nestedClassName = escapeIdentifier(casePath.sourceName + "PropertyPaths")
    val namespaceName = escapeIdentifier(requireNotNull(casePath.generatedPathsName))
    val tokenArguments = rootDeclaration.typeParameters.joinToString { parameter ->
      rootTypeTokenNames.getValue(parameter.name.asString())
    }

    writer.appendLine("  public val $namespaceName: $nestedClassName<${rootTypeParameters.names}> =")
    writer.appendLine("    $nestedClassName($tokenArguments)")
    writer.appendLine()
    writer.appendLine("  public class $nestedClassName<${rootTypeParameters.declaration}> internal constructor(")
    writer.appendLine("    $tokenParameters,")
    writer.appendLine("  )${rootTypeParameters.whereClause} {")
    caseProperties.forEach { property ->
      writePropertyPath(
        writer = writer,
        rootKotlinType = resolvedCaseType.kotlinType,
        rootTypeId = resolvedCaseType.typeId,
        pathProperty = property,
        typeTokenNames = resolvedCaseType.typeTokenSubstitutions,
        typeParameterSubstitutions = resolvedCaseType.typeParameterSubstitutions,
        capturesTypeTokens = true,
        declarationIndent = "    ",
      )
    }
    writer.appendLine("  }")
  }

  /** Resolves a sealed variant's type parameters against the annotated generic root. */
  private fun resolveCaseType(
    rootDeclaration: KSClassDeclaration,
    rootTypeTokenNames: Map<String, String>,
    casePath: CasePath,
  ): ResolvedCaseType? {
    if (casePath.kind == CaseKind.ENUM_ENTRY) {
      return ResolvedCaseType(
        kotlinType = "kotlin.Unit",
        typeId = "dev.kpaths.core.TypeId.of<kotlin.Unit>()",
        typeParameterSubstitutions = emptyMap(),
        typeTokenSubstitutions = emptyMap(),
      )
    }

    val caseDeclaration = casePath.declaration
    val caseQualifiedName = requireNotNull(casePath.qualifiedName)
    if (rootDeclaration.typeParameters.isEmpty()) {
      if (isKPathsGenericVariantOfConcreteRoot(false, caseDeclaration.typeParameters.isNotEmpty())) {
        reportGenerationError(
          rootDeclaration,
          KPathsDiagnostics.genericVariantOfConcreteRoot(casePath.sourceName),
        )
        return null
      }
      return ResolvedCaseType(
        kotlinType = renderKotlinQualifiedName(caseQualifiedName),
        typeId = renderDeclaredTypeId(caseQualifiedName, emptyList(), emptyMap()),
        typeParameterSubstitutions = emptyMap(),
        typeTokenSubstitutions = emptyMap(),
      )
    }

    val rootQualifiedName = requireNotNull(rootDeclaration.qualifiedName?.asString())
    val caseRootSupertype = caseDeclaration.superTypes
      .map { it.resolve() }
      .firstOrNull { it.declaration.qualifiedName?.asString() == rootQualifiedName }
    if (caseRootSupertype == null) {
      reportGenerationError(
        rootDeclaration,
        "Cannot generate '${casePath.sourceName}' because KPaths could not resolve its direct $rootQualifiedName supertype.",
      )
      return null
    }

    val plan = planKPathsGenericCase(
      rootParameters = rootDeclaration.typeParameters.map {
        KPathsGenericRootParameter(it.name.asString(), it.variance == Variance.COVARIANT)
      },
      caseParameters = caseDeclaration.typeParameters.map { it.name.asString() },
      arguments = caseRootSupertype.arguments.map { argument ->
        val type = argument.type?.resolve()
        KPathsGenericCaseArgument(
          forwardedParameter = (type?.declaration as? KSTypeParameter)?.name?.asString(),
          isNullable = type?.isMarkedNullable == true,
          isNothing = type?.isNothing() == true,
          rendered = type?.renderKotlinType() ?: "<unresolved>",
        )
      },
    )
    val kotlinSubstitutions = (plan as? KPathsGenericCasePlan.Supported)?.substitutions ?: run {
      reportGenerationError(rootDeclaration, requireNotNull(kPathsGenericCaseDiagnostic(casePath.sourceName, rootQualifiedName, plan)))
      return null
    }
    val tokenSubstitutions = kotlinSubstitutions.mapValues { (_, rootName) -> rootTypeTokenNames.getValue(rootName) }

    val caseTypeArguments = caseDeclaration.typeParameters.joinToString { parameter ->
      escapeIdentifier(kotlinSubstitutions.getValue(parameter.name.asString()))
    }
    return ResolvedCaseType(
      kotlinType = renderKotlinQualifiedName(caseQualifiedName) + caseTypeArguments.takeIf { it.isNotEmpty() }
        ?.let { "<$it>" }
        .orEmpty(),
      typeId = renderDeclaredTypeId(caseQualifiedName, caseDeclaration.typeParameters, tokenSubstitutions),
      typeParameterSubstitutions = kotlinSubstitutions,
      typeTokenSubstitutions = tokenSubstitutions,
    )
  }

  private fun KSType.isNothing(): Boolean =
    declaration.qualifiedName?.asString() == "kotlin.Nothing" && !isMarkedNullable

  private fun reportGenerationError(rootDeclaration: KSClassDeclaration, message: String) {
    val annotationQualifiedName = requestsByDeclaration[rootDeclaration]?.annotationQualifiedName
    val annotation = annotationQualifiedName?.let { qualifiedName ->
      rootDeclaration.annotations.firstOrNull { candidate ->
        candidate.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName
      }
    }
    logger.error("[KPaths] $message", annotation ?: rootDeclaration)
  }

  private fun renameAdvice(
    declaration: KSClassDeclaration,
    generatedName: String,
    conflictingDeclaration: String = "existing declaration",
  ): String = requestsByDeclaration[declaration]?.renameSuggestion
    ?.let { "Use an unused explicit name, such as $it, or rename the $conflictingDeclaration." }
    ?: "Use an unused generated catalog name or rename the $conflictingDeclaration."

  private fun writeSetter(
    writer: java.io.BufferedWriter,
    kind: PathKind,
    rootKotlinType: String,
    valueKotlinType: String,
    identifier: String,
    indent: String,
  ) {
    when (kind) {
      PathKind.READ_ONLY -> Unit
      PathKind.COPY_WRITABLE ->
        writer.appendLine("${indent}override fun set(root: $rootKotlinType, value: $valueKotlinType): $rootKotlinType = root.copy($identifier = value)")
      PathKind.COPY_THEN_MUTATE -> {
        writer.appendLine("${indent}override fun set(root: $rootKotlinType, value: $valueKotlinType): $rootKotlinType {")
        writer.appendLine("${indent}  val __kpaths_copy = root.copy()")
        writer.appendLine("${indent}  __kpaths_copy.$identifier = value")
        writer.appendLine("${indent}  return __kpaths_copy")
        writer.appendLine("${indent}}")
      }
      PathKind.REFERENCE_WRITABLE -> {
        writer.appendLine("${indent}override fun setInPlace(root: $rootKotlinType, value: $valueKotlinType) {")
        writer.appendLine("${indent}  root.$identifier = value")
        writer.appendLine("${indent}}")
      }
    }
  }

  private data class PathProperty(
    val declaration: KSPropertyDeclaration,
    val generatedName: String,
    val kind: PathKind,
    val recipe: KPathsPropertyPathRecipe? = null,
    val declarativeAdapter: DeclarativeAdapter? = null,
  )

  private data class CasePath(
    val declaration: KSClassDeclaration,
    val sourceName: String,
    val generatedCaseName: String,
    val generatedPathsName: String?,
    val kind: CaseKind,
    val qualifiedName: String?,
    val propertyPaths: List<PathProperty>,
  )

  private data class ResolvedCaseType(
    val kotlinType: String,
    val typeId: String,
    val typeParameterSubstitutions: Map<String, String>,
    val typeTokenSubstitutions: Map<String, String>,
  )

  private data class ResolvedCasePath(
    val casePath: CasePath,
    val resolvedCaseType: ResolvedCaseType,
  )

  private data class CasePayload(
    val casePath: CasePath,
    val properties: List<PathProperty>,
  )

  private data class RenderedTypeParameters(
    val declaration: String,
    val names: String,
    val whereClause: String,
  )

  private data class CompanionPaths(
    val companion: KSClassDeclaration,
    val namespaceName: String,
  )

  private data class CompanionGeneratedMember(
    val companion: KSClassDeclaration,
    val name: String,
  )

  private data class GenericCompanionPaths(
    val companion: KSClassDeclaration,
    val namespaceName: String,
    val isDirectCompanionNamespace: Boolean,
  )

  private typealias CaseKind = KPathsCaseKind
  private typealias PathKind = KPathsPropertyPathKind

  private fun renderDeclaredTypeId(
    qualifiedName: String,
    parameters: List<com.google.devtools.ksp.symbol.KSTypeParameter>,
    typeTokenNames: Map<String, String>,
  ): String {
    return KPathsTypeIdSpec.declaredRoot(
      qualifiedName,
      parameters.map { it.name.asString() },
    ).renderTypeId(typeTokenNames)
  }

  /** Renders generic bounds so generated namespaces can use constrained model type parameters. */
  private fun renderTypeParameters(
    parameters: List<KSTypeParameter>,
    reified: Boolean = false,
    forceNonNull: Boolean = false,
  ): RenderedTypeParameters {
    val names = parameters.joinToString { parameter -> escapeIdentifier(parameter.name.asString()) }
    val constraints = parameters.flatMap { parameter ->
      val name = escapeIdentifier(parameter.name.asString())
      val bounds = parameter.bounds
        .map { it.resolve() }
        .filterNot { it.isImplicitNullableAnyBound() }
        .map { bound -> "$name : ${bound.renderKotlinType()}" }
        .toMutableList()
      if (forceNonNull && bounds.none { it.endsWith(" : kotlin.Any") || !it.endsWith("?") }) {
        bounds += "$name : kotlin.Any"
      }
      bounds
    }
    val declaration = parameters.joinToString { parameter ->
      val name = escapeIdentifier(parameter.name.asString())
      if (reified) "reified $name" else name
    }
    val whereClause = constraints.takeIf { it.isNotEmpty() }
      ?.joinToString(prefix = " where ")
      .orEmpty()
    return RenderedTypeParameters(declaration, names, whereClause)
  }

  private fun KSType.isImplicitNullableAnyBound(): Boolean =
    declaration.qualifiedName?.asString() == "kotlin.Any" && isMarkedNullable

  private fun KSType.renderKotlinType(typeParameterSubstitutions: Map<String, String> = emptyMap()): String {
    val typeDeclaration = declaration
    if (typeDeclaration is KSTypeParameter) {
      val name = typeParameterSubstitutions[typeDeclaration.name.asString()]
        ?: typeDeclaration.name.asString()
      return escapeIdentifier(name) + if (isMarkedNullable) "?" else ""
    }
    val declarationName = typeDeclaration.qualifiedName
      ?.asString()
      ?.let(::renderKotlinQualifiedName)
      ?: escapeIdentifier(typeDeclaration.simpleName.asString())
    val arguments = arguments
      .takeIf { it.isNotEmpty() }
      ?.joinToString(prefix = "<", postfix = ">") { argument ->
        val type = argument.type?.resolve()?.renderKotlinType(typeParameterSubstitutions)
          ?: return@joinToString "*"
        when (argument.variance) {
          Variance.COVARIANT -> "out $type"
          Variance.CONTRAVARIANT -> "in $type"
          else -> type
        }
      }
      .orEmpty()
    return declarationName + arguments + if (isMarkedNullable) "?" else ""
  }

  private fun KSType.renderTypeId(typeTokenNames: Map<String, String>): String {
    return toKPathsTypeIdSpec().renderTypeId(typeTokenNames)
  }

  /** Converts KSP symbols to the backend-neutral model before KotlinPoet/source rendering. */
  private fun KSType.toKPathsTypeIdSpec(): KPathsTypeIdSpec {
    val typeDeclaration = declaration
    if (typeDeclaration is KSTypeParameter) {
      return KPathsTypeIdSpec.Parameter(typeDeclaration.name.asString(), isMarkedNullable)
    }
    val qualifiedName = typeDeclaration.qualifiedName?.asString()
    if (qualifiedName == null) {
      return KPathsTypeIdSpec.Parameter(typeDeclaration.simpleName.asString(), isMarkedNullable)
    }
    return KPathsTypeIdSpec.Declared(
      classifier = qualifiedName,
      arguments = arguments.map { argument ->
        val type = argument.type?.resolve()?.toKPathsTypeIdSpec()
        when (argument.variance) {
          Variance.STAR -> KPathsTypeArgumentSpec.star
          Variance.COVARIANT -> KPathsTypeArgumentSpec.out(requireNotNull(type))
          Variance.CONTRAVARIANT -> KPathsTypeArgumentSpec.`in`(requireNotNull(type))
          else -> KPathsTypeArgumentSpec.invariant(requireNotNull(type))
        }
      },
      nullable = isMarkedNullable,
    )
  }

  /** Renders the shared type shape as the `TypeId` expression used in generated Kotlin source. */
  private fun KPathsTypeIdSpec.renderTypeId(typeTokenNames: Map<String, String>): String = when (this) {
    is KPathsTypeIdSpec.Parameter -> {
      val token = typeTokenNames[name] ?: error("No generated type token for $name.")
      if (nullable) "$token.withNullability(true)" else token
    }
    is KPathsTypeIdSpec.Declared -> {
      val arguments = arguments.joinToString { argument -> argument.renderTypeArgument(typeTokenNames) }
      val nullableArgument = if (nullable) ", nullable = true" else ""
      if (arguments.isEmpty()) {
        "dev.kpaths.core.TypeId(\"$classifier\"$nullableArgument)"
      } else {
        "dev.kpaths.core.TypeId(\"$classifier\", listOf($arguments)$nullableArgument)"
      }
    }
  }

  private fun KPathsTypeArgumentSpec.renderTypeArgument(typeTokenNames: Map<String, String>): String = when (variance) {
    KPathsTypeVariance.STAR -> "dev.kpaths.core.TypeArgument.star"
    KPathsTypeVariance.INVARIANT -> "dev.kpaths.core.TypeArgument.invariant(${requireNotNull(type).renderTypeId(typeTokenNames)})"
    KPathsTypeVariance.IN -> "dev.kpaths.core.TypeArgument.`in`(${requireNotNull(type).renderTypeId(typeTokenNames)})"
    KPathsTypeVariance.OUT -> "dev.kpaths.core.TypeArgument.out(${requireNotNull(type).renderTypeId(typeTokenNames)})"
  }

  private fun typeTokenName(parameterName: String): String =
    escapeIdentifier(generatedInternalPrefix + parameterName + "TypeId")

  private fun typeTokenArgumentName(parameterName: String): String =
    escapeIdentifier(parameterName + "TypeId")

  private fun typeTokenClassArgumentName(parameterName: String): String =
    escapeIdentifier(parameterName + "Type")

  private fun renderKotlinQualifiedName(qualifiedName: String): String =
    qualifiedName.split('.').joinToString(".", transform = ::escapeIdentifier)

  private fun escapeIdentifier(identifier: String): String =
    if (identifier in kotlinKeywords || !identifier.isKotlinIdentifier()) "`$identifier`" else identifier

  private fun String.isKotlinIdentifier(): Boolean =
    isNotEmpty() && first().isLetterOrUnderscore() && drop(1).all { it.isLetterOrDigitOrUnderscore() }

  private fun Char.isLetterOrUnderscore(): Boolean = isLetter() || this == '_'

  private fun Char.isLetterOrDigitOrUnderscore(): Boolean = isLetterOrDigit() || this == '_'

  private companion object {
    private const val generatedInternalPrefix = KPATHS_INTERNAL_PREFIX

    private val kotlinKeywords = setOf(
      "as", "as?", "break", "class", "continue", "do", "else", "false", "for", "fun", "if",
      "in", "!in", "interface", "is", "!is", "null", "object", "package", "return", "super",
      "this", "throw", "true", "try", "typealias", "val", "var", "when", "while",
    )
  }
}
