package cpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ImmediateSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Immediate"

  it should "decode the correct negative immediate for beq inst" in {
    simulate(new Immediate) { dut =>
      dut.io.inst.poke("hFE000CE3".U)
      dut.io.imm_src.poke("b10".U)
      dut.clock.step()
      dut.io.imm.expect("hFFFFFFF8".U)
    }
  }
}
