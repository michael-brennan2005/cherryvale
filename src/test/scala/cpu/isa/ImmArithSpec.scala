package cpu.isa

import org.scalatest.flatspec.AnyFlatSpec

/** I-type register-immediate ALU: addi, slti, sltiu, xori, ori, andi, slli, srli, srai */
class ImmArithSpec extends AnyFlatSpec with CpuTestBase {
  behavior of "I-type ALU"

  // ----- addi -----------------------------------------------------------------

  it should "addi with a positive immediate" in {
    run("""lw x1, 0x50(x0)
           addi x2, x1, 100
        """, data = Seq(0x50 -> BigInt(42))) { dut =>
      readReg(dut, 2) shouldBe BigInt(142)
    }
  }

  it should "addi with a negative immediate (sign-extended)" in {
    run("""lw x1, 0x50(x0)
           addi x2, x1, -10
        """, data = Seq(0x50 -> BigInt(100))) { dut =>
      readReg(dut, 2) shouldBe BigInt(90)
    }
  }

  it should "addi wraps around modulo 2^32" in {
    run("""addi x1, x0, -1
           addi x2, x1, 1
        """) { dut =>
      // x1 = 0xFFFFFFFF, +1 wraps to 0
      readReg(dut, 2) shouldBe BigInt(0)
    }
  }

  it should "addi with immediate 0 acts as a move" in {
    run("""lw x1, 0x50(x0)
           addi x2, x1, 0
        """, data = Seq(0x50 -> BigInt("DEADBEEF", 16))) { dut =>
      readReg(dut, 2) shouldBe BigInt("DEADBEEF", 16)
    }
  }

  it should "addi into x0 is discarded" in {
    run("addi x0, x0, 123") { dut =>
      readReg(dut, 0) shouldBe BigInt(0)
    }
  }

  // ----- slti / sltiu ---------------------------------------------------------

  it should "slti is a signed comparison (true case, negative < positive)" in {
    run("""addi x1, x0, -5
           slti x2, x1, 1
        """) { dut =>
      readReg(dut, 2) shouldBe BigInt(1)
    }
  }

  it should "slti is a signed comparison (false case)" in {
    run("""addi x1, x0, 5
           slti x2, x1, 1
        """) { dut =>
      readReg(dut, 2) shouldBe BigInt(0)
    }
  }

  it should "sltiu treats operands as unsigned (-1 as 0xFFFFFFFF is not < 1)" in {
    run("""addi x1, x0, -1
           sltiu x2, x1, 1
        """) { dut =>
      // 0xFFFFFFFF <u 1 ? no
      readReg(dut, 2) shouldBe BigInt(0)
    }
  }

  it should "sltiu rd, rs, 1 is the seqz idiom (1 iff rs == 0)" in {
    run("""addi x1, x0, 0
           sltiu x2, x1, 1
        """) { dut =>
      readReg(dut, 2) shouldBe BigInt(1)
    }
  }

  // ----- xori / ori / andi ----------------------------------------------------

  it should "xori with -1 is a bitwise NOT" in {
    run("""lw x1, 0x50(x0)
           xori x2, x1, -1
        """, data = Seq(0x50 -> BigInt("0F0F0F0F", 16))) { dut =>
      readReg(dut, 2) shouldBe BigInt("F0F0F0F0", 16)
    }
  }

  it should "ori sets bits (immediate sign-extended)" in {
    run("""lw x1, 0x50(x0)
           ori x2, x1, 0xF
        """, data = Seq(0x50 -> BigInt("F0F0F0F0", 16))) { dut =>
      readReg(dut, 2) shouldBe BigInt("F0F0F0FF", 16)
    }
  }

  it should "andi masks bits" in {
    run("""lw x1, 0x50(x0)
           andi x2, x1, 0xFF
        """, data = Seq(0x50 -> BigInt("DEADBEEF", 16))) { dut =>
      readReg(dut, 2) shouldBe BigInt("EF", 16)
    }
  }

  // ----- slli / srli / srai ---------------------------------------------------

  it should "slli shifts left by shamt" in {
    run("""addi x1, x0, 1
           slli x2, x1, 4
        """) { dut =>
      readReg(dut, 2) shouldBe BigInt(0x10)
    }
  }

  it should "slli by 31" in {
    run("""addi x1, x0, 1
           slli x2, x1, 31
        """) { dut =>
      readReg(dut, 2) shouldBe BigInt("80000000", 16)
    }
  }

  it should "slli by 0 is identity" in {
    run("""lw x1, 0x50(x0)
           slli x2, x1, 0
        """, data = Seq(0x50 -> BigInt("DEADBEEF", 16))) { dut =>
      readReg(dut, 2) shouldBe BigInt("DEADBEEF", 16)
    }
  }

  it should "srli shifts right with zero fill" in {
    run("""lw x1, 0x50(x0)
           srli x2, x1, 4
        """, data = Seq(0x50 -> BigInt("80000000", 16))) { dut =>
      readReg(dut, 2) shouldBe BigInt("08000000", 16)
    }
  }

  it should "srai shifts right with sign fill on a negative value" in {
    run("""lw x1, 0x50(x0)
           srai x2, x1, 4
        """, data = Seq(0x50 -> BigInt("80000000", 16))) { dut =>
      readReg(dut, 2) shouldBe BigInt("F8000000", 16)
    }
  }

  it should "srai on a positive value matches srli" in {
    run("""lw x1, 0x50(x0)
           srai x2, x1, 4
        """, data = Seq(0x50 -> BigInt("70000000", 16))) { dut =>
      readReg(dut, 2) shouldBe BigInt("07000000", 16)
    }
  }
}
