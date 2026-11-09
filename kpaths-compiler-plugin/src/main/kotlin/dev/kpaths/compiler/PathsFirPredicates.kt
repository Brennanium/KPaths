package dev.kpaths.compiler

import org.jetbrains.kotlin.fir.extensions.predicate.DeclarationPredicate
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate
import org.jetbrains.kotlin.name.FqName

/** Predicates must be shared by registration and session-scoped discovery. */
internal object PathsFirPredicates {
  val pathablePredicate = LookupPredicate.create { annotated(FqName("dev.kpaths.annotations.Pathable")) }
  val metaPathablePredicate = DeclarationPredicate.create {
    metaAnnotated(listOf(FqName("dev.kpaths.annotations.KPathsPathable")), false)
  }
}
