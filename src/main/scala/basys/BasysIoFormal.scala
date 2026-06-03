package basys

import chisel3._
import cherrytrunk.{Request, Response}
import cherrytrunk.FormalProperties
import circt.stage.ChiselStage

// Formal harness for BasysIO
class BasysIoFormal extends Module {
  val req = IO(Input(new Request))
  val resp = IO(Output(new Response))
  val led = IO(Output(UInt(16.W)))
  val sw = IO(Input(UInt(16.W)))
  val btn = IO(Input(UInt(4.W)))

  private val slave = Module(new BasysIo)
  slave.io.req := req
  resp := slave.io.resp
  led := slave.io.led
  slave.io.sw := sw
  slave.io.btn := btn

  FormalProperties.checkSlave(req, slave.io.resp)
}

object BasysIoFormal extends App {
  ChiselStage.emitSystemVerilogFile(
    new BasysIoFormal,
    args = Array(
      "--target-dir",
      "./build/sv/"
    ),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-default-layer-specialization=enable"
      // "-preserve-values=named",
      // "-preserve-aggregate=all"
    )
  )
}
