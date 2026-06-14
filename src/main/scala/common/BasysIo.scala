package common

import chisel3._
import _root_.circt.stage.ChiselStage
import trunk.Request
import trunk.Response
import chisel3.util._

// Cherrytrunk slave device for Basys3 peripherals (LEDs, switches, buttons)
class BasysIo extends Module {
  val io = IO(new Bundle {
    val req = Input(new Request)
    val resp = Output(new Response)

    val led = Output(UInt(16.W))
    val sw = Input(UInt(16.W))
    val btn = Input(UInt(4.W))
  })

  private object State extends ChiselEnum {
    val Idle = Value(0.U)
    val Ack = Value(1.U)
  }

  import State._

  val ledState = RegInit(7.U(16.W)) // accessed at 0x0
  val swState = RegNext(io.sw) // accessed at 0x4
  val btnState = RegNext(io.btn) // accessed at 0x8

  private val moduleState = RegInit(State.Idle)
  val resp = Reg(new Response)

  io.led := ledState

  io.resp := resp
  resp.data := 0.U
  resp.err := false.B

  when(moduleState === State.Idle && io.req.stb) {
    moduleState := State.Ack

    resp.ack := true.B
    resp.err := false.B
    resp.data := MuxCase(
      0.U,
      Seq(
        (io.req.addr(3, 0) === "h0".U) -> ledState,
        (io.req.addr(3, 0) === "h4".U) -> swState,
        (io.req.addr(3, 0) === "h8".U) -> btnState
      )
    )

    when(io.req.we && io.req.addr(3, 0) === "h0".U) {
      ledState := io.req.data
    }
  }.otherwise {
    moduleState := State.Idle
    resp.ack := false.B
    resp.err := false.B
    resp.data := 0.U
  }
}
