package debug

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

// scratchpad thing not really supposed to work
class DebugTestSpec extends AnyFlatSpec with Matchers with ChiselSim {
  private val ClocksPerBaud = 6

  behavior of "DebugTest"

  /** Hold rx at `level` for `n` clock cycles. */
  private def hold(dut: DebugTest, cosim: UartTxSim, level: Boolean, n: Int): Unit = {
    dut.io.rx.poke(level.B)
    for (i <- 0 until n) {
      dut.clock.step(1)
      cosim.step(dut.io.tx.peek().litToBoolean)
    }
  }

  /** Drive one UART frame LSB-first: start(0), 8 data bits, stop(1). */
  private def sendByte(dut: DebugTest, cosim: UartTxSim, b: Int): Unit = {
    hold(dut, cosim, false, ClocksPerBaud) // start bit
    for (i <- 0 until 8) hold(dut, cosim, ((b >> i) & 1) == 1, ClocksPerBaud)
    hold(dut, cosim, true, (ClocksPerBaud)) // stop bit
  }

  it should "work" in {
    val cosim = new UartTxSim(ClocksPerBaud)

    simulate(new DebugTest(ClocksPerBaud)) { dut =>
      {
        // hold idle line for 5 cycles
        dut.io.rx.poke(true.B)
        for (i <- 0 until 5) {
          dut.clock.step(1)
          cosim.step(dut.io.tx.peek().litToBoolean)
        }

        sendByte(dut, cosim, 0x55)
        sendByte(dut, cosim, 0x55)
        sendByte(dut, cosim, 0x55)
        sendByte(dut, cosim, 0x55)
        sendByte(dut, cosim, 0x55)

        for (i <- 0 until 100) {
          dut.clock.step(1)
          cosim.step(dut.io.tx.peek().litToBoolean)
        }

        print(s"FOUND STRING: ${cosim.sb.toString()}")
      }
    }
  }
}
