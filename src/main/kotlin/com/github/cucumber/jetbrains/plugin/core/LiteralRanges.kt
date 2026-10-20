package com.github.cucumber.jetbrains.plugin.core

/** Decisions about the text range used for Ctrl+hover / Ctrl+click on a step definition literal. */
object LiteralRanges {

  /**
   * True if the PSI leaf `[leafStart, leafEnd)` is coarser than the pattern literal
   * `[literalStart, literalEnd)` it contains (a plain-text or TextMate file is one leaf), so the
   * literal must get its own reference range instead of the leaf's.
   */
  @JvmStatic
  fun leafIsCoarser(leafStart: Int, leafEnd: Int, literalStart: Int, literalEnd: Int): Boolean =
      literalStart < literalEnd &&
          leafStart <= literalStart &&
          literalEnd <= leafEnd &&
          (leafStart < literalStart || literalEnd < leafEnd)
}
