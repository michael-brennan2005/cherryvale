import chisel3._
import _root_.circt.stage.ChiselStage
import cpu.Cpu

class Top extends Module {
  val io = IO(new Bundle {
    val led = Output(UInt(16.W))
    val sw = Input(UInt(16.W))
  })

  val cpu = Module(new Cpu(32))

  io.led := cpu.io.led
  cpu.io.sw := io.sw

  cpu.io.mem_debug.addr := 0.U
  cpu.io.mem_debug.w_data := 0.U
  cpu.io.mem_debug.w_en := false.B
  cpu.io.reg_debug_addr := 0.U
}
