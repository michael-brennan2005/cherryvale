package cpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ProgramCounterSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "ProgramCounter"

  it should "output 0 on the first cycle after reset" in {
    simulate(new ProgramCounter) { dut =>
      dut.io.i_pc_next.poke(0xdeadbeefL.U)
      dut.io.o_pc.expect(0.U)
    }
  }

  it should "register i_pc_next on the next cycle" in {
    simulate(new ProgramCounter) { dut =>
      dut.io.i_pc_next.poke(0x00000004.U)
      dut.clock.step()
      dut.io.o_pc.expect(0x00000004.U)
    }
  }

  it should "lag i_pc_next by exactly one cycle across a sequence" in {
    simulate(new ProgramCounter) { dut =>
      val sequence = Seq(0x4, 0x8, 0xc, 0x10, 0x14)

      dut.io.i_pc_next.poke(sequence.head.U)
      dut.clock.step()
      dut.io.o_pc.expect(sequence.head.U)

      sequence.sliding(2).foreach { case Seq(_, next) =>
        dut.io.i_pc_next.poke(next.U)
        dut.clock.step()
        dut.io.o_pc.expect(next.U)
      }
    }
  }
}
