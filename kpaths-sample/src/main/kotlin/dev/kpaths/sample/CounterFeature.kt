package dev.kpaths.sample

import dev.kpaths.annotations.Pathable

@Pathable
data class CounterState(
  val count: Int = 0,
)

@Pathable
sealed interface CounterAction {
  data object IncrementTapped : CounterAction
  data class SetCount(val count: Int) : CounterAction
}
