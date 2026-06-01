package cpu.isa

import org.scalatest.flatspec.AnyFlatSpec

/** lui, auipc */
class UpperImmSpec extends AnyFlatSpec with CpuTestBase {
  behavior of "lui/auipc"

  it should "lui loads the immediate into bits [31:12] and zeroes [11:0]" in {
    run("lui x1, 0xABCDE") { dut =>
      readReg(dut, 1) shouldBe BigInt("ABCDE000", 16)
    }
  }

  it should "lui with the sign bit set fills the top bit (no sign extension downward)" in {
    run("lui x1, 0x80000") { dut =>
      readReg(dut, 1) shouldBe BigInt("80000000", 16)
    }
  }

  it should "lui with all immediate bits set" in {
    run("lui x1, 0xFFFFF") { dut =>
      readReg(dut, 1) shouldBe BigInt("FFFFF000", 16)
    }
  }

  it should "lui into x0 is discarded" in {
    run("lui x0, 0x12345") { dut =>
      readReg(dut, 0) shouldBe BigInt(0)
    }
  }

  it should "auipc adds (imm << 12) to its own PC (PC == 0 here)" in {
    run("auipc x1, 0x10") { dut =>
      // pc = 0, result = 0 + (0x10 << 12) = 0x10000
      readReg(dut, 1) shouldBe BigInt("10000", 16)
    }
  }

  it should "auipc is PC-relative, not relative to 0" in {
    // auipc is the 4th instruction, so its PC = 0xC.
    run("""addi x0, x0, 0
           addi x0, x0, 0
           addi x0, x0, 0
           auipc x1, 0x10
        """) { dut =>
      // result = 0xC + (0x10 << 12) = 0x1000C
      readReg(dut, 1) shouldBe BigInt("1000C", 16)
    }
  }
}
