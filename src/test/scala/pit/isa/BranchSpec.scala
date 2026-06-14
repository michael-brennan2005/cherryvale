package pit.isa

import org.scalatest.flatspec.AnyFlatSpec

/** Branches: beq, bne, blt, bge, bltu, bgeu.
  *
  * Pattern: set up operands, set a marker x10 = 5, then branch over a "poison" instruction that
  * would set x10 = 9. Taken => x10 stays 5; not-taken => x10 == 9. Operand setup is separated from
  * the branch by the marker instruction so the branch reads settled registers (isolating branch
  * semantics from tight forwarding).
  */
class BranchSpec extends AnyFlatSpec with CpuTestBase {
  behavior of "branches"

  private def branchTest(mnemonic: String, a: Int, b: Int, taken: Boolean): Unit = {
    val prog =
      s"""addi x1, x0, $a
          addi x2, x0, $b
          addi x10, x0, 5
          $mnemonic x1, x2, 8
          addi x10, x0, 9
       """
    run(prog) { dut =>
      readReg(dut, 10) shouldBe BigInt(if (taken) 5 else 9)
    }
  }

  // ----- beq / bne ------------------------------------------------------------

  it should "beq taken when equal" in { branchTest("beq", 7, 7, taken = true) }
  it should "beq not taken when unequal" in { branchTest("beq", 7, 8, taken = false) }

  it should "bne taken when unequal" in { branchTest("bne", 7, 8, taken = true) }
  it should "bne not taken when equal" in { branchTest("bne", 7, 7, taken = false) }

  // ----- blt / bge (signed) ---------------------------------------------------

  it should "blt taken (signed: -1 < 1)" in { branchTest("blt", -1, 1, taken = true) }
  it should "blt not taken (signed: 1 < -1 is false)" in {
    branchTest("blt", 1, -1, taken = false)
  }

  it should "bge taken when greater (signed)" in { branchTest("bge", 5, 3, taken = true) }
  it should "bge taken when equal" in { branchTest("bge", 5, 5, taken = true) }
  it should "bge not taken (signed: -1 >= 1 is false)" in {
    branchTest("bge", -1, 1, taken = false)
  }

  // ----- bltu / bgeu (unsigned) ----------------------------------------------
  // -1 is assembled as 0xFFFFFFFF, the largest unsigned value.

  it should "bltu taken (unsigned: 1 < 0xFFFFFFFF)" in {
    branchTest("bltu", 1, -1, taken = true)
  }
  it should "bltu not taken (unsigned: 0xFFFFFFFF < 1 is false)" in {
    branchTest("bltu", -1, 1, taken = false)
  }

  it should "bgeu taken (unsigned: 0xFFFFFFFF >= 1)" in {
    branchTest("bgeu", -1, 1, taken = true)
  }
  it should "bgeu taken when equal" in { branchTest("bgeu", 3, 3, taken = true) }
  it should "bgeu not taken (unsigned: 1 >= 0xFFFFFFFF is false)" in {
    branchTest("bgeu", 1, -1, taken = false)
  }

  // ----- backward (negative) offset ------------------------------------------

  it should "beq with a negative offset loops back" in {
    run(
      """lw x1, 0x50(x0)
           beq x0, x0, 16
           addi x1, x0, 4
           addi x1, x0, 4
           lw x1, 0x54(x0)
           beq x0, x0, -4
        """,
      data = Seq(0x50 -> BigInt("CAFE", 16), 0x54 -> BigInt("BEEF", 16))
    ) { dut =>
      readReg(dut, 1) shouldBe BigInt("BEEF", 16)
    }
  }
}
