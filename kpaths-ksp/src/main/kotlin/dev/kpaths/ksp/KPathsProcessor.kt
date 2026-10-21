package dev.kpaths.ksp

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Variance
import com.google.devtools.ksp.validate
import dev.kpaths.annotations.Pathable

private val pathableAnnotation = Pathable::class.qualifiedName!!

/** Generates stable property and case-path APIs for [Pathable] declarations. */
public class KPathsProcessorProvider : SymbolProcessorProvider {
  override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
    KPathsProcessor(environment.codeGenerator, environment.logger)
}

private class KPathsProcessor(
  private val codeGenerator: CodeGenerator,
  private val logger: KSPLogger,
) : SymbolProcessor {
  override fun process(resolver: Resolver): List<KSAnnotated> {
    val deferred = mutableListOf<KSAnnotated>()
    val targets = mutableListOf<GenerationTarget>()
    val declarations = resolver.getSymbolsWithAnnotation(pathableAnnotation)
      .asSequence()
      .filterIsInstance<KSClassDeclaration>()
      .toList()

    declarations.forEach { declaration ->
      if (!declaration.validate(enableNewFeatures = true)) {
        deferred += declaration
        return@forEach
      }
      val qualifiedName = declaration.qualifiedName?.asString() ?: run {
        logger.error("@Pathable types must have a qualified name.", declaration)
        return@forEach
      }
      val packageName = declaration.packageName.asString()
      val defaultName = generatedObjectName(qualifiedName, packageName)
      val generatedName = configuredGeneratedName(declaration, defaultName) ?: return@forEach
      targets += GenerationTarget(declaration, generatedName)
    }

    val duplicateNames = targets.groupBy { target ->
      generatedQualifiedName(target.declaration.packageName.asString(), target.generatedName)
    }.filterValues { it.size > 1 }
    duplicateNames.values.flatten().forEach { target ->
      val others = duplicateNames.getValue(
        generatedQualifiedName(target.declaration.packageName.asString(), target.generatedName),
      ).filterNot { it.declaration == target.declaration }
      reportPathableError(
        target.declaration,
        "Cannot generate '${target.generatedName}' because @Pathable also requests that name for " +
          others.joinToString(" and ") { "'${it.declaration.qualifiedName?.asString()}'" } + ". " +
          "Choose distinct @Pathable names.",
      )
    }
    targets.filter { target ->
      generatedQualifiedName(target.declaration.packageName.asString(), target.generatedName) !in duplicateNames
    }.forEach { target ->
      val packageName = target.declaration.packageName.asString()
      if (validateTopLevelGeneratedName(target.declaration, resolver, packageName, target.generatedName)) {
        generatePaths(target.declaration, target.generatedName)
      }
    }

    return deferred
  }

  private fun generatePaths(
    declaration: KSClassDeclaration,
    generatedName: String,
  ) {
    val qualifiedName = declaration.qualifiedName?.asString() ?: run {
      logger.error("@Pathable types must have a qualified name.", declaration)
      return
    }
    val packageName = declaration.packageName.asString()
    val generatedIdentifier = escapeIdentifier(generatedName)
    val pathProperties = pathProperties(declaration)
    val casePaths = casePaths(declaration)
    if (!validateGeneratedMemberNames(declaration, pathProperties, casePaths)) {
      return
    }
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
    val containingFile = declaration.containingFile ?: run {
      logger.error("@Pathable types must originate from a source file.", declaration)
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
      if (declaration.typeParameters.isEmpty()) {
        writer.appendLine("public object $generatedIdentifier {")
      } else {
        val typeParameters = declaration.typeParameters.joinToString { escapeIdentifier(it.name.asString()) }
        val tokenParameters = declaration.typeParameters.joinToString(", ") { parameter ->
          "${typeTokenArgumentNames.getValue(parameter.name.asString())}: dev.kpaths.core.TypeId"
        }
        val constructorParameters = typeTokenNames.values.joinToString(", ") { name ->
          "private val $name: dev.kpaths.core.TypeId"
        }
        writer.appendLine("public class $generatedIdentifier<$typeParameters> private constructor(")
        writer.appendLine("  $constructorParameters,")
        writer.appendLine(") {")
        writer.appendLine("  public companion object {")
        writer.appendLine("    public fun <$typeParameters> of($tokenParameters): $generatedIdentifier<$typeParameters> =")
        writer.appendLine("      $generatedIdentifier(${declaration.typeParameters.joinToString { typeTokenArgumentNames.getValue(it.name.asString()) }})")
        val classTokenParameters = declaration.typeParameters.joinToString(", ") { parameter ->
          "${typeTokenClassArgumentName(parameter.name.asString())}: kotlin.reflect.KClass<${escapeIdentifier(parameter.name.asString())}>"
        }
        val nonNullTypeParameters = declaration.typeParameters.joinToString(", ") { parameter ->
          "${escapeIdentifier(parameter.name.asString())} : Any"
        }
        writer.appendLine("    public fun <$nonNullTypeParameters> of($classTokenParameters): $generatedIdentifier<$typeParameters> =")
        writer.appendLine("      $generatedIdentifier(${declaration.typeParameters.joinToString { "dev.kpaths.core.TypeId.from(${typeTokenClassArgumentName(it.name.asString())})" }})")
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
      casePaths.forEach { casePath ->
        writeCasePath(
          writer = writer,
          rootKotlinType = rootKotlinType,
          rootTypeId = generatedRootTypeName,
          rootDeclaration = declaration,
          rootTypeTokenNames = typeTokenNames,
          capturesTypeTokens = declaration.typeParameters.isNotEmpty(),
          casePath = casePath,
        )
      }
      writer.appendLine("}")
      if (declaration.typeParameters.isNotEmpty()) {
        val typeParameters = declaration.typeParameters.joinToString { "reified ${escapeIdentifier(it.name.asString())}" }
        writer.appendLine()
        val returnTypeParameters = declaration.typeParameters.joinToString { escapeIdentifier(it.name.asString()) }
        writer.appendLine("public inline fun <$typeParameters> $generatedIdentifier(): $generatedIdentifier<$returnTypeParameters> =")
        writer.appendLine("  $generatedIdentifier.of(${declaration.typeParameters.joinToString { "dev.kpaths.core.TypeId.of<${escapeIdentifier(it.name.asString())}>()" }})")
      }
    }
  }

  private fun generatedObjectName(
    qualifiedName: String,
    packageName: String,
  ): String {
    val typePath = qualifiedName.removePrefix("$packageName.")
    return typePath.replace('.', '_') + "Paths"
  }

  private fun configuredGeneratedName(
    declaration: KSClassDeclaration,
    defaultName: String,
  ): String? {
    val annotation = declaration.annotations.firstOrNull { candidate ->
      candidate.annotationType.resolve().declaration.qualifiedName?.asString() == pathableAnnotation
    } ?: return defaultName
    val configuredName = annotation.arguments
      .firstOrNull { it.name?.asString() == "name" }
      ?.value as? String
      ?: ""
    if (configuredName.isEmpty()) {
      return defaultName
    }
    if (configuredName.startsWith(generatedInternalPrefix)) {
      reportPathableError(
        declaration,
        "@Pathable name '$configuredName' uses the reserved '$generatedInternalPrefix' prefix for " +
          "KPaths-generated implementation details.",
      )
      return null
    }
    if (!configuredName.isKotlinIdentifier()) {
      reportPathableError(
        declaration,
        "@Pathable name '$configuredName' must be a single Kotlin identifier, without a package or dot.",
      )
      return null
    }
    return configuredName
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
    reportPathableError(
      declaration,
      "Cannot generate '$generatedName' because it conflicts with existing top-level " +
        conflicts.joinToString(" and ") + ". Use an unused explicit name, such as " +
        "@Pathable(\"Custom$generatedName\"), or rename the existing declaration.",
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
    reportPathableError(
      declaration,
      "Cannot generate '$fileName' because source file '${conflictingFile.filePath}' has the same " +
        "Kotlin/JVM file name. Use an unused explicit name, such as @Pathable(\"Custom$generatedName\"), " +
        "or rename the source file.",
    )
    return false
  }

  private fun generatedQualifiedName(packageName: String, generatedName: String): String =
    "$packageName.$generatedName"

  /** Returns direct sealed variants or payload-less enum entries. */
  private fun casePaths(declaration: KSClassDeclaration): List<CasePath> {
    if (Modifier.SEALED in declaration.modifiers) {
      return declaration.getSealedSubclasses().mapNotNull { caseDeclaration ->
        if (caseDeclaration.classKind !in setOf(ClassKind.CLASS, ClassKind.OBJECT)) {
          logger.error(
            "@Pathable sealed variant '${caseDeclaration.simpleName.asString()}' must be a class or object.",
            caseDeclaration,
          )
          return@mapNotNull null
        }
        val qualifiedName = caseDeclaration.qualifiedName?.asString() ?: run {
          logger.error("Sealed variants must have qualified names.", caseDeclaration)
          return@mapNotNull null
        }
        CasePath(
          declaration = caseDeclaration,
          name = caseDeclaration.simpleName.asString(),
          kind = CaseKind.SEALED_VARIANT,
          qualifiedName = qualifiedName,
          propertyPaths = pathProperties(caseDeclaration),
        )
      }.toList()
    }

    if (declaration.classKind == ClassKind.ENUM_CLASS) {
      return declaration.declarations
        .filterIsInstance<KSClassDeclaration>()
        .filter { it.classKind == ClassKind.ENUM_ENTRY }
        .map { entry ->
          CasePath(
            declaration = entry,
            name = entry.simpleName.asString(),
            kind = CaseKind.ENUM_ENTRY,
            qualifiedName = null,
            propertyPaths = emptyList(),
          )
        }
        .toList()
    }

    return emptyList()
  }

  /** Classifies Kotlin properties into the library's explicit value-style and reference-style APIs. */
  private fun pathProperties(declaration: KSClassDeclaration): List<PathProperty> {
    if (declaration.classKind != ClassKind.CLASS) {
      return emptyList()
    }

    val accessibleProperties = declaration.getDeclaredProperties()
      .filter(::isAccessible)
      .toList()

    if (Modifier.DATA !in declaration.modifiers) {
      return accessibleProperties.map { property ->
        PathProperty(
          declaration = property,
          kind = if (property.isMutable && isSetterAccessible(property)) {
            PathKind.REFERENCE_WRITABLE
          } else {
            PathKind.READ_ONLY
          },
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
      if (property.simpleName.asString() in constructorPropertyNames) {
        PathProperty(property, PathKind.COPY_WRITABLE)
      } else if (property.isMutable) {
        logger.error(
          "@Pathable data-class property '${property.simpleName.asString()}' must be declared in the primary constructor to receive a copy-based writable path.",
          property,
        )
        null
      } else {
        PathProperty(property, PathKind.READ_ONLY)
      }
    }
  }

  private fun isAccessible(property: KSPropertyDeclaration): Boolean =
    Modifier.PRIVATE !in property.modifiers && Modifier.PROTECTED !in property.modifiers

  private fun isSetterAccessible(property: KSPropertyDeclaration): Boolean =
    property.setter?.let(::isAccessible) ?: false

  private fun isAccessible(setter: com.google.devtools.ksp.symbol.KSPropertySetter): Boolean =
    Modifier.PRIVATE !in setter.modifiers && Modifier.PROTECTED !in setter.modifiers

  /** Rejects ambiguous public members instead of silently changing the generated API. */
  private fun validateGeneratedMemberNames(
    declaration: KSClassDeclaration,
    pathProperties: List<PathProperty>,
    casePaths: List<CasePath>,
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
        name = property.declaration.simpleName.asString(),
        description = "the key path for property '${property.declaration.simpleName.asString()}'",
      )
    }
    casePaths.forEach { casePath ->
      members += GeneratedMember(
        name = casePath.name,
        description = "the case path for '${casePath.name}'",
      )
      if (casePath.kind == CaseKind.SEALED_VARIANT && casePath.propertyPaths.isNotEmpty()) {
        members += GeneratedMember(
          name = casePath.name + "Paths",
          description = "the property-path namespace for case '${casePath.name}'",
        )
        if (declaration.typeParameters.isNotEmpty()) {
          members += GeneratedMember(
            name = casePath.name + "PropertyPaths",
            description = "the generated property-path type for case '${casePath.name}'",
          )
        }
      }
    }

    var isValid = true
    (pathProperties.asSequence() + casePaths.asSequence().flatMap { it.propertyPaths.asSequence() })
      .map { it.declaration }
      .filter { it.simpleName.asString().startsWith(generatedInternalPrefix) }
      .forEach { property ->
        reportPathableError(
          declaration,
          "Cannot generate paths because property '${property.simpleName.asString()}' uses the reserved " +
            "'$generatedInternalPrefix' prefix for KPaths-generated implementation details.",
        )
        isValid = false
      }
    casePaths
      .filter { it.name.startsWith(generatedInternalPrefix) }
      .forEach { casePath ->
        reportPathableError(
          declaration,
          "Cannot generate paths because case '${casePath.name}' uses the reserved " +
            "'$generatedInternalPrefix' prefix for KPaths-generated implementation details.",
        )
        isValid = false
      }

    members.groupBy(GeneratedMember::name)
      .filterValues { it.size > 1 }
      .forEach { (name, collisions) ->
        reportPathableError(
          declaration,
          "Cannot generate paths because '$name' would name both " +
            collisions.joinToString(" and ") { it.description } + ". Rename one of the model members.",
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
    val identifier = escapeIdentifier(name)
    val valueKotlinType = property.type.resolve().renderKotlinType(typeParameterSubstitutions)
    val valueTypeId = property.type.resolve().renderTypeId(typeTokenNames)
    val pathInterface = when (pathProperty.kind) {
      PathKind.READ_ONLY -> "dev.kpaths.core.KeyPath"
      PathKind.COPY_WRITABLE -> "dev.kpaths.core.WritableKeyPath"
      PathKind.REFERENCE_WRITABLE -> "dev.kpaths.core.ReferenceWritableKeyPath"
    }
    val pathBase = when (pathProperty.kind) {
      PathKind.READ_ONLY -> "dev.kpaths.core.KeyPathBase"
      PathKind.COPY_WRITABLE -> "dev.kpaths.core.WritableKeyPathBase"
      PathKind.REFERENCE_WRITABLE -> "dev.kpaths.core.ReferenceWritableKeyPathBase"
    }
    writer.appendLine()
    if (capturesTypeTokens) {
      writer.appendLine("${declarationIndent}public val $identifier: $pathInterface<$rootKotlinType, $valueKotlinType> =")
      writer.appendLine("${declarationIndent}  object : $pathBase<$rootKotlinType, $valueKotlinType>() {")
      writer.appendLine("${declarationIndent}    override val id: dev.kpaths.core.PathId = dev.kpaths.core.PathId.property($rootTypeId, \"$name\")")
      writer.appendLine("${declarationIndent}    override val valueType: dev.kpaths.core.TypeId = $valueTypeId")
      writer.appendLine("${declarationIndent}    override fun get(root: $rootKotlinType): $valueKotlinType = root.$identifier")
      writeSetter(writer, pathProperty.kind, rootKotlinType, valueKotlinType, identifier, indent = "${declarationIndent}    ")
      writer.appendLine("${declarationIndent}  }")
    } else {
      writer.appendLine("${declarationIndent}public object $identifier : $pathBase<$rootKotlinType, $valueKotlinType>() {")
      writer.appendLine("${declarationIndent}  override val id: dev.kpaths.core.PathId = dev.kpaths.core.PathId.property($rootTypeId, \"$name\")")
      writer.appendLine("${declarationIndent}  override val valueType: dev.kpaths.core.TypeId = $valueTypeId")
      writer.appendLine("${declarationIndent}  override fun get(root: $rootKotlinType): $valueKotlinType = root.$identifier")
      writeSetter(writer, pathProperty.kind, rootKotlinType, valueKotlinType, identifier, indent = "${declarationIndent}  ")
      writer.appendLine("${declarationIndent}}")
    }
  }

  private fun writeCasePath(
    writer: java.io.BufferedWriter,
    rootKotlinType: String,
    rootTypeId: String,
    rootDeclaration: KSClassDeclaration,
    rootTypeTokenNames: Map<String, String>,
    capturesTypeTokens: Boolean,
    casePath: CasePath,
  ) {
    val caseIdentifier = escapeIdentifier(casePath.name)
    val resolvedCaseType = resolveCaseType(
      rootDeclaration = rootDeclaration,
      rootTypeTokenNames = rootTypeTokenNames,
      casePath = casePath,
    ) ?: return

    writer.appendLine()
    if (capturesTypeTokens) {
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
      writer.appendLine("  public object ${escapeIdentifier(casePath.name + "Paths")} {")
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

  private fun writeCasePathBody(
    writer: java.io.BufferedWriter,
    indent: String,
    rootKotlinType: String,
    rootTypeId: String,
    casePath: CasePath,
    resolvedCaseType: ResolvedCaseType,
  ) {
    writer.appendLine("${indent}override val id: dev.kpaths.core.PathId = dev.kpaths.core.PathId.case($rootTypeId, \"${casePath.name}\")")
    writer.appendLine("${indent}override val valueType: dev.kpaths.core.TypeId = ${resolvedCaseType.typeId}")
    when (casePath.kind) {
      CaseKind.SEALED_VARIANT -> {
        writer.appendLine("${indent}@Suppress(\"UNCHECKED_CAST\")")
        writer.appendLine("${indent}override fun extract(root: $rootKotlinType): ${resolvedCaseType.kotlinType}? = root as? ${resolvedCaseType.kotlinType}")
        writer.appendLine("${indent}override fun embed(value: ${resolvedCaseType.kotlinType}): $rootKotlinType = value")
      }
      CaseKind.ENUM_ENTRY -> {
        val enumEntryIdentifier = escapeIdentifier(casePath.name)
        writer.appendLine("${indent}override fun extract(root: $rootKotlinType): kotlin.Unit? = if (root == $rootKotlinType.$enumEntryIdentifier) kotlin.Unit else null")
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
    val rootTypeParameters = rootDeclaration.typeParameters.joinToString { escapeIdentifier(it.name.asString()) }
    val tokenParameters = rootDeclaration.typeParameters.joinToString(", ") { parameter ->
      "private val ${rootTypeTokenNames.getValue(parameter.name.asString())}: dev.kpaths.core.TypeId"
    }
    val nestedClassName = escapeIdentifier(casePath.name + "PropertyPaths")
    val namespaceName = escapeIdentifier(casePath.name + "Paths")
    val tokenArguments = rootDeclaration.typeParameters.joinToString { parameter ->
      rootTypeTokenNames.getValue(parameter.name.asString())
    }

    writer.appendLine("  public val $namespaceName: $nestedClassName<$rootTypeParameters> =")
    writer.appendLine("    $nestedClassName($tokenArguments)")
    writer.appendLine()
    writer.appendLine("  public class $nestedClassName<$rootTypeParameters> internal constructor(")
    writer.appendLine("    $tokenParameters,")
    writer.appendLine("  ) {")
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
      if (caseDeclaration.typeParameters.isNotEmpty()) {
        reportPathableError(
          rootDeclaration,
          "Cannot generate '${casePath.name}' because this non-generic sealed root has a generic variant. " +
            "Make the variant non-generic or use a generic sealed root.",
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
      reportPathableError(
        rootDeclaration,
        "Cannot generate '${casePath.name}' because KPaths could not resolve its direct $rootQualifiedName supertype.",
      )
      return null
    }

    val caseParameterNames = caseDeclaration.typeParameters.map { it.name.asString() }.toSet()
    val kotlinSubstitutions = mutableMapOf<String, String>()
    val tokenSubstitutions = mutableMapOf<String, String>()
    for ((index, rootParameter) in rootDeclaration.typeParameters.withIndex()) {
      val caseArgument = caseRootSupertype.arguments.getOrNull(index)?.type?.resolve()
      val caseParameter = caseArgument?.declaration as? KSTypeParameter
      val caseParameterName = caseParameter?.name?.asString()
      if (
        caseParameterName != null &&
        caseParameterName in caseParameterNames &&
        !caseArgument.isMarkedNullable
      ) {
        val rootParameterName = rootParameter.name.asString()
        val existingSubstitution = kotlinSubstitutions[caseParameterName]
        if (existingSubstitution != null && existingSubstitution != rootParameterName) {
          reportPathableError(
            rootDeclaration,
            "Cannot generate '${casePath.name}' because its type parameter '$caseParameterName' " +
              "would need to represent both '$existingSubstitution' and '$rootParameterName'.",
          )
          return null
        }
        kotlinSubstitutions[caseParameterName] = rootParameterName
        tokenSubstitutions[caseParameterName] = rootTypeTokenNames.getValue(rootParameterName)
      } else if (rootParameter.variance == Variance.COVARIANT && caseArgument?.isNothing() == true) {
        // A covariant `Root<Nothing>` can be embedded as `Root<T>` for every T.
      } else {
        val renderedArgument = caseArgument?.renderKotlinType() ?: "<unresolved>"
        reportPathableError(
          rootDeclaration,
          "Cannot generate '${casePath.name}' because it implements $rootQualifiedName<$renderedArgument> " +
            "where the generic root requires '${rootParameter.name.asString()}'. " +
            "The variant must forward that type parameter, or refine it to Nothing through an `out` parameter.",
        )
        return null
      }
    }

    val missingParameters = caseDeclaration.typeParameters
      .map { it.name.asString() }
      .filterNot(kotlinSubstitutions::containsKey)
    if (missingParameters.isNotEmpty()) {
      reportPathableError(
        rootDeclaration,
        "Cannot generate '${casePath.name}' because its type parameter(s) " +
          "${missingParameters.joinToString()} cannot be determined from the sealed root.",
      )
      return null
    }

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

  private fun reportPathableError(rootDeclaration: KSClassDeclaration, message: String) {
    val annotation = rootDeclaration.annotations.firstOrNull { candidate ->
      candidate.annotationType.resolve().declaration.qualifiedName?.asString() == pathableAnnotation
    }
    logger.error("[KPaths] $message", annotation ?: rootDeclaration)
  }

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
      PathKind.REFERENCE_WRITABLE -> {
        writer.appendLine("${indent}override fun setInPlace(root: $rootKotlinType, value: $valueKotlinType) {")
        writer.appendLine("${indent}  root.$identifier = value")
        writer.appendLine("${indent}}")
      }
    }
  }

  private data class PathProperty(
    val declaration: KSPropertyDeclaration,
    val kind: PathKind,
  )

  private data class CasePath(
    val declaration: KSClassDeclaration,
    val name: String,
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

  private data class GeneratedMember(
    val name: String,
    val description: String,
  )

  private data class GenerationTarget(
    val declaration: KSClassDeclaration,
    val generatedName: String,
  )

  private enum class CaseKind {
    SEALED_VARIANT,
    ENUM_ENTRY,
  }

  private enum class PathKind {
    READ_ONLY,
    COPY_WRITABLE,
    REFERENCE_WRITABLE,
  }

  private fun renderDeclaredTypeId(
    qualifiedName: String,
    parameters: List<com.google.devtools.ksp.symbol.KSTypeParameter>,
    typeTokenNames: Map<String, String>,
  ): String {
    val arguments = parameters.joinToString { parameter ->
      "dev.kpaths.core.TypeArgument.invariant(${typeTokenNames.getValue(parameter.name.asString())})"
    }
    return if (arguments.isEmpty()) {
      "dev.kpaths.core.TypeId(\"$qualifiedName\")"
    } else {
      "dev.kpaths.core.TypeId(\"$qualifiedName\", listOf($arguments))"
    }
  }

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
    val typeDeclaration = declaration
    if (typeDeclaration is KSTypeParameter) {
      val token = typeTokenNames[typeDeclaration.name.asString()]
        ?: error("No generated type token for ${typeDeclaration.name.asString()}.")
      return if (isMarkedNullable) "$token.withNullability(true)" else token
    }
    val qualifiedName = typeDeclaration.qualifiedName?.asString()
    if (qualifiedName == null) {
      val token = typeTokenNames[typeDeclaration.simpleName.asString()]
        ?: error("No generated type token for ${typeDeclaration.simpleName.asString()}.")
      return if (isMarkedNullable) "$token.withNullability(true)" else token
    }

    val arguments = arguments.joinToString { argument ->
      val type = argument.type?.resolve()?.renderTypeId(typeTokenNames)
      when (argument.variance) {
        Variance.STAR -> "dev.kpaths.core.TypeArgument.star"
        Variance.COVARIANT -> "dev.kpaths.core.TypeArgument.out($type)"
        Variance.CONTRAVARIANT -> "dev.kpaths.core.TypeArgument.`in`($type)"
        else -> "dev.kpaths.core.TypeArgument.invariant($type)"
      }
    }
    val nullable = if (isMarkedNullable) ", nullable = true" else ""
    return if (arguments.isEmpty()) {
      "dev.kpaths.core.TypeId(\"$qualifiedName\"$nullable)"
    } else {
      "dev.kpaths.core.TypeId(\"$qualifiedName\", listOf($arguments)$nullable)"
    }
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
    private const val generatedInternalPrefix = "__kpaths_"

    private val kotlinKeywords = setOf(
      "as", "as?", "break", "class", "continue", "do", "else", "false", "for", "fun", "if",
      "in", "!in", "interface", "is", "!is", "null", "object", "package", "return", "super",
      "this", "throw", "true", "try", "typealias", "val", "var", "when", "while",
    )
  }
}
