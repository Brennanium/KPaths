package dev.kpaths.codegen.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KPathsCompanionPolicyTest {
  @Test
  fun `bare companions followed by members are ambiguous even through comments`() {
    assertTrue(hasAmbiguousKPathsCompanion("companion object\n// comment\ndata object Idle", 3))
    assertTrue(hasAmbiguousKPathsCompanion("companion object\n/* comment */\n@Annotation val x = 0", 1))
  }

  @Test
  fun `named braced and final companions are accepted`() {
    assertFalse(hasAmbiguousKPathsCompanion("companion object Factory\ndata object Idle", 1))
    assertFalse(hasAmbiguousKPathsCompanion("companion object {}\ndata object Idle", 1))
    assertFalse(hasAmbiguousKPathsCompanion("companion object\n}", 1))
  }
}
