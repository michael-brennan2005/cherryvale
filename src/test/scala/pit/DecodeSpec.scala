package pit

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Tests for [[Decode]] -- a single-deep, registered-output pipeline stage.
  *
  * The decode *computation* (instruction -> control / immediate / reg indices) is pure combinational
  * logic and these tests barely poke at it: just enough to confirm the standard RISC-V register
  * fields are sliced out and that the carried-forward `fetch` payload (pc / pcPlus4 / inst) passes
  * through untouched. Everything else here is about the ready/valid contract at the two stage
  * boundaries.
  *
  * Contract under test:
  *   - Registered output: an instruction accepted on `in` this cycle (in.valid && in.ready) appears
  *     on `out` the NEXT cycle. out.valid is a register, never a combinational view of in.
  *   - `in.ready` is a promise to capture: it is high only when Decode will actually load the input
  *     this cycle, i.e. `!halt && !clear && (out.ready || !outValid)`. It is therefore LOW whenever
  *     the stage is frozen (halt), flushing (clear), or full and not draining.
  *   - Backpressure: while the sink holds out.ready low, out.bits/out.valid are held stable and no
  *     instruction is dropped; in.ready falls so the producer (Fetch) stalls.
  *   - Halt = FREEZE (held, not hidden): output is held exactly as-is, the drain is blocked even when
  *     out.ready is high, and no new input is accepted (even into an empty slot). Resumes cleanly.
  *   - Clear = FLUSH (discard): the held output is dropped (out.valid -> false), no input is accepted
  *     during the flush, and fetching resumes from empty afterwards.
  *   - Precedence: clear outranks halt; on the simultaneous drain+load cycle, load wins (no bubble,
  *     no duplicate).
  */
class DecodeSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Decode"

  // --- Instruction encodings ---------------------------------------------------------------------
  // Plain R-type words so rs1 (inst[19:15]) and rs2 (inst[24:20]) are both real, named fields. We
  // never check control bits, so the opcode/funct fields are irrelevant.
  private def rType(rs2: Int, rs1: Int, rd: Int): UInt =
    (((rs2 & 0x1f) << 20) | ((rs1 & 0x1f) << 15) | ((rd & 0x1f) << 7) | 0x33).U(32.W)

  private val instA = rType(rs2 = 20, rs1 = 10, rd = 1)
  private val instB = rType(rs2 = 7, rs1 = 3, rd = 2)
  private val instC = rType(rs2 = 31, rs1 = 0, rd = 15)

  // --- Helpers -----------------------------------------------------------------------------------
  private def park(dut: Decode): Unit = {
    dut.io.halt.poke(false.B)
    dut.io.clear.poke(false.B)
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.pc.poke(0.U)
    dut.io.in.bits.pcPlus4.poke(0.U)
    dut.io.in.bits.inst.poke(0.U)
    dut.io.out.ready.poke(false.B)
  }

  /** Drive a FetchOutput onto the input port. pcPlus4 is set to pc+4 so passthrough is checkable. */
  private def driveIn(dut: Decode, pc: Int, inst: UInt, valid: Boolean = true): Unit = {
    dut.io.in.valid.poke(valid.B)
    dut.io.in.bits.pc.poke(pc.U)
    dut.io.in.bits.pcPlus4.poke((pc + 4).U)
    dut.io.in.bits.inst.poke(inst)
  }

  // === 1. Datapath sanity: reg-index decode + fetch passthrough, 1-cycle registered latency =====
  it should "decode register indices and pass the fetch payload through with one cycle of latency" in {
    simulate(new Decode) { dut =>
      park(dut)

      driveIn(dut, 0x100, instA)
      dut.io.out.ready.poke(true.B)

      // Registered output: nothing is presented the same cycle the input is offered.
      dut.io.in.ready.expect(true.B) // empty slot -> ready to accept
      dut.io.out.valid.expect(false.B)

      dut.clock.step() // in fires here; the output register loads

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.reg1Idx.expect(10.U) // inst[19:15]
      dut.io.out.bits.reg2Idx.expect(20.U) // inst[24:20]
      dut.io.out.bits.fetch.pc.expect(0x100.U)
      dut.io.out.bits.fetch.pcPlus4.expect(0x104.U)
      dut.io.out.bits.fetch.inst.expect(instA)
    }
  }

  // === 2. Streaming throughput: one in / one out per cycle, in order, nothing dropped ===========
  it should "accept and emit one instruction per cycle when the sink is always ready" in {
    simulate(new Decode) { dut =>
      park(dut)
      dut.io.out.ready.poke(true.B)

      val prog = Seq(instA, instB, instC)
      for (i <- prog.indices) {
        driveIn(dut, 4 * i, prog(i))
        // Never stalls: empty or draining every cycle.
        dut.io.in.ready.expect(true.B)
        // Output lags the input by exactly one cycle.
        if (i == 0) {
          dut.io.out.valid.expect(false.B)
        } else {
          dut.io.out.valid.expect(true.B)
          dut.io.out.bits.fetch.inst.expect(prog(i - 1))
          dut.io.out.bits.fetch.pc.expect((4 * (i - 1)).U)
        }
        dut.clock.step()
      }

      // The final instruction is now presented.
      dut.io.in.valid.poke(false.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.fetch.inst.expect(prog.last)
      dut.io.out.bits.fetch.pc.expect((4 * (prog.size - 1)).U)
    }
  }

  // === 3. Backpressure: sink stall holds output, stalls input, drops nothing ====================
  it should "hold its output and stall its input when the sink is not ready, then resume losslessly" in {
    simulate(new Decode) { dut =>
      park(dut)

      // Load instA.
      driveIn(dut, 0x10, instA)
      dut.io.out.ready.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.fetch.inst.expect(instA)

      // Stall the sink and offer a NEW instruction. Slot is full and not draining -> refuse input.
      dut.io.out.ready.poke(false.B)
      driveIn(dut, 0x14, instB)
      for (_ <- 0 until 5) {
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.fetch.inst.expect(instA) // held stable
        dut.io.out.bits.fetch.pc.expect(0x10.U)
        dut.io.in.ready.expect(false.B) // upstream stalled
        dut.clock.step()
      }

      // Release the sink. This cycle instA finally fires out AND instB is accepted in (load wins).
      dut.io.out.ready.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.fetch.inst.expect(instA)
      dut.clock.step()

      // Next cycle presents instB exactly once -- no bubble, instA not re-emitted.
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.fetch.inst.expect(instB)
      dut.io.out.bits.fetch.pc.expect(0x14.U)
    }
  }

  // === 4. Halt freezes a held output: blocks the drain, refuses input ===========================
  it should "freeze under halt: hold the output even with the sink ready, and refuse new input" in {
    simulate(new Decode) { dut =>
      park(dut)

      driveIn(dut, 0x20, instA)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.fetch.inst.expect(instA)

      // Halt WITH the sink ready: freeze must shadow the drain -- the instruction is NOT consumed.
      dut.io.halt.poke(true.B)
      dut.io.out.ready.poke(true.B)
      driveIn(dut, 0x24, instB) // also offer a new instruction
      for (_ <- 0 until 5) {
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.fetch.inst.expect(instA) // held, not drained
        dut.io.in.ready.expect(false.B) // frozen -> not accepting
        dut.clock.step()
      }

      // Release halt: the still-offered instB is accepted as instA drains.
      dut.io.halt.poke(false.B)
      dut.io.in.ready.expect(true.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.fetch.inst.expect(instA)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.fetch.inst.expect(instB)
    }
  }

  // === 5. Halt refuses input even into an empty slot (the "promise to capture" rule) ============
  it should "refuse input under halt even when its output slot is empty" in {
    simulate(new Decode) { dut =>
      park(dut)

      // Output empty, halt asserted, an instruction offered with the sink ready.
      dut.io.halt.poke(true.B)
      driveIn(dut, 0x0, instA)
      dut.io.out.ready.poke(true.B)

      // Asserting in.ready here would let Fetch fire and advance, but we'd have nowhere to put the
      // instruction (frozen) -> it would be lost. So in.ready MUST stay low.
      for (_ <- 0 until 4) {
        dut.io.in.ready.expect(false.B)
        dut.io.out.valid.expect(false.B)
        dut.clock.step()
      }

      // Release: now it accepts and the instruction surfaces next cycle.
      dut.io.halt.poke(false.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.fetch.inst.expect(instA)
    }
  }

  // === 6. Clear discards the held output ========================================================
  it should "discard the held output under clear and resume from empty" in {
    simulate(new Decode) { dut =>
      park(dut)

      driveIn(dut, 0x30, instA)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)

      // Clear for one cycle with the sink stalled, so the drop is due to flush, not a normal drain.
      dut.io.out.ready.poke(false.B)
      dut.io.clear.poke(true.B)
      dut.io.in.ready.expect(false.B) // flushing -> not accepting
      dut.clock.step()
      dut.io.clear.poke(false.B)

      // The held instruction is gone (not frozen, not delivered).
      dut.io.out.valid.expect(false.B)

      // A fresh instruction flows through normally afterwards.
      driveIn(dut, 0x34, instB)
      dut.io.out.ready.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.fetch.inst.expect(instB)
    }
  }

  // === 7. Clear outranks halt ===================================================================
  it should "let clear win when halt and clear assert on the same cycle" in {
    simulate(new Decode) { dut =>
      park(dut)

      driveIn(dut, 0x40, instA)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)

      // Both asserted: discard must beat freeze. If halt won, the output would be held.
      dut.io.halt.poke(true.B)
      dut.io.clear.poke(true.B)
      dut.io.out.ready.poke(false.B)
      dut.clock.step()
      dut.io.halt.poke(false.B)
      dut.io.clear.poke(false.B)

      dut.io.out.valid.expect(false.B) // cleared, not frozen
    }
  }

  // === 8. in.ready equation: combinational in out.ready, gated by halt/clear =====================
  it should "drive in.ready as !halt && !clear && (out.ready || !outValid)" in {
    simulate(new Decode) { dut =>
      park(dut)

      // Empty slot: out.ready||!outValid == true, so in.ready tracks !halt && !clear.
      dut.io.in.ready.expect(true.B)
      dut.io.halt.poke(true.B); dut.io.in.ready.expect(false.B); dut.io.halt.poke(false.B)
      dut.io.clear.poke(true.B); dut.io.in.ready.expect(false.B); dut.io.clear.poke(false.B)

      // Fill the slot.
      driveIn(dut, 0x0, instA)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      dut.io.in.valid.poke(false.B)

      // Full slot: in.ready now follows out.ready combinationally (no clock step between pokes).
      dut.io.out.ready.poke(false.B); dut.io.in.ready.expect(false.B)
      dut.io.out.ready.poke(true.B); dut.io.in.ready.expect(true.B)
    }
  }

  // TODO once the decode logic lands and we care about it: immediate extraction per instruction
  // format (I/S/B/U/J), control-signal generation, and reg index masking for formats without rs2.
}
