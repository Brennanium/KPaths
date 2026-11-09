package dev.kpaths.compiler

import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** Compiler symbols referenced by the KPaths IR emitters. */
internal object PathsIrSymbols {
  val keyPathCallableId = CallableId(FqName("dev.kpaths.core"), Name.identifier("keyPath"))
  val caseKeyPathCallableId = CallableId(
    FqName("dev.kpaths.core"),
    Name.identifier("caseKeyPath"),
  )
  val anyPathClassId = ClassId.topLevel(FqName("dev.kpaths.core.AnyPath"))
  val keyPathClassId = ClassId.topLevel(FqName("dev.kpaths.core.KeyPath"))
  val keyPathBaseClassId = ClassId.topLevel(FqName("dev.kpaths.core.KeyPathBase"))
  val caseKeyPathClassId = ClassId.topLevel(FqName("dev.kpaths.core.CaseKeyPath"))
  val caseKeyPathBaseClassId = ClassId.topLevel(FqName("dev.kpaths.core.CaseKeyPathBase"))
  val partialCaseKeyPathClassId = ClassId.topLevel(FqName("dev.kpaths.core.PartialCaseKeyPath"))
  val casePathCatalogClassId = ClassId.topLevel(FqName("dev.kpaths.core.CasePathCatalog"))
  val casePathCatalogBaseClassId = ClassId.topLevel(
    FqName("dev.kpaths.core.CasePathCatalogBase"),
  )
  val caseMatchClassId = ClassId.topLevel(FqName("dev.kpaths.core.CaseMatch"))
  val referenceWritableKeyPathClassId = ClassId.topLevel(
    FqName("dev.kpaths.core.ReferenceWritableKeyPath"),
  )
  val referenceWritableKeyPathBaseClassId = ClassId.topLevel(
    FqName("dev.kpaths.core.ReferenceWritableKeyPathBase"),
  )
  val writableKeyPathClassId = ClassId.topLevel(FqName("dev.kpaths.core.WritableKeyPath"))
  val writableKeyPathBaseClassId = ClassId.topLevel(
    FqName("dev.kpaths.core.WritableKeyPathBase"),
  )
  const val referenceWritableKeyPathQualifiedName =
    "dev.kpaths.core.ReferenceWritableKeyPath"
  const val writableKeyPathQualifiedName = "dev.kpaths.core.WritableKeyPath"
  const val caseKeyPathQualifiedName = "dev.kpaths.core.CaseKeyPath"
  val pathIdClassId = ClassId.topLevel(FqName("dev.kpaths.core.PathId"))
  val pathSegmentClassId = ClassId.topLevel(FqName("dev.kpaths.core.PathSegment"))
  val pathSegmentPropertyClassId = ClassId.topLevel(FqName("dev.kpaths.core.PathSegment"))
    .createNestedClassId(Name.identifier("Property"))
  val typeIdClassId = ClassId.topLevel(FqName("dev.kpaths.core.TypeId"))
  val typeArgumentClassId = ClassId.topLevel(FqName("dev.kpaths.core.TypeArgument"))
  val listOfCallableId = CallableId(FqName("kotlin.collections"), Name.identifier("listOf"))
}
