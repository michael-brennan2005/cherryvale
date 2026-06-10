package debug

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class UartTxSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "UartTx"

  it should "transmit a single byte as an 8N1 frame" in {
    val cosim = new UartTxSim(5)

    simulate(new UartTx(5)) { dut =>
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.poke('H'.U)

      dut.clock.step(1)

      while (!dut.io.in.ready.peek().litToBoolean) {
        dut.clock.step(1)
        cosim.step(dut.io.tx.peek().litToBoolean)
      }

      assert(cosim.sb.toString() == "H")
    }
  }

  it should "transmit multiple bytes as 8N1 frames" in {
    val cosim = new UartTxSim(5)

    val string = "Hello, world!"

    simulate(new UartTx(5)) { dut =>
      for (char <- string) {
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.poke(char.toInt)

        dut.clock.step(1)

        dut.io.in.valid.poke(false.B)
        while (!dut.io.in.ready.peek().litToBoolean) {
          dut.clock.step(1)
          cosim.step(dut.io.tx.peek().litToBoolean)
        }
      }

      assert(cosim.sb.toString() == string)
    }
  }
}
