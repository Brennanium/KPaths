package dev.kpaths.compiler.contributor

import dev.kpaths.compiler.ExperimentalKPathsCompilerApi
import dev.kpaths.compiler.KPathsGenerationOptions
import dev.kpaths.compiler.KPathsCompilerPropertyAdapter
import dev.kpaths.compiler.KPathsCompilerPropertyRecipe
import dev.kpaths.compiler.kpaths
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.extensions.DeclarationGenerationContext
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.fir.extensions.predicate.DeclarationPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.plugin.createConeType
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** Registers a deliberately tiny independent contributor to the KPaths compiler API. */
public class ContributedPathsPluginRegistrar : CompilerPluginRegistrar() {
  override val pluginId: String = "dev.kpaths.compiler-contributor"
  override val supportsK2: Boolean = true

  override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
    FirExtensionRegistrarAdapter.registerExtension(ContributedPathsFirRegistrar())
  }
}

internal class ContributedPathsFirRegistrar : FirExtensionRegistrar() {
  override fun ExtensionRegistrarContext.configurePlugin() {
    +::ContributedPathsGenerator
    +::ContributedAdapterChecks
  }
}

/** Verifies the integration lookup after source annotations have been resolved. */
@OptIn(ExperimentalKPathsCompilerApi::class, SymbolInternals::class)
internal class ContributedAdapterChecks(private val pluginSession: FirSession) : FirAdditionalCheckersExtension(pluginSession) {
  override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
    override val regularClassCheckers: Set<FirDeclarationChecker<FirRegularClass>> = setOf(object :
      FirDeclarationChecker<FirRegularClass>(MppCheckerKind.Common) {
      context(context: CheckerContext, reporter: DiagnosticReporter)
      override fun check(declaration: FirRegularClass) {
        if (declaration.symbol.classId.asSingleFqName().asString() != "dev.kpaths.compiler.fixture.SourceAdapterCell") return
        check(pluginSession.kpaths.adapterFactoryFor(declaration.symbol) ==
          ClassId.topLevel(FqName("dev.kpaths.compiler.fixture.CellPaths"))) {
          "KPaths must read a resolved source adapter annotation"
        }
      }
    })
  }
}

/**
 * Represents an external library deciding that a class it owns should have a KPaths catalog.
 *
 * The annotation itself is fixture-only. The important behavior is the direct
 * `session.kpaths.pathable(...)` call, which is the same call an external generator would make
 * for a synthetic class symbol.
 */
@OptIn(ExperimentalKPathsCompilerApi::class, DirectDeclarationsAccess::class, SymbolInternals::class)
internal class ContributedPathsGenerator(session: FirSession) : FirDeclarationGenerationExtension(session) {
  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(contributedPredicate)
  }

  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: DeclarationGenerationContext.Nested,
  ): Set<Name> {
    if (session.predicateBasedProvider.matches(contributedPredicate, classSymbol)) {
      if (classSymbol.classId.asSingleFqName().asString() == "dev.kpaths.compiler.fixture.RequestedDocument") {
        fun storage(name: String): FirClassSymbol<*> = session.symbolProvider.getClassLikeSymbolByClassId(
          ClassId.topLevel(FqName("dev.kpaths.compiler.integration.$name")),
        ) as FirClassSymbol<*>
        val packageName = FqName("dev.kpaths.compiler.integration")
        check(session.kpaths.adapterFactoryFor(storage("Cell")) ==
          ClassId.topLevel(FqName("dev.kpaths.compiler.integration.CellPathFactory")))
        check(session.kpaths.adapterFactoryFor(storage("Scalar")) ==
          ClassId(packageName, FqName("Adapters.ScalarFactory"), false))
        check(session.kpaths.adapterFactoryFor(storage("PlainStorage")) == null)
      }
      val declaration = classSymbol.fir as? FirRegularClass
      val excludedProperties = declaration?.declarations.orEmpty().filterIsInstance<FirProperty>()
        .filter { it.name.asString() == "identifier" || it.name.asString() == "revision" } +
        declaration?.declarations.orEmpty().filterIsInstance<FirRegularClass>()
          .filter { it.name.asString() == "Protected" }
          .flatMap { it.declarations.filterIsInstance<FirProperty>() }
          .filter { it.name.asString() == "value" }
      session.kpaths.pathable(
        generatedClass = classSymbol,
        options = KPathsGenerationOptions(
          catalogName = "GeneratedPaths",
          copyWritableVals = true,
          copyWritableValExclusions = excludedProperties.map { it.symbol }.toSet(),
          propertyPathAdapters = listOf(cellAdapter),
        ),
      )
    }
    return emptySet()
  }

  private companion object {
    private val cellAdapter = KPathsCompilerPropertyAdapter { context ->
      val storage = (context.storageType as? ConeClassLikeType)?.lookupTag?.classId
        ?.let(context.session.symbolProvider::getClassLikeSymbolByClassId) as? FirClassSymbol<*>
        ?: return@KPathsCompilerPropertyAdapter null
      if (context.session.kpaths.adapterFactoryFor(storage) !=
        ClassId.topLevel(FqName("dev.kpaths.compiler.integration.CellPathFactory"))) return@KPathsCompilerPropertyAdapter null
      val owner = context.property.callableId?.classId?.asSingleFqName()?.asString()
      if (owner == "dev.kpaths.compiler.fixture.AdaptedEvent.Item") {
        return@KPathsCompilerPropertyAdapter KPathsCompilerPropertyRecipe(
          publicPathType = ClassId.topLevel(FqName("dev.kpaths.compiler.fixture.EventCellPath"))
            .createConeType(context.session),
          emit = { ir ->
            val factory = ir.pluginContext.referenceFunctions(CallableId(
              FqName("dev.kpaths.compiler.fixture"), Name.identifier("makeEventCellPath"),
            )).single()
            ir.builder.irCall(factory).apply { arguments[0] = ir.defaultPath }
          },
        )
      }
      if (owner == "dev.kpaths.compiler.fixture.AdaptedBox") {
        val args = (context.rootType as? ConeClassLikeType)?.typeArguments ?: return@KPathsCompilerPropertyAdapter null
        return@KPathsCompilerPropertyAdapter KPathsCompilerPropertyRecipe(
          publicPathType = ClassId.topLevel(FqName("dev.kpaths.compiler.fixture.GenericCellPath"))
            .createConeType(context.session, Array<org.jetbrains.kotlin.fir.types.ConeTypeProjection>(args.size) { args[it] }, false),
          emit = { ir ->
            val factory = ir.pluginContext.referenceFunctions(CallableId(
              FqName("dev.kpaths.compiler.fixture"), Name.identifier("makeGenericCellPath"),
            )).single()
            val type = ((ir.generatedProperty.getter!!.returnType as IrSimpleType).arguments.single() as IrTypeProjection).type
            ir.builder.irCall(factory).apply { typeArguments[0] = type; arguments[0] = ir.defaultPath }
          },
        )
      }
      if (owner != "dev.kpaths.compiler.fixture.AdaptedDocument") return@KPathsCompilerPropertyAdapter null
      KPathsCompilerPropertyRecipe(
        publicPathType = ClassId.topLevel(FqName("dev.kpaths.compiler.fixture.CellTextPath"))
          .createConeType(context.session),
        emit = { ir ->
          val factory = ir.pluginContext.referenceFunctions(CallableId(
            FqName("dev.kpaths.compiler.fixture"), Name.identifier("makeCellTextPath"),
          )).single()
          ir.builder.irCall(factory).apply { arguments[0] = ir.defaultPath }
        },
      )
    }
    private val contributedPredicate = DeclarationPredicate.create {
      annotated(FqName("dev.kpaths.compiler.fixture.ContributedCatalog"))
    }
  }
}
