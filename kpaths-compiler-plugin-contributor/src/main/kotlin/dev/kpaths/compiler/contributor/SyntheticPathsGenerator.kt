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
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.types.toTypeProjection
import org.jetbrains.kotlin.fir.types.ConeAttributes
import org.jetbrains.kotlin.fir.types.impl.ConeTypeParameterTypeImpl
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.types.Variance

internal object SyntheticSnapshotKey : GeneratedDeclarationKey()

/** A real externally synthesized class, used to exercise KPaths' generated-owner integration. */
@OptIn(ExperimentalKPathsCompilerApi::class, SymbolInternals::class)
internal class SyntheticPathsGenerator(session: FirSession) : FirDeclarationGenerationExtension(session) {
  private val ownerId = ClassId.topLevel(FqName("dev.kpaths.compiler.fixture.SyntheticOwner"))
  private val snapshotId = ownerId.createNestedClassId(Name.identifier("Snapshot"))
  private val genericSnapshotId = ownerId.createNestedClassId(Name.identifier("GenericSnapshot"))
  private val invalidOwnerId = ClassId.topLevel(FqName("dev.kpaths.compiler.fixture.InvalidSyntheticOwner"))
  private val invalidSnapshotId = invalidOwnerId.createNestedClassId(Name.identifier("Snapshot"))
  private val titleName = Name.identifier("title")

  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>, context: DeclarationGenerationContext.Nested,
  ): Set<Name> = when (classSymbol.classId) {
    ownerId -> setOf(snapshotId.shortClassName, genericSnapshotId.shortClassName)
    invalidOwnerId -> setOf(invalidSnapshotId.shortClassName)
    snapshotId, genericSnapshotId, invalidSnapshotId -> {
      session.kpaths.pathable(classSymbol, KPathsGenerationOptions())
      session.kpaths.nestedClassifierNames(classSymbol, context)
    }
    else -> if (classSymbol.isSnapshotCatalog()) session.kpaths.nestedClassifierNames(classSymbol, context) else emptySet()
  }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>, name: Name, context: DeclarationGenerationContext.Nested,
  ): FirClassLikeSymbol<*>? = when {
    owner.classId == ownerId && name == snapshotId.shortClassName ->
      createNestedClass(owner, name, SyntheticSnapshotKey, ClassKind.OBJECT).symbol
    owner.classId == ownerId && name == genericSnapshotId.shortClassName ->
      createNestedClass(owner, name, SyntheticSnapshotKey, ClassKind.CLASS) { typeParameter(Name.identifier("T")) }.symbol
    owner.classId == invalidOwnerId && name == invalidSnapshotId.shortClassName ->
      createNestedClass(owner, name, SyntheticSnapshotKey, ClassKind.OBJECT).symbol
    owner.isSnapshot() || owner.isSnapshotCatalog() -> session.kpaths.generateNestedClassifier(owner, name, context)
    else -> null
  }

  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>, context: DeclarationGenerationContext.Member,
  ): Set<Name> = when {
    classSymbol.isSnapshot() -> setOf(SpecialNames.INIT, titleName)
    classSymbol.isSnapshotCatalog() -> session.kpaths.callableNames(classSymbol, context)
    else -> emptySet()
  }

  override fun generateConstructors(context: DeclarationGenerationContext.Member): List<FirConstructorSymbol> =
    when {
      context.owner.isSnapshot() -> listOf(createConstructor(context.owner, SyntheticSnapshotKey, true).symbol)
      context.owner.isSnapshotCatalog() -> session.kpaths.generateConstructors(context)
      else -> emptyList()
    }

  override fun generateProperties(callableId: CallableId, context: DeclarationGenerationContext.Member?): List<FirPropertySymbol> {
    val owner = context?.owner ?: return emptyList()
    if (owner.isSnapshotCatalog()) return session.kpaths.generateProperties(callableId, context)
    if (!owner.isSnapshot() || callableId.callableName != titleName) return emptyList()
    val focus = if (owner.classId == genericSnapshotId) {
      ConeTypeParameterTypeImpl(owner.fir.typeParameters.single().symbol.toLookupTag(), false, ConeAttributes.Empty)
    } else {
      val string = ClassId.topLevel(FqName("kotlin.String")).createConeType(session, emptyArray(), true)
      ClassId.topLevel(FqName("kotlin.collections.List")).createConeType(
        session, arrayOf(string.toTypeProjection(Variance.INVARIANT)), false,
      )
    }
    val storageName = if (owner.classId == invalidSnapshotId) "InvalidBinaryCell" else "BinaryCell"
    val storage = ClassId.topLevel(FqName("dev.kpaths.compiler.integration.$storageName")).createConeType(
      session, arrayOf(focus.toTypeProjection(Variance.INVARIANT)), false,
    )
    return listOf(createMemberProperty(owner, SyntheticSnapshotKey, titleName, storage, true, false).symbol)
  }

  override fun generateFunctions(callableId: CallableId, context: DeclarationGenerationContext.Member?): List<FirNamedFunctionSymbol> =
    if (context?.owner?.isSnapshotCatalog() == true) session.kpaths.generateFunctions(callableId, context) else emptyList()

  private fun FirClassSymbol<*>.isSnapshotCatalog(): Boolean =
    listOf(snapshotId, genericSnapshotId, invalidSnapshotId).any {
      classId.asSingleFqName().asString().startsWith(it.asSingleFqName().asString() + ".")
    }

  private fun FirClassSymbol<*>.isSnapshot(): Boolean = classId == snapshotId || classId == genericSnapshotId || classId == invalidSnapshotId
}
