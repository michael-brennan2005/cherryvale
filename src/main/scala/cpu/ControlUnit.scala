package cpu

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class ControlSignals extends Bundle {
  // Where does the next PC come from? True => branching instruction, may be pc + literal, False => pc + 4
  val pc_src = Output(Bool())

  // What is written to register file? True => value from data memory, False => ALU result
  val result_src = Output(Bool())

  // Should we write to data memory on this instruction?
  val mem_write = Output(Bool())

  // What is 2nd operand of ALU? True => immediate from instruction, False => register value
  val alu_src = Output(Bool())

  // How do we decode the immediate from the instruction? Varies depending on instruction type
  // TODO: should this be put under an enum? Or more sensible literals rn, why are we using 0, 3, 2
  val imm_src = Output(UInt(2.W))

  // Should we write to register on this instruction?
  val reg_write = Output(Bool())

  // What operation should the ALU perform?
  val alu_op = Output(UInt(3.W))
}

class ControlUnit extends Module {
  val io = IO(new Bundle {
    val i_inst = Input(UInt(32.W))
    val i_zero = Input(Bool())

    val control = new ControlSignals
  })

  // For debugging
  val current_inst = io.i_inst
  dontTouch(current_inst)

  // Defaults to make chisel happy
  io.control.result_src := false.B
  io.control.mem_write := false.B
  io.control.alu_src := false.B
  io.control.imm_src := 0.U
  io.control.reg_write := false.B
  io.control.alu_op := 0.U

  // or,lw,sw,beq
  val op = Wire(UInt(7.W))
  op := io.i_inst(6, 0)

  val branch = WireInit(Bool(), false.B)
  val alu_op = WireInit(UInt(2.W), 0.U)
  switch(op) {
    is("b0110011".U) {
      // R-type instruction (add, sub, etc.)
      branch := false.B
      io.control.result_src := false.B
      io.control.mem_write := false.B
      io.control.alu_src := false.B
      io.control.reg_write := true.B
      alu_op := "b10".U
    }
    is("b0000011".U) {
      // I-type instruction (lw, lb, etc.)
      branch := false.B
      io.control.result_src := true.B
      io.control.mem_write := false.B
      io.control.alu_src := true.B
      io.control.imm_src := 0.U
      io.control.reg_write := true.B
      alu_op := "b00".U
    }
    is("b0100011".U) {
      // S-type instruction (sw, sb, etc.)
      branch := false.B
      io.control.mem_write := true.B
      io.control.alu_src := true.B
      io.control.imm_src := "b11".U
      io.control.reg_write := false.B
      alu_op := "b00".U
    }
    is("b1100011".U) {
      // B-type instruction (beq, bge, etc.)
      branch := true.B
      io.control.mem_write := false.B
      io.control.alu_src := false.B
      io.control.imm_src := "b10".U
      io.control.reg_write := false.B
      alu_op := "b01".U
    }
  }

  io.control.pc_src := branch & io.i_zero

  // ALU decoding
  val funct3 = Wire(UInt(3.W))
  val funct7 = Wire(UInt(7.W))

  funct3 := io.i_inst(14, 12)
  funct7 := io.i_inst(31, 25)

  val r_alu_op = WireInit(UInt(3.W), 0.U)
  val weird_imm = Wire(
    UInt(2.W)
  ) // todo: pg.18 of chp. 7, dont understand this part
  weird_imm := Cat(op(5), funct7(5))

  when(funct3 === "b0".U && weird_imm < 3.U) {
    r_alu_op := 0.U // add
  }.elsewhen(funct3 === "b0".U && weird_imm === 3.U) {
    r_alu_op := 1.U // sub
  }.elsewhen(funct3 === "b10".U) {
    r_alu_op := 5.U // slt
  }.elsewhen(funct3 === "b110".U) {
    r_alu_op := 3.U // or
  }.elsewhen(funct3 === "b111".U) {
    r_alu_op := 4.U // and
  }.elsewhen(funct3 === "b100".U) {
    r_alu_op := 2.U // xor
  }

  switch(alu_op) {
    is("b00".U) {
      // add for finding addresses of loads and stores
      io.control.alu_op := "b000".U
    }
    is("b01".U) {
      // subtract to compare numbers for branches
      io.control.alu_op := "b001".U
    }
    is("b10".U) {
      // R-type alu instruction
      io.control.alu_op := r_alu_op
    }
  }
}
