package dev.kpaths.compiler.contributor

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** Fills the fixture contributor's own synthetic declarations; KPaths handles its catalog. */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class SyntheticPathsIrGeneration : IrGenerationExtension {
  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    moduleFragment.acceptChildrenVoid(object : IrVisitorVoid() {
      override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

      override fun visitConstructor(declaration: IrConstructor) {
        if ((declaration.origin as? IrDeclarationOrigin.GeneratedByPlugin)?.pluginKey != SyntheticSnapshotKey) return
        val owner = declaration.parent as IrClass
        val anyConstructor = requireNotNull(pluginContext.irBuiltIns.anyClass.owner.primaryConstructor)
        val builder = DeclarationIrBuilder(pluginContext, declaration.symbol)
        declaration.body = builder.irBlockBody {
          +irDelegatingConstructorCall(anyConstructor)
          +IrInstanceInitializerCallImpl(-1, -1, owner.symbol, declaration.returnType)
        }
      }

      override fun visitProperty(declaration: IrProperty) {
        if ((declaration.origin as? IrDeclarationOrigin.GeneratedByPlugin)?.pluginKey != SyntheticSnapshotKey) return
        val getter = requireNotNull(declaration.getter)
        val owner = declaration.parent as IrClass
        val isGeneric = owner.typeParameters.isNotEmpty()
        val factory = pluginContext.referenceFunctions(CallableId(
          FqName("dev.kpaths.compiler.fixture"), Name.identifier(if (isGeneric) "makeSyntheticGenericStorage" else "makeSyntheticStorage"),
        )).single()
        val builder = DeclarationIrBuilder(pluginContext, getter.symbol)
        getter.body = builder.irBlockBody {
          +irReturn(irCall(factory).apply {
            if (isGeneric) typeArguments[0] = IrSimpleTypeImpl(owner.typeParameters.single().symbol, false, emptyList(), emptyList())
          })
        }
      }
    })
  }
}
