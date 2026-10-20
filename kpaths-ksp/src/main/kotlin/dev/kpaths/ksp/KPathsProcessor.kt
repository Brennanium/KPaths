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
    val writableProperties = writableProperties(declaration)
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

    codeGenerator.createNewFile(
      dependencies = Dependencies(false, containingFile),
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
      writableProperties.forEach { property ->
        writeDataClassPropertyPath(
          writer = writer,
          rootKotlinType = rootKotlinType,
          rootTypeId = rootTypeId,
          property = property,
          typeTokenNames = typeTokenNames,
          capturesTypeTokens = declaration.typeParameters.isNotEmpty(),
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

  /**
   * An immutable [WritableKeyPath] can be emitted when Kotlin supplies `copy` with a named
   * argument for the property: that is precisely a primary-constructor property of a data class.
   */
  private fun writableProperties(declaration: KSClassDeclaration): List<KSPropertyDeclaration> {
    if (declaration.classKind != ClassKind.CLASS || Modifier.DATA !in declaration.modifiers) {
      return emptyList()
    }

    val constructorPropertyNames = declaration.primaryConstructor
      ?.parameters
      ?.filter { it.isVal || it.isVar }
      ?.mapNotNull { it.name?.asString() }
      ?.toSet()
      ?: return emptyList()

    return declaration.getDeclaredProperties()
      .filter { property ->
        property.simpleName.asString() in constructorPropertyNames &&
          Modifier.PRIVATE !in property.modifiers &&
          Modifier.PROTECTED !in property.modifiers
      }
      .toList()
  }

  private fun writeDataClassPropertyPath(
    writer: java.io.BufferedWriter,
    rootKotlinType: String,
    rootTypeId: String,
    property: KSPropertyDeclaration,
    typeTokenNames: Map<String, String>,
    capturesTypeTokens: Boolean,
  ) {
    val name = property.simpleName.asString()
    val identifier = escapeIdentifier(name)
    val valueKotlinType = property.type.resolve().renderKotlinType()
    val valueTypeId = property.type.resolve().renderTypeId(typeTokenNames)
    writer.appendLine()
    if (capturesTypeTokens) {
      writer.appendLine("  public val $identifier: dev.kpaths.core.WritableKeyPath<$rootKotlinType, $valueKotlinType> =")
      writer.appendLine("    object : dev.kpaths.core.WritableKeyPathBase<$rootKotlinType, $valueKotlinType>() {")
      writer.appendLine("      override val id: dev.kpaths.core.PathId = dev.kpaths.core.PathId.property($rootTypeId, \"$name\")")
      writer.appendLine("      override val valueType: dev.kpaths.core.TypeId = $valueTypeId")
      writer.appendLine("      override fun get(root: $rootKotlinType): $valueKotlinType = root.$identifier")
      writer.appendLine("      override fun set(root: $rootKotlinType, value: $valueKotlinType): $rootKotlinType = root.copy($identifier = value)")
      writer.appendLine("    }")
    } else {
      writer.appendLine("  public object $identifier : dev.kpaths.core.WritableKeyPathBase<$rootKotlinType, $valueKotlinType>() {")
      writer.appendLine("    override val id: dev.kpaths.core.PathId = dev.kpaths.core.PathId.property($rootTypeId, \"$name\")")
      writer.appendLine("    override val valueType: dev.kpaths.core.TypeId = $valueTypeId")
      writer.appendLine("    override fun get(root: $rootKotlinType): $valueKotlinType = root.$identifier")
      writer.appendLine("    override fun set(root: $rootKotlinType, value: $valueKotlinType): $rootKotlinType = root.copy($identifier = value)")
      writer.appendLine("  }")
    }
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
