package debug

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

// scratchpad thing not really supposed to work
class DebugTestSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "DebugTest"

  it should "work" in {
    val cosim = new UartTxSim(5)

    simulate(new DebugTest(5)) { dut =>
      {
        for (i <- 0 until 1000) {
          dut.clock.step(1)
          cosim.step(dut.io.tx.peek().litToBoolean)
        }
        print(s"FOUND STRING: ${cosim.sb.toString()}")
      }
    }
  }
}
