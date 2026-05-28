package cpu

import chisel3._
import _root_.circt.stage.ChiselStage
import chisel3.util._

object AluOp extends ChiselEnum {
  val add = Value(0.U)
  val sub = Value(1.U)
  val xor = Value(2.U)
  val or = Value(3.U)
  val and = Value(4.U)
  val slt = Value(5.U)

  val dontCare = add
}

class Alu extends Module {
  val io = IO(new Bundle {
    val i_src_a = Input(UInt(32.W))
    val i_src_b = Input(UInt(32.W))
    val i_control = Input(AluOp())

    val o_result = Output(UInt(32.W))
    val o_zero = Output(Bool())
  })

  val result = Wire(UInt(32.W))

  result := MuxLookup(io.i_control, 0.U)(
    Seq(
      AluOp.add -> (io.i_src_a + io.i_src_b),
      AluOp.sub -> (io.i_src_a - io.i_src_b),
      AluOp.xor -> (io.i_src_a ^ io.i_src_b),
      AluOp.or -> (io.i_src_a | io.i_src_b),
      AluOp.and -> (io.i_src_a & io.i_src_b),
      AluOp.slt -> (io.i_src_a < io.i_src_b)
    )
  )

  io.o_result := result
  io.o_zero := result === 0.U
}
