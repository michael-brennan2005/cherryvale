package pit

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Wrapper that gives the spec a single DUT: Execute wired to a real BadCache acting as the D$, with
  * the cache's sim-only debug port exposed so the test can seed memory (for loads) and read it back
  * (to verify stores). Mirrors how FetchTestHarness wraps Fetch + the I$.
  *
  * Execute's request port (the path to the cache) is internal to this wrapper, so the few request
  * fields tests care about (addr alignment, we, byte mask, lane-aligned write data) are tapped out
  * as `reqValid`/`reqAddr`/`reqWe`/`reqWriteMask`/`reqWriteData`.
  *
  * @param latency
  *   forwarded to BadCache.responseLatency. The sim port is always 0-latency, so seeding/readback is
  *   unaffected; only the path Execute uses (dCache.io.req/resp) sees the stalls.
  */
class ExecuteTestHarness(latency: Int) extends Module {
  val io = IO(new Bundle {
    val halt = Input(Bool())
    val clear = Input(Bool())

    val in = DeqIO(new ReadOperandsOutput)
    val out = EnqIO(new ExecuteOutput)

    // Observation taps for Execute's combinational control outputs.
    val redirectPc = Output(Bool())
    val newPc = Output(UInt(32.W))
    val regDestIdx = Output(UInt(5.W))
    val regDestData = Output(UInt(32.W))
    val loadHazardDest = Output(UInt(5.W))

    // D$ request monitor (taps Execute -> cache so a store's mask/data are checkable).
    val reqValid = Output(Bool())
    val reqAddr = Output(UInt(32.W))
    val reqWe = Output(Bool())
    val reqWriteMask = Output(UInt(4.W))
    val reqWriteData = Output(UInt(32.W))

    // Always-0-latency debug seed/readback port, mirrors BadCache's simReq/simResp.
    val simReq = DeqIO(new MemoryRequest)
    val simResp = Valid(UInt(32.W))
  })

  val ex = Module(new Execute)
  val dCache = Module(new BadCache(None, responseLatency = latency, sim = true))

  ex.io.halt := io.halt
  ex.io.clear := io.clear

  // io.in and ex.io.in are both DeqIO (consumer-facing): drive valid/bits in, read ready out.
  ex.io.in.valid := io.in.valid
  ex.io.in.bits := io.in.bits
  io.in.ready := ex.io.in.ready

  // io.out and ex.io.out are both EnqIO (same direction), so wire explicitly.
  io.out.valid := ex.io.out.valid
  io.out.bits := ex.io.out.bits
  ex.io.out.ready := io.out.ready

  dCache.io.req <> ex.io.req
  ex.io.resp.bits := dCache.io.resp.bits
  ex.io.resp.valid := dCache.io.resp.valid
  dCache.io.resp.ready := ex.io.resp.ready

  io.redirectPc := ex.io.redirectPc
  io.newPc := ex.io.newPc
  io.regDestIdx := ex.io.regDestIdx
  io.regDestData := ex.io.regDestData
  io.loadHazardDest := ex.io.loadHazardDest

  io.reqValid := ex.io.req.valid
  io.reqAddr := ex.io.req.bits.addr
  io.reqWe := ex.io.req.bits.we
  io.reqWriteMask := ex.io.req.bits.writeMask
  io.reqWriteData := ex.io.req.bits.writeData

  dCache.io.simReq.get.bits := io.simReq.bits
  dCache.io.simReq.get.valid := io.simReq.valid
  io.simReq.ready := dCache.io.simReq.get.ready
  io.simResp := dCache.io.simResp.get
}

/** Tests for [[Execute]] -- the stage that runs the ALU for arithmetic/branch/jump ops and drives
  * the D$ for loads/stores.
  *
  * Per the agreed plan, this spec is NOT an exhaustive per-instruction correctness check (that lives
  * in the ISA spec). It targets the stage's *contract*:
  *
  *   1. ready/valid at both boundaries, but note Execute is not a plain registered stage:
  *        - in.ready := io.req.fire || completeAlu -- a load/store is consumed the cycle the cache
  *          ACCEPTS the request; a non-memory op the cycle it RETIRES into the output register.
  *        - There is NO redirectPc *input*; the only flush is `clear` (flush = io.clear). redirectPc
  *          is an OUTPUT Execute generates.
  *   2. the single-outstanding, in-order D$ protocol (one req in flight, response held under
  *      back-pressure, alignment of addr / byte-mask / lane-aligned write data, load extension).
  *   3. global halt (FREEZE: no req issues, in-flight response held not surfaced) and clear (FLUSH:
  *      in-flight load squashed, held output dropped).
  *   4. the combinational control outputs: redirectPc/newPc, the forwarding port
  *      (regDestIdx/regDestData) including the completing-load forward, and the load-use hazard port.
  *
  * Memory-path tests poll for out.valid (latency-independent); ALU-path tests assert exact cycles
  * since their latency is a deterministic 1.
  */
class ExecuteSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Execute"

  private val maxCycles = 64

  // --- Helpers -----------------------------------------------------------------------------------

  /** Drive a ReadOperandsOutput onto the input port. Everything defaults benign; tests pass only the
    * fields they care about. pcPlus4 is set to pc+4 so passthrough is checkable.
    */
  private def driveInput(
      dut: ExecuteTestHarness,
      valid: Boolean = true,
      regFileWriteSrc: RegFileWriteSrc.Type = RegFileWriteSrc.dontCare,
      alu1stOperand: Alu1stOperand.Type = Alu1stOperand.registerValue,
      alu2ndOperand: Alu2ndOperand.Type = Alu2ndOperand.registerValue,
      aluOp: AluOp.Type = AluOp.add,
      branchIf: BranchIf.Type = BranchIf.dontCare,
      branch: Boolean = false,
      jump: Boolean = false,
      jalr: Boolean = false,
      writeToMem: Boolean = false,
      writeToReg: Boolean = false,
      memAccess: MemAccess.Type = MemAccess.dontCare,
      aluSrcA: Long = 0,
      aluSrcB: Long = 0,
      memWriteData: Long = 0,
      memAddress: Long = 0,
      jumpAddress: Long = 0,
      regDestIdx: Int = 0,
      immediate: Long = 0,
      pc: Long = 0,
      inst: Long = 0
  ): Unit = {
    val b = dut.io.in.bits
    dut.io.in.valid.poke(valid.B)
    b.control.regFileWriteSrc.poke(regFileWriteSrc)
    b.control.alu1stOperand.poke(alu1stOperand)
    b.control.alu2ndOperand.poke(alu2ndOperand)
    b.control.immEncoding.poke(ImmediateEncoding.iType)
    b.control.alu_op.poke(aluOp)
    b.control.branchIf.poke(branchIf)
    b.control.branch.poke(branch.B)
    b.control.jump.poke(jump.B)
    b.control.jalr.poke(jalr.B)
    b.control.writeToMem.poke(writeToMem.B)
    b.control.writeToReg.poke(writeToReg.B)
    b.control.memAccess.poke(memAccess)
    b.aluSrcA.poke(aluSrcA.U)
    b.aluSrcB.poke(aluSrcB.U)
    b.memWriteData.poke(memWriteData.U)
    b.memAddress.poke(memAddress.U)
    b.jumpAddress.poke(jumpAddress.U)
    b.regDestIdx.poke(regDestIdx.U)
    b.immediate.poke(immediate.U)
    b.fetch.pc.poke(pc.U)
    b.fetch.pcPlus4.poke((pc + 4).U)
    b.fetch.inst.poke(inst.U)
  }

  /** Quiescent state: no input offered, no flush/halt, sink not ready, sim port idle. */
  private def park(dut: ExecuteTestHarness): Unit = {
    dut.io.halt.poke(false.B)
    dut.io.clear.poke(false.B)
    dut.io.out.ready.poke(false.B)
    dut.io.simReq.valid.poke(false.B)
    dut.io.simReq.bits.we.poke(false.B)
    dut.io.simReq.bits.addr.poke(0.U)
    dut.io.simReq.bits.writeData.poke(0.U)
    dut.io.simReq.bits.writeMask.poke(0.U)
    driveInput(dut, valid = false)
  }

  /** Write one word into the data cache via the always-0-latency sim port. Assumes the input side is
    * parked so Execute isn't issuing requests on the main port.
    */
  private def seedMem(dut: ExecuteTestHarness, addr: Long, word: Long): Unit = {
    dut.io.simReq.valid.poke(true.B)
    dut.io.simReq.bits.we.poke(true.B)
    dut.io.simReq.bits.addr.poke(addr.U)
    dut.io.simReq.bits.writeData.poke(word.U)
    dut.io.simReq.bits.writeMask.poke(0xf.U)
    dut.clock.step()
    dut.io.simReq.valid.poke(false.B)
    dut.io.simReq.bits.we.poke(false.B)
  }

  /** Read one word back from the data cache via the sim port (one synchronous read). Assumes the
    * input side is parked. Steps the clock once.
    */
  private def readMem(dut: ExecuteTestHarness, addr: Long): BigInt = {
    dut.io.simReq.valid.poke(true.B)
    dut.io.simReq.bits.we.poke(false.B)
    dut.io.simReq.bits.addr.poke(addr.U)
    dut.clock.step()
    dut.io.simReq.valid.poke(false.B)
    dut.io.simResp.bits.peek().litValue
  }

  /** Step (without touching out.ready) until out.valid is high, or fail. Caller sets out.ready. */
  private def stepUntilOutValid(dut: ExecuteTestHarness, maxC: Int = maxCycles): Unit = {
    var i = 0
    while (dut.io.out.valid.peek().litValue == 0 && i < maxC) { dut.clock.step(); i += 1 }
    assert(dut.io.out.valid.peek().litValue == 1, s"out.valid never went high (waited $maxC cycles)")
  }

  // ===============================================================================================
  // Group A -- ready/valid stage contract for ALU (non-memory) ops. Mirrors DecodeSpec, but routed
  // through Execute's completeAlu path. NOTE: Execute only raises in.ready when there is actually a
  // fireable input present (in.ready := req.fire || completeAlu, and completeAlu needs in.valid), so
  // every "in.ready high" check below presents a valid ALU op.
  // ===============================================================================================

  // A1 ----------------------------------------------------------------------------------------------
  it should "present an accepted ALU op on out with exactly one cycle of registered latency" in {
    simulate(new ExecuteTestHarness(0)) { dut =>
      park(dut)
      driveInput(
        dut,
        writeToReg = true,
        regFileWriteSrc = RegFileWriteSrc.aluResult,
        aluOp = AluOp.add,
        aluSrcA = 5,
        aluSrcB = 7,
        regDestIdx = 3,
        pc = 0x100
      )
      dut.io.out.ready.poke(true.B)

      dut.io.in.ready.expect(true.B) // completeAlu -> ready to accept
      dut.io.out.valid.expect(false.B) // registered: nothing the same cycle

      dut.clock.step() // in fires; the output register loads

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.aluResult.expect(12.U)
      dut.io.out.bits.memResult.expect(0.U) // not meaningful for a non-memory op
      dut.io.out.bits.regDestIdx.expect(3.U)
      dut.io.out.bits.control.regFileWriteSrc.expect(RegFileWriteSrc.aluResult)
      dut.io.out.bits.fetch.pc.expect(0x100.U)
      dut.io.out.bits.fetch.pcPlus4.expect(0x104.U)
    }
  }

  // A2 ----------------------------------------------------------------------------------------------
  it should "accept and emit one ALU op per cycle when the sink is always ready" in {
    simulate(new ExecuteTestHarness(0)) { dut =>
      park(dut)
      dut.io.out.ready.poke(true.B)

      // (srcA, srcB, dest); result = srcA + srcB.
      val ops = Seq((1, 2, 5), (4, 5, 6), (7, 8, 7))
      for (i <- ops.indices) {
        val (a, b, d) = ops(i)
        driveInput(
          dut,
          writeToReg = true,
          regFileWriteSrc = RegFileWriteSrc.aluResult,
          aluSrcA = a.toLong,
          aluSrcB = b.toLong,
          regDestIdx = d,
          pc = 4L * i
        )
        dut.io.in.ready.expect(true.B)
        if (i == 0) {
          dut.io.out.valid.expect(false.B)
        } else {
          val (pa, pb, pd) = ops(i - 1)
          dut.io.out.valid.expect(true.B)
          dut.io.out.bits.aluResult.expect((pa + pb).U)
          dut.io.out.bits.regDestIdx.expect(pd.U)
        }
        dut.clock.step()
      }

      driveInput(dut, valid = false)
      val (la, lb, ld) = ops.last
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.aluResult.expect((la + lb).U)
      dut.io.out.bits.regDestIdx.expect(ld.U)
    }
  }

  // A3 ----------------------------------------------------------------------------------------------
  it should "hold its output and stall its input when the sink is not ready, then resume losslessly" in {
    simulate(new ExecuteTestHarness(0)) { dut =>
      park(dut)

      driveInput(dut, writeToReg = true, regFileWriteSrc = RegFileWriteSrc.aluResult,
        aluSrcA = 10, aluSrcB = 1, regDestIdx = 11)
      dut.io.out.ready.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.aluResult.expect(11.U)

      // Stall the sink, offer a NEW op: slot full and not draining -> refuse input.
      dut.io.out.ready.poke(false.B)
      driveInput(dut, writeToReg = true, regFileWriteSrc = RegFileWriteSrc.aluResult,
        aluSrcA = 20, aluSrcB = 2, regDestIdx = 12)
      for (_ <- 0 until 5) {
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.aluResult.expect(11.U) // held stable
        dut.io.in.ready.expect(false.B)
        dut.clock.step()
      }

      // Release: this cycle the held one fires out AND the new one is accepted (load wins).
      dut.io.out.ready.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.aluResult.expect(11.U)
      dut.clock.step()

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.aluResult.expect(22.U) // the new op, exactly once
    }
  }

  // A4 ----------------------------------------------------------------------------------------------
  it should "freeze under halt: hold the output even with the sink ready, and refuse new input" in {
    simulate(new ExecuteTestHarness(0)) { dut =>
      park(dut)

      driveInput(dut, writeToReg = true, regFileWriteSrc = RegFileWriteSrc.aluResult,
        aluSrcA = 21, aluSrcB = 0, regDestIdx = 21)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.aluResult.expect(21.U)

      dut.io.halt.poke(true.B)
      dut.io.out.ready.poke(true.B)
      driveInput(dut, writeToReg = true, regFileWriteSrc = RegFileWriteSrc.aluResult,
        aluSrcA = 22, aluSrcB = 0, regDestIdx = 22)
      for (_ <- 0 until 5) {
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.aluResult.expect(21.U) // held, not drained
        dut.io.in.ready.expect(false.B)
        dut.clock.step()
      }

      dut.io.halt.poke(false.B)
      dut.io.in.ready.expect(true.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.aluResult.expect(21.U)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.aluResult.expect(22.U)
    }
  }

  // A5 ----------------------------------------------------------------------------------------------
  it should "refuse input under halt even when its output slot is empty" in {
    simulate(new ExecuteTestHarness(0)) { dut =>
      park(dut)

      dut.io.halt.poke(true.B)
      driveInput(dut, writeToReg = true, regFileWriteSrc = RegFileWriteSrc.aluResult,
        aluSrcA = 9, aluSrcB = 0, regDestIdx = 9)
      dut.io.out.ready.poke(true.B)

      for (_ <- 0 until 4) {
        dut.io.in.ready.expect(false.B)
        dut.io.out.valid.expect(false.B)
        dut.clock.step()
      }

      dut.io.halt.poke(false.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.regDestIdx.expect(9.U)
    }
  }

  // A6 ----------------------------------------------------------------------------------------------
  it should "discard the held output under clear and resume from empty" in {
    simulate(new ExecuteTestHarness(0)) { dut =>
      park(dut)

      driveInput(dut, writeToReg = true, regFileWriteSrc = RegFileWriteSrc.aluResult,
        aluSrcA = 13, aluSrcB = 0, regDestIdx = 13)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)

      // Clear with the sink stalled, so the drop is due to flush, not a normal drain.
      dut.io.out.ready.poke(false.B)
      dut.io.clear.poke(true.B)
      dut.io.in.ready.expect(false.B)
      dut.clock.step()
      dut.io.clear.poke(false.B)

      dut.io.out.valid.expect(false.B)

      driveInput(dut, writeToReg = true, regFileWriteSrc = RegFileWriteSrc.aluResult,
        aluSrcA = 14, aluSrcB = 0, regDestIdx = 14)
      dut.io.out.ready.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.regDestIdx.expect(14.U)
    }
  }

  // A7 ----------------------------------------------------------------------------------------------
  it should "let clear win when halt and clear assert on the same cycle" in {
    simulate(new ExecuteTestHarness(0)) { dut =>
      park(dut)

      driveInput(dut, writeToReg = true, regFileWriteSrc = RegFileWriteSrc.aluResult,
        aluSrcA = 19, aluSrcB = 0, regDestIdx = 19)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)

      dut.io.halt.poke(true.B)
      dut.io.clear.poke(true.B)
      dut.io.out.ready.poke(false.B)
      dut.clock.step()
      dut.io.halt.poke(false.B)
      dut.io.clear.poke(false.B)

      dut.io.out.valid.expect(false.B) // cleared, not frozen
    }
  }

  // A8 ----------------------------------------------------------------------------------------------
  it should "gate in.ready on a fireable ALU op, halt/clear, and the drain condition" in {
    simulate(new ExecuteTestHarness(0)) { dut =>
      park(dut)

      // Empty slot, a valid ALU op offered: in.ready tracks !halt && !clear.
      driveInput(dut, writeToReg = true, regFileWriteSrc = RegFileWriteSrc.aluResult,
        aluSrcA = 1, aluSrcB = 1, regDestIdx = 1)
      dut.io.out.ready.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.io.halt.poke(true.B); dut.io.in.ready.expect(false.B); dut.io.halt.poke(false.B)
      dut.io.clear.poke(true.B); dut.io.in.ready.expect(false.B); dut.io.clear.poke(false.B)

      // No fireable input -> in.ready low (Execute only accepts something it can act on).
      driveInput(dut, valid = false)
      dut.io.in.ready.expect(false.B)

      // Fill the slot, then in.ready follows out.ready combinationally for a fresh ALU op.
      driveInput(dut, writeToReg = true, regFileWriteSrc = RegFileWriteSrc.aluResult,
        aluSrcA = 1, aluSrcB = 1, regDestIdx = 1)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)

      // Slot full, keep offering a valid op:
      dut.io.out.ready.poke(false.B); dut.io.in.ready.expect(false.B)
      dut.io.out.ready.poke(true.B); dut.io.in.ready.expect(true.B)
    }
  }

  // ===============================================================================================
  // Group B -- ALU datapath sanity (light). Enough to confirm the result lands on aluResult, the
  // mem field is the dead one, and metadata passes through. Full per-op coverage is the ISA spec.
  // ===============================================================================================

  // B1 ----------------------------------------------------------------------------------------------
  it should "route the ALU result to aluResult and pass metadata through (add and sub)" in {
    simulate(new ExecuteTestHarness(0)) { dut =>
      park(dut)
      dut.io.out.ready.poke(true.B)

      driveInput(dut, writeToReg = true, regFileWriteSrc = RegFileWriteSrc.aluResult,
        aluOp = AluOp.add, aluSrcA = 40, aluSrcB = 2, regDestIdx = 4, immediate = 0xbeefL,
        pc = 0x800, inst = 0xcafe)
      dut.clock.step()
      dut.io.out.bits.aluResult.expect(42.U)
      dut.io.out.bits.memResult.expect(0.U)
      dut.io.out.bits.regDestIdx.expect(4.U)
      dut.io.out.bits.immediate.expect(0xbeefL.U)
      dut.io.out.bits.fetch.inst.expect(0xcafe.U)

      driveInput(dut, writeToReg = true, regFileWriteSrc = RegFileWriteSrc.aluResult,
        aluOp = AluOp.sub, aluSrcA = 40, aluSrcB = 2, regDestIdx = 4)
      dut.clock.step()
      dut.io.out.bits.aluResult.expect(38.U)
    }
  }

  // ===============================================================================================
  // Group C -- memory path & D$ handshake (the core of this spec).
  // ===============================================================================================

  /** Issue a single load and check the value returned on memResult, for a given cache latency. */
  private def loadCase(latency: Int, word: Long, loadAddr: Long, acc: MemAccess.Type, expected: Long): Unit = {
    simulate(new ExecuteTestHarness(latency)) { dut =>
      park(dut)
      seedMem(dut, 0x0, word)

      driveInput(dut, regFileWriteSrc = RegFileWriteSrc.data, writeToReg = true,
        memAccess = acc, memAddress = loadAddr, regDestIdx = 5)
      dut.io.out.ready.poke(true.B)
      dut.io.reqValid.expect(true.B)
      dut.io.reqWe.expect(false.B)
      dut.io.reqAddr.expect((loadAddr & ~0x3L).U) // 4-byte aligned
      dut.io.in.ready.expect(true.B) // cache accepts the request -> input consumed

      dut.clock.step()
      driveInput(dut, valid = false) // bubble behind the issued load
      stepUntilOutValid(dut)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.memResult.expect(expected.U)
      dut.io.out.bits.regDestIdx.expect(5.U)
      dut.io.out.bits.control.regFileWriteSrc.expect(RegFileWriteSrc.data)
    }
  }

  // C1 -- word load, latency 0 and 5 (latency-independent value). ---------------------------------
  it should "load a word and present it on memResult (latency 0)" in {
    loadCase(0, 0x9abcdef0L, 0x0, MemAccess.word, 0x9abcdef0L)
  }
  it should "load a word and present it on memResult (latency 5)" in {
    loadCase(5, 0x9abcdef0L, 0x0, MemAccess.word, 0x9abcdef0L)
  }

  // C2 -- byte/half load extension + sub-word offset. Seed = 0x9ABCDEF0. ---------------------------
  it should "sign-extend a byte load" in {
    loadCase(0, 0x9abcdef0L, 0x0, MemAccess.byte, 0xfffffff0L) // 0xF0 sign-extended
  }
  it should "zero-extend a byteUnsigned load" in {
    loadCase(0, 0x9abcdef0L, 0x0, MemAccess.byteUnsigned, 0x000000f0L)
  }
  it should "select the addressed byte by sub-offset" in {
    loadCase(0, 0x9abcdef0L, 0x1, MemAccess.byte, 0xffffffdeL) // byte at offset 1 = 0xDE
  }
  it should "sign-extend a half load" in {
    loadCase(0, 0x9abcdef0L, 0x0, MemAccess.half, 0xffffdef0L) // 0xDEF0 sign-extended
  }
  it should "select and sign-extend the upper half by sub-offset" in {
    loadCase(0, 0x9abcdef0L, 0x2, MemAccess.half, 0xffff9abcL) // half at offset 2 = 0x9ABC
  }

  /** Issue a single store and verify the request shape + that the masked lanes landed in memory. */
  private def storeCase(
      acc: MemAccess.Type,
      storeAddr: Long,
      data: Long,
      expectedMask: Int,
      expectedWriteData: Long,
      seedWord: Long,
      expectedMem: Long
  ): Unit = {
    simulate(new ExecuteTestHarness(0)) { dut =>
      park(dut)
      seedMem(dut, 0x0, seedWord)

      driveInput(dut, writeToMem = true, memAccess = acc, memAddress = storeAddr, memWriteData = data)
      dut.io.out.ready.poke(true.B)
      dut.io.reqValid.expect(true.B)
      dut.io.reqWe.expect(true.B)
      dut.io.reqAddr.expect((storeAddr & ~0x3L).U)
      dut.io.reqWriteMask.expect(expectedMask.U)
      dut.io.reqWriteData.expect(expectedWriteData.U) // lane-aligned
      dut.io.in.ready.expect(true.B)

      dut.clock.step()
      driveInput(dut, valid = false)
      stepUntilOutValid(dut) // a store still produces a pipeline token
      readMem(dut, 0x0) shouldBe BigInt(expectedMem)
    }
  }

  // C3 -- store request shape (mask + lane-aligned data) and the write landing. -------------------
  it should "store a word with full mask" in {
    storeCase(MemAccess.word, 0x0, 0xdeadbeefL, 0xf, 0xdeadbeefL, 0x11223344L, 0xdeadbeefL)
  }
  it should "store a byte into the addressed lane only" in {
    // byte 0xAB to offset 1: mask 0b0010, data shifted left 8, only lane 1 changes.
    storeCase(MemAccess.byte, 0x1, 0xab, 0x2, 0xab00L, 0x11223344L, 0x1122ab44L)
  }
  it should "store a half into the upper lanes only" in {
    // half 0xBEEF to offset 2: mask 0b1100, data shifted left 16, lanes 2-3 change.
    storeCase(MemAccess.half, 0x2, 0xbeefL, 0xc, 0xbeef0000L, 0x11223344L, 0xbeef3344L)
  }

  // C4 -- single-outstanding, in order: a second load waits while one is in flight. --------------
  it should "hold a second load until the in-flight load retires (single-outstanding, in order)" in {
    simulate(new ExecuteTestHarness(5)) { dut =>
      park(dut)
      seedMem(dut, 0x0, 0xaaaaL)
      seedMem(dut, 0x4, 0xbbbbL)

      // Load A (addr 0 -> reg 5) is accepted.
      driveInput(dut, regFileWriteSrc = RegFileWriteSrc.data, writeToReg = true,
        memAccess = MemAccess.word, memAddress = 0x0, regDestIdx = 5)
      dut.io.out.ready.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()

      // Offer load B (addr 4 -> reg 6) while A is in flight: no request issues, B not accepted.
      driveInput(dut, regFileWriteSrc = RegFileWriteSrc.data, writeToReg = true,
        memAccess = MemAccess.word, memAddress = 0x4, regDestIdx = 6)
      for (_ <- 0 until 3) {
        dut.io.reqValid.expect(false.B) // cache busy with A, slot occupied
        dut.io.in.ready.expect(false.B)
        dut.io.out.valid.expect(false.B)
        dut.clock.step()
      }

      // A retires first.
      stepUntilOutValid(dut)
      dut.io.out.bits.memResult.expect(0xaaaaL.U)
      dut.io.out.bits.regDestIdx.expect(5.U)
      dut.clock.step() // consume A

      // Then B is accepted and retires.
      stepUntilOutValid(dut)
      dut.io.out.bits.memResult.expect(0xbbbbL.U)
      dut.io.out.bits.regDestIdx.expect(6.U)
    }
  }

  // C5 -- output back-pressure: a completed load's response is held in the cache, not lost. -------
  it should "hold a completed load's response under output back-pressure and deliver it losslessly" in {
    simulate(new ExecuteTestHarness(0)) { dut =>
      park(dut)
      seedMem(dut, 0x0, 0x1111L)
      seedMem(dut, 0x4, 0x2222L)

      // Load A with the sink stalled. The empty output slot still captures A's result.
      driveInput(dut, regFileWriteSrc = RegFileWriteSrc.data, writeToReg = true,
        memAccess = MemAccess.word, memAddress = 0x0, regDestIdx = 5)
      dut.io.out.ready.poke(false.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      driveInput(dut, valid = false)
      stepUntilOutValid(dut) // A now held in the output register (sink still stalled)
      dut.io.out.bits.memResult.expect(0x1111L.U)

      // Issue load B; its response cannot retire (output full) so it parks in the cache.
      driveInput(dut, regFileWriteSrc = RegFileWriteSrc.data, writeToReg = true,
        memAccess = MemAccess.word, memAddress = 0x4, regDestIdx = 6)
      dut.io.in.ready.expect(true.B) // accepted (nothing in flight)
      dut.clock.step()
      driveInput(dut, valid = false)

      // Hold a while: A stays presented, B is not lost.
      for (_ <- 0 until 5) {
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.memResult.expect(0x1111L.U)
        dut.clock.step()
      }

      // Release: A drains, then B retires with its correct value.
      dut.io.out.ready.poke(true.B)
      dut.io.out.bits.memResult.expect(0x1111L.U)
      dut.clock.step() // consume A
      stepUntilOutValid(dut)
      dut.io.out.bits.memResult.expect(0x2222L.U)
      dut.io.out.bits.regDestIdx.expect(6.U)
    }
  }

  // ===============================================================================================
  // Group D -- global halt / clear in the memory path.
  // ===============================================================================================

  // D1 -- halt gates request issue. --------------------------------------------------------------
  it should "issue no D$ request and refuse input while halted, then resume" in {
    simulate(new ExecuteTestHarness(0)) { dut =>
      park(dut)
      seedMem(dut, 0x0, 0x1234L)

      dut.io.halt.poke(true.B)
      driveInput(dut, regFileWriteSrc = RegFileWriteSrc.data, writeToReg = true,
        memAccess = MemAccess.word, memAddress = 0x0, regDestIdx = 5)
      dut.io.out.ready.poke(true.B)
      for (_ <- 0 until 4) {
        dut.io.reqValid.expect(false.B)
        dut.io.in.ready.expect(false.B)
        dut.clock.step()
      }

      dut.io.halt.poke(false.B)
      dut.io.reqValid.expect(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      driveInput(dut, valid = false)
      stepUntilOutValid(dut)
      dut.io.out.bits.memResult.expect(0x1234L.U)
    }
  }

  // D2 -- halt mid-flight: the response is held in the cache, not surfaced, until release. --------
  it should "not surface an in-flight load while halted (latency 5) and deliver it after release" in {
    simulate(new ExecuteTestHarness(5)) { dut =>
      park(dut)
      seedMem(dut, 0x0, 0x5678L)

      driveInput(dut, regFileWriteSrc = RegFileWriteSrc.data, writeToReg = true,
        memAccess = MemAccess.word, memAddress = 0x0, regDestIdx = 5)
      dut.io.out.ready.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step() // A in flight
      driveInput(dut, valid = false)
      dut.clock.step(2) // let it progress mid-stall

      // Halt longer than the cache latency: out.valid never rises; the completed response is parked.
      dut.io.halt.poke(true.B)
      for (_ <- 0 until 12) {
        dut.io.out.valid.expect(false.B)
        dut.clock.step()
      }

      dut.io.halt.poke(false.B)
      stepUntilOutValid(dut)
      dut.io.out.bits.memResult.expect(0x5678L.U)
      dut.io.out.bits.regDestIdx.expect(5.U)
    }
  }

  // D3 -- clear mid-flight squashes the in-flight load; the pipeline stays alive. ----------------
  it should "squash an in-flight load under clear (latency 5) so its result never surfaces" in {
    simulate(new ExecuteTestHarness(5)) { dut =>
      park(dut)
      seedMem(dut, 0x0, 0xdeadL)

      driveInput(dut, regFileWriteSrc = RegFileWriteSrc.data, writeToReg = true,
        memAccess = MemAccess.word, memAddress = 0x0, regDestIdx = 5)
      dut.io.out.ready.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step() // A in flight
      driveInput(dut, valid = false)
      dut.clock.step(2)

      // Clear one cycle: in-flight A is squashed.
      dut.io.clear.poke(true.B)
      dut.clock.step()
      dut.io.clear.poke(false.B)

      // A must never surface. Wait out the original latency window and then some.
      for (_ <- 0 until 10) {
        dut.io.out.valid.expect(false.B)
        dut.clock.step()
      }

      // Pipeline is alive: a fresh ALU op flows through normally.
      driveInput(dut, writeToReg = true, regFileWriteSrc = RegFileWriteSrc.aluResult,
        aluSrcA = 2, aluSrcB = 3, regDestIdx = 7)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.aluResult.expect(5.U)
      dut.io.out.bits.regDestIdx.expect(7.U)
    }
  }

  // D4 -- clear drops a held memory output. ------------------------------------------------------
  it should "discard a held load result under clear" in {
    simulate(new ExecuteTestHarness(0)) { dut =>
      park(dut)
      seedMem(dut, 0x0, 0xabcdL)

      driveInput(dut, regFileWriteSrc = RegFileWriteSrc.data, writeToReg = true,
        memAccess = MemAccess.word, memAddress = 0x0, regDestIdx = 5)
      dut.io.out.ready.poke(false.B) // never drain, so the result just sits in the output reg
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      driveInput(dut, valid = false)
      stepUntilOutValid(dut)
      dut.io.out.bits.memResult.expect(0xabcdL.U)

      dut.io.clear.poke(true.B)
      dut.clock.step()
      dut.io.clear.poke(false.B)
      dut.io.out.valid.expect(false.B) // flushed, not delivered
    }
  }

  // ===============================================================================================
  // Group E -- PC redirect output. Per the agreed scope: one taken branch, one not-taken, one jump,
  // plus the guard that redirect only fires when the instruction actually retires. Per-BranchIf
  // coverage lives in the ISA spec.
  // ===============================================================================================

  // E1 -- a taken branch retiring asserts redirectPc with newPc = jumpAddress. --------------------
  it should "assert redirectPc to jumpAddress when a taken branch retires" in {
    simulate(new ExecuteTestHarness(0)) { dut =>
      park(dut)
      // branchIf.zero with srcA==srcB (sub -> 0) makes the branch taken.
      driveInput(dut, branch = true, branchIf = BranchIf.zero, aluOp = AluOp.sub,
        aluSrcA = 5, aluSrcB = 5, jumpAddress = 0x200)
      dut.io.out.ready.poke(true.B)
      dut.io.redirectPc.expect(true.B)
      dut.io.newPc.expect(0x200.U)
    }
  }

  // E2 -- a not-taken branch does not redirect. --------------------------------------------------
  it should "not assert redirectPc when a branch is not taken" in {
    simulate(new ExecuteTestHarness(0)) { dut =>
      park(dut)
      driveInput(dut, branch = true, branchIf = BranchIf.zero, aluOp = AluOp.sub,
        aluSrcA = 5, aluSrcB = 3, jumpAddress = 0x200) // result 2 != 0 -> not taken
      dut.io.out.ready.poke(true.B)
      dut.io.redirectPc.expect(false.B)
    }
  }

  // E3 -- a jump retiring asserts redirectPc. ----------------------------------------------------
  it should "assert redirectPc to jumpAddress when a jump retires" in {
    simulate(new ExecuteTestHarness(0)) { dut =>
      park(dut)
      driveInput(dut, jump = true, writeToReg = true, regFileWriteSrc = RegFileWriteSrc.pcPlusFour,
        jumpAddress = 0x300, pc = 0x100)
      dut.io.out.ready.poke(true.B)
      dut.io.redirectPc.expect(true.B)
      dut.io.newPc.expect(0x300.U)
    }
  }

  // E4 -- no spurious redirect unless the branch/jump actually retires (completeAlu). ------------
  it should "not assert redirectPc for a halted, bubbled, or stalled taken branch, or a load/store" in {
    simulate(new ExecuteTestHarness(0)) { dut =>
      park(dut)

      def takenBranch(): Unit =
        driveInput(dut, branch = true, branchIf = BranchIf.zero, aluOp = AluOp.sub,
          aluSrcA = 5, aluSrcB = 5, jumpAddress = 0x200)

      // Halted: frozen, does not retire.
      takenBranch()
      dut.io.out.ready.poke(true.B)
      dut.io.halt.poke(true.B)
      dut.io.redirectPc.expect(false.B)
      dut.io.halt.poke(false.B)

      // Bubble (in.valid low): nothing to retire.
      driveInput(dut, valid = false)
      dut.io.redirectPc.expect(false.B)

      // Output full + sink stalled: the branch at the input can't retire this cycle.
      driveInput(dut, writeToReg = true, regFileWriteSrc = RegFileWriteSrc.aluResult,
        aluSrcA = 1, aluSrcB = 1, regDestIdx = 1)
      dut.clock.step() // fill the output register
      dut.io.out.valid.expect(true.B)
      dut.io.out.ready.poke(false.B)
      takenBranch()
      dut.io.redirectPc.expect(false.B)

      // A load never redirects regardless.
      dut.io.out.ready.poke(true.B)
      driveInput(dut, regFileWriteSrc = RegFileWriteSrc.data, writeToReg = true,
        memAccess = MemAccess.word, memAddress = 0x0, jumpAddress = 0x200, regDestIdx = 5)
      dut.io.redirectPc.expect(false.B)
    }
  }

  // ===============================================================================================
  // Group F -- forwarding port (regDestIdx / regDestData), combinational on the input instruction.
  // ===============================================================================================

  // F1 -- an ALU op at the input forwards its destination + ALU result. --------------------------
  it should "forward an ALU op's destination and result combinationally" in {
    simulate(new ExecuteTestHarness(0)) { dut =>
      park(dut)
      driveInput(dut, writeToReg = true, regFileWriteSrc = RegFileWriteSrc.aluResult,
        aluOp = AluOp.add, aluSrcA = 10, aluSrcB = 20, regDestIdx = 9)
      dut.io.regDestIdx.expect(9.U)
      dut.io.regDestData.expect(30.U)
    }
  }

  // F2 -- the forwarded value follows the writeback-source mux. -----------------------------------
  it should "forward pcPlus4 and immediate per regFileWriteSrc" in {
    simulate(new ExecuteTestHarness(0)) { dut =>
      park(dut)

      // pcPlusFour (e.g. JAL link value).
      driveInput(dut, jump = true, writeToReg = true, regFileWriteSrc = RegFileWriteSrc.pcPlusFour,
        regDestIdx = 8, pc = 0x100)
      dut.io.regDestIdx.expect(8.U)
      dut.io.regDestData.expect(0x104.U)

      // immediate (e.g. LUI).
      driveInput(dut, writeToReg = true, regFileWriteSrc = RegFileWriteSrc.immediate,
        regDestIdx = 8, immediate = 0xbeefL)
      dut.io.regDestData.expect(0xbeefL.U)
    }
  }

  // F3 -- a load at the input is NOT forwarded (its value isn't ready yet). -----------------------
  it should "not forward a load at the input (idx forced to 0)" in {
    simulate(new ExecuteTestHarness(0)) { dut =>
      park(dut)
      driveInput(dut, regFileWriteSrc = RegFileWriteSrc.data, writeToReg = true,
        memAccess = MemAccess.word, memAddress = 0x0, regDestIdx = 5)
      dut.io.regDestIdx.expect(0.U)
    }
  }

  // F4 -- an instruction that writes no register does not forward. --------------------------------
  it should "not forward when the instruction writes no register" in {
    simulate(new ExecuteTestHarness(0)) { dut =>
      park(dut)
      driveInput(dut, writeToReg = false, regFileWriteSrc = RegFileWriteSrc.aluResult,
        aluSrcA = 1, aluSrcB = 2, regDestIdx = 5)
      dut.io.regDestIdx.expect(0.U)
    }
  }

  // F5 -- a completing load forwards on the same port (memFwd). -----------------------------------
  it should "forward a completing load's destination and value" in {
    simulate(new ExecuteTestHarness(0)) { dut =>
      park(dut)
      seedMem(dut, 0x0, 0x7777L)

      driveInput(dut, regFileWriteSrc = RegFileWriteSrc.data, writeToReg = true,
        memAccess = MemAccess.word, memAddress = 0x0, regDestIdx = 5)
      dut.io.out.ready.poke(true.B)
      dut.clock.step() // load consumed; next cycle it completes
      driveInput(dut, valid = false) // EX input is a bubble on the completing cycle

      dut.io.regDestIdx.expect(5.U)
      dut.io.regDestData.expect(0x7777L.U)
    }
  }

  // F6 -- a valid forwarding op at the input outranks the completing-load forward. ----------------
  it should "prefer the EX-input forward over a completing-load forward" in {
    simulate(new ExecuteTestHarness(0)) { dut =>
      park(dut)
      seedMem(dut, 0x0, 0x7777L)

      driveInput(dut, regFileWriteSrc = RegFileWriteSrc.data, writeToReg = true,
        memAccess = MemAccess.word, memAddress = 0x0, regDestIdx = 5)
      dut.io.out.ready.poke(true.B)
      dut.clock.step() // load now completing

      // Present a valid ALU op the same cycle the load completes: its forward wins.
      driveInput(dut, writeToReg = true, regFileWriteSrc = RegFileWriteSrc.aluResult,
        aluSrcA = 1, aluSrcB = 2, regDestIdx = 8)
      dut.io.regDestIdx.expect(8.U)
      dut.io.regDestData.expect(3.U)
    }
  }

  // ===============================================================================================
  // Group G -- load-use hazard port (loadHazardDest): the destination of a load whose value cannot
  // yet be forwarded, 0 otherwise.
  // ===============================================================================================

  // G1 -- a load at the input flags its destination. ---------------------------------------------
  it should "flag the load-use hazard destination for a load at the input" in {
    simulate(new ExecuteTestHarness(0)) { dut =>
      park(dut)
      driveInput(dut, regFileWriteSrc = RegFileWriteSrc.data, writeToReg = true,
        memAccess = MemAccess.word, memAddress = 0x0, regDestIdx = 5)
      dut.io.loadHazardDest.expect(5.U)
    }
  }

  // G2 -- an in-flight (not yet completing) load keeps flagging its destination. ------------------
  it should "flag the load-use hazard destination for an in-flight load (latency 5)" in {
    simulate(new ExecuteTestHarness(5)) { dut =>
      park(dut)
      seedMem(dut, 0x0, 0x4242L)

      driveInput(dut, regFileWriteSrc = RegFileWriteSrc.data, writeToReg = true,
        memAccess = MemAccess.word, memAddress = 0x0, regDestIdx = 5)
      dut.io.out.ready.poke(true.B)
      dut.clock.step() // load now in flight
      driveInput(dut, valid = false)

      for (_ <- 0 until 3) {
        dut.io.out.valid.expect(false.B) // still in flight
        dut.io.loadHazardDest.expect(5.U)
        dut.clock.step()
      }
    }
  }

  // G3 -- on the completing cycle the hazard clears (the value is forwarded that cycle). ----------
  it should "drop the load-use hazard on the completing cycle" in {
    simulate(new ExecuteTestHarness(0)) { dut =>
      park(dut)
      seedMem(dut, 0x0, 0x7777L)

      driveInput(dut, regFileWriteSrc = RegFileWriteSrc.data, writeToReg = true,
        memAccess = MemAccess.word, memAddress = 0x0, regDestIdx = 5)
      dut.io.out.ready.poke(true.B)
      dut.clock.step() // completing cycle
      driveInput(dut, valid = false)

      dut.io.loadHazardDest.expect(0.U) // forward delivers it this cycle, so no stall needed
    }
  }

  // G4 -- a load writing x0 is not a hazard. -----------------------------------------------------
  it should "not flag a hazard for a load whose destination is x0" in {
    simulate(new ExecuteTestHarness(0)) { dut =>
      park(dut)
      driveInput(dut, regFileWriteSrc = RegFileWriteSrc.data, writeToReg = true,
        memAccess = MemAccess.word, memAddress = 0x0, regDestIdx = 0)
      dut.io.loadHazardDest.expect(0.U)
    }
  }

  // G5 -- a non-load instruction is never a load-use hazard. --------------------------------------
  it should "not flag a hazard for a non-load instruction" in {
    simulate(new ExecuteTestHarness(0)) { dut =>
      park(dut)
      driveInput(dut, writeToReg = true, regFileWriteSrc = RegFileWriteSrc.aluResult,
        aluSrcA = 1, aluSrcB = 2, regDestIdx = 5)
      dut.io.loadHazardDest.expect(0.U)
    }
  }
}
