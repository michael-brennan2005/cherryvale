package debug

import elaborate.Elaboratable
import chisel3._
import _root_.circt.stage.ChiselStage
import elaborate.ClockGen
import chisel3.util.Fill
import chisel3.util.MuxLookup

class DebugTest(clocksPerBaud: Int = 5) extends Module {
  val io = IO(new Bundle {
    val rx = Input(Bool())
    val tx = Output(Bool())
  })

  // 100MHz -> 25Mhz
  val clockGen = Module(new ClockGen(10.0, 40.0))

  withClock(clockGen.io.clockOut) {
    val rx = Module(new UartRx(clocksPerBaud, emitFormal = false))
    val fifo = Module(new Fifo(UInt(8.W), 16, emitFormal = false))
    val tx = Module(new UartTx(clocksPerBaud, emitFormal = false))

    // RX <-> FIFO
    rx.io.out.ready := fifo.io.enq.ready
    fifo.io.enq.valid := rx.io.out.valid
    fifo.io.enq.bits := rx.io.out.bits

    // FIFO <-> TX
    // val txCounter = RegInit(0.U(12.W))
    // txCounter := txCounter + 1.U
    fifo.io.deq.ready := tx.io.in.ready // && (txCounter === 0.U)
    tx.io.in.valid := fifo.io.deq.valid // && (txCounter === 0.U)
    tx.io.in.bits := fifo.io.deq.bits

    rx.io.rx := io.rx
    io.tx := tx.io.tx
  }
}

object EmitDebugTest extends Elaboratable {
  def build = new DebugTest(2000)
}
