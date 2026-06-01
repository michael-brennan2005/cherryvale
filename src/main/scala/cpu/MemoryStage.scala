package cpu

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

    val data = Flipped(new ReadWritePort)

    val out = new MemoryStageOutput
  })

  io.data.addr := io.executeInput.aluResult
  // TODO: HORRIBLE way of doing this
  // Doing it this way for now until we build a bus/real mem infra
  io.data.w_data := MuxLookup(io.executeInput.control.memAccess, 0.U(32.W))(
    Seq(
      MemAccess.byte -> MuxLookup(io.executeInput.aluResult(1, 0), 0.U(32.W))(
        Seq(
          0.U -> Cat(io.data.data(31, 8), io.executeInput.memWriteData(7, 0)),
          1.U -> Cat(io.data.data(31, 16), io.executeInput.memWriteData(7, 0), io.data.data(7, 0)),
          2.U -> Cat(io.data.data(31, 24), io.executeInput.memWriteData(7, 0), io.data.data(15, 0)),
          3.U -> Cat(io.executeInput.memWriteData(7, 0), io.data.data(23, 0))
        )
      ),
      MemAccess.half -> MuxLookup(io.executeInput.aluResult(1), 0.U(32.W))(
        Seq(
          0.U -> Cat(io.data.data(31, 16), io.executeInput.memWriteData(15, 0)),
          1.U -> Cat(io.executeInput.memWriteData(15, 0), io.data.data(15, 0))
        )
      ),
      MemAccess.word -> io.executeInput.memWriteData
    )
  )
  io.data.w_en := io.executeInput.control.writeToMem

  io.out.control := io.executeInput.control
  io.out.aluResult := io.executeInput.aluResult

  // Handling for lb,lh,lw,sb,etc.
  // Memory is always accessed in word-sizes - to access bytes and halfs we look at the last 2 bits
  // of the memory address (computed from aluResult) to extract the right sub-data
  // Per RISCV spec: we only allow loads/stores on "natural alignments" - the address is divisible by
  // the size of the access in bytes
  val unsignedByte = MuxLookup(io.executeInput.aluResult(1, 0), 0.U(32.W))(
    Seq(
      0.U -> io.data.data(7, 0),
      1.U -> io.data.data(15, 8),
      2.U -> io.data.data(23, 16),
      3.U -> io.data.data(31, 24)
    )
  )

  val unsignedHalf = MuxLookup(io.executeInput.aluResult(1), 0.U(32.W))(
    Seq(
      0.U -> io.data.data(15, 0),
      1.U -> io.data.data(31, 16)
    )
  )

  io.out.memReadData := MuxLookup(io.executeInput.control.memAccess, 0.U(32.W))(
    Seq(
      MemAccess.byte -> unsignedByte.asSInt.pad(32).asUInt,
      MemAccess.half -> unsignedHalf.asSInt.pad(32).asUInt,
      MemAccess.word -> io.data.data,
      MemAccess.byteUnsigned -> unsignedByte,
      MemAccess.halfUnsigned -> unsignedHalf
    )
  )

  io.out.regDestIdx := io.executeInput.regDestIdx

  io.out.immediate := io.executeInput.immediate
  io.out.pcPlusFour := io.executeInput.pcPlusFour
}
