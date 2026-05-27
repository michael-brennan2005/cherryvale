package cpu

import chisel3._
import _root_.circt.stage.ChiselStage

class Cpu extends Module {
  val io = IO(new Bundle {
    // TODO: debug should let you also set pc manually, i think this would omit needing reset for now
    val debug = Input(Bool())
    val mem_debug = new ReadWritePort

    val reg_debug_addr = Input(UInt(5.W))
    val reg_debug_data = Output(UInt(32.W))

    val led = Output(UInt(16.W))
    val sw = Input(UInt(16.W))
  })

  val control = Module(new ControlUnit)
  val data_path = Module(new DataPath)
  data_path.reset := reset

  // 256B memory, 1 read port (for code), 1 r/w port (for data)
  val memory = Module(new Memory(1))
  memory.reset := reset
  io.led := memory.io.led
  memory.io.sw := io.sw

  data_path.io.control <> control.io.control
  data_path.io.code <> memory.io.ro(0)

  when(io.debug) {
    memory.io.rw.addr := io.mem_debug.addr
    memory.io.rw.w_data := io.mem_debug.w_data
    memory.io.rw.w_en := io.mem_debug.w_en
  }.otherwise {
    memory.io.rw.addr := data_path.io.data.addr
    memory.io.rw.w_data := data_path.io.data.w_data
    memory.io.rw.w_en := data_path.io.data.w_en
  }
  io.mem_debug.data := memory.io.rw.data
  data_path.io.data.data := memory.io.rw.data

  data_path.io.reg_file_ra := io.reg_debug_addr
  io.reg_debug_data := data_path.io.reg_file_rd

  control.io.i_inst := data_path.io.o_inst
  control.io.i_zero := data_path.io.o_zero
}
