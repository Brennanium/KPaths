package dev.kpaths.compiler

import dev.kpaths.codegen.model.KPathsCaseKind
import dev.kpaths.codegen.model.KPathsCasePayloadDecision
import dev.kpaths.codegen.model.KPathsCasePayloadShape
import dev.kpaths.codegen.model.KPathsCasePolicyResult
import dev.kpaths.codegen.model.KPathsCaseShape
import dev.kpaths.codegen.model.classifyForKPaths
import dev.kpaths.codegen.model.kPathsCasePropertyCatalogName
import dev.kpaths.codegen.model.validateKPathsConfiguredName
import dev.kpaths.compiler.PathsFirPredicates.metaPathablePredicate
import dev.kpaths.compiler.PathsFirPredicates.pathablePredicate
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirEnumEntry
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.declaredProperties
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** Session-scoped FIR queries shared by declaration-generation callbacks. */
@OptIn(DirectDeclarationsAccess::class, ExperimentalKPathsCompilerApi::class, SymbolInternals::class)
internal class PathsFirAnalysis(private val session: FirSession) {
  /**
   * Nested classifier lookup happens at FIR's `COMPANION_GENERATION` phase, before annotations
   * on the source declaration can safely be resolved. The predicate provider pre-indexes this
   * top-level plugin annotation specifically for early lookup.
   */
  fun FirClassSymbol<*>.catalogName(): Name? {
    session.kpaths.optionsFor(this)?.let { request ->
      return Name.identifier(request.catalogName)
    }
    return when {
      classId in pathableClassIds -> pathsName
      session.predicateBasedProvider.matches(metaPathablePredicate, this) -> pathsName
      else -> null
    }
  }

  /** Resolves generation configuration from either a compiler-plugin request or annotations. */
  fun FirClassSymbol<*>.generationOptions(): KPathsGenerationOptions? {
    session.kpaths.optionsFor(this)?.let { return it }
    fir.annotations.firstOrNull { it.classIdOrNull() == pathableAnnotationClassId }?.let { annotation ->
      return KPathsGenerationOptions(copyWritableVals = annotation.booleanArgument("copyWritableVals"))
    }
    if (!session.predicateBasedProvider.matches(metaPathablePredicate, this)) return null
    val optIn = fir.annotations.firstNotNullOfOrNull { annotation ->
      val annotationClass = annotation.classIdOrNull()
        ?.let(session.symbolProvider::getClassLikeSymbolByClassId)
        as? FirClassSymbol<*>
        ?: return@firstNotNullOfOrNull null
      annotationClass.fir.annotations.firstOrNull {
        it.classIdOrNull() == kpathsPathableAnnotationClassId
      }
    }
    return KPathsGenerationOptions(
      copyWritableVals = optIn?.booleanArgument("copyWritableVals") ?: false,
    )
  }

  fun FirClassSymbol<*>.primaryConstructorPropertyNames(): Set<Name> =
    ((fir as? FirRegularClass)?.declarations.orEmpty()
      .filterIsInstance<FirConstructor>()
      .singleOrNull { it.isPrimary }
      ?.valueParameters
      ?.filter { it.isVal || it.isVar }
      ?.mapTo(mutableSetOf()) { it.name })
      ?: emptySet()

  /**
   * The first compiler-plugin case slice intentionally emits direct children only. They cover the
   * idiomatic nested sealed hierarchy and give FIR an early, source-stable discovery mechanism.
   * Package-level inheritors will move to compiler-provided sealed-inheritor lookup once the
   * generated case surface is established.
   */
  fun FirClassSymbol<*>.caseSymbols(): List<GeneratedCase> {
    val root = fir as? FirRegularClass ?: return emptyList()
    return when {
      root.status.modality == Modality.SEALED -> root.declarations
        .filterIsInstance<FirRegularClass>()
        .map { it.symbol }
        .filter { candidate ->
          ExperimentalCaseTypeResolver(session).isDirectCase(candidate, root.symbol.classId) && KPathsCaseShape(
            kind = KPathsCaseKind.SEALED_VARIANT,
            isClassOrObject = candidate.fir.classKind in setOf(ClassKind.CLASS, ClassKind.OBJECT),
            hasQualifiedName = true,
          ).classifyForKPaths() == KPathsCasePolicyResult.Supported
        }
        .map { candidate ->
          GeneratedCase(
            name = candidate.fir.generatedKPathsCaseName(candidate.classId.shortClassName),
            kind = KPathsCaseKind.SEALED_VARIANT,
            sealedSymbol = candidate,
            hasTypeParameters = candidate.fir.typeParameters.isNotEmpty(),
          )
        }
      root.classKind == ClassKind.ENUM_CLASS -> root.declarations
        .filterIsInstance<FirEnumEntry>()
        .map { entry -> GeneratedCase(entry.generatedKPathsCaseName(entry.name), KPathsCaseKind.ENUM_ENTRY) }
      else -> emptyList()
    }
  }

  data class GeneratedCase(
    val name: Name,
    val kind: KPathsCaseKind,
    val sealedSymbol: FirClassSymbol<*>? = null,
    val hasTypeParameters: Boolean = false,
  )

  fun GeneratedCase.propertyCatalogName(allowGeneric: Boolean = false): Name? {
    val case = sealedSymbol ?: return null
    if ((!allowGeneric && hasTypeParameters) || case.sourceDeclaredProperties().isEmpty()) return null
    val configured = case.fir.configuredKPathsCatalogName()
    val name = configured?.takeIf { validateKPathsConfiguredName(it) == null }
      ?: kPathsCasePropertyCatalogName(case.classId.shortClassName.asString())
    return Name.identifier(name)
  }

  fun GeneratedCase.genericPropertyCatalogClassName(): Name? =
    propertyCatalogName(allowGeneric = true)?.let {
      Name.identifier(requireNotNull(sealedSymbol).classId.shortClassName.asString() + "PropertyPaths")
    }

  private fun FirAnnotation.classIdOrNull() =
    ((annotationTypeRef as? FirResolvedTypeRef)?.coneType as? ConeClassLikeType)?.lookupTag?.classId

  private fun FirAnnotation.booleanArgument(name: String): Boolean =
    (argumentMapping.mapping[Name.identifier(name)] as? FirLiteralExpression)?.value as? Boolean ?: false

  fun FirClassSymbol<*>.isGeneratedPaths(): Boolean =
    (origin as? FirDeclarationOrigin.Plugin)?.key == PathsDeclarationKey

  fun FirClassSymbol<*>.isGeneratedCasePath(): Boolean =
    (origin as? FirDeclarationOrigin.Plugin)?.key == PathsCaseDeclarationKey

  fun FirClassSymbol<*>.isGeneratedCasePropertyCatalog(): Boolean =
    (origin as? FirDeclarationOrigin.Plugin)?.key == PathsCasePropertyCatalogKey

  fun FirClassSymbol<*>.isGeneratedGenericCatalog(): Boolean =
    (origin as? FirDeclarationOrigin.Plugin)?.key == PathsGenericCatalogKey

  fun FirClassSymbol<*>.isGeneratedGenericCasePath(): Boolean =
    (origin as? FirDeclarationOrigin.Plugin)?.key == PathsGenericCaseDeclarationKey

  fun FirClassSymbol<*>.isGeneratedGenericCasePropertyCatalog(): Boolean =
    (origin as? FirDeclarationOrigin.Plugin)?.key == PathsGenericCasePropertyCatalogKey

  fun FirClassSymbol<*>.genericCasePropertyRoot(): FirClassSymbol<*>? {
    if (!isGeneratedGenericCasePropertyCatalog()) return null
    val rootId = classId.outerClassId?.outerClassId?.outerClassId ?: return null
    return session.symbolProvider.getClassLikeSymbolByClassId(rootId) as? FirClassSymbol<*>
  }

  fun FirClassSymbol<*>.genericCasePropertySource(): FirClassSymbol<*>? =
    genericCasePropertyRoot()?.caseSymbols()
      ?.singleOrNull { it.genericPropertyCatalogClassName() == classId.shortClassName }?.sealedSymbol

  fun FirClassSymbol<*>.genericCaseRoot(): FirClassSymbol<*>? {
    if (!isGeneratedGenericCasePath()) return null
    val rootId = classId.outerClassId?.outerClassId?.outerClassId ?: return null
    return session.symbolProvider.getClassLikeSymbolByClassId(rootId) as? FirClassSymbol<*>
  }

  fun FirClassSymbol<*>.genericSourceCase(): FirClassSymbol<*>? {
    val root = genericCaseRoot() ?: return null
    return root.caseSymbols().singleOrNull { it.name.asString() + "CasePath" == classId.shortClassName.asString() }
      ?.sealedSymbol
  }

  fun FirClassSymbol<*>.genericCatalogRoot(): FirClassSymbol<*>? {
    if (!isGeneratedGenericCatalog()) return null
    val rootId = classId.outerClassId?.outerClassId ?: return null
    return session.symbolProvider.getClassLikeSymbolByClassId(rootId) as? FirClassSymbol<*>
  }

  fun FirClassSymbol<*>.propertyCatalogRoot(): FirClassSymbol<*>? {
    if (!isGeneratedCasePropertyCatalog()) return null
    val rootId = classId.outerClassId?.outerClassId ?: return null
    return session.symbolProvider.getClassLikeSymbolByClassId(rootId) as? FirClassSymbol<*>
  }

  fun FirClassSymbol<*>.propertyCatalogCase(): FirClassSymbol<*>? =
    propertyCatalogRoot()?.caseSymbols()?.singleOrNull { it.propertyCatalogName() == classId.shortClassName }?.sealedSymbol

  fun FirClassSymbol<*>.rootClass(): FirClassSymbol<*>? {
    if (!isGeneratedPaths()) return null
    val rootClassId = classId.outerClassId ?: return null
    return session.symbolProvider.getClassLikeSymbolByClassId(rootClassId) as? FirClassSymbol<*>
  }

  fun FirClassSymbol<*>.casePathRootClass(): FirClassSymbol<*>? {
    if (!isGeneratedCasePath()) return null
    val pathsClassId = classId.outerClassId ?: return null
    val rootClassId = pathsClassId.outerClassId ?: return null
    return session.symbolProvider.getClassLikeSymbolByClassId(rootClassId) as? FirClassSymbol<*>
  }

  fun FirClassSymbol<*>.sourceCase(): FirClassSymbol<*>? {
    val root = casePathRootClass() ?: return null
    return root.caseSymbols().singleOrNull { it.name == classId.shortClassName }?.sealedSymbol
  }

  fun FirClassSymbol<*>.sourceCasePayloadProperties(): List<FirPropertySymbol> {
    val source = (if (isGeneratedGenericCasePath()) genericSourceCase() else sourceCase()) ?: return emptyList()
    val sourceClass = source.fir as? FirRegularClass ?: return emptyList()
    val constructor = sourceClass.declarations
      .filterIsInstance<FirConstructor>().singleOrNull { it.isPrimary } ?: return emptyList()
    val properties = source.declaredProperties()
    val selected = constructor.valueParameters.mapNotNull { parameter ->
      properties.singleOrNull { it.name == parameter.name }
    }
    val decision = KPathsCasePayloadShape(
      isDataClass = sourceClass.status.isData,
      hasPrimaryConstructor = true,
      isConstructorAccessible = constructor.status.visibility !in setOf(Visibilities.Private, Visibilities.Protected),
      propertyNames = constructor.valueParameters.map { it.name.asString() },
      allParametersAreAccessibleProperties = selected.size == constructor.valueParameters.size &&
        selected.all { it.fir.status.visibility !in setOf(Visibilities.Private, Visibilities.Protected) },
    ).classifyForKPaths()
    return if (decision is KPathsCasePayloadDecision.Available) selected else emptyList()
  }

  private fun FirClassSymbol<*>.declaredProperties(): List<FirPropertySymbol> =
    declaredProperties(session, FirResolvePhase.TYPES)

  /** Excludes members that this extension itself has generated for the root. */
  fun FirClassSymbol<*>.sourceDeclaredProperties(): List<FirPropertySymbol> =
    declaredProperties().filter { it.fir.origin == FirDeclarationOrigin.Source && it.fir.isKPathsAccessible() }

  fun FirClassSymbol<*>.canGenerateCaseCatalog(): Boolean =
    fir.typeParameters.isEmpty() && caseSymbols().isNotEmpty()

  fun FirClassSymbol<*>.canGenerateCasePathableConformance(): Boolean =
    catalogName() != null &&
      canGenerateCaseCatalog() &&
      // Do not call `declaredProperties()` while FIR is asking this extension which
      // generated members to add: that re-enters the generated-member scope lookup.
      fir.declarations
        .filterIsInstance<FirProperty>()
        .none { it.origin == FirDeclarationOrigin.Source && it.name.asString() == "case" }

  private companion object {
    private val pathableAnnotationClassId = org.jetbrains.kotlin.name.ClassId.topLevel(FqName("dev.kpaths.annotations.Pathable"))
    private val kpathsPathableAnnotationClassId = org.jetbrains.kotlin.name.ClassId.topLevel(FqName("dev.kpaths.annotations.KPathsPathable"))
    private val pathsName: Name = Name.identifier("Paths")
  }

  private val pathableClassIds by lazy {
    session.predicateBasedProvider
      .getSymbolsByPredicate(pathablePredicate)
      .filterIsInstance<FirClassSymbol<*>>()
      .mapTo(mutableSetOf()) { it.classId }
  }
}
