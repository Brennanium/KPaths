package dev.kpaths.core

/** Binds an indexed getter's [index] argument while leaving its root argument open. */
public fun <Root, Value> ((Root, Int) -> Value).at(index: Int): (Root) -> Value =
  { root -> this(root, index) }

/** Binds an indexed setter's [index] argument while leaving its root and value arguments open. */
public fun <Root, Value> ((Root, Int, Value) -> Unit).at(index: Int): (Root, Value) -> Unit =
  { root, value -> this(root, index, value) }
