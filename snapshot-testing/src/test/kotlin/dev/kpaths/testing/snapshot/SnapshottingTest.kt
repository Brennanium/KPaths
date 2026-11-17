package dev.kpaths.testing.snapshot

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SnapshottingTest {
  @Test fun `pullback adapts a text strategy without losing its comparison`() {
    data class Result(val output: String)
    val strategy = textSnapshotting.pullback<Result> { it.output }
    assertNull(compareSnapshot(Result("ok"), "ok", strategy))
    val difference = assertNotNull(compareSnapshot(Result("actual"), "expected", strategy))
    assertEquals("expected", difference.expected)
    assertEquals("actual", difference.actual)
  }

  @Test fun `capture and diffing are supplied by the strategy`() {
    var captures = 0
    val strategy = Snapshotting<Int, Int>(Diffing { expected, actual ->
      if (expected == actual) null else "different numbers"
    }) { value -> captures++; value * 2 }
    assertEquals("different numbers", compareSnapshot(2, 3, strategy)?.message)
    assertEquals(1, captures)
  }
}
