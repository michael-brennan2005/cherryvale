package debug

import chisel3._
import chisel3.util._
import harness._
import _root_.circt.stage.ChiselStage

// 8N1 uart transmitter
class UartTx(clocksPerBaud: Int, emitFormal: Boolean = true) extends Module {
  val io = IO(new Bundle {
    val tx = Output(Bool())

    val in = Flipped(Decoupled(UInt(8.W)))
  })

  private object State extends ChiselEnum {
    val Idle = Value(0.U)
    val Start = Value(1.U)
    val Data = Value(2.U)
  }

  import State._

  private val state = RegInit(State.Idle)

  // On (ready && valid) we move the input byte into this register, in case the input sender
  // changes the input while this module is transmitting
  val byte = RegInit(0.U(8.W))

  // +1 for each bit of the data transmitted.
  val bitCounter = dontTouch(RegInit(0.U(4.W)))

  // tx line idles at high
  io.tx := true.B

  // Baud strobe logic
  val counter = RegInit(0.U(log2Ceil(clocksPerBaud + 1).W))
  when(io.in.fire) {
    counter := clocksPerBaud.U - 1.U
  }.elsewhen(counter > 0.U) {
    counter := counter - 1.U
  }.elsewhen(state =/= State.Idle) {
    counter := clocksPerBaud.U - 1.U
  }
  val baudStrobe = (counter === 0.U)

  // Only ready when idling and baud strobe
  // Why the baudStrobe check? We don't keep a separate state for transmitting stop bit, we reuse
  // idle. If we don't have the baudStrobe check signalling that <clocksPerBaud> cycles have passed,
  // we could begin a new byte transmission before we've transmitted a full stop bit.
  io.in.ready := (state === State.Idle && baudStrobe)

  switch(state) {
    is(State.Idle) {
      when(io.in.fire) {
        state := State.Start
        byte := io.in.bits
      }

      io.tx := true.B
    }
    is(State.Start) {
      when(baudStrobe) {
        state := State.Data
        bitCounter := 0.U
      }

      io.tx := false.B
    }
    is(State.Data) {
      when(baudStrobe && bitCounter === 7.U) {
        state := State.Idle
      }.elsewhen(baudStrobe) {
        bitCounter := bitCounter + 1.U
      }

      io.tx := byte(bitCounter)
    }
  }

  // Verification
  if (emitFormal) {
    val isPastValid = RegInit(false.B)
    isPastValid := true.B

    val pastInValid = RegNext(io.in.valid)
    val pastInReady = RegNext(io.in.ready)
    val pastInBits = RegNext(io.in.bits)

    // Inputs should remain constant until they're serviced
    when(isPastValid && pastInValid && !pastInReady) {
      assume(io.in.valid === pastInValid)
      assume(io.in.bits === pastInBits)
    }

    // Baud counter should always be less than clocksPerBaud, and be counting down if non zero
    assert(counter < clocksPerBaud.U)

    val pastCounter = RegNext(counter)
    when(isPastValid && (pastCounter =/= 0.U)) {
      assert(counter === (pastCounter - 1.U))
    }

    // Check for correct outputs on tx line
    switch(state) {
      is(State.Idle) {
        assert(io.tx === true.B)
      }
      is(State.Start) {
        assert(io.tx === false.B)
      }
      is(State.Data) {
        assert(io.tx === byte(bitCounter))
      }
    }
  }
}

object UartTxFormal extends Formal {
  def build = new UartTx(20)

  override def checks: Seq[Check] = Seq(Bmc(20), Cover(40), Prove(20))
}
