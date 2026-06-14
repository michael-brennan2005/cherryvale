package debug

import chisel3._
import _root_.circt.stage.ChiselStage
import chisel3.util._
import trunk.Request
import trunk.Response
import common.Fifo

class DebugMaster(clocksPerBaud: Int = 6, emitFormal: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val req = Output(new Request)
    val resp = Input(new Response)

    val uartTx = Output(Bool())
    val uartRx = Input(Bool())
  })

  val rx = Module(new UartRx(clocksPerBaud, emitFormal))
  val rxFifo = Module(new Fifo(UInt(8.W), 32, emitFormal))

  val tx = Module(new UartTx(clocksPerBaud, emitFormal))
  val txFifo = Module(new Fifo(UInt(8.W), 32, emitFormal))

  val dispatcher = Module(new Dispatcher(emitFormal))

  // Outside world -> UartRx -> FIFO -> Dispatcher -> Bus request
  rx.io.rx := io.uartRx

  rxFifo.io.enq.valid := rx.io.out.valid
  rxFifo.io.enq.bits := rx.io.out.bits
  rx.io.out.ready := rxFifo.io.enq.ready

  dispatcher.io.deq.valid := rxFifo.io.deq.valid
  dispatcher.io.deq.bits := rxFifo.io.deq.bits
  rxFifo.io.deq.ready := dispatcher.io.deq.ready

  io.req := dispatcher.io.req

  // Bus response -> Dispatcher -> FIFO -> UartTx -> Outside world
  dispatcher.io.resp := io.resp

  txFifo.io.enq.valid := dispatcher.io.enq.valid
  txFifo.io.enq.bits := dispatcher.io.enq.bits
  dispatcher.io.enq.ready := txFifo.io.enq.ready

  tx.io.in.valid := txFifo.io.deq.valid
  tx.io.in.bits := txFifo.io.deq.bits
  txFifo.io.deq.ready := tx.io.in.ready

  io.uartTx := tx.io.tx
}
