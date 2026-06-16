package pit

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Tests for [[BadCache]] -- the synchronous-read, byte-masked, stall-capable memory.
  *
  * Contract under test (see BadCache.scala):
  *   - req/valid/stall handshake; poll for `valid`, never hand-count cycles.
  *   - writes honor the 4-bit byte-enable mask (lane i = bits [8i+7:8i]); reads return the full
  *     word.
  *   - write data is lane-aligned by the caller; `addr` only selects the word (addr >> 2).
  *   - reads are synchronous (registered); a positive `responseLatency` injects stall cycles.
  */
class BadCacheSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "BadCache"

  private val maxCycles = 64

  // --- Handshake helpers: latency-agnostic, they poll `valid` (works at any responseLatency). ---

  /** Drive a write through the handshake and wait for completion. */
  private def doWrite(dut: BadCache, byteAddr: Int, data: UInt, mask: Int): Unit = {
    dut.io.req.valid.poke(true.B)
    dut.io.req.bits.we.poke(true.B)
    dut.io.req.bits.addr.poke(byteAddr.U)
    dut.io.req.bits.writeData.poke(data)
    dut.io.req.bits.writeMask.poke(mask.U)
    var i = 0
    while (dut.io.resp.valid.peek().litValue == 0 && i < maxCycles) { dut.clock.step(); i += 1 }
    assert(
      dut.io.resp.valid.peek().litValue == 1,
      s"write to 0x${byteAddr.toHexString} never completed"
    )
    // Deassert before the final step so the cycle after completion can't start a phantom access.
    dut.io.req.valid.poke(false.B)
    dut.io.req.bits.we.poke(false.B)
    dut.clock.step()
  }

  /** Drive a read through the handshake and return the word captured on the `valid` cycle. */
  private def doRead(dut: BadCache, byteAddr: Int): BigInt = {
    dut.io.req.valid.poke(true.B)
    dut.io.req.bits.we.poke(false.B)
    dut.io.req.bits.addr.poke(byteAddr.U)
    var i = 0
    while (dut.io.resp.valid.peek().litValue == 0 && i < maxCycles) { dut.clock.step(); i += 1 }
    assert(
      dut.io.resp.valid.peek().litValue == 1,
      s"read from 0x${byteAddr.toHexString} never completed"
    )
    val v = dut.io.resp.bits.peek().litValue
    dut.io.req.valid.poke(false.B)
    dut.clock.step()
    v
  }

  private def seedWord(dut: BadCache, byteAddr: Int, value: UInt): Unit =
    doWrite(dut, byteAddr, value, 0xf)

  /** Seed a full image (words(i) -> byte address 4*i), reusing the Utils.buildMemInit format. */
  private def seed(dut: BadCache, words: Seq[UInt]): Unit =
    for ((w, i) <- words.zipWithIndex) doWrite(dut, 4 * i, w, 0xf)

  private def idle(dut: BadCache, cycles: Int = 2): Unit = {
    dut.io.req.valid.poke(false.B)
    dut.io.req.bits.we.poke(false.B)
    dut.clock.step(cycles)
  }

  // --- Functional cases (responseLatency = 0), via the polling helpers -------------------------

  it should "round-trip a full word (mask 0xf)" in {
    simulate(new BadCache(None)) { dut =>
      doWrite(dut, 0, "hDEADBEEF".U, 0xf)
      doRead(dut, 0) shouldBe BigInt("DEADBEEF", 16)
    }
  }

  it should "write only the masked byte lane and preserve the others" in {
    val cases = Seq(
      (0x1, "h000000AA".U, BigInt("FFFFFFAA", 16)),
      (0x2, "h0000BB00".U, BigInt("FFFFBBFF", 16)),
      (0x4, "h00CC0000".U, BigInt("FFCCFFFF", 16)),
      (0x8, "hDD000000".U, BigInt("DDFFFFFF", 16))
    )
    for ((mask, wd, expected) <- cases) {
      simulate(new BadCache(None)) { dut =>
        seedWord(dut, 0, "hFFFFFFFF".U)
        doWrite(dut, 0, wd, mask)
        doRead(dut, 0) shouldBe expected
      }
    }
  }

  it should "write only the masked halfword and preserve the other half" in {
    simulate(new BadCache(None)) { dut =>
      seedWord(dut, 0, "hFFFFFFFF".U)
      doWrite(dut, 0, "h00001234".U, 0x3) // lower half
      doRead(dut, 0) shouldBe BigInt("FFFF1234", 16)
    }
    simulate(new BadCache(None)) { dut =>
      seedWord(dut, 0, "hFFFFFFFF".U)
      doWrite(dut, 0, "h56780000".U, 0xc) // upper half
      doRead(dut, 0) shouldBe BigInt("5678FFFF", 16)
    }
  }

  // The headline case: a halfword store to a 2-byte-aligned but NOT 4-byte-aligned address.
  // The byte address (0x2 vs 0x0) only changes the mask + the lane the data sits in; BadCache does
  // not shift internally. Both target word index 0.
  it should "store a halfword to a non-4-byte-aligned address (byte addr 0x2, upper half)" in {
    simulate(new BadCache(None)) { dut =>
      seedWord(dut, 0, "hFFFFFFFF".U)
      doWrite(dut, 0x2, "hBBBB0000".U, 0xc)
      doRead(dut, 0) shouldBe BigInt("BBBBFFFF", 16)
    }
  }

  it should "store a halfword to a 4-byte-aligned address (byte addr 0x0, lower half)" in {
    simulate(new BadCache(None)) { dut =>
      seedWord(dut, 0, "hFFFFFFFF".U)
      doWrite(dut, 0x0, "h0000BBBB".U, 0x3)
      doRead(dut, 0) shouldBe BigInt("FFFFBBBB", 16)
    }
  }

  it should "not modify memory on a request with we=false" in {
    simulate(new BadCache(None)) { dut =>
      seedWord(dut, 0, "h12345678".U)
      // Drive a read-shaped access that also presents write data/mask but keeps we low.
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.we.poke(false.B)
      dut.io.req.bits.addr.poke(0.U)
      dut.io.req.bits.writeData.poke("hFFFFFFFF".U)
      dut.io.req.bits.writeMask.poke(0xf.U)
      var i = 0
      while (dut.io.resp.valid.peek().litValue == 0 && i < maxCycles) { dut.clock.step(); i += 1 }
      dut.io.req.valid.poke(false.B)
      dut.clock.step()
      doRead(dut, 0) shouldBe BigInt("12345678", 16)
    }
  }

  it should "keep distinct words at distinct addresses and ignore addr[1:0] for word select" in {
    simulate(new BadCache(None)) { dut =>
      seedWord(dut, 0, "h11111111".U)
      seedWord(dut, 4, "h22222222".U)
      seedWord(dut, 8, "h33333333".U)
      doRead(dut, 0) shouldBe BigInt("11111111", 16)
      doRead(dut, 4) shouldBe BigInt("22222222", 16)
      doRead(dut, 8) shouldBe BigInt("33333333", 16)
      // addr 0x1/0x2/0x3 all index word 0.
      doRead(dut, 0x1) shouldBe BigInt("11111111", 16)
      doRead(dut, 0x2) shouldBe BigInt("11111111", 16)
      doRead(dut, 0x3) shouldBe BigInt("11111111", 16)
    }
  }

  it should "seed a full image via the write port (memInit-in-test path)" in {
    simulate(new BadCache(None)) { dut =>
      val image =
        Utils.buildMemInit("addi x1, x0, 5\naddi x2, x0, 6", Seq(0x50 -> BigInt("CAFEBABE", 16)))
      seed(dut, image)
      doRead(dut, 0x50) shouldBe BigInt("CAFEBABE", 16)
      doRead(dut, 0) should not be BigInt(0) // word 0 holds the first assembled instruction
    }
  }

  // --- Handshake / stall timing cases (assert valid/stall cycle-by-cycle) ----------------------

  it should "complete a hit one cycle after req and sustain one access per cycle" in {
    simulate(new BadCache(None)) { dut =>
      // Seed two adjacent words.
      seedWord(dut, 0, "hAAAAAAAA".U)
      seedWord(dut, 4, "hBBBBBBBB".U)
      idle(dut)

      // Cycle C0: issue a read of addr 0. valid is still low (data not ready), so stall is high.
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.we.poke(false.B)
      dut.io.req.bits.addr.poke(0.U)
      dut.io.resp.valid.expect(false.B)
      dut.clock.step()

      // C1: data for addr 0 is valid; pivot to addr 4 while holding req.
      dut.io.resp.valid.expect(true.B)
      dut.io.resp.bits.expect("hAAAAAAAA".U)
      dut.io.req.bits.addr.poke(4.U)
      dut.clock.step()

      // C2: back-to-back hit -- valid stays high, now serving addr 4 (1 access/cycle).
      dut.io.resp.valid.expect(true.B)
      dut.io.resp.bits.expect("hBBBBBBBB".U)
      dut.io.req.valid.poke(false.B)
    }
  }

  it should "hold stall high for responseLatency+1 cycles, then complete a read" in {
    val latency = 5
    simulate(new BadCache(None, responseLatency = latency)) { dut =>
      seedWord(dut, 0, "hCAFEBABE".U)
      idle(dut)

      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.we.poke(false.B)
      dut.io.req.bits.addr.poke(0.U)
      for (_ <- 0 until (latency + 1)) {
        dut.io.resp.valid.expect(false.B)
        dut.clock.step()
      }
      dut.io.resp.valid.expect(true.B)
      dut.io.resp.bits.expect("hCAFEBABE".U)
      dut.io.req.valid.poke(false.B)
    }
  }

  it should "not complete a stalled write until the valid cycle" in {
    val latency = 5
    simulate(new BadCache(None, responseLatency = latency)) { dut =>
      seedWord(dut, 0, "h00000000".U)
      idle(dut)

      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.we.poke(true.B)
      dut.io.req.bits.addr.poke(0.U)
      dut.io.req.bits.writeData.poke("h99999999".U)
      dut.io.req.bits.writeMask.poke(0xf.U)
      for (_ <- 0 until (latency + 1)) {
        dut.io.resp.valid.expect(false.B) // write not "done" while stalled
        dut.clock.step()
      }
      dut.io.resp.valid.expect(true.B) // completes here
      dut.io.req.valid.poke(false.B)
      dut.io.req.bits.we.poke(false.B)
      dut.clock.step()

      doRead(dut, 0) shouldBe BigInt("99999999", 16)
    }
  }

  it should "return the originally-requested word across a stall and then return to idle" in {
    val latency = 5
    simulate(new BadCache(None, responseLatency = latency)) { dut =>
      seedWord(dut, 8, "h0BADF00D".U)
      idle(dut)

      // Hold req + fields stable across the whole stall.
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.we.poke(false.B)
      dut.io.req.bits.addr.poke(8.U)
      var i = 0
      while (dut.io.resp.valid.peek().litValue == 0 && i < maxCycles) { dut.clock.step(); i += 1 }
      dut.io.resp.valid.expect(true.B)
      dut.io.resp.bits.expect("h0BADF00D".U)
      dut.io.req.valid.poke(false.B)
      dut.clock.step()

      // FSM is idle again: a fresh transaction still works.
      doRead(dut, 8) shouldBe BigInt("0BADF00D", 16)
    }
  }
}
