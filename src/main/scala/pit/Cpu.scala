package pit

import chisel3._
import _root_.circt.stage.ChiselStage

class Cpu(memoryInit: Option[Seq[UInt]]) extends Module {
  val io = IO(new Bundle {
    val mem_debug = new ReadPort

    val reg_debug_addr = Input(UInt(5.W))
    val reg_debug_data = Output(UInt(32.W))

    val led = Output(UInt(16.W))
    val sw = Input(UInt(16.W))
  })

  val data_path = Module(new DataPath)
  val memory = Module(new Memory(2, memoryInit))
  data_path.reset := reset
  memory.reset := reset

  // 1 read port for code, 1 read port for debug/test
  data_path.io.code <> memory.io.ro(0)
  io.mem_debug <> memory.io.ro(1)

  io.led := memory.io.led
  memory.io.sw := io.sw

  data_path.io.data <> memory.io.rw

  data_path.io.reg_file_ra := io.reg_debug_addr
  io.reg_debug_data := data_path.io.reg_file_rd
}
