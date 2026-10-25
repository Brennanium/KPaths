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
import dev.kpaths.annotations.CaseName
import dev.kpaths.annotations.Pathable
import dev.kpaths.annotations.PathName
import dev.kpaths.annotations.PathsName

private val pathableAnnotation = Pathable::class.qualifiedName!!
private val caseNameAnnotation = CaseName::class.qualifiedName!!
private val pathNameAnnotation = PathName::class.qualifiedName!!
private val pathsNameAnnotation = PathsName::class.qualifiedName!!

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
        generatePaths(target.declaration, target.generatedName, resolver)
      }
    }

    return deferred
  }

  private fun generatePaths(
    declaration: KSClassDeclaration,
    generatedName: String,
    resolver: Resolver,
  ) {
    val qualifiedName = declaration.qualifiedName?.asString() ?: run {
      logger.error("@Pathable types must have a qualified name.", declaration)
      return
    }
    val packageName = declaration.packageName.asString()
    val generatedIdentifier = escapeIdentifier(generatedName)
    val pathProperties = pathProperties(declaration)
    val casePaths = casePaths(declaration)
    val companionPaths = companionPathsExtension(declaration, pathProperties)
    if (!validateGeneratedMemberNames(declaration, pathProperties, casePaths, companionPaths, resolver)) {
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
    val resolvedCasePaths = casePaths.mapNotNull { casePath ->
      resolveCaseType(declaration, typeTokenNames, casePath)?.let { resolvedCaseType ->
        ResolvedCasePath(casePath, resolvedCaseType)
      }
    }
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
        val typeParameters = renderTypeParameters(declaration.typeParameters)
        val tokenParameters = declaration.typeParameters.joinToString(", ") { parameter ->
          "${typeTokenArgumentNames.getValue(parameter.name.asString())}: dev.kpaths.core.TypeId"
        }
        val constructorParameters = typeTokenNames.values.joinToString(", ") { name ->
          "private val $name: dev.kpaths.core.TypeId"
        }
        writer.appendLine("public class $generatedIdentifier<${typeParameters.declaration}> private constructor(")
        writer.appendLine("  $constructorParameters,")
        writer.appendLine(")${typeParameters.whereClause} {")
        writer.appendLine("  public companion object {")
        writer.appendLine("    public fun <${typeParameters.declaration}> of($tokenParameters): $generatedIdentifier<${typeParameters.names}>${typeParameters.whereClause} =")
        writer.appendLine("      $generatedIdentifier(${declaration.typeParameters.joinToString { typeTokenArgumentNames.getValue(it.name.asString()) }})")
        val classTokenParameters = declaration.typeParameters.joinToString(", ") { parameter ->
          "${typeTokenClassArgumentName(parameter.name.asString())}: kotlin.reflect.KClass<${escapeIdentifier(parameter.name.asString())}>"
        }
        val nonNullTypeParameters = renderTypeParameters(declaration.typeParameters, forceNonNull = true)
        writer.appendLine("    public fun <${nonNullTypeParameters.declaration}> of($classTokenParameters): $generatedIdentifier<${nonNullTypeParameters.names}>${nonNullTypeParameters.whereClause} =")
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
      if (companionPaths) {
        writeCompanionPathsView(
          writer = writer,
          rootKotlinType = rootKotlinType,
          pathProperties = pathProperties,
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
          rootKotlinType = rootKotlinType,
          resolvedCasePaths = resolvedCasePaths,
        )
      }
      writer.appendLine("}")
      if (companionPaths) {
        writeCompanionPathsExtension(
          writer = writer,
          rootType = renderKotlinQualifiedName(qualifiedName),
          generatedIdentifier = generatedIdentifier,
          generatedName = generatedName,
          pathProperties = pathProperties,
        )
      }
      if (declaration.typeParameters.isNotEmpty()) {
        val typeParameters = renderTypeParameters(declaration.typeParameters, reified = true)
        writer.appendLine()
        writer.appendLine("public inline fun <${typeParameters.declaration}> $generatedIdentifier(): $generatedIdentifier<${typeParameters.names}>${typeParameters.whereClause} =")
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
          defaultName = sourceName + "Paths",
        ) ?: return@mapNotNull null
        val propertyPaths = pathProperties(caseDeclaration)
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
          qualifiedName = qualifiedName,
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
    if (configuredName.isEmpty()) {
      logger.error("[KPaths] $annotationName requires a non-empty Kotlin identifier.", declaration)
      return null
    }
    if (configuredName.startsWith(generatedInternalPrefix)) {
      logger.error(
        "[KPaths] $annotationName value '$configuredName' uses the reserved " +
          "'$generatedInternalPrefix' prefix for KPaths-generated implementation details.",
        declaration,
      )
      return null
    }
    if (!configuredName.isKotlinIdentifier()) {
      logger.error(
        "[KPaths] $annotationName value '$configuredName' must be a single Kotlin identifier, " +
          "without a package or dot.",
        declaration,
      )
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

  /** Classifies Kotlin properties into the library's explicit value-style and reference-style APIs. */
  private fun pathProperties(declaration: KSClassDeclaration): List<PathProperty> {
    if (declaration.classKind != ClassKind.CLASS) {
      return emptyList()
    }

    val accessibleProperties = declaration.getDeclaredProperties()
      .filter(::isAccessible)
      .toList()

    if (Modifier.DATA !in declaration.modifiers) {
      return accessibleProperties.mapNotNull { property ->
        val generatedName = configuredPathMemberName(property) ?: return@mapNotNull null
        PathProperty(
          declaration = property,
          generatedName = generatedName,
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
      val generatedName = configuredPathMemberName(property) ?: return@mapNotNull null
      if (property.isMutable && property.simpleName.asString() in constructorPropertyNames) {
        PathProperty(property, generatedName, PathKind.COPY_WRITABLE)
      } else if (property.isMutable && isSetterAccessible(property)) {
        PathProperty(property, generatedName, PathKind.COPY_THEN_MUTATE)
      } else if (property.isMutable) {
        logger.error(
          "@Pathable data-class property '${property.simpleName.asString()}' must be declared in the primary constructor or have an accessible setter to receive a copy-based writable path.",
          property,
        )
        null
      } else {
        PathProperty(property, generatedName, PathKind.READ_ONLY)
      }
    }
  }

  /** Returns the constructor properties that can form a lossless generated case payload path. */
  private fun generatedCasePayload(casePath: CasePath): CasePayload? {
    if (
      casePath.kind != CaseKind.SEALED_VARIANT ||
        Modifier.DATA !in casePath.declaration.modifiers
    ) {
      return null
    }
    val constructor = casePath.declaration.primaryConstructor ?: return null
    if (Modifier.PRIVATE in constructor.modifiers || Modifier.PROTECTED in constructor.modifiers) {
      return null
    }
    val parameters = constructor.parameters
    if (parameters.size !in 1..3 || parameters.any { !it.isVal && !it.isVar }) {
      return null
    }
    val propertiesByName = casePath.propertyPaths.associateBy { property ->
      property.declaration.simpleName.asString()
    }
    val properties = parameters.mapNotNull { parameter ->
      parameter.name?.asString()?.let(propertiesByName::get)
    }
    if (properties.size != parameters.size) {
      return null
    }
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
  ): Boolean {
    if (
      declaration.typeParameters.isNotEmpty() ||
        Modifier.SEALED in declaration.modifiers ||
        declaration.classKind != ClassKind.CLASS ||
        pathProperties.isEmpty()
    ) {
      return false
    }
    val companion = declaration.declarations
      .filterIsInstance<KSClassDeclaration>()
      .firstOrNull(KSClassDeclaration::isCompanionObject)
      ?: return false
    return Modifier.PRIVATE !in companion.modifiers &&
      Modifier.PROTECTED !in companion.modifiers &&
      Modifier.INTERNAL !in companion.modifiers
  }

  private fun writeCompanionPathsView(
    writer: java.io.BufferedWriter,
    rootKotlinType: String,
    pathProperties: List<PathProperty>,
  ) {
    writer.appendLine()
    writer.appendLine("  /** Instance-facing view used by the generated companion [Paths] extension. */")
    writer.appendLine("  public interface CompanionView {")
    pathProperties.forEach { pathProperty ->
      val property = pathProperty.declaration
      val identifier = escapeIdentifier(pathProperty.generatedName)
      val valueType = property.type.resolve().renderKotlinType()
      writer.appendLine("    public val $identifier: ${pathInterface(pathProperty.kind)}<$rootKotlinType, $valueType>")
    }
    writer.appendLine("  }")
  }

  private fun writeCompanionPathsExtension(
    writer: java.io.BufferedWriter,
    rootType: String,
    generatedIdentifier: String,
    generatedName: String,
    pathProperties: List<PathProperty>,
  ) {
    val viewType = "$generatedIdentifier.CompanionView"
    val backingName = escapeIdentifier(generatedInternalPrefix + generatedName + "CompanionView")
    writer.appendLine()
    writer.appendLine("private val $backingName: $viewType = object : $viewType {")
    pathProperties.forEach { pathProperty ->
      val property = pathProperty.declaration
      val identifier = escapeIdentifier(pathProperty.generatedName)
      val valueType = property.type.resolve().renderKotlinType()
      writer.appendLine("  override val $identifier: ${pathInterface(pathProperty.kind)}<$rootType, $valueType>")
      writer.appendLine("    get() = $generatedIdentifier.$identifier")
    }
    writer.appendLine("}")
    writer.appendLine()
    writer.appendLine("/** Returns the generated paths for $rootType. */")
    writer.appendLine("public val $rootType.Companion.Paths: $viewType")
    writer.appendLine("  get() = $backingName")
  }

  /** Rejects ambiguous public members instead of silently changing the generated API. */
  @OptIn(KspExperimental::class)
  private fun validateGeneratedMemberNames(
    declaration: KSClassDeclaration,
    pathProperties: List<PathProperty>,
    casePaths: List<CasePath>,
    companionPaths: Boolean,
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

    if (companionPaths) {
      val companion = declaration.declarations
        .filterIsInstance<KSClassDeclaration>()
        .first(KSClassDeclaration::isCompanionObject)
      if (companion.declarations.any { it.simpleName.asString() == "Paths" }) {
        reportPathableError(
          declaration,
          "Cannot generate ${declaration.simpleName.asString()}.Companion.Paths because the companion " +
            "already declares a member named 'Paths'. Rename that member or remove the companion object.",
        )
        return false
      }
      val existingExtension = resolver.getDeclarationsFromPackage(declaration.packageName.asString())
        .filterIsInstance<KSPropertyDeclaration>()
        .firstOrNull { property ->
          property.parentDeclaration == null &&
            property.simpleName.asString() == "Paths" &&
            (property.extensionReceiver?.resolve()?.declaration as? KSClassDeclaration)
              ?.let { receiver ->
                receiver.isCompanionObject && receiver.parentDeclaration == declaration
              } == true
        }
      if (existingExtension != null) {
        reportPathableError(
          declaration,
          "Cannot generate ${declaration.simpleName.asString()}.Companion.Paths because the top-level " +
            "extension '${existingExtension.qualifiedName?.asString()}' already declares it. Rename that " +
            "extension or remove the companion object.",
        )
        return false
      }
      members += GeneratedMember(
        name = "CompanionView",
        description = "the generated companion-path view type",
      )
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
      .filter { it.sourceName.startsWith(generatedInternalPrefix) }
      .forEach { casePath ->
        reportPathableError(
          declaration,
          "Cannot generate paths because case '${casePath.sourceName}' uses the reserved " +
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
    val identifier = escapeIdentifier(pathProperty.generatedName)
    val sourceIdentifier = escapeIdentifier(name)
    val valueKotlinType = property.type.resolve().renderKotlinType(typeParameterSubstitutions)
    val valueTypeId = property.type.resolve().renderTypeId(typeTokenNames)
    val pathInterface = pathInterface(pathProperty.kind)
    val pathBase = when (pathProperty.kind) {
      PathKind.READ_ONLY -> "dev.kpaths.core.KeyPathBase"
      PathKind.COPY_WRITABLE -> "dev.kpaths.core.WritableKeyPathBase"
      PathKind.COPY_THEN_MUTATE -> "dev.kpaths.core.WritableKeyPathBase"
      PathKind.REFERENCE_WRITABLE -> "dev.kpaths.core.ReferenceWritableKeyPathBase"
    }
    writer.appendLine()
    if (capturesTypeTokens) {
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
      writer.appendLine("${indent}public object $memberIdentifier : $pathBase<$rootKotlinType, $focusKotlinType>() {")
      writeCasePayloadPathBody(
        writer = writer,
        indent = "$indent  ",
        rootKotlinType = rootKotlinType,
        rootTypeId = rootTypeId,
        casePath = casePath,
        resolvedCaseType = resolvedCaseType,
        properties = properties,
        focusKotlinType = focusKotlinType,
        focusTypeId = focusTypeId,
      )
      writer.appendLine("${indent}}")
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
    if (properties.size !in 2..3) return
    properties.forEachIndexed { targetIndex, target ->
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
    rootKotlinType: String,
    resolvedCasePaths: List<ResolvedCasePath>,
  ) {
    val partialCasePathType = "dev.kpaths.core.PartialCaseKeyPath<$rootKotlinType>"
    val paths = resolvedCasePaths.joinToString { resolvedCasePath ->
      escapeIdentifier(resolvedCasePath.casePath.generatedCaseName)
    }

    writer.appendLine()
    writer.appendLine("  public val allCasePaths: kotlin.collections.List<$partialCasePathType> =")
    writer.appendLine("    kotlin.collections.listOf($paths)")
    writer.appendLine()
    writer.appendLine("  public fun caseOf(root: $rootKotlinType): $partialCasePathType {")
    resolvedCasePaths.forEach { resolvedCasePath ->
      val identifier = escapeIdentifier(resolvedCasePath.casePath.generatedCaseName)
      writer.appendLine("    if ($identifier.matches(root)) return $identifier")
    }
    writer.appendLine("    error(\"No generated case path matched the supplied ${rootKotlinType.substringBefore('<')} value.\")")
    writer.appendLine("  }")
  }

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
      if (caseDeclaration.typeParameters.isNotEmpty()) {
        reportPathableError(
          rootDeclaration,
          "Cannot generate '${casePath.sourceName}' because this non-generic sealed root has a generic variant. " +
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
        "Cannot generate '${casePath.sourceName}' because KPaths could not resolve its direct $rootQualifiedName supertype.",
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
            "Cannot generate '${casePath.sourceName}' because its type parameter '$caseParameterName' " +
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
          "Cannot generate '${casePath.sourceName}' because it implements $rootQualifiedName<$renderedArgument> " +
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
        "Cannot generate '${casePath.sourceName}' because its type parameter(s) " +
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

  private data class GeneratedMember(
    val name: String,
    val description: String,
  )

  private data class RenderedTypeParameters(
    val declaration: String,
    val names: String,
    val whereClause: String,
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
    COPY_THEN_MUTATE,
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
