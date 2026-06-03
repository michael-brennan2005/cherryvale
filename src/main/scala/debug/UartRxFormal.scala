package debug

import chisel3._
import cherrytrunk.{Request, Response}
import cherrytrunk.FormalProperties
import circt.stage.ChiselStage
import chisel3.util.Decoupled
import debug.DecoupledFormalProperties

// Formal harness for UartRx
class DecoupledFormal extends Module {
  val write = IO(Decoupled(UInt(8.W)))
  val rx = IO(Input(Bool()))

  private val uart = Module(new UartRx(100, 5))
  uart.io.rx := rx
  write <> uart.io.out

  DecoupledFormalProperties.emitTx(write)
}

object DecoupledFormal extends App {
  ChiselStage.emitSystemVerilogFile(
    new DecoupledFormal,
    args = Array(
      "--target-dir",
      "./build/sv/"
    ),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-default-layer-specialization=enable"
    )
  )
}
