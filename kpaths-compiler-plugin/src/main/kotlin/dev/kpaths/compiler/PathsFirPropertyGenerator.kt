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
import org.jetbrains.kotlin.fir.types.impl.ConeTypeParameterTypeImpl
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutorByMap
import org.jetbrains.kotlin.name.FqName
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
    if (pathsClass.isGeneratedGenericCatalog()) {
      val root = pathsClass.genericCatalogRoot() ?: return@with emptyList()
      val property = root.sourceDeclaredProperties()
        .singleOrNull { it.fir.generatedKPathsName() == callableId.callableName } ?: return@with emptyList()
      val arguments = pathsClass.fir.typeParameters.map {
        ConeTypeParameterTypeImpl(it.symbol.toLookupTag(), false, ConeAttributes.Empty)
      }
      val substitution = root.fir.typeParameters.map { it.symbol }.zip(arguments).toMap()
      val valueType = ConeSubstitutorByMap.create(substitution, session, false).substituteOrSelf(property.resolvedReturnType)
      val rootType = root.classId.createConeType(session, arguments.map {
        it.toTypeProjection(Variance.INVARIANT)
      }.toTypedArray(), false)
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
      return@with listOf(createMemberProperty(pathsClass, PathsGenericCatalogKey, callableId.callableName,
        resultType, true, false).symbol)
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
      return@with listOf(createMemberProperty(pathsClass, PathsCasePropertyCatalogKey, callableId.callableName, pathType, true, false).symbol)
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
        returnType,
        isVal = true,
        hasBackingField = false,
      ).symbol,
    )
  }

}
