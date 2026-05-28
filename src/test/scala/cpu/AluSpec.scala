package cpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AluSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Alu"

  it should "add two values (ctrl=000)" in {
    simulate(new Alu) { dut =>
      dut.io.i_src_a.poke(5.U)
      dut.io.i_src_b.poke(7.U)
      dut.io.i_control.poke(AluOp.add)
      dut.clock.step()
      dut.io.o_result.expect(12.U)
      dut.io.o_zero.expect(false.B)
    }
  }

  it should "subtract two values (ctrl=001)" in {
    simulate(new Alu) { dut =>
      dut.io.i_src_a.poke(10.U)
      dut.io.i_src_b.poke(3.U)
      dut.io.i_control.poke(AluOp.sub)
      dut.clock.step()
      dut.io.o_result.expect(7.U)
      dut.io.o_zero.expect(false.B)
    }
  }

  it should "raise the zero flag when subtraction yields 0" in {
    simulate(new Alu) { dut =>
      dut.io.i_src_a.poke(5.U)
      dut.io.i_src_b.poke(5.U)
      dut.io.i_control.poke(AluOp.sub)
      dut.clock.step()
      dut.io.o_result.expect(0.U)
      dut.io.o_zero.expect(true.B)
    }
  }

  it should "bitwise-XOR two values (ctrl=010)" in {
    simulate(new Alu) { dut =>
      dut.io.i_src_a.poke(0xff0.U)
      dut.io.i_src_b.poke(0x0f0.U)
      dut.io.i_control.poke(AluOp.xor)
      dut.clock.step()
      dut.io.o_result.expect(0xf00.U)
    }
  }

  it should "bitwise-AND two values (ctrl=100)" in {
    simulate(new Alu) { dut =>
      dut.io.i_src_a.poke(0xff.U)
      dut.io.i_src_b.poke(0x0f.U)
      dut.io.i_control.poke(AluOp.and)
      dut.clock.step()
      dut.io.o_result.expect(0x0f.U)
    }
  }

  it should "bitwise-OR two values (ctrl=011)" in {
    simulate(new Alu) { dut =>
      dut.io.i_src_a.poke(0xf0.U)
      dut.io.i_src_b.poke(0x0f.U)
      dut.io.i_control.poke(AluOp.or)
      dut.clock.step()
      dut.io.o_result.expect(0xff.U)
    }
  }

  it should "set-less-than: a<b yields 1 (ctrl=101)" in {
    simulate(new Alu) { dut =>
      dut.io.i_src_a.poke(3.U)
      dut.io.i_src_b.poke(5.U)
      dut.io.i_control.poke(AluOp.slt)
      dut.clock.step()
      dut.io.o_result.expect(1.U)
      dut.io.o_zero.expect(false.B)
    }
  }

  it should "set-less-than: a>=b yields 0 (ctrl=101)" in {
    simulate(new Alu) { dut =>
      dut.io.i_src_a.poke(5.U)
      dut.io.i_src_b.poke(3.U)
      dut.io.i_control.poke(AluOp.slt)
      dut.clock.step()
      dut.io.o_result.expect(0.U)
      dut.io.o_zero.expect(true.B)
    }
  }
}
