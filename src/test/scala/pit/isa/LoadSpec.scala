package pit.isa

import org.scalatest.flatspec.AnyFlatSpec

/** Loads: lw, lh, lhu, lb, lbu. RISC-V memory is little-endian, so the word 0xAABBCCDD stored at
  * 0x50 lays out as byte[0x50]=0xDD .. byte[0x53]=0xAA.
  */
class LoadSpec extends AnyFlatSpec with CpuTestBase {
  behavior of "loads"

  // ----- lw -------------------------------------------------------------------

  it should "lw loads a word" in {
    run("lw x1, 0x50(x0)", data = Seq(0x50 -> BigInt("DEADBEEF", 16))) { dut =>
      readReg(dut, 1) shouldBe BigInt("DEADBEEF", 16)
    }
  }

  it should "lw with a non-zero base register and offset" in {
    run(
      """addi x2, x0, 0x50
           lw x1, 0x4(x2)
        """,
      data = Seq(0x54 -> BigInt("CAFEBABE", 16))
    ) { dut =>
      readReg(dut, 1) shouldBe BigInt("CAFEBABE", 16)
    }
  }

  it should "lw with a negative offset" in {
    run(
      """addi x2, x0, 0x54
           lw x1, -4(x2)
        """,
      data = Seq(0x50 -> BigInt("12345678", 16))
    ) { dut =>
      readReg(dut, 1) shouldBe BigInt("12345678", 16)
    }
  }

  it should "lw into x0 is discarded" in {
    run("lw x0, 0x50(x0)", data = Seq(0x50 -> BigInt("DEADBEEF", 16))) { dut =>
      readReg(dut, 0) shouldBe BigInt(0)
    }
  }

  // ----- lh / lhu -------------------------------------------------------------

  it should "lh sign-extends a halfword with the high bit set" in {
    // low half of 0xAABBCCDD is 0xCCDD (bit 15 set)
    run("lh x1, 0x50(x0)", data = Seq(0x50 -> BigInt("AABBCCDD", 16))) { dut =>
      readReg(dut, 1) shouldBe BigInt("FFFFCCDD", 16)
    }
  }

  it should "lh loads the upper halfword (offset 2) and sign-extends" in {
    run("lh x1, 0x52(x0)", data = Seq(0x50 -> BigInt("AABBCCDD", 16))) { dut =>
      readReg(dut, 1) shouldBe BigInt("FFFFAABB", 16)
    }
  }

  it should "lh on a positive halfword does not sign-extend" in {
    run("lh x1, 0x50(x0)", data = Seq(0x50 -> BigInt("00007FFF", 16))) { dut =>
      readReg(dut, 1) shouldBe BigInt("00007FFF", 16)
    }
  }

  it should "lhu zero-extends a halfword" in {
    run("lhu x1, 0x50(x0)", data = Seq(0x50 -> BigInt("AABBCCDD", 16))) { dut =>
      readReg(dut, 1) shouldBe BigInt("0000CCDD", 16)
    }
  }

  it should "lhu loads the upper halfword (offset 2)" in {
    run("lhu x1, 0x52(x0)", data = Seq(0x50 -> BigInt("AABBCCDD", 16))) { dut =>
      readReg(dut, 1) shouldBe BigInt("0000AABB", 16)
    }
  }

  // ----- lb / lbu -------------------------------------------------------------

  it should "lb sign-extends byte 0 (0xDD)" in {
    run("lb x1, 0x50(x0)", data = Seq(0x50 -> BigInt("AABBCCDD", 16))) { dut =>
      readReg(dut, 1) shouldBe BigInt("FFFFFFDD", 16)
    }
  }

  it should "lb sign-extends byte 1 (0xCC)" in {
    run("lb x1, 0x51(x0)", data = Seq(0x50 -> BigInt("AABBCCDD", 16))) { dut =>
      readReg(dut, 1) shouldBe BigInt("FFFFFFCC", 16)
    }
  }

  it should "lb sign-extends byte 2 (0xBB)" in {
    run("lb x1, 0x52(x0)", data = Seq(0x50 -> BigInt("AABBCCDD", 16))) { dut =>
      readReg(dut, 1) shouldBe BigInt("FFFFFFBB", 16)
    }
  }

  it should "lb sign-extends byte 3 (0xAA)" in {
    run("lb x1, 0x53(x0)", data = Seq(0x50 -> BigInt("AABBCCDD", 16))) { dut =>
      readReg(dut, 1) shouldBe BigInt("FFFFFFAA", 16)
    }
  }

  it should "lb on a positive byte does not sign-extend" in {
    run("lb x1, 0x50(x0)", data = Seq(0x50 -> BigInt("00000044", 16))) { dut =>
      readReg(dut, 1) shouldBe BigInt("44", 16)
    }
  }

  it should "lbu zero-extends a byte" in {
    run("lbu x1, 0x50(x0)", data = Seq(0x50 -> BigInt("AABBCCDD", 16))) { dut =>
      readReg(dut, 1) shouldBe BigInt("000000DD", 16)
    }
  }

  it should "lbu zero-extends byte 2" in {
    run("lbu x1, 0x52(x0)", data = Seq(0x50 -> BigInt("AABBCCDD", 16))) { dut =>
      readReg(dut, 1) shouldBe BigInt("000000BB", 16)
    }
  }
}
