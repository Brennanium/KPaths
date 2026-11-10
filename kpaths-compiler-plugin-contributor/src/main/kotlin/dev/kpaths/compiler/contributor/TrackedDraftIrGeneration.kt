package dev.kpaths.compiler.contributor

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irUnit
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** Implements the fixture's generated setters by calling the tracking harness. */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class TrackedDraftIrGeneration : IrGenerationExtension {
  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    moduleFragment.acceptChildrenVoid(object : IrVisitorVoid() {
      override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

      override fun visitConstructor(declaration: IrConstructor) {
        if ((declaration.origin as? IrDeclarationOrigin.GeneratedByPlugin)?.pluginKey != TrackedDraftKey) return
        val owner = declaration.parent as IrClass
        val base = requireNotNull(pluginContext.irBuiltIns.anyClass.owner.primaryConstructor)
        declaration.body = DeclarationIrBuilder(pluginContext, declaration.symbol).irBlockBody {
          +irDelegatingConstructorCall(base)
          +IrInstanceInitializerCallImpl(-1, -1, owner.symbol, declaration.returnType)
        }
      }

      override fun visitProperty(declaration: IrProperty) {
        if ((declaration.origin as? IrDeclarationOrigin.GeneratedByPlugin)?.pluginKey != TrackedDraftKey) return
        val suffix = declaration.name.asString().replaceFirstChar { it.uppercaseChar() }
        fun function(prefix: String) = pluginContext.referenceFunctions(CallableId(
          FqName("dev.kpaths.compiler.fixture"), Name.identifier(prefix + suffix),
        )).single()
        val getter = requireNotNull(declaration.getter)
        val getBuilder = DeclarationIrBuilder(pluginContext, getter.symbol)
        getter.body = getBuilder.irBlockBody {
          +irReturn(irCall(function("readDraft")).apply {
            arguments[0] = irGet(requireNotNull(getter.dispatchReceiverParameter))
          })
        }
        val setter = requireNotNull(declaration.setter)
        val setBuilder = DeclarationIrBuilder(pluginContext, setter.symbol)
        setter.body = setBuilder.irBlockBody {
          +irCall(function("writeDraft")).apply {
            arguments[0] = irGet(requireNotNull(setter.dispatchReceiverParameter))
            arguments[1] = irGet(setter.parameters.single { it.kind == IrParameterKind.Regular })
          }
          +irReturn(irUnit())
        }
      }
    })
  }
}
