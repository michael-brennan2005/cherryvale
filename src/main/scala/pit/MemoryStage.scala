package pit

import chisel3._
import _root_.circt.stage.ChiselStage
import chisel3.util._

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
    val out = new MemoryStageOutput

    val dataReq = EnqIO(new MemoryRequest)
    val dataResp = Flipped(Valid(UInt(32.W)))
  })

  io.dataReq.bits.addr := io.executeInput.aluResult
  io.dataReq.bits.we := io.executeInput.control.writeToMem
  io.dataReq.bits.writeData := io.executeInput.memWriteData
  io.dataReq.bits.writeMask := "b1111".U
  io.dataReq.valid := true.B

  // MuxLookup(io.executeInput.control.memAccess, 0.U(32.W))(
  //   Seq(
  //     MemAccess.byte -> MuxLookup(io.executeInput.aluResult(1, 0), 0.U(32.W))(
  //       Seq(
  //         0.U -> Cat(io.data.data(31, 8), io.executeInput.memWriteData(7, 0)),
  //         1.U -> Cat(io.data.data(31, 16), io.executeInput.memWriteData(7, 0), io.data.data(7, 0)),
  //         2.U -> Cat(io.data.data(31, 24), io.executeInput.memWriteData(7, 0), io.data.data(15, 0)),
  //         3.U -> Cat(io.executeInput.memWriteData(7, 0), io.data.data(23, 0))
  //       )
  //     ),
  //     MemAccess.half -> MuxLookup(io.executeInput.aluResult(1), 0.U(32.W))(
  //       Seq(
  //         0.U -> Cat(io.data.data(31, 16), io.executeInput.memWriteData(15, 0)),
  //         1.U -> Cat(io.executeInput.memWriteData(15, 0), io.data.data(15, 0))
  //       )
  //     ),
  //     MemAccess.word -> io.executeInput.memWriteData
  //   )
  // )

  // See FetchStage for explanation of why we do it like this
  val controlReg = RegNext(io.executeInput.control)
  val aluResultReg = RegNext(io.executeInput.aluResult)
  val regDestIdxReg = RegNext(io.executeInput.regDestIdx)
  val immediateReg = RegNext(io.executeInput.immediate)
  val pcPlusFourReg = RegNext(io.executeInput.pcPlusFour)

  io.out.control := controlReg
  io.out.aluResult := aluResultReg
  io.out.regDestIdx := regDestIdxReg
  io.out.immediate := immediateReg
  io.out.pcPlusFour := pcPlusFourReg
  io.out.memReadData := io.dataResp.bits

  // Handling for lb,lh,lw,sb,etc.
  // Memory is always accessed in word-sizes - to access bytes and halfs we look at the last 2 bits
  // of the memory address (computed from aluResult) to extract the right sub-data
  // Per RISCV spec: we only allow loads/stores on "natural alignments" - the address is divisible by
  // the size of the access in bytes
  // val unsignedByte = MuxLookup(io.executeInput.aluResult(1, 0), 0.U(32.W))(
  //   Seq(
  //     0.U -> io.data.data(7, 0),
  //     1.U -> io.data.data(15, 8),
  //     2.U -> io.data.data(23, 16),
  //     3.U -> io.data.data(31, 24)
  //   )
  // )

  // val unsignedHalf = MuxLookup(io.executeInput.aluResult(1), 0.U(32.W))(
  //   Seq(
  //     0.U -> io.data.data(15, 0),
  //     1.U -> io.data.data(31, 16)
  //   )
  // )

  // io.out.memReadData := MuxLookup(io.executeInput.control.memAccess, 0.U(32.W))(
  //   Seq(
  //     MemAccess.byte -> unsignedByte.asSInt.pad(32).asUInt,
  //     MemAccess.half -> unsignedHalf.asSInt.pad(32).asUInt,
  //     MemAccess.word -> io.data.data,
  //     MemAccess.byteUnsigned -> unsignedByte,
  //     MemAccess.halfUnsigned -> unsignedHalf
  //   )
  // )
}
