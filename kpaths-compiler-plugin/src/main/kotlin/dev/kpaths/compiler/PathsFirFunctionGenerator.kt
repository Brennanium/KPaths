package dev.kpaths.compiler

import dev.kpaths.codegen.model.planKPathsBoundPayloadMembers
import dev.kpaths.compiler.PathsFirSymbols.caseKeyPathClassId
import dev.kpaths.compiler.PathsFirSymbols.caseMatchClassId
import dev.kpaths.compiler.PathsFirSymbols.listClassId
import dev.kpaths.compiler.PathsFirSymbols.partialCaseKeyPathClassId
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutorByMap
import org.jetbrains.kotlin.fir.extensions.DeclarationGenerationContext
import org.jetbrains.kotlin.fir.extensions.FirExtension
import org.jetbrains.kotlin.fir.plugin.createConeType
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.toTypeProjection
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance

/** Generates FIR functions for case matching, embedding, and bound payloads. */
@OptIn(DirectDeclarationsAccess::class, ExperimentalKPathsCompilerApi::class, SymbolInternals::class)
internal class PathsFirFunctionGenerator(
  private val session: FirSession,
  private val analysis: PathsFirAnalysis,
) {
  fun FirExtension.generateFunctionDeclarations(
    callableId: org.jetbrains.kotlin.name.CallableId,
    context: DeclarationGenerationContext.Member?,
  ): List<FirNamedFunctionSymbol> = with(analysis) {
    val pathsClass = context?.owner ?: return@with emptyList()
    if (pathsClass.isGeneratedPaths() && callableId.callableName.asString() in setOf("invoke", "of")) {
      val root = pathsClass.rootClass() ?: return@with emptyList()
      if (root.fir.typeParameters.isEmpty()) return@with emptyList()
      val catalogId = pathsClass.classId.createNestedClassId(Name.identifier("__kpaths_Catalog"))
      val reifiedFactory = createMemberFunction(pathsClass, PathsDeclarationKey, callableId.callableName, { parameters ->
        catalogId.createConeType(session, parameters.map {
          it.symbol.kPathsTypeParameterType()
            .toTypeProjection(Variance.INVARIANT)
        }.toTypedArray(), false)
      }) {
        copyKPathsTypeParameters(root.fir.typeParameters, session, reified = true)
        status { isOperator = callableId.callableName.asString() == "invoke"; isInline = true }
        withGeneratedDefaultBody()
      }.symbol
      if (callableId.callableName.asString() != "of") return@with listOf(reifiedFactory)
      val classFactory = createMemberFunction(pathsClass, PathsDeclarationKey, callableId.callableName, { parameters ->
        catalogId.createConeType(session, parameters.map {
          it.symbol.kPathsTypeParameterType()
            .toTypeProjection(Variance.INVARIANT)
        }.toTypedArray(), false)
      }) {
        copyKPathsTypeParameters(root.fir.typeParameters, session, forceNonNull = true)
        val kClass = org.jetbrains.kotlin.name.ClassId.topLevel(org.jetbrains.kotlin.name.FqName("kotlin.reflect.KClass"))
        root.fir.typeParameters.forEachIndexed { index, parameter ->
          valueParameter(Name.identifier("${parameter.symbol.name}Type"), { generated ->
            val argument = generated[index].symbol.kPathsTypeParameterType()
            kClass.createConeType(session, arrayOf(argument.toTypeProjection(Variance.INVARIANT)), false)
          })
        }
        withGeneratedDefaultBody()
      }.symbol
      val typeIdFactory = createMemberFunction(pathsClass, PathsDeclarationKey, callableId.callableName, { parameters ->
        catalogId.createConeType(session, parameters.map {
          it.symbol.kPathsTypeParameterType()
            .toTypeProjection(Variance.INVARIANT)
        }.toTypedArray(), false)
      }) {
        copyKPathsTypeParameters(root.fir.typeParameters, session)
        root.fir.typeParameters.forEach { parameter ->
          valueParameter(Name.identifier("${parameter.symbol.name}TypeId"),
            PathsFirSymbols.typeIdClassId.createConeType(session))
        }
        withGeneratedDefaultBody()
      }.symbol
      return@with listOf(reifiedFactory, classFactory, typeIdFactory)
    }
    if ((pathsClass.isGeneratedPaths() || pathsClass.isGeneratedGenericCatalog()) &&
      callableId.callableName.asString() == "__kpaths_buildAllCasePaths") {
      val generic = pathsClass.isGeneratedGenericCatalog()
      val root = (if (generic) pathsClass.genericCatalogRoot() else pathsClass.rootClass()) ?: return@with emptyList()
      if (root.caseSymbols().isEmpty()) return@with emptyList()
      val rootType = if (generic) root.classId.createConeType(session, pathsClass.fir.typeParameters.map {
        it.symbol.kPathsTypeParameterType()
          .toTypeProjection(Variance.INVARIANT)
      }.toTypedArray(), false) else root.classId.createConeType(session)
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
      return@with listOf(
        createMemberFunction(
          pathsClass,
          if (generic) PathsGenericCatalogKey else PathsDeclarationKey,
          callableId.callableName,
          returnType,
        ) {
          withGeneratedDefaultBody()
        }.symbol,
      )
    }
    val isGeneric = pathsClass.isGeneratedGenericCasePath()
    if (!pathsClass.isGeneratedCasePath() && !isGeneric) return@with emptyList()
    val root = (if (isGeneric) pathsClass.genericCaseRoot() else pathsClass.casePathRootClass()) ?: return@with emptyList()
    val sourceCase = (if (isGeneric) pathsClass.genericSourceCase() else pathsClass.sourceCase()) ?: return@with emptyList()
    val (rootType, caseType) = if (isGeneric) {
      kPathsGenericCaseTypes(root.fir as? FirRegularClass ?: return@with emptyList(),
        sourceCase.fir as? FirRegularClass ?: return@with emptyList(), pathsClass.fir.typeParameters, session)
        ?: return@with emptyList()
    } else root.classId.createConeType(session) to sourceCase.classId.createConeType(session)
    val key = if (isGeneric) PathsGenericCaseDeclarationKey else PathsCaseDeclarationKey
    val substitutor = if (isGeneric) {
      val arguments = (caseType as ConeClassLikeType).typeArguments.map {
        (it as? ConeKotlinTypeProjection)?.type ?: return@with emptyList()
      }
      ConeSubstitutorByMap.create(sourceCase.fir.typeParameters.map { it.symbol }.zip(arguments).toMap(), session, false)
    } else null
    val payloadProperties = pathsClass.sourceCasePayloadProperties()
    val boundMember = planKPathsBoundPayloadMembers(payloadProperties.map { it.name.asString() })
      .singleOrNull { it.generatedName == callableId.callableName.asString() }
    if (boundMember != null) {
      val originalTargetType = payloadProperties[boundMember.targetIndex].resolvedReturnType
      val targetType = substitutor?.substituteOrSelf(originalTargetType) ?: originalTargetType
      val returnType = caseKeyPathClassId.createConeType(session, arrayOf(
        rootType.toTypeProjection(Variance.INVARIANT), targetType.toTypeProjection(Variance.INVARIANT),
      ), false)
      return@with listOf(createMemberFunction(pathsClass, key, callableId.callableName, returnType) {
        payloadProperties.filterIndexed { index, _ -> index != boundMember.targetIndex }.forEach { property ->
          valueParameter(property.name, substitutor?.substituteOrSelf(property.resolvedReturnType) ?: property.resolvedReturnType)
        }
        withGeneratedDefaultBody()
      }.symbol)
    }
    val function = when (callableId.callableName.asString()) {
      "match" -> createMemberFunction(
        pathsClass,
        key,
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
        key,
        callableId.callableName,
        rootType,
      ) {
        valueParameter(Name.identifier("value"), caseType)
        withGeneratedDefaultBody()
      }
      else -> null
    } ?: return@with emptyList()
    return@with listOf(function.symbol)
  }


}
