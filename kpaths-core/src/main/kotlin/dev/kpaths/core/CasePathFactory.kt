package dev.kpaths.core

import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.KVariance
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.typeOf

/**
 * A root-scoped factory for conventional data-class case paths.
 *
 * Obtain one with [casePathsOf]. [pathFor] reflects the referenced constructor once to locate its
 * stored primary-constructor properties. Prefer generated catalogs for frequently used paths.
 */
public class CasePathFactory<Root>(
  private val rootType: TypeId,
  private val rootKType: KType,
) {
  /** Creates a path for a unary data-class case constructor. */
  public fun <Case : Root, Value> pathFor(
    constructor: (Value) -> Case,
  ): CaseKeyPath<Root, Value> {
    val shape = reflectedCaseShape(constructor, propertyCount = 1)
    @Suppress("UNCHECKED_CAST")
    val property = shape.properties.single() as KProperty1<Case, Value>
    return caseKeyPath(
      id = casePayloadId(rootType, shape),
      valueType = shape.typeIdFor(property.returnType, rootKType),
      match = { root ->
        if (shape.caseClass.isInstance(root)) {
          @Suppress("UNCHECKED_CAST")
          CaseMatch.Matched(property.get(root as Case))
        } else {
          CaseMatch.NoMatch
        }
      },
      embed = constructor,
    )
  }

  /** Creates a path for a binary data-class case constructor, focused as an ordered [Pair]. */
  public fun <Case : Root, First, Second> pathFor(
    constructor: (First, Second) -> Case,
  ): CaseKeyPath<Root, Pair<First, Second>> {
    val shape = reflectedCaseShape(constructor, propertyCount = 2)
    @Suppress("UNCHECKED_CAST")
    val first = shape.properties[0] as KProperty1<Case, First>
    @Suppress("UNCHECKED_CAST")
    val second = shape.properties[1] as KProperty1<Case, Second>
    return caseKeyPath(
      id = casePayloadId(rootType, shape),
      valueType = TypeId("kotlin.Pair", listOf(TypeArgument.invariant(shape.typeIdFor(first.returnType, rootKType)), TypeArgument.invariant(shape.typeIdFor(second.returnType, rootKType)))),
      match = { root ->
        if (shape.caseClass.isInstance(root)) {
          @Suppress("UNCHECKED_CAST")
          val case = root as Case
          CaseMatch.Matched(first.get(case) to second.get(case))
        } else {
          CaseMatch.NoMatch
        }
      },
      embed = { value -> constructor(value.first, value.second) },
    )
  }

  /** Creates a path for a ternary data-class case constructor, focused as an ordered [Triple]. */
  public fun <Case : Root, First, Second, Third> pathFor(
    constructor: (First, Second, Third) -> Case,
  ): CaseKeyPath<Root, Triple<First, Second, Third>> {
    val shape = reflectedCaseShape(constructor, propertyCount = 3)
    @Suppress("UNCHECKED_CAST") val first = shape.properties[0] as KProperty1<Case, First>
    @Suppress("UNCHECKED_CAST") val second = shape.properties[1] as KProperty1<Case, Second>
    @Suppress("UNCHECKED_CAST") val third = shape.properties[2] as KProperty1<Case, Third>
    return caseKeyPath(
      id = casePayloadId(rootType, shape),
      valueType = TypeId("kotlin.Triple", listOf(TypeArgument.invariant(shape.typeIdFor(first.returnType, rootKType)), TypeArgument.invariant(shape.typeIdFor(second.returnType, rootKType)), TypeArgument.invariant(shape.typeIdFor(third.returnType, rootKType)))),
      match = { root ->
        if (shape.caseClass.isInstance(root)) {
          @Suppress("UNCHECKED_CAST") val case = root as Case
          CaseMatch.Matched(Triple(first.get(case), second.get(case), third.get(case)))
        } else CaseMatch.NoMatch
      },
      embed = { value -> constructor(value.first, value.second, value.third) },
    )
  }
}

/** Starts a reflection-backed case-path factory while retaining [Root]'s complete [TypeId]. */
@OptIn(ExperimentalStdlibApi::class)
public inline fun <reified Root> casePathsOf(): CasePathFactory<Root> =
  CasePathFactory(TypeId.of<Root>(), typeOf<Root>())

private data class ReflectedCaseShape(
  val caseClass: KClass<*>,
  val properties: List<KProperty1<out Any, *>>,
)

private fun reflectedCaseShape(constructor: Any, propertyCount: Int): ReflectedCaseShape {
  val function = constructor as? KFunction<*>
    ?: error("pathFor requires a Kotlin constructor reference, such as ::Message, not an arbitrary lambda.")
  val caseClass = function.returnType.classifier as? KClass<*>
    ?: error("pathFor could not determine the constructor's case class.")
  require(caseClass.isData) { "pathFor supports data-class cases only. Use caseKeyPath(extract = ..., embed = ...) for ${caseClass.qualifiedName}." }
  val parameters = requireNotNull(caseClass.primaryConstructor).parameters
    .filter { it.kind == kotlin.reflect.KParameter.Kind.VALUE }
  require(parameters.size == propertyCount) {
    "pathFor expected a $propertyCount-value data-class case, but ${caseClass.qualifiedName} has ${parameters.size} primary-constructor values."
  }
  val propertiesByName = caseClass.memberProperties.associateBy { it.name }
  val properties = parameters.map { parameter ->
    requireNotNull(propertiesByName[parameter.name]) {
      "pathFor requires every primary-constructor value of ${caseClass.qualifiedName} to be a stored property. Use an explicit caseKeyPath factory instead."
    }
  }
  return ReflectedCaseShape(caseClass, properties)
}

private fun casePayloadId(rootType: TypeId, shape: ReflectedCaseShape): PathId {
  val caseName = requireNotNull(shape.caseClass.simpleName) { "Local and anonymous classes cannot form stable case paths." }
  return shape.properties.fold(PathId.case(rootType, caseName)) { id, property ->
    id.appending(PathSegment.Property(property.name))
  }
}

private fun ReflectedCaseShape.typeIdFor(type: KType, rootType: KType): TypeId {
  val rootClass = rootType.classifier as? KClass<*> ?: return TypeId.from(type)
  val substitutions = caseClass.supertypes
    .firstOrNull { it.classifier == rootClass }
    ?.arguments
    ?.zip(rootType.arguments)
    ?.mapNotNull { (caseArgument, rootArgument) ->
      val parameter = caseArgument.type?.classifier as? KTypeParameter
      val value = rootArgument.type
      if (parameter == null || value == null) null else parameter to TypeId.from(value)
    }
    ?.toMap()
    .orEmpty()
  return type.toTypeId(substitutions)
}

private fun KType.toTypeId(substitutions: Map<KTypeParameter, TypeId>): TypeId {
  val classifier = classifier
  if (classifier is KTypeParameter) return substitutions[classifier] ?: TypeId.from(this)
  val klass = classifier as? KClass<*> ?: return TypeId.from(this)
  return TypeId(
    classifier = klass.qualifiedName ?: error("Local and anonymous types cannot form stable TypeIds."),
    arguments = arguments.map { argument ->
      val type = argument.type
      when (argument.variance) {
        null -> TypeArgument.star
        KVariance.INVARIANT -> TypeArgument.invariant(requireNotNull(type).toTypeId(substitutions))
        KVariance.IN -> TypeArgument.`in`(requireNotNull(type).toTypeId(substitutions))
        KVariance.OUT -> TypeArgument.out(requireNotNull(type).toTypeId(substitutions))
      }
    },
    nullable = isMarkedNullable,
  )
}
