package dev.kpaths.compiler

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrStarProjection
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import java.nio.file.Files
import java.nio.file.Path

/**
 * A deliberately small, stable projection of the generated IR for compiler-plugin tests.
 *
 * Raw IR dumps contain offsets, fake overrides, and implementation details that change between
 * Kotlin releases. This reports only KPaths-owned declarations after the generator has completed,
 * so a fixture can assert the generated API and direct implementation shape without inspecting
 * class files by hand.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal object PathsIrSnapshot {
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
