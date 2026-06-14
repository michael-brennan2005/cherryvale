package pit.isa

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.matchers.should.Matchers
import pit.{Cpu, Utils}

/** Shared harness for the RV32I instruction test suite.
  *
  * Conventions:
  *   - Programs are written in RISC-V assembly (assembled by RISCVAssembler via
  *     [[Utils.buildMemInit]]). PC starts at 0x0, instructions are placed from word 0.
  *   - `run` appends a self-loop trap (`beq x0, x0, 0`) so once the program finishes the PC parks
  *     instead of fetching zero-words / data as garbage. This lets us over-step the clock by a
  *     generous fixed amount instead of hand-counting pipeline cycles.
  *   - Data lives at high byte addresses (>= 0x50, i.e. words 20..31) to stay clear of the program
  *     region. Memory is only 32 words total.
  *   - Register/memory reads use the debug ports. `addr` on the memory debug port is a BYTE
  *     address.
  *
  * Behavioral expectations encode the RISC-V ISA, NOT the current datapath. Tests for unimplemented
  * / buggy instructions are expected to fail -- that failing set is the implementation checklist.
  */
trait CpuTestBase extends Matchers with ChiselSim { self: org.scalatest.TestSuite =>

  /** Self-loop: once reached, the PC stays put so over-stepping is harmless. */
  private val Trap = "beq x0, x0, 0"

  /** Read register `idx` (x0..x31) via the debug port. */
  protected def readReg(dut: Cpu, idx: Int): BigInt = {
    dut.io.reg_debug_addr.poke(idx.U)
    dut.io.reg_debug_data.peek().litValue
  }

  /** Read the memory word at BYTE address `addr` via the debug port. */
  protected def readMem(dut: Cpu, addr: Int): BigInt = {
    dut.io.mem_debug.addr.poke(addr.U)
    dut.io.mem_debug.data.peek().litValue
  }

  /** Express a (possibly negative) 32-bit value as the unsigned BigInt a register read would
    * return. e.g. `u32(-10) == 0xFFFFFFF6`.
    */
  protected def u32(v: Long): BigInt = BigInt(v & 0xffffffffL)

  /** Assemble `program` (with an appended trap), simulate, step the clock `steps` times, then run
    * assertions in `check`.
    *
    * @param steps
    *   default is generous enough to fully drain any straight-line program (including load-use
    *   stalls and branch flushes). Loop tests should pass an explicit value sized to the loop.
    */
  protected def run(
      program: String,
      data: Seq[(Int, BigInt)] = Seq.empty,
      steps: Int = 40
  )(check: Cpu => Unit): Unit = {
    val memInit = Utils.buildMemInit(program + "\n" + Trap, data)
    simulate(new Cpu(Some(memInit))) { dut =>
      dut.clock.step(steps)
      check(dut)
    }
  }
}
