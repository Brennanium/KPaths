package dev.kpaths.compiler

import dev.kpaths.codegen.model.planKPathsBoundPayloadMembers
import dev.kpaths.compiler.PathsFirSymbols.caseKeyPathClassId
import dev.kpaths.compiler.PathsFirSymbols.caseMatchClassId
import dev.kpaths.compiler.PathsFirSymbols.listClassId
import dev.kpaths.compiler.PathsFirSymbols.partialCaseKeyPathClassId
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.extensions.DeclarationGenerationContext
import org.jetbrains.kotlin.fir.extensions.FirExtension
import org.jetbrains.kotlin.fir.plugin.createConeType
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.toTypeProjection
import org.jetbrains.kotlin.fir.types.ConeAttributes
import org.jetbrains.kotlin.fir.types.impl.ConeTypeParameterTypeImpl
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
      return@with listOf(createMemberFunction(pathsClass, PathsDeclarationKey, callableId.callableName, { parameters ->
        catalogId.createConeType(session, parameters.map {
          ConeTypeParameterTypeImpl(it.symbol.toLookupTag(), false, ConeAttributes.Empty)
            .toTypeProjection(Variance.INVARIANT)
        }.toTypedArray(), false)
      }) {
        copyKPathsTypeParameters(root.fir.typeParameters, session, reified = true)
        status { isOperator = callableId.callableName.asString() == "invoke"; isInline = true }
        withGeneratedDefaultBody()
      }.symbol)
    }
    if (pathsClass.isGeneratedPaths() && callableId.callableName.asString() == "__kpaths_buildAllCasePaths") {
      val root = pathsClass.rootClass() ?: return@with emptyList()
      if (!root.canGenerateCaseCatalog()) return@with emptyList()
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
      return@with listOf(
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
    if (!pathsClass.isGeneratedCasePath()) return@with emptyList()
    val root = pathsClass.casePathRootClass() ?: return@with emptyList()
    val sourceCase = pathsClass.sourceCase() ?: return@with emptyList()
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
      return@with listOf(createMemberFunction(pathsClass, PathsCaseDeclarationKey, callableId.callableName, returnType) {
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
    } ?: return@with emptyList()
    return@with listOf(function.symbol)
  }


}
