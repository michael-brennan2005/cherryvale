package debug

import elaborate.Elaboratable
import chisel3._
import _root_.circt.stage.ChiselStage
import elaborate.ClockGen
import chisel3.util.Fill
import chisel3.util.MuxLookup

class DebugTest(clocksPerBaud: Int = 5) extends Module {
  val io = IO(new Bundle {
    val tx = Output(Bool())
  })

  // 100MHz -> 25Mhz
  val clockGen = Module(new ClockGen(10.0, 40.0))

  withClock(clockGen.io.clockOut) {
    val tx = Module(new UartTx(clocksPerBaud, emitFormal = false))
    val fifo = Module(new Fifo(UInt(8.W), 16, emitFormal = false))

    val txCounter = RegInit(0.U(12.W))
    txCounter := txCounter + 1.U
    fifo.io.deq.ready := tx.io.in.ready && (txCounter === 0.U)
    tx.io.in.valid := fifo.io.deq.valid && (txCounter === 0.U)
    tx.io.in.bits := fifo.io.deq.bits

    val sOne :: sTwo :: sThree :: sFour :: sFive :: sSix :: sSeven :: nil = util.Enum(7)
    val state = RegInit(sOne)

    when(fifo.io.enq.ready) {
      state := Mux(
        state === sSeven,
        sOne,
        state + 1.U
      )
    }

    fifo.io.enq.valid := true.B
    fifo.io.enq.bits := MuxLookup(state, 0.U)(
      Seq(
        sOne -> 'H'.U,
        sTwo -> 'e'.U,
        sThree -> 'l'.U,
        sFour -> 'l'.U,
        sFive -> 'o'.U,
        sSix -> '!'.U,
        sSeven -> '\n'.U
      )
    )

    io.tx := tx.io.tx
  }
}

object EmitDebugTest extends Elaboratable {
  def build = new DebugTest(2000)
}
