package cpu.isa

import org.scalatest.flatspec.AnyFlatSpec

/** R-type register-register ALU: add, sub, sll, slt, sltu, xor, srl, sra, or, and */
class RegArithSpec extends AnyFlatSpec with CpuTestBase {
  behavior of "R-type ALU"

  // ----- add / sub ------------------------------------------------------------

  it should "add two registers" in {
    run("""lw x1, 0x50(x0)
           lw x2, 0x54(x0)
           add x3, x1, x2
        """, data = Seq(0x50 -> BigInt(40), 0x54 -> BigInt(2))) { dut =>
      readReg(dut, 3) shouldBe BigInt(42)
    }
  }

  it should "add wraps around modulo 2^32" in {
    run("""lw x1, 0x50(x0)
           lw x2, 0x54(x0)
           add x3, x1, x2
        """, data = Seq(0x50 -> BigInt("FFFFFFFF", 16), 0x54 -> BigInt(1))) { dut =>
      readReg(dut, 3) shouldBe BigInt(0)
    }
  }

  it should "sub two registers" in {
    run("""lw x1, 0x50(x0)
           lw x2, 0x54(x0)
           sub x3, x1, x2
        """, data = Seq(0x50 -> BigInt(100), 0x54 -> BigInt(58))) { dut =>
      readReg(dut, 3) shouldBe BigInt(42)
    }
  }

  it should "sub producing a negative result" in {
    run("""lw x1, 0x50(x0)
           lw x2, 0x54(x0)
           sub x3, x1, x2
        """, data = Seq(0x50 -> BigInt(5), 0x54 -> BigInt(10))) { dut =>
      readReg(dut, 3) shouldBe u32(-5)
    }
  }

  // ----- shifts: amount is low 5 bits of rs2 ----------------------------------

  it should "sll shifts left by rs2[4:0]" in {
    run("""addi x1, x0, 1
           addi x2, x0, 4
           sll x3, x1, x2
        """) { dut =>
      readReg(dut, 3) shouldBe BigInt(0x10)
    }
  }

  it should "sll uses only the low 5 bits of rs2 (shift by 32 == shift by 0)" in {
    run("""addi x1, x0, 1
           addi x2, x0, 32
           sll x3, x1, x2
        """) { dut =>
      readReg(dut, 3) shouldBe BigInt(1)
    }
  }

  it should "srl shifts right (zero fill) by rs2[4:0]" in {
    run("""lw x1, 0x50(x0)
           addi x2, x0, 4
           srl x3, x1, x2
        """, data = Seq(0x50 -> BigInt("80000000", 16))) { dut =>
      readReg(dut, 3) shouldBe BigInt("08000000", 16)
    }
  }

  it should "sra shifts right (sign fill) by rs2[4:0]" in {
    run("""lw x1, 0x50(x0)
           addi x2, x0, 4
           sra x3, x1, x2
        """, data = Seq(0x50 -> BigInt("80000000", 16))) { dut =>
      readReg(dut, 3) shouldBe BigInt("F8000000", 16)
    }
  }

  it should "sra by rs2 == 33 shifts by 1 (low 5 bits)" in {
    run("""lw x1, 0x50(x0)
           addi x2, x0, 33
           sra x3, x1, x2
        """, data = Seq(0x50 -> BigInt("80000000", 16))) { dut =>
      readReg(dut, 3) shouldBe BigInt("C0000000", 16)
    }
  }

  // ----- slt / sltu -----------------------------------------------------------

  it should "slt is signed (-1 < 1 is true)" in {
    run("""addi x1, x0, -1
           addi x2, x0, 1
           slt x3, x1, x2
        """) { dut =>
      readReg(dut, 3) shouldBe BigInt(1)
    }
  }

  it should "sltu is unsigned (0xFFFFFFFF < 1 is false)" in {
    run("""addi x1, x0, -1
           addi x2, x0, 1
           sltu x3, x1, x2
        """) { dut =>
      readReg(dut, 3) shouldBe BigInt(0)
    }
  }

  it should "slt false case (equal operands)" in {
    run("""addi x1, x0, 7
           addi x2, x0, 7
           slt x3, x1, x2
        """) { dut =>
      readReg(dut, 3) shouldBe BigInt(0)
    }
  }

  // ----- xor / or / and -------------------------------------------------------

  it should "xor two registers" in {
    run("""lw x1, 0x50(x0)
           lw x2, 0x54(x0)
           xor x3, x1, x2
        """, data = Seq(0x50 -> BigInt("F0F0F0F0", 16), 0x54 -> BigInt("FFFF0000", 16))) {
      dut => readReg(dut, 3) shouldBe BigInt("0F0FF0F0", 16)
    }
  }

  it should "or two registers" in {
    run("""lw x1, 0x50(x0)
           lw x2, 0x54(x0)
           or x3, x1, x2
        """, data = Seq(0x50 -> BigInt("F0F0F0F0", 16), 0x54 -> BigInt("0F0F0F0F", 16))) {
      dut => readReg(dut, 3) shouldBe BigInt("FFFFFFFF", 16)
    }
  }

  it should "and two registers" in {
    run("""lw x1, 0x50(x0)
           lw x2, 0x54(x0)
           and x3, x1, x2
        """, data = Seq(0x50 -> BigInt("FF00FF00", 16), 0x54 -> BigInt("0FF00FF0", 16))) {
      dut => readReg(dut, 3) shouldBe BigInt("0F000F00", 16)
    }
  }
}
