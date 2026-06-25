package pit

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Tests for [[ReadOperands]] -- a single-deep, registered-output pipeline stage that reads the
  * register file, computes ALU operands, and computes load/store + jump effective addresses.
  *
  * Per the test plan, the focus is NOT exhaustive operand-decode correctness. It is, in priority
  * order:
  *   1. the ready/valid stage contract at the two boundaries (identical machinery to [[Decode]]),
  *   2. the writeback commit path (regfile write port + same-cycle write->read bypass), and
  *   3. the execute-stage data-forwarding contract.
  *
  * IMPORTANT (TDD): execute forwarding (`executeRegDestIdx`/`executeRegDestData`) is not yet
  * implemented -- those inputs are declared but unused, so operands read straight from the regfile.
  * The Group D tests below assert the *intended* forwarding contract and are expected to FAIL (red)
  * until forwarding is wired up. They are the executable spec for that next step.
  *
  * Assumed forwarding contract encoded by Group D:
  *   - Forward operand N when `executeRegDestIdx =/= 0 && executeRegDestIdx === regNIdx`
  *     (idx 0 == "no producer"; reads of x0 stay 0).
  *   - Priority for a register: execute (newest) > writeback > regfile. Writeback->read is already
  *     handled by the regfile bypass, so execute forwarding muxes on top of the regfile read.
  *   - The forwarded value feeds aluSrcA (registerValue), aluSrcB (registerValue), memAddress
  *     (rs1+imm), the JALR jumpAddress (rs1+imm), and memWriteData (rs2 store data).
  *   - Forwarding is combinational on the capture cycle (the cycle `in.fire`); execute inputs on
  *     other cycles must not perturb a held output.
  */
class ReadOperandsSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "ReadOperands"

  // --- Helpers -----------------------------------------------------------------------------------

  /** Set every field of the input control bundle to a benign default. Required because the bundle is
    * a mix of ChiselEnums and Bools and the simulator needs each field driven. Individual tests
    * override the few fields they care about after calling driveIn.
    */
  private def defaultControl(dut: ReadOperands): Unit = {
    val c = dut.io.in.bits.control
    c.regFileWriteSrc.poke(RegFileWriteSrc.dontCare)
    c.alu1stOperand.poke(Alu1stOperand.registerValue)
    c.alu2ndOperand.poke(Alu2ndOperand.registerValue)
    c.immEncoding.poke(ImmediateEncoding.iType)
    c.alu_op.poke(AluOp.add)
    c.branchIf.poke(BranchIf.dontCare)
    c.branch.poke(false.B)
    c.jump.poke(false.B)
    c.jalr.poke(false.B)
    c.writeToMem.poke(false.B)
    c.writeToReg.poke(false.B)
    c.memAccess.poke(MemAccess.dontCare)
  }

  /** Quiescent state: no flow on either boundary, no flush/halt, no forwarding, no writeback. */
  private def park(dut: ReadOperands): Unit = {
    dut.io.halt.poke(false.B)
    dut.io.clear.poke(false.B)
    dut.io.redirectPc.poke(false.B)

    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.reg1Idx.poke(0.U)
    dut.io.in.bits.reg2Idx.poke(0.U)
    dut.io.in.bits.regDestIdx.poke(0.U)
    dut.io.in.bits.immediate.poke(0.U)
    dut.io.in.bits.fetch.pc.poke(0.U)
    dut.io.in.bits.fetch.pcPlus4.poke(0.U)
    dut.io.in.bits.fetch.inst.poke(0.U)
    defaultControl(dut)

    dut.io.out.ready.poke(false.B)

    dut.io.executeRegDestIdx.poke(0.U)
    dut.io.executeRegDestData.poke(0.U)
    dut.io.writebackRegDestIdx.poke(0.U)
    dut.io.writebackRegDestData.poke(0.U)
    dut.io.writebackRegDestEn.poke(false.B)

    dut.io.regSimIdx.get.poke(0.U)
  }

  /** Drive a DecodeOutput onto the input port. Control is left at whatever it currently is (park
    * installs defaults); a test pokes specific control fields after this call. pcPlus4 is set to
    * pc+4 so passthrough is checkable.
    */
  private def driveIn(
      dut: ReadOperands,
      reg1Idx: Int = 0,
      reg2Idx: Int = 0,
      regDestIdx: Int = 0,
      imm: Long = 0,
      pc: Long = 0,
      inst: Long = 0,
      valid: Boolean = true
  ): Unit = {
    dut.io.in.valid.poke(valid.B)
    dut.io.in.bits.reg1Idx.poke(reg1Idx.U)
    dut.io.in.bits.reg2Idx.poke(reg2Idx.U)
    dut.io.in.bits.regDestIdx.poke(regDestIdx.U)
    dut.io.in.bits.immediate.poke(imm.U)
    dut.io.in.bits.fetch.pc.poke(pc.U)
    dut.io.in.bits.fetch.pcPlus4.poke((pc + 4).U)
    dut.io.in.bits.fetch.inst.poke(inst.U)
  }

  /** Commit a value to a register through the writeback port (one cycle). Assumes the input side is
    * parked so nothing is captured into the output register meanwhile.
    */
  private def commitReg(dut: ReadOperands, idx: Int, data: Long): Unit = {
    dut.io.writebackRegDestIdx.poke(idx.U)
    dut.io.writebackRegDestData.poke(data.U)
    dut.io.writebackRegDestEn.poke(true.B)
    dut.clock.step()
    dut.io.writebackRegDestEn.poke(false.B)
    dut.io.writebackRegDestIdx.poke(0.U)
    dut.io.writebackRegDestData.poke(0.U)
  }

  /** Combinationally read a register via the sim debug port. */
  private def readSimReg(dut: ReadOperands, idx: Int): BigInt = {
    dut.io.regSimIdx.get.poke(idx.U)
    dut.io.regSimData.get.peek().litValue
  }

  private def dut() = new ReadOperands(exposeSimPorts = true)

  // ===============================================================================================
  // Group A -- ready/valid stage contract (mirrors DecodeSpec; the output-reg + in.ready logic is
  // identical). We track instruction identity through the carried `regDestIdx` / `fetch.pc` fields.
  // ===============================================================================================

  // A1 ----------------------------------------------------------------------------------------------
  it should "present an accepted instruction on out with exactly one cycle of registered latency" in {
    simulate(dut()) { dut =>
      park(dut)
      driveIn(dut, reg1Idx = 1, regDestIdx = 7, pc = 0x100)
      dut.io.out.ready.poke(true.B)

      // Registered output: nothing presented the same cycle the input is offered.
      dut.io.in.ready.expect(true.B)
      dut.io.out.valid.expect(false.B)

      dut.clock.step() // in fires; the output register loads

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.regDestIdx.expect(7.U)
      dut.io.out.bits.fetch.pc.expect(0x100.U)
    }
  }

  // A2 ----------------------------------------------------------------------------------------------
  it should "accept and emit one instruction per cycle when the sink is always ready" in {
    simulate(dut()) { dut =>
      park(dut)
      dut.io.out.ready.poke(true.B)

      val dests = Seq(5, 6, 7)
      for (i <- dests.indices) {
        driveIn(dut, regDestIdx = dests(i), pc = 4 * i)
        dut.io.in.ready.expect(true.B)
        if (i == 0) {
          dut.io.out.valid.expect(false.B)
        } else {
          dut.io.out.valid.expect(true.B)
          dut.io.out.bits.regDestIdx.expect(dests(i - 1).U)
          dut.io.out.bits.fetch.pc.expect((4 * (i - 1)).U)
        }
        dut.clock.step()
      }

      dut.io.in.valid.poke(false.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.regDestIdx.expect(dests.last.U)
    }
  }

  // A3 ----------------------------------------------------------------------------------------------
  it should "hold its output and stall its input when the sink is not ready, then resume losslessly" in {
    simulate(dut()) { dut =>
      park(dut)

      driveIn(dut, regDestIdx = 11, pc = 0x10)
      dut.io.out.ready.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.regDestIdx.expect(11.U)

      // Stall the sink, offer a NEW instruction: slot full and not draining -> refuse input.
      dut.io.out.ready.poke(false.B)
      driveIn(dut, regDestIdx = 12, pc = 0x14)
      for (_ <- 0 until 5) {
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.regDestIdx.expect(11.U) // held stable
        dut.io.in.ready.expect(false.B)
        dut.clock.step()
      }

      // Release: this cycle the held one fires out AND the new one is accepted (load wins).
      dut.io.out.ready.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.regDestIdx.expect(11.U)
      dut.clock.step()

      // Next cycle presents the new one exactly once -- no bubble, no duplicate.
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.regDestIdx.expect(12.U)
    }
  }

  // A4 ----------------------------------------------------------------------------------------------
  it should "freeze under halt: hold the output even with the sink ready, and refuse new input" in {
    simulate(dut()) { dut =>
      park(dut)

      driveIn(dut, regDestIdx = 21, pc = 0x20)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.regDestIdx.expect(21.U)

      dut.io.halt.poke(true.B)
      dut.io.out.ready.poke(true.B)
      driveIn(dut, regDestIdx = 22, pc = 0x24)
      for (_ <- 0 until 5) {
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.regDestIdx.expect(21.U) // held, not drained
        dut.io.in.ready.expect(false.B)
        dut.clock.step()
      }

      dut.io.halt.poke(false.B)
      dut.io.in.ready.expect(true.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.regDestIdx.expect(21.U)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.regDestIdx.expect(22.U)
    }
  }

  // A5 ----------------------------------------------------------------------------------------------
  it should "refuse input under halt even when its output slot is empty" in {
    simulate(dut()) { dut =>
      park(dut)

      dut.io.halt.poke(true.B)
      driveIn(dut, regDestIdx = 9, pc = 0x0)
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
    simulate(dut()) { dut =>
      park(dut)

      driveIn(dut, regDestIdx = 13, pc = 0x30)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)

      dut.io.out.ready.poke(false.B)
      dut.io.clear.poke(true.B)
      dut.io.in.ready.expect(false.B)
      dut.clock.step()
      dut.io.clear.poke(false.B)

      dut.io.out.valid.expect(false.B)

      driveIn(dut, regDestIdx = 14, pc = 0x34)
      dut.io.out.ready.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.regDestIdx.expect(14.U)
    }
  }

  // A7 ----------------------------------------------------------------------------------------------
  it should "discard the held output under redirectPc and resume from empty" in {
    simulate(dut()) { dut =>
      park(dut)

      driveIn(dut, regDestIdx = 13, pc = 0x30)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)

      dut.io.out.ready.poke(false.B)
      dut.io.redirectPc.poke(true.B)
      dut.io.in.ready.expect(false.B)
      dut.clock.step()
      dut.io.redirectPc.poke(false.B)

      dut.io.out.valid.expect(false.B)

      driveIn(dut, regDestIdx = 14, pc = 0x34)
      dut.io.out.ready.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.regDestIdx.expect(14.U)
    }
  }

  // A8 ----------------------------------------------------------------------------------------------
  it should "refuse new input during a redirectPc flush so a wrong-path instruction is never latched" in {
    simulate(dut()) { dut =>
      park(dut)

      dut.io.redirectPc.poke(true.B)
      driveIn(dut, regDestIdx = 31, pc = 0x50)
      dut.io.out.ready.poke(true.B)
      dut.io.in.ready.expect(false.B)
      dut.clock.step()

      dut.io.out.valid.expect(false.B)

      dut.io.redirectPc.poke(false.B)
      driveIn(dut, regDestIdx = 30, pc = 0x54)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.regDestIdx.expect(30.U)
    }
  }

  // A9 ----------------------------------------------------------------------------------------------
  it should "let flush win when halt and redirectPc assert on the same cycle" in {
    simulate(dut()) { dut =>
      park(dut)

      driveIn(dut, regDestIdx = 19, pc = 0x40)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)

      dut.io.halt.poke(true.B)
      dut.io.redirectPc.poke(true.B)
      dut.io.out.ready.poke(false.B)
      dut.clock.step()
      dut.io.halt.poke(false.B)
      dut.io.redirectPc.poke(false.B)

      dut.io.out.valid.expect(false.B) // flushed, not frozen
    }
  }

  // A10 ---------------------------------------------------------------------------------------------
  it should "drive in.ready as !halt && !clear && !redirectPc && (out.ready || !outValid)" in {
    simulate(dut()) { dut =>
      park(dut)

      // Empty slot: in.ready tracks !halt && !clear && !redirectPc.
      dut.io.in.ready.expect(true.B)
      dut.io.halt.poke(true.B); dut.io.in.ready.expect(false.B); dut.io.halt.poke(false.B)
      dut.io.clear.poke(true.B); dut.io.in.ready.expect(false.B); dut.io.clear.poke(false.B)
      dut.io.redirectPc.poke(true.B); dut.io.in.ready.expect(false.B); dut.io.redirectPc.poke(false.B)

      // Fill the slot.
      driveIn(dut, regDestIdx = 1)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      dut.io.in.valid.poke(false.B)

      // Full slot: in.ready follows out.ready combinationally.
      dut.io.out.ready.poke(false.B); dut.io.in.ready.expect(false.B)
      dut.io.out.ready.poke(true.B); dut.io.in.ready.expect(true.B)

      // ...but a redirect still forces it low regardless of out.ready.
      dut.io.redirectPc.poke(true.B); dut.io.in.ready.expect(false.B); dut.io.redirectPc.poke(false.B)
    }
  }

  // ===============================================================================================
  // Group B -- operand computation sanity (light; scaffolds the forwarding tests). With no execute
  // forwarding active (executeRegDestIdx == 0), operands reflect raw register-file reads.
  // ===============================================================================================

  // B1 ----------------------------------------------------------------------------------------------
  it should "select aluSrcA from the register value or pc per control.alu1stOperand" in {
    simulate(dut()) { dut =>
      park(dut)
      commitReg(dut, idx = 10, data = 100)

      // registerValue (default)
      driveIn(dut, reg1Idx = 10, pc = 0x200)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.bits.aluSrcA.expect(100.U)

      // pc
      driveIn(dut, reg1Idx = 10, pc = 0x200)
      dut.io.in.bits.control.alu1stOperand.poke(Alu1stOperand.pc)
      dut.clock.step()
      dut.io.out.bits.aluSrcA.expect(0x200.U)
    }
  }

  // B2 ----------------------------------------------------------------------------------------------
  it should "select aluSrcB from the register value or immediate per control.alu2ndOperand" in {
    simulate(dut()) { dut =>
      park(dut)
      commitReg(dut, idx = 20, data = 7)

      // registerValue (default)
      driveIn(dut, reg2Idx = 20, imm = 0x40)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.bits.aluSrcB.expect(7.U)

      // immediate
      driveIn(dut, reg2Idx = 20, imm = 0x40)
      dut.io.in.bits.control.alu2ndOperand.poke(Alu2ndOperand.immediate)
      dut.clock.step()
      dut.io.out.bits.aluSrcB.expect(0x40.U)
    }
  }

  // B3 ----------------------------------------------------------------------------------------------
  it should "compute memAddress as rs1 + immediate" in {
    simulate(dut()) { dut =>
      park(dut)
      commitReg(dut, idx = 10, data = 100)

      driveIn(dut, reg1Idx = 10, imm = 0x40)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.bits.memAddress.expect((100 + 0x40).U)
    }
  }

  // B4 ----------------------------------------------------------------------------------------------
  it should "compute jumpAddress as pc+imm for JAL/branch and rs1+imm for JALR" in {
    simulate(dut()) { dut =>
      park(dut)
      commitReg(dut, idx = 10, data = 0x1000)

      // JAL/branch: pc + imm
      driveIn(dut, reg1Idx = 10, imm = 0x20, pc = 0x400)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.bits.jumpAddress.expect((0x400 + 0x20).U)

      // JALR: rs1 + imm
      driveIn(dut, reg1Idx = 10, imm = 0x20, pc = 0x400)
      dut.io.in.bits.control.jalr.poke(true.B)
      dut.clock.step()
      dut.io.out.bits.jumpAddress.expect((0x1000 + 0x20).U)
    }
  }

  // B5 ----------------------------------------------------------------------------------------------
  it should "pass regDestIdx, control, and the fetch payload through to out" in {
    simulate(dut()) { dut =>
      park(dut)

      driveIn(dut, regDestIdx = 17, pc = 0x800, inst = 0xdeadbeefL)
      dut.io.in.bits.control.writeToReg.poke(true.B)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()

      dut.io.out.bits.regDestIdx.expect(17.U)
      dut.io.out.bits.control.writeToReg.expect(true.B)
      dut.io.out.bits.fetch.pc.expect(0x800.U)
      dut.io.out.bits.fetch.pcPlus4.expect(0x804.U)
      dut.io.out.bits.fetch.inst.expect(0xdeadbeefL.U)
    }
  }

  // B6 ----------------------------------------------------------------------------------------------
  it should "read x0 as zero in operands" in {
    simulate(dut()) { dut =>
      park(dut)

      driveIn(dut, reg1Idx = 0, imm = 0x40)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.bits.aluSrcA.expect(0.U)
      dut.io.out.bits.memAddress.expect(0x40.U) // 0 + imm
    }
  }

  // B7 ----------------------------------------------------------------------------------------------
  // For a store, the value written to memory is the rs2 register value. memWriteData carries it.
  it should "carry the rs2 register value as memWriteData (store data)" in {
    simulate(dut()) { dut =>
      park(dut)
      commitReg(dut, idx = 20, data = 0xcafe)

      // memWriteData is rs2 independent of the ALU operand selects, so flip those to prove it.
      driveIn(dut, reg1Idx = 10, reg2Idx = 20, imm = 0x40)
      dut.io.in.bits.control.alu2ndOperand.poke(Alu2ndOperand.immediate)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.bits.memWriteData.expect(0xcafe.U)
    }
  }

  // ===============================================================================================
  // Group C -- writeback commit + same-cycle bypass (implemented; should pass).
  // ===============================================================================================

  // C1 ----------------------------------------------------------------------------------------------
  it should "commit a writeback to the register file so a later read sees it" in {
    simulate(dut()) { dut =>
      park(dut)
      commitReg(dut, idx = 12, data = 0xabcd)

      readSimReg(dut, 12) shouldBe BigInt(0xabcd)

      driveIn(dut, reg1Idx = 12)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.bits.aluSrcA.expect(0xabcd.U)
    }
  }

  // C2 ----------------------------------------------------------------------------------------------
  it should "not commit when writebackRegDestEn is low" in {
    simulate(dut()) { dut =>
      park(dut)
      commitReg(dut, idx = 7, data = 0x1111)

      // Drive the write port with new data but enable LOW.
      dut.io.writebackRegDestIdx.poke(7.U)
      dut.io.writebackRegDestData.poke(0x2222.U)
      dut.io.writebackRegDestEn.poke(false.B)
      dut.clock.step()

      readSimReg(dut, 7) shouldBe BigInt(0x1111)
    }
  }

  // C3 ----------------------------------------------------------------------------------------------
  it should "ignore a writeback to x0" in {
    simulate(dut()) { dut =>
      park(dut)
      commitReg(dut, idx = 0, data = 0x9999)
      readSimReg(dut, 0) shouldBe BigInt(0)
    }
  }

  // C4 ----------------------------------------------------------------------------------------------
  it should "bypass a same-cycle writeback into the operand read (write->read bypass)" in {
    simulate(dut()) { dut =>
      park(dut)

      // Capture an instruction reading reg 15 on the SAME cycle a writeback commits reg 15.
      driveIn(dut, reg1Idx = 15)
      dut.io.writebackRegDestIdx.poke(15.U)
      dut.io.writebackRegDestData.poke(0x7777.U)
      dut.io.writebackRegDestEn.poke(true.B)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()

      dut.io.out.bits.aluSrcA.expect(0x7777.U) // saw the in-flight writeback, not the stale 0
    }
  }

  // C5 ----------------------------------------------------------------------------------------------
  it should "expose committed register state on the sim read port" in {
    simulate(dut()) { dut =>
      park(dut)
      commitReg(dut, idx = 3, data = 0x33)
      commitReg(dut, idx = 4, data = 0x44)

      readSimReg(dut, 3) shouldBe BigInt(0x33)
      readSimReg(dut, 4) shouldBe BigInt(0x44)
      readSimReg(dut, 5) shouldBe BigInt(0) // untouched
    }
  }

  // ===============================================================================================
  // Group D -- execute-stage data forwarding (TDD). These encode the intended contract. The cases
  // that require an actual forwarding mux (D1, D2, D5, D6) FAIL until forwarding is implemented; the
  // "must-not-forward" guards (D3, D4) and the capture-cycle guard (D7) pass already and protect
  // against over-forwarding once it lands.
  // ===============================================================================================

  // D1 ----------------------------------------------------------------------------------------------
  it should "forward an execute result for rs1 into aluSrcA and memAddress" in {
    simulate(dut()) { dut =>
      park(dut)
      commitReg(dut, idx = 10, data = 0x1111) // stale regfile value

      driveIn(dut, reg1Idx = 10, imm = 0x8)
      dut.io.executeRegDestIdx.poke(10.U)
      dut.io.executeRegDestData.poke(0x2222.U)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()

      dut.io.out.bits.aluSrcA.expect(0x2222.U)
      dut.io.out.bits.memAddress.expect((0x2222 + 0x8).U)
    }
  }

  // D2 ----------------------------------------------------------------------------------------------
  it should "forward an execute result for rs2 into aluSrcB" in {
    simulate(dut()) { dut =>
      park(dut)
      commitReg(dut, idx = 20, data = 0x3333) // stale regfile value

      driveIn(dut, reg2Idx = 20)
      dut.io.executeRegDestIdx.poke(20.U)
      dut.io.executeRegDestData.poke(0x4444.U)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()

      dut.io.out.bits.aluSrcB.expect(0x4444.U)
    }
  }

  // D3 ----------------------------------------------------------------------------------------------
  it should "not forward when the execute destination does not match the source register" in {
    simulate(dut()) { dut =>
      park(dut)
      commitReg(dut, idx = 10, data = 0x1111)

      driveIn(dut, reg1Idx = 10)
      dut.io.executeRegDestIdx.poke(11.U) // mismatch
      dut.io.executeRegDestData.poke(0x2222.U)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()

      dut.io.out.bits.aluSrcA.expect(0x1111.U) // regfile value, no forward
    }
  }

  // D4 ----------------------------------------------------------------------------------------------
  // An execute destination of x0 means "this producer writes no register" -- it must NOT be
  // forwarded. The dangerous case is when the consumer is also reading x0: a naive
  // `executeRegDestIdx === reg1Idx` match (0 === 0) would forward the bus data over the hardwired 0.
  it should "not forward an x0 execute destination onto an x0 operand read" in {
    simulate(dut()) { dut =>
      park(dut)

      driveIn(dut, reg1Idx = 0) // reading x0 -> must stay 0
      dut.io.executeRegDestIdx.poke(0.U) // x0 == "no register written"
      dut.io.executeRegDestData.poke(0x2222.U)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()

      dut.io.out.bits.aluSrcA.expect(0.U) // x0 reads 0, never the forwarded bus value
    }
  }

  // D5 ----------------------------------------------------------------------------------------------
  it should "prefer the execute forward over a same-cycle writeback for the same register" in {
    simulate(dut()) { dut =>
      park(dut)
      commitReg(dut, idx = 8, data = 0x1111) // regfile value

      driveIn(dut, reg1Idx = 8)
      // Writeback would bypass 0x2222 via the regfile; execute (newer) must win with 0x3333.
      dut.io.writebackRegDestIdx.poke(8.U)
      dut.io.writebackRegDestData.poke(0x2222.U)
      dut.io.writebackRegDestEn.poke(true.B)
      dut.io.executeRegDestIdx.poke(8.U)
      dut.io.executeRegDestData.poke(0x3333.U)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()

      dut.io.out.bits.aluSrcA.expect(0x3333.U)
    }
  }

  // D6 ----------------------------------------------------------------------------------------------
  it should "forward an execute rs1 result into the JALR jump address" in {
    simulate(dut()) { dut =>
      park(dut)
      commitReg(dut, idx = 10, data = 0x1111)

      driveIn(dut, reg1Idx = 10, imm = 0x40)
      dut.io.in.bits.control.jalr.poke(true.B)
      dut.io.executeRegDestIdx.poke(10.U)
      dut.io.executeRegDestData.poke(0x2000.U)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()

      dut.io.out.bits.jumpAddress.expect((0x2000 + 0x40).U)
    }
  }

  // D7 ----------------------------------------------------------------------------------------------
  it should "not let execute inputs on a non-capture cycle perturb a held output" in {
    simulate(dut()) { dut =>
      park(dut)
      commitReg(dut, idx = 10, data = 0x1111)

      // Capture an instruction with no forwarding active.
      driveIn(dut, reg1Idx = 10)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.bits.aluSrcA.expect(0x1111.U)

      // Stop accepting input, hold the output, and wiggle execute inputs that "match" reg 10.
      dut.io.in.valid.poke(false.B)
      dut.io.out.ready.poke(false.B)
      dut.io.executeRegDestIdx.poke(10.U)
      dut.io.executeRegDestData.poke(0x9999.U)
      dut.clock.step()

      dut.io.out.bits.aluSrcA.expect(0x1111.U) // unchanged; forwarding is a capture-cycle effect
    }
  }

  // D8 ----------------------------------------------------------------------------------------------
  // Store data is rs2, so it must see execute forwarding too (a store right after the instruction
  // that produces its source register).
  it should "forward an execute result for rs2 into memWriteData" in {
    simulate(dut()) { dut =>
      park(dut)
      commitReg(dut, idx = 20, data = 0x3333) // stale regfile value

      driveIn(dut, reg2Idx = 20)
      dut.io.executeRegDestIdx.poke(20.U)
      dut.io.executeRegDestData.poke(0x4444.U)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()

      dut.io.out.bits.memWriteData.expect(0x4444.U)
    }
  }
}
