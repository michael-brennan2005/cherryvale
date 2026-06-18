package pit

import chisel3._
import _root_.circt.stage.ChiselStage
import chisel3.util._
import chisel3.experimental.BundleLiterals._

// "Core" compute path for CherryPit - contains datapath (fetch -> decode -> execute -> mem),
// control unit, PC, and reg file.
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

  io.dataReq.bits := 0.U.asTypeOf(new MemoryRequest)
  io.dataReq.valid := false.B

  if (sim) {
    io.regDebugData.get := 0.U
  }

  // Flow is PC -> I$ -> F/D -> D/E -> E/M -> D$; hazard handles stalls and flushes and (TODO: forwards)
  val hazard = Module(new HazardUnit)
  val pc = Module(new PSR(UInt(32.W)))

  // Stuff for hazard
  hazard.io.halt := io.halt
  hazard.io.iCacheReqReady := io.codeReq.ready
  hazard.io.iCacheRespValid := io.codeResp.valid

  // Stuff for PC
  pc.io.in := pc.io.out + 4.U
  pc.io.stall := hazard.io.stallPcOut
  pc.io.flush := hazard.io.flushPcOut

  // Stuff for I$
  val iCacheAddr = RegNext(pc.io.out)
  io.codeReq.bits.addr := pc.io.out
  io.codeReq.bits.we := false.B
  io.codeReq.bits.writeData := 0.U
  io.codeReq.bits.writeMask := 0.U
  io.codeReq.valid := !hazard.io.stallICache

  // Stuff for Fetch (not really a "real" stage - just registering the iCache outputs)
  val fetchOut = Module(new PSR(new FetchStageOutput))
  fetchOut.io.in.inst := io.codeResp.bits
  fetchOut.io.in.pc := iCacheAddr
  fetchOut.io.in.pcPlusFour := iCacheAddr + 4.U
  fetchOut.io.stall := hazard.io.stallFetchOut
  fetchOut.io.flush := hazard.io.flushFetchOut

  // Stuff for decode
  val wire = dontTouch(fetchOut.io.out.inst)
  val wire1 = dontTouch(fetchOut.io.out.pc)
  val wire2 = dontTouch(fetchOut.io.out.pcPlusFour)

  // val hazard = Module(new HazardUnit)
  // val fetch = Module(new FetchStage)
  // val decode = Module(new DecodeStage)
  // val execute = Module(new ExecuteStage)
  // val memory = Module(new MemoryStage)

  // val pc = Module(new PSR(UInt(32.W))) // writeback -> fetch
  // val ifId = fetch.io.out // fetch -> decode
  // val idEx = Module(new PSR(new DecodeStageOutput)) // decode -> execute
  // val exMem = Module(new PSR(new ExecuteStageOutput)) // execute -> memory
  // val memWb = memory.io.out // memory -> writeback

  // val pcOverride = Wire(Bool())
  // val resultWriteback = Wire(UInt(32.W))

  // // Fetch stage
  // when(!hazard.io.stallPc && pcOverride) {
  //   pc.io.in := execute.io.pcTarget
  // }.elsewhen(!hazard.io.stallPc) {
  //   pc.io.in := pc.io.out + 4.U
  // }.otherwise {
  //   pc.io.in := pc.io.out
  // }

  // fetch.io.pc := pc.io.out
  // io.codeReq.bits := fetch.io.codeReq.bits
  // io.codeReq.valid := fetch.io.codeReq.valid
  // fetch.io.codeReq.ready := io.codeReq.ready
  // fetch.io.codeResp := io.codeResp

  // // Decode stage
  // decode.io.fetchInput := ifId

  // decode.io.regWriteIdx := memWb.regDestIdx
  // decode.io.regWriteEnable := memWb.control.writeToReg
  // decode.io.regWriteData := resultWriteback

  // if (sim) {
  //   decode.io.regDebugIdx := io.regDebugIdx.get
  //   io.regDebugData.get := decode.io.regDebugData
  // } else {
  //   decode.io.regDebugIdx := 0.U
  // }

  // idEx.io.in := decode.io.out

  // // Execute stage
  // execute.io.decodeInput := idEx.io.out

  // pcOverride := idEx.io.out.control.jump | (idEx.io.out.control.branch & execute.io.takeBranch)

  // execute.io.aluSrcASelect := hazard.io.executeAInputSel
  // execute.io.aluSrcBSelect := hazard.io.executeBInputSel

  // execute.io.resultWriteback := resultWriteback
  // execute.io.resultMemory := exMem.io.out.aluResult
  // exMem.io.in := execute.io.out

  // // Memory stage
  // memory.io.executeInput := exMem.io.out
  // io.dataReq.bits := memory.io.dataReq.bits
  // io.dataReq.valid := memory.io.dataReq.valid
  // memory.io.dataReq.ready := io.dataReq.ready
  // memory.io.dataResp := io.dataResp

  // // Writeback stage
  // resultWriteback := MuxLookup(
  //   memWb.control.regFileWriteSrc,
  //   0.U(32.W)
  // )(
  //   Seq(
  //     RegFileWriteSrc.data -> memWb.memReadData,
  //     RegFileWriteSrc.aluResult -> memWb.aluResult,
  //     RegFileWriteSrc.pcPlusFour -> memWb.pcPlusFour,
  //     RegFileWriteSrc.immediate -> memWb.immediate
  //   )
  // )

  // // Hazard inputs
  // hazard.io.halt := io.halt

  // pc.io.stall := hazard.io.stallPc
  // pc.io.flush := hazard.io.flushPc

  // fetch.io.stall := hazard.io.stallFetch
  // fetch.io.flush := hazard.io.flushFetch

  // idEx.io.stall := hazard.io.stallDecode
  // idEx.io.flush := hazard.io.flushDecode

  // exMem.io.stall := hazard.io.stallExecute
  // exMem.io.flush := hazard.io.flushExecute

  // memory.io.stall := hazard.io.stallMemory
  // memory.io.flush := hazard.io.flushMemory

  // hazard.io.pcOverride := pcOverride
  // hazard.io.decodeReg1Idx := decode.io.out.reg1Idx
  // hazard.io.decodeReg2Idx := decode.io.out.reg2Idx

  // hazard.io.executeReg1Idx := idEx.io.out.reg1Idx
  // hazard.io.executeReg2Idx := idEx.io.out.reg2Idx
  // hazard.io.executeRegDestIdx := idEx.io.out.regDestIdx
  // hazard.io.executeRegFileWriteSrc := idEx.io.out.control.regFileWriteSrc

  // hazard.io.memoryRegDestIdx := exMem.io.out.regDestIdx
  // hazard.io.memoryWriteToReg := exMem.io.out.control.writeToReg

  // hazard.io.writebackRegDestIdx := memWb.regDestIdx
  // hazard.io.writebackWriteToReg := memWb.control.writeToReg
}
