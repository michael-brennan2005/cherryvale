package pit

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
    val srcA = Input(UInt(32.W))
    val srcB = Input(UInt(32.W))
    val control = Input(AluOp())

    val result = Output(UInt(32.W))
    val zero = Output(Bool()) // ALU result is zero
    val neg = Output(Bool()) // ALU result is negative (bit 31 == 1)
  })

  val result = Wire(UInt(32.W))

  result := MuxLookup(io.control, 0.U)(
    Seq(
      AluOp.add -> (io.srcA + io.srcB),
      AluOp.sub -> (io.srcA - io.srcB),
      AluOp.xor -> (io.srcA ^ io.srcB),
      AluOp.or -> (io.srcA | io.srcB),
      AluOp.and -> (io.srcA & io.srcB),
      AluOp.sll -> (io.srcA << io.srcB(4, 0)),
      AluOp.srl -> (io.srcA >> io.srcB(4, 0)),
      AluOp.sra -> (io.srcA.asSInt >> io.srcB(4, 0)).asUInt,
      AluOp.slt -> (io.srcA.asSInt < io.srcB.asSInt),
      AluOp.sltu -> (io.srcA < io.srcB)
    )
  )

  io.result := result
  io.zero := result === 0.U
  io.neg := result(31) === 1.U
}
