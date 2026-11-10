package dev.kpaths.compiler

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.unwrapSubstitutionOverrides
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirExpressionChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.renderer.FirRenderer
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import java.nio.file.Files
import java.nio.file.Path

/** Test-only capture of resolved, source-referenced generated callables during FIR checking. */
internal class PathsFirSnapshot(private val destination: String) {
  private val entries = sortedSetOf<String>()

  init { write() }

  @Synchronized
  fun record(id: String, rendered: String) {
    entries += "$id\n${rendered.trimEnd()}"
    write()
  }

  private fun write() {
    val path = Path.of(destination)
    path.parent?.let(Files::createDirectories)
    val output = buildString {
      appendLine("KPaths resolved FIR callables")
      entries.forEach { entry ->
        appendLine()
        appendLine(entry)
      }
    }
    Files.writeString(path, output)
  }
}

@OptIn(SymbolInternals::class)
internal class PathsFirSnapshotCheckers(session: FirSession, private val snapshot: PathsFirSnapshot) :
  FirAdditionalCheckersExtension(session) {
  override val expressionCheckers: ExpressionCheckers = object : ExpressionCheckers() {
    override val propertyAccessExpressionCheckers: Set<FirExpressionChecker<FirPropertyAccessExpression>> =
      setOf(PropertyChecker())
    override val functionCallCheckers: Set<FirExpressionChecker<FirFunctionCall>> = setOf(FunctionChecker())
  }

  private inner class PropertyChecker : FirExpressionChecker<FirPropertyAccessExpression>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirPropertyAccessExpression) {
      val symbol = (expression.calleeReference as? FirResolvedNamedReference)?.resolvedSymbol as? FirPropertySymbol ?: return
      record(symbol)
    }
  }

  private inner class FunctionChecker : FirExpressionChecker<FirFunctionCall>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
      val symbol = (expression.calleeReference as? FirResolvedNamedReference)?.resolvedSymbol as? FirNamedFunctionSymbol ?: return
      record(symbol)
    }
  }

  private fun record(symbol: FirCallableSymbol<*>) {
    val origin = symbol.unwrapSubstitutionOverrides().fir.origin as? FirDeclarationOrigin.Plugin ?: return
    if (origin.key !in setOf(PathsDeclarationKey, PathsCaseDeclarationKey, PathsCasePropertyCatalogKey,
        PathsGenericCatalogKey, PathsGenericCaseDeclarationKey, PathsGenericCasePropertyCatalogKey)) return
    snapshot.record(symbol.callableId.toString(), FirRenderer().renderElementAsString(symbol.fir))
  }
}
