package debug

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class UartRxSpec extends AnyFlatSpec with Matchers with ChiselSim {
  private val ClocksPerBaud = 6

  /** Hold rx at `level` for `n` clock cycles. */
  private def hold(dut: UartRx, level: Boolean, n: Int): Unit = {
    dut.io.rx.poke(level.B)
    dut.clock.step(n)
  }

  /** Drive one UART frame LSB-first: start(0), 8 data bits, stop(1). */
  private def sendByte(dut: UartRx, b: Int): Unit = {
    hold(dut, false, ClocksPerBaud) // start bit
    for (i <- 0 until 8) hold(dut, ((b >> i) & 1) == 1, ClocksPerBaud)
    hold(dut, true, (ClocksPerBaud)) // stop bit
  }

  behavior of "UartRx"

  it should "receive a single byte and pulse valid once" in {
    simulate(new UartRx(ClocksPerBaud)) { dut =>
      hold(dut, true, 4) // idle line high before start
      sendByte(dut, 0x55)
      // step until valid asserts, capture byte, confirm it deasserts next cycle
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.expect(0x55.U)
    }
  }
}
