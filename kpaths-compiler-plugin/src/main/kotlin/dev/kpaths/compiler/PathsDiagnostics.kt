package dev.kpaths.compiler

import dev.kpaths.codegen.model.KPathsCasePayloadDecision
import dev.kpaths.codegen.model.KPathsCasePayloadShape
import dev.kpaths.codegen.model.KPathsCaseKind
import dev.kpaths.codegen.model.KPathsCasePolicyResult
import dev.kpaths.codegen.model.KPathsCaseShape
import dev.kpaths.codegen.model.KPathsDiagnostics
import dev.kpaths.codegen.model.KPathsGeneratedMember
import dev.kpaths.codegen.model.KPathsPropertyPolicyResult
import dev.kpaths.codegen.model.KPathsPropertyShape
import dev.kpaths.codegen.model.classifyForKPaths
import dev.kpaths.codegen.model.findKPathsGeneratedMemberCollisions
import dev.kpaths.codegen.model.hasAmbiguousKPathsCompanion
import dev.kpaths.codegen.model.isKPathsReservedName
import dev.kpaths.codegen.model.validateKPathsConfiguredName
import dev.kpaths.codegen.model.kPathsConfiguredNameDiagnostic
import dev.kpaths.codegen.model.kPathsCasePropertyCatalogName
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.text
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.diagnostics.warning1
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirEnumEntry
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.utils.isLocal
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.predicate.DeclarationPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtElement

internal object PathsDiagnostics : KtDiagnosticsContainer() {
  val INCOMPLETE_CASE_CATALOG by warning1<KtElement, String>(SourceElementPositioningStrategies.DECLARATION_NAME)
  val GENERATED_MEMBER_COLLISION by error1<KtElement, String>(SourceElementPositioningStrategies.DECLARATION_NAME)
  val RESERVED_NAME by error1<KtElement, String>(SourceElementPositioningStrategies.DECLARATION_NAME)
  val INACCESSIBLE_DATA_CLASS_SETTER by error1<KtElement, String>(SourceElementPositioningStrategies.DECLARATION_NAME)
  val NESTED_CATALOG_COLLISION by error1<KtElement, String>(SourceElementPositioningStrategies.DECLARATION_NAME)
  val INVALID_PATH_NAME by error1<KtElement, String>(SourceElementPositioningStrategies.DECLARATION_NAME)
  val INVALID_CASE_NAME by error1<KtElement, String>(SourceElementPositioningStrategies.DECLARATION_NAME)
  val INVALID_CASE_CATALOG_NAME by error1<KtElement, String>(SourceElementPositioningStrategies.DECLARATION_NAME)
  val UNSUPPORTED_SEALED_VARIANT by error1<KtElement, String>(SourceElementPositioningStrategies.DECLARATION_NAME)
  val AMBIGUOUS_COMPANION by error1<KtElement, String>(SourceElementPositioningStrategies.DECLARATION_NAME)

  override fun getRendererFactory(): BaseDiagnosticRendererFactory = Renderers

  private object Renderers : BaseDiagnosticRendererFactory() {
    override val MAP by KtDiagnosticFactoryToRendererMap("KPaths") {
      it.put(INCOMPLETE_CASE_CATALOG, "{0}", CommonRenderers.STRING)
      it.put(GENERATED_MEMBER_COLLISION, "{0}", CommonRenderers.STRING)
      it.put(RESERVED_NAME, "{0}", CommonRenderers.STRING)
      it.put(INACCESSIBLE_DATA_CLASS_SETTER, "{0}", CommonRenderers.STRING)
      it.put(NESTED_CATALOG_COLLISION, "{0}", CommonRenderers.STRING)
      it.put(INVALID_PATH_NAME, "{0}", CommonRenderers.STRING)
      it.put(INVALID_CASE_NAME, "{0}", CommonRenderers.STRING)
      it.put(INVALID_CASE_CATALOG_NAME, "{0}", CommonRenderers.STRING)
      it.put(UNSUPPORTED_SEALED_VARIANT, "{0}", CommonRenderers.STRING)
      it.put(AMBIGUOUS_COMPANION, "{0}", CommonRenderers.STRING)
    }
  }
}

/** Checks generated names and catalog coverage using shared policy and wording. */
@OptIn(DirectDeclarationsAccess::class, SymbolInternals::class, ExperimentalKPathsCompilerApi::class)
internal class PathsDiagnosticCheckers(private val pluginSession: FirSession) : FirAdditionalCheckersExtension(pluginSession) {
  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(metaPathablePredicate)
  }

  override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
    override val regularClassCheckers: Set<FirDeclarationChecker<FirRegularClass>> = setOf(CatalogChecker())
  }

  private inner class CatalogChecker : FirDeclarationChecker<FirRegularClass>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirRegularClass) {
      val isPathable = declaration.annotations.any {
        ((it.annotationTypeRef as? FirResolvedTypeRef)?.coneType as? ConeClassLikeType)
          ?.lookupTag?.classId?.asSingleFqName() == pathableName
      } || pluginSession.kpaths.optionsFor(declaration.symbol) != null ||
        pluginSession.predicateBasedProvider.matches(metaPathablePredicate, declaration.symbol)
      if (!isPathable) return
      val companion = declaration.declarations.filterIsInstance<FirRegularClass>()
        .firstOrNull { it.status.isCompanion }
      val rootSource = declaration.source
      val companionSource = companion?.source
      if (rootSource != null && companionSource != null) {
        val sourceText = rootSource.text.toString()
        val offset = (companionSource.startOffset - rootSource.startOffset).coerceIn(0, sourceText.length)
        val line = sourceText.take(offset).count { it == '\n' } + 1
        if (hasAmbiguousKPathsCompanion(sourceText, line)) {
          reporter.reportOn(declaration.source, PathsDiagnostics.AMBIGUOUS_COMPANION,
            "[KPaths] ${KPathsDiagnostics.ambiguousCompanion()}")
        }
      }
      val catalogName = pluginSession.kpaths.optionsFor(declaration.symbol)?.catalogName ?: "Paths"
      if (declaration.hasKPathsCatalogCollision(catalogName)) {
        reporter.reportOn(declaration.source, PathsDiagnostics.NESTED_CATALOG_COLLISION,
          "[KPaths] ${KPathsDiagnostics.nestedCatalogCollision(declaration.name.asString(), catalogName)}")
      }
      val directSealedCases = if (declaration.status.modality == Modality.SEALED) {
        declaration.declarations
          .filterIsInstance<FirRegularClass>()
          .filter { candidate ->
            candidate.symbol.resolvedSuperTypes.any {
              (it as? ConeClassLikeType)?.fullyExpandedType()?.lookupTag?.classId == declaration.symbol.classId
            }
          }
      } else emptyList()
      val supportedSealedCases = directSealedCases.filter { candidate ->
        when (KPathsCaseShape(
          kind = KPathsCaseKind.SEALED_VARIANT,
          isClassOrObject = candidate.classKind in setOf(ClassKind.CLASS, ClassKind.OBJECT),
          hasQualifiedName = !candidate.symbol.isLocal,
        ).classifyForKPaths()) {
          KPathsCasePolicyResult.Supported -> true
          KPathsCasePolicyResult.SealedVariantMustBeClassOrObject -> {
            reporter.reportOn(candidate.source, PathsDiagnostics.UNSUPPORTED_SEALED_VARIANT,
              "[KPaths] ${KPathsDiagnostics.sealedVariantMustBeClassOrObject(candidate.name.asString())}")
            false
          }
          KPathsCasePolicyResult.SealedVariantRequiresQualifiedName -> {
            reporter.reportOn(candidate.source, PathsDiagnostics.UNSUPPORTED_SEALED_VARIANT,
              "[KPaths] ${KPathsDiagnostics.sealedVariantRequiresQualifiedName()}")
            false
          }
        }
      }
      val cases = when {
        declaration.status.modality == Modality.SEALED -> supportedSealedCases
          .map { Triple(it, it.name.asString(), it.generatedKPathsCaseName(it.name).asString()) }
        declaration.classKind == ClassKind.ENUM_CLASS -> declaration.declarations
          .filterIsInstance<FirEnumEntry>().map { Triple(it, it.name.asString(), it.generatedKPathsCaseName(it.name).asString()) }
        else -> emptyList()
      }
      val sourceCaseNames = cases.map { it.second }
      cases.forEach { (case, _, _) ->
        case.configuredKPathsCaseName()?.let { name ->
          validateKPathsConfiguredName(name)?.let { error ->
            reporter.reportOn(case.source, PathsDiagnostics.INVALID_CASE_NAME,
              "[KPaths] ${kPathsConfiguredNameDiagnostic("@CaseName", name, error)}")
          }
        }
      }
      val properties = declaration.declarations.filterIsInstance<FirProperty>()
        .filter { it.origin == FirDeclarationOrigin.Source && it.isKPathsAccessible() }
      val caseProperties = declaration.declarations.filterIsInstance<FirRegularClass>()
        .filter { it.name.asString() in sourceCaseNames }
        .flatMap { it.declarations.filterIsInstance<FirProperty>() }
        .filter { it.origin == FirDeclarationOrigin.Source && it.isKPathsAccessible() }
      (properties + caseProperties).forEach { property ->
        property.configuredKPathsName()?.let { name ->
          validateKPathsConfiguredName(name)?.let { error ->
            reporter.reportOn(property.source, PathsDiagnostics.INVALID_PATH_NAME,
              "[KPaths] ${kPathsConfiguredNameDiagnostic("@PathName", name, error)}")
          }
        }
      }
      (properties + caseProperties).filter { isKPathsReservedName(it.name.asString()) }.forEach { property ->
        reporter.reportOn(declaration.source, PathsDiagnostics.RESERVED_NAME,
          "[KPaths] ${KPathsDiagnostics.reservedSourceName("property", property.name.asString())}")
      }
      sourceCaseNames.filter(::isKPathsReservedName).forEach { name ->
        reporter.reportOn(declaration.source, PathsDiagnostics.RESERVED_NAME,
          "[KPaths] ${KPathsDiagnostics.reservedSourceName("case", name)}")
      }
      val caseClasses = declaration.declarations.filterIsInstance<FirRegularClass>()
        .filter { it.name.asString() in sourceCaseNames }
      val casePropertyCatalogs = caseClasses.mapNotNull { case ->
        val configured = case.configuredKPathsCatalogName()
        val hasProperties = case.declarations.filterIsInstance<FirProperty>()
          .any { it.origin == FirDeclarationOrigin.Source && it.isKPathsAccessible() }
        if (configured != null) {
          val error = validateKPathsConfiguredName(configured)
          if (error != null) {
            reporter.reportOn(case.source, PathsDiagnostics.INVALID_CASE_CATALOG_NAME,
              "[KPaths] ${kPathsConfiguredNameDiagnostic("@PathsName", configured, error)}")
          } else if (!hasProperties) {
            reporter.reportOn(case.source, PathsDiagnostics.INVALID_CASE_CATALOG_NAME,
              "[KPaths] @PathsName requires a sealed case with at least one accessible property path.")
          }
        }
        if (!hasProperties || case.typeParameters.isNotEmpty()) null else {
          val name = configured?.takeIf { validateKPathsConfiguredName(it) == null }
            ?: kPathsCasePropertyCatalogName(case.name.asString())
          KPathsGeneratedMember(name, "the property-path namespace for case '${case.name}'")
        }
      }
      (listOf(declaration) + caseClasses).filter { it.status.isData }.forEach { owner ->
        val constructorNames = owner.declarations.filterIsInstance<FirConstructor>()
          .singleOrNull { it.isPrimary }?.valueParameters.orEmpty()
          .filter { it.isVal || it.isVar }.map { it.name }.toSet()
        owner.declarations.filterIsInstance<FirProperty>()
          .filter { it.origin == FirDeclarationOrigin.Source && it.isKPathsAccessible() }
          .forEach { property ->
            val shape = KPathsPropertyShape(
              ownerIsDataClass = true,
              isMutable = property.isVar,
              isPrimaryConstructorProperty = property.name in constructorNames,
              hasAccessibleSetter = property.hasKPathsAccessibleSetter(),
              copyWritableVals = false,
              isExcludedFromCopyWritableVals = false,
            )
            if (shape.classifyForKPaths() == KPathsPropertyPolicyResult.InaccessibleDataClassSetter) {
              reporter.reportOn(property.source, PathsDiagnostics.INACCESSIBLE_DATA_CLASS_SETTER,
                "[KPaths] ${KPathsDiagnostics.inaccessibleDataClassSetter(property.name.asString())}")
            }
          }
      }
      val members = properties
        .map { KPathsGeneratedMember(it.generatedKPathsName().asString(), "the key path for property '${it.name}'") } +
        cases.map { (_, sourceName, generatedName) -> KPathsGeneratedMember(generatedName, "the case path for '$sourceName'") } +
        casePropertyCatalogs +
        if (cases.isNotEmpty() && declaration.typeParameters.isEmpty()) listOf(
          KPathsGeneratedMember("allCasePaths", "the generated catalog of all case paths"),
          KPathsGeneratedMember("caseOf", "the generated case-path lookup function"),
        ) else emptyList()
      findKPathsGeneratedMemberCollisions(members).forEach { collision ->
        reporter.reportOn(declaration.source, PathsDiagnostics.GENERATED_MEMBER_COLLISION,
          "[KPaths] ${KPathsDiagnostics.generatedMemberCollision(collision)}")
      }
      if (declaration.status.modality != Modality.SEALED || declaration.typeParameters.isNotEmpty()) return
      val unavailable = declaration.declarations.filterIsInstance<FirRegularClass>().filter { case ->
        case.classKind == ClassKind.CLASS && case.symbol.resolvedSuperTypes.any {
          (it as? ConeClassLikeType)?.fullyExpandedType()?.lookupTag?.classId == declaration.symbol.classId
        } && !case.hasCanonicalPayload()
      }.map { it.name.asString() }
      if (unavailable.isNotEmpty()) {
        reporter.reportOn(declaration.source, PathsDiagnostics.INCOMPLETE_CASE_CATALOG,
          "[KPaths] ${KPathsDiagnostics.incompleteCaseCatalog(unavailable)}")
      }
    }

    private fun FirRegularClass.hasCanonicalPayload(): Boolean {
      val constructor = declarations.filterIsInstance<FirConstructor>().singleOrNull { it.isPrimary }
      val parameters = constructor?.valueParameters.orEmpty()
      val properties = declarations.filterIsInstance<FirProperty>()
      val selected = parameters.mapNotNull { parameter -> properties.singleOrNull { it.name == parameter.name } }
      return KPathsCasePayloadShape(
        isDataClass = status.isData,
        hasPrimaryConstructor = constructor != null,
        isConstructorAccessible = constructor != null && constructor.status.visibility !in
          setOf(Visibilities.Private, Visibilities.Protected),
        propertyNames = parameters.map { it.name.asString() },
        allParametersAreAccessibleProperties = selected.size == parameters.size && selected.all {
          it.status.visibility !in setOf(Visibilities.Private, Visibilities.Protected)
        },
      ).classifyForKPaths() is KPathsCasePayloadDecision.Available
    }
  }

  private companion object {
    val pathableName = FqName("dev.kpaths.annotations.Pathable")
    val metaPathablePredicate = DeclarationPredicate.create {
      metaAnnotated(listOf(FqName("dev.kpaths.annotations.KPathsPathable")), false)
    }
  }
}
