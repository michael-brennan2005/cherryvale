package harness

import chisel3._
import chisel3.ltl.AssertProperty
import harness.AnyConst

object Utils {
  // when ante holds, conds must hold on the same cycle. This just creates the boolean condition
  def implies(ante: Bool, conds: Bool): Bool = !ante || conds

  // Create an (* anyconst *) statement
  def anyconst[T <: Data](gen: T): T = {
    val cst = Module(new AnyConst(gen.getWidth))
    cst.io.out.asTypeOf(gen)
  }
}
