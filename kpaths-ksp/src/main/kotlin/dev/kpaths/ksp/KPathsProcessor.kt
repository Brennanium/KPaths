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
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.Modifier
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
    val generatedName = generatedObjectName(declaration, qualifiedName, packageName)
    val writableProperties = writableProperties(declaration)
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
      writer.appendLine("public object $generatedName {")
      writer.appendLine("  public const val sourceType: String = \"$qualifiedName\"")
      writableProperties.forEach { property ->
        writeDataClassPropertyPath(writer, qualifiedName, property)
      }
      writer.appendLine("}")
    }
  }

  private fun generatedObjectName(
    declaration: KSClassDeclaration,
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
    rootType: String,
    property: KSPropertyDeclaration,
  ) {
    val name = property.simpleName.asString()
    val identifier = escapeIdentifier(name)
    val valueType = property.type.resolve().renderKotlinType()
    writer.appendLine()
    writer.appendLine("  public object $identifier : dev.kpaths.core.WritableKeyPathBase<$rootType, $valueType>() {")
    writer.appendLine("    override val id: dev.kpaths.core.PathId = dev.kpaths.core.PathId.property(\"$rootType\", \"$name\")")
    writer.appendLine("    override val valueType: String = \"$valueType\"")
    writer.appendLine("    override fun get(root: $rootType): $valueType = root.$identifier")
    writer.appendLine("    override fun set(root: $rootType, value: $valueType): $rootType = root.copy($identifier = value)")
    writer.appendLine("  }")
  }

  private fun KSType.renderKotlinType(): String {
    val declarationName = declaration.qualifiedName?.asString() ?: declaration.simpleName.asString()
    val arguments = arguments
      .takeIf { it.isNotEmpty() }
      ?.joinToString(prefix = "<", postfix = ">") { argument ->
        argument.type?.resolve()?.renderKotlinType() ?: "*"
      }
      .orEmpty()
    return declarationName + arguments + if (isMarkedNullable) "?" else ""
  }

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
