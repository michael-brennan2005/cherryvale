package pit

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

    val codeReq = EnqIO(new MemoryRequest)
    val codeResp = Flipped(Valid(UInt(32.W)))

    val flush = Input(Bool())
    val stall = Input(Bool())

    val out = new FetchStageOutput
  })

  io.codeReq.valid := !io.stall
  io.codeReq.bits.addr := io.pc
  io.codeReq.bits.we := false.B
  io.codeReq.bits.writeData := 0.U
  io.codeReq.bits.writeMask := 0.U

  val pcReg = RegEnable(io.pc, !io.stall)
  val pcPlus4Reg = RegEnable(io.pc + 4.U, !io.stall)

  val squash = RegInit(true.B)
  when(io.flush) {
    squash := true.B
  }.elsewhen(!io.stall) {
    squash := false.B
  }

  // cache's are synch-read and have at min 1-cycle latency - in effect the pipeline register for
  // the instruction is in the cache itself. So instead of putting instruction in a register we out-
  // put directly
  // TODO: proper no-op instead of 0.U
  io.out.inst := Mux(squash || !io.codeResp.valid, RegNext(io.codeResp.bits), io.codeResp.bits)
  io.out.pc := pcReg
  io.out.pcPlusFour := pcPlus4Reg
}
