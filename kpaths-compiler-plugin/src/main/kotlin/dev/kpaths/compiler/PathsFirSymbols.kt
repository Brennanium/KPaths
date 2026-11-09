package dev.kpaths.compiler

import org.jetbrains.kotlin.name.FqName

/** Type identifiers used by generated FIR declarations. */
internal object PathsFirSymbols {
  val keyPathClassId = org.jetbrains.kotlin.name.ClassId.topLevel(
    FqName("dev.kpaths.core.KeyPath"),
  )
  val referenceWritableKeyPathClassId = org.jetbrains.kotlin.name.ClassId.topLevel(
    FqName("dev.kpaths.core.ReferenceWritableKeyPath"),
  )
  val writableKeyPathClassId = org.jetbrains.kotlin.name.ClassId.topLevel(
    FqName("dev.kpaths.core.WritableKeyPath"),
  )
  val caseKeyPathClassId = org.jetbrains.kotlin.name.ClassId.topLevel(
    FqName("dev.kpaths.core.CaseKeyPath"),
  )
  val caseKeyPathBaseClassId = org.jetbrains.kotlin.name.ClassId.topLevel(
    FqName("dev.kpaths.core.CaseKeyPathBase"),
  )
  val partialCaseKeyPathClassId = org.jetbrains.kotlin.name.ClassId.topLevel(
    FqName("dev.kpaths.core.PartialCaseKeyPath"),
  )
  val casePathCatalogBaseClassId = org.jetbrains.kotlin.name.ClassId.topLevel(
    FqName("dev.kpaths.core.CasePathCatalogBase"),
  )
  val listClassId = org.jetbrains.kotlin.name.ClassId.topLevel(FqName("kotlin.collections.List"))
  val caseMatchClassId = org.jetbrains.kotlin.name.ClassId.topLevel(
    FqName("dev.kpaths.core.CaseMatch"),
  )
  val pathIdClassId = org.jetbrains.kotlin.name.ClassId.topLevel(
    FqName("dev.kpaths.core.PathId"),
  )
  val typeIdClassId = org.jetbrains.kotlin.name.ClassId.topLevel(
    FqName("dev.kpaths.core.TypeId"),
  )
  val unitClassId = org.jetbrains.kotlin.name.ClassId.topLevel(FqName("kotlin.Unit"))
}
