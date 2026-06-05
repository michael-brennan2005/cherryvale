package debug

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

// 8N1 uart transmitter
class UartTx(sysClockHz: Int, baudRateHz: Int) extends Module {
  val io = IO(new Bundle {
    val tx = Output(Bool())

    val in = Flipped(Decoupled(UInt(8.W)))
  })

  private object State extends ChiselEnum {
    val Idle = Value(0.U)
    val Start = Value(1.U)
    val Data = Value(2.U)
    val Stop = Value(3.U)
  }

  import State._

  private val state = RegInit(State.Idle)

  val ticksPerBaud = (sysClockHz / baudRateHz) - 1

  // On (ready && valid) we move the input byte into this register, in case the input sender
  // changes the input while this module is transmitting
  val byte = RegInit(0.U(8.W))

  val tickCounter = RegInit(0.U((log2Ceil(ticksPerBaud + 1)).W))

  // +1 for each bit of the data transmitted.
  val bitCounter = dontTouch(RegInit(0.U(4.W)))

  // Only ready when idling
  io.in.ready := state =/= State.Idle

  switch(state) {
    is(State.Idle) {
      when(io.in.ready && io.in.valid) {
        state := State.Start
        tickCounter := 0.U
        byte := io.in.bits
      }

      io.tx := true.B
    }
    is(State.Start) {
      when(tickCounter === ticksPerBaud.U) {
        state := State.Data
        tickCounter := 0.U
        bitCounter := 0.U
      }.otherwise {
        tickCounter := tickCounter + 1.U
      }

      io.tx := false.B
    }
    is(State.Data) {
      when(tickCounter === ticksPerBaud.U && bitCounter === 7.U) {
        state := State.Stop
        tickCounter := 0.U
      }.elsewhen(tickCounter === ticksPerBaud.U) {
        state := State.Data
        tickCounter := 0.U
        bitCounter := bitCounter + 1.U
      }.otherwise {
        tickCounter := tickCounter + 1.U
      }

      io.tx := byte(bitCounter)
    }
    is(State.Stop) {
      when(tickCounter === ticksPerBaud.U) {
        state := State.Idle
      }.otherwise {
        tickCounter := tickCounter + 1.U
      }

      io.tx := true.B
    }
  }
}
