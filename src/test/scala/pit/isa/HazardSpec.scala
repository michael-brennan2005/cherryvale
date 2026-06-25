package pit.isa

import org.scalatest.flatspec.AnyFlatSpec

/** Multi-instruction pipeline stress: forwarding, load-use stalls, x0 handling, and control-flow
  * flushes. Expected values follow sequential RISC-V semantics regardless of how the pipeline
  * achieves them.
  */
class HazardSpec extends AnyFlatSpec with CpuTestBase {
  behavior of "pipeline hazards"

  it should "stall on a load-use hazard (dependent op immediately after lw)" in {
    run(
      """lw x1, 0x50(x0)
           add x2, x1, x1
        """,
      data = Seq(0x50 -> BigInt(21))
    ) { dut =>
      readReg(dut, 2) shouldBe BigInt(42)
    }
  }

  it should "use a loaded value two instructions later (no stall needed)" in {
    run(
      """lw x1, 0x50(x0)
           addi x3, x0, 0
           add x2, x1, x1
        """,
      data = Seq(0x50 -> BigInt(21))
    ) { dut =>
      readReg(dut, 2) shouldBe BigInt(42)
    }
  }

  it should "forward EX->EX for a back-to-back dependency" in {
    run("""addi x1, x0, 10
           addi x2, x1, 5
        """) { dut =>
      readReg(dut, 2) shouldBe BigInt(15)
    }
  }

  it should "forward MEM->EX across one independent instruction" in {
    run("""addi x1, x0, 10
           addi x3, x0, 0
           addi x2, x1, 5
        """) { dut =>
      readReg(dut, 2) shouldBe BigInt(15)
    }
  }

  it should "forward WB->EX across two independent instructions" in {
    run("""addi x1, x0, 10
           addi x3, x0, 0
           addi x4, x0, 0
           addi x2, x1, 5
        """) { dut =>
      readReg(dut, 2) shouldBe BigInt(15)
    }
  }

  it should "forward both operands in one instruction from different producers" in {
    run("""addi x1, x0, 10
           addi x2, x0, 20
           add x3, x1, x2
        """) { dut =>
      readReg(dut, 3) shouldBe BigInt(30)
    }
  }

  it should "forward through a chain of dependent adds" in {
    run("""addi x1, x0, 1
           addi x1, x1, 1
           addi x1, x1, 1
           addi x1, x1, 1
        """) { dut =>
      readReg(dut, 1) shouldBe BigInt(4)
    }
  }

  it should "never forward x0 (writes to x0 are dropped)" in {
    run("""addi x1, x0, 5
           addi x0, x1, 7
           add x2, x0, x0
        """) { dut =>
      readReg(dut, 0) shouldBe BigInt(0)
      readReg(dut, 2) shouldBe BigInt(0)
    }
  }

  it should "flush the instruction after a taken branch" in {
    run("""addi x10, x0, 5
           beq x0, x0, 8
           addi x10, x0, 9
        """) { dut =>
      readReg(dut, 10) shouldBe BigInt(5)
    }
  }

  it should "flush the instruction after a jal" in {
    run("""addi x10, x0, 5
           jal x0, 8
           addi x10, x0, 9
        """) { dut =>
      readReg(dut, 10) shouldBe BigInt(5)
    }
  }

  it should "run a countdown accumulate loop and store the result" in {
    // acc = 5+4+3+2+1 = 15, written to memory; exercises forwarding into both the
    // accumulate add and the loop-branch comparison across many iterations.
    run(
      """addi x1, x0, 5
         addi x2, x0, 0
         add x2, x2, x1
         addi x1, x1, -1
         bne x1, x0, -8
         sw x2, 0x50(x0)
      """,
      data = Seq(0x50 -> BigInt(0)),
      steps = 160
    ) { dut =>
      readMem(dut, 0x50) shouldBe BigInt(15)
    }
  }
}
