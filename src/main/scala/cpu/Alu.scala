package cpu

import chisel3._
import _root_.circt.stage.ChiselStage
import chisel3.util._

class Alu extends Module {
  val io = IO(new Bundle {
    val i_src_a = Input(UInt(32.W))
    val i_src_b = Input(UInt(32.W))
    val i_control = Input(UInt(3.W))

    val o_result = Output(UInt(32.W))
    val o_zero = Output(Bool())
  })

  val result = Wire(UInt(32.W))

  result := MuxLookup(io.i_control, 0.U)(
    Seq(
      "b000".U -> (io.i_src_a + io.i_src_b),
      "b001".U -> (io.i_src_a - io.i_src_b),
      "b010".U -> (io.i_src_a ^ io.i_src_b),
      "b011".U -> (io.i_src_a | io.i_src_b),
      "b100".U -> (io.i_src_a & io.i_src_b),
      "b101".U -> (io.i_src_a < io.i_src_b)
    )
  )

  io.o_result := result
  io.o_zero := result === 0.U
}
