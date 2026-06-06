package formal

import chisel3._
import chisel3.util.HasBlackBoxInline

// Replicates (*anyconst*) functionality in Verilog - allows us to set free variables for FV.
//
// The `(* anyconst *)` attribute is a yosys *native-frontend* extension, not real SystemVerilog, so
// it only survives when the helper is read with `read_verilog -sv -formal` (see formal.Formal's sby
// script) -- `read_slang` silently drops it and the value degrades to a time-varying `$anyseq`.
// `read_verilog`-imported modules are linked into the slang hierarchy as blackboxes via slang's
// always-on `--extern-modules`, but slang rejects parameters on such blackboxes. So the module must
// be *non-parametric*: the width is baked into the module name (`AnyConst_w<w>`) and body instead.
class AnyConst(w: Int) extends BlackBox with HasBlackBoxInline {
  override def desiredName = s"AnyConst_w$w"

  val io = IO(new Bundle() {
    val out = Output(UInt(w.W))
  })

  setInline(
    s"AnyConst_w$w.sv",
    s"""
       |module AnyConst_w$w (
       |    output [${w - 1}:0] out
       |);
       |
       |(* anyconst *) reg [${w - 1}:0] cst;
       |assign out = cst;
       |
       |endmodule
       |""".stripMargin
  )
}
