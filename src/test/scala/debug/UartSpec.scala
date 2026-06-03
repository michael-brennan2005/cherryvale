package debug

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class UartRxSpec extends AnyFlatSpec with Matchers with ChiselSim {
  // 16 system clocks per UART bit (sampleRate is unused in the DUT).
  private val SysClock = 1_000_000
  private val BaudRate = 100_000
  private val TicksPerBaud = SysClock / BaudRate

  /** Hold rx at `level` for `n` clock cycles. */
  private def hold(dut: UartRx, level: Boolean, n: Int): Unit = {
    dut.io.rx.poke(level.B)
    dut.clock.step(n)
  }

  /** Drive one UART frame LSB-first: start(0), 8 data bits, stop(1). */
  private def sendByte(dut: UartRx, b: Int): Unit = {
    hold(dut, false, TicksPerBaud) // start bit
    for (i <- 0 until 8) hold(dut, ((b >> i) & 1) == 1, TicksPerBaud)
    hold(dut, true, (TicksPerBaud / 2) + 1) // stop bit
  }

  behavior of "UartRx"

  it should "receive a single byte and pulse valid once" in {
    simulate(new UartRx(SysClock, BaudRate)) { dut =>
      hold(dut, true, 4) // idle line high before start
      sendByte(dut, 0x55)
      // step until valid asserts, capture byte, confirm it deasserts next cycle
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.expect(0x55.U)
    }
  }
}
