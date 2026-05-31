package cpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.carlosedp.riscvassembler.RISCVAssembler
import cpu.Utils

class CpuSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Cpu"

  private def readMem(dut: Cpu, addr: Int): BigInt = {
    dut.io.mem_debug.addr.poke(addr.U)
    dut.io.mem_debug.data.peek().litValue
  }

  private def readReg(dut: Cpu, rIdx: Int): BigInt = {
    dut.io.reg_debug_addr.poke(rIdx.U)
    dut.io.reg_debug_data.peek().litValue
  }

  it should "execute lw" in {
    val memInit = Utils.buildMemInit(
      program = "lw x1, 0x40(x0)",
      data = Seq(0x40 -> BigInt("DEADBEEF", 16))
    )
    simulate(new Cpu(Some(memInit))) { dut =>
      dut.clock.step(5)
      readReg(dut, 1) shouldBe BigInt("DEADBEEF", 16)
    }
  }

  it should "execute sw" in {
    val memInit = Utils.buildMemInit(
      program = "sw x0, 0x40(x0)",
      data =
        Seq(0x40 -> BigInt("FFFFFFFF", 16)) // sentinel: proves write happened
    )
    simulate(new Cpu(Some(memInit))) { dut =>
      dut.clock.step(5)
      readMem(dut, 0x40) shouldBe BigInt(0)
    }
  }

  it should "execute xor" in {
    val memInit = Utils.buildMemInit(
      program = """lw x1, 0x40(x0)
        lw x2, 0x44(x0)
        xor x3, x1, x2
      """,
      data = Seq(
        0x40 -> BigInt("F0F0F0F0", 16),
        0x44 -> BigInt("FFFF0000", 16)
      )
    )
    simulate(new Cpu(Some(memInit))) { dut =>
      dut.clock.step(7)
      readReg(dut, 3) shouldBe BigInt("0F0FF0F0", 16)
    }
  }

  it should "execute or" in {
    val memInit = Utils.buildMemInit(
      program = """lw x1, 0x40(x0)
           lw x2, 0x44(x0)
           or x3, x1, x2
        """.stripMargin,
      data = Seq(
        0x40 -> BigInt("F0F0F0F0", 16),
        0x44 -> BigInt("0F0F0F0F", 16)
      )
    )
    simulate(new Cpu(Some(memInit))) { dut =>
      dut.clock.step(7)
      readReg(dut, 3) shouldBe BigInt("FFFFFFFF", 16)
    }
  }

  it should "execute beq (taken)" in {
    val memInit = Utils.buildMemInit(
      program = """lw x1, 0x40(x0)
      beq x0, x0, 8,
      lw x1, 0x44(x0)
      sw x2, 0x4c(x0)
      """.stripMargin,
      data = Seq(
        0x40 -> BigInt("CAFE", 16),
        0x44 -> BigInt("BEEF", 16)
      )
    )
    simulate(new Cpu(Some(memInit))) { dut =>
      dut.clock.step(8)
      readReg(dut, 1) shouldBe BigInt("CAFE", 16)
    }
  }

  it should "execute beq (taken, negative offset)" in {
    val memInit = Utils.buildMemInit(
      program = """lw x1, 0x40(x0)
        beq x0, x0, 16
        addi x1, x0, 4
        addi x1, x0, 4
        lw x1, 0x44(x0)
        beq x0, x0, -4
      """.stripMargin,
      data = Seq(
        0x40 -> BigInt("CAFE", 16),
        0x44 -> BigInt("BEEF", 16)
      )
    )
    simulate(new Cpu(Some(memInit))) { dut =>
      dut.clock.step(11)
      readReg(dut, 1) shouldBe BigInt("BEEF", 16)
    }
  }

  it should "execute beq (not taken)" in {
    val memInit = Utils.buildMemInit(
      program = """lw x1, 0x40(x0)
        lw x2, 0x44(x0)
        beq x1, x2, 8
        lw x1, 0x48(x0)
      """.stripMargin,
      data = Seq(
        0x40 -> BigInt("CAFE", 16),
        0x44 -> BigInt("BEEF", 16),
        0x48 -> BigInt("1234", 16)
      )
    )
    simulate(new Cpu(Some(memInit))) { dut =>
      dut.clock.step(9)
      readReg(dut, 1) shouldBe BigInt("1234", 16)
    }
  }

  it should "execute addi" in {
    val memInit = Utils.buildMemInit(
      program = """lw x1, 0x40(x0)
        addi x2, x1, 100
      """.stripMargin,
      data = Seq(0x40 -> BigInt(42))
    )
    simulate(new Cpu(Some(memInit))) { dut =>
      dut.clock.step(7)
      readReg(dut, 2) shouldBe BigInt(142)
    }
  }

  it should "execute addi (negative immediate)" in {
    val memInit = Utils.buildMemInit(
      program = """lw x1, 0x40(x0)
        addi x2, x1, -10
      """.stripMargin,
      data = Seq(0x40 -> BigInt(100))
    )
    simulate(new Cpu(Some(memInit))) { dut =>
      dut.clock.step(7)
      readReg(dut, 2) shouldBe BigInt(90)
    }
  }

  it should "execute jal" in {
    val memInit = Utils.buildMemInit(
      program = """jal x1, 8
        lw x2, 0x40(x0)
        lw x2, 0x44(x0)
      """.stripMargin,
      data = Seq(
        0x40 -> BigInt("CAFE", 16),
        0x44 -> BigInt("BEEF", 16)
      )
    )
    simulate(new Cpu(Some(memInit))) { dut =>
      dut.clock.step(8)
      readReg(dut, 1) shouldBe BigInt(4)
      readReg(dut, 2) shouldBe BigInt("BEEF", 16)
    }
  }

  it should "execute jal (negative offset)" in {
    val memInit = Utils.buildMemInit(
      program = """jal x0, 8
        lw x1, 0x40(x0)
        jal x2, -4
      """.stripMargin,
      data = Seq(
        0x40 -> BigInt("BEEF", 16)
      )
    )
    simulate(new Cpu(Some(memInit))) { dut =>
      dut.clock.step(10)
      readReg(dut, 1) shouldBe BigInt("BEEF", 16)
      readReg(dut, 2) shouldBe BigInt(12)
    }
  }

  it should "execute lui" in {
    val memInit = Utils.buildMemInit(
      program = "lui x1, 0xABCDE",
      data = Seq.empty[(Int, BigInt)]
    )
    simulate(new Cpu(Some(memInit))) { dut =>
      dut.clock.step(4)
      readReg(dut, 1) shouldBe BigInt("ABCDE000", 16)
    }
  }
}
