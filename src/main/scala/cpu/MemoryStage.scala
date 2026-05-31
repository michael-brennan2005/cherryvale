package cpu

import chisel3._
import _root_.circt.stage.ChiselStage
import chisel3.util.RegEnable

class MemoryStageOutput extends Bundle {
  val control = Output(new ControlSignals)

  val aluResult = Output(UInt(32.W))
  val memReadData = Output(UInt(32.W))
  val regDestIdx = Output(UInt(5.W))

  val immediate = Output(UInt(32.W))
  val pcPlusFour = Output(UInt(32.W))
}

class MemoryStage extends Module {
  val io = IO(new Bundle {
    val executeInput = Flipped(new ExecuteStageOutput)

    val data = Flipped(new ReadWritePort)

    val out = new MemoryStageOutput
  })

  io.data.addr := io.executeInput.aluResult
  io.data.w_data := io.executeInput.memWriteData
  io.data.w_en := io.executeInput.control.writeToMem

  io.out.control := io.executeInput.control
  io.out.aluResult := io.executeInput.aluResult
  io.out.memReadData := io.data.data
  io.out.regDestIdx := io.executeInput.regDestIdx

  io.out.immediate := io.executeInput.immediate
  io.out.pcPlusFour := io.executeInput.pcPlusFour
}
