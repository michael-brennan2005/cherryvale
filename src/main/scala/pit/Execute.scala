package pit

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class ExecuteOutput extends Bundle {
  val control = Output(new ControlSignals)

  // Use the signals in control to discern whether these are valid values or junk.
  // If the instruction is loading from mem (regFileWriteSrc === .data), then memResult
  // is a valid value and aluResult is not. For all other instructions, aluResult is a valid
  // value and memResult is not.
  val aluResult = UInt(32.W)
  val memResult = UInt(32.W)

  // Passthrough from read operands stage
  // - what register to put ALU/MEM result in (source of reg data is contained in control signals)
  // - immediate (one of the things that can be written to register in writeback)
  val regDestIdx = UInt(5.W)
  val immediate = UInt(32.W)

  // pc, pc+4, instruction
  val fetch = new FetchOutput
}

// Execute stage - for loads/stores: interacts with D$; for all other instructions: interacts with
// ALU
class Execute extends Module {
  val io = IO(new Bundle {
    // Global CPU signals
    val halt = Input(Bool())
    val clear = Input(Bool())

    // Pipeline I/O
    val in = DeqIO(new ReadOperandsOutput)
    val out = EnqIO(new ExecuteOutput)

    // Connection to I$
    val req = EnqIO(new MemoryRequest)
    val resp = DeqIO(UInt(32.W))

    // pcRedirect - instructs preceding stages to flush their outputs upon a taken branch/jump.
    // newPc - the new PC to go to in case of a jump/branch
    val redirectPc = Output(Bool())
    val newPc = Output(UInt(32.W))

    // Forwarding to read operands stage - give the computed/loaded value and destination so
    // instruction will use the most up-to-date value.
    val regDestIdx = Output(UInt(5.W))
    val regDestData = Output(UInt(32.W))

    // Load-use hazard: the destination register of a load whose value is not yet available to
    // forward (it is issuing this cycle, or in flight and not responding). 0 when there is no such
    // load. ReadOperands stalls a dependent instruction against this.
    val loadHazardDest = Output(UInt(5.W))
  })

  // A clear flushes the in-flight fetch and the held output this cycle.
  val flush = io.clear

  // MARK: D$ logic
  // Registers to track whether there's a wanted request currently in-flight in the cache, and the
  // instruction of the request currently in the cache.
  val reqInst = RegInit(0.U.asTypeOf(new ReadOperandsOutput))
  val inFlight = RegInit(false.B)

  // Request logic . D$ always expects 4-byte aligned addresses, so we extract that, the subaddress
  // for bytes and half read, and then the correct writeData and writeMask (D$ expects lane-aligned
  // writeData)
  val addr = io.in.bits.memAddress & (~"b11".U(32.W))
  val subaddr = io.in.bits.memAddress(1, 0)

  io.req.bits.addr := addr
  io.req.bits.we := io.in.bits.control.writeToMem
  // writeData must be lane-aligned: shift the source bytes into their final position by the byte
  // sub-offset (subaddr * 8 bits). The mask below selects the matching lanes.
  io.req.bits.writeData := io.in.bits.memWriteData << (subaddr << 3.U)
  io.req.bits.writeMask := MuxLookup(io.in.bits.control.memAccess, "b0000".U)(
    Seq(
      MemAccess.byte -> ("b1".U << subaddr),
      MemAccess.half -> ("b11".U << subaddr),
      MemAccess.word -> "b1111".U
    )
  )

  // A request to the D$ is issued only for a load/store, and only when nothing else is in flight -
  // the cache and our single `reqInst` slot are single-outstanding. When the cache accepts the
  // request we latch the instruction and consume it from the input (see io.in.ready below).
  val isLoadStore =
    io.in.bits.control.writeToMem || io.in.bits.control.regFileWriteSrc === RegFileWriteSrc.data
  io.req.valid := io.in.valid && isLoadStore && !inFlight && !io.halt && !flush

  // In-flight tracking. A request becomes in-flight when the cache accepts it; the slot empties when
  // the response is taken/drained, or when a flush invalidates it.
  when(io.req.fire) {
    reqInst := io.in.bits
    inFlight := true.B
  }.elsewhen(flush || io.resp.fire) {
    inFlight := false.B
  }

  // Loaded value for the in-flight request: extract the addressed byte/half from the cache word
  // (little-endian lanes: lane k = bits[8k+7:8k]) and sign/zero-extend per the access type. This is
  // the value that will be written back, so it is what both memResult and the load forward carry.
  val respOff = reqInst.memAddress(1, 0)
  val respShifted = io.resp.bits >> (respOff << 3.U)
  val loadResult = MuxLookup(reqInst.control.memAccess, io.resp.bits)(
    Seq(
      MemAccess.byte -> respShifted(7, 0).asSInt.pad(32).asUInt,
      MemAccess.byteUnsigned -> respShifted(7, 0).pad(32),
      MemAccess.half -> respShifted(15, 0).asSInt.pad(32).asUInt,
      MemAccess.halfUnsigned -> respShifted(15, 0).pad(32),
      MemAccess.word -> io.resp.bits
    )
  )

  // MARK: ALU logic - combinationally evaluates the instruction currently at the input.
  val alu = Module(new Alu)
  alu.io.control := io.in.bits.control.alu_op
  alu.io.srcA := io.in.bits.aluSrcA
  alu.io.srcB := io.in.bits.aluSrcB

  val takeBranch = MuxLookup(io.in.bits.control.branchIf, false.B)(
    Seq(
      BranchIf.zero -> (alu.io.zero === true.B),
      BranchIf.notZero -> (alu.io.zero === false.B),
      BranchIf.neg -> (alu.io.neg === true.B)
    )
  )

  // MARK: Output and pipeline logic
  val outBits = RegInit(0.U.asTypeOf(new ExecuteOutput))
  val outValid = RegInit(false.B)

  // Two completion paths, both needing a free output slot (empty, or drained by the sink this cycle):
  //  - completeMem: the in-flight load/store retires when the D$ responds. Data is io.resp.bits and
  //    metadata is reqInst (the instruction that issued the request).
  //  - completeAlu: a non-memory op at the input retires immediately with the combinational ALU
  //    result and its own (io.in.bits) metadata.
  // The in-flight (older) op has priority, and a younger ALU op may not pass it: aluReady requires
  // !inFlight, so the pipeline stays in order.
  val outFree = !outValid || io.out.ready
  val memReady = inFlight && io.resp.valid
  val aluReady = io.in.valid && !isLoadStore && !inFlight
  val completeMem = !io.halt && !flush && outFree && memReady
  val completeAlu = !io.halt && !flush && outFree && aluReady && !memReady

  // Output register. Priority: flush discards, halt freezes, completing memory op, completing ALU
  // op, otherwise drain once the sink takes the current output.
  when(flush) {
    outValid := false.B
  }.elsewhen(io.halt) {
    // Freeze: hold outValid/outBits exactly as they are.
  }.elsewhen(completeMem) {
    outValid := true.B
    outBits.control := reqInst.control
    outBits.regDestIdx := reqInst.regDestIdx
    outBits.immediate := reqInst.immediate
    outBits.fetch := reqInst.fetch
    outBits.aluResult := 0.U // not meaningful for a load/store
    outBits.memResult := loadResult
  }.elsewhen(completeAlu) {
    outValid := true.B
    outBits.control := io.in.bits.control
    outBits.regDestIdx := io.in.bits.regDestIdx
    outBits.immediate := io.in.bits.immediate
    outBits.fetch := io.in.bits.fetch
    outBits.aluResult := alu.io.result
    outBits.memResult := 0.U // not meaningful for a non-memory op
  }.elsewhen(io.out.ready) {
    outValid := false.B
  }

  // Accept the input when we can store/retire it: a load/store on the cycle the cache accepts the
  // request, a non-memory op on the cycle it retires.
  io.in.ready := io.req.fire || completeAlu

  // Consume the D$ response when we flush, when nothing is in flight (drain a stray), or when the
  // in-flight op retires into the output register (held under back-pressure otherwise).
  io.resp.ready := flush || !inFlight || completeMem

  io.out.valid := outValid
  io.out.bits := outBits

  // MARK: PC redirect - assert only when a branch/jump actually retires (completeAlu), so a stalled,
  // halted, flushed, or bubble instruction never spuriously flushes the upstream stages.
  io.redirectPc := completeAlu && (io.in.bits.control.jump || (io.in.bits.control.branch && takeBranch))
  io.newPc := io.in.bits.jumpAddress

  // MARK: Forwarding to read operands - present the in-progress instruction's destination + would-be
  // writeback value combinationally, mirroring the writeback source mux. A load's value
  // (regFileWriteSrc === data) is NOT available this cycle, so it is not forwarded (idx forced to 0);
  // a load-use dependency needs a stall instead. Idx is also 0 when the instruction writes no
  // register, so ReadOperands' (idx =/= 0) guard disables forwarding.
  val fwdData = MuxLookup(io.in.bits.control.regFileWriteSrc, alu.io.result)(
    Seq(
      RegFileWriteSrc.aluResult -> alu.io.result,
      RegFileWriteSrc.pcPlusFour -> io.in.bits.fetch.pcPlus4,
      RegFileWriteSrc.immediate -> io.in.bits.immediate
    )
  )
  // We can forward this stage's input instruction if:
  // - There's a valid instruction in this stage (io.in.valid)
  // - That instruction writes to register but does not write data (loads aren't ready yet).
  val canForward =
    io.in.valid && io.in.bits.control.writeToReg &&
      io.in.bits.control.regFileWriteSrc =/= RegFileWriteSrc.data

  // A completing load also forwards, on the same port: on the completeMem cycle the EX-input slot is
  // a bubble (ReadOperands only reads then to release a stalled load-use consumer), so the two never
  // collide. EX-input result takes priority (it is the younger instruction).
  val reqIsLoad = reqInst.control.regFileWriteSrc === RegFileWriteSrc.data
  val memFwd = completeMem && reqInst.control.writeToReg && reqIsLoad
  io.regDestIdx := Mux(canForward, io.in.bits.regDestIdx, Mux(memFwd, reqInst.regDestIdx, 0.U))
  io.regDestData := Mux(canForward, fwdData, Mux(memFwd, loadResult, 0.U))

  // Load-use hazard destination: a load that writes a register but whose value is not yet
  // forwardable - either issuing at the input this cycle, or in flight and not responding yet (on
  // the completing cycle the forward above delivers it, so it drops to 0 and the consumer releases).
  val inIsLoad = io.in.bits.control.regFileWriteSrc === RegFileWriteSrc.data
  io.loadHazardDest := MuxCase(
    0.U,
    Seq(
      (io.in.valid && inIsLoad && io.in.bits.regDestIdx =/= 0.U) -> io.in.bits.regDestIdx,
      (inFlight && reqIsLoad && !completeMem && reqInst.regDestIdx =/= 0.U) -> reqInst.regDestIdx
    )
  )
}
