package dev.kpaths.ksp.codegen

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Variance
import dev.kpaths.annotations.PathsAdapter
import dev.kpaths.codegen.model.KPathsAdapterFactoryShape
import dev.kpaths.codegen.model.KPathsAdapterPlan
import dev.kpaths.codegen.model.KPathsAdapterType
import dev.kpaths.codegen.model.planKPathsAdapter

/** KSP projection of the shared declarative factory contract. */
internal data class DeclarativeAdapter(val factoryName: String, val publicType: String)

internal sealed interface DeclarativeAdapterResult {
  data object Absent : DeclarativeAdapterResult
  data class Available(val adapter: DeclarativeAdapter) : DeclarativeAdapterResult
  data class Invalid(val reason: String) : DeclarativeAdapterResult
}

internal fun declarativeAdapter(
  property: KSPropertyDeclaration,
  root: KSClassDeclaration,
  rootParameters: Map<String, String> = emptyMap(),
): DeclarativeAdapterResult {
  val storageType = property.type.resolve()
  val alias = storageType.declaration as? KSTypeAlias
  if (alias != null) {
    val target = alias.type.resolve().declaration as? KSClassDeclaration
    if (target?.annotations?.any {
        it.annotationType.resolve().declaration.qualifiedName?.asString() == PathsAdapter::class.qualifiedName
      } == true) {
      return DeclarativeAdapterResult.Invalid("type aliases of adapted storage types are not yet supported")
    }
  }
  val storage = storageType.declaration as? KSClassDeclaration ?: return DeclarativeAdapterResult.Absent
  val annotation = storage.annotations.firstOrNull {
    it.annotationType.resolve().declaration.qualifiedName?.asString() == PathsAdapter::class.qualifiedName
  } ?: return DeclarativeAdapterResult.Absent
  val factory = (annotation.arguments.firstOrNull { it.name?.asString() == "factory" }?.value as? KSType)
    ?.declaration as? KSClassDeclaration
    ?: return DeclarativeAdapterResult.Invalid("factory must name a resolvable object")
  val factoryName = factory.qualifiedName?.asString()
    ?: return DeclarativeAdapterResult.Invalid("factory must have a qualified name")
  val functions = factory.declarations.filterIsInstance<KSFunctionDeclaration>()
    .filter { it.simpleName.asString() == "make" }.toList()
  val function = functions.singleOrNull()
  val resultType = function?.returnType?.resolve()
  if (resultType != null && !resultType.isAdapterKeyPath()) {
    return DeclarativeAdapterResult.Invalid("make must return a KeyPath subtype")
  }
  val variables = function?.typeParameters?.map { it.name.asString() }?.toSet().orEmpty()
  val rootName = root.qualifiedName?.asString() ?: return DeclarativeAdapterResult.Invalid("root must have a qualified name")
  val rootType = KPathsAdapterType.Classifier(rootName, root.typeParameters.map {
    KPathsAdapterType.Parameter(rootParameters[it.name.asString()] ?: it.name.asString())
  })
  val actualStorage = storageType.toAdapterModel(emptySet(), rootParameters)
    ?: return DeclarativeAdapterResult.Invalid("storage type has unsupported projections or nullable type parameters")
  val shape = KPathsAdapterFactoryShape(
    functionCount = functions.size,
    isObjectFactory = factory.classKind == ClassKind.OBJECT,
    isAccessible = function != null && function.modifiers.none { it == Modifier.PRIVATE || it == Modifier.PROTECTED || it == Modifier.INTERNAL } &&
      factory.modifiers.none { it == Modifier.PRIVATE || it == Modifier.PROTECTED || it == Modifier.INTERNAL },
    isSuspend = function?.modifiers?.contains(Modifier.SUSPEND) == true,
    isExtension = function?.extensionReceiver != null,
    hasExplicitBounds = function?.typeParameters?.any { parameter ->
      parameter.bounds.map { it.resolve() }.any {
        it.declaration.qualifiedName?.asString() != "kotlin.Any" || !it.isMarkedNullable
      }
    } == true,
    parameterCount = function?.parameters?.size ?: 0,
    parameterType = function?.parameters?.singleOrNull()?.type?.resolve()?.toAdapterModel(variables),
    resultType = resultType?.toAdapterModel(variables),
    typeParameters = variables,
  )
  return when (val plan = planKPathsAdapter(shape, rootType, actualStorage)) {
    is KPathsAdapterPlan.Invalid -> DeclarativeAdapterResult.Invalid(plan.reason)
    is KPathsAdapterPlan.Supported -> DeclarativeAdapterResult.Available(
      DeclarativeAdapter(factoryName, plan.resultType.renderAdapterModel()),
    )
  }
}

private fun KSType.isAdapterKeyPath(visited: MutableSet<String> = mutableSetOf()): Boolean {
  val name = declaration.qualifiedName?.asString() ?: return false
  if (name == "dev.kpaths.core.KeyPath") return true
  if (!visited.add(name)) return false
  return (declaration as? KSClassDeclaration)?.superTypes?.any { it.resolve().isAdapterKeyPath(visited) } == true
}

private fun KSType.toAdapterModel(
  factoryParameters: Set<String>,
  substitutions: Map<String, String> = emptyMap(),
): KPathsAdapterType? {
  val decl = declaration
  if (decl is KSTypeParameter) {
    if (isMarkedNullable) return null
    val name = decl.name.asString()
    return KPathsAdapterType.Parameter(if (name in factoryParameters) name else substitutions[name] ?: name)
  }
  val name = decl.qualifiedName?.asString() ?: return null
  val args = arguments.map {
    if (it.variance != Variance.INVARIANT) return null
    it.type?.resolve()?.toAdapterModel(factoryParameters, substitutions) ?: return null
  }
  return KPathsAdapterType.Classifier(name, args, isMarkedNullable)
}

private fun KPathsAdapterType.renderAdapterModel(): String = when (this) {
  is KPathsAdapterType.Parameter -> "`$name`"
  is KPathsAdapterType.Classifier -> name.split('.').joinToString(".") { "`$it`" } +
    arguments.takeIf { it.isNotEmpty() }?.joinToString(", ", "<", ">") { it.renderAdapterModel() }.orEmpty() +
    if (nullable) "?" else ""
}
