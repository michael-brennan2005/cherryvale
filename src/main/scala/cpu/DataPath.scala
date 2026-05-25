package cpu

import chisel3._
import _root_.circt.stage.ChiselStage
import chisel3.util._

class DataPath extends Module {
  val io = IO(new Bundle {
    // For control unit - TODO: may be better to put refactor these into control signals
    val o_inst = Output(UInt(32.W))
    val o_zero = Output(Bool())

    val control = Flipped(new ControlSignals)

    val code = Flipped(new ReadPort)
    val data = Flipped(new ReadWritePort)

    // for debug
    val reg_file_ra = Input(UInt(5.W))
    val reg_file_rd = Output(UInt(32.W))
  })

  val pc = Module(new ProgramCounter)
  pc.reset := reset

  val register_file = Module(new RegisterFile)
  register_file.reset := reset

  val alu = Module(new Alu)

  val imm_ext = WireInit(UInt(32.W), 0.U)
  val pc_next = Wire(UInt(32.W))

  when(io.control.pc_src === true.B) {
    pc_next := pc.io.o_pc + imm_ext
  }.otherwise {
    pc_next := pc.io.o_pc + 4.U
  }
  pc.io.i_pc_next := pc_next

  io.o_inst := io.code.data

  // Mem port 1 is used for code, mem port 2 is used for data
  io.code.addr := pc.io.o_pc

  io.data.addr := alu.io.o_result
  io.data.w_data := register_file.io.o_rd_2
  io.data.w_en := io.control.mem_write

  register_file.io.i_ra_1 := io.code.data(19, 15)
  register_file.io.i_ra_2 := io.code.data(24, 20)
  register_file.io.i_wa := io.code.data(11, 7)
  register_file.io.i_w_en := io.control.reg_write

  register_file.io.i_ra_3 := io.reg_file_ra
  io.reg_file_rd := register_file.io.o_rd_3

  val result_src = Wire(UInt(32.W))
  result_src := Mux(
    io.control.result_src,
    io.data.data,
    alu.io.o_result
  )

  register_file.io.i_wd := result_src

  alu.io.i_src_a := register_file.io.o_rd_1

  // Selecting between immediate operand
  // TODO: move this into control unit, that in turn becomes a general "Decode" Unit
  switch(io.control.imm_src) {
    is("b00".U) { // I-type instruction
      imm_ext := io.code.data(31, 20).asSInt.pad(32).asUInt
    }
    is("b11".U) { // S-type instruction
      imm_ext := Cat(
        io.code.data(31, 25),
        io.code.data(11, 7)
      ).asSInt.pad(32).asUInt
    }
    is("b10".U) { // B-type instruction
      imm_ext := Cat(
        io.code.data(31),
        io.code.data(7),
        io.code.data(30, 25),
        io.code.data(11, 8),
        0.U
      ).asSInt.pad(32).asUInt
    }
  }

  // Selecting between immediate or register as 2nd alu operand
  val alu_src = Wire(UInt(32.W))
  when(io.control.alu_src === true.B) {
    alu_src := imm_ext // register_file.io.o_rd_2
  }.otherwise {
    alu_src := register_file.io.o_rd_2
  }

  alu.io.i_src_b := alu_src
  alu.io.i_control := io.control.alu_op
  io.o_zero := alu.io.o_zero
}
