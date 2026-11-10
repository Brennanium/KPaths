package dev.kpaths.compiler

import dev.kpaths.codegen.model.KPathsCaseKind
import dev.kpaths.codegen.model.KPathsPropertyPathKind
import dev.kpaths.codegen.model.KPathsPropertyPolicyResult
import dev.kpaths.codegen.model.KPathsPropertyShape
import dev.kpaths.codegen.model.classifyForKPaths
import dev.kpaths.compiler.PathsFirSymbols.caseKeyPathClassId
import dev.kpaths.compiler.PathsFirSymbols.keyPathClassId
import dev.kpaths.compiler.PathsFirSymbols.partialCaseKeyPathClassId
import dev.kpaths.compiler.PathsFirSymbols.pathIdClassId
import dev.kpaths.compiler.PathsFirSymbols.referenceWritableKeyPathClassId
import dev.kpaths.compiler.PathsFirSymbols.typeIdClassId
import dev.kpaths.compiler.PathsFirSymbols.unitClassId
import dev.kpaths.compiler.PathsFirSymbols.writableKeyPathClassId
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.extensions.DeclarationGenerationContext
import org.jetbrains.kotlin.fir.extensions.FirExtension
import org.jetbrains.kotlin.fir.plugin.createConeType
import org.jetbrains.kotlin.fir.plugin.createMemberProperty
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.toTypeProjection
import org.jetbrains.kotlin.fir.types.ConeAttributes
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.fir.types.impl.ConeTypeParameterTypeImpl
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutorByMap
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.types.Variance

/** Generates FIR property declarations for root, case, and case-property paths. */
@OptIn(DirectDeclarationsAccess::class, ExperimentalKPathsCompilerApi::class, SymbolInternals::class)
internal class PathsFirPropertyGenerator(
  private val session: FirSession,
  private val analysis: PathsFirAnalysis,
) {
  fun FirExtension.generatePropertyDeclarations(
    callableId: org.jetbrains.kotlin.name.CallableId,
    context: DeclarationGenerationContext.Member?,
  ): List<FirPropertySymbol> = with(analysis) {
    val pathsClass = context?.owner ?: return@with emptyList()
    if (pathsClass.isGeneratedGenericCasePropertyCatalog()) {
      val sourceCase = pathsClass.genericCasePropertySource() ?: return@with emptyList()
      val root = pathsClass.genericCasePropertyRoot() ?: return@with emptyList()
      val property = sourceCase.sourceDeclaredProperties()
        .singleOrNull { it.fir.generatedKPathsName() == callableId.callableName } ?: return@with emptyList()
      val arguments = pathsClass.fir.typeParameters.map {
        ConeTypeParameterTypeImpl(it.symbol.toLookupTag(), false, ConeAttributes.Empty)
      }
      val substitution = sourceCase.fir.typeParameters.map { it.symbol }.zip(arguments).toMap()
      val valueType = ConeSubstitutorByMap.create(substitution, session, false).substituteOrSelf(property.resolvedReturnType)
      val caseType = sourceCase.classId.createConeType(session,
        arguments.map { it.toTypeProjection(Variance.INVARIANT) }.toTypedArray(), false)
      val shape = KPathsPropertyShape(
        ownerIsDataClass = (sourceCase.fir as? FirRegularClass)?.status?.isData == true,
        isMutable = property.fir.isVar,
        isPrimaryConstructorProperty = property.name in sourceCase.primaryConstructorPropertyNames(),
        hasAccessibleSetter = property.fir.hasKPathsAccessibleSetter(),
        copyWritableVals = root.generationOptions()?.copyWritableVals ?: false,
        isExcludedFromCopyWritableVals = root.generationOptions()?.copyWritableValExclusions
          ?.any { it.callableId == property.callableId } == true,
      ).classifyForKPaths()
      val pathClass = when ((shape as? KPathsPropertyPolicyResult.Supported)?.kind) {
        KPathsPropertyPathKind.REFERENCE_WRITABLE -> referenceWritableKeyPathClassId
        KPathsPropertyPathKind.COPY_WRITABLE, KPathsPropertyPathKind.COPY_THEN_MUTATE -> writableKeyPathClassId
        else -> keyPathClassId
      }
      val pathType = pathClass.createConeType(session, arrayOf(
        caseType.toTypeProjection(Variance.INVARIANT), valueType.toTypeProjection(Variance.INVARIANT),
      ), false)
      val adaptedType = adaptedPathType(pathsClass, root, property, caseType, valueType, pathType)
      return@with listOf(createMemberProperty(pathsClass, PathsGenericCasePropertyCatalogKey,
        callableId.callableName, adaptedType, true, false).symbol)
    }
    if (pathsClass.isGeneratedGenericCasePath()) {
      val root = pathsClass.genericCaseRoot()?.fir as? FirRegularClass ?: return@with emptyList()
      val sourceCase = pathsClass.genericSourceCase()?.fir as? FirRegularClass ?: return@with emptyList()
      val (rootType, caseType) = kPathsGenericCaseTypes(root, sourceCase, pathsClass.fir.typeParameters, session)
        ?: return@with emptyList()
      val metadataType = when (callableId.callableName.asString()) {
        "__kpaths_id" -> pathIdClassId.createConeType(session)
        "__kpaths_valueType" -> typeIdClassId.createConeType(session)
        else -> null
      }
      if (metadataType != null) return@with listOf(createMemberProperty(pathsClass, PathsGenericCaseDeclarationKey,
        callableId.callableName, metadataType, true, false).symbol)
      val properties = pathsClass.sourceCasePayloadProperties()
      val caseArguments = (caseType as ConeClassLikeType).typeArguments.map {
        (it as? ConeKotlinTypeProjection)?.type ?: return@with emptyList()
      }
      val substitution = sourceCase.typeParameters.map { it.symbol }.zip(caseArguments).toMap()
      val substitutor = ConeSubstitutorByMap.create(substitution, session, false)
      val payloadType = when {
        callableId.callableName.asString() == "unit" && sourceCase.classKind == ClassKind.OBJECT ->
          unitClassId.createConeType(session)
        callableId.callableName.asString() == "payload" && properties.size in 2..3 -> {
          val tuple = org.jetbrains.kotlin.name.ClassId.topLevel(
            FqName(if (properties.size == 2) "kotlin.Pair" else "kotlin.Triple"))
          tuple.createConeType(session, properties.map {
            substitutor.substituteOrSelf(it.resolvedReturnType).toTypeProjection(Variance.INVARIANT)
          }.toTypedArray(), false)
        }
        else -> properties.singleOrNull { it.name == callableId.callableName }
          ?.let { substitutor.substituteOrSelf(it.resolvedReturnType) }
      } ?: return@with emptyList()
      val pathType = caseKeyPathClassId.createConeType(session, arrayOf(
        rootType.toTypeProjection(Variance.INVARIANT), payloadType.toTypeProjection(Variance.INVARIANT),
      ), false)
      return@with listOf(createMemberProperty(pathsClass, PathsGenericCaseDeclarationKey,
        callableId.callableName, pathType, true, false).symbol)
    }
    if (pathsClass.isGeneratedGenericCatalog()) {
      val root = pathsClass.genericCatalogRoot() ?: return@with emptyList()
      val arguments = pathsClass.fir.typeParameters.map {
        ConeTypeParameterTypeImpl(it.symbol.toLookupTag(), false, ConeAttributes.Empty)
      }
      val rootType = root.classId.createConeType(session, arguments.map {
        it.toTypeProjection(Variance.INVARIANT)
      }.toTypedArray(), false)
      val propertyCatalog = root.caseSymbols().singleOrNull {
        it.propertyCatalogName(allowGeneric = true) == callableId.callableName
      }
      if (propertyCatalog != null) {
        val caseClass = propertyCatalog.sealedSymbol?.fir as? FirRegularClass ?: return@with emptyList()
        val rootClass = root.fir as? FirRegularClass ?: return@with emptyList()
        val (_, caseType) = kPathsGenericCaseTypes(rootClass, caseClass, pathsClass.fir.typeParameters, session)
          ?: return@with emptyList()
        val caseArguments = (caseType as ConeClassLikeType).typeArguments
        val pathType = pathsClass.classId.createNestedClassId(requireNotNull(propertyCatalog.genericPropertyCatalogClassName()))
          .createConeType(session, Array<org.jetbrains.kotlin.fir.types.ConeTypeProjection>(caseArguments.size) { caseArguments[it] }, false)
        return@with listOf(createMemberProperty(pathsClass, PathsGenericCatalogKey,
          callableId.callableName, pathType, true, false).symbol)
      }
      val concreteCase = root.caseSymbols().singleOrNull {
        it.name == callableId.callableName && it.sealedSymbol != null
      }
      if (concreteCase != null) {
        val caseSymbol = requireNotNull(concreteCase.sealedSymbol)
        val caseClass = caseSymbol.fir as? FirRegularClass ?: return@with emptyList()
        val rootClass = root.fir as? FirRegularClass ?: return@with emptyList()
        kPathsGenericCaseTypes(rootClass, caseClass, pathsClass.fir.typeParameters, session)
          ?: return@with emptyList()
        val pathType = pathsClass.classId.createNestedClassId(Name.identifier(concreteCase.name.asString() + "CasePath"))
          .createConeType(session, arguments.map { it.toTypeProjection(Variance.INVARIANT) }.toTypedArray(), false)
        return@with listOf(createMemberProperty(pathsClass, PathsGenericCatalogKey,
          callableId.callableName, pathType, true, false).symbol)
      }
      val property = root.sourceDeclaredProperties()
        .singleOrNull { it.fir.generatedKPathsName() == callableId.callableName } ?: return@with emptyList()
      val substitution = root.fir.typeParameters.map { it.symbol }.zip(arguments).toMap()
      val valueType = ConeSubstitutorByMap.create(substitution, session, false).substituteOrSelf(property.resolvedReturnType)
      val kind = KPathsPropertyShape(
        ownerIsDataClass = (root.fir as? FirRegularClass)?.status?.isData == true,
        isMutable = property.fir.isVar,
        isPrimaryConstructorProperty = property.name in root.primaryConstructorPropertyNames(),
        hasAccessibleSetter = property.fir.hasKPathsAccessibleSetter(),
        copyWritableVals = root.generationOptions()?.copyWritableVals ?: false,
        isExcludedFromCopyWritableVals = root.generationOptions()?.copyWritableValExclusions
          ?.any { it.callableId == property.callableId } == true,
      ).classifyForKPaths()
      val pathClass = when ((kind as? KPathsPropertyPolicyResult.Supported)?.kind) {
        KPathsPropertyPathKind.REFERENCE_WRITABLE -> referenceWritableKeyPathClassId
        KPathsPropertyPathKind.COPY_WRITABLE, KPathsPropertyPathKind.COPY_THEN_MUTATE -> writableKeyPathClassId
        else -> keyPathClassId
      }
      val resultType = pathClass.createConeType(session, arrayOf(
        rootType.toTypeProjection(Variance.INVARIANT), valueType.toTypeProjection(Variance.INVARIANT),
      ), false)
      val adaptedType = adaptedPathType(pathsClass, root, property, rootType, valueType, resultType)
      return@with listOf(createMemberProperty(pathsClass, PathsGenericCatalogKey, callableId.callableName,
        adaptedType, true, false).symbol)
    }
    if (pathsClass.isGeneratedCasePropertyCatalog()) {
      val sourceCase = pathsClass.propertyCatalogCase() ?: return@with emptyList()
      val sourceProperty = sourceCase.sourceDeclaredProperties()
        .singleOrNull { it.fir.generatedKPathsName() == callableId.callableName } ?: return@with emptyList()
      val shape = KPathsPropertyShape(
        ownerIsDataClass = (sourceCase.fir as? FirRegularClass)?.status?.isData == true,
        isMutable = sourceProperty.fir.isVar,
        isPrimaryConstructorProperty = sourceProperty.name in sourceCase.primaryConstructorPropertyNames(),
        hasAccessibleSetter = sourceProperty.fir.hasKPathsAccessibleSetter(),
        copyWritableVals = pathsClass.propertyCatalogRoot()?.generationOptions()?.copyWritableVals ?: false,
        isExcludedFromCopyWritableVals = pathsClass.propertyCatalogRoot()?.generationOptions()
          ?.copyWritableValExclusions?.any { it.callableId == sourceProperty.callableId } == true,
      ).classifyForKPaths()
      val pathType = when ((shape as? KPathsPropertyPolicyResult.Supported)?.kind) {
        KPathsPropertyPathKind.REFERENCE_WRITABLE -> referenceWritableKeyPathClassId
        KPathsPropertyPathKind.COPY_WRITABLE, KPathsPropertyPathKind.COPY_THEN_MUTATE -> writableKeyPathClassId
        else -> keyPathClassId
      }.createConeType(session, arrayOf(
        sourceCase.classId.createConeType(session).toTypeProjection(Variance.INVARIANT),
        sourceProperty.resolvedReturnType.toTypeProjection(Variance.INVARIANT),
      ), false)
      val owner = pathsClass.propertyCatalogRoot() ?: return@with emptyList()
      val adaptedType = adaptedPathType(pathsClass, owner, sourceProperty,
        sourceCase.classId.createConeType(session), sourceProperty.resolvedReturnType, pathType)
      return@with listOf(createMemberProperty(pathsClass, PathsCasePropertyCatalogKey, callableId.callableName, adaptedType, true, false).symbol)
    }
    if (pathsClass.canGenerateCasePathableConformance()) {
      if (callableId.callableName.asString() != "case") return@with emptyList()
      val rootType = pathsClass.classId.createConeType(session)
      val returnType = partialCaseKeyPathClassId.createConeType(
        session,
        arrayOf(rootType.toTypeProjection(Variance.INVARIANT)),
        false,
      )
      return@with listOf(
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
      val root = pathsClass.casePathRootClass() ?: return@with emptyList()
      val sourceCase = pathsClass.sourceCase() ?: return@with emptyList()
      val metadataType = when (callableId.callableName.asString()) {
        "__kpaths_id" -> pathIdClassId.createConeType(session)
        "__kpaths_valueType" -> typeIdClassId.createConeType(session)
        else -> null
      }
      if (metadataType != null) {
        return@with listOf(
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
        return@with listOf(createMemberProperty(pathsClass, PathsCaseDeclarationKey, callableId.callableName, returnType, true, false).symbol)
      }
      val properties = pathsClass.sourceCasePayloadProperties()
      val payloadType = if (callableId.callableName.asString() == "payload" && properties.size in 2..3) {
        val tupleId = org.jetbrains.kotlin.name.ClassId.topLevel(
          FqName(if (properties.size == 2) "kotlin.Pair" else "kotlin.Triple"),
        )
        tupleId.createConeType(session, properties.map { it.resolvedReturnType.toTypeProjection(Variance.INVARIANT) }.toTypedArray(), false)
      } else {
        properties.singleOrNull { it.name == callableId.callableName }?.resolvedReturnType
      } ?: return@with emptyList()
      val returnType = caseKeyPathClassId.createConeType(
        session,
        arrayOf(
          root.classId.createConeType(session).toTypeProjection(Variance.INVARIANT),
          payloadType.toTypeProjection(Variance.INVARIANT),
        ),
        false,
      )
      return@with listOf(createMemberProperty(pathsClass, PathsCaseDeclarationKey, callableId.callableName, returnType, true, false).symbol)
    }
    val rootClass = pathsClass.rootClass() ?: return@with emptyList()
    val rootType = rootClass.classId.createConeType(session)
    val case = rootClass.caseSymbols()
      .singleOrNull { it.name == callableId.callableName }
    val sourceProperty = rootClass.sourceDeclaredProperties()
      .singleOrNull { it.fir.generatedKPathsName() == callableId.callableName }
    if (case != null) {
      if (rootClass.fir.typeParameters.isNotEmpty() || case.hasTypeParameters) {
        return@with emptyList()
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
      return@with listOf(
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
    if (sourceProperty == null) return@with emptyList()
    if (rootClass.fir.typeParameters.isNotEmpty() || sourceProperty.fir.typeParameters.isNotEmpty()) {
      return@with emptyList()
    }
    val pathKind = KPathsPropertyShape(
      ownerIsDataClass = (rootClass.fir as? FirRegularClass)?.status?.isData == true,
      isMutable = sourceProperty.fir.isVar,
      isPrimaryConstructorProperty = sourceProperty.name in rootClass.primaryConstructorPropertyNames(),
      hasAccessibleSetter = sourceProperty.fir.hasKPathsAccessibleSetter(),
      copyWritableVals = rootClass.generationOptions()?.copyWritableVals ?: false,
      isExcludedFromCopyWritableVals = rootClass.generationOptions()?.copyWritableValExclusions
        ?.any { it.callableId == sourceProperty.callableId } == true,
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
    return@with listOf(
      createMemberProperty(
        pathsClass,
        PathsDeclarationKey,
        callableId.callableName,
        adaptedPathType(pathsClass, rootClass, sourceProperty, rootType, sourceProperty.resolvedReturnType, returnType),
        isVal = true,
        hasBackingField = false,
      ).symbol,
    )
  }

  private fun adaptedPathType(
    catalog: FirClassSymbol<*>, owner: FirClassSymbol<*>, property: FirPropertySymbol,
    rootType: ConeKotlinType, storageType: ConeKotlinType, defaultType: ConeKotlinType,
  ): ConeKotlinType = with(analysis) {
    val adapters = owner.generationOptions()?.propertyPathAdapters.orEmpty()
    val context = KPathsCompilerPropertyContext(session, property, rootType, storageType, defaultType)
    val recipe = when (val selection = selectCompilerPropertyAdapter(context, adapters)) {
      is CompilerAdapterAnalysis.Available -> selection.recipe
      is CompilerAdapterAnalysis.Invalid, CompilerAdapterAnalysis.Absent -> return@with defaultType
    }
    val defaultPathClass = (defaultType as ConeClassLikeType).lookupTag.classId
    session.kpaths.adapterBridge.record(CallableId(catalog.classId, property.fir.generatedKPathsName()), recipe, defaultPathClass)
    recipe.publicPathType
  }

}
