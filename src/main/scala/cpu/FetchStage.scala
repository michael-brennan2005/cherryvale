package cpu

import chisel3._
import _root_.circt.stage.ChiselStage
import chisel3.util._
import chisel3.experimental.BundleLiterals._

class FetchStageOutput extends Bundle {
  val inst = Output(UInt(32.W))
  val pc = Output(UInt(32.W))
  val pcPlusFour = Output(UInt(32.W))
}

// Purely combinational: the PC register lives in the DataPath (it's the 0th
// pipeline register). This stage just addresses instruction memory with the
// current PC and forwards pc / pc+4 alongside the fetched instruction.
class FetchStage extends Module {
  val io = IO(new Bundle {
    val pc = Input(UInt(32.W))

    val out = new FetchStageOutput

    val code = Flipped(new ReadPort)
  })

  io.code.addr := io.pc

  io.out.inst := io.code.data
  io.out.pc := io.pc
  io.out.pcPlusFour := io.pc + 4.U
}
