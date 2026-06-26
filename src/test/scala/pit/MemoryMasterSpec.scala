package pit

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import trunk.TrunkMemSlave

/** Test harness for [[MemoryMaster]]. Wires the master to a real, latency-configurable cherrytrunk
  * slave ([[trunk.TrunkMemSlave]]) and taps the bus-side request signals out so protocol-shape
  * tests can assert them cycle-by-cycle. Convention mirrors `ExecuteTestHarness` in
  * ExecuteSpec.scala.
  *
  *   - `coreReq`/`coreResp` pass straight through to the master's core-side ports (drive/observe
  *     exactly like `BadCache`'s req/resp).
  *   - `bus*` are read-only taps on `MemoryMaster.busSideReq`.
  *   - `seedReq`/`seedResp` reach the slave's 0-latency sim port for deterministic memory seeding.
  */
class MemoryMasterTestHarness(slaveLatency: Int) extends Module {
  val io = IO(new Bundle {
    // Core-side passthrough (same shapes as MemoryMaster's core ports).
    val coreReq = DeqIO(new MemoryRequest)
    val coreResp = EnqIO(UInt(32.W))

    // Read-only taps on the bus-side request the master drives.
    val busStb = Output(Bool())
    val busCyc = Output(Bool())
    val busAddr = Output(UInt(32.W))
    val busData = Output(UInt(32.W))
    val busMask = Output(UInt(4.W))
    val busWe = Output(Bool())

    // 0-latency seed/inspect port into the slave's memory.
    val seedReq = DeqIO(new MemoryRequest)
    val seedResp = Valid(UInt(32.W))
  })

  val mm = Module(new MemoryMaster)
  val slave = Module(new TrunkMemSlave(latency = slaveLatency, sim = true))

  // Core request passthrough.
  mm.io.coreSideReq.valid := io.coreReq.valid
  mm.io.coreSideReq.bits := io.coreReq.bits
  io.coreReq.ready := mm.io.coreSideReq.ready

  // Core response passthrough.
  io.coreResp.valid := mm.io.coreSideResp.valid
  io.coreResp.bits := mm.io.coreSideResp.bits
  mm.io.coreSideResp.ready := io.coreResp.ready

  // Bus: master drives the slave; slave's response feeds back to the master.
  slave.io.req := mm.io.busSideReq
  mm.io.busSideResp := slave.io.resp

  // Bus taps.
  io.busStb := mm.io.busSideReq.stb
  io.busCyc := mm.io.busSideReq.cyc
  io.busAddr := mm.io.busSideReq.addr
  io.busData := mm.io.busSideReq.data
  io.busMask := mm.io.busSideReq.mask
  io.busWe := mm.io.busSideReq.we

  // Seed port into the slave.
  slave.io.simReq.get.valid := io.seedReq.valid
  slave.io.simReq.get.bits := io.seedReq.bits
  io.seedReq.ready := slave.io.simReq.get.ready
  io.seedResp := slave.io.simResp.get
}

/** Contract for [[MemoryMaster]]: convert ready-valid `MemoryRequest` transactions into cherrytrunk
  * master transactions.
  *
  * Decisions pinned here (confirmed with the design owner):
  *   1. Functional tests poll the core side (latency-agnostic, like BadCacheSpec); protocol tests
  *      assert the bus taps cycle-by-cycle.
  *   2. A write pulses `coreSideResp.valid` on completion (a "done" signal, symmetric with
  *      BadCache).
  *   3. `resp.err` handling is out of scope -- the core side has no error channel yet. Tests assume
  *      `err == false`; there is no error test.
  *   4. A low `coreSideResp.ready` when the one-cycle bus `ack` arrives must be lossless: the
  *      master holds the response and back-pressures the core request until it is taken.
  *
  * NOTE: this spec is written against the current `MemoryMaster` *stub* (IO only), so it is
  * expected to fail until the module is implemented. It pins the contract for that implementation
  * step.
  */
class MemoryMasterSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "MemoryMaster"

  private val maxCycles = 64
  private val mask32 = 0xffffffffL

  // --- Core-side handshake helpers: poll `valid`, never hand-count (works at any slaveLatency). ---

  private def idle(dut: MemoryMasterTestHarness, cycles: Int = 2): Unit = {
    dut.io.coreReq.valid.poke(false.B)
    dut.io.coreReq.bits.we.poke(false.B)
    dut.io.coreResp.ready.poke(true.B) // default to a consuming core unless a test overrides
    dut.io.seedReq.valid.poke(false.B)
    dut.clock.step(cycles)
  }

  /** Seed one word straight into the slave memory via the 0-latency sim port. */
  private def seed(dut: MemoryMasterTestHarness, byteAddr: Int, word: Long): Unit = {
    dut.io.seedReq.valid.poke(true.B)
    dut.io.seedReq.bits.we.poke(true.B)
    dut.io.seedReq.bits.addr.poke(byteAddr.U)
    dut.io.seedReq.bits.writeData.poke((word & mask32).U)
    dut.io.seedReq.bits.writeMask.poke(0xf.U)
    dut.clock.step()
    dut.io.seedReq.valid.poke(false.B)
    dut.io.seedReq.bits.we.poke(false.B)
  }

  /** Drive a read through the core handshake; return the word seen on the `valid` cycle. */
  private def coreRead(dut: MemoryMasterTestHarness, byteAddr: Int): BigInt = {
    dut.io.coreResp.ready.poke(true.B)
    dut.io.coreReq.valid.poke(true.B)
    dut.io.coreReq.bits.we.poke(false.B)
    dut.io.coreReq.bits.addr.poke(byteAddr.U)
    dut.io.coreReq.bits.writeMask.poke(0xf.U) // request the full word
    var i = 0
    while (dut.io.coreResp.valid.peek().litValue == 0 && i < maxCycles) { dut.clock.step(); i += 1 }
    assert(
      dut.io.coreResp.valid.peek().litValue == 1,
      s"read from 0x${byteAddr.toHexString} never completed"
    )
    val v = dut.io.coreResp.bits.peek().litValue
    dut.io.coreReq.valid.poke(false.B)
    dut.clock.step()
    v
  }

  /** Drive a write through the core handshake and wait for the completion ("done") pulse. */
  private def coreWrite(
      dut: MemoryMasterTestHarness,
      byteAddr: Int,
      data: Long,
      mask: Int
  ): Unit = {
    dut.io.coreResp.ready.poke(true.B)
    dut.io.coreReq.valid.poke(true.B)
    dut.io.coreReq.bits.we.poke(true.B)
    dut.io.coreReq.bits.addr.poke(byteAddr.U)
    dut.io.coreReq.bits.writeData.poke((data & mask32).U)
    dut.io.coreReq.bits.writeMask.poke(mask.U)
    var i = 0
    while (dut.io.coreResp.valid.peek().litValue == 0 && i < maxCycles) { dut.clock.step(); i += 1 }
    assert(
      dut.io.coreResp.valid.peek().litValue == 1,
      s"write to 0x${byteAddr.toHexString} never completed"
    )
    dut.io.coreReq.valid.poke(false.B)
    dut.io.coreReq.bits.we.poke(false.B)
    dut.clock.step()
  }

  // ============================================================================================
  // A. Functional / end-to-end (poll the core side; run at slaveLatency 0 and 5)
  // ============================================================================================

  for (latency <- Seq(0, 5)) {
    it should s"round-trip a seeded read (slaveLatency=$latency)" in {
      simulate(new MemoryMasterTestHarness(latency)) { dut =>
        idle(dut)
        seed(dut, 0, 0xdeadbeefL)
        coreRead(dut, 0) shouldBe BigInt("DEADBEEF", 16)
      }
    }

    it should s"write then read back the same word end-to-end (slaveLatency=$latency)" in {
      simulate(new MemoryMasterTestHarness(latency)) { dut =>
        idle(dut)
        coreWrite(dut, 0x4, 0x0badf00dL, 0xf)
        coreRead(dut, 0x4) shouldBe BigInt("0BADF00D", 16)
      }
    }

    it should s"keep distinct words at distinct addresses (slaveLatency=$latency)" in {
      simulate(new MemoryMasterTestHarness(latency)) { dut =>
        idle(dut)
        coreWrite(dut, 0x0, 0x11111111L, 0xf)
        coreWrite(dut, 0x4, 0x22222222L, 0xf)
        coreWrite(dut, 0x8, 0x33333333L, 0xf)
        coreRead(dut, 0x0) shouldBe BigInt("11111111", 16)
        coreRead(dut, 0x4) shouldBe BigInt("22222222", 16)
        coreRead(dut, 0x8) shouldBe BigInt("33333333", 16)
      }
    }
  }

  it should "honor a byte-lane write mask, preserving the other lanes" in {
    val cases = Seq(
      (0x1, 0x000000aaL, BigInt("FFFFFFAA", 16)),
      (0x2, 0x0000bb00L, BigInt("FFFFBBFF", 16)),
      (0x4, 0x00cc0000L, BigInt("FFCCFFFF", 16)),
      (0x8, 0xdd000000L, BigInt("DDFFFFFF", 16))
    )
    for ((mask, wd, expected) <- cases) {
      simulate(new MemoryMasterTestHarness(0)) { dut =>
        idle(dut)
        seed(dut, 0, 0xffffffffL)
        coreWrite(dut, 0, wd, mask)
        coreRead(dut, 0) shouldBe expected
      }
    }
  }

  it should "honor a halfword write mask, preserving the other half" in {
    simulate(new MemoryMasterTestHarness(0)) { dut =>
      idle(dut)
      seed(dut, 0, 0xffffffffL)
      coreWrite(dut, 0, 0x00001234L, 0x3) // lower half
      coreRead(dut, 0) shouldBe BigInt("FFFF1234", 16)
    }
    simulate(new MemoryMasterTestHarness(0)) { dut =>
      idle(dut)
      seed(dut, 0, 0xffffffffL)
      coreWrite(dut, 0, 0x56780000L, 0xc) // upper half
      coreRead(dut, 0) shouldBe BigInt("5678FFFF", 16)
    }
  }

  it should "complete two back-to-back transactions without losing either" in {
    simulate(new MemoryMasterTestHarness(0)) { dut =>
      idle(dut)
      seed(dut, 0x0, 0xa5a5a5a5L)
      // read -> write -> read, sequentially.
      coreRead(dut, 0x0) shouldBe BigInt("A5A5A5A5", 16)
      coreWrite(dut, 0x0, 0x5a5a5a5aL, 0xf)
      coreRead(dut, 0x0) shouldBe BigInt("5A5A5A5A", 16)
    }
  }

  // ============================================================================================
  // B. Field mapping -- assert the bus taps while the request is on the bus.
  // ============================================================================================

  /** Step until the master's strobe is observed (within budget) and return having stopped on that
    * cycle. Uses a positive slaveLatency so the request lingers on the bus.
    */
  private def stepUntilStb(dut: MemoryMasterTestHarness): Unit = {
    var i = 0
    while (dut.io.busStb.peek().litValue == 0 && i < maxCycles) { dut.clock.step(); i += 1 }
    assert(dut.io.busStb.peek().litValue == 1, "master never asserted stb")
  }

  /** Drain whatever transaction is in flight so the harness returns to idle. */
  private def drain(dut: MemoryMasterTestHarness): Unit = {
    dut.io.coreResp.ready.poke(true.B)
    var i = 0
    while (dut.io.coreResp.valid.peek().litValue == 0 && i < maxCycles) { dut.clock.step(); i += 1 }
    dut.io.coreReq.valid.poke(false.B)
    dut.clock.step()
  }

  it should "map a write's fields onto the bus request" in {
    simulate(new MemoryMasterTestHarness(5)) { dut =>
      idle(dut)
      dut.io.coreResp.ready.poke(true.B)
      dut.io.coreReq.valid.poke(true.B)
      dut.io.coreReq.bits.we.poke(true.B)
      dut.io.coreReq.bits.addr.poke(0x40.U)
      dut.io.coreReq.bits.writeData.poke(0xcafebabeL.U)
      dut.io.coreReq.bits.writeMask.poke(0x3.U)
      stepUntilStb(dut)
      dut.io.busWe.expect(true.B)
      dut.io.busAddr.expect(0x40.U)
      dut.io.busData.expect(0xcafebabeL.U)
      dut.io.busMask.expect(0x3.U)
      dut.io.busCyc.expect(true.B)
      drain(dut)
    }
  }

  it should "map a read's fields onto the bus request (mask forwarded as-is)" in {
    simulate(new MemoryMasterTestHarness(5)) { dut =>
      idle(dut)
      dut.io.coreResp.ready.poke(true.B)
      dut.io.coreReq.valid.poke(true.B)
      dut.io.coreReq.bits.we.poke(false.B)
      dut.io.coreReq.bits.addr.poke(0x20.U)
      dut.io.coreReq.bits.writeMask.poke(0xf.U)
      stepUntilStb(dut)
      dut.io.busWe.expect(false.B)
      dut.io.busAddr.expect(0x20.U)
      // The request's mask is forwarded verbatim. If the implementation instead chooses to force
      // mask=0xf on reads, update this expectation (and document the choice in MemoryMaster).
      dut.io.busMask.expect(0xf.U)
      dut.io.busCyc.expect(true.B)
      drain(dut)
    }
  }

  // ============================================================================================
  // C. Protocol shape -- cycle-by-cycle via taps; slaveLatency=5 opens the window.
  // ============================================================================================

  it should "drive stb as a one-cycle pulse for the whole transaction" in {
    simulate(new MemoryMasterTestHarness(5)) { dut =>
      idle(dut)
      seed(dut, 0, 0x12345678L)
      dut.io.coreResp.ready.poke(true.B)
      dut.io.coreReq.valid.poke(true.B)
      dut.io.coreReq.bits.we.poke(false.B)
      dut.io.coreReq.bits.addr.poke(0.U)
      dut.io.coreReq.bits.writeMask.poke(0xf.U)

      // Count stb-high cycles across the whole transaction. Deassert the core request as soon as it
      // is accepted so we measure exactly one transaction.
      var stbHigh = 0
      var accepted = false
      var i = 0
      while (dut.io.coreResp.valid.peek().litValue == 0 && i < maxCycles) {
        if (dut.io.busStb.peek().litValue == 1) stbHigh += 1
        if (!accepted && dut.io.coreReq.ready.peek().litValue == 1) accepted = true
        dut.clock.step()
        if (accepted) dut.io.coreReq.valid.poke(false.B)
        i += 1
      }
      stbHigh shouldBe 1
    }
  }

  it should "hold cyc high for the entire transaction and low when idle" in {
    simulate(new MemoryMasterTestHarness(5)) { dut =>
      idle(dut)
      // Idle: no outstanding transaction -> cyc low.
      dut.io.busCyc.expect(false.B)
      dut.io.busStb.expect(false.B)

      seed(dut, 0, 0x9abcdef0L)
      dut.io.coreResp.ready.poke(true.B)
      dut.io.coreReq.valid.poke(true.B)
      dut.io.coreReq.bits.we.poke(false.B)
      dut.io.coreReq.bits.addr.poke(0.U)
      dut.io.coreReq.bits.writeMask.poke(0xf.U)

      stepUntilStb(dut)
      // From stb until the transaction completes, cyc must stay asserted.
      var i = 0
      while (dut.io.coreResp.valid.peek().litValue == 0 && i < maxCycles) {
        dut.io.busCyc.expect(true.B)
        dut.io.coreReq.valid.poke(false.B) // accepted already; don't start a second transaction
        dut.clock.step()
        i += 1
      }
    }
  }

  it should "hold the bus request fields stable from stb until ack" in {
    simulate(new MemoryMasterTestHarness(5)) { dut =>
      idle(dut)
      dut.io.coreResp.ready.poke(true.B)
      dut.io.coreReq.valid.poke(true.B)
      dut.io.coreReq.bits.we.poke(true.B)
      dut.io.coreReq.bits.addr.poke(0x30.U)
      dut.io.coreReq.bits.writeData.poke(0xface.U)
      dut.io.coreReq.bits.writeMask.poke(0xf.U)

      stepUntilStb(dut)
      var i = 0
      while (dut.io.coreResp.valid.peek().litValue == 0 && i < maxCycles) {
        dut.io.busWe.expect(true.B)
        dut.io.busAddr.expect(0x30.U)
        dut.io.busData.expect(0xface.U)
        dut.io.busMask.expect(0xf.U)
        dut.io.coreReq.valid.poke(false.B)
        dut.clock.step()
        i += 1
      }
    }
  }

  it should "issue no bus transaction while the core request is idle" in {
    simulate(new MemoryMasterTestHarness(5)) { dut =>
      idle(dut)
      for (_ <- 0 until 8) {
        dut.io.busStb.expect(false.B)
        dut.io.busCyc.expect(false.B)
        dut.clock.step()
      }
    }
  }

  it should "back-pressure the core request while a transaction is in flight" in {
    simulate(new MemoryMasterTestHarness(5)) { dut =>
      idle(dut)
      dut.io.coreReq.ready.expect(true.B) // idle: ready to accept
      seed(dut, 0, 0x0L)

      dut.io.coreResp.ready.poke(true.B)
      dut.io.coreReq.valid.poke(true.B)
      dut.io.coreReq.bits.we.poke(false.B)
      dut.io.coreReq.bits.addr.poke(0.U)
      dut.io.coreReq.bits.writeMask.poke(0xf.U)
      stepUntilStb(dut)
      dut.io.coreReq.valid.poke(false.B)

      // While the transaction is outstanding the master must not accept a new request.
      var i = 0
      while (dut.io.coreResp.valid.peek().litValue == 0 && i < maxCycles) {
        dut.io.coreReq.ready.expect(false.B)
        dut.clock.step()
        i += 1
      }
    }
  }

  // ============================================================================================
  // D. Write-done semantics.
  // ============================================================================================

  it should "pulse coreSideResp.valid when a write completes" in {
    simulate(new MemoryMasterTestHarness(5)) { dut =>
      idle(dut)
      dut.io.coreResp.ready.poke(true.B)
      dut.io.coreReq.valid.poke(true.B)
      dut.io.coreReq.bits.we.poke(true.B)
      dut.io.coreReq.bits.addr.poke(0x0.U)
      dut.io.coreReq.bits.writeData.poke(0x99999999L.U)
      dut.io.coreReq.bits.writeMask.poke(0xf.U)
      var i = 0
      while (dut.io.coreResp.valid.peek().litValue == 0 && i < maxCycles) {
        dut.clock.step(); i += 1
      }
      dut.io.coreResp.valid.expect(true.B) // the write reported "done"
    }
  }

  // ============================================================================================
  // E. Core-side response back-pressure must be lossless.
  // ============================================================================================

  it should "hold a completed read while coreSideResp.ready is low, then transfer on drain" in {
    simulate(new MemoryMasterTestHarness(3)) { dut =>
      idle(dut)
      seed(dut, 0xc, 0xabcdef12L)

      // Issue a read but keep the consumer not ready.
      dut.io.coreResp.ready.poke(false.B)
      dut.io.coreReq.valid.poke(true.B)
      dut.io.coreReq.bits.we.poke(false.B)
      dut.io.coreReq.bits.addr.poke(0xc.U)
      dut.io.coreReq.bits.writeMask.poke(0xf.U)
      var i = 0
      while (dut.io.coreResp.valid.peek().litValue == 0 && i < maxCycles) {
        dut.clock.step(); i += 1
      }
      dut.io.coreResp.valid.expect(true.B)
      dut.io.coreResp.bits.expect(0xabcdef12L.U)
      dut.io.coreReq.ready.expect(false.B) // not idle: holding the completed response

      // Hold for several cycles with ready low; the value persists and req stays back-pressured.
      dut.io.coreReq.valid.poke(false.B)
      dut.clock.step(3)
      dut.io.coreResp.valid.expect(true.B)
      dut.io.coreResp.bits.expect(0xabcdef12L.U)
      dut.io.coreReq.ready.expect(false.B)

      // Drain: with ready high the held word transfers and the master returns to idle.
      dut.io.coreResp.ready.poke(true.B)
      dut.clock.step()
      dut.io.coreResp.valid.expect(false.B)
      dut.io.coreReq.ready.expect(true.B)
    }
  }
}
