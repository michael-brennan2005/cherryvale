package pit

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class DecodeStageOutput extends Bundle {
  val control = Output(new ControlSignals)

  val reg1Idx = Output(UInt(5.W))
  val reg1Data = Output(UInt(32.W))

  val reg2Idx = Output(UInt(5.W))
  val reg2Data = Output(UInt(32.W))

  val regDestIdx = Output(UInt(5.W))
  val immediate = Output(UInt(32.W))

  val pc = Output(UInt(32.W))
  val pcPlusFour = Output(UInt(32.W))
}

class DecodeStage extends Module {
  val io = IO(new Bundle {
    val fetchInput = Flipped(new FetchStageOutput)

    val out = new DecodeStageOutput

    // Will come from writeback stage
    val regWriteIdx = Input(UInt(5.W))
    val regWriteData = Input(UInt(32.W))
    val regWriteEnable = Input(Bool())

    // Used for testing
    val regDebugIdx = Input(UInt(5.W))
    val regDebugData = Output(UInt(32.W))
  })

  val inst = io.fetchInput.inst
  val reg1Idx = inst(19, 15)
  val reg2Idx = inst(24, 20)
  val regDestIdx = inst(11, 7)

  // TODO: do we need to set reset for regfile here?
  val regFile = Module(new RegisterFile)
  regFile.io.readIdx1 := reg1Idx
  regFile.io.readIdx2 := reg2Idx

  regFile.io.readIdx3 := io.regDebugIdx
  io.regDebugData := regFile.io.readData3

  regFile.io.writeIdx := io.regWriteIdx
  regFile.io.writeData := io.regWriteData
  regFile.io.writeEn := io.regWriteEnable

  val control = Module(new ControlUnit)

  control.io.inst := inst
  val controlSignals = control.io.control

  // format: off
  io.out.immediate := MuxLookup(controlSignals.immEncoding, 0.U(32.W))(
    Seq(
      ImmediateEncoding.iType -> inst(31, 20).asSInt.pad(32).asUInt,
      ImmediateEncoding.sType -> Cat(inst(31, 25), inst(11, 7)).asSInt.pad(32).asUInt,
      ImmediateEncoding.bType -> Cat(inst(31), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W)).asSInt.pad(32).asUInt,
      ImmediateEncoding.jType -> Cat(inst(31), inst(20), inst(30, 21), 0.U(1.W)).asSInt.pad(32).asUInt,
      ImmediateEncoding.uType -> Cat(inst(31, 12), Fill(12, "b0".U(1.W)))
    )
  )
  // format: on

  io.out.control := controlSignals
  io.out.reg1Idx := reg1Idx
  io.out.reg1Data := regFile.io.readData1
  io.out.reg2Idx := reg2Idx
  io.out.reg2Data := regFile.io.readData2
  io.out.regDestIdx := regDestIdx
  io.out.pc := io.fetchInput.pc
  io.out.pcPlusFour := io.fetchInput.pcPlusFour
}
