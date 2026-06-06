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
  // when ante holds, conds must hold on the same cycle. This just creates the boolean condition
  def implies(ante: Bool, conds: Bool): Bool = !ante || conds

  // Create an (* anyconst *) statement
  def anyconst[T <: Data](gen: T): T = {
    val cst = Module(new AnyConst(gen.getWidth))
    cst.io.out.asTypeOf(gen)
  }
}
