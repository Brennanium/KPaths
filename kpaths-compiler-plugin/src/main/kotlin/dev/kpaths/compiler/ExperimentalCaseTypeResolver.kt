package dev.kpaths.compiler

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirTypeAlias
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.SupertypeSupplier
import org.jetbrains.kotlin.fir.resolve.TypeResolutionConfiguration
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.typeResolver
import org.jetbrains.kotlin.fir.scopes.createImportingScopes
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeAliasSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirUserTypeRef
import org.jetbrains.kotlin.fir.types.builder.buildUserTypeRef
import org.jetbrains.kotlin.fir.types.impl.FirQualifierPartImpl
import org.jetbrains.kotlin.fir.types.impl.FirTypeArgumentListImpl
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.name.ClassId

/** Experiment: resolve written supertypes without triggering supertype resolution. */
@OptIn(SymbolInternals::class)
internal class ExperimentalCaseTypeResolver(private val session: FirSession) {
  private val scopes = ScopeSession()

  fun isDirectCase(candidate: FirRegularClassSymbol, root: ClassId): Boolean {
    val file = session.firProvider.getFirClassifierContainerFileIfAny(candidate.classId) ?: return false
    val containers = mutableListOf<FirRegularClass>()
    var current: FirRegularClassSymbol? = candidate
    while (current != null) {
      containers += current.fir
      val outer = current.classId.outerClassId ?: break
      current = session.symbolProvider.getClassLikeSymbolByClassId(outer) as? FirRegularClassSymbol
    }
    val nestedScopes = containers.mapNotNull { klass ->
      runCatching { klass.scopeProvider.getNestedClassifierScope(klass, session, scopes) }.getOrNull()
    }
    val configuration = TypeResolutionConfiguration(nestedScopes + createImportingScopes(file, session, scopes).asReversed(), containers, file)
    return candidate.fir.superTypeRefs.any { ref ->
      val bare = if (ref is FirUserTypeRef && ref.qualifier.any { it.typeArgumentList.typeArguments.isNotEmpty() }) {
        buildUserTypeRef {
          source = ref.source
          isMarkedNullable = ref.isMarkedNullable
          qualifier.addAll(ref.qualifier.map { FirQualifierPartImpl(it.source, it.name, FirTypeArgumentListImpl(it.source)) })
        }
      } else ref
      val type = (ref as? FirResolvedTypeRef)?.coneType ?: runCatching {
        session.typeResolver.resolveType(bare, configuration, true, false, false, ResolvedOnly, true).type
      }.getOrNull()
      val alias = type?.classId?.let(session.symbolProvider::getClassLikeSymbolByClassId) as? FirTypeAliasSymbol
      val expanded = alias?.fir?.expandedTypeRef?.let { expansion ->
        val aliasFile = session.firProvider.getFirClassifierContainerFileIfAny(alias.classId)
        val aliasConfiguration = aliasFile?.let {
          TypeResolutionConfiguration(createImportingScopes(it, session, scopes).asReversed(), emptyList(), it)
        }
        (expansion as? FirResolvedTypeRef)?.coneType ?: runCatching {
          aliasConfiguration?.let {
            session.typeResolver.resolveType(expansion, it, true, false, false, ResolvedOnly, true).type
          }
        }.getOrNull()
      }
      type?.classId == root || expanded?.classId == root
    }
  }

  private object ResolvedOnly : SupertypeSupplier() {
    override fun forClass(firClass: FirClass, useSiteSession: FirSession): List<ConeClassLikeType> =
      firClass.superTypeRefs.mapNotNull { (it as? FirResolvedTypeRef)?.coneType as? ConeClassLikeType }

    override fun expansionForTypeAlias(typeAlias: FirTypeAlias, useSiteSession: FirSession): ConeClassLikeType? =
      (typeAlias.expandedTypeRef as? FirResolvedTypeRef)?.coneType as? ConeClassLikeType
  }
}
