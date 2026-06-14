package pit

import chisel3._
import _root_.circt.stage.ChiselStage
import com.carlosedp.riscvassembler.RISCVAssembler

class MMCME2_BASE
    extends BlackBox(
      Map(
        "CLKIN1_PERIOD" -> DoubleParam(10.0), // 100 MHz input
        "DIVCLK_DIVIDE" -> IntParam(1), // D
        "CLKFBOUT_MULT_F" -> DoubleParam(7.5), // M  -> VCO = 750 MHz
        "CLKOUT0_DIVIDE_F" -> DoubleParam(10.0) // O  -> 75 MHz
      )
    ) {
  val io = IO(new Bundle {
    val CLKIN1 = Input(Clock())
    val RST = Input(Bool())
    val PWRDWN = Input(Bool())
    val CLKFBIN = Input(Clock())
    val CLKFBOUT = Output(Clock())
    val CLKOUT0 = Output(Clock())
    val LOCKED = Output(Bool())
  })
}

class BUFG extends BlackBox {
  val io = IO(new Bundle {
    val I = Input(Clock())
    val O = Output(Clock())
  })
}

class Top(memoryInit: Option[Seq[UInt]], debug_port: Boolean = true) extends Module {
  val io = IO(new Bundle {
    val led = Output(UInt(16.W))
    val sw = Input(UInt(16.W))
    val mem_debug = if (debug_port) { Some(new ReadPort) }
    else { None }
  })

  val mmcm = Module(new MMCME2_BASE)
  mmcm.io.CLKIN1 := clock
  mmcm.io.RST := reset.asBool
  mmcm.io.PWRDWN := false.B
  mmcm.io.CLKFBIN := mmcm.io.CLKFBOUT // internal feedback

  val clkbuf = Module(new BUFG)
  clkbuf.io.I := mmcm.io.CLKOUT0
  val cpuClock = clkbuf.io.O

  // hold CPU in reset until the MMCM locks
  val cpuReset = reset.asBool || !mmcm.io.LOCKED

  withClockAndReset(cpuClock, cpuReset) {
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
