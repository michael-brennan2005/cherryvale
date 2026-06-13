package elaborate

import chisel3._

import chisel3._
import _root_.circt.stage.ChiselStage

// Simple clock generator - Vivado artix fpga has 100MHz clock and we use this module to divide
// it down. New clock rate is (100MHz * mult) / divide Hz.
// TODO: probably broken
class ClockGen(mult: Double, divide: Double) extends Module {
  val io = IO(new Bundle {
    val clockOut = Output(Clock())
    val mmcmLock = Output(Bool())
  })

  require(mult >= 2.0 && mult <= 64.0, "per AMD spec - CLKFBOUT_MULT_F must be between 2-64")
  require(divide >= 1.0 && divide <= 128.0, "per AMD spec - CLKOUT0_DIVIDE_F must be between 1-128")
  class MMCME2_BASE
      extends BlackBox(
        Map(
          "CLKIN1_PERIOD" -> DoubleParam(10.0), // 100 MHz input
          "DIVCLK_DIVIDE" -> IntParam(1), // D
          "CLKFBOUT_MULT_F" -> DoubleParam(mult), // M
          "CLKOUT0_DIVIDE_F" -> DoubleParam(divide) // O
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

  val mmcm = Module(new MMCME2_BASE)
  mmcm.io.CLKIN1 := clock
  mmcm.io.RST := reset.asBool
  mmcm.io.PWRDWN := false.B
  mmcm.io.CLKFBIN := mmcm.io.CLKFBOUT // internal feedback (TODO: what that mean)

  val clkbuf = Module(new BUFG)
  clkbuf.io.I := mmcm.io.CLKOUT0

  io.clockOut := clkbuf.io.O
  io.mmcmLock := mmcm.io.LOCKED
}

object ClockGen {
  // Wrap a module so it runs on a clock derived from the current clock domain.
  // New clock rate is (currentClock * mult) / divide Hz. The wrapped module is
  // held in reset until the MMCM locks.
  //   val foo = ClockGen(mult = 8.0, divide = 4.0) { new Foo }
  def apply[T <: Module](mult: Double, divide: Double)(gen: => T): T = {
    val clkgen = Module(new ClockGen(mult, divide))
    withClockAndReset(clkgen.io.clockOut, !clkgen.io.mmcmLock) {
      Module(gen)
    }
  }
}
