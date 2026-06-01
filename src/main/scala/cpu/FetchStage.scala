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
