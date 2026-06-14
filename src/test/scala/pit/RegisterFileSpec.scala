package pit

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pit.RegisterFile

class RegisterFileSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "RegisterFile"

  it should "read back a value written to a register" in {
    simulate(new RegisterFile) { dut =>
      dut.io.i_wa.poke(5.U)
      dut.io.i_wd.poke("hDEADBEEF".U)
      dut.io.i_w_en.poke(true.B)
      dut.clock.step()

      dut.io.i_w_en.poke(false.B)
      dut.io.i_ra_1.poke(5.U)
      dut.clock.step()
      dut.io.o_rd_1.expect("hDEADBEEF".U)
    }
  }

  it should "expose both read ports independently" in {
    simulate(new RegisterFile) { dut =>
      // Populate reg 5 = 0xAAAAAAAA
      dut.io.i_wa.poke(5.U)
      dut.io.i_wd.poke("hAAAAAAAA".U)
      dut.io.i_w_en.poke(true.B)
      dut.clock.step()

      // Populate reg 10 = 0x55555555
      dut.io.i_wa.poke(10.U)
      dut.io.i_wd.poke("h55555555".U)
      dut.io.i_w_en.poke(true.B)
      dut.clock.step()

      dut.io.i_w_en.poke(false.B)
      dut.io.i_ra_1.poke(5.U)
      dut.io.i_ra_2.poke(10.U)
      dut.clock.step()
      dut.io.o_rd_1.expect("hAAAAAAAA".U)
      dut.io.o_rd_2.expect("h55555555".U)
    }
  }

  it should "not mutate a register when write-enable is low" in {
    simulate(new RegisterFile) { dut =>
      // Seed reg 7 with a known value
      dut.io.i_wa.poke(7.U)
      dut.io.i_wd.poke("h12345678".U)
      dut.io.i_w_en.poke(true.B)
      dut.clock.step()

      // Try to overwrite with we=false
      dut.io.i_wd.poke("hFFFFFFFF".U)
      dut.io.i_w_en.poke(false.B)
      dut.clock.step()

      dut.io.i_ra_1.poke(7.U)
      dut.clock.step()
      dut.io.o_rd_1.expect("h12345678".U)
    }
  }
}
