package debug

import elaborate.Elaboratable
import chisel3._
import _root_.circt.stage.ChiselStage
import elaborate.ClockGen
import chisel3.util.Fill

class DebugTest extends Module {
  val io = IO(new Bundle {
    val rx = Input(Bool())
    val tx = Output(Bool())
    val led = Output(UInt(16.W))
  })

  val reg = RegNext(io.rx)
  io.tx := reg
  io.led := Fill(16, reg)
  // 25 MHz clock
  // val clockGen = Module(new ClockGen(10.0, 40.0))

  // withClock(clockGen.io.clockOut) {
  //   val sysClockHz = 25_000_000
  //   val baudRateHz = 118_000

  //   val rx = Module(new UartRx(sysClockHz, baudRateHz))
  //   val tx = Module(new UartTx(sysClockHz, baudRateHz))
  //   val fifo = Module(new Fifo(UInt(8.W), 32, emitFormal = false))

  //   rx.io.rx := io.rx
  //   io.tx := tx.io.tx

  //   fifo.io.enq.bits := rx.io.out.bits
  //   fifo.io.enq.valid := rx.io.out.valid
  //   rx.io.out.ready := fifo.io.enq.ready

  //   tx.io.in.bits := fifo.io.deq.bits
  //   tx.io.in.valid := fifo.io.deq.valid
  //   fifo.io.deq.ready := tx.io.in.ready
  // }
}

object EmitDebugTest extends Elaboratable {
  def build = new DebugTest()
}
