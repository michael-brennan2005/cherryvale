package vale

import chisel3._
import _root_.circt.stage.ChiselStage
import trunk.Crossbar
import debug.Dispatcher
import debug.DebugMaster
import common.BasysIo
import harness.Elaboratable
import harness.ClockGen

// Cherryvale Soc1 - uart debug master, basysIO controller, and cherrytrunk bus crossbar
class Soc1(clocksPerBaud: Int = 6, emitFormal: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val uartTx = Output(Bool())
    val uartRx = Input(Bool())

    val led = Output(UInt(16.W))
    val sw = Input(UInt(16.W))
    val btn = Input(UInt(4.W))
  })

  val crossbar = Module(new Crossbar)

  val debugMaster = Module(new DebugMaster(clocksPerBaud, emitFormal))
  val basysSlave = Module(new BasysIo)

  // uart outside connects
  debugMaster.io.uartRx := io.uartRx
  io.uartTx := debugMaster.io.uartTx

  // basys outside connects
  io.led := basysSlave.io.led
  basysSlave.io.btn := io.btn
  basysSlave.io.sw := io.sw

  // crossbar connects
  crossbar.io.masterReq := debugMaster.io.req
  debugMaster.io.resp := crossbar.io.masterResp

  crossbar.io.basysResp := basysSlave.io.resp
  basysSlave.io.req := crossbar.io.basysReq
}

class Soc1Inst extends Module {
  val io = IO(new Bundle {
    val uartTx = Output(Bool())
    val uartRx = Input(Bool())

    val led = Output(UInt(16.W))
    val sw = Input(UInt(16.W))
    val btn = Input(UInt(4.W))
  })

  // 100MHz -> 25MHz
  val clk = Module(new ClockGen(10.0, 40.0))

  withClock(clk.io.clockOut) {
    val soc1 = Module(new Soc1(2604, false))

    soc1.io <> io
  }
}

object EmitSoc1 extends Elaboratable {
  def build = new Soc1Inst()
}
