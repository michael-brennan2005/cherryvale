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

class MemoryStageOutput extends Bundle {
  val control = Output(new ControlSignals)

  val aluResult = Output(UInt(32.W))
  val memReadData = Output(UInt(32.W))
  val regDestIdx = Output(UInt(5.W))

  val immediate = Output(UInt(32.W))
  val pcPlusFour = Output(UInt(32.W))
}

// "Core" compute path for CherryPit - contains datapath (fetch -> decode -> execute -> mem),
// control unit, PC, and reg file.
// This core assumes a max 1-cycle latency for I$ and D$ (data appears on the cycle immediately after
// address). If a cache needs longer than 1-cycle then it should issue a halt to the CPU.
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

  // Register Flow is PC -> I$ -> F/D -> D/E -> E/M -> D$; hazard handles stalls and flushes and (TODO: forwards)
  val hazard = Module(new HazardUnit)
  val decode = Module(new DecodeStage)
  val execute = Module(new ExecuteStage)

  val pc = Module(new PSR(UInt(32.W)))
  val iCacheAddr = RegInit(0.U(32.W))
  val fetchOut = Module(new PSR(new FetchStageOutput))
  val decodeOut = Module(new PSR(new DecodeStageOutput))
  val executeOut = Module(new PSR(new ExecuteStageOutput))
  val dCacheReg = RegNext(executeOut.io.out)
  val memoryOut = Module(new PSR(new MemoryStageOutput))
  val resultWriteback = WireInit(0.U(32.W))

  // Stuff for hazard
  hazard.io.halt := io.halt
  hazard.io.iCacheReqReady := io.codeReq.ready
  hazard.io.iCacheRespValid := io.codeResp.valid
  hazard.io.dCacheReqReady := io.dataReq.ready
  hazard.io.dCacheRespValid := io.dataResp.valid
  hazard.io.pcRedirect := execute.io.pcRedirect

  hazard.io.decodeReg1Idx := decodeOut.io.in.reg1Idx
  hazard.io.decodeReg2Idx := decodeOut.io.in.reg2Idx
  hazard.io.decodeOutRegDestIdx := decodeOut.io.out.regDestIdx
  hazard.io.executeOutRegDestIdx := executeOut.io.out.regDestIdx
  hazard.io.dCacheRegDestIdx := dCacheReg.regDestIdx
  hazard.io.memoryOutRegDestIdx := memoryOut.io.out.regDestIdx

  // Stuff for PC
  when(execute.io.pcRedirect) {
    pc.io.in := execute.io.pcTarget
  }.otherwise {
    pc.io.in := pc.io.out + 4.U
  }
  pc.io.stall := hazard.io.stallPcOut
  pc.io.flush := hazard.io.flushPcOut

  // Stuff for I$
  // If fetchOut is stalled then we should hold whatever read is currently going on in I$, hence
  // this when condition and MUX on codeReq.bits.addr
  when(!hazard.io.stallFetchOut) {
    iCacheAddr := pc.io.out
  }

  io.codeReq.bits.addr := Mux(hazard.io.stallFetchOut, iCacheAddr, pc.io.out)
  io.codeReq.bits.we := false.B
  io.codeReq.bits.writeData := 0.U
  io.codeReq.bits.writeMask := 0.U
  io.codeReq.valid := !hazard.io.stallICache

  // Stuff for Fetch (not really a "real" stage - just registering the iCache outputs)
  fetchOut.io.in.inst := io.codeResp.bits
  fetchOut.io.in.pc := iCacheAddr
  fetchOut.io.in.pcPlusFour := iCacheAddr + 4.U
  fetchOut.io.stall := hazard.io.stallFetchOut
  fetchOut.io.flush := hazard.io.flushFetchOut

  // Stuff for Decode
  decode.io.fetchInput := fetchOut.io.out
  decode.io.regWriteIdx := memoryOut.io.out.regDestIdx
  decode.io.regWriteData := resultWriteback
  decode.io.regWriteEnable := memoryOut.io.out.control.writeToReg

  decode.io.regDebugIdx := io.regDebugIdx.getOrElse(0.U)
  io.regDebugData match {
    case Some(value) => value := decode.io.regDebugData
    case None        => {}
  }

  decodeOut.io.in := decode.io.out
  decodeOut.io.stall := hazard.io.stallDecodeOut
  decodeOut.io.flush := hazard.io.flushDecodeOut

  // Stuff for execute
  execute.io.decodeInput := decodeOut.io.out

  // TODO: real values when we can start to do forwarding
  execute.io.aluSrcASelect := 0.U
  execute.io.aluSrcBSelect := 0.U
  execute.io.resultWriteback := 0.U
  execute.io.resultMemory := 0.U

  executeOut.io.in := execute.io.out
  executeOut.io.stall := hazard.io.stallExecuteOut
  executeOut.io.flush := hazard.io.flushExecuteOut

  // Stuff for data memory
  val addr = executeOut.io.out.aluResult & (~"b11".U(32.W)) // what is sent to D$
  val subaddr = executeOut.io.out.aluResult(1, 0) // for byte & half reads
  val writeData = executeOut.io.out.memWriteData << (subaddr << 3.U)
  val writeMask = MuxLookup(executeOut.io.out.control.memAccess, "b0000".U)(
    Seq(
      MemAccess.byte -> ("b1".U << subaddr),
      MemAccess.half -> ("b11".U << subaddr),
      MemAccess.word -> "b1111".U
    )
  )

  io.dataReq.bits.addr := addr
  io.dataReq.bits.we := executeOut.io.out.control.writeToMem
  io.dataReq.bits.writeData := writeData
  io.dataReq.bits.writeMask := writeMask
  io.dataReq.valid := !hazard.io.stallDCache

  // Stuff for memory (not really a "real" stage - just registering the dCache outputs)
  memoryOut.io.in.control := dCacheReg.control
  memoryOut.io.in.aluResult := dCacheReg.aluResult
  memoryOut.io.in.immediate := dCacheReg.immediate
  memoryOut.io.in.pcPlusFour := dCacheReg.pcPlusFour
  memoryOut.io.in.regDestIdx := dCacheReg.regDestIdx

  memoryOut.io.in.memReadData := io.dataResp.bits

  memoryOut.io.stall := hazard.io.stallMemoryOut
  memoryOut.io.flush := hazard.io.flushMemoryOut

  // Stuff for writeback
  val addrWB = memoryOut.io.out.aluResult & (~"b11".U(32.W)) // what is sent to D$
  val subaddrWB = memoryOut.io.out.aluResult(1, 0) // for byte & half reads
  val rawMemWB = memoryOut.io.out.memReadData
  val accessType = memoryOut.io.out.control.memAccess
  val shifted = rawMemWB >> (subaddrWB << 3.U) // align selected byte/half to LSBs
  val data = MuxLookup(accessType, rawMemWB)(
    Seq(
      MemAccess.byte -> shifted(7, 0).asSInt.pad(32).asUInt,
      MemAccess.byteUnsigned -> shifted(7, 0),
      MemAccess.half -> shifted(15, 0).asSInt.pad(32).asUInt,
      MemAccess.halfUnsigned -> shifted(15, 0),
      MemAccess.word -> rawMemWB
    )
  )

  resultWriteback := MuxLookup(
    memoryOut.io.out.control.regFileWriteSrc,
    0.U(32.W)
  )(
    Seq(
      RegFileWriteSrc.data -> data,
      RegFileWriteSrc.aluResult -> memoryOut.io.out.aluResult,
      RegFileWriteSrc.pcPlusFour -> memoryOut.io.out.pcPlusFour,
      RegFileWriteSrc.immediate -> memoryOut.io.out.immediate
    )
  )
}
