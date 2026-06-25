package pit.isa

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.matchers.should.Matchers
import pit.{Tile, Utils}

/** Shared harness for the RV32I instruction test suite.
  *
  * Conventions:
  *   - Programs are written in RISC-V assembly (assembled by RISCVAssembler via
  *     [[Utils.buildMemInit]]). PC starts at 0x0, instructions are placed from word 0.
  *   - `run` appends a self-loop trap (`beq x0, x0, 0`) so once the program finishes the PC parks
  *     instead of fetching zero-words / data as garbage. This lets us over-step the clock by a
  *     generous fixed amount instead of hand-counting pipeline cycles.
  *   - Data lives at high byte addresses (>= 0x50, i.e. words 20..31) to stay clear of the program
  *     region. Memory is only 32 words total (per [[Utils.buildMemInit]]).
  *   - Register reads use the register debug port. Memory reads/writes use the per-cache debug
  *     request ports. `addr` on those ports is a BYTE address.
  *
  * Memory model: the core now fetches from a synchronous-read `BadCache` (one for code, one for
  * data) instead of the old `RegInit`-backed `Memory`. `SyncReadMem` cannot be seeded at
  * construction under ChiselSim, so instead we:
  *   1. hold the core in `halt`,
  *   2. write the program+data image into BOTH caches through their sim-only debug request ports
  *      (iCache is fetched, dCache is loaded/stored -- they share an address space but are
  *      physically separate, so we seed them identically),
  *   3. release `halt` and step the program,
  *   4. re-assert `halt` and snapshot registers/memory.
  *
  * Seeding with the FULL image (including zero words) restores the old "uninitialized reads as 0"
  * behaviour that `SyncReadMem` otherwise loses.
  *
  * Behavioral expectations encode the RISC-V ISA, NOT the current datapath. Tests for unimplemented
  * / buggy instructions are expected to fail -- that failing set is the implementation checklist.
  */
trait CpuTestBase extends Matchers with ChiselSim { self: org.scalatest.TestSuite =>

  /** Self-loop: once reached, the PC stays put so over-stepping is harmless. */
  private val Trap = "beq x0, x0, 0"

  /** Read register `idx` (x0..x31) via the (combinational) register debug port. */
  protected def readReg(dut: Tile, idx: Int): BigInt = {
    dut.io.regSimIdx.get.poke(idx.U)
    dut.io.regSimData.get.peek().litValue
  }

  /** Read the data-memory word at BYTE address `addr` via the dCache debug port. The debug read is
    * synchronous (one cycle of `SyncReadMem` latency), so we issue the request and step once before
    * sampling. Reads never collide with the core, so this is safe whether or not the core is
    * halted.
    */
  protected def readMem(dut: Tile, addr: Int): BigInt = {
    val p = dut.io.dCacheReq.get
    p.bits.addr.poke(addr.U)
    p.bits.we.poke(false.B)
    p.bits.writeData.poke(0.U)
    p.bits.writeMask.poke(0.U)
    p.valid.poke(true.B)
    dut.clock.step() // sync-read latency: data valid the cycle after the request
    val v = dut.io.dCacheResp.get.bits.peek().litValue
    p.valid.poke(false.B)
    v
  }

  /** Express a (possibly negative) 32-bit value as the unsigned BigInt a register read would
    * return. e.g. `u32(-10) == 0xFFFFFFF6`.
    */
  protected def u32(v: Long): BigInt = BigInt(v & 0xffffffffL)

  /** Park both debug request ports (no in-flight access). */
  private def idleDebug(dut: Tile): Unit =
    for (p <- Seq(dut.io.iCacheReq.get, dut.io.dCacheReq.get)) {
      p.valid.poke(false.B)
      p.bits.we.poke(false.B)
      p.bits.addr.poke(0.U)
      p.bits.writeData.poke(0.U)
      p.bits.writeMask.poke(0.U)
    }

  /** Write `image` (word `i` -> byte address `4*i`) into both caches, one word per cycle, via the
    * debug write ports. Assumes `halt` is asserted so the core isn't contending for the caches.
    */
  private def seedBoth(dut: Tile, image: Seq[UInt]): Unit = {
    for ((w, i) <- image.zipWithIndex) {
      for (p <- Seq(dut.io.iCacheReq.get, dut.io.dCacheReq.get)) {
        p.valid.poke(true.B)
        p.bits.we.poke(true.B)
        p.bits.addr.poke((4 * i).U)
        p.bits.writeData.poke(w)
        p.bits.writeMask.poke(0xf.U)
      }
      dut.clock.step()
    }
    idleDebug(dut)
  }

  /** Assemble `program` (with an appended trap), seed both caches under halt, release halt and step
    * the clock `steps` times, then re-assert halt and run assertions in `check`.
    *
    * @param steps
    *   default is generous enough to fully drain any straight-line program (including load-use
    *   stalls and branch flushes). Loop tests should pass an explicit value sized to the loop.
    */
  protected def run(
      program: String,
      data: Seq[(Int, BigInt)] = Seq.empty,
      steps: Int = 200
  )(check: Tile => Unit): Unit = {
    val image = Utils.buildMemInit(program + "\n" + Trap, data)
    simulate(new Tile(None, exposeSimPorts = true, simCacheLatency = 0)) { dut =>
      // 1. Hold the core so seeding doesn't contend with fetch / load-store.
      dut.io.halt.poke(true.B)
      idleDebug(dut)

      // 2. Seed both caches identically with the full image.
      seedBoth(dut, image)

      // 3. Release halt and run the program.
      dut.io.halt.poke(false.B)
      dut.clock.step(steps)

      // 4. Halt again for a clean snapshot, then check.
      dut.io.halt.poke(true.B)
      check(dut)
    }
  }
}
