package dev.kpaths.compiler

import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeAttributes
import org.jetbrains.kotlin.fir.types.ConeLookupTagBasedType
import org.jetbrains.kotlin.fir.types.constructType

/** Construct through FIR's factory instead of depending on its version-specific implementation class. */
internal fun FirTypeParameterSymbol.kPathsTypeParameterType(): ConeLookupTagBasedType =
  constructType(emptyArray(), false, ConeAttributes.Empty)
