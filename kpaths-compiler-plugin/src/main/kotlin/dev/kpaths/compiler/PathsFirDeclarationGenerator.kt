package dev.kpaths.compiler

import dev.kpaths.codegen.model.KPathsCaseKind
import dev.kpaths.codegen.model.KPathsCasePayload
import dev.kpaths.codegen.model.KPathsCasePayloadDecision
import dev.kpaths.codegen.model.KPathsCasePayloadShape
import dev.kpaths.codegen.model.KPathsCasePolicyResult
import dev.kpaths.codegen.model.KPathsCaseShape
import dev.kpaths.codegen.model.KPathsPropertyPathKind
import dev.kpaths.codegen.model.KPathsPropertyPolicyResult
import dev.kpaths.codegen.model.KPathsPropertyShape
import dev.kpaths.codegen.model.classifyForKPaths
import dev.kpaths.codegen.model.kPathsCasePropertyCatalogName
import dev.kpaths.codegen.model.validateKPathsConfiguredName
import dev.kpaths.codegen.model.planKPathsBoundPayloadMembers
import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirEnumEntry
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.declaredProperties
import org.jetbrains.kotlin.fir.extensions.DeclarationGenerationContext
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.predicate.DeclarationPredicate
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.plugin.createConstructor
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
import org.jetbrains.kotlin.fir.plugin.createMemberProperty
import org.jetbrains.kotlin.fir.plugin.createNestedClass
import org.jetbrains.kotlin.fir.plugin.createConeType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.toTypeProjection
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.types.Variance

/**
 * Generates a nested `Paths` object for each opted-in class.
 *
 * It proves the central API shape—`Person.Paths` without a companion or generated `.kt` file.
 * This research slice exposes direct declared properties as typed [KeyPath] values, preserves
 * in-place reference writes for ordinary class `var` properties, and exposes direct nested sealed
 * cases plus enum entries as `CaseKeyPath`s. Object cases and one-property data cases also
 * expose their canonical associated-value paths for `allCasePaths`; generic catalogs,
 * adapter-selected path subtypes, and diagnostic policy are still pending.
 */
@OptIn(DirectDeclarationsAccess::class, ExperimentalKPathsCompilerApi::class, SymbolInternals::class)
internal class PathsDeclarationGenerator(session: FirSession) : FirDeclarationGenerationExtension(session) {
  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(pathablePredicate)
    register(metaPathablePredicate)
  }

  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: DeclarationGenerationContext.Nested,
  ): Set<Name> = when {
    classSymbol.catalogName() != null -> {
      val name = requireNotNull(classSymbol.catalogName())
      if ((classSymbol.fir as? FirRegularClass)?.hasKPathsCatalogCollision(name.asString()) == true) emptySet()
      else setOf(name)
    }
    classSymbol.isGeneratedPaths() -> classSymbol.rootClass()
      ?.caseSymbols()
      ?.filter { it.kind == KPathsCaseKind.SEALED_VARIANT && !it.hasTypeParameters }
      ?.flatMap { case ->
        listOf(case.name) + listOfNotNull(case.propertyCatalogName())
      }?.toMutableSet()
      ?: emptySet()
    else -> emptySet()
  }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: DeclarationGenerationContext.Nested,
  ): FirClassLikeSymbol<*>? {
    if (name == owner.catalogName()) {
      if ((owner.fir as? FirRegularClass)?.hasKPathsCatalogCollision(name.asString()) == true) return null
      return createNestedClass(owner, name, PathsDeclarationKey, ClassKind.OBJECT) {
        if (owner.canGenerateCaseCatalog()) {
          superType(
            casePathCatalogBaseClassId.createConeType(
              session,
              arrayOf(owner.classId.createConeType(session).toTypeProjection(Variance.INVARIANT)),
              false,
            ),
          )
        }
      }.symbol
    }
    if (owner.isGeneratedPaths()) {
      val root = owner.rootClass() ?: return null
      val propertyCase = root.caseSymbols().singleOrNull { it.propertyCatalogName() == name }
      if (propertyCase != null) {
        return createNestedClass(owner, name, PathsCasePropertyCatalogKey, ClassKind.OBJECT).symbol
      }
      val case = root.caseSymbols().singleOrNull { it.name == name } ?: return null
      if (case.kind != KPathsCaseKind.SEALED_VARIANT || case.hasTypeParameters) return null
      val rootType = root.classId.createConeType(session)
      val caseType = requireNotNull(case.sealedSymbol).classId.createConeType(session)
      return createNestedClass(owner, name, PathsCaseDeclarationKey, ClassKind.OBJECT) {
        superType(
          caseKeyPathBaseClassId.createConeType(
            session,
            arrayOf(
              rootType.toTypeProjection(Variance.INVARIANT),
              caseType.toTypeProjection(Variance.INVARIANT),
            ),
            false,
          ),
        )
      }.symbol
    }
    return null
  }

  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: DeclarationGenerationContext.Member,
  ): Set<Name> = when {
    classSymbol.canGenerateCasePathableConformance() -> setOf(Name.identifier("case"))
    classSymbol.isGeneratedCasePropertyCatalog() -> buildSet {
      add(SpecialNames.INIT)
      addAll(classSymbol.propertyCatalogCase()?.sourceDeclaredProperties()?.map { it.fir.generatedKPathsName() }.orEmpty())
    }
    classSymbol.isGeneratedCasePath() -> classSymbol.sourceCasePayloadProperties().let { properties ->
      KPathsCasePayload.fromProperties(properties.map { it.name.asString() })
        ?.let { mutableSetOf(Name.identifier(it.generatedMemberName)) }
        ?: mutableSetOf()
    }
      .also {
        val properties = classSymbol.sourceCasePayloadProperties()
        it += planKPathsBoundPayloadMembers(properties.map { property -> property.name.asString() })
          .map { member -> Name.identifier(member.generatedName) }
        if (classSymbol.sourceCase()?.fir?.classKind == ClassKind.OBJECT) it += Name.identifier("unit")
        it += SpecialNames.INIT
        it += Name.identifier("__kpaths_id")
        it += Name.identifier("__kpaths_valueType")
        it += Name.identifier("match")
        it += Name.identifier("embed")
      }
    !classSymbol.isGeneratedPaths() -> emptySet()
    else -> buildSet {
      add(SpecialNames.INIT)
      addAll(classSymbol.rootClass()?.sourceDeclaredProperties()?.map { it.fir.generatedKPathsName() }.orEmpty())
      addAll(classSymbol.rootClass()?.caseSymbols()?.map { it.name }.orEmpty())
      if (classSymbol.rootClass()?.caseSymbols().orEmpty().isNotEmpty()) {
        add(Name.identifier("__kpaths_buildAllCasePaths"))
      }
    }
  }

  override fun generateConstructors(
    context: DeclarationGenerationContext.Member,
  ): List<FirConstructorSymbol> {
    val key = when {
      context.owner.isGeneratedPaths() -> PathsDeclarationKey
      context.owner.isGeneratedCasePath() -> PathsCaseDeclarationKey
      context.owner.isGeneratedCasePropertyCatalog() -> PathsCasePropertyCatalogKey
      else -> return emptyList()
    }
    return listOf(createConstructor(context.owner, key, true).symbol)
  }

  override fun generateProperties(
    callableId: org.jetbrains.kotlin.name.CallableId,
    context: DeclarationGenerationContext.Member?,
  ): List<FirPropertySymbol> {
    val pathsClass = context?.owner ?: return emptyList()
    if (pathsClass.isGeneratedCasePropertyCatalog()) {
      val sourceCase = pathsClass.propertyCatalogCase() ?: return emptyList()
      val sourceProperty = sourceCase.sourceDeclaredProperties()
        .singleOrNull { it.fir.generatedKPathsName() == callableId.callableName } ?: return emptyList()
      val shape = KPathsPropertyShape(
        ownerIsDataClass = (sourceCase.fir as? FirRegularClass)?.status?.isData == true,
        isMutable = sourceProperty.fir.isVar,
        isPrimaryConstructorProperty = sourceProperty.name in sourceCase.primaryConstructorPropertyNames(),
        hasAccessibleSetter = sourceProperty.fir.hasKPathsAccessibleSetter(),
        copyWritableVals = pathsClass.propertyCatalogRoot()?.generationOptions()?.copyWritableVals ?: false,
        isExcludedFromCopyWritableVals = false,
      ).classifyForKPaths()
      val pathType = when ((shape as? KPathsPropertyPolicyResult.Supported)?.kind) {
        KPathsPropertyPathKind.REFERENCE_WRITABLE -> referenceWritableKeyPathClassId
        KPathsPropertyPathKind.COPY_WRITABLE, KPathsPropertyPathKind.COPY_THEN_MUTATE -> writableKeyPathClassId
        else -> keyPathClassId
      }.createConeType(session, arrayOf(
        sourceCase.classId.createConeType(session).toTypeProjection(Variance.INVARIANT),
        sourceProperty.resolvedReturnType.toTypeProjection(Variance.INVARIANT),
      ), false)
      return listOf(createMemberProperty(pathsClass, PathsCasePropertyCatalogKey, callableId.callableName, pathType, true, false).symbol)
    }
    if (pathsClass.canGenerateCasePathableConformance()) {
      if (callableId.callableName.asString() != "case") return emptyList()
      val rootType = pathsClass.classId.createConeType(session)
      val returnType = partialCaseKeyPathClassId.createConeType(
        session,
        arrayOf(rootType.toTypeProjection(Variance.INVARIANT)),
        false,
      )
      return listOf(
        createMemberProperty(
          pathsClass,
          PathsDeclarationKey,
          callableId.callableName,
          returnType,
          isVal = true,
          hasBackingField = false,
        ).symbol,
      )
    }
    if (pathsClass.isGeneratedCasePath()) {
      val root = pathsClass.casePathRootClass() ?: return emptyList()
      val sourceCase = pathsClass.sourceCase() ?: return emptyList()
      val metadataType = when (callableId.callableName.asString()) {
        "__kpaths_id" -> pathIdClassId.createConeType(session)
        "__kpaths_valueType" -> typeIdClassId.createConeType(session)
        else -> null
      }
      if (metadataType != null) {
        return listOf(
          createMemberProperty(
            pathsClass,
            PathsCaseDeclarationKey,
            callableId.callableName,
            metadataType,
            isVal = true,
            hasBackingField = false,
          ).symbol,
        )
      }
      if (callableId.callableName.asString() == "unit" && sourceCase.fir.classKind == ClassKind.OBJECT) {
        val returnType = caseKeyPathClassId.createConeType(
          session,
          arrayOf(
            root.classId.createConeType(session).toTypeProjection(Variance.INVARIANT),
            unitClassId.createConeType(session).toTypeProjection(Variance.INVARIANT),
          ),
          false,
        )
        return listOf(createMemberProperty(pathsClass, PathsCaseDeclarationKey, callableId.callableName, returnType, true, false).symbol)
      }
      val properties = pathsClass.sourceCasePayloadProperties()
      val payloadType = if (callableId.callableName.asString() == "payload" && properties.size in 2..3) {
        val tupleId = org.jetbrains.kotlin.name.ClassId.topLevel(
          FqName(if (properties.size == 2) "kotlin.Pair" else "kotlin.Triple"),
        )
        tupleId.createConeType(session, properties.map { it.resolvedReturnType.toTypeProjection(Variance.INVARIANT) }.toTypedArray(), false)
      } else {
        properties.singleOrNull { it.name == callableId.callableName }?.resolvedReturnType
      } ?: return emptyList()
      val returnType = caseKeyPathClassId.createConeType(
        session,
        arrayOf(
          root.classId.createConeType(session).toTypeProjection(Variance.INVARIANT),
          payloadType.toTypeProjection(Variance.INVARIANT),
        ),
        false,
      )
      return listOf(createMemberProperty(pathsClass, PathsCaseDeclarationKey, callableId.callableName, returnType, true, false).symbol)
    }
    val rootClass = pathsClass.rootClass() ?: return emptyList()
    val rootType = rootClass.classId.createConeType(session)
    val case = rootClass.caseSymbols()
      .singleOrNull { it.name == callableId.callableName }
    val sourceProperty = rootClass.sourceDeclaredProperties()
      .singleOrNull { it.fir.generatedKPathsName() == callableId.callableName }
    if (case != null) {
      if (rootClass.fir.typeParameters.isNotEmpty() || case.hasTypeParameters) {
        return emptyList()
      }
      val caseValueType = when (case.kind) {
        KPathsCaseKind.SEALED_VARIANT -> requireNotNull(case.sealedSymbol).classId.createConeType(session)
        KPathsCaseKind.ENUM_ENTRY -> unitClassId.createConeType(session)
      }
      val returnType = if (case.kind == KPathsCaseKind.SEALED_VARIANT) {
        pathsClass.classId.createNestedClassId(case.name).createConeType(session)
      } else caseKeyPathClassId.createConeType(
        session,
        arrayOf(
          rootType.toTypeProjection(Variance.INVARIANT),
          caseValueType.toTypeProjection(Variance.INVARIANT),
        ),
        false,
      )
      return listOf(
        createMemberProperty(
          pathsClass,
          PathsDeclarationKey,
          callableId.callableName,
          returnType,
          isVal = true,
          hasBackingField = false,
        ).symbol,
      )
    }
    if (sourceProperty == null) return emptyList()
    if (rootClass.fir.typeParameters.isNotEmpty() || sourceProperty.fir.typeParameters.isNotEmpty()) {
      return emptyList()
    }
    val pathKind = KPathsPropertyShape(
      ownerIsDataClass = (rootClass.fir as? FirRegularClass)?.status?.isData == true,
      isMutable = sourceProperty.fir.isVar,
      isPrimaryConstructorProperty = sourceProperty.name in rootClass.primaryConstructorPropertyNames(),
      hasAccessibleSetter = sourceProperty.fir.hasKPathsAccessibleSetter(),
      copyWritableVals = rootClass.generationOptions()?.copyWritableVals ?: false,
      isExcludedFromCopyWritableVals = false,
    ).classifyForKPaths()
    val pathClassId = when (pathKind) {
      is KPathsPropertyPolicyResult.Supported -> when (pathKind.kind) {
        KPathsPropertyPathKind.REFERENCE_WRITABLE -> referenceWritableKeyPathClassId
        KPathsPropertyPathKind.COPY_WRITABLE -> writableKeyPathClassId
        KPathsPropertyPathKind.COPY_THEN_MUTATE -> writableKeyPathClassId
        else -> keyPathClassId
      }
      KPathsPropertyPolicyResult.InaccessibleDataClassSetter -> keyPathClassId
    }
    val returnType = pathClassId.createConeType(
      session,
      arrayOf(
        rootType.toTypeProjection(Variance.INVARIANT),
        sourceProperty.resolvedReturnType.toTypeProjection(Variance.INVARIANT),
      ),
      false,
    )
    return listOf(
      createMemberProperty(
        pathsClass,
        PathsDeclarationKey,
        callableId.callableName,
        returnType,
        isVal = true,
        hasBackingField = false,
      ).symbol,
    )
  }

  override fun generateFunctions(
    callableId: org.jetbrains.kotlin.name.CallableId,
    context: DeclarationGenerationContext.Member?,
  ): List<FirNamedFunctionSymbol> {
    val pathsClass = context?.owner ?: return emptyList()
    if (pathsClass.isGeneratedPaths() && callableId.callableName.asString() == "__kpaths_buildAllCasePaths") {
      val root = pathsClass.rootClass() ?: return emptyList()
      if (!root.canGenerateCaseCatalog()) return emptyList()
      val rootType = root.classId.createConeType(session)
      val partialCasePathType = partialCaseKeyPathClassId.createConeType(
        session,
        arrayOf(rootType.toTypeProjection(Variance.INVARIANT)),
        false,
      )
      val returnType = listClassId.createConeType(
        session,
        arrayOf(partialCasePathType.toTypeProjection(Variance.INVARIANT)),
        false,
      )
      return listOf(
        createMemberFunction(
          pathsClass,
          PathsDeclarationKey,
          callableId.callableName,
          returnType,
        ) {
          withGeneratedDefaultBody()
        }.symbol,
      )
    }
    if (!pathsClass.isGeneratedCasePath()) return emptyList()
    val root = pathsClass.casePathRootClass() ?: return emptyList()
    val sourceCase = pathsClass.sourceCase() ?: return emptyList()
    val rootType = root.classId.createConeType(session)
    val caseType = sourceCase.classId.createConeType(session)
    val payloadProperties = pathsClass.sourceCasePayloadProperties()
    val boundMember = planKPathsBoundPayloadMembers(payloadProperties.map { it.name.asString() })
      .singleOrNull { it.generatedName == callableId.callableName.asString() }
    if (boundMember != null) {
      val targetType = payloadProperties[boundMember.targetIndex].resolvedReturnType
      val returnType = caseKeyPathClassId.createConeType(session, arrayOf(
        rootType.toTypeProjection(Variance.INVARIANT), targetType.toTypeProjection(Variance.INVARIANT),
      ), false)
      return listOf(createMemberFunction(pathsClass, PathsCaseDeclarationKey, callableId.callableName, returnType) {
        payloadProperties.filterIndexed { index, _ -> index != boundMember.targetIndex }.forEach { property ->
          valueParameter(property.name, property.resolvedReturnType)
        }
        withGeneratedDefaultBody()
      }.symbol)
    }
    val function = when (callableId.callableName.asString()) {
      "match" -> createMemberFunction(
        pathsClass,
        PathsCaseDeclarationKey,
        callableId.callableName,
        caseMatchClassId.createConeType(
          session,
          arrayOf(caseType.toTypeProjection(Variance.INVARIANT)),
          false,
        ),
      ) {
        valueParameter(Name.identifier("root"), rootType)
        withGeneratedDefaultBody()
      }
      "embed" -> createMemberFunction(
        pathsClass,
        PathsCaseDeclarationKey,
        callableId.callableName,
        rootType,
      ) {
        valueParameter(Name.identifier("value"), caseType)
        withGeneratedDefaultBody()
      }
      else -> null
    } ?: return emptyList()
    return listOf(function.symbol)
  }

  /**
   * Nested classifier lookup happens at FIR's `COMPANION_GENERATION` phase, before annotations
   * on the source declaration can safely be resolved. The predicate provider pre-indexes this
   * top-level plugin annotation specifically for early lookup.
   */
  private fun FirClassSymbol<*>.catalogName(): Name? {
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
  private fun FirClassSymbol<*>.generationOptions(): KPathsGenerationOptions? {
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

  private fun FirClassSymbol<*>.primaryConstructorPropertyNames(): Set<Name> =
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
  private fun FirClassSymbol<*>.caseSymbols(): List<GeneratedCase> {
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

  private data class GeneratedCase(
    val name: Name,
    val kind: KPathsCaseKind,
    val sealedSymbol: FirClassSymbol<*>? = null,
    val hasTypeParameters: Boolean = false,
  )

  private fun GeneratedCase.propertyCatalogName(): Name? {
    val case = sealedSymbol ?: return null
    if (hasTypeParameters || case.sourceDeclaredProperties().isEmpty()) return null
    val configured = case.fir.configuredKPathsCatalogName()
    val name = configured?.takeIf { validateKPathsConfiguredName(it) == null }
      ?: kPathsCasePropertyCatalogName(case.classId.shortClassName.asString())
    return Name.identifier(name)
  }

  private fun FirAnnotation.classIdOrNull() =
    ((annotationTypeRef as? FirResolvedTypeRef)?.coneType as? ConeClassLikeType)?.lookupTag?.classId

  private fun FirAnnotation.booleanArgument(name: String): Boolean =
    (argumentMapping.mapping[Name.identifier(name)] as? FirLiteralExpression)?.value as? Boolean ?: false

  private fun FirClassSymbol<*>.isGeneratedPaths(): Boolean =
    (origin as? FirDeclarationOrigin.Plugin)?.key == PathsDeclarationKey

  private fun FirClassSymbol<*>.isGeneratedCasePath(): Boolean =
    (origin as? FirDeclarationOrigin.Plugin)?.key == PathsCaseDeclarationKey

  private fun FirClassSymbol<*>.isGeneratedCasePropertyCatalog(): Boolean =
    (origin as? FirDeclarationOrigin.Plugin)?.key == PathsCasePropertyCatalogKey

  private fun FirClassSymbol<*>.propertyCatalogRoot(): FirClassSymbol<*>? {
    if (!isGeneratedCasePropertyCatalog()) return null
    val rootId = classId.outerClassId?.outerClassId ?: return null
    return session.symbolProvider.getClassLikeSymbolByClassId(rootId) as? FirClassSymbol<*>
  }

  private fun FirClassSymbol<*>.propertyCatalogCase(): FirClassSymbol<*>? =
    propertyCatalogRoot()?.caseSymbols()?.singleOrNull { it.propertyCatalogName() == classId.shortClassName }?.sealedSymbol

  private fun FirClassSymbol<*>.rootClass(): FirClassSymbol<*>? {
    if (!isGeneratedPaths()) return null
    val rootClassId = classId.outerClassId ?: return null
    return session.symbolProvider.getClassLikeSymbolByClassId(rootClassId) as? FirClassSymbol<*>
  }

  private fun FirClassSymbol<*>.casePathRootClass(): FirClassSymbol<*>? {
    if (!isGeneratedCasePath()) return null
    val pathsClassId = classId.outerClassId ?: return null
    val rootClassId = pathsClassId.outerClassId ?: return null
    return session.symbolProvider.getClassLikeSymbolByClassId(rootClassId) as? FirClassSymbol<*>
  }

  private fun FirClassSymbol<*>.sourceCase(): FirClassSymbol<*>? {
    val root = casePathRootClass() ?: return null
    return root.caseSymbols().singleOrNull { it.name == classId.shortClassName }?.sealedSymbol
  }

  private fun FirClassSymbol<*>.sourceCasePayloadProperties(): List<FirPropertySymbol> {
    val source = sourceCase() ?: return emptyList()
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
  private fun FirClassSymbol<*>.sourceDeclaredProperties(): List<FirPropertySymbol> =
    declaredProperties().filter { it.fir.origin == FirDeclarationOrigin.Source && it.fir.isKPathsAccessible() }

  private fun FirClassSymbol<*>.canGenerateCaseCatalog(): Boolean =
    fir.typeParameters.isEmpty() && caseSymbols().isNotEmpty()

  private fun FirClassSymbol<*>.canGenerateCasePathableConformance(): Boolean =
    catalogName() != null &&
      canGenerateCaseCatalog() &&
      // Do not call `declaredProperties()` while FIR is asking this extension which
      // generated members to add: that re-enters the generated-member scope lookup.
      fir.declarations
        .filterIsInstance<FirProperty>()
        .none { it.origin == FirDeclarationOrigin.Source && it.name.asString() == "case" }

  private companion object {
    private val pathableAnnotationName = FqName("dev.kpaths.annotations.Pathable")
    private val pathableAnnotationClassId = org.jetbrains.kotlin.name.ClassId.topLevel(pathableAnnotationName)
    private val pathablePredicate = LookupPredicate.create {
      annotated(pathableAnnotationName)
    }
    private val kpathsPathableAnnotationName = FqName("dev.kpaths.annotations.KPathsPathable")
    private val kpathsPathableAnnotationClassId = org.jetbrains.kotlin.name.ClassId.topLevel(
      kpathsPathableAnnotationName,
    )
    private val metaPathablePredicate = DeclarationPredicate.create {
      metaAnnotated(listOf(kpathsPathableAnnotationName), false)
    }
    private val pathsName: Name = Name.identifier("Paths")
    private val keyPathClassId = org.jetbrains.kotlin.name.ClassId.topLevel(
      FqName("dev.kpaths.core.KeyPath"),
    )
    private val referenceWritableKeyPathClassId = org.jetbrains.kotlin.name.ClassId.topLevel(
      FqName("dev.kpaths.core.ReferenceWritableKeyPath"),
    )
    private val writableKeyPathClassId = org.jetbrains.kotlin.name.ClassId.topLevel(
      FqName("dev.kpaths.core.WritableKeyPath"),
    )
    private val caseKeyPathClassId = org.jetbrains.kotlin.name.ClassId.topLevel(
      FqName("dev.kpaths.core.CaseKeyPath"),
    )
    private val caseKeyPathBaseClassId = org.jetbrains.kotlin.name.ClassId.topLevel(
      FqName("dev.kpaths.core.CaseKeyPathBase"),
    )
    private val partialCaseKeyPathClassId = org.jetbrains.kotlin.name.ClassId.topLevel(
      FqName("dev.kpaths.core.PartialCaseKeyPath"),
    )
    private val casePathCatalogBaseClassId = org.jetbrains.kotlin.name.ClassId.topLevel(
      FqName("dev.kpaths.core.CasePathCatalogBase"),
    )
    private val listClassId = org.jetbrains.kotlin.name.ClassId.topLevel(FqName("kotlin.collections.List"))
    private val caseMatchClassId = org.jetbrains.kotlin.name.ClassId.topLevel(
      FqName("dev.kpaths.core.CaseMatch"),
    )
    private val pathIdClassId = org.jetbrains.kotlin.name.ClassId.topLevel(
      FqName("dev.kpaths.core.PathId"),
    )
    private val typeIdClassId = org.jetbrains.kotlin.name.ClassId.topLevel(
      FqName("dev.kpaths.core.TypeId"),
    )
    private val unitClassId = org.jetbrains.kotlin.name.ClassId.topLevel(FqName("kotlin.Unit"))
  }

  private val pathableClassIds by lazy {
    session.predicateBasedProvider
      .getSymbolsByPredicate(pathablePredicate)
      .filterIsInstance<FirClassSymbol<*>>()
      .mapTo(mutableSetOf()) { it.classId }
  }
}

internal object PathsDeclarationKey : GeneratedDeclarationKey()
internal object PathsCaseDeclarationKey : GeneratedDeclarationKey()
internal object PathsCasePropertyCatalogKey : GeneratedDeclarationKey()
