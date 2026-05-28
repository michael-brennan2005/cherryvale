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
  val register_file = Module(new RegisterFile)
  val alu = Module(new Alu)
  pc.reset := reset
  register_file.reset := reset

  val inst = io.code.data

  // format: off
  val inst_immediate = MuxLookup(io.control.immEncoding, 0.U(32.W))(
    Seq(
      ImmediateEncoding.iType -> inst(31, 20).asSInt.pad(32).asUInt,
      ImmediateEncoding.sType -> Cat(inst(31, 25), inst(11, 7)).asSInt.pad(32).asUInt,
      ImmediateEncoding.bType -> Cat(inst(31), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W)).asSInt.pad(32).asUInt,
      ImmediateEncoding.jType -> Cat(inst(31), inst(20), inst(30, 21), 0.U(1.W)).asSInt.pad(32).asUInt,
      ImmediateEncoding.uType -> Cat(inst(31, 12), Fill(12, "b0".U(1.W)))
    )
  )
  // format: on

  pc.io.i_pc_next := MuxLookup(io.control.pcSrc, pc.io.o_pc + 4.U)(
    Seq(
      PcSrc.branchImmediate -> (pc.io.o_pc + inst_immediate),
      PcSrc.plusFour -> (pc.io.o_pc + 4.U)
    )
  )

  // For control unit to decode
  io.o_inst := inst

  io.code.addr := pc.io.o_pc

  io.data.addr := alu.io.o_result
  io.data.w_data := register_file.io.o_rd_2
  io.data.w_en := io.control.writeToMem

  register_file.io.i_ra_1 := inst(19, 15)
  register_file.io.i_ra_2 := inst(24, 20)
  register_file.io.i_wa := inst(11, 7)
  register_file.io.i_w_en := io.control.writeToReg

  register_file.io.i_ra_3 := io.reg_file_ra
  io.reg_file_rd := register_file.io.o_rd_3

  register_file.io.i_wd := MuxLookup(io.control.regFileWriteSrc, 0.U(32.W))(
    Seq(
      RegFileWriteSrc.data -> io.data.data,
      RegFileWriteSrc.aluResult -> alu.io.o_result,
      RegFileWriteSrc.pcPlusFour -> (pc.io.o_pc + 4.U),
      RegFileWriteSrc.immediate -> inst_immediate
    )
  )

  alu.io.i_src_a := register_file.io.o_rd_1
  alu.io.i_src_b := MuxLookup(
    io.control.alu2ndOperand,
    register_file.io.o_rd_2
  )(
    Seq(
      Alu2ndOperand.immediate -> inst_immediate,
      Alu2ndOperand.registerValue -> register_file.io.o_rd_2
    )
  )
  alu.io.i_control := io.control.alu_op
  io.o_zero := alu.io.o_zero
}
