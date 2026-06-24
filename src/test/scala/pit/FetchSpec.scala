package pit

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Wrapper that gives the spec a single DUT: Fetch wired to a real BadCache, with the cache's
  * sim-only debug port exposed so the test can seed instructions through it. Mirrors how Tile
  * exposes BadCache's sim port for CpuTestBase.
  *
  * @param latency
  *   forwarded to BadCache.responseLatency. The sim port is always 0-latency, so seeding is
  *   unaffected by this; only the path Fetch uses (iCache.io.req/resp) sees the stalls.
  */
class FetchTestHarness(latency: Int) extends Module {
  val io = IO(new Bundle {
    val halt = Input(Bool())
    val clear = Input(Bool())
    val redirectPc = Input(Bool())
    val newPc = Input(UInt(32.W))

    val out = EnqIO(new FetchOutput)

    // Always-0-latency debug seeding port, mirrors BadCache's simReq/simResp.
    val simReq = DeqIO(new MemoryRequest)
    val simResp = Valid(UInt(32.W))
  })

  val fetch = Module(new Fetch)
  val iCache = Module(new BadCache(None, responseLatency = latency, sim = true))

  fetch.io.halt := io.halt
  fetch.io.clear := io.clear
  fetch.io.redirectPc := io.redirectPc
  fetch.io.newPc := io.newPc

  iCache.io.req <> fetch.io.req
  fetch.io.resp.bits := iCache.io.resp.bits
  fetch.io.resp.valid := iCache.io.resp.valid
  iCache.io.resp.ready := fetch.io.resp.ready

  // io.out and fetch.io.out are both EnqIO (same direction), so wire explicitly.
  io.out.bits := fetch.io.out.bits
  io.out.valid := fetch.io.out.valid
  fetch.io.out.ready := io.out.ready

  iCache.io.simReq.get.bits := io.simReq.bits
  iCache.io.simReq.get.valid := io.simReq.valid
  io.simReq.ready := iCache.io.simReq.get.ready
  io.simResp := iCache.io.simResp.get
}

/** Tests for [[Fetch]] -- PC generation, I$ handshake, sink backpressure, halt, clear, redirect.
  *
  * Contract under test:
  *   - After reset (or `clear`), PC is 0 and the first delivered instruction has pc=0.
  *   - Sequential fetch: pc advances by 4 each delivered instruction; pcPlus4 == pc + 4 always.
  *   - Sink backpressure (out.ready=false): bits and valid are held; no instruction is skipped
  *     (gaps between deliveries are fine -- Fetch has no FIFO).
  *   - Halt: a FREEZE, not a bubble. While halt is high the stage's visible output is held exactly
  *     as it was -- if out.valid was high it stays high (readable on release), if it was low it stays
  *     low. No new I$ requests issue and the PC does not advance. An in-flight I$ request may complete
  *     on the cache side during halt, but its response stays parked in the cache (held, never hidden,
  *     never lost) and only surfaces once halt deasserts. Fetching then resumes correctly.
  *   - Clear: full Fetch reset. In-flight I$ response does NOT surface on out. After clear, the
  *     next delivered instruction has pc=0.
  *   - redirectPc: any in-flight output is flushed; the next delivered instruction has pc=newPc.
  *     The last redirect of a back-to-back run wins.
  */
class FetchSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Fetch"

  private val maxCycles = 128

  // 16 distinct addi instructions; each line assembles to a unique 32-bit word so tests can assert
  // exact inst values via image(i). Reg destination is x1..x16 just for word-uniqueness.
  private val LongProg = (0 until 16).map(i => s"addi x${(i % 31) + 1}, x0, $i").mkString("\n")

  // --- Helpers ----------------------------------------------------------------------------------

  private def parkInputs(dut: FetchTestHarness): Unit = {
    dut.io.halt.poke(false.B)
    dut.io.clear.poke(false.B)
    dut.io.redirectPc.poke(false.B)
    dut.io.newPc.poke(0.U)
    dut.io.out.ready.poke(false.B)
    dut.io.simReq.valid.poke(false.B)
    dut.io.simReq.bits.we.poke(false.B)
    dut.io.simReq.bits.addr.poke(0.U)
    dut.io.simReq.bits.writeData.poke(0.U)
    dut.io.simReq.bits.writeMask.poke(0.U)
  }

  /** Write `image` into the wrapped iCache via the always-0-latency sim port. Assumes halt is high
    * so Fetch isn't issuing requests on the main port.
    */
  private def seed(dut: FetchTestHarness, image: Seq[UInt]): Unit = {
    for ((w, i) <- image.zipWithIndex) {
      dut.io.simReq.valid.poke(true.B)
      dut.io.simReq.bits.we.poke(true.B)
      dut.io.simReq.bits.addr.poke((4 * i).U)
      dut.io.simReq.bits.writeData.poke(w)
      dut.io.simReq.bits.writeMask.poke(0xf.U)
      dut.clock.step()
    }
    dut.io.simReq.valid.poke(false.B)
    dut.io.simReq.bits.we.poke(false.B)
  }

  /** Park inputs, halt, seed, release halt. After this, the DUT is ready to fetch. */
  private def setup(dut: FetchTestHarness, image: Seq[UInt]): Unit = {
    parkInputs(dut)
    dut.io.halt.poke(true.B)
    seed(dut, image)
    dut.io.halt.poke(false.B)
  }

  /** Poll for `out.valid` with ready=true, assert pc/pcPlus4/inst, step (consume), then deassert
    * ready so successive calls don't accidentally double-consume.
    */
  private def expectInst(
      dut: FetchTestHarness,
      expectedPc: Int,
      expectedInst: UInt,
      maxC: Int = maxCycles
  ): Unit = {
    dut.io.out.ready.poke(true.B)
    var i = 0
    while (dut.io.out.valid.peek().litValue == 0 && i < maxC) { dut.clock.step(); i += 1 }
    assert(
      dut.io.out.valid.peek().litValue == 1,
      s"out.valid never went high for pc=0x${expectedPc.toHexString} (waited $maxC cycles)"
    )
    dut.io.out.bits.pc.expect(expectedPc.U)
    dut.io.out.bits.pcPlus4.expect((expectedPc + 4).U)
    dut.io.out.bits.inst.expect(expectedInst)
    dut.clock.step()
    dut.io.out.ready.poke(false.B)
  }

  /** Wait until out.valid goes high (or fail), without consuming. Leaves ready=false. */
  private def waitForValid(dut: FetchTestHarness, maxC: Int = maxCycles): Unit = {
    dut.io.out.ready.poke(false.B)
    var i = 0
    while (dut.io.out.valid.peek().litValue == 0 && i < maxC) { dut.clock.step(); i += 1 }
    assert(
      dut.io.out.valid.peek().litValue == 1,
      s"out.valid never went high (waited $maxC cycles)"
    )
  }

  // === 1. Sequential fetch, latency=0 ===========================================================
  it should "fetch instructions sequentially with cache latency=0" in {
    val image = Utils.buildMemInit(LongProg, Seq.empty)
    simulate(new FetchTestHarness(0)) { dut =>
      setup(dut, image)
      for (i <- 0 until 4) expectInst(dut, 4 * i, image(i))
    }
  }

  // === 2. Sequential fetch, latency=5 ===========================================================
  it should "fetch instructions sequentially with cache latency=5" in {
    val image = Utils.buildMemInit(LongProg, Seq.empty)
    simulate(new FetchTestHarness(5)) { dut =>
      setup(dut, image)
      for (i <- 0 until 4) expectInst(dut, 4 * i, image(i))
    }
  }

  // === 3. Hold-on-stall, latency=0 ==============================================================
  it should "hold its current output when out.ready=false (latency=0) and not skip any instruction" in {
    val image = Utils.buildMemInit(LongProg, Seq.empty)
    simulate(new FetchTestHarness(0)) { dut =>
      setup(dut, image)

      // Consume one.
      expectInst(dut, 0, image(0))

      // Wait for the next instruction to be valid, but DON'T consume it.
      waitForValid(dut)
      dut.io.out.bits.pc.expect(4.U)
      dut.io.out.bits.inst.expect(image(1))

      // Hold for several cycles; the held output must not change.
      for (_ <- 0 until 8) {
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.pc.expect(4.U)
        dut.io.out.bits.inst.expect(image(1))
        dut.clock.step()
      }

      // Release the held instruction; the next one (pc=8) should follow.
      expectInst(dut, 4, image(1))
      expectInst(dut, 8, image(2))
    }
  }

  // === 4. Hold-on-stall, latency=5 ==============================================================
  it should "hold its current output when out.ready=false (latency=5) and capture the in-flight response" in {
    val image = Utils.buildMemInit(LongProg, Seq.empty)
    simulate(new FetchTestHarness(5)) { dut =>
      setup(dut, image)

      // Consume the first one.
      expectInst(dut, 0, image(0))

      // The next request is mid-stall in the cache. Keep ready=false the whole time.
      waitForValid(dut)
      dut.io.out.bits.pc.expect(4.U)
      dut.io.out.bits.inst.expect(image(1))

      // Hold across more cycles than the cache latency to be sure nothing shifts.
      for (_ <- 0 until 10) {
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.pc.expect(4.U)
        dut.io.out.bits.inst.expect(image(1))
        dut.clock.step()
      }

      // Resume; no instruction was dropped.
      expectInst(dut, 4, image(1))
      expectInst(dut, 8, image(2))
    }
  }

  // === 5. Halt freezes a presented output, latency=0 ============================================
  it should "freeze the pipeline under halt (latency=0), holding its output, and resume cleanly" in {
    val image = Utils.buildMemInit(LongProg, Seq.empty)
    simulate(new FetchTestHarness(0)) { dut =>
      setup(dut, image)

      expectInst(dut, 0, image(0))
      expectInst(dut, 4, image(1))

      // The next instruction (pc=8) is now presented but not yet consumed.
      waitForValid(dut)
      dut.io.out.bits.pc.expect(8.U)
      dut.io.out.bits.inst.expect(image(2))

      // Halt freezes the stage: the presented output is held unchanged and no new fetching happens.
      dut.io.halt.poke(true.B)
      dut.io.out.ready.poke(false.B)
      for (_ <- 0 until 10) {
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.pc.expect(8.U)
        dut.io.out.bits.inst.expect(image(2))
        dut.clock.step()
      }

      // Resume; the held instruction is consumed and fetching continues with nothing skipped.
      dut.io.halt.poke(false.B)
      expectInst(dut, 8, image(2))
      expectInst(dut, 12, image(3))
    }
  }

  // === 6. Halt during in-flight request, latency=5 ==============================================
  it should "not surface a result during halt asserted mid-fetch (latency=5), and resume correctly after" in {
    val image = Utils.buildMemInit(LongProg, Seq.empty)
    simulate(new FetchTestHarness(5)) { dut =>
      setup(dut, image)

      // Consume the first one so we know the request for pc=4 is now in flight.
      expectInst(dut, 0, image(0))

      // Give a couple of cycles for Fetch to start the next req, then halt mid-stall. out.valid is
      // low right now (the pc=4 response is still in flight), so freeze must hold it low.
      dut.clock.step(2)
      dut.io.halt.poke(true.B)
      dut.io.out.ready.poke(false.B)

      // Cover the full original latency window plus margin: out.valid stays low the whole time. The
      // in-flight response completes on the cache side during halt, but it stays parked in the cache
      // (held, not surfaced) because the frozen output was low when halt asserted.
      for (_ <- 0 until 20) {
        dut.io.out.valid.expect(false.B)
        dut.clock.step()
      }

      // Release; the parked pc=4 response now surfaces and is delivered correctly.
      dut.io.halt.poke(false.B)
      expectInst(dut, 4, image(1))
      expectInst(dut, 8, image(2))
    }
  }

  // === 7. Halt while out.valid is high ==========================================================
  it should "preserve a held output through halt and let the consumer read it on release" in {
    val image = Utils.buildMemInit(LongProg, Seq.empty)
    simulate(new FetchTestHarness(0)) { dut =>
      setup(dut, image)

      waitForValid(dut)
      dut.io.out.bits.pc.expect(0.U)

      // Halt with valid=1 in place. Output must stay stable.
      dut.io.halt.poke(true.B)
      for (_ <- 0 until 10) {
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.pc.expect(0.U)
        dut.io.out.bits.inst.expect(image(0))
        dut.clock.step()
      }

      // Release halt; consumer reads.
      dut.io.halt.poke(false.B)
      expectInst(dut, 0, image(0))
      expectInst(dut, 4, image(1))
    }
  }

  // === 8. Clear during idle, latency=0 ==========================================================
  it should "reset PC to 0 on clear (latency=0) and re-deliver from pc=0" in {
    val image = Utils.buildMemInit(LongProg, Seq.empty)
    simulate(new FetchTestHarness(0)) { dut =>
      setup(dut, image)

      // Advance past a few instructions.
      expectInst(dut, 0, image(0))
      expectInst(dut, 4, image(1))
      expectInst(dut, 8, image(2))

      // Clear for one cycle.
      dut.io.clear.poke(true.B)
      dut.clock.step()
      dut.io.clear.poke(false.B)

      // Next delivered instruction must be pc=0 again.
      expectInst(dut, 0, image(0))
      expectInst(dut, 4, image(1))
    }
  }

  // === 9. Clear during in-flight request, latency=5 =============================================
  it should "drop any in-flight response under clear (latency=5) and restart from pc=0" in {
    val image = Utils.buildMemInit(LongProg, Seq.empty)
    simulate(new FetchTestHarness(5)) { dut =>
      setup(dut, image)

      expectInst(dut, 0, image(0))

      // Now a request for pc=4 is in flight. Clear mid-stall.
      dut.clock.step(2)
      dut.io.clear.poke(true.B)
      dut.clock.step()
      dut.io.clear.poke(false.B)

      // Next delivered instruction must be pc=0, NOT pc=4 (the in-flight result is discarded).
      expectInst(dut, 0, image(0))
      expectInst(dut, 4, image(1))
    }
  }

  // === 10. Clear while out.valid is high ========================================================
  it should "drop a held output on clear and restart from pc=0" in {
    val image = Utils.buildMemInit(LongProg, Seq.empty)
    simulate(new FetchTestHarness(0)) { dut =>
      setup(dut, image)

      expectInst(dut, 0, image(0))
      waitForValid(dut)
      dut.io.out.bits.pc.expect(4.U)

      // Clear while pc=4 is being presented.
      dut.io.clear.poke(true.B)
      dut.clock.step()
      dut.io.clear.poke(false.B)
      // out.valid must drop (held instruction is flushed).
      dut.io.out.valid.expect(false.B)

      // Next delivered instruction is pc=0.
      expectInst(dut, 0, image(0))
    }
  }

  // === 11. redirectPc, latency=0 ================================================================
  it should "redirect to newPc on redirectPc (latency=0) and flush any pending output" in {
    val image = Utils.buildMemInit(LongProg, Seq.empty)
    simulate(new FetchTestHarness(0)) { dut =>
      setup(dut, image)

      expectInst(dut, 0, image(0))

      // Redirect to 0x20 (word 8).
      dut.io.redirectPc.poke(true.B)
      dut.io.newPc.poke(0x20.U)
      dut.clock.step()
      dut.io.redirectPc.poke(false.B)

      // Next delivered instruction must be pc=0x20 (image(8)), then 0x24 (image(9)).
      expectInst(dut, 0x20, image(8))
      expectInst(dut, 0x24, image(9))
    }
  }

  // === 12. redirectPc during in-flight request, latency=5 =======================================
  it should "flush in-flight fetch under redirectPc (latency=5) and only deliver from newPc" in {
    val image = Utils.buildMemInit(LongProg, Seq.empty)
    simulate(new FetchTestHarness(5)) { dut =>
      setup(dut, image)

      // Consume the first one.
      expectInst(dut, 0, image(0))

      // Now a request for pc=4 is mid-flight in the cache. Redirect to 0x20 before it completes.
      dut.clock.step(2)
      dut.io.redirectPc.poke(true.B)
      dut.io.newPc.poke(0x20.U)
      dut.clock.step()
      dut.io.redirectPc.poke(false.B)

      // The pre-redirect response for pc=4 must NOT surface; the next instruction is pc=0x20.
      expectInst(dut, 0x20, image(8))
      expectInst(dut, 0x24, image(9))
    }
  }

  // === 13. redirectPc while sink is stalled =====================================================
  it should "drop a held output on redirectPc when the sink is stalled" in {
    val image = Utils.buildMemInit(LongProg, Seq.empty)
    simulate(new FetchTestHarness(0)) { dut =>
      setup(dut, image)

      // Wait for pc=0 to appear but don't consume.
      waitForValid(dut)
      dut.io.out.bits.pc.expect(0.U)

      // Redirect while the held output is still presented.
      dut.io.redirectPc.poke(true.B)
      dut.io.newPc.poke(0x10.U)
      dut.clock.step()
      dut.io.redirectPc.poke(false.B)

      // On resume, the first delivered instruction is from newPc, not the previously-held pc=0.
      expectInst(dut, 0x10, image(4))
      expectInst(dut, 0x14, image(5))
    }
  }

  // === 14. Back-to-back redirects ===============================================================
  it should "honor the latest redirectPc on back-to-back redirects" in {
    val image = Utils.buildMemInit(LongProg, Seq.empty)
    simulate(new FetchTestHarness(0)) { dut =>
      setup(dut, image)

      expectInst(dut, 0, image(0))

      // Cycle A: redirect to 0x10.
      dut.io.redirectPc.poke(true.B)
      dut.io.newPc.poke(0x10.U)
      dut.clock.step()
      // Cycle B: redirect to 0x20 (this one wins).
      dut.io.newPc.poke(0x20.U)
      dut.clock.step()
      dut.io.redirectPc.poke(false.B)

      expectInst(dut, 0x20, image(8))
      expectInst(dut, 0x24, image(9))
    }
  }

  // === 15. Post-reset first fetch ===============================================================
  it should "deliver pc=0 as the first instruction after reset" in {
    val image = Utils.buildMemInit(LongProg, Seq.empty)
    simulate(new FetchTestHarness(0)) { dut =>
      setup(dut, image)
      expectInst(dut, 0, image(0))
    }
  }

  // TODO: additional edges to consider once the implementation lands:
  //   - halt && clear asserted on the same cycle (precedence is design-dependent).
  //   - redirectPc with newPc not 4-byte-aligned (BadCache ignores low 2 bits; Fetch should
  //     probably mask or reject).
  //   - redirectPc with newPc equal to the currently-fetching PC (no-op-ish).
}
