package pit

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

object Instructions {
  // Arithmetic instructions
  // format: off
  def add =  BitPat("b0000000??????????000?????0110011")
  def sub =  BitPat("b0100000??????????000?????0110011")
  def xor =  BitPat("b0000000??????????100?????0110011")
  def or =   BitPat("b0000000??????????110?????0110011")
  def and =  BitPat("b0000000??????????111?????0110011")
  def sll =  BitPat("b0000000??????????001?????0110011")
  def srl =  BitPat("b0000000??????????101?????0110011")
  def sra =  BitPat("b0100000??????????101?????0110011")
  def slt =  BitPat("b0000000??????????010?????0110011")
  def sltu = BitPat("b0000000??????????011?????0110011")

  // Arithmetic immediate instructions
  def addi =  BitPat("b?????????????????000?????0010011")
  def xori =  BitPat("b?????????????????100?????0010011")
  def ori =   BitPat("b?????????????????110?????0010011")
  def andi =  BitPat("b?????????????????111?????0010011")
  def slli =  BitPat("b0000000??????????001?????0010011")
  def srli =  BitPat("b0000000??????????101?????0010011")
  def srai =  BitPat("b0100000??????????101?????0010011")
  def slti =  BitPat("b?????????????????010?????0010011")
  def sltiu = BitPat("b?????????????????011?????0010011")

  // Load instructions
  def lb  = BitPat("b?????????????????000?????0000011")
  def lh  = BitPat("b?????????????????001?????0000011")
  def lw  = BitPat("b?????????????????010?????0000011")
  def lbu = BitPat("b?????????????????100?????0000011")
  def lhu = BitPat("b?????????????????101?????0000011")

  // Store instructions
  def sb = BitPat("b?????????????????000?????0100011")
  def sh = BitPat("b?????????????????001?????0100011")
  def sw = BitPat("b?????????????????010?????0100011")

  // Branch instructions
  def beq =  BitPat("b?????????????????000?????1100011")
  def bne =  BitPat("b?????????????????001?????1100011")
  def blt =  BitPat("b?????????????????100?????1100011")
  def bge =  BitPat("b?????????????????101?????1100011")
  def bltu = BitPat("b?????????????????110?????1100011")
  def bgeu = BitPat("b?????????????????111?????1100011")

  // Jump and link instructions
  def jal =  BitPat("b?????????????????????????1101111")
  def jalr = BitPat("b?????????????????000?????1100111")

  // Upper immediate instructions
  def lui =   BitPat("b?????????????????????????0110111")
  def auipc = BitPat("b?????????????????????????0010111")
  // format: on
}

// What should be written to the register file after an instruction
object RegFileWriteSrc extends ChiselEnum {
  val dontCare = Value(0.U) // reset/bubble value
  val data = Value(1.U) // value from memory
  val aluResult = Value(2.U) // result from ALU
  val pcPlusFour = Value(3.U) // pc + 4
  val immediate = Value(4.U) // imm
}

// What is the 1st operand for the ALU?
object Alu1stOperand extends ChiselEnum {
  val registerValue = Value(0.U) // value from register file (RD1)
  val pc = Value(1.U) // pc (used for AUIPC)
}

// What is the 2nd operand for the ALU?
object Alu2ndOperand extends ChiselEnum {
  val registerValue = Value(0.U) // value from register file (RD2)
  val immediate = Value(1.U) // imm
}

// How we do we decode the immediate from an instruction?
object ImmediateEncoding extends ChiselEnum {
  val iType = Value(0.U) // i-type
  val sType = Value(1.U) // s-type
  val bType = Value(2.U) // b-type
  val uType = Value(3.U) // u-type
  val jType = Value(4.U) // j-type

  val dontCare = iType
}

// What type of memory load/store is this?
object MemAccess extends ChiselEnum {
  val dontCare = Value(0.U) // reset/bubble value
  val byte = Value(1.U) // load/store 1 byte, sign-extend result on load
  val half = Value(2.U) // load/store 2 bytes, sign-extend result on load
  val word = Value(3.U) // load/store 4 bytes
  val byteUnsigned = Value(5.U) // load/store 1 byte, zero-extend result on load
  val halfUnsigned = Value(6.U) // load/store 2 bytes, zero-extend result on load
}

// On what conditions from the ALU should we branch?
object BranchIf extends ChiselEnum {
  val dontCare = Value(0.U) // not a branch instruction
  val zero = Value(1.U) // branch if alu out == 0
  val notZero = Value(2.U) // branch if alu out != 0
  val neg = Value(3.U) // branch if alu val is negative
}

class ControlSignals extends Bundle {
  val regFileWriteSrc = Output(RegFileWriteSrc())
  val alu1stOperand = Output(Alu1stOperand())
  val alu2ndOperand = Output(Alu2ndOperand())
  val immEncoding = Output(ImmediateEncoding())
  val alu_op = Output(AluOp())

  val branchIf = Output(BranchIf())
  val branch = Output(Bool())
  val jump = Output(Bool())

  // If (jump && !jalr): PC += imm; if (jump && jalr): PC = imm + rs1
  val jalr = Output(Bool())

  val writeToMem = Output(Bool())
  val writeToReg = Output(Bool())

  val memAccess = Output(MemAccess())
}

class ControlUnit extends Module {
  val io = IO(new Bundle {
    val inst = Input(UInt(32.W))

    val control = new ControlSignals
  })
  // format: off
  val default =
  //
  //                           regFileWriteSrc,             alu1stOperand,                alu2ndOperand,                immEncoding,                  aluOp,            jump,     jalr,     branch,   wToMem,   wToReg,   memAccess,                branchIf
  //
                          List(RegFileWriteSrc.dontCare,    Alu1stOperand.registerValue,  Alu2ndOperand.immediate,      ImmediateEncoding.sType,      AluOp.add,        false.B,  false.B,  false.B,  false.B,  false.B,  MemAccess.dontCare,       BranchIf.dontCare)

  val map = Array(
    Instructions.add ->   List(RegFileWriteSrc.aluResult,   Alu1stOperand.registerValue,  Alu2ndOperand.registerValue,  ImmediateEncoding.dontCare,   AluOp.add,        false.B,  false.B,  false.B,  false.B,  true.B,   MemAccess.dontCare,       BranchIf.dontCare),
    Instructions.sub ->   List(RegFileWriteSrc.aluResult,   Alu1stOperand.registerValue,  Alu2ndOperand.registerValue,  ImmediateEncoding.dontCare,   AluOp.sub,        false.B,  false.B,  false.B,  false.B,  true.B,   MemAccess.dontCare,       BranchIf.dontCare),
    Instructions.xor ->   List(RegFileWriteSrc.aluResult,   Alu1stOperand.registerValue,  Alu2ndOperand.registerValue,  ImmediateEncoding.dontCare,   AluOp.xor,        false.B,  false.B,  false.B,  false.B,  true.B,   MemAccess.dontCare,       BranchIf.dontCare),
    Instructions.or ->    List(RegFileWriteSrc.aluResult,   Alu1stOperand.registerValue,  Alu2ndOperand.registerValue,  ImmediateEncoding.dontCare,   AluOp.or,         false.B,  false.B,  false.B,  false.B,  true.B,   MemAccess.dontCare,       BranchIf.dontCare),
    Instructions.and  ->  List(RegFileWriteSrc.aluResult,   Alu1stOperand.registerValue,  Alu2ndOperand.registerValue,  ImmediateEncoding.dontCare,   AluOp.and,        false.B,  false.B,  false.B,  false.B,  true.B,   MemAccess.dontCare,       BranchIf.dontCare),
    Instructions.sll  ->  List(RegFileWriteSrc.aluResult,   Alu1stOperand.registerValue,  Alu2ndOperand.registerValue,  ImmediateEncoding.dontCare,   AluOp.sll,        false.B,  false.B,  false.B,  false.B,  true.B,   MemAccess.dontCare,       BranchIf.dontCare),
    Instructions.srl  ->  List(RegFileWriteSrc.aluResult,   Alu1stOperand.registerValue,  Alu2ndOperand.registerValue,  ImmediateEncoding.dontCare,   AluOp.srl,        false.B,  false.B,  false.B,  false.B,  true.B,   MemAccess.dontCare,       BranchIf.dontCare),
    Instructions.sra  ->  List(RegFileWriteSrc.aluResult,   Alu1stOperand.registerValue,  Alu2ndOperand.registerValue,  ImmediateEncoding.dontCare,   AluOp.sra,        false.B,  false.B,  false.B,  false.B,  true.B,   MemAccess.dontCare,       BranchIf.dontCare),
    Instructions.slt  ->  List(RegFileWriteSrc.aluResult,   Alu1stOperand.registerValue,  Alu2ndOperand.registerValue,  ImmediateEncoding.dontCare,   AluOp.slt,        false.B,  false.B,  false.B,  false.B,  true.B,   MemAccess.dontCare,       BranchIf.dontCare),
    Instructions.sltu ->  List(RegFileWriteSrc.aluResult,   Alu1stOperand.registerValue,  Alu2ndOperand.registerValue,  ImmediateEncoding.dontCare,   AluOp.sltu,       false.B,  false.B,  false.B,  false.B,  true.B,   MemAccess.dontCare,       BranchIf.dontCare),
    Instructions.addi ->  List(RegFileWriteSrc.aluResult,   Alu1stOperand.registerValue,  Alu2ndOperand.immediate,      ImmediateEncoding.iType,      AluOp.add,        false.B,  false.B,  false.B,  false.B,  true.B,   MemAccess.dontCare,       BranchIf.dontCare),
    Instructions.xori ->  List(RegFileWriteSrc.aluResult,   Alu1stOperand.registerValue,  Alu2ndOperand.immediate,      ImmediateEncoding.iType,      AluOp.xor,        false.B,  false.B,  false.B,  false.B,  true.B,   MemAccess.dontCare,       BranchIf.dontCare),
    Instructions.ori ->   List(RegFileWriteSrc.aluResult,   Alu1stOperand.registerValue,  Alu2ndOperand.immediate,      ImmediateEncoding.iType,      AluOp.or,         false.B,  false.B,  false.B,  false.B,  true.B,   MemAccess.dontCare,       BranchIf.dontCare),
    Instructions.andi ->  List(RegFileWriteSrc.aluResult,   Alu1stOperand.registerValue,  Alu2ndOperand.immediate,      ImmediateEncoding.iType,      AluOp.and,        false.B,  false.B,  false.B,  false.B,  true.B,   MemAccess.dontCare,       BranchIf.dontCare),
    Instructions.slli ->  List(RegFileWriteSrc.aluResult,   Alu1stOperand.registerValue,  Alu2ndOperand.immediate,      ImmediateEncoding.iType,      AluOp.sll,        false.B,  false.B,  false.B,  false.B,  true.B,   MemAccess.dontCare,       BranchIf.dontCare),
    Instructions.srli ->  List(RegFileWriteSrc.aluResult,   Alu1stOperand.registerValue,  Alu2ndOperand.immediate,      ImmediateEncoding.iType,      AluOp.srl,        false.B,  false.B,  false.B,  false.B,  true.B,   MemAccess.dontCare,       BranchIf.dontCare),
    Instructions.srai ->  List(RegFileWriteSrc.aluResult,   Alu1stOperand.registerValue,  Alu2ndOperand.immediate,      ImmediateEncoding.iType,      AluOp.sra,        false.B,  false.B,  false.B,  false.B,  true.B,   MemAccess.dontCare,       BranchIf.dontCare),
    Instructions.slti ->  List(RegFileWriteSrc.aluResult,   Alu1stOperand.registerValue,  Alu2ndOperand.immediate,      ImmediateEncoding.iType,      AluOp.slt,        false.B,  false.B,  false.B,  false.B,  true.B,   MemAccess.dontCare,       BranchIf.dontCare),
    Instructions.sltiu -> List(RegFileWriteSrc.aluResult,   Alu1stOperand.registerValue,  Alu2ndOperand.immediate,      ImmediateEncoding.iType,      AluOp.sltu,       false.B,  false.B,  false.B,  false.B,  true.B,   MemAccess.dontCare,       BranchIf.dontCare),
    Instructions.lb ->    List(RegFileWriteSrc.data,        Alu1stOperand.registerValue,  Alu2ndOperand.immediate,      ImmediateEncoding.iType,      AluOp.add,        false.B,  false.B,  false.B,  false.B,  true.B,   MemAccess.byte,           BranchIf.dontCare),
    Instructions.lh ->    List(RegFileWriteSrc.data,        Alu1stOperand.registerValue,  Alu2ndOperand.immediate,      ImmediateEncoding.iType,      AluOp.add,        false.B,  false.B,  false.B,  false.B,  true.B,   MemAccess.half,           BranchIf.dontCare),
    Instructions.lw ->    List(RegFileWriteSrc.data,        Alu1stOperand.registerValue,  Alu2ndOperand.immediate,      ImmediateEncoding.iType,      AluOp.add,        false.B,  false.B,  false.B,  false.B,  true.B,   MemAccess.word,           BranchIf.dontCare),
    Instructions.lbu ->   List(RegFileWriteSrc.data,        Alu1stOperand.registerValue,  Alu2ndOperand.immediate,      ImmediateEncoding.iType,      AluOp.add,        false.B,  false.B,  false.B,  false.B,  true.B,   MemAccess.byteUnsigned,   BranchIf.dontCare),
    Instructions.lhu ->   List(RegFileWriteSrc.data,        Alu1stOperand.registerValue,  Alu2ndOperand.immediate,      ImmediateEncoding.iType,      AluOp.add,        false.B,  false.B,  false.B,  false.B,  true.B,   MemAccess.halfUnsigned,   BranchIf.dontCare),
    Instructions.sb ->    List(RegFileWriteSrc.dontCare,    Alu1stOperand.registerValue,  Alu2ndOperand.immediate,      ImmediateEncoding.sType,      AluOp.add,        false.B,  false.B,  false.B,  true.B,   false.B,  MemAccess.byte,           BranchIf.dontCare),
    Instructions.sh ->    List(RegFileWriteSrc.dontCare,    Alu1stOperand.registerValue,  Alu2ndOperand.immediate,      ImmediateEncoding.sType,      AluOp.add,        false.B,  false.B,  false.B,  true.B,   false.B,  MemAccess.half,           BranchIf.dontCare),
    Instructions.sw ->    List(RegFileWriteSrc.dontCare,    Alu1stOperand.registerValue,  Alu2ndOperand.immediate,      ImmediateEncoding.sType,      AluOp.add,        false.B,  false.B,  false.B,  true.B,   false.B,  MemAccess.word,           BranchIf.dontCare),
    Instructions.beq ->   List(RegFileWriteSrc.dontCare,    Alu1stOperand.registerValue,  Alu2ndOperand.registerValue,  ImmediateEncoding.bType,      AluOp.sub,        false.B,  false.B,  true.B,   false.B,  false.B,  MemAccess.dontCare,       BranchIf.zero),
    Instructions.bne ->   List(RegFileWriteSrc.dontCare,    Alu1stOperand.registerValue,  Alu2ndOperand.registerValue,  ImmediateEncoding.bType,      AluOp.sub,        false.B,  false.B,  true.B,   false.B,  false.B,  MemAccess.dontCare,       BranchIf.notZero),
    Instructions.blt ->   List(RegFileWriteSrc.dontCare,    Alu1stOperand.registerValue,  Alu2ndOperand.registerValue,  ImmediateEncoding.bType,      AluOp.slt,        false.B,  false.B,  true.B,   false.B,  false.B,  MemAccess.dontCare,       BranchIf.notZero),
    Instructions.bge ->   List(RegFileWriteSrc.dontCare,    Alu1stOperand.registerValue,  Alu2ndOperand.registerValue,  ImmediateEncoding.bType,      AluOp.slt,        false.B,  false.B,  true.B,   false.B,  false.B,  MemAccess.dontCare,       BranchIf.zero),
    Instructions.bltu ->  List(RegFileWriteSrc.dontCare,    Alu1stOperand.registerValue,  Alu2ndOperand.registerValue,  ImmediateEncoding.bType,      AluOp.sltu,       false.B,  false.B,  true.B,   false.B,  false.B,  MemAccess.dontCare,       BranchIf.notZero),
    Instructions.bgeu ->  List(RegFileWriteSrc.dontCare,    Alu1stOperand.registerValue,  Alu2ndOperand.registerValue,  ImmediateEncoding.bType,      AluOp.sltu,       false.B,  false.B,  true.B,   false.B,  false.B,  MemAccess.dontCare,       BranchIf.zero),
    Instructions.jal ->   List(RegFileWriteSrc.pcPlusFour,  Alu1stOperand.registerValue,  Alu2ndOperand.immediate,      ImmediateEncoding.jType,      AluOp.dontCare,   true.B,   false.B,  false.B,  false.B,  true.B,   MemAccess.dontCare,       BranchIf.dontCare),
    Instructions.jalr ->  List(RegFileWriteSrc.pcPlusFour,  Alu1stOperand.registerValue,  Alu2ndOperand.immediate,      ImmediateEncoding.jType,      AluOp.dontCare,   true.B,   true.B,   false.B,  false.B,  true.B,   MemAccess.dontCare,       BranchIf.dontCare),
    Instructions.lui ->   List(RegFileWriteSrc.immediate,   Alu1stOperand.registerValue,  Alu2ndOperand.immediate,      ImmediateEncoding.uType,      AluOp.dontCare,   false.B,  false.B,  false.B,  false.B,  true.B,   MemAccess.dontCare,       BranchIf.dontCare),
    Instructions.auipc -> List(RegFileWriteSrc.aluResult,   Alu1stOperand.pc,             Alu2ndOperand.immediate,      ImmediateEncoding.uType,      AluOp.add,        false.B,  false.B,  false.B,  false.B,  true.B,   MemAccess.dontCare,       BranchIf.dontCare)
  )
  // format: on

  val signals = ListLookup(io.inst, default, map)

  io.control.regFileWriteSrc := signals(0)
  io.control.alu1stOperand := signals(1)
  io.control.alu2ndOperand := signals(2)
  io.control.immEncoding := signals(3)
  io.control.alu_op := signals(4)
  io.control.jump := signals(5)
  io.control.jalr := signals(6)
  io.control.branch := signals(7)
  io.control.writeToMem := signals(8)
  io.control.writeToReg := signals(9)
  io.control.memAccess := signals(10)
  io.control.branchIf := signals(11)
}
