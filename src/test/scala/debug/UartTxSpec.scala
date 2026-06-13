package debug

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class UartTxSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "UartTx"

  it should "transmit a single byte as an 8N1 frame" in {
    simulate(new UartTx(5)) { dut =>
      val uartRx = new UartRxMonitor(5, dut.io.tx)

      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.poke('H'.U)
      uartRx.tick()
      dut.clock.step(1)

      while (!dut.io.in.ready.peek().litToBoolean) {
        uartRx.tick()
        dut.clock.step(1)
      }

      assert(uartRx.asString() == "H")
    }
  }

  it should "transmit multiple bytes as 8N1 frames" in {
    val string = "Hello, world!"

    simulate(new UartTx(5)) { dut =>
      val uartRx = new UartRxMonitor(5, dut.io.tx)

      for (char <- string) {
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.poke(char.toInt)
        uartRx.tick()
        dut.clock.step(1)

        dut.io.in.valid.poke(false.B)
        while (!dut.io.in.ready.peek().litToBoolean) {
          uartRx.tick()
          dut.clock.step(1)
        }
      }

      assert(uartRx.asString() == string)
    }
  }
}
