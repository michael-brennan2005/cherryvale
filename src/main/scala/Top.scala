import chisel3._
import _root_.circt.stage.ChiselStage
import cpu.{Cpu, ReadWritePort}
import com.carlosedp.riscvassembler.RISCVAssembler

/** @param memFile path to a hex file loaded into instruction memory at boot.
  *                Used on FPGA via $readmemh.
  * @param exposeDebug when true, surfaces the CPU's debug + mem_debug ports out
  *                    of Top so tests (or external tools) can preload memory.
  *                    Set false in production / FPGA bitstream generation.
  */
class Top(memFile: String = "dbg.mem", exposeDebug: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val led = Output(UInt(16.W))
    val sw = Input(UInt(16.W))
    val debug = if (exposeDebug) Some(Input(Bool())) else None
    val mem_debug = if (exposeDebug) Some(new ReadWritePort) else None
  })

  val cpu = Module(new Cpu(256, memFile))

  io.led := cpu.io.led
  cpu.io.sw := io.sw
  cpu.io.reg_debug_addr := 0.U

  io.debug match {
    case Some(d) =>
      cpu.io.debug := d
      cpu.io.mem_debug.addr := io.mem_debug.get.addr
      cpu.io.mem_debug.w_data := io.mem_debug.get.w_data
      cpu.io.mem_debug.w_en := io.mem_debug.get.w_en
      io.mem_debug.get.data := cpu.io.mem_debug.data
    case None =>
      cpu.io.debug := false.B
      cpu.io.mem_debug.addr := 0.U
      cpu.io.mem_debug.w_data := 0.U
      cpu.io.mem_debug.w_en := false.B
  }
}
