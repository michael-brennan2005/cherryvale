package formal

import chisel3._
import chisel3.ltl.AssertProperty

/** Reusable temporal/formal helper patterns.
  *
  * The `|=>`/`##n` *timing* is lowered to `RegNext` so no SVA sequence operators survive -- the
  * open-source Yosys / SymbiYosys flow only gets full sequence SVA through the commercial Verific
  * frontend. The resulting plain-boolean property is then emitted via
  * [[chisel3.ltl.AssertProperty]] (a *formal* assertion the OSS tools check), NOT `chisel3.assert`
  * (which lowers to a simulation `$error`/`$fatal` guarded by `ifndef SYNTHESIS` and is invisible
  * to the solver). Assumes use `chisel3.assume`, which already lowers to a formal `assume(...)`.
  *
  * Call these inside a Module body: the edge-detect/delay helpers build registers and the
  * implication helpers emit assertions, so both rely on the module's implicit clock and reset.
  * Assertions are automatically disabled during reset.
  */

// TODO: this is broken/ot totally understanding what's going on here. AFAICT, property operations
// like |-> and |=> dont work with the OSS yosys flow I have going, but doing boolean conditions
// works fine. So this should really just be past-value and edge building blocks (so they synth
// to registers instead of unsupported operators), but actual definition of properties should be
// done with LTL constructs. Implicaion assertions should probably just return the boolean values
// that we then stick in the LTL constructs.
//
// TODO: formal workflow is fucked and this does not work at all right now

object Utils {

  // --- Past-value / edge building blocks (return a value, no side effects) -------------------

  /** SVA `$past(x, n)`: the value `x` held `n` cycles ago (default 1).
    *
    * `init` seeds the pre-history -- the value reported on the first `n` cycles after reset, before
    * `x` has actually been observed that many times. Leave it `None` to use a plain reset-less
    * register (value is whatever the register powers up to).
    */
  def past[T <: Data](x: T, n: Int = 1, init: Option[T] = None): T = {
    require(n >= 1, s"past depth must be >= 1, got $n")
    (0 until n).foldLeft(x) { (sig, _) =>
      init match {
        case Some(i) => RegNext(sig, i)
        case None    => RegNext(sig)
      }
    }
  }

  /** SVA `$stable(x)`: true when `x` equals its value last cycle. Works for any `Data`. */
  def stable[T <: Data](x: T): Bool = x.asUInt === past(x).asUInt

  /** Negation of [[stable]]: true when `x` changed since last cycle. */
  def changed[T <: Data](x: T): Bool = !stable(x)

  /** SVA `$rose(x)`: low last cycle, high now. Seeded `false` so it can't fire out of reset. */
  def rose(x: Bool): Bool = !past(x, 1, Some(false.B)) && x

  /** SVA `$fell(x)`: high last cycle, low now. Seeded `true` so it can't fire out of reset. */
  def fell(x: Bool): Bool = past(x, 1, Some(true.B)) && !x

  /** False on the first cycle after reset, true thereafter.
    *
    * Use to guard properties that reference [[past]] before enough history exists, e.g.
    * `when(started) { assert(stable(x)) }`.
    */
  def started: Bool = RegNext(true.B, false.B)

  // --- Implication assertions ----------------------------------------------------------------

  // Implication `ante -> cons` as a plain boolean (`!ante || cons`), asserted as a formal property.
  def doAssert(cond: Bool, msg: String): Unit =
    if (msg.isEmpty) AssertProperty(cond) else AssertProperty(cond, label = msg)

  private def doAssume(cond: Bool, msg: String): Unit =
    if (msg.isEmpty) assume(cond) else assume(cond, msg)

  /** SVA `ante |-> cons` (overlapping): whenever `ante` holds, `cons` must hold the SAME cycle. */
  def overlapImpl(ante: Bool, cons: Bool, msg: String = ""): Unit =
    doAssert(!ante || cons, msg)

  /** SVA `ante |=> cons` (non-overlapping): whenever `ante` holds, `cons` must hold the NEXT cycle.
    * The antecedent is registered (seeded `false`) so nothing fires coming out of reset.
    */
  def nextImpl(ante: Bool, cons: Bool, msg: String = ""): Unit =
    delayedImpl(ante, cons, 1, msg)

  /** SVA `ante |-> ##n cons`: whenever `ante` holds, `cons` must hold `n` cycles later. `n == 0` is
    * the overlapping case ([[overlapImpl]]); `n == 1` is `|=>` ([[nextImpl]]).
    */
  def delayedImpl(ante: Bool, cons: Bool, n: Int, msg: String = ""): Unit = {
    require(n >= 0, s"delay must be >= 0, got $n")
    if (n == 0) overlapImpl(ante, cons, msg)
    else doAssert(!past(ante, n, Some(false.B)) || cons, msg)
  }

  // --- Assume-flavored variants (for constraining inputs in a formal harness) ----------------

  /** Assume form of [[overlapImpl]]: constrain that `ante` implies `cons` on the same cycle. */
  def overlapAssume(ante: Bool, cons: Bool, msg: String = ""): Unit =
    when(ante) { doAssume(cons, msg) }

  /** Assume form of [[nextImpl]]: constrain that `ante` implies `cons` on the next cycle. */
  def nextAssume(ante: Bool, cons: Bool, msg: String = ""): Unit =
    when(past(ante, 1, Some(false.B))) { doAssume(cons, msg) }
}
