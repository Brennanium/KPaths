package dev.kpaths.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.name.CallableId
import java.util.concurrent.ConcurrentHashMap

/** Describes a replacement for a generated property path in a cooperating compiler plugin. */
@ExperimentalKPathsCompilerApi
public fun interface KPathsCompilerPropertyAdapter {
  /**
   * Return null to leave this property to the next adapter or KPaths' normal generation.
   * The compiler may query the same property more than once, including for diagnostics, so
   * implementations must not rely on invocation count or side effects.
   */
  public fun pathRecipe(context: KPathsCompilerPropertyContext): KPathsCompilerPropertyRecipe?
}

/** Resolved FIR information for a property, with types substituted into its generated catalog. */
@ExperimentalKPathsCompilerApi
public data class KPathsCompilerPropertyContext(
  public val session: FirSession,
  public val property: FirPropertySymbol,
  public val rootType: ConeKotlinType,
  public val storageType: ConeKotlinType,
  /** The ordinary KPaths property type that will be supplied to [KPathsCompilerPropertyIrContext.defaultPath]. */
  public val defaultPathType: ConeKotlinType,
)

/** A public FIR path type and an IR factory that wraps KPaths' normal generated path. */
@ExperimentalKPathsCompilerApi
public data class KPathsCompilerPropertyRecipe(
  public val publicPathType: ConeKotlinType,
  /** Whether the recipe requires a directly generated path rather than a property-reference fallback. */
  public val requiresDirectPath: Boolean = false,
  public val emit: (KPathsCompilerPropertyIrContext) -> IrExpression,
)

/** IR context for a selected recipe. The normal path has already been constructed. */
@ExperimentalKPathsCompilerApi
public data class KPathsCompilerPropertyIrContext(
  public val pluginContext: IrPluginContext,
  public val builder: DeclarationIrBuilder,
  public val catalog: IrClass,
  public val root: IrClass,
  public val sourceProperty: IrProperty,
  public val generatedProperty: IrProperty,
  public val defaultPath: IrExpression,
)

/** Apply integration recipes first, then the declarative contract, consistently in FIR and diagnostics. */
@OptIn(ExperimentalKPathsCompilerApi::class)
internal fun selectCompilerPropertyAdapter(
  context: KPathsCompilerPropertyContext,
  adapters: List<KPathsCompilerPropertyAdapter>,
): CompilerAdapterAnalysis {
  adapters.firstNotNullOfOrNull { it.pathRecipe(context) }?.let {
    return if (it.publicPathType.isAdapterKeyPath(context.session)) CompilerAdapterAnalysis.Available(it)
    else CompilerAdapterAnalysis.Invalid(dev.kpaths.codegen.model.KPathsAdapterDiagnostics.INVALID_RECIPE_TYPE, isIntegration = true)
  }
  return declarativeCompilerAdapter(context.session, context.rootType, context.storageType)
}

/** Shared only between FIR sessions and IR generation for one compiler invocation. */
@OptIn(ExperimentalKPathsCompilerApi::class)
internal class KPathsAdapterBridge {
  internal data class Entry(val recipe: KPathsCompilerPropertyRecipe, val defaultPathClass: org.jetbrains.kotlin.name.ClassId)
  private val recipes = ConcurrentHashMap<CallableId, Entry>()
  fun record(id: CallableId, recipe: KPathsCompilerPropertyRecipe, defaultPathClass: org.jetbrains.kotlin.name.ClassId) {
    recipes[id] = Entry(recipe, defaultPathClass)
  }
  fun find(id: CallableId): Entry? = recipes[id]
}
