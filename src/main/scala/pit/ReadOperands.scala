package pit

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class ReadOperandsOutput extends Bundle {
  val control = Output(new ControlSignals)

  // ALU operands for arithmetic or branching instructions
  val aluSrcA = UInt(32.W)
  val aluSrcB = UInt(32.W)

  // address to load/store memory from, register data for load, and address to jump or branch to.
  val memWriteData = UInt(32.W)
  val memAddress = UInt(32.W)
  val jumpAddress = UInt(32.W)

  // Passthrough from Decode stage
  // - what register to put ALU/MEM result in (source of reg data is contained in control signals)
  // - immediate (one of the things that can be written to register in writeback)
  val regDestIdx = UInt(5.W)
  val immediate = UInt(32.W)

  // pc, pc+4, instruction
  val fetch = new FetchOutput
}

// Read operands stage - reads register file, and calculates effective address for loads & stores
class ReadOperands(exposeSimPorts: Boolean = false) extends Module {
  val io = IO(new Bundle {
    // Global CPU signals
    val halt = Input(Bool())
    val clear = Input(Bool())

    // Pipeline I/O
    val in = DeqIO(new DecodeOutput)
    val out = EnqIO(new ReadOperandsOutput)

    // From execute stage:
    // - Register destination idx and data (for data forwarding)
    // - pcRedirect (flush if branch or jump is taken)
    val redirectPc = Input(Bool())
    val executeRegDestIdx = Input(UInt(5.W))
    val executeRegDestData = Input(UInt(32.W))

    // Load-use hazard: destination of a load in execute whose value can't be forwarded yet. If the
    // instruction we're about to read depends on it, we stall (hold our input) until it clears.
    val executeLoadHazardDest = Input(UInt(5.W))

    // From writeback stage:
    // Register idx and data (for committing an instruction result to regs)
    val writebackRegDestIdx = Input(UInt(5.W))
    val writebackRegDestData = Input(UInt(32.W))
    val writebackRegDestEn = Input(Bool())

    // Expose a register read port for sim
    val regSimIdx = if (exposeSimPorts) Some(Input(UInt(5.W))) else None
    val regSimData = if (exposeSimPorts) Some(Output(UInt(32.W))) else None
  })

  // Register & module definitions
  val registerFile = Module(new RegisterFile)
  val outBits = RegInit(0.U.asTypeOf(new ReadOperandsOutput))
  val outValid = RegInit(false.B)

  // Register file connects (TODO: wire clear to this so registers zero out)
  registerFile.io.readIdx1 := io.in.bits.reg1Idx
  registerFile.io.readIdx2 := io.in.bits.reg2Idx

  if (exposeSimPorts) {
    registerFile.io.readIdx3 := io.regSimIdx.get
    io.regSimData.get := registerFile.io.readData3
  }

  registerFile.io.writeIdx := io.writebackRegDestIdx
  registerFile.io.writeData := io.writebackRegDestData
  registerFile.io.writeEn := io.writebackRegDestEn

  // Accessing register data needs to take into account forwarding.
  // Forwarding from execute: if the destination of the instruction in execute is an operand of the
  // instruction here, use the value coming from execute rather than the "stale" value in the
  // register file.
  // Forwarding from writeback: this is handled internally by the RegisterFile module - if a read
  // address == write address on a write (writeEn == 1), then readData will be writeData, not the
  // "stale" value in the register file.
  val reg1Data = Mux(
    io.in.bits.reg1Idx =/= 0.U && io.executeRegDestIdx === io.in.bits.reg1Idx,
    io.executeRegDestData,
    registerFile.io.readData1
  )

  val reg2Data = Mux(
    io.in.bits.reg2Idx =/= 0.U && io.executeRegDestIdx === io.in.bits.reg2Idx,
    io.executeRegDestData,
    registerFile.io.readData2
  )

  // Effective address for loads/stores is always RS1 + IMM. Calculate that here to pass
  // to execute stage.
  val memAddress = reg1Data + io.in.bits.immediate
  val jumpAddress = Wire(UInt(32.W))
  when(io.in.bits.control.jalr) {
    // JALR - jump address is RS1 + IMM, reuse memAddress
    jumpAddress := memAddress
  }.otherwise {
    // JAL and branches - jump address is PC + IMM.
    jumpAddress := io.in.bits.fetch.pc + io.in.bits.immediate
  }

  // TODO: forwarding - definitely will need from execute, but do we need a forward from writeback?
  val aluSrcA = MuxLookup(io.in.bits.control.alu1stOperand, reg1Data)(
    Seq(
      Alu1stOperand.registerValue -> reg1Data,
      Alu1stOperand.pc -> io.in.bits.fetch.pc
    )
  )

  val aluSrcB = MuxLookup(io.in.bits.control.alu2ndOperand, reg2Data)(
    Seq(
      Alu2ndOperand.registerValue -> reg2Data,
      Alu2ndOperand.immediate -> io.in.bits.immediate
    )
  )

  // A redirect or clear flushes the held output this cycle.
  val flush = io.redirectPc || io.clear

  // Output register. Priority: flush discards, halt freezes, otherwise load the next decoded
  // instruction or drain the current one.
  when(flush) {
    outValid := false.B
  }.elsewhen(io.halt) {}
    .elsewhen(io.in.fire) {
      outValid := true.B
      outBits.control := io.in.bits.control
      outBits.aluSrcA := aluSrcA
      outBits.aluSrcB := aluSrcB
      outBits.memWriteData := reg2Data
      outBits.immediate := io.in.bits.immediate
      outBits.memAddress := memAddress
      outBits.jumpAddress := jumpAddress
      outBits.regDestIdx := io.in.bits.regDestIdx
      outBits.fetch := io.in.bits.fetch
    }
    .elsewhen(io.out.ready) {
      outValid := false.B
    }
  io.out.bits := outBits
  io.out.valid := outValid

  // Load-use stall: the instruction at our input reads a register that an in-execute load will write
  // but can't forward yet. Hold the input until execute clears the hazard (drops the dest to 0) - by
  // then the load's value arrives via the execute forward (completing load) or the regfile bypass.
  // Conservative: matches on index regardless of whether the operand is actually consumed.
  val loadUseStall = io.executeLoadHazardDest =/= 0.U &&
    (io.in.bits.reg1Idx === io.executeLoadHazardDest ||
      io.in.bits.reg2Idx === io.executeLoadHazardDest)

  // We can accept data if:
  // - we are not in halt or clear, and not stalling on a load-use hazard
  // - we can move the decoded result into output reg - NOT(output consumer isnt ready output is valid)
  io.in.ready := !io.halt && !flush && !loadUseStall && !(!io.out.ready && outValid)
}
