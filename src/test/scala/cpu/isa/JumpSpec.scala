package cpu.isa

import org.scalatest.flatspec.AnyFlatSpec

/** Jumps: jal, jalr. */
class JumpSpec extends AnyFlatSpec with CpuTestBase {
  behavior of "jumps"

  // ----- jal ------------------------------------------------------------------

  it should "jal writes the return address (pc+4) and jumps" in {
    // jal at pc=0: link x1 = 4, jump to pc+8 (skips the first lw).
    run("""jal x1, 8
           lw x2, 0x50(x0)
           lw x2, 0x54(x0)
        """, data = Seq(0x50 -> BigInt("CAFE", 16), 0x54 -> BigInt("BEEF", 16))) { dut =>
      readReg(dut, 1) shouldBe BigInt(4)
      readReg(dut, 2) shouldBe BigInt("BEEF", 16)
    }
  }

  it should "jal with a negative offset, and jal x0 writes no link" in {
    // jal x0, 8 -> jump to pc+8 (the jal x2,-4) with no link.
    // jal x2, -4 -> link x2 = pc+4 = 12, jump back to the lw.
    run("""jal x0, 8
           lw x1, 0x50(x0)
           jal x2, -4
        """, data = Seq(0x50 -> BigInt("BEEF", 16))) { dut =>
      readReg(dut, 1) shouldBe BigInt("BEEF", 16)
      readReg(dut, 2) shouldBe BigInt(12)
      readReg(dut, 0) shouldBe BigInt(0) // jal x0 wrote no link
    }
  }

  // ----- jalr -----------------------------------------------------------------
  // Layout (a nop gap keeps x5 settled before jalr):
  //   pc0 : addi x5, x0, <setup>
  //   pc4 : addi x0, x0, 0      (nop)
  //   pc8 : jalr x1, x5, <imm>  link x1 = 12, target = (x5 + imm) & ~1
  //   pc12: addi x10, x0, 99    (poison, skipped when the jump lands at pc16)
  //   pc16: lw x10, 0x50(x0)    landing
  // All variants below compute target == 16.
  private def jalrTest(setup: Int, imm: Int): Unit = {
    val prog =
      s"""addi x5, x0, $setup
          addi x0, x0, 0
          jalr x1, x5, $imm
          addi x10, x0, 99
          lw x10, 0x50(x0)
       """
    run(prog, data = Seq(0x50 -> BigInt(0xabc))) { dut =>
      readReg(dut, 10) shouldBe BigInt(0xabc) // landed at pc16, poison skipped
      readReg(dut, 1) shouldBe BigInt(12) // link = pc(jalr) + 4
    }
  }

  it should "jalr jumps to rs1 + imm and links pc+4" in { jalrTest(16, 0) }
  it should "jalr clears the low bit of the target" in { jalrTest(17, 0) }
  it should "jalr adds a positive immediate" in { jalrTest(12, 4) }
  it should "jalr adds a negative immediate" in { jalrTest(24, -8) }
}
