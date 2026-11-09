package dev.kpaths.compiler.contributor

import dev.kpaths.compiler.ExperimentalKPathsCompilerApi
import dev.kpaths.compiler.KPathsGenerationOptions
import dev.kpaths.compiler.kpaths
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.DeclarationGenerationContext
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.fir.extensions.predicate.DeclarationPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
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
  }
}

/**
 * Represents an external library deciding that a class it owns should have a KPaths catalog.
 *
 * The annotation itself is fixture-only. The important behavior is the direct
 * `session.kpaths.pathable(...)` call, which is the same call an external generator would make
 * for a synthetic class symbol.
 */
@OptIn(ExperimentalKPathsCompilerApi::class)
internal class ContributedPathsGenerator(session: FirSession) : FirDeclarationGenerationExtension(session) {
  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(contributedPredicate)
  }

  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: DeclarationGenerationContext.Nested,
  ): Set<Name> {
    if (session.predicateBasedProvider.matches(contributedPredicate, classSymbol)) {
      session.kpaths.pathable(
        generatedClass = classSymbol,
        options = KPathsGenerationOptions(
          catalogName = "GeneratedPaths",
          copyWritableVals = true,
        ),
      )
    }
    return emptySet()
  }

  private companion object {
    private val contributedPredicate = DeclarationPredicate.create {
      annotated(FqName("dev.kpaths.compiler.fixture.ContributedCatalog"))
    }
  }
}
