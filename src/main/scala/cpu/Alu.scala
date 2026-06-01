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
  val sll = Value(5.U)
  val srl = Value(6.U)
  val sra = Value(7.U)
  val slt = Value(8.U)
  val sltu = Value(9.U)

  val dontCare = add
}

class Alu extends Module {
  val io = IO(new Bundle {
    val i_src_a = Input(UInt(32.W))
    val i_src_b = Input(UInt(32.W))
    val i_control = Input(AluOp())

    val o_result = Output(UInt(32.W))
    val zero = Output(Bool()) // ALU result is zero
    val neg = Output(Bool()) // ALU result is negative (bit 31 == 1)
  })

  val result = Wire(UInt(32.W))

  result := MuxLookup(io.i_control, 0.U)(
    Seq(
      AluOp.add -> (io.i_src_a + io.i_src_b),
      AluOp.sub -> (io.i_src_a - io.i_src_b),
      AluOp.xor -> (io.i_src_a ^ io.i_src_b),
      AluOp.or -> (io.i_src_a | io.i_src_b),
      AluOp.and -> (io.i_src_a & io.i_src_b),
      AluOp.sll -> (io.i_src_a << io.i_src_b(4, 0)),
      AluOp.srl -> (io.i_src_a >> io.i_src_b(4, 0)),
      AluOp.sra -> (io.i_src_a.asSInt >> io.i_src_b(4, 0)).asUInt,
      AluOp.slt -> (io.i_src_a.asSInt < io.i_src_b.asSInt),
      AluOp.sltu -> (io.i_src_a < io.i_src_b)
    )
  )

  io.o_result := result
  io.zero := result === 0.U
  io.neg := result(31) === 1.U
}
