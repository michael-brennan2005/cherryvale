package cherryvale

import chisel3._
import _root_.circt.stage.ChiselStage
import cherrytrunk.Crossbar
import debug.Dispatcher

// Cherryvale Soc1 - uart debug master
class Soc1 extends Module {
  val io = IO(new Bundle {
    val uartTx = Output(Bool())
    val uartRx = Input(Bool())
  })

  val crossbar = Module(new Crossbar)
  val dispatcher = Module(new Dispatcher)
}
