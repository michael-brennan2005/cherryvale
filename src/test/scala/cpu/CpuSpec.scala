package cpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CpuSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Cpu"

  // ---- Instruction encoders ---------------------------------------------------
  //
  // Encodings produce the exact bit pattern the current ControlUnit / DataPath
  // expects, which is *almost* standard RISC-V. The one place we diverge is BEQ:
  // ControlUnit decodes the B-type immediate as
  //   imm_ext = Cat(inst[31], inst[7], inst[30:25], inst[11:8])
  // and uses it as a raw byte offset (no LSB-0 shift), so we pack the desired
  // byte offset directly into those 12 bits without the spec's *2 shift.

  private def lw(rd: Int, imm: Int, rs1: Int): BigInt = {
    val i = BigInt(imm & 0xFFF)
    (i << 20) | (BigInt(rs1) << 15) | (BigInt(2) << 12) | (BigInt(rd) << 7) | BigInt(0x03)
  }

  private def sw(rs2: Int, imm: Int, rs1: Int): BigInt = {
    val immHi = BigInt((imm >> 5) & 0x7F)
    val immLo = BigInt(imm & 0x1F)
    (immHi << 25) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) |
      (BigInt(2) << 12) | (immLo << 7) | BigInt(0x23)
  }

  private def or_(rd: Int, rs1: Int, rs2: Int): BigInt = {
    (BigInt(0) << 25) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) |
      (BigInt(6) << 12) | (BigInt(rd) << 7) | BigInt(0x33)
  }

  private def beq(rs1: Int, rs2: Int, byteOffset: Int): BigInt = {
    val o = byteOffset & 0xFFF
    val bit11   = BigInt((o >> 11) & 0x1)
    val bit10   = BigInt((o >> 10) & 0x1)
    val bits9_4 = BigInt((o >> 4)  & 0x3F)
    val bits3_0 = BigInt(o & 0xF)
    (bit11 << 31) | (bits9_4 << 25) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) |
      (BigInt(0) << 12) | (bits3_0 << 8) | (bit10 << 7) | BigInt(0x63)
  }

  // ---- Test fixture helpers --------------------------------------------------

  private def writeMem(dut: Cpu, addr: Int, value: BigInt): Unit = {
    dut.io.mem_debug.addr.poke(addr.U)
    dut.io.mem_debug.w_data.poke(value.U)
    dut.io.mem_debug.w_en.poke(true.B)
    dut.clock.step()
    dut.io.mem_debug.w_en.poke(false.B)
  }

  private def readMem(dut: Cpu, addr: Int): BigInt = {
    dut.io.mem_debug.w_en.poke(false.B)
    dut.io.mem_debug.addr.poke(addr.U)
    dut.io.mem_debug.data.peek().litValue
  }

  private def readReg(dut: Cpu, rIdx: Int): BigInt = {
    dut.io.reg_debug_addr.poke(rIdx.U)
    dut.io.reg_debug_data.peek().litValue
  }

  /** Hold reset, write program words at addrs 0,4,8,…, write any additional data
    * words via `data` (addr → value), then release reset and step `cycles` times,
    * then re-assert reset to halt for inspection. */
  private def runProgram(
      dut: Cpu,
      program: Seq[BigInt],
      data: Seq[(Int, BigInt)],
      cycles: Int
  ): Unit = {
    dut.reset.poke(true.B)
    for ((inst, idx) <- program.zipWithIndex) writeMem(dut, idx * 4, inst)
    for ((addr, value) <- data) writeMem(dut, addr, value)
    dut.io.mem_debug.w_en.poke(false.B)
    dut.reset.poke(false.B)
    dut.clock.step(cycles)
    dut.reset.poke(true.B)
  }

  // ---- LW ---------------------------------------------------------------------

  it should "load a word into a register (lw)" in {
    simulate(new Cpu) { dut =>
      runProgram(
        dut,
        program = Seq(
          lw(rd = 1, imm = 0x80, rs1 = 0) // x1 <- mem[0x80]
        ),
        data = Seq(0x80 -> BigInt("DEADBEEF", 16)),
        cycles = 1
      )
      readReg(dut, 1) shouldBe BigInt("DEADBEEF", 16)
    }
  }

  // ---- SW ---------------------------------------------------------------------

  it should "store a register value to memory (sw)" in {
    simulate(new Cpu) { dut =>
      runProgram(
        dut,
        program = Seq(
          sw(rs2 = 0, imm = 0x80, rs1 = 0) // mem[0x80] <- x0 (= 0)
        ),
        data = Seq(0x80 -> BigInt("FFFFFFFF", 16)), // sentinel: proves write happened
        cycles = 1
      )
      readMem(dut, 0x80) shouldBe BigInt(0)
    }
  }

  // ---- OR ---------------------------------------------------------------------

  it should "compute bitwise OR of two registers (or)" in {
    simulate(new Cpu) { dut =>
      runProgram(
        dut,
        program = Seq(
          lw(rd = 1, imm = 0x80, rs1 = 0),       // x1 <- 0xF0F0F0F0
          lw(rd = 2, imm = 0x84, rs1 = 0),       // x2 <- 0x0F0F0F0F
          or_(rd = 3, rs1 = 1, rs2 = 2)          // x3 <- x1 | x2
        ),
        data = Seq(
          0x80 -> BigInt("F0F0F0F0", 16),
          0x84 -> BigInt("0F0F0F0F", 16)
        ),
        cycles = 3
      )
      readReg(dut, 3) shouldBe BigInt("FFFFFFFF", 16)
    }
  }

  // ---- BEQ (taken) ------------------------------------------------------------

  it should "branch when operands are equal (beq taken)" in {
    simulate(new Cpu) { dut =>
      runProgram(
        dut,
        program = Seq(
          lw(rd = 1, imm = 0x80, rs1 = 0),       // x1 <- 0xCAFE
          beq(rs1 = 0, rs2 = 0, byteOffset = 8), // x0 == x0, jump PC+8 → addr 12
          lw(rd = 1, imm = 0x84, rs1 = 0),       // SKIPPED: would set x1 = 0xBEEF
          sw(rs2 = 0, imm = 0x8C, rs1 = 0)       // safe landing pad at addr 12
        ),
        data = Seq(
          0x80 -> BigInt("CAFE", 16),
          0x84 -> BigInt("BEEF", 16)
        ),
        cycles = 3
      )
      readReg(dut, 1) shouldBe BigInt("CAFE", 16)
    }
  }

  // ---- BEQ (not taken) --------------------------------------------------------

  it should "fall through when operands differ (beq not taken)" in {
    simulate(new Cpu) { dut =>
      runProgram(
        dut,
        program = Seq(
          lw(rd = 1, imm = 0x80, rs1 = 0),       // x1 <- 0xCAFE
          lw(rd = 2, imm = 0x84, rs1 = 0),       // x2 <- 0xBEEF
          beq(rs1 = 1, rs2 = 2, byteOffset = 8), // x1 != x2, NOT taken
          lw(rd = 1, imm = 0x88, rs1 = 0)        // x1 <- 0x1234 (should run)
        ),
        data = Seq(
          0x80 -> BigInt("CAFE", 16),
          0x84 -> BigInt("BEEF", 16),
          0x88 -> BigInt("1234", 16)
        ),
        cycles = 4
      )
      readReg(dut, 1) shouldBe BigInt("1234", 16)
    }
  }
}
