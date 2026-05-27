import chisel3._
import _root_.circt.stage.ChiselStage
import cpu.{Cpu, ReadWritePort}
import com.carlosedp.riscvassembler.RISCVAssembler
import cpu.ReadPort

/** @param memFile
  *   path to a hex file loaded into instruction memory at boot. Used on FPGA
  *   via $readmemh.
  * @param exposeDebug
  *   when true, surfaces the CPU's debug + mem_debug ports out of Top so tests
  *   (or external tools) can preload memory. Set false in production / FPGA
  *   bitstream generation.
  */
class Top(memoryInit: Option[Seq[UInt]], debug_port: Boolean = true)
    extends Module {
  val io = IO(new Bundle {
    val led = Output(UInt(16.W))
    val sw = Input(UInt(16.W))
    val mem_debug = if (debug_port) { Some(new ReadPort) }
    else { None }
  })

  val clk_divide = RegInit(0.U(4.W))
  clk_divide := clk_divide + 1.U
  withClock(clk_divide(3).asClock) {
    val cpu = Module(new Cpu(memoryInit))
    io.led := cpu.io.led
    cpu.io.sw := io.sw

    cpu.io.reg_debug_addr := 0.U

    if (debug_port) {
      cpu.io.mem_debug <> io.mem_debug.get
    } else {
      cpu.io.mem_debug.addr := 0.U
    }
  }

}
