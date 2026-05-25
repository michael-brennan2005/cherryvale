package cpu

import chisel3._
import _root_.circt.stage.ChiselStage
import chisel3.util.Cat
import chisel3.util.MuxLookup

class Immediate extends Module {
  val io = IO(new Bundle {
    val imm_src = Input(UInt(2.W))
    val inst = Input(UInt(32.W))

    val imm = Output(UInt(32.W))
  })

  io.imm := MuxLookup(io.imm_src, 0.U)(
    Seq(
      "b00".U -> io.inst(31, 20).asSInt.pad(32).asUInt,
      "b11".U -> Cat(
        io.inst(31, 25),
        io.inst(11, 7)
      ).asSInt.pad(32).asUInt,
      "b10".U -> Cat(
        io.inst(31),
        io.inst(7),
        io.inst(30, 25),
        io.inst(11, 8),
        0.U
      ).asSInt.pad(32).asUInt
    )
  )
}
