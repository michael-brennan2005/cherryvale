package pit

import chisel3._
import _root_.circt.stage.ChiselStage
import chisel3.util._

// CherryPit Core
// contained Fetch -> Decode -> Read Operands -> Execute (ALU/MEM) -> Writeback pipeline, with
// external connections for halt, clear, and code/data memory interface.
class Core(exposeSimPorts: Boolean = false) extends Module {
  val io = IO(new Bundle {
    // Global CPU signals
    val halt = Input(Bool())
    val clear = Input(Bool())

    val codeReq = EnqIO(new MemoryRequest)
    val codeResp = DeqIO(UInt(32.W))

    val dataReq = EnqIO(new MemoryRequest)
    val dataResp = DeqIO(UInt(32.W))

    // Expose a register read port for sim
    val regSimIdx = if (exposeSimPorts) Some(Input(UInt(5.W))) else None
    val regSimData = if (exposeSimPorts) Some(Output(UInt(32.W))) else None
  })

  // Pipeline stages
  val fetch = Module(new Fetch)
  val decode = Module(new Decode)
  val readOperands = Module(new ReadOperands(exposeSimPorts = exposeSimPorts))
  val execute = Module(new Execute)
  val writeback = Module(new Writeback)

  // Halt and clear signal connections
  fetch.io.halt := io.halt
  decode.io.halt := io.halt
  readOperands.io.halt := io.halt
  execute.io.halt := io.halt
  writeback.io.halt := io.halt

  fetch.io.clear := io.clear
  decode.io.clear := io.clear
  readOperands.io.clear := io.clear
  execute.io.clear := io.clear
  writeback.io.clear := io.clear

  // Code and data mem connections
  io.codeReq.bits := fetch.io.req.bits
  io.codeReq.valid := fetch.io.req.valid
  fetch.io.req.ready := io.codeReq.ready

  fetch.io.resp.bits := io.codeResp.bits
  fetch.io.resp.valid := io.codeResp.valid
  io.codeResp.ready := fetch.io.resp.ready

  io.dataReq.bits := execute.io.req.bits
  io.dataReq.valid := execute.io.req.valid
  execute.io.req.ready := io.dataReq.ready

  execute.io.resp.bits := io.dataResp.bits
  execute.io.resp.valid := io.dataResp.valid
  io.dataResp.ready := execute.io.resp.ready

  // Register sim port connection
  if (exposeSimPorts) {
    readOperands.io.regSimIdx.get := io.regSimIdx.get
    io.regSimData.get := readOperands.io.regSimData.get
  }

  // Pipeline connections!
  def pipelineConnect[T <: Data](enq: DecoupledIO[T], deq: DecoupledIO[T]): Unit = {
    deq.bits := enq.bits
    deq.valid := enq.valid
    enq.ready := deq.ready
  }

  pipelineConnect(fetch.io.out, decode.io.in)
  pipelineConnect(decode.io.out, readOperands.io.in)
  pipelineConnect(readOperands.io.out, execute.io.in)
  pipelineConnect(execute.io.out, writeback.io.in)

  // Forwarding + writeback connections
  fetch.io.redirectPc := execute.io.redirectPc
  fetch.io.newPc := execute.io.newPc

  decode.io.redirectPc := execute.io.redirectPc

  readOperands.io.redirectPc := execute.io.redirectPc
  readOperands.io.executeRegDestIdx := execute.io.regDestIdx
  readOperands.io.executeRegDestData := execute.io.regDestData
  readOperands.io.executeLoadHazardDest := execute.io.loadHazardDest

  readOperands.io.writebackRegDestIdx := writeback.io.regDestIdx
  readOperands.io.writebackRegDestData := writeback.io.regDestData
  readOperands.io.writebackRegDestEn := writeback.io.regDestEn
}
