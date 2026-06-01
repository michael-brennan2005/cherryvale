package cpu

import chisel3._
import _root_.circt.stage.ChiselStage
import chisel3.util._
import chisel3.experimental.BundleLiterals._

class DataPath extends Module {
  val io = IO(new Bundle {
    val code = Flipped(new ReadPort)
    val data = Flipped(new ReadWritePort)

    // for debug
    val reg_file_ra = Input(UInt(5.W))
    val reg_file_rd = Output(UInt(32.W))
  })

  val hazard = Module(new HazardUnit)
  val fetch = Module(new FetchStage)
  val decode = Module(new DecodeStage)
  val execute = Module(new ExecuteStage)
  val memory = Module(new MemoryStage)

  val pc = RegInit(0.U(32.W)) // writeback -> fetch (sort of)
  val ifId = RegInit(0.U.asTypeOf(new FetchStageOutput)) // fetch -> decode
  val idEx = RegInit(0.U.asTypeOf(new DecodeStageOutput)) // decode -> execute
  val exMem = RegInit(0.U.asTypeOf(new ExecuteStageOutput)) // execute -> memory
  val memWb = RegInit(
    0.U.asTypeOf(new MemoryStageOutput)
  ) // memory -> writeback

  val pcOverride = Wire(Bool())
  val resultWriteback = Wire(UInt(32.W))

  // Fetch stage
  when(!hazard.io.stallPc && pcOverride) {
    pc := execute.io.pcTarget
  }.elsewhen(!hazard.io.stallPc) {
    pc := pc + 4.U
  }

  fetch.io.pc := pc
  fetch.io.code <> io.code

  when(hazard.io.flushFetchOutput) {
    ifId := 0.U.asTypeOf(new FetchStageOutput)
  }.elsewhen(!hazard.io.stallFetchOutput) {
    ifId := fetch.io.out
  }

  // Decode stage
  decode.io.fetchInput := ifId

  decode.io.regWriteIdx := memWb.regDestIdx
  decode.io.regWriteEnable := memWb.control.writeToReg
  decode.io.regWriteData := resultWriteback

  decode.io.regDebugIdx := io.reg_file_ra
  io.reg_file_rd := decode.io.regDebugData

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
  memory.io.data <> io.data

  memWb := memory.io.out

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
