package cpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.carlosedp.riscvassembler.RISCVAssembler

class CpuSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Cpu"

  // ---- Test fixture helpers --------------------------------------------------
  private def writeMem(dut: Cpu, addr: Int, value: BigInt): Unit = {
    dut.io.mem_debug.addr.poke(addr.U)
    dut.io.mem_debug.w_data.poke(value.U)
    dut.io.mem_debug.w_en.poke(true.B)
    dut.clock.step()
    dut.io.mem_debug.w_en.poke(false.B)
  }

  private def readMem(dut: Cpu, addr: Int): BigInt = {
    dut.io.mem_debug.w_en.poke(false.B)
    dut.io.mem_debug.addr.poke(addr.U)
    dut.io.mem_debug.data.peek().litValue
  }

  private def readReg(dut: Cpu, rIdx: Int): BigInt = {
    dut.io.reg_debug_addr.poke(rIdx.U)
    dut.io.reg_debug_data.peek().litValue
  }

  /** Hold reset, write program words at addrs 0,4,8,…, write any additional
    * data words via `data` (addr → value), then release reset and step `cycles`
    * times, then re-assert reset to halt for inspection.
    */
  private def runProgram(
      dut: Cpu,
      program: String,
      data: Seq[(Int, BigInt)],
      cycles: Int
  ): Unit = {
    dut.reset.poke(true.B)
    val insts = RISCVAssembler
      .fromString(program)
      .split('\n')
      .filter(_.nonEmpty)
      .map(line => BigInt(line, 16))

    for ((inst, idx) <- insts.zipWithIndex) writeMem(dut, idx * 4, inst)
    for ((addr, value) <- data) writeMem(dut, addr, value)
    dut.io.mem_debug.w_en.poke(false.B)
    dut.reset.poke(false.B)
    dut.clock.step(cycles)
    dut.reset.poke(true.B)
  }

  // ---- LW ---------------------------------------------------------------------

  it should "load a word into a register (lw)" in {
    simulate(new Cpu) { dut =>
      runProgram(
        dut,
        program = "lw x1, 0x80(x0)",
        data = Seq(0x80 -> BigInt("DEADBEEF", 16)),
        cycles = 1
      )
      readReg(dut, 1) shouldBe BigInt("DEADBEEF", 16)
    }
  }

  // ---- SW ---------------------------------------------------------------------

  it should "store a register value to memory (sw)" in {
    simulate(new Cpu) { dut =>
      runProgram(
        dut,
        program = "sw r0, 0x80(x0)",
        data = Seq(
          0x80 -> BigInt("FFFFFFFF", 16)
        ), // sentinel: proves write happened
        cycles = 1
      )
      readMem(dut, 0x80) shouldBe BigInt(0)
    }
  }

  // ---- OR ---------------------------------------------------------------------

  it should "compute bitwise OR of two registers (or)" in {
    simulate(new Cpu) { dut =>
      runProgram(
        dut,
        program = """lw x1, 0x80(x0)
             lw x2, 0x84(x0)
             or x3, x1, x2
          """.stripMargin,
        data = Seq(
          0x80 -> BigInt("F0F0F0F0", 16),
          0x84 -> BigInt("0F0F0F0F", 16)
        ),
        cycles = 3
      )
      readReg(dut, 3) shouldBe BigInt("FFFFFFFF", 16)
    }
  }

  // ---- BEQ (taken) ------------------------------------------------------------

  it should "branch when operands are equal (beq taken)" in {
    simulate(new Cpu) { dut =>
      runProgram(
        dut,
        program = """lw x1, 0x80(x0)
        beq x0, x0, 8,
        lw x1, 0x84(x0)
        sw x2, 0x8c(x0)
        """.stripMargin,
        data = Seq(
          0x80 -> BigInt("CAFE", 16),
          0x84 -> BigInt("BEEF", 16)
        ),
        cycles = 3
      )
      readReg(dut, 1) shouldBe BigInt("CAFE", 16)
    }
  }

  // ---- BEQ (not taken) --------------------------------------------------------

  it should "fall through when operands differ (beq not taken)" in {
    simulate(new Cpu) { dut =>
      runProgram(
        dut,
        program = """lw x1, 0x80(x0)
          lw x2, 0x84(x0)
          beq x1, x2, 8
          lw x1, 0x88(x0)
        """.stripMargin,
        data = Seq(
          0x80 -> BigInt("CAFE", 16),
          0x84 -> BigInt("BEEF", 16),
          0x88 -> BigInt("1234", 16)
        ),
        cycles = 4
      )
      readReg(dut, 1) shouldBe BigInt("1234", 16)
    }
  }
}
