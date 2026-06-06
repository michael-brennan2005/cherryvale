package debug

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import chisel3.ltl.AssertProperty

// 8N1 uart receiver
class UartRx(sysClockHz: Int, baudRateHz: Int) extends Module {
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

  // On detection of falling edge in Idle, we wait 1/2 the baud to ensure we'll be sampling in
  // the middle of each UART bit. After the start bit, we then wait 1 baud for each data bit and
  // stop bit sample.
  require(sysClockHz / baudRateHz >= 2)
  val ticksPerBaud = (sysClockHz / baudRateHz) - 1
  val tickCounter = RegInit(0.U((log2Ceil(ticksPerBaud + 1)).W))
  val baudTick = tickCounter === (ticksPerBaud).U
  val halfBaudTick = tickCounter === (ticksPerBaud / 2).U

  // +1 for each bit of the data sampled.
  val bitCounter = RegInit(0.U(3.W))

  // falling edge detector for start
  val fallingEdge = ~io.rx && RegNext(io.rx)

  assert(fallingEdge && (bitCounter <= 3.U))
  switch(state) {
    is(State.Idle) {
      when(fallingEdge) {
        // HI -> LO means transaction is about to start
        state := State.Start
        tickCounter := 0.U
      }
    }
    is(State.Start) {
      when(halfBaudTick && !io.rx) {
        when(!io.rx) {
          // successful start, go to data
          state := State.Data
          tickCounter := 0.U
          bitCounter := 0.U
          byte := 0.U
        }.otherwise {
          // unsuccessful start, back to idle
          state := State.Idle
        }
      }.otherwise {
        tickCounter := tickCounter + 1.U
      }
    }
    is(State.Data) {
      when(baudTick && bitCounter === 7.U) {
        when(bitCounter === 7.U) {
          state := State.Stop
          tickCounter := 0.U
          byte := Cat(io.rx, byte(7, 1)) // UART sends LSB first
        }.otherwise {
          bitCounter := bitCounter + 1.U
          tickCounter := 0.U
          byte := Cat(io.rx, byte(7, 1))
        }
      }.otherwise {
        tickCounter := tickCounter + 1.U
      }
    }
    is(State.Stop) {
      when(baudTick) {
        when(io.rx) {
          // STOP is HI -> valid reception
          valid := true.B
          state := State.Idle
        }.otherwise {
          state := State.Idle
        }
      }.otherwise {
        tickCounter := tickCounter + 1.U
      }
    }
  }

  io.out.bits := byte
  io.out.valid := valid
}
