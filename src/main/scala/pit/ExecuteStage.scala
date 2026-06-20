package pit

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class ExecuteStageOutput extends Bundle {
  val control = Output(new ControlSignals)

  val aluResult = Output(UInt(32.W))
  val memWriteData = Output(UInt(32.W))
  val regDestIdx = Output(UInt(5.W))

  val immediate = Output(UInt(32.W))
  val pcPlusFour = Output(UInt(32.W))
}

class ExecuteStage extends Module {
  val io = IO(new Bundle {
    val decodeInput = Flipped(new DecodeStageOutput)

    val out = new ExecuteStageOutput

    // Outputs that aren't used by memory stage
    val pcRedirect = Output(Bool())
    val pcTarget = Output(UInt(32.W))

    // These will come from hazard unit
    val aluSrcASelect = Input(UInt(2.W))
    val aluSrcBSelect = Input(UInt(2.W))

    // Forwarding - for srcs a and b, we can either
    // - use data from decode
    // - use data from memory stage
    // - use data from writeback stage
    val resultWriteback = Input(UInt(32.W))
    val resultMemory = Input(UInt(32.W))
  })

  val alu = Module(new Alu)
  alu.io.i_control := io.decodeInput.control.alu_op

  val takeBranch = MuxLookup(io.decodeInput.control.branchIf, false.B)(
    Seq(
      BranchIf.zero -> (alu.io.zero === true.B),
      BranchIf.notZero -> (alu.io.zero === false.B),
      BranchIf.neg -> (alu.io.neg === true.B)
    )
  )

  io.pcRedirect := io.decodeInput.control.jump || (io.decodeInput.control.branch && takeBranch)

  val srcA = MuxLookup(io.aluSrcASelect, io.decodeInput.reg1Data)(
    Seq(
      "b00".U -> io.decodeInput.reg1Data,
      "b01".U -> io.resultWriteback,
      "b10".U -> io.resultMemory
    )
  )
  alu.io.i_src_a := MuxLookup(io.decodeInput.control.alu1stOperand, srcA)(
    Seq(
      Alu1stOperand.registerValue -> srcA,
      Alu1stOperand.pc -> io.decodeInput.pc
    )
  )

  val srcB = MuxLookup(io.aluSrcBSelect, io.decodeInput.reg2Data)(
    Seq(
      "b00".U -> io.decodeInput.reg2Data,
      "b01".U -> io.resultWriteback,
      "b10".U -> io.resultMemory
    )
  )
  alu.io.i_src_b := MuxLookup(io.decodeInput.control.alu2ndOperand, srcB)(
    Seq(
      Alu2ndOperand.registerValue -> srcB,
      Alu2ndOperand.immediate -> io.decodeInput.immediate
    )
  )

  io.out.control := io.decodeInput.control
  io.out.aluResult := alu.io.o_result
  io.out.memWriteData := srcB

  when(io.decodeInput.control.jalr) {
    io.pcTarget := io.decodeInput.reg1Data + io.decodeInput.immediate
  }.otherwise {
    io.pcTarget := io.decodeInput.pc + io.decodeInput.immediate
  }

  io.out.immediate := io.decodeInput.immediate
  io.out.pcPlusFour := io.decodeInput.pcPlusFour

  io.out.regDestIdx := io.decodeInput.regDestIdx
}
