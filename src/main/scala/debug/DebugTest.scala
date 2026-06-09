package debug

import elaborate.Elaboratable
import chisel3._
import _root_.circt.stage.ChiselStage
import elaborate.ClockGen
import chisel3.util.Fill

class DebugTest extends Module {
  val io = IO(new Bundle {
    val tx = Output(Bool())
  })

  // 100MHz -> 25Mhz
  val newClock = RegInit(0.U(2.W))
  newClock := newClock + 1.U

  withClock(newClock(1).asClock) {
    val tx = Module(new UartTx(2604, emitFormal = false))

    tx.io.in.valid := true.B
    tx.io.in.bits := 'H'.U

    io.tx := tx.io.tx
  }
}

object EmitDebugTest extends Elaboratable {
  def build = new DebugTest()
}
