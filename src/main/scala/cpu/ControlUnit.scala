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

// What should be written to the register file after an instruction
object RegFileWriteSrc extends ChiselEnum {
  val dontCare = Value(0.U) // reset/bubble value
  val data = Value(1.U) // value from memory
  val aluResult = Value(2.U) // result from ALU
  val pcPlusFour = Value(3.U) // pc + 4
  val immediate = Value(4.U) // imm
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
  val regFileWriteSrc = Output(RegFileWriteSrc())
  val alu2ndOperand = Output(Alu2ndOperand())
  val immEncoding = Output(ImmediateEncoding())
  val alu_op = Output(AluOp())

  val branch = Output(Bool())
  val jump = Output(Bool())

  val writeToMem = Output(Bool())
  val writeToReg = Output(Bool())
}

class ControlUnit extends Module {
  val io = IO(new Bundle {
    val inst = Input(UInt(32.W))

    val control = new ControlSignals
  })
  // format: off
  val default =
  //
  //                           regFileWriteSrc,             alu2ndOperand,                immEncoding,                  aluOp,            jump,     branch,   wToMem,   wToReg
  //
                          List(RegFileWriteSrc.data,        Alu2ndOperand.immediate,      ImmediateEncoding.sType,      AluOp.add,        false.B,  false.B,  false.B,  false.B)

  val map = Array(
    Instructions.xor ->   List(RegFileWriteSrc.aluResult,   Alu2ndOperand.registerValue,  ImmediateEncoding.dontCare,   AluOp.xor,        false.B,  false.B,  false.B,  true.B),
    Instructions.or ->    List(RegFileWriteSrc.aluResult,   Alu2ndOperand.registerValue,  ImmediateEncoding.dontCare,   AluOp.or,         false.B,  false.B,  false.B,  true.B),
    Instructions.addi ->  List(RegFileWriteSrc.aluResult,   Alu2ndOperand.immediate,      ImmediateEncoding.iType,      AluOp.add,        false.B,  false.B,  false.B,  true.B),
    Instructions.lw ->    List(RegFileWriteSrc.data,        Alu2ndOperand.immediate,      ImmediateEncoding.iType,      AluOp.add,        false.B,  false.B,  false.B,  true.B),
    Instructions.sw ->    List(RegFileWriteSrc.dontCare,    Alu2ndOperand.immediate,      ImmediateEncoding.sType,      AluOp.add,        false.B,  false.B,  true.B,   false.B),
    Instructions.beq ->   List(RegFileWriteSrc.dontCare,    Alu2ndOperand.registerValue,  ImmediateEncoding.bType,      AluOp.sub,        false.B,  true.B,   false.B,  false.B),
    Instructions.jal ->   List(RegFileWriteSrc.pcPlusFour,  Alu2ndOperand.immediate,      ImmediateEncoding.jType,      AluOp.dontCare,   true.B,   false.B,  false.B,  true.B),
    Instructions.lui ->   List(RegFileWriteSrc.immediate,   Alu2ndOperand.immediate,      ImmediateEncoding.uType,      AluOp.dontCare,   false.B,  false.B,  false.B,  true.B)
  )
  // format: on

  val signals = ListLookup(io.inst, default, map)

  io.control.regFileWriteSrc := signals(0)
  io.control.alu2ndOperand := signals(1)
  io.control.immEncoding := signals(2)
  io.control.alu_op := signals(3)
  io.control.jump := signals(4)
  io.control.branch := signals(5)
  io.control.writeToMem := signals(6)
  io.control.writeToReg := signals(7)
}
