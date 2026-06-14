package debug

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import chisel3.ltl.AssertProperty

// 8N1 uart receiver
class UartRx(clocksPerBaud: Int, emitFormal: Boolean = true) extends Module {
  val io = IO(new Bundle {
    val rx = Input(Bool())

    val out = Decoupled(UInt(8.W))
  })

  private object State extends ChiselEnum {
    val Idle = Value(0.U)
    val BitZero = Value(1.U)
    val BitOne = Value(2.U)
    val BitTwo = Value(3.U)
    val BitThree = Value(4.U)
    val BitFour = Value(5.U)
    val BitFive = Value(6.U)
    val BitSix = Value(7.U)
    val BitSeven = Value(8.U)
    val Stop = Value(9.U)
  }

  import State._

  // 2FF synchronizer
  val qUart = RegInit(true.B)
  val ckUart = RegInit(true.B)
  qUart := io.rx
  ckUart := qUart

  private val state = RegInit(State.Idle)
  val counter = RegInit(0.U((log2Ceil(clocksPerBaud) + 1).W))
  val baudStrobe = (counter === 0.U)

  when(state === State.Idle) {
    state := State.Idle
    counter := 0.U

    when(!ckUart) {
      state := State.BitZero
      counter := (clocksPerBaud + clocksPerBaud / 2 - 1).U
    }
  }.elsewhen(baudStrobe) {
    state := state.next
    counter := (clocksPerBaud - 1).U

    when(state === State.Stop) {
      state := State.Idle
      counter := 0.U
    }
  }.otherwise {
    counter := counter - 1.U
  }

  val outReg = RegInit(0.U(8.W))
  when(baudStrobe && state =/= State.Stop) {
    outReg := Cat(ckUart, outReg(7, 1))
  }

  io.out.bits := outReg

  val validReg = RegNext(baudStrobe && state === State.Stop)
  io.out.valid := validReg
}
