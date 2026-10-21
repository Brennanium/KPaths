package dev.kpaths.ksp

import com.google.devtools.ksp.getDeclaredProperties
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
    val declarations = resolver.getSymbolsWithAnnotation(pathableAnnotation)
      .asSequence()
      .filterIsInstance<KSClassDeclaration>()

    declarations.forEach { declaration ->
      if (!declaration.validate(enableNewFeatures = true)) {
        deferred += declaration
        return@forEach
      }
      generatePaths(declaration)
    }

    return deferred
  }

  private fun generatePaths(declaration: KSClassDeclaration) {
    val qualifiedName = declaration.qualifiedName?.asString() ?: run {
      logger.error("@Pathable types must have a qualified name.", declaration)
      return
    }
    val packageName = declaration.packageName.asString()
    val generatedName = generatedObjectName(qualifiedName, packageName)
    val pathProperties = pathProperties(declaration)
    val casePaths = casePaths(declaration)
    if (casePaths.isNotEmpty() && declaration.typeParameters.isNotEmpty()) {
      logger.error("@Pathable sealed hierarchies with type parameters are not supported yet.", declaration)
      return
    }
    val typeTokenNames = declaration.typeParameters.associate { parameter ->
      parameter.name.asString() to typeTokenName(parameter.name.asString())
    }
    val rootKotlinType = qualifiedName + declaration.typeParameters
      .takeIf { it.isNotEmpty() }
      ?.joinToString(prefix = "<", postfix = ">") { it.name.asString() }
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
        writer.appendLine("public object $generatedName {")
      } else {
        val typeParameters = declaration.typeParameters.joinToString { it.name.asString() }
        val tokenParameters = typeTokenNames.values.joinToString(", ") { name ->
          "$name: dev.kpaths.core.TypeId"
        }
        val constructorParameters = typeTokenNames.values.joinToString(", ") { name ->
          "private val $name: dev.kpaths.core.TypeId"
        }
        writer.appendLine("public class $generatedName<$typeParameters> private constructor(")
        writer.appendLine("  $constructorParameters,")
        writer.appendLine(") {")
        writer.appendLine("  public companion object {")
        writer.appendLine("    public fun <$typeParameters> of($tokenParameters): $generatedName<$typeParameters> =")
        writer.appendLine("      $generatedName(${typeTokenNames.values.joinToString()})")
        val classTokenParameters = declaration.typeParameters.joinToString(", ") { parameter ->
          "${typeTokenName(parameter.name.asString())}: kotlin.reflect.KClass<${parameter.name.asString()}>"
        }
        val nonNullTypeParameters = declaration.typeParameters.joinToString(", ") { parameter ->
          "${parameter.name.asString()} : Any"
        }
        writer.appendLine("    public fun <$nonNullTypeParameters> of($classTokenParameters): $generatedName<$typeParameters> =")
        writer.appendLine("      $generatedName(${typeTokenNames.values.joinToString { "dev.kpaths.core.TypeId.from($it)" }})")
        writer.appendLine("  }")
      }
      writer.appendLine("  public val rootType: dev.kpaths.core.TypeId = $rootTypeId")
      pathProperties.forEach { property ->
        writePropertyPath(
          writer = writer,
          rootKotlinType = rootKotlinType,
          rootTypeId = rootTypeId,
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
          rootTypeId = rootTypeId,
          casePath = casePath,
        )
      }
      writer.appendLine("}")
      if (declaration.typeParameters.isNotEmpty()) {
        val typeParameters = declaration.typeParameters.joinToString { it.name.asString() }
        writer.appendLine()
        writer.appendLine("public inline fun <reified $typeParameters> $generatedName(): $generatedName<$typeParameters> =")
        writer.appendLine("  $generatedName.of(${declaration.typeParameters.joinToString { "dev.kpaths.core.TypeId.of<${it.name.asString()}>()" }})")
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

  /** Returns direct concrete variants for the first sealed-hierarchy generation slice. */
  private fun casePaths(declaration: KSClassDeclaration): List<CasePath> {
    if (Modifier.SEALED !in declaration.modifiers) {
      return emptyList()
    }

    return declaration.getSealedSubclasses().mapNotNull { caseDeclaration ->
      if (caseDeclaration.classKind !in setOf(ClassKind.CLASS, ClassKind.OBJECT)) {
        logger.error(
          "@Pathable sealed variant '${caseDeclaration.simpleName.asString()}' must be a class or object.",
          caseDeclaration,
        )
        return@mapNotNull null
      }
      if (caseDeclaration.typeParameters.isNotEmpty()) {
        logger.error(
          "@Pathable sealed variant '${caseDeclaration.simpleName.asString()}' cannot declare type parameters yet.",
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
        qualifiedName = qualifiedName,
      )
    }.toList()
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

  private fun writePropertyPath(
    writer: java.io.BufferedWriter,
    rootKotlinType: String,
    rootTypeId: String,
    pathProperty: PathProperty,
    typeTokenNames: Map<String, String>,
    capturesTypeTokens: Boolean,
    declarationIndent: String,
  ) {
    val property = pathProperty.declaration
    val name = property.simpleName.asString()
    val identifier = escapeIdentifier(name)
    val valueKotlinType = property.type.resolve().renderKotlinType()
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
    casePath: CasePath,
  ) {
    val caseIdentifier = escapeIdentifier(casePath.name.replaceFirstChar { it.lowercaseChar() })
    val caseTypeId = renderDeclaredTypeId(casePath.qualifiedName, emptyList(), emptyMap())

    writer.appendLine()
    writer.appendLine("  public object $caseIdentifier : dev.kpaths.core.CaseKeyPathBase<$rootKotlinType, ${casePath.qualifiedName}>() {")
    writer.appendLine("    override val id: dev.kpaths.core.PathId = dev.kpaths.core.PathId.case($rootTypeId, \"${casePath.name}\")")
    writer.appendLine("    override val valueType: dev.kpaths.core.TypeId = $caseTypeId")
    writer.appendLine("    override fun extract(root: $rootKotlinType): ${casePath.qualifiedName}? = root as? ${casePath.qualifiedName}")
    writer.appendLine("    override fun embed(value: ${casePath.qualifiedName}): $rootKotlinType = value")
    writer.appendLine("  }")

    val caseProperties = pathProperties(casePath.declaration)
    if (caseProperties.isEmpty()) {
      return
    }

    writer.appendLine()
    writer.appendLine("  public object ${escapeIdentifier(casePath.name + "Paths")} {")
    val caseRootTypeId = renderDeclaredTypeId(casePath.qualifiedName, emptyList(), emptyMap())
    caseProperties.forEach { property ->
      writePropertyPath(
        writer = writer,
        rootKotlinType = casePath.qualifiedName,
        rootTypeId = caseRootTypeId,
        pathProperty = property,
        typeTokenNames = emptyMap(),
        capturesTypeTokens = false,
        declarationIndent = "    ",
      )
    }
    writer.appendLine("  }")
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
    val qualifiedName: String,
  )

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

  private fun KSType.renderKotlinType(): String {
    val typeDeclaration = declaration
    if (typeDeclaration is KSTypeParameter) {
      return typeDeclaration.name.asString() + if (isMarkedNullable) "?" else ""
    }
    val declarationName = typeDeclaration.qualifiedName?.asString() ?: typeDeclaration.simpleName.asString()
    val arguments = arguments
      .takeIf { it.isNotEmpty() }
      ?.joinToString(prefix = "<", postfix = ">") { argument ->
        val type = argument.type?.resolve()?.renderKotlinType() ?: return@joinToString "*"
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
    escapeIdentifier(parameterName.replaceFirstChar { it.lowercaseChar() } + "Type")

  private fun escapeIdentifier(identifier: String): String =
    if (identifier in kotlinKeywords) "`$identifier`" else identifier

  private companion object {
    private val kotlinKeywords = setOf(
      "as", "as?", "break", "class", "continue", "do", "else", "false", "for", "fun", "if",
      "in", "!in", "interface", "is", "!is", "null", "object", "package", "return", "super",
      "this", "throw", "true", "try", "typealias", "val", "var", "when", "while",
    )
  }
}
