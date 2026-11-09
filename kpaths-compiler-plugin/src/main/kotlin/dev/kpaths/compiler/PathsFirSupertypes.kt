package dev.kpaths.compiler

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.declaredProperties
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension
import org.jetbrains.kotlin.fir.extensions.ExperimentalSupertypesGenerationApi
import org.jetbrains.kotlin.fir.extensions.predicate.DeclarationPredicate
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.plugin.createConeType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.fir.types.toTypeProjection
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.types.Variance

/** Adds `CasePathable<Root>` to a concrete, catalog-bearing sealed root or enum. */
@OptIn(
  DirectDeclarationsAccess::class,
  ExperimentalKPathsCompilerApi::class,
  ExperimentalSupertypesGenerationApi::class,
  SymbolInternals::class,
)
internal class PathsRootCasePathableSupertypeGenerator(
  session: FirSession,
) : FirSupertypeGenerationExtension(session) {
  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(pathablePredicate)
    register(metaPathablePredicate)
  }

  override fun needTransformSupertypes(
    declaration: org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration,
  ): Boolean = true

  override fun computeAdditionalSupertypes(
    classLikeDeclaration: org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration,
    resolvedSupertypes: List<FirResolvedTypeRef>,
    typeResolver: TypeResolveService,
  ): List<org.jetbrains.kotlin.fir.types.ConeKotlinType> {
    val regular = classLikeDeclaration as? FirRegularClass ?: return emptyList()
    if (!regular.canGenerateCasePathableConformance()) return emptyList()
    if (resolvedSupertypes.any {
        (it.coneType as? ConeClassLikeType)?.lookupTag?.classId == casePathableClassId
      }
    ) return emptyList()
    val rootType = regular.symbol.classId.createConeType(session)
    val casePathableType = casePathableClassId.createConeType(
      session,
      arrayOf(rootType.toTypeProjection(Variance.INVARIANT)),
      false,
    )
    // The CLI compiler can leave an enum's already-resolved Enum<E> supertypes
    // untouched when applying computed additions. Update FIR as well as returning
    // the type: IDE analysis can apply the returned list independently. This approach
    // follows the enum workaround in obabichev/kotlin-structural-typing.
    if (regular.classKind == ClassKind.ENUM_CLASS && regular.superTypeRefs.none {
        ((it as? FirResolvedTypeRef)?.coneType as? ConeClassLikeType)?.lookupTag?.classId == casePathableClassId
      }
    ) {
      regular.replaceSuperTypeRefs(regular.superTypeRefs + buildResolvedTypeRef { coneType = casePathableType })
    }
    return listOf(casePathableType)
  }

  private fun FirRegularClass.canGenerateCasePathableConformance(): Boolean {
    if (typeParameters.isNotEmpty()) return false
    // This is called while resolving supertypes, so use source declarations rather
    // than `declaredProperties`, which includes generated members and can recurse.
    if (declarations.filterIsInstance<FirProperty>().any {
        it.origin == FirDeclarationOrigin.Source && it.name.asString() == "case"
      }
    ) return false
    val isPathable = symbol.classId in pathableClassIds ||
      session.kpaths.optionsFor(symbol) != null ||
      session.predicateBasedProvider.matches(metaPathablePredicate, symbol)
    if (!isPathable) return false
    return classKind == ClassKind.ENUM_CLASS ||
      (status.modality == Modality.SEALED && declarations.any { it is FirRegularClass })
  }

  private companion object {
    private val pathableAnnotationName = FqName("dev.kpaths.annotations.Pathable")
    private val pathablePredicate = LookupPredicate.create {
      annotated(pathableAnnotationName)
    }
    private val kpathsPathableAnnotationName = FqName("dev.kpaths.annotations.KPathsPathable")
    private val metaPathablePredicate = DeclarationPredicate.create {
      metaAnnotated(listOf(kpathsPathableAnnotationName), false)
    }
    private val casePathableClassId = org.jetbrains.kotlin.name.ClassId.topLevel(
      FqName("dev.kpaths.core.CasePathable"),
    )
  }

  private val pathableClassIds by lazy {
    session.predicateBasedProvider
      .getSymbolsByPredicate(pathablePredicate)
      .filterIsInstance<FirClassSymbol<*>>()
      .mapTo(mutableSetOf()) { it.classId }
  }
}

/** Adds `CaseKeyPathBase<Root, Case>` to generated concrete case objects. */
@OptIn(
  DirectDeclarationsAccess::class,
  ExperimentalKPathsCompilerApi::class,
  ExperimentalSupertypesGenerationApi::class,
  SymbolInternals::class,
)
internal class PathsCaseSupertypeGenerator(
  session: FirSession,
) : FirSupertypeGenerationExtension(session) {
  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(pathableOrNestedPredicate)
  }

  override fun needTransformSupertypes(declaration: org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration): Boolean =
    true

  override fun computeAdditionalSupertypes(
    classLikeDeclaration: org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration,
    resolvedSupertypes: List<FirResolvedTypeRef>,
    typeResolver: TypeResolveService,
  ): List<org.jetbrains.kotlin.fir.types.ConeKotlinType> {
    val regular = classLikeDeclaration as? FirRegularClass ?: return emptyList()
    return regular.generatedCasePathSupertype()
  }

  override fun computeAdditionalSupertypesForGeneratedNestedClass(
    klass: FirRegularClass,
    typeResolver: TypeResolveService,
  ): List<org.jetbrains.kotlin.fir.types.ConeKotlinType> {
    return klass.generatedCasePathSupertype()
  }

  private fun FirRegularClass.generatedCasePathSupertype(): List<org.jetbrains.kotlin.fir.types.ConeKotlinType> {
    if ((origin as? FirDeclarationOrigin.Plugin)?.key != PathsCaseDeclarationKey) return emptyList()
    val pathsClassId = symbol.classId.outerClassId ?: return emptyList()
    val rootClassId = pathsClassId.outerClassId ?: return emptyList()
    val rootType = rootClassId.createConeType(session)
    val rootClass = session.symbolProvider.getClassLikeSymbolByClassId(rootClassId) as? FirClassSymbol<*>
      ?: return emptyList()
    val caseClass = (rootClass.fir as? FirRegularClass)
      ?.declarations
      ?.filterIsInstance<FirRegularClass>()
      ?.singleOrNull { it.name == name }
      ?: return emptyList()
    val caseType = caseClass.symbol.classId.createConeType(session)
    return listOf(
      caseKeyPathBaseClassId.createConeType(
        session,
        arrayOf(
          rootType.toTypeProjection(Variance.INVARIANT),
          caseType.toTypeProjection(Variance.INVARIANT),
        ),
        false,
      ),
    )
  }

  private companion object {
    private val pathableOrNestedPredicate = LookupPredicate.create {
      annotatedOrUnder(FqName("dev.kpaths.annotations.Pathable"))
    }
    private val caseKeyPathBaseClassId = org.jetbrains.kotlin.name.ClassId.topLevel(
      FqName("dev.kpaths.core.CaseKeyPathBase"),
    )
  }
}
