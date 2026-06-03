package debug

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class UartRx(sysClockHz: Int, baudRate: Int) extends Module {
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

  val ticksPerBaud = (sysClockHz / baudRate) - 1
  val ticksPerHalfBaud = (sysClockHz / (2 * baudRate)) - 1

  val byte = RegInit(0.U(8.W))
  val valid = RegInit(false.B)

  // On detection of falling edge in Idle, we wait 1/2 the baud to ensure we'll be sampling in
  // the middle of each UART bit. After the start bit, we then wait 1 baud for each data bit and
  // stop bit sample.
  val tickCounter = RegInit(0.U((log2Ceil(ticksPerBaud)).W))

  // +1 for each bit of the data sampled.
  val bitCounter = dontTouch(RegInit(0.U(4.W)))

  // falling edge detector for start
  val fallingEdge = dontTouch(~io.rx && RegNext(io.rx))

  switch(state) {
    is(State.Idle) {
      when(fallingEdge) {
        // HI -> LO means transaction is about to start
        state := State.Start
        tickCounter := 0.U
      }

      valid := false.B
    }
    is(State.Start) {
      when(tickCounter === ticksPerHalfBaud.U && !io.rx) {
        // RX is LO -> successful start, go to data
        state := State.Data
        tickCounter := 0.U
        bitCounter := 0.U
        byte := 0.U
      }.elsewhen(tickCounter === ticksPerHalfBaud.U && io.rx) {
        // RX is HI -> unsuccessful start, go back to idle
        state := State.Idle
      }.otherwise {
        tickCounter := tickCounter + 1.U
      }

      valid := false.B
    }
    is(State.Data) {
      when(tickCounter === ticksPerBaud.U && bitCounter === 7.U) {
        state := State.Stop
        tickCounter := 0.U
        byte := Cat(io.rx, byte(7, 1)) // UART sends LSB first
      }.elsewhen(tickCounter === ticksPerBaud.U) {
        state := State.Data
        tickCounter := 0.U
        byte := Cat(io.rx, byte(7, 1))
        bitCounter := bitCounter + 1.U
      }.otherwise {
        tickCounter := tickCounter + 1.U
      }

      valid := false.B
    }
    is(State.Stop) {
      when(tickCounter === ticksPerBaud.U && io.rx) {
        valid := true.B
        state := State.Idle
      }.elsewhen(tickCounter === ticksPerBaud.U && !io.rx) {
        valid := false.B
        state := State.Idle
      }.otherwise {
        tickCounter := tickCounter + 1.U
        valid := false.B
      }
    }
  }

  io.out.bits := byte
  io.out.valid := valid
}
