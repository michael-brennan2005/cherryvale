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
    val Start = Value(1.U)
    val Data = Value(2.U)
    val Stop = Value(3.U)
  }

  import State._

  private val state = RegInit(State.Idle)

  val byte = RegInit(0.U(8.W))
  val valid = RegInit(false.B)
  valid := false.B

  val counter = RegInit(0.U((log2Ceil(clocksPerBaud) + 1).W))
  when(state === State.Idle) {
    counter := 0.U
  }.otherwise {
    counter := counter - 1.U
  }
  val baudStrobe = dontTouch((counter === 0.U))

  val bitCounter = RegInit(0.U(3.W))

  // 2FF synchronizer (???)
  val qUart = RegNext(io.rx)
  val uart = RegNext(qUart)

  switch(state) {
    is(State.Idle) {
      when(!uart) {
        state := State.Start
        counter := (clocksPerBaud - 1 + clocksPerBaud / 2).U
      }
    }
    is(State.Start) {
      when(baudStrobe) {
        state := State.Data
        counter := (clocksPerBaud.U - 1.U)
        bitCounter := 7.U
        byte := Cat(uart, byte(7, 1))
      }
    }
    is(State.Data) {
      when(baudStrobe) {
        when(bitCounter === 0.U) {
          state := State.Stop
        }

        byte := Cat(uart, byte(7, 1))
        counter := (clocksPerBaud.U - 1.U)
        bitCounter := bitCounter - 1.U
      }
    }
    is(State.Stop) {
      when(baudStrobe) {
        when(io.rx) {
          // STOP is HI -> valid reception
          valid := true.B
          state := State.Idle
        }.otherwise {
          state := State.Idle
        }
      }
    }
  }

  io.out.bits := byte
  io.out.valid := valid
}
