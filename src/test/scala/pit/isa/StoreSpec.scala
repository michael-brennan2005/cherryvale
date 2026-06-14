package pit.isa

import org.scalatest.flatspec.AnyFlatSpec

/** Stores: sw, sh, sb. Sub-word stores must preserve the surrounding bytes of the target word
  * (read-modify-write semantics). Little-endian byte layout.
  */
class StoreSpec extends AnyFlatSpec with CpuTestBase {
  behavior of "stores"

  // ----- sw -------------------------------------------------------------------

  it should "sw stores a full word" in {
    run(
      """lw x1, 0x54(x0)
           sw x1, 0x50(x0)
        """,
      data = Seq(0x54 -> BigInt("DEADBEEF", 16))
    ) { dut =>
      readMem(dut, 0x50) shouldBe BigInt("DEADBEEF", 16)
    }
  }

  it should "sw of x0 overwrites a sentinel word with 0" in {
    run("sw x0, 0x50(x0)", data = Seq(0x50 -> BigInt("FFFFFFFF", 16))) { dut =>
      readMem(dut, 0x50) shouldBe BigInt(0)
    }
  }

  it should "sw with a base register and offset" in {
    run(
      """addi x2, x0, 0x50
           lw x1, 0x54(x0)
           sw x1, 0x8(x2)
        """,
      data = Seq(0x54 -> BigInt("CAFEBABE", 16))
    ) { dut =>
      readMem(dut, 0x58) shouldBe BigInt("CAFEBABE", 16)
    }
  }

  // ----- sh -------------------------------------------------------------------

  it should "sh stores the low halfword, preserving the high half" in {
    run(
      """lw x1, 0x54(x0)
           sh x1, 0x50(x0)
        """,
      data = Seq(0x50 -> BigInt("FFFFFFFF", 16), 0x54 -> BigInt("00001234", 16))
    ) { dut =>
      readMem(dut, 0x50) shouldBe BigInt("FFFF1234", 16)
    }
  }

  it should "sh stores into the high halfword (offset 2), preserving the low half" in {
    run(
      """lw x1, 0x54(x0)
           sh x1, 0x52(x0)
        """,
      data = Seq(0x50 -> BigInt("FFFFFFFF", 16), 0x54 -> BigInt("00001234", 16))
    ) { dut =>
      readMem(dut, 0x50) shouldBe BigInt("1234FFFF", 16)
    }
  }

  // ----- sb -------------------------------------------------------------------

  it should "sb stores byte 0, preserving the rest" in {
    run(
      """addi x1, x0, 0xAB
           sb x1, 0x50(x0)
        """,
      data = Seq(0x50 -> BigInt("FFFFFFFF", 16))
    ) { dut =>
      readMem(dut, 0x50) shouldBe BigInt("FFFFFFAB", 16)
    }
  }

  it should "sb stores byte 1, preserving the rest" in {
    run(
      """addi x1, x0, 0xAB
           sb x1, 0x51(x0)
        """,
      data = Seq(0x50 -> BigInt("FFFFFFFF", 16))
    ) { dut =>
      readMem(dut, 0x50) shouldBe BigInt("FFFFABFF", 16)
    }
  }

  it should "sb stores byte 2, preserving the rest" in {
    run(
      """addi x1, x0, 0xAB
           sb x1, 0x52(x0)
        """,
      data = Seq(0x50 -> BigInt("FFFFFFFF", 16))
    ) { dut =>
      readMem(dut, 0x50) shouldBe BigInt("FFABFFFF", 16)
    }
  }

  it should "sb stores byte 3, preserving the rest" in {
    run(
      """addi x1, x0, 0xAB
           sb x1, 0x53(x0)
        """,
      data = Seq(0x50 -> BigInt("FFFFFFFF", 16))
    ) { dut =>
      readMem(dut, 0x50) shouldBe BigInt("ABFFFFFF", 16)
    }
  }

  it should "sb only writes the low byte of the source register" in {
    run(
      """lw x1, 0x54(x0)
           sb x1, 0x50(x0)
        """,
      data = Seq(0x50 -> BigInt(0), 0x54 -> BigInt("DEADBEEF", 16))
    ) { dut =>
      readMem(dut, 0x50) shouldBe BigInt("000000EF", 16)
    }
  }
}
