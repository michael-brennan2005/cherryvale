package pit

import chisel3._
import _root_.circt.stage.ChiselStage

class RegisterFile extends Module {
  val io = IO(new Bundle {
    // Registers to read from
    // 2 for instruction execution and one as a debug port for testing
    val readIdx1 = Input(UInt(5.W))
    val readIdx2 = Input(UInt(5.W))
    val readIdx3 = Input(UInt(5.W))

    // Registers data
    val readData1 = Output(UInt(32.W))
    val readData2 = Output(UInt(32.W))
    val readData3 = Output(UInt(32.W))

    // Writing to registers - address, data, an enable
    val writeIdx = Input(UInt(5.W))
    val writeData = Input(UInt(32.W))
    val writeEn = Input(Bool())
  })

  val regs = RegInit(VecInit(Seq.fill(32)(0.U(32.W))))

  private def read(idx: UInt): UInt =
    Mux(
      idx === 0.U,
      0.U,
      Mux(io.writeEn && (io.writeIdx === idx), io.writeData, regs(idx))
    )

  io.readData1 := read(io.readIdx1)
  io.readData2 := read(io.readIdx2)
  io.readData3 := read(io.readIdx3)

  when(io.writeEn === true.B && !reset.asBool) {
    regs(io.writeIdx) := io.writeData
  }
}
