package cpu

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

object Instructions {
  // Arithmetic instructions
  def xor = BitPat("b0000000??????????100?????0110011")
  def or = BitPat("b0000000??????????110?????0110011")

  // Arithmetic immediate instructions
  def addi = BitPat("b?????????????????000?????0010011")

  // Load instructions
  def lw = BitPat("b?????????????????010?????0000011")

  // Store instructions
  def sw = BitPat("b?????????????????010?????0100011")

  // Branch instructions
  def beq = BitPat("b?????????????????000?????1100011")

  // Jump and link instructions
  def jal = BitPat("b?????????????????????????1101111")

  // Upper immediate instructions
  def lui = BitPat("b?????????????????????????0110111")
}

// What the pc should be set to after an instruction
object PcSrc extends ChiselEnum {
  val branchImmediate = Value(0.U) // pc = pc + imm
  val plusFour = Value(1.U) // pc = pc + 4
}

// What should be written to the register file after an instruction
object RegFileWriteSrc extends ChiselEnum {
  val data = Value(0.U) // value from memory
  val aluResult = Value(1.U) // result from ALU
  val pcPlusFour = Value(2.U) // pc + 4
  val immediate = Value(3.U) // imm

  val dontCare = data
}

// What is the 2nd operand for the ALU? (First operand is always value RD1 from register file)
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

class ControlSignals extends Bundle {
  val pcSrc = Output(PcSrc())
  val regFileWriteSrc = Output(RegFileWriteSrc())
  val alu2ndOperand = Output(Alu2ndOperand())
  val immEncoding = Output(ImmediateEncoding())
  val alu_op = Output(AluOp())

  val writeToMem = Output(Bool())
  val writeToReg = Output(Bool())
}

class ControlUnit extends Module {
  val io = IO(new Bundle {
    val i_inst = Input(UInt(32.W))
    val i_zero = Input(Bool())

    val control = new ControlSignals
  })
  // format: off
  val default =
  //
  //                           regFileWriteSrc,             alu2ndOperand,                immEncoding,                  aluOp,            branch,   wToMem,   wToReg
  //
                          List(RegFileWriteSrc.data,        Alu2ndOperand.immediate,      ImmediateEncoding.sType,      AluOp.add,        false.B,  false.B,  false.B)

  val map = Array(
    Instructions.xor ->   List(RegFileWriteSrc.aluResult,   Alu2ndOperand.registerValue,  ImmediateEncoding.dontCare,   AluOp.xor,        false.B,  false.B,  true.B),
    Instructions.or ->    List(RegFileWriteSrc.aluResult,   Alu2ndOperand.registerValue,  ImmediateEncoding.dontCare,   AluOp.or,         false.B,  false.B,  true.B),
    Instructions.addi ->  List(RegFileWriteSrc.aluResult,   Alu2ndOperand.immediate,      ImmediateEncoding.iType,      AluOp.add,        false.B,  false.B,  true.B),
    Instructions.lw ->    List(RegFileWriteSrc.data,        Alu2ndOperand.immediate,      ImmediateEncoding.iType,      AluOp.add,        false.B,  false.B,  true.B),
    Instructions.sw ->    List(RegFileWriteSrc.dontCare,    Alu2ndOperand.immediate,      ImmediateEncoding.sType,      AluOp.add,        false.B,  true.B,   false.B),
    Instructions.beq ->   List(RegFileWriteSrc.dontCare,    Alu2ndOperand.registerValue,  ImmediateEncoding.bType,      AluOp.sub,        true.B,   false.B,  false.B),
    Instructions.jal ->   List(RegFileWriteSrc.pcPlusFour,  Alu2ndOperand.immediate,      ImmediateEncoding.jType,      AluOp.dontCare,   false.B,  false.B,  true.B),
    Instructions.lui ->   List(RegFileWriteSrc.immediate,   Alu2ndOperand.immediate,      ImmediateEncoding.uType,      AluOp.dontCare,   false.B,  false.B,  true.B)
  )
  // format: on

  val signals = ListLookup(io.i_inst, default, map)

  io.control.regFileWriteSrc := signals(0)
  io.control.alu2ndOperand := signals(1)
  io.control.immEncoding := signals(2)
  io.control.alu_op := signals(3)
  io.control.writeToMem := signals(5)
  io.control.writeToReg := signals(6)

  // TODO: ugh
  when(io.i_inst(6, 0) === "b1101111".U) {
    io.control.pcSrc := PcSrc.branchImmediate
  }.elsewhen(signals(4).asUInt.asBool & io.i_zero) {
    io.control.pcSrc := PcSrc.branchImmediate
  }.otherwise {
    io.control.pcSrc := PcSrc.plusFour
  }
}
