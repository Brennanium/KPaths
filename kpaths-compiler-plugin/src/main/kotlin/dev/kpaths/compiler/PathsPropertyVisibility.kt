package dev.kpaths.compiler

import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.declarations.FirProperty

/** KSP catalogs only expose properties and setters accessible outside the source class. */
internal fun FirProperty.isKPathsAccessible(): Boolean =
  status.visibility !in setOf(Visibilities.Private, Visibilities.Protected)

internal fun FirProperty.hasKPathsAccessibleSetter(): Boolean =
  isVar && setter?.status?.visibility !in setOf(Visibilities.Private, Visibilities.Protected)
