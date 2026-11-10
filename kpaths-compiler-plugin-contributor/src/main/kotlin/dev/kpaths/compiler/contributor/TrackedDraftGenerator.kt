package dev.kpaths.compiler.contributor

import dev.kpaths.compiler.ExperimentalKPathsCompilerApi
import dev.kpaths.compiler.KPathsGenerationOptions
import dev.kpaths.compiler.kpaths
import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.DeclarationGenerationContext
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.plugin.createConeType
import org.jetbrains.kotlin.fir.plugin.createConstructor
import org.jetbrains.kotlin.fir.plugin.createMemberProperty
import org.jetbrains.kotlin.fir.plugin.createNestedClass
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

internal object TrackedDraftKey : GeneratedDeclarationKey()

/** Independently synthesizes a mutable type with a projected child property. */
@OptIn(ExperimentalKPathsCompilerApi::class)
internal class TrackedDraftGenerator(session: FirSession) : FirDeclarationGenerationExtension(session) {
  private val rootId = ClassId.topLevel(FqName("dev.kpaths.compiler.fixture.TrackedRecord"))
  private val draftId = rootId.createNestedClassId(Name.identifier("Draft"))
  private val nameProperty = Name.identifier("name")
  private val addressProperty = Name.identifier("address")

  override fun getNestedClassifiersNames(classSymbol: FirClassSymbol<*>, context: DeclarationGenerationContext.Nested): Set<Name> =
    when {
      classSymbol.classId == rootId -> setOf(draftId.shortClassName)
      classSymbol.classId == draftId -> {
        session.kpaths.pathable(classSymbol, KPathsGenerationOptions())
        session.kpaths.nestedClassifierNames(classSymbol, context)
      }
      classSymbol.isDraftCatalog() -> session.kpaths.nestedClassifierNames(classSymbol, context)
      else -> emptySet()
    }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>, name: Name, context: DeclarationGenerationContext.Nested,
  ): FirClassLikeSymbol<*>? = when {
    owner.classId == rootId && name == draftId.shortClassName ->
      createNestedClass(owner, name, TrackedDraftKey, ClassKind.CLASS).symbol
    owner.classId == draftId || owner.isDraftCatalog() -> session.kpaths.generateNestedClassifier(owner, name, context)
    else -> null
  }

  override fun getCallableNamesForClass(classSymbol: FirClassSymbol<*>, context: DeclarationGenerationContext.Member): Set<Name> =
    when {
      classSymbol.classId == draftId -> setOf(SpecialNames.INIT, nameProperty, addressProperty)
      classSymbol.isDraftCatalog() -> session.kpaths.callableNames(classSymbol, context)
      else -> emptySet()
    }

  override fun generateConstructors(context: DeclarationGenerationContext.Member): List<FirConstructorSymbol> = when {
    context.owner.classId == draftId -> listOf(createConstructor(context.owner, TrackedDraftKey, true).symbol)
    context.owner.isDraftCatalog() -> session.kpaths.generateConstructors(context)
    else -> emptyList()
  }

  override fun generateProperties(id: CallableId, context: DeclarationGenerationContext.Member?): List<FirPropertySymbol> {
    val owner = context?.owner ?: return emptyList()
    if (owner.isDraftCatalog()) return session.kpaths.generateProperties(id, context)
    if (owner.classId != draftId) return emptyList()
    val type = when (id.callableName) {
      nameProperty -> ClassId.topLevel(FqName("kotlin.String"))
      addressProperty -> ClassId.topLevel(FqName("dev.kpaths.compiler.fixture.DraftAddress"))
      else -> return emptyList()
    }.createConeType(session)
    return listOf(createMemberProperty(owner, TrackedDraftKey, id.callableName, type, false, false).symbol)
  }

  override fun generateFunctions(id: CallableId, context: DeclarationGenerationContext.Member?): List<FirNamedFunctionSymbol> =
    if (context?.owner?.isDraftCatalog() == true) session.kpaths.generateFunctions(id, context) else emptyList()

  private fun FirClassSymbol<*>.isDraftCatalog(): Boolean =
    classId.asSingleFqName().asString().startsWith(draftId.asSingleFqName().asString() + ".")
}
