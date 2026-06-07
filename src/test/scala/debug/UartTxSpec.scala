package debug

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class UartTxSpec extends AnyFlatSpec with Matchers with ChiselSim {
  // 10 system clocks per UART bit.
  private val SysClock = 1_000_000
  private val BaudRate = 100_000
  private val TicksPerBaud = SysClock / BaudRate // full bit duration in clock cycles

  /** Drive a byte in over the ready/valid handshake and complete it in one cycle. */
  private def startByte(dut: UartTx, b: Int): Unit = {
    dut.io.in.bits.poke(b.U)
    dut.io.in.valid.poke(true.B)
    // Wait until the transmitter accepts (it is ready while idling), then complete the
    // handshake on the next edge.
    var guard = 0
    while (!dut.io.in.ready.peek().litToBoolean && guard < 1000) {
      dut.clock.step(1)
      guard += 1
    }
    dut.clock.step(1)
    dut.io.in.valid.poke(false.B)
  }

  /**
   * Sample a complete 8N1 frame off the `tx` line, the dual of UartRx's logic:
   * find the falling edge (start bit), sample mid-bit, then one sample per bit period.
   * Asserts the start and stop bits and returns the 8 data bits (LSB first).
   */
  private def receiveByte(dut: UartTx): Int = {
    // Find the start of the frame (line goes low). Usually already low right after the
    // handshake, in which case this is a no-op.
    var guard = 0
    while (dut.io.tx.peek().litToBoolean && guard < 1000) {
      dut.clock.step(1)
      guard += 1
    }
    // Land in the middle of the start bit and confirm it is low.
    dut.clock.step(TicksPerBaud / 2)
    dut.io.tx.expect(false.B, "start bit should be low")

    // Sample the 8 data bits, LSB first, one full bit period apart.
    var b = 0
    for (i <- 0 until 8) {
      dut.clock.step(TicksPerBaud)
      if (dut.io.tx.peek().litToBoolean) b |= (1 << i)
    }

    // Stop bit should be high.
    dut.clock.step(TicksPerBaud)
    dut.io.tx.expect(true.B, "stop bit should be high")
    b
  }

  /** Step until the transmitter returns to idle (ready high again). */
  private def waitIdle(dut: UartTx): Unit = {
    var guard = 0
    while (!dut.io.in.ready.peek().litToBoolean && guard < 1000) {
      dut.clock.step(1)
      guard += 1
    }
  }

  behavior of "UartTx"

  it should "idle high and ready after reset" in {
    simulate(new UartTx(SysClock, BaudRate)) { dut =>
      dut.io.in.valid.poke(false.B)
      dut.clock.step(2)
      dut.io.tx.expect(true.B)
      dut.io.in.ready.expect(true.B)
    }
  }

  it should "transmit a single byte as an 8N1 frame" in {
    simulate(new UartTx(SysClock, BaudRate)) { dut =>
      dut.io.in.valid.poke(false.B)
      dut.clock.step(2)
      startByte(dut, 0x55)
      receiveByte(dut) shouldBe 0x55
    }
  }

  it should "transmit every bit pattern correctly" in {
    for (value <- Seq(0x00, 0xff, 0xa5, 0x55, 0x01, 0x80)) {
      simulate(new UartTx(SysClock, BaudRate)) { dut =>
        dut.io.in.valid.poke(false.B)
        dut.clock.step(2)
        startByte(dut, value)
        receiveByte(dut) shouldBe value
      }
    }
  }

  it should "return to idle and ready after a frame" in {
    simulate(new UartTx(SysClock, BaudRate)) { dut =>
      dut.io.in.valid.poke(false.B)
      dut.clock.step(2)
      startByte(dut, 0x3c)
      receiveByte(dut) shouldBe 0x3c
      waitIdle(dut)
      dut.io.in.ready.expect(true.B)
      dut.io.tx.expect(true.B)
    }
  }

  it should "transmit back-to-back bytes" in {
    simulate(new UartTx(SysClock, BaudRate)) { dut =>
      dut.io.in.valid.poke(false.B)
      dut.clock.step(2)

      startByte(dut, 0xa5)
      receiveByte(dut) shouldBe 0xa5
      waitIdle(dut)

      startByte(dut, 0x5a)
      receiveByte(dut) shouldBe 0x5a
    }
  }
}
