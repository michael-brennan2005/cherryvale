package pit

import chisel3._
import org.scalatest.flatspec.AnyFlatSpec
import trunk.{BusMaster, Request, Response, CherrytrunkSlaveTestBase}

/** Tests for [[BadCache]] -- the synchronous-read, byte-masked, stall-capable memory, now a
  * cherrytrunk slave.
  *
  * Contract under test (see BadCache.scala):
  *   - cherrytrunk handshake: a transaction starts on req.stb and ends on the one-cycle resp.ack
  *     pulse; poll for ack, never hand-count cycles. resp.data is 0 unless ack is high.
  *   - writes honor the 4-bit byte-enable mask (lane i = bits [8i+7:8i]); reads return the full
  *     word.
  *   - write data is lane-aligned by the caller; `addr` only selects the word (addr >> 2).
  *   - reads are synchronous (registered); ack lands `responseLatency + 1` cycles after the strobe.
  */
class BadCacheSpec extends AnyFlatSpec with CherrytrunkSlaveTestBase {
  behavior of "BadCache"

  private val maxCycles = 64

  private def bus(d: BadCache): (Request, Response) = (d.io.req, d.io.resp)
  private def hex(s: String): BigInt = BigInt(s, 16)

  // --- Functional cases (responseLatency = 0), via the latency-agnostic BusMaster ---------------

  it should "round-trip a full word (mask 0xf)" in {
    withMaster(new BadCache(None), bus) { (_, m) =>
      m.write(0, hex("DEADBEEF"))
      val (data, err) = m.read(0)
      err shouldBe false
      data shouldBe hex("DEADBEEF")
    }
  }

  it should "write only the masked byte lane and preserve the others" in {
    val cases = Seq(
      (0x1, hex("000000AA"), hex("FFFFFFAA")),
      (0x2, hex("0000BB00"), hex("FFFFBBFF")),
      (0x4, hex("00CC0000"), hex("FFCCFFFF")),
      (0x8, hex("DD000000"), hex("DDFFFFFF"))
    )
    for ((mask, wd, expected) <- cases) {
      withMaster(new BadCache(None), bus) { (_, m) =>
        m.write(0, hex("FFFFFFFF"))
        m.write(0, wd, mask)
        m.read(0)._1 shouldBe expected
      }
    }
  }

  it should "write only the masked halfword and preserve the other half" in {
    withMaster(new BadCache(None), bus) { (_, m) =>
      m.write(0, hex("FFFFFFFF"))
      m.write(0, hex("00001234"), 0x3) // lower half
      m.read(0)._1 shouldBe hex("FFFF1234")
    }
    withMaster(new BadCache(None), bus) { (_, m) =>
      m.write(0, hex("FFFFFFFF"))
      m.write(0, hex("56780000"), 0xc) // upper half
      m.read(0)._1 shouldBe hex("5678FFFF")
    }
  }

  // The headline case: a halfword store to a 2-byte-aligned but NOT 4-byte-aligned address. The
  // byte address (0x2 vs 0x0) only changes the mask + the lane the data sits in; BadCache does not
  // shift internally. Both target word index 0.
  it should "store a halfword to a non-4-byte-aligned address (byte addr 0x2, upper half)" in {
    withMaster(new BadCache(None), bus) { (_, m) =>
      m.write(0, hex("FFFFFFFF"))
      m.write(0x2, hex("BBBB0000"), 0xc)
      m.read(0)._1 shouldBe hex("BBBBFFFF")
    }
  }

  it should "store a halfword to a 4-byte-aligned address (byte addr 0x0, lower half)" in {
    withMaster(new BadCache(None), bus) { (_, m) =>
      m.write(0, hex("FFFFFFFF"))
      m.write(0x0, hex("0000BBBB"), 0x3)
      m.read(0)._1 shouldBe hex("FFFFBBBB")
    }
  }

  it should "not modify memory on a read that also presents junk write data/mask" in {
    withMaster(new BadCache(None), bus) { (dut, m) =>
      m.write(0, hex("12345678"))
      // Drive a read (we=false) that nonetheless presents write data/mask, by hand.
      dut.io.req.we.poke(false.B)
      dut.io.req.addr.poke(0.U)
      dut.io.req.data.poke("hFFFFFFFF".U)
      dut.io.req.mask.poke(0xf.U)
      dut.io.req.stb.poke(true.B)
      dut.clock.step()
      dut.io.req.stb.poke(false.B)
      var i = 0
      while (dut.io.resp.ack.peek().litValue == 0 && i < maxCycles) { dut.clock.step(); i += 1 }
      dut.io.resp.ack.expect(true.B)
      m.idle()
      dut.clock.step()
      m.read(0)._1 shouldBe hex("12345678")
    }
  }

  it should "keep distinct words at distinct addresses and ignore addr[1:0] for word select" in {
    withMaster(new BadCache(None), bus) { (_, m) =>
      m.write(0, hex("11111111"))
      m.write(4, hex("22222222"))
      m.write(8, hex("33333333"))
      m.read(0)._1 shouldBe hex("11111111")
      m.read(4)._1 shouldBe hex("22222222")
      m.read(8)._1 shouldBe hex("33333333")
      // addr 0x1/0x2/0x3 all index word 0.
      m.read(0x1)._1 shouldBe hex("11111111")
      m.read(0x2)._1 shouldBe hex("11111111")
      m.read(0x3)._1 shouldBe hex("11111111")
    }
  }

  it should "seed a full image via the write port (memInit-in-test path)" in {
    withMaster(new BadCache(None), bus) { (_, m) =>
      val image =
        Utils.buildMemInit("addi x1, x0, 5\naddi x2, x0, 6", Seq(0x50 -> hex("CAFEBABE")))
      for ((w, i) <- image.zipWithIndex) m.write(4 * i, w.litValue)
      m.read(0x50)._1 shouldBe hex("CAFEBABE")
      m.read(0)._1 should not be BigInt(0) // word 0 holds the first assembled instruction
    }
  }

  // --- Handshake / latency timing cases (assert ack cycle-by-cycle) -----------------------------

  it should "ack exactly one cycle after the strobe for a hit (minimum latency)" in {
    withMaster(new BadCache(None), bus) { (dut, m) =>
      m.write(0, hex("AAAAAAAA"))
      m.idle()
      dut.clock.step()

      // C0: strobe in; ack is not asserted until the cycle after.
      dut.io.req.we.poke(false.B)
      dut.io.req.addr.poke(0.U)
      dut.io.req.mask.poke(0xf.U)
      dut.io.req.stb.poke(true.B)
      dut.io.resp.ack.expect(false.B)
      dut.clock.step()

      // C1: ack + data for the requested word.
      dut.io.req.stb.poke(false.B)
      dut.io.resp.ack.expect(true.B)
      dut.io.resp.data.expect("hAAAAAAAA".U)
    }
  }

  it should "hold ack low for responseLatency cycles, then complete a read" in {
    val latency = 5
    withMaster(new BadCache(None, responseLatency = latency), bus) { (dut, m) =>
      m.write(0, hex("CAFEBABE"))
      m.idle()
      dut.clock.step()

      dut.io.req.we.poke(false.B)
      dut.io.req.addr.poke(0.U)
      dut.io.req.mask.poke(0xf.U)
      dut.io.req.stb.poke(true.B)
      dut.clock.step()
      dut.io.req.stb.poke(false.B)

      // ack stays low through the stall, then asserts on the (latency+1)-th cycle after the strobe.
      for (_ <- 0 until latency) {
        dut.io.resp.ack.expect(false.B)
        dut.clock.step()
      }
      dut.io.resp.ack.expect(true.B)
      dut.io.resp.data.expect("hCAFEBABE".U)
    }
  }

  it should "not ack a stalled write until completion, then commit it" in {
    val latency = 5
    withMaster(new BadCache(None, responseLatency = latency), bus) { (dut, m) =>
      m.write(0, BigInt(0))
      m.idle()
      dut.clock.step()

      dut.io.req.we.poke(true.B)
      dut.io.req.addr.poke(0.U)
      dut.io.req.data.poke("h99999999".U)
      dut.io.req.mask.poke(0xf.U)
      dut.io.req.stb.poke(true.B)
      dut.clock.step()
      dut.io.req.stb.poke(false.B)

      for (_ <- 0 until latency) {
        dut.io.resp.ack.expect(false.B) // write not "done" while stalled
        dut.io.resp.data.expect(0.U) // and data stays 0 (slave obligation)
        dut.clock.step()
      }
      dut.io.resp.ack.expect(true.B) // completes here
      dut.io.resp.data.expect(0.U) // a write acks with no data

      m.idle()
      dut.clock.step()
      m.read(0)._1 shouldBe hex("99999999")
    }
  }

  it should "return the originally-requested word across a stall and then return to idle" in {
    val latency = 5
    withMaster(new BadCache(None, responseLatency = latency), bus) { (_, m) =>
      m.write(8, hex("0BADF00D"))
      m.read(8)._1 shouldBe hex("0BADF00D")
      // FSM is idle again: a fresh transaction still works.
      m.read(8)._1 shouldBe hex("0BADF00D")
    }
  }
}
