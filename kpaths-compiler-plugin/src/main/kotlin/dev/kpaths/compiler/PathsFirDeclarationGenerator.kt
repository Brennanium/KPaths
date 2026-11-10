package dev.kpaths.compiler

import dev.kpaths.codegen.model.KPathsCaseKind
import dev.kpaths.codegen.model.KPathsCasePayload
import dev.kpaths.codegen.model.planKPathsBoundPayloadMembers
import dev.kpaths.compiler.PathsFirPredicates.metaPathablePredicate
import dev.kpaths.compiler.PathsFirPredicates.pathablePredicate
import dev.kpaths.compiler.PathsFirSymbols.caseKeyPathBaseClassId
import dev.kpaths.compiler.PathsFirSymbols.casePathCatalogBaseClassId
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.extensions.DeclarationGenerationContext
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.plugin.createConeType
import org.jetbrains.kotlin.fir.plugin.createConstructor
import org.jetbrains.kotlin.fir.plugin.createNestedClass
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.toTypeProjection
import org.jetbrains.kotlin.fir.types.ConeAttributes
import org.jetbrains.kotlin.fir.types.impl.ConeTypeParameterTypeImpl
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.types.Variance

/**
 * Generates a nested `Paths` object for each opted-in class.
 *
 * Dispatches FIR's declaration callbacks to session analysis and focused property and function
 * generators. The nested catalog is a real `Person.Paths` declaration without a companion object.
 */
@OptIn(DirectDeclarationsAccess::class, ExperimentalKPathsCompilerApi::class, SymbolInternals::class)
internal class PathsDeclarationGenerator(session: FirSession) : FirDeclarationGenerationExtension(session) {
  private val analysis = PathsFirAnalysis(session)
  private val propertyGenerator = PathsFirPropertyGenerator(session, analysis)
  private val functionGenerator = PathsFirFunctionGenerator(session, analysis)

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(pathablePredicate)
    register(metaPathablePredicate)
  }

  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: DeclarationGenerationContext.Nested,
  ): Set<Name> = with(analysis) {
    when {
      classSymbol.catalogName() != null -> {
        val name = requireNotNull(classSymbol.catalogName())
        if ((classSymbol.fir as? FirRegularClass)?.hasKPathsCatalogCollision(name.asString()) == true) emptySet()
        else setOf(name)
      }
      classSymbol.isGeneratedPaths() && classSymbol.rootClass()?.fir?.typeParameters?.isNotEmpty() == true ->
        setOf(Name.identifier("__kpaths_Catalog"))
      classSymbol.isGeneratedPaths() -> classSymbol.rootClass()
        ?.caseSymbols()
        ?.filter { it.kind == KPathsCaseKind.SEALED_VARIANT && !it.hasTypeParameters }
        ?.flatMap { case ->
          listOf(case.name) + listOfNotNull(case.propertyCatalogName())
        }?.toMutableSet()
        ?: emptySet()
      classSymbol.isGeneratedGenericCatalog() -> classSymbol.genericCatalogRoot()?.caseSymbols()
        ?.filter { it.sealedSymbol != null }
        ?.flatMap { listOfNotNull(Name.identifier(it.name.asString() + "CasePath"), it.genericPropertyCatalogClassName()) }
        ?.toMutableSet() ?: emptySet()
      else -> emptySet()
    }
  }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: DeclarationGenerationContext.Nested,
  ): FirClassLikeSymbol<*>? = with(analysis) {
    if (name == owner.catalogName()) {
      if ((owner.fir as? FirRegularClass)?.hasKPathsCatalogCollision(name.asString()) == true) return@with null
      return@with createNestedClass(owner, name, PathsDeclarationKey, ClassKind.OBJECT) {
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
      val root = owner.rootClass() ?: return@with null
      if (name.asString() == "__kpaths_Catalog" && root.fir.typeParameters.isNotEmpty()) {
        return@with createNestedClass(owner, name, PathsGenericCatalogKey, ClassKind.CLASS) {
          copyKPathsTypeParameters(root.fir.typeParameters, session)
          if (root.caseSymbols().isNotEmpty()) {
            superType { parameters ->
              val rootType = root.classId.createConeType(session, parameters.map {
                ConeTypeParameterTypeImpl(it.symbol.toLookupTag(), false, ConeAttributes.Empty)
                  .toTypeProjection(Variance.INVARIANT)
              }.toTypedArray(), false)
              casePathCatalogBaseClassId.createConeType(session,
                arrayOf(rootType.toTypeProjection(Variance.INVARIANT)), false)
            }
          }
        }.symbol
      }
      val propertyCase = root.caseSymbols().singleOrNull { it.propertyCatalogName() == name }
      if (propertyCase != null) {
        return@with createNestedClass(owner, name, PathsCasePropertyCatalogKey, ClassKind.OBJECT).symbol
      }
      val case = root.caseSymbols().singleOrNull { it.name == name } ?: return@with null
      if (case.kind != KPathsCaseKind.SEALED_VARIANT || case.hasTypeParameters) return@with null
      val rootType = root.classId.createConeType(session)
      val caseType = requireNotNull(case.sealedSymbol).classId.createConeType(session)
      return@with createNestedClass(owner, name, PathsCaseDeclarationKey, ClassKind.OBJECT) {
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
    if (owner.isGeneratedGenericCatalog()) {
      val root = owner.genericCatalogRoot()?.fir as? FirRegularClass ?: return@with null
      val propertyCase = root.symbol.caseSymbols().singleOrNull { it.genericPropertyCatalogClassName() == name }
      if (propertyCase != null) {
        val case = propertyCase.sealedSymbol?.fir as? FirRegularClass ?: return@with null
        return@with createNestedClass(owner, name, PathsGenericCasePropertyCatalogKey, ClassKind.CLASS) {
          copyKPathsTypeParameters(case.typeParameters, session)
        }.symbol
      }
      val sourceCase = root.symbol.caseSymbols().singleOrNull { it.name.asString() + "CasePath" == name.asString() }
        ?.sealedSymbol?.fir as? FirRegularClass ?: return@with null
      return@with createNestedClass(owner, name, PathsGenericCaseDeclarationKey, ClassKind.CLASS) {
        copyKPathsTypeParameters(root.typeParameters, session)
        superType { parameters ->
          val (rootType, caseType) = requireNotNull(kPathsGenericCaseTypes(root, sourceCase, parameters, session))
          caseKeyPathBaseClassId.createConeType(session, arrayOf(
            rootType.toTypeProjection(Variance.INVARIANT), caseType.toTypeProjection(Variance.INVARIANT),
          ), false)
        }
      }.symbol
    }
    return@with null
  }

  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: DeclarationGenerationContext.Member,
  ): Set<Name> = with(analysis) {
    when {
      classSymbol.canGenerateCasePathableConformance() -> setOf(Name.identifier("case"))
      classSymbol.isGeneratedGenericCatalog() -> buildSet {
        add(SpecialNames.INIT)
        addAll(classSymbol.genericCatalogRoot()?.sourceDeclaredProperties()?.map { it.fir.generatedKPathsName() }.orEmpty())
        addAll(classSymbol.genericCatalogRoot()?.caseSymbols()?.filter { it.sealedSymbol != null }
          ?.map { it.name }.orEmpty())
        addAll(classSymbol.genericCatalogRoot()?.caseSymbols()
          ?.mapNotNull { it.propertyCatalogName(allowGeneric = true) }.orEmpty())
        if (classSymbol.genericCatalogRoot()?.caseSymbols().orEmpty().isNotEmpty()) {
          add(Name.identifier("__kpaths_buildAllCasePaths"))
        }
      }
      classSymbol.isGeneratedGenericCasePropertyCatalog() -> buildSet {
        add(SpecialNames.INIT)
        addAll(classSymbol.genericCasePropertySource()?.sourceDeclaredProperties()?.map { it.fir.generatedKPathsName() }.orEmpty())
      }
      classSymbol.isGeneratedCasePropertyCatalog() -> buildSet {
        add(SpecialNames.INIT)
        addAll(classSymbol.propertyCatalogCase()?.sourceDeclaredProperties()?.map { it.fir.generatedKPathsName() }.orEmpty())
      }
      classSymbol.isGeneratedCasePath() || classSymbol.isGeneratedGenericCasePath() -> classSymbol.sourceCasePayloadProperties().let { properties ->
        KPathsCasePayload.fromProperties(properties.map { it.name.asString() })
          ?.let { mutableSetOf(Name.identifier(it.generatedMemberName)) }
          ?: mutableSetOf()
      }
        .also {
          val properties = classSymbol.sourceCasePayloadProperties()
          it += planKPathsBoundPayloadMembers(properties.map { property -> property.name.asString() })
            .map { member -> Name.identifier(member.generatedName) }
          if ((if (classSymbol.isGeneratedGenericCasePath()) classSymbol.genericSourceCase() else classSymbol.sourceCase())
              ?.fir?.classKind == ClassKind.OBJECT) it += Name.identifier("unit")
          it += SpecialNames.INIT
          it += Name.identifier("__kpaths_id")
          it += Name.identifier("__kpaths_valueType")
          it += Name.identifier("match")
          it += Name.identifier("embed")
        }
      !classSymbol.isGeneratedPaths() -> emptySet()
      else -> buildSet {
        add(SpecialNames.INIT)
        if (classSymbol.rootClass()?.fir?.typeParameters?.isNotEmpty() == true) {
          add(Name.identifier("invoke"))
          add(Name.identifier("of"))
        }
        addAll(classSymbol.rootClass()?.sourceDeclaredProperties()?.map { it.fir.generatedKPathsName() }.orEmpty())
        addAll(classSymbol.rootClass()?.caseSymbols()?.map { it.name }.orEmpty())
        if (classSymbol.rootClass()?.caseSymbols().orEmpty().isNotEmpty()) {
          add(Name.identifier("__kpaths_buildAllCasePaths"))
        }
      }
    }
  }

  override fun generateConstructors(
    context: DeclarationGenerationContext.Member,
  ): List<FirConstructorSymbol> = with(analysis) {
    val key = when {
      context.owner.isGeneratedPaths() -> PathsDeclarationKey
      context.owner.isGeneratedCasePath() -> PathsCaseDeclarationKey
      context.owner.isGeneratedCasePropertyCatalog() -> PathsCasePropertyCatalogKey
      context.owner.isGeneratedGenericCatalog() -> PathsGenericCatalogKey
      context.owner.isGeneratedGenericCasePath() -> PathsGenericCaseDeclarationKey
      context.owner.isGeneratedGenericCasePropertyCatalog() -> PathsGenericCasePropertyCatalogKey
      else -> return@with emptyList()
    }
    return@with listOf(createConstructor(context.owner, key, true) {
      if (key == PathsGenericCatalogKey || key == PathsGenericCaseDeclarationKey || key == PathsGenericCasePropertyCatalogKey) {
        val root = when (key) {
          PathsGenericCatalogKey -> context.owner.genericCatalogRoot()
          PathsGenericCaseDeclarationKey -> context.owner.genericCaseRoot()
          else -> context.owner.genericCasePropertySource()
        }
        root?.fir?.typeParameters.orEmpty().forEach { parameter ->
          valueParameter(Name.identifier("__kpaths_type_${parameter.symbol.name}"),
            PathsFirSymbols.typeIdClassId.createConeType(session))
        }
      }
    }.symbol)
  }

  override fun generateProperties(
    callableId: org.jetbrains.kotlin.name.CallableId,
    context: DeclarationGenerationContext.Member?,
  ): List<FirPropertySymbol> = with(propertyGenerator) {
    this@PathsDeclarationGenerator.generatePropertyDeclarations(callableId, context)
  }

  override fun generateFunctions(
    callableId: org.jetbrains.kotlin.name.CallableId,
    context: DeclarationGenerationContext.Member?,
  ): List<FirNamedFunctionSymbol> = with(functionGenerator) {
    this@PathsDeclarationGenerator.generateFunctionDeclarations(callableId, context)
  }
}
