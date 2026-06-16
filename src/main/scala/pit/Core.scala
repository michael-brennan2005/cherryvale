package pit

import chisel3._
import _root_.circt.stage.ChiselStage
import chisel3.util._
import chisel3.experimental.BundleLiterals._

// "Core" compute path for CherryPit - contains datapath (fetch -> decode -> execute -> mem),
// control unit, PC, and reg file.
// TODO: Fetch stage has now been rewritten to use BadCache, memoryUnit has not.
class Core(sim: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val halt = Input(Bool())

    val codeReq = EnqIO(new MemoryRequest)
    val codeResp = Flipped(Valid(UInt(32.W)))

    val dataReq = EnqIO(new MemoryRequest)
    val dataResp = Flipped(Valid(UInt(32.W)))

    val regDebugIdx = if (sim) Some(Input(UInt(5.W))) else None
    val regDebugData = if (sim) Some(Output(UInt(32.W))) else None
  })

  val hazard = Module(new HazardUnit)
  val fetch = Module(new FetchStage)
  val decode = Module(new DecodeStage)
  val execute = Module(new ExecuteStage)
  val memory = Module(new MemoryStage)

  val pc = RegInit(0.U(32.W)) // writeback -> fetch
  val ifId = fetch.io.out // fetch -> decode
  val idEx = RegInit(0.U.asTypeOf(new DecodeStageOutput)) // decode -> execute
  val exMem = RegInit(0.U.asTypeOf(new ExecuteStageOutput)) // execute -> memory
  val memWb = memory.io.out // memory -> writeback

  val pcOverride = Wire(Bool())
  val resultWriteback = Wire(UInt(32.W))

  // Fetch stage
  when(!hazard.io.stallPc && pcOverride) {
    pc := execute.io.pcTarget
  }.elsewhen(!hazard.io.stallPc) {
    pc := pc + 4.U
  }

  fetch.io.pc := pc
  fetch.io.flush := hazard.io.flushFetchOutput
  fetch.io.stall := hazard.io.stallFetchOutput
  io.codeReq.bits := fetch.io.codeReq.bits
  io.codeReq.valid := fetch.io.codeReq.valid
  fetch.io.codeReq.ready := io.codeReq.ready
  fetch.io.codeResp := io.codeResp

  // Decode stage
  decode.io.fetchInput := ifId

  decode.io.regWriteIdx := memWb.regDestIdx
  decode.io.regWriteEnable := memWb.control.writeToReg
  decode.io.regWriteData := resultWriteback

  if (sim) {
    decode.io.regDebugIdx := io.regDebugIdx.get
    io.regDebugData.get := decode.io.regDebugData
  } else {
    decode.io.regDebugIdx := 0.U
  }

  when(hazard.io.flushDecodeOutput) {
    idEx := 0.U.asTypeOf(new DecodeStageOutput)
  }.otherwise {
    idEx := decode.io.out
  }

  // Execute stage
  execute.io.decodeInput := idEx

  pcOverride := idEx.control.jump | (idEx.control.branch & execute.io.takeBranch)

  execute.io.aluSrcASelect := hazard.io.executeAInputSel
  execute.io.aluSrcBSelect := hazard.io.executeBInputSel

  execute.io.resultWriteback := resultWriteback
  execute.io.resultMemory := exMem.aluResult
  exMem := execute.io.out

  // Memory stage
  memory.io.executeInput := exMem
  io.dataReq.bits := memory.io.dataReq.bits
  io.dataReq.valid := memory.io.dataReq.valid
  memory.io.dataReq.ready := io.dataReq.ready
  memory.io.dataResp := io.dataResp

  // Writeback stage
  resultWriteback := MuxLookup(
    memWb.control.regFileWriteSrc,
    0.U(32.W)
  )(
    Seq(
      RegFileWriteSrc.data -> memWb.memReadData,
      RegFileWriteSrc.aluResult -> memWb.aluResult,
      RegFileWriteSrc.pcPlusFour -> memWb.pcPlusFour,
      RegFileWriteSrc.immediate -> memWb.immediate
    )
  )

  // Hazard inputs
  hazard.io.halt := io.halt

  hazard.io.pcOverride := pcOverride
  hazard.io.decodeReg1Idx := decode.io.out.reg1Idx
  hazard.io.decodeReg2Idx := decode.io.out.reg2Idx

  hazard.io.executeReg1Idx := idEx.reg1Idx
  hazard.io.executeReg2Idx := idEx.reg2Idx
  hazard.io.executeRegDestIdx := idEx.regDestIdx
  hazard.io.executeRegFileWriteSrc := idEx.control.regFileWriteSrc

  hazard.io.memoryRegDestIdx := exMem.regDestIdx
  hazard.io.memoryWriteToReg := exMem.control.writeToReg

  hazard.io.writebackRegDestIdx := memWb.regDestIdx
  hazard.io.writebackWriteToReg := memWb.control.writeToReg
}
