package dev.kpaths.compiler

import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.symbols.SymbolInternals

@OptIn(DirectDeclarationsAccess::class, SymbolInternals::class)
internal fun FirRegularClass.hasKPathsCatalogCollision(catalogName: String): Boolean =
  declarations.filterIsInstance<FirClassLikeDeclaration>().any {
    it.origin == FirDeclarationOrigin.Source && it.symbol.classId.shortClassName.asString() == catalogName
  }
