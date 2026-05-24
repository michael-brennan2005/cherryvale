package cpu

import chisel3._
import _root_.circt.stage.ChiselStage

class Cpu(memSizeBytes: Int) extends Module {
  val io = IO(new Bundle {
    val mem_debug = new ReadWritePort

    val reg_debug_addr = Input(UInt(5.W))
    val reg_debug_data = Output(UInt(32.W))
  })

  val control = Module(new ControlUnit)
  val data_path = Module(new DataPath)
  data_path.reset := reset

  // 256B memory, 1 read port (for code), 2 read/write ports (for data, for debug)
  val memory = Module(new Memory(memSizeBytes, 1, 2))
  memory.reset := reset

  data_path.io.control <> control.io.control
  data_path.io.code <> memory.io.r(0)

  // Hand-wire the data port so the write-enable can be gated by reset.
  // Memory is not reset-sensitive (it's a Mem), so during preload via
  // io.mem_debug we'd otherwise see spurious writes from whatever the
  // ControlUnit decodes out of address 0.
  memory.io.rw(0).addr := data_path.io.data.addr
  memory.io.rw(0).w_data := data_path.io.data.w_data
  memory.io.rw(0).w_en := data_path.io.data.w_en && !reset.asBool
  data_path.io.data.data := memory.io.rw(0).data

  io.mem_debug <> memory.io.rw(1)
  data_path.io.reg_file_ra := io.reg_debug_addr
  io.reg_debug_data := data_path.io.reg_file_rd

  control.io.i_inst := data_path.io.o_inst
  control.io.i_zero := data_path.io.o_zero
}
