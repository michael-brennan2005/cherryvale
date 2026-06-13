package debug

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class UartRxSpec extends AnyFlatSpec with Matchers with ChiselSim {
  private val ClocksPerBaud = 6

  behavior of "UartRx"

  it should "receive a single byte and pulse valid once" in {
    simulate(new UartRx(ClocksPerBaud)) { dut =>
      val uartTx = new UartTxDriver(ClocksPerBaud, dut.io.rx)

      uartTx.idleLine(4)
      uartTx.encodeByte(0x55)

      while (!dut.io.out.valid.peekBoolean()) {
        uartTx.tick()
        dut.clock.step(1)
      }

      // step until valid asserts, capture byte, confirm it deasserts next cycle
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.expect(0x55.U)
    }
  }
}
